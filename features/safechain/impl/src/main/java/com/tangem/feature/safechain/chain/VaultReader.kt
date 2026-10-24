package com.tangem.feature.safechain.chain

import java.math.BigInteger

internal const val ZERO_ADDRESS = "0x0000000000000000000000000000000000000000"

/** Amounts per token, keyed by the token's lowercase address. */
internal typealias Amounts = Map<String, BigInteger>

internal fun Amounts.of(token: String): BigInteger = this[token.lowercase()] ?: BigInteger.ZERO

/** Per token, the smallest amount any reading reported (a token missing from a reading counts as 0). */
internal fun List<Amounts>.minPerToken(): Amounts =
    flatMap { it.keys }.toSet().associateWith { k -> minOf { it[k] ?: BigInteger.ZERO } }

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
    /** Stopped by a lock but still holding its reservation until releaseVoided (shown as state 4). */
    val voided: Boolean = false,
    val disputed: Boolean = false,
)

/** SavingsVault.pendingPolicyChange(): kind 1 = new recovery key, 2 = new safe haven, 3 = shorter delay. */
internal data class PolicyView(val kind: Int, val proposer: String, val approved: Boolean, val eta: Long, val newAddress: String)

internal data class VaultView(
    val address: String,
    val primary: String,
    val recovery: String,
    val haven: String,
    val delaySeconds: Long,
    val locked: Boolean,
    /** Per token: what the vault holds, and what isn't reserved by withdrawals on their way out. */
    val balances: Amounts,
    val available: Amounts,
    val pendingPrimary: String,
    val previousPrimary: String,
    val vetoUntil: Long,
    val withdrawals: List<WithdrawalView>,
    /** Proposal numbers so far; the next proposal is [policyNonce] + 1. */
    val policyNonce: BigInteger = BigInteger.ZERO,
    /** A security change waiting for approval or for its delay, or null. */
    val policy: PolicyView? = null,
    /** Newest first, at most [VaultReader.MAX_ACTIVITY]. */
    val activity: List<ActivityView> = emptyList(),
    val disputed: Boolean = false,
) {
    val holdsAnything: Boolean get() = balances.values.any { it.signum() > 0 }
}

/** ProtectedTransferEscrow.paymentState: 1 pending (cancellable), 2 final (awaiting settlement), 3 settled, 4 cancelled. */
internal data class PaymentView(
    val id: BigInteger,
    val sender: String,
    val recipient: String,
    val token: String,
    val amount: BigInteger,
    val createdAt: Long,
    val deadline: Long,
    val state: Int,
    val disputed: Boolean = false,
)

internal data class AccountSnapshot(
    val address: String,
    val ethBalance: BigInteger,
    /** Checking: each token the card holds. */
    val tokenBalances: Amounts,
    val vaults: List<VaultView>,
    val chainTime: Long,
    /** Protected payments this account sent or receives through the escrow, newest first. */
    val payments: List<PaymentView> = emptyList(),
    /** Checking's own history: instant sends and receipts, and its protected payments. */
    val checkingActivity: List<ActivityView> = emptyList(),
    /** Per token, what the escrow may take from Checking (a one-time approval per token enables protected payments). */
    val escrowAllowances: Amounts = emptyMap(),
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
                tokenBalances = all.map { it.tokenBalances }.minPerToken(),
                escrowAllowances = all.map { it.escrowAllowances }.minPerToken(),
                payments = all.flatMap { it.payments }.groupBy { it.id }.values.map { ps ->
                    ps.first().copy(
                        state = ps.minByOrNull { STATE_URGENCY.indexOf(it.state) }!!.state,
                        deadline = ps.minOf { it.deadline },
                        amount = ps.maxOf { it.amount },
                        disputed = ps.size < all.size || ps.toSet().size > 1,
                    )
                }.sortedByDescending { it.id },
                checkingActivity = all.flatMap { it.checkingActivity }.distinctBy { it.txHash to it.logIndex }
                    .sortedWith(compareByDescending<ActivityView> { it.block }.thenByDescending { it.logIndex }),
                vaults = byVault.values.map { versions -> mergeVault(versions, reportedByAll = versions.size == all.size) },
            )
        }

        val STATE_URGENCY = listOf(1, 2, 3, 4, 0)

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
                    voided = ws.any { it.voided },
                    disputed = ws.size < versions.size || ws.toSet().size > 1,
                )
            }.sortedByDescending { it.id }
            return v.copy(
                locked = versions.all { it.locked },
                balances = versions.map { it.balances }.minPerToken(),
                available = versions.map { it.available }.minPerToken(),
                pendingPrimary = versions.firstOrNull { it.pendingPrimary != ZERO_ADDRESS }?.pendingPrimary ?: ZERO_ADDRESS,
                vetoUntil = versions.map { it.vetoUntil }.filter { it > 0 }.minOrNull() ?: 0,
                withdrawals = withdrawals,
                // A change any endpoint reports is shown; the contract decides whether it can go through.
                policy = versions.firstNotNullOfOrNull { it.policy },
                policyNonce = versions.maxOf { it.policyNonce },
                // Union of what any endpoint reported (by transaction and log position), newest first.
                activity = versions.flatMap { it.activity }.distinctBy { it.txHash to it.logIndex }
                    .sortedWith(compareByDescending<ActivityView> { it.block }.thenByDescending { it.logIndex }),
                disputed = !reportedByAll || versions.toSet().size > 1,
            )
        }
    }
}

