package com.tangem.feature.safechain.chain

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.math.BigInteger

internal class CrossCheckTest {

    /** A fake endpoint: a head, a chain name (block hashes), and what it reports. */
    private class FakeRpc(
        override val url: String,
        val head: Long = 100,
        val chain: String = "main",
        val down: Boolean = false,
    ) : EthRpc {
        override suspend fun blockNumber(): BigInteger = if (down) throw RpcError("down") else BigInteger.valueOf(head)
        override suspend fun block(number: BigInteger?) =
            BlockHeader(number ?: BigInteger.valueOf(head), "0x$chain-${number ?: head}", 1_000, BigInteger.ONE)
        override suspend fun call(to: String, data: ByteArray, block: BigInteger) = ByteArray(0)
        override suspend fun balance(address: String, block: BigInteger): BigInteger = BigInteger.ZERO
        override suspend fun logs(addresses: List<String>, topics: List<String?>, from: BigInteger, to: BigInteger) = emptyList<LogEntry>()
        override suspend fun nonce(address: String): BigInteger = BigInteger.ZERO
        override suspend fun estimateGas(from: String, to: String, data: ByteArray, value: BigInteger): BigInteger = BigInteger.ONE
        override suspend fun maxPriorityFee(): BigInteger = BigInteger.ONE
        override suspend fun sendRaw(raw: ByteArray) = "0x"
        override suspend fun receipt(txHash: String): Receipt? = null
    }

    private fun w(id: Long, state: Int, deadline: Long = 2_000, toHaven: Boolean = false, amount: Long = 10) = WithdrawalView(
        id = BigInteger.valueOf(id), token = "t", to = "x", amount = BigInteger.valueOf(amount), requestedAt = 0,
        deadline = deadline, initiator = "i", toHaven = toHaven, state = state,
    )

    private fun account(vararg ws: WithdrawalView, locked: Boolean = false, pendingPrimary: String = ZERO_ADDRESS) =
        AccountSnapshot(
            address = "me", ethBalance = BigInteger.TEN, tokenBalances = mapOf("t" to BigInteger.TEN), chainTime = 1_000,
            vaults = listOf(
                VaultView(
                    address = "0xvault", primary = "me", recovery = "r", haven = ZERO_ADDRESS, delaySeconds = 86_400,
                    locked = locked, balances = mapOf("t" to BigInteger.TEN), available = mapOf("t" to BigInteger.TEN), pendingPrimary = pendingPrimary,
                    previousPrimary = ZERO_ADDRESS, vetoUntil = 0, withdrawals = ws.toList(),
                ),
            ),
        )

    @Test
    fun `GIVEN two agreeing endpoints WHEN read THEN Agree, pinned to the lower head`() = runTest {
        val seen = mutableListOf<BigInteger>()
        val r = CrossCheckedReader(listOf(FakeRpc("https://a", head = 100), FakeRpc("https://b", head = 98)))
            .read(merge = { it.first() }) { _, b -> seen += b.number; "same" }
        assertThat(r.health).isEqualTo(RpcHealth.Agree)
        assertThat(seen).containsExactly(BigInteger.valueOf(98), BigInteger.valueOf(98))
    }

    @Test
    fun `GIVEN same data on two different chains WHEN read THEN Disagree`() = runTest {
        val r = CrossCheckedReader(listOf(FakeRpc("https://a", chain = "main"), FakeRpc("https://b", chain = "fork")))
            .read(merge = { it.first() }) { _, _ -> "same" }
        assertThat(r.health).isInstanceOf(RpcHealth.Disagree::class.java)
    }

    @Test
    fun `GIVEN one endpoint down WHEN read THEN Degraded and the other is used`() = runTest {
        val r = CrossCheckedReader(listOf(FakeRpc("https://a", down = true), FakeRpc("https://b")))
            .read(merge = { it.first() }) { e, _ -> e.url }
        assertThat(r.health).isInstanceOf(RpcHealth.Degraded::class.java)
        assertThat(r.value).isEqualTo("https://b")
    }

    @Test
    fun `GIVEN a stale endpoint WHEN read THEN it is excluded, not used to hold the screen back`() = runTest {
        val r = CrossCheckedReader(listOf(FakeRpc("https://a", head = 500), FakeRpc("https://b", head = 100)))
            .read(merge = { it.first() }) { e, b -> "${e.url}@${b.number}" }
        assertThat(r.value).isEqualTo("https://a@500")
        assertThat(r.health).isInstanceOf(RpcHealth.Degraded::class.java)
    }

    @Test
    fun `GIVEN one endpoint hides a theft WHEN merged THEN the withdrawal is shown pending and disputed`() = runTest {
        val honest = account(w(3, state = 1))
        val lying = account()
        val endpoints = listOf(FakeRpc("https://a"), FakeRpc("https://b"))
        var i = 0
        val r = CrossCheckedReader(endpoints).read(merge = AccountSnapshot::mergeUrgent) { _, _ -> if (i++ == 0) honest else lying }
        assertThat(r.health).isInstanceOf(RpcHealth.Disagree::class.java)
        val shown = r.value.vaults.single().withdrawals.single()
        assertThat(shown.state).isEqualTo(1)
        assertThat(shown.disputed).isTrue()
    }

    @Test
    fun `GIVEN conflicting readings WHEN merged THEN the most urgent one wins`() {
        val merged = AccountSnapshot.mergeUrgent(
            listOf(
                account(w(1, state = 2, deadline = 5_000, toHaven = true, amount = 1), locked = true),
                account(w(1, state = 1, deadline = 3_000, toHaven = false, amount = 9), pendingPrimary = "0xthief"),
            ),
        )
        val v = merged.vaults.single()
        val wd = v.withdrawals.single()
        assertThat(wd.state).isEqualTo(1) // still cancellable
        assertThat(wd.deadline).isEqualTo(3_000) // act by the earliest deadline
        assertThat(wd.amount).isEqualTo(BigInteger.valueOf(9)) // largest amount at risk
        assertThat(wd.toHaven).isFalse() // a disputed haven move is an ordinary withdrawal
        assertThat(v.locked).isFalse() // keep the Lock action available
        assertThat(v.pendingPrimary).isEqualTo("0xthief") // a scheduled takeover is shown
    }

    @Test
    fun `GIVEN balances that differ per token WHEN merged THEN each token shows the lower reading`() {
        val a = mapOf("usdc" to BigInteger.valueOf(5), "pw" to BigInteger.valueOf(9))
        val b = mapOf("usdc" to BigInteger.valueOf(7))
        val merged = listOf(a, b).minPerToken()
        assertThat(merged).containsExactly("usdc", BigInteger.valueOf(5), "pw", BigInteger.ZERO)
    }
}
