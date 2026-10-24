package com.tangem.feature.safechain.chain

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.math.BigInteger

/**
 * How far the endpoints corroborate what's on screen (the web wallet's rule, docs/wallet-ux.md):
 * - Agree: all endpoints answered, at the same block hash, with the same data.
 * - Degraded: some endpoint was down, more than [MAX_HEAD_LAG] blocks behind, or failed; the rest agree.
 * - Disagree: endpoints returned different data, or different blocks at the same height. The screen then
 *   says to check an independent explorer and shows the most urgent reading (see [VaultState.mergeUrgent]).
 */
internal sealed interface RpcHealth {
    data object Agree : RpcHealth
    data class Degraded(val problems: List<String>) : RpcHealth
    data class Disagree(val detail: String) : RpcHealth
}

internal data class Checked<T>(val value: T, val health: RpcHealth, val block: BlockHeader)

internal class CrossCheckedReader(private val endpoints: List<EthRpc>) {

    /**
     * Runs [load] on every endpoint pinned to the same block and compares the results. [merge] combines
     * differing results into the most urgent reading (never a quieter one).
     */
    suspend fun <T> read(merge: (List<T>) -> T, load: suspend (EthRpc, BlockHeader) -> T): Checked<T> = coroutineScope {
        val problems = mutableListOf<String>()
        val heads = endpoints.map { e -> async { runCatching { e.blockNumber() } } }.map { it.await() }
        val live = endpoints.indices.filter { i -> heads[i].isSuccess }
        endpoints.indices.filter { heads[it].isFailure }.forEach { problems += "${host(endpoints[it].url)}: down" }
        if (live.isEmpty()) throw RpcError("No RPC endpoint is reachable")

        // Pinned to the lowest head any live endpoint reports, and every endpoint is read at that block. An
        // endpoint claiming a far-higher head can't push an honest one out as "behind" and be read alone
        // (security review 2026-10-24): data a few blocks old but cross-checked beats fresh data from one source.
        val top = live.maxOf { heads[it].getOrThrow() }
        val fresh = live
        live.filter { top - heads[it].getOrThrow() > MAX_HEAD_LAG }
            .forEach { problems += "${host(endpoints[it].url)}: ${top - heads[it].getOrThrow()} blocks behind the other" }
        val pin = fresh.minOf { heads[it].getOrThrow() }

        val blocks = fresh.map { i -> async { i to runCatching { endpoints[i].block(pin) } } }.map { it.await() }
        val withBlock = blocks.filter { it.second.isSuccess }
        blocks.filter { it.second.isFailure }.forEach { problems += "${host(endpoints[it.first].url)}: no block $pin" }
        if (withBlock.isEmpty()) throw RpcError("No RPC endpoint returned block $pin")
        val hashes = withBlock.map { it.second.getOrThrow().hash }.toSet()
        val header = withBlock.first().second.getOrThrow()

        val results = withBlock.map { (i, b) -> async { i to runCatching { load(endpoints[i], b.getOrThrow()) } } }.map { it.await() }
        val ok = results.filter { it.second.isSuccess }.map { it.second.getOrThrow() }
        results.filter { it.second.isFailure }.forEach { problems += "${host(endpoints[it.first].url)}: ${it.second.exceptionOrNull()?.message}" }
        if (ok.isEmpty()) throw results.first().second.exceptionOrNull() ?: RpcError("all reads failed")

        val sameData = ok.all { it == ok.first() }
        val health = when {
            hashes.size > 1 -> RpcHealth.Disagree("the endpoints report different blocks at #$pin (different chains, or one is lying)")
            !sameData -> RpcHealth.Disagree("the endpoints report different data at block #$pin")
            problems.isNotEmpty() || endpoints.size < 2 -> RpcHealth.Degraded(problems.ifEmpty { listOf("only one RPC endpoint") })
            else -> RpcHealth.Agree
        }
        Checked(value = if (sameData) ok.first() else merge(ok), health = health, block = header)
    }

    private fun host(url: String) = url.removePrefix("https://").substringBefore('/')

    companion object {
        val MAX_HEAD_LAG: BigInteger = BigInteger.TEN
    }
}