/** Reads one account's SafeChain state from one endpoint at one block. */
internal class VaultReader(
    private val tokens: List<String> = SepoliaConfig.TOKENS.map { it.address },
    private val factory: String = SepoliaConfig.FACTORY,
    private val escrow: String = SepoliaConfig.ESCROW,
) {

    suspend fun snapshot(rpc: EthRpc, block: BlockHeader, account: String): AccountSnapshot {
        val n = block.number
        val vaults = findVaults(rpc, n, account).mapNotNull { readVault(rpc, n, it, account, block.timestamp) }
        val me = topicOf(account)
        val sent = logs(rpc, listOf(escrow), listOf(PAYMENT_CREATED, null, me), n)
        val received = logs(rpc, listOf(escrow), listOf(PAYMENT_CREATED, null, null, me), n)
        val payments = (sent + received).distinctBy { it.topics[1] }
            .sortedByDescending { Hex.toBigInteger(it.topics[1]) }
            .take(MAX_PAYMENTS)
            .map { readPayment(rpc, n, Hex.toBigInteger(it.topics[1])) }
        return AccountSnapshot(
            address = account,
            ethBalance = rpc.balance(account, n),
            tokenBalances = perToken { Abi.uint(rpc.call(it, SafeChainCalls.balanceOf(account), n)) },
            vaults = vaults,
            chainTime = block.timestamp,
            payments = payments,
            checkingActivity = checkingActivity(rpc, n, account, vaults.map { it.address }, sent, received),
            escrowAllowances = perToken { Abi.uint(rpc.call(it, SafeChainCalls.allowance(account, escrow), n)) },
        )
    }

    private inline fun perToken(read: (String) -> BigInteger): Amounts = tokens.associate { it.lowercase() to read(it) }

    private suspend fun readPayment(rpc: EthRpc, n: BigInteger, id: BigInteger): PaymentView {
        val w = Abi.words(rpc.call(escrow, SafeChainCalls.getPayment(id), n))
        return PaymentView(
            id = id,
            sender = Abi.address(w[0]),
            createdAt = Abi.uint(w[1]).toLong(),
            recipient = Abi.address(w[2]),
            deadline = Abi.uint(w[3]).toLong(),
            token = Abi.address(w[5]),
            amount = Abi.uint(w[6]),
            state = Abi.uint(rpc.call(escrow, SafeChainCalls.paymentState(id), n)).toInt(),
        )
    }

    /**
     * Checking's history: protected payments it sent or receives (and their cancellation or delivery), plus
     * instant token transfers. Transfers to or from its own vaults and the escrow are left out: Savings'
     * activity and the payment entries already tell those.
     */
    @Suppress("LongParameterList")
    private suspend fun checkingActivity(
        rpc: EthRpc,
        n: BigInteger,
        account: String,
        vaults: List<String>,
        sent: List<LogEntry>,
        received: List<LogEntry>,
    ): List<ActivityView> {
        val me = topicOf(account)
        val own = (vaults + escrow).map { it.lowercase() }.toSet()
        fun amountWord(log: LogEntry, i: Int) = Abi.words(Hex.decode(log.data)).getOrNull(i)?.let(Abi::uint)
        fun base(kind: ActivityView.Kind, log: LogEntry) = ActivityView(kind, log.blockNumber, log.txHash, log.logIndex)

        fun tokenWord(log: LogEntry) = Abi.words(Hex.decode(log.data)).firstOrNull()?.let(Abi::address)
        val created = sent.map { base(ActivityView.Kind.PaymentSent, it).copy(withdrawalId = Hex.toBigInteger(it.topics[1]), token = tokenWord(it), amount = amountWord(it, 1), address = addressFromTopic(it.topics[3]), by = account) } +
            received.map { base(ActivityView.Kind.PaymentReceived, it).copy(withdrawalId = Hex.toBigInteger(it.topics[1]), token = tokenWord(it), amount = amountWord(it, 1), address = addressFromTopic(it.topics[2])) }
        val tokenOf = created.associate { it.withdrawalId to it.token }
        val ids = created.mapNotNull { it.withdrawalId }.toSet()
        val outcomes = if (ids.isEmpty()) {
            emptyList()
        } else {
            logs(rpc, listOf(escrow), listOf(PAYMENT_CANCELLED), n).map { ActivityView.Kind.PaymentCancelled to it } +
                logs(rpc, listOf(escrow), listOf(PAYMENT_SETTLED), n).map { ActivityView.Kind.PaymentSettled to it }
        }.mapNotNull { (kind, log) ->
            val id = Hex.toBigInteger(log.topics[1])
            if (id in ids) base(kind, log).copy(withdrawalId = id, token = tokenOf[id], by = log.topics.getOrNull(2)?.let(::addressFromTopic)) else null
        }
        val transfersOut = logs(rpc, tokens, listOf(ERC20_TRANSFER, me), n)
            .filter { addressFromTopic(it.topics[2]).lowercase() !in own }
            .map { base(ActivityView.Kind.CheckingSent, it).copy(token = it.address, amount = amountWord(it, 0), address = addressFromTopic(it.topics[2]), by = account) }
        val transfersIn = logs(rpc, tokens, listOf(ERC20_TRANSFER, null, me), n)
            .filter { addressFromTopic(it.topics[1]).lowercase() !in own }
            .map { base(ActivityView.Kind.CheckingReceived, it).copy(token = it.address, amount = amountWord(it, 0), address = addressFromTopic(it.topics[1])) }
        return (created + outcomes + transfersOut + transfersIn)
            .sortedWith(compareByDescending<ActivityView> { it.block }.thenByDescending { it.logIndex })
            .take(MAX_ACTIVITY)
    }

    /**
     * Vaults whose history names [account], oldest first (the order they were created, then any the account
     * joined later by a key rotation): VaultCreated (primary or recovery) from the factory, and key
     * rotations asked only of the factory's own vaults (public RPCs refuse address-less log queries).
     */
    private suspend fun findVaults(rpc: EthRpc, to: BigInteger, account: String): List<String> {
        val me = topicOf(account)
        val created = logs(rpc, listOf(factory), listOf(VAULT_CREATED), to)
        val candidates = created.filter { it.topics.getOrNull(2) == me || it.topics.getOrNull(3) == me }
            .map { addressFromTopic(it.topics[1]) }
            .toCollection(LinkedHashSet())
        val allVaults = created.map { addressFromTopic(it.topics[1]) }
        for (batch in allVaults.chunked(VAULTS_PER_QUERY)) {
            logs(rpc, batch, listOf(PRIMARY_REPLACED, null, me), to).forEach { candidates += checksumAddress(it.address) }
            logs(rpc, batch, listOf(PRIMARY_REPLACED, me), to).forEach { candidates += checksumAddress(it.address) }
            logs(rpc, batch, listOf(RECOVERY_REPLACED, null, me), to).forEach { candidates += checksumAddress(it.address) }
        }
        return candidates.filter { Abi.bool(rpc.call(factory, SafeChainCalls.isVault(it), to)) }
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
            val state = Abi.uint(word(SafeChainCalls.withdrawalState(id))).toInt()
            WithdrawalView(
                id = id,
                token = Abi.address(w[0]),
                requestedAt = Abi.uint(w[1]).toLong(),
                to = Abi.address(w[2]),
                deadline = Abi.uint(w[3]).toLong(),
                initiator = Abi.address(w[4]),
                toHaven = Abi.bool(w[7]),
                amount = Abi.uint(w[8]),
                state = state,
                voided = state == STATE_CANCELLED && Abi.bool(word(SafeChainCalls.isVoid(id))),
            )
        }
        return VaultView(
            address = vault,
            primary = primary,
            recovery = recovery,
            haven = Abi.address(word(SafeChainCalls.view("haven"))),
            delaySeconds = Abi.uint(word(SafeChainCalls.view("withdrawalDelay"))).toLong(),
            locked = Abi.bool(word(SafeChainCalls.view("locked"))),
            balances = perToken { Abi.uint(rpc.call(it, SafeChainCalls.balanceOf(vault), n)) },
            available = perToken { Abi.uint(word(SafeChainCalls.available(it))) },
            pendingPrimary = Abi.address(word(SafeChainCalls.view("pendingPrimary"))),
            previousPrimary = previous,
            vetoUntil = vetoUntil,
            withdrawals = withdrawals,
            policyNonce = Abi.uint(word(SafeChainCalls.view("policyNonce"))),
            policy = Abi.words(word(SafeChainCalls.pendingPolicyChange())).let { p ->
                val kind = Abi.uint(p[0]).toInt()
                if (kind == 0) {
                    null
                } else {
                    PolicyView(kind, Abi.address(p[1]), Abi.bool(p[2]), Abi.uint(p[3]).toLong(), Abi.address(p[4]))
                }
            },
            activity = activity(rpc, vault, n)
                .sortedWith(compareByDescending<ActivityView> { it.block }.thenByDescending { it.logIndex })
                .take(MAX_ACTIVITY),
        )
    }

    /**
     * The vault's own events, plus token transfers into it: a plain transfer counts as a deposit but emits no
     * Deposited event (the app's one-tap Add is such a transfer). A transfer whose transaction already has a
     * Deposited event (the deposit() path) isn't listed twice.
     */
    private suspend fun activity(rpc: EthRpc, vault: String, n: BigInteger): List<ActivityView> {
        val own = logs(rpc, listOf(vault), emptyList(), n).mapNotNull(ActivityView::decode)
        val deposits = own.filter { it.kind == ActivityView.Kind.Deposited }.map { it.txHash.lowercase() }.toSet()
        val transfersIn = logs(rpc, tokens, listOf(ERC20_TRANSFER, null, topicOf(vault)), n)
            .filter { it.txHash.lowercase() !in deposits }
            .map { log ->
                ActivityView(
                    kind = ActivityView.Kind.Deposited,
                    block = log.blockNumber,
                    txHash = log.txHash,
                    logIndex = log.logIndex,
                    token = log.address,
                    amount = Abi.words(Hex.decode(log.data)).firstOrNull()?.let(Abi::uint),
                    by = log.topics.getOrNull(1)?.let(::addressFromTopic),
                )
            }
        return own + transfersIn
    }

    companion object {
        private val LOG_CHUNK: BigInteger = BigInteger.valueOf(10_000)
        private const val VAULTS_PER_QUERY = 200
        /** The newest withdrawals shown per vault (bounded work per refresh). */
        const val MAX_WITHDRAWALS = 25
        const val MAX_ACTIVITY = 30
        private const val STATE_CANCELLED = 4

        val VAULT_CREATED = eventTopic("VaultCreated(address,address,address,uint64,bytes32)")
        val PRIMARY_REPLACED = eventTopic("PrimaryReplaced(address,address)")
        val RECOVERY_REPLACED = eventTopic("RecoveryReplaced(address,address)")
        val ERC20_TRANSFER = eventTopic("Transfer(address,address,uint256)")
        val PAYMENT_CREATED = eventTopic("PaymentCreated(uint256,address,address,address,uint256,uint64)")
        val PAYMENT_CANCELLED = eventTopic("PaymentCancelled(uint256,address)")
        val PAYMENT_SETTLED = eventTopic("PaymentSettled(uint256,address)")
        const val MAX_PAYMENTS = 25

        fun eventTopic(signature: String) = Hex.encode(signature.toByteArray(Charsets.US_ASCII).keccak())
        fun topicOf(address: String) = "0x" + "0".repeat(24) + address.removePrefix("0x").lowercase()
        fun addressFromTopic(topic: String) = checksumAddress("0x" + topic.removePrefix("0x").takeLast(40))
    }
}
