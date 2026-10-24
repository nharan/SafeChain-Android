package com.tangem.feature.safechain.chain

import kotlinx.coroutines.delay
import java.math.BigInteger

/**
 * Builds, signs and broadcasts one SafeChain transaction. The signer is the card; everything else comes
 * from the RPC endpoints. The contract is the authority: the call is simulated first (eth_estimateGas
 * reverts with the contract's reason), and the receipt is checked to have come from the card's address.
 */
/** Broadcast, but no receipt yet: it may still be included, so it must not be sent again. */
internal class NotMinedYet(val txHash: String) : Exception("Sent, not included yet ($txHash)")

internal class TxSender(private val endpoints: List<EthRpc>, private val chainId: Long = SepoliaConfig.CHAIN_ID) {

    data class Prepared(val tx: Eip1559Tx, val maxCostWei: BigInteger)

    /**
     * [nonceOffset] > 0 prepares a transaction to follow others from the same sender that aren't sent yet
     * (signed in the same card tap). [value] is ETH in wei, for funding the phone's backup key.
     */
    suspend fun prepare(from: String, to: String, data: ByteArray, value: BigInteger = BigInteger.ZERO, nonceOffset: Int = 0): Prepared {
        val rpc = endpoints.first()
        // Simulate against every endpoint that answers: if any says it reverts, don't ask for a tap.
        val estimates = endpoints.map { runCatching { it.estimateGas(from, to, data, value) } }
        estimates.firstOrNull { it.isFailure && it.exceptionOrNull()?.message?.contains("revert", ignoreCase = true) == true }
            ?.let { throw it.exceptionOrNull()!! }
        val gasEstimate = estimates.mapNotNull { it.getOrNull() }.maxOrNull() ?: throw estimates.first().exceptionOrNull()!!
        val gas = gasEstimate * GAS_MARGIN_NUM / GAS_MARGIN_DEN
        // The next transaction number must agree: one endpoint inflating it would leave the signed transaction
        // stuck behind a gap, to go through unexpectedly later (security review 2026-10-24).
        val nonces = endpoints.mapNotNull { runCatching { it.nonce(from) }.getOrNull() }.distinct()
        if (nonces.size > 1) throw RpcError("The data sources disagree on the next transaction number. Try again in a moment.")
        val nonce = (nonces.firstOrNull() ?: rpc.nonce(from)) + BigInteger.valueOf(nonceOffset.toLong())
        // Fees: the higher base fee either endpoint reports (so it gets included), and a capped tip (one endpoint
        // can't make the card overpay validators).
        val baseFee = endpoints.mapNotNull { runCatching { it.block(null).baseFee }.getOrNull() }.maxOrNull() ?: rpc.block(null).baseFee
        val tip = runCatching { rpc.maxPriorityFee() }.getOrDefault(DEFAULT_TIP).max(MIN_TIP).min(MAX_TIP)
        val maxFee = baseFee * BigInteger.TWO + tip
        val tx = Eip1559Tx(chainId, nonce, tip, maxFee, gas, to, value, data)
        return Prepared(tx, maxCostWei = gas * maxFee)
    }

    /** Sends the signed transaction to every endpoint and waits for its receipt. Returns the tx hash. */
    suspend fun broadcastAndWait(raw: ByteArray, expectedFrom: String): String {
        val txHash = Hex.encode(raw.keccak())
        val sent = endpoints.map { runCatching { it.sendRaw(raw) } }
        if (sent.none { it.isSuccess }) {
            val msg = sent.first().exceptionOrNull()?.message.orEmpty()
            // "already known" means another endpoint delivered it: still fine.
            if (!msg.contains("known", ignoreCase = true)) throw RpcError("Couldn't send: $msg")
        }
        repeat(RECEIPT_POLLS) {
            for (e in endpoints) {
                val r = runCatching { e.receipt(txHash) }.getOrNull() ?: continue
                check(r.from.equals(expectedFrom, ignoreCase = true)) { "Transaction came from ${r.from}, not $expectedFrom" }
                if (!r.status) throw RpcError("The transaction was mined but reverted ($txHash)")
                return txHash
            }
            delay(RECEIPT_POLL_MS)
        }
        throw NotMinedYet(txHash)
    }

    private companion object {
        val GAS_MARGIN_NUM: BigInteger = BigInteger.valueOf(125)
        val GAS_MARGIN_DEN: BigInteger = BigInteger.valueOf(100)
        val DEFAULT_TIP: BigInteger = BigInteger.valueOf(1_000_000_000)
        val MIN_TIP: BigInteger = BigInteger.valueOf(1_000_000)
        val MAX_TIP: BigInteger = BigInteger.valueOf(5_000_000_000) // 5 gwei
        const val RECEIPT_POLLS = 60
        const val RECEIPT_POLL_MS = 3_000L
    }
}
