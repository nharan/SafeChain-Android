package com.tangem.feature.safechain.chain

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.math.BigInteger

/**
 * Opt-in: reads the owner's card account on the real Sepolia deployment through the same code the phone uses
 * (both public endpoints, cross-checked, block hash included). Run with SAFECHAIN_LIVE=1. Read-only.
 */
@EnabledIfEnvironmentVariable(named = "SAFECHAIN_LIVE", matches = "1")
internal class LiveSepoliaReadTest {

    private val card = "0xcbd2cCdB0F1e6ABb6f764Da75859e81718E92f14"

    @Test
    fun `GIVEN the real Sepolia deployment WHEN the card's account is read THEN both endpoints agree on it`() = runBlocking {
        val http = OkHttpClient()
        val endpoints = SepoliaConfig.RPC_URLS.map { OkHttpEthRpc(it, http) }
        val checked = CrossCheckedReader(endpoints).read(AccountSnapshot::mergeUrgent) { rpc, block ->
            VaultReader().snapshot(rpc, block, card)
        }
        println("health=${checked.health} block=${checked.block.number} snapshot=${checked.value}")
        assertThat(checked.health).isEqualTo(RpcHealth.Agree)
        assertThat(checked.value.tokenBalances.keys).containsExactlyElementsIn(SepoliaConfig.TOKENS.map { it.key })
        assertThat(checked.value.tokenBalances.of(SepoliaConfig.TOKEN)).isGreaterThan(BigInteger.ZERO)
        assertThat(checked.value.ethBalance).isGreaterThan(BigInteger.ZERO)
        // The card opened its vaults itself, so each is recognised as its own (the others would be ignored).
        val own = checked.value.vaults.filter { it.primary.equals(card, ignoreCase = true) }
        assertThat(own).isNotEmpty()
        assertThat(own.all { it.createdByMe }).isTrue()
    }
}
