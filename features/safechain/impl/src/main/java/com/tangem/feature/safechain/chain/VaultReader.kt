package com.tangem.feature.safechain.chain

import java.math.BigInteger

internal const val ZERO_ADDRESS = "0x0000000000000000000000000000000000000000"

/** Mirrors SavingsVault.State: 1 pending (cancellable), 2 final (awaiting execution), 3 completed, 4 cancelled. */
internal data class WithdrawalView(
    val id: BigInteger,
    val token: String,
    val to: String,
    val amount: BigInteger,
    val requestedAt: Long,
    val deadline: Long,
    val initiator: String,
    val toHaven: Boolean,
    val state: Int,
    val disputed: Boolean = false,
)

internal data class VaultView(
    val address: String,
    val primary: String,
    val recovery: String,
    val haven: String,
    val delaySeconds: Long,
    val locked: Boolean,
    val balance: BigInteger,
    val available: BigInteger,
    val pendingPrimary: String,
    val previousPrimary: String,
    val vetoUntil: Long,
    val withdrawals: List<WithdrawalView>,
    val disputed: Boolean = false,
)

internal data class AccountSnapshot(
    val address: String,
    val ethBalance: BigInteger,
    val tokenBalance: BigInteger,
    val vaults: List<VaultView>,
    val chainTime: Long,
) {
    companion object {
        /**
         * Endpoints disagree: keep everything any of them reported and show the reading that asks the owner
         * to act — the same rules as the web wallet's crosscheck.ts. Never a quieter reading.
         */
        fun mergeUrgent(all: List<AccountSnapshot>): AccountSnapshot {
            val first = all.first()
            val byVault = all.flatMap { it.vaults }.groupBy { it.address.lowercase() }
            return first.copy(
                ethBalance = all.minOf { it.ethBalance },
                tokenBalance = all.minOf { it.tokenBalance },
                vaults = byVault.values.map { versions -> mergeVault(versions, reportedByAll = versions.size == all.size) },
            )
        }

        private val STATE_URGENCY = listOf(1, 2, 3, 4, 0)

        private fun mergeVault(versions: List<VaultView>, reportedByAll: Boolean): VaultView {
            val v = versions.first()
            val ids = versions.flatMap { it.withdrawals }.groupBy { it.id }
            val withdrawals = ids.values.map { ws ->
                val w = ws.first()
                w.copy(
                    state = ws.minByOrNull { STATE_URGENCY.indexOf(it.state) }!!.state,
                    deadline = ws.minOf { it.deadline },
                    amount = ws.maxOf { it.amount },
                    toHaven = ws.all { it.toHaven },
                    disputed = ws.size < versions.size || ws.toSet().size > 1,
                )
            }.sortedByDescending { it.id }
            return v.copy(
                locked = versions.all { it.locked },
                balance = versions.minOf { it.balance },
                available = versions.minOf { it.available },
                pendingPrimary = versions.firstOrNull { it.pendingPrimary != ZERO_ADDRESS }?.pendingPrimary ?: ZERO_ADDRESS,
                vetoUntil = versions.map { it.vetoUntil }.filter { it > 0 }.minOrNull() ?: 0,
                withdrawals = withdrawals,
                disputed = !reportedByAll || versions.toSet().size > 1,
            )
        }
    }
}

/** Reads one account's SafeChain state from one endpoint at one block. */
internal class VaultReader(private val token: String = SepoliaConfig.TOKEN, private val factory: String = SepoliaConfig.FACTORY) {

    suspend fun snapshot(rpc: EthRpc, block: BlockHeader, account: String): AccountSnapshot {
        val n = block.number
        val vaults = findVaults(rpc, n, account).mapNotNull { readVault(rpc, n, it, account, block.timestamp) }
        return AccountSnapshot(
            address = account,
            ethBalance = rpc.balance(account, n),
            tokenBalance = Abi.uint(rpc.call(token, SafeChainCalls.balanceOf(account), n)),
            vaults = vaults,
            chainTime = block.timestamp,
        )
    }

    /**
     * Vaults whose history names [account]: VaultCreated (primary or recovery) from the factory, and key
     * rotations asked only of the factory's own vaults (public RPCs refuse address-less log queries).
     */
    private suspend fun findVaults(rpc: EthRpc, to: BigInteger, account: String): List<String> {
        val me = topicOf(account)
        val created = logs(rpc, listOf(factory), listOf(VAULT_CREATED), to)
        val candidates = created.filter { it.topics.getOrNull(2) == me || it.topics.getOrNull(3) == me }
            .map { addressFromTopic(it.topics[1]) }
            .toMutableSet()
        val allVaults = created.map { addressFromTopic(it.topics[1]) }
        for (batch in allVaults.chunked(VAULTS_PER_QUERY)) {
            logs(rpc, batch, listOf(PRIMARY_REPLACED, null, me), to).forEach { candidates += checksumAddress(it.address) }
            logs(rpc, batch, listOf(PRIMARY_REPLACED, me), to).forEach { candidates += checksumAddress(it.address) }
            logs(rpc, batch, listOf(RECOVERY_REPLACED, null, me), to).forEach { candidates += checksumAddress(it.address) }
        }
        return candidates.filter { Abi.bool(rpc.call(factory, SafeChainCalls.isVault(it), to)) }.sorted()
    }

    private suspend fun logs(rpc: EthRpc, addresses: List<String>, topics: List<String?>, to: BigInteger): List<LogEntry> {
        val out = mutableListOf<LogEntry>()
        var from = BigInteger.valueOf(SepoliaConfig.START_BLOCK)
        while (from <= to) {
            val end = (from + LOG_CHUNK - BigInteger.ONE).min(to)
            out += rpc.logs(addresses, topics, from, end)
            from = end + BigInteger.ONE
        }
        return out
    }

    private suspend fun readVault(rpc: EthRpc, n: BigInteger, vault: String, account: String, now: Long): VaultView? {
        suspend fun word(data: ByteArray) = rpc.call(vault, data, n)
        val primary = Abi.address(word(SafeChainCalls.view("primary")))
        val recovery = Abi.address(word(SafeChainCalls.view("recovery")))
        val previous = Abi.address(word(SafeChainCalls.view("previousPrimary")))
        val vetoUntil = Abi.uint(word(SafeChainCalls.view("previousPrimaryVetoUntil"))).toLong()
        val me = account.lowercase()
        val isMine = primary.lowercase() == me || recovery.lowercase() == me ||
            (previous.lowercase() == me && now < vetoUntil) // a replaced key still sees it while it can veto
        if (!isMine) return null

        val count = Abi.uint(word(SafeChainCalls.view("withdrawalCount")))
        val ids = generateSequence(count) { it - BigInteger.ONE }.takeWhile { it >= BigInteger.ONE }.take(MAX_WITHDRAWALS).toList()
        val withdrawals = ids.map { id ->
            val w = Abi.words(word(SafeChainCalls.getWithdrawal(id)))
            WithdrawalView(
                id = id,
                token = Abi.address(w[0]),
                requestedAt = Abi.uint(w[1]).toLong(),
                to = Abi.address(w[2]),
                deadline = Abi.uint(w[3]).toLong(),
                initiator = Abi.address(w[4]),
                toHaven = Abi.bool(w[7]),
                amount = Abi.uint(w[8]),
                state = Abi.uint(word(SafeChainCalls.withdrawalState(id))).toInt(),
            )
        }
        return VaultView(
            address = vault,
            primary = primary,
            recovery = recovery,
            haven = Abi.address(word(SafeChainCalls.view("haven"))),
            delaySeconds = Abi.uint(word(SafeChainCalls.view("withdrawalDelay"))).toLong(),
            locked = Abi.bool(word(SafeChainCalls.view("locked"))),
            balance = Abi.uint(rpc.call(token, SafeChainCalls.balanceOf(vault), n)),
            available = Abi.uint(word(SafeChainCalls.available(token))),
            pendingPrimary = Abi.address(word(SafeChainCalls.view("pendingPrimary"))),
            previousPrimary = previous,
            vetoUntil = vetoUntil,
            withdrawals = withdrawals,
        )
    }

    companion object {
        private val LOG_CHUNK: BigInteger = BigInteger.valueOf(10_000)
        private const val VAULTS_PER_QUERY = 200
        /** The newest withdrawals shown per vault (bounded work per refresh). */
        const val MAX_WITHDRAWALS = 25

        val VAULT_CREATED = eventTopic("VaultCreated(address,address,address,uint64,bytes32)")
        val PRIMARY_REPLACED = eventTopic("PrimaryReplaced(address,address)")
        val RECOVERY_REPLACED = eventTopic("RecoveryReplaced(address,address)")

        fun eventTopic(signature: String) = Hex.encode(signature.toByteArray(Charsets.US_ASCII).keccak())
        fun topicOf(address: String) = "0x" + "0".repeat(24) + address.removePrefix("0x").lowercase()
        fun addressFromTopic(topic: String) = checksumAddress("0x" + topic.removePrefix("0x").takeLast(40))
    }
}
