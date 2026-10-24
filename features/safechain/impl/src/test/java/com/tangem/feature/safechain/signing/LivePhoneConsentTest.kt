package com.tangem.feature.safechain.signing

import com.google.common.truth.Truth.assertThat
import com.tangem.feature.safechain.chain.Eip712
import com.tangem.feature.safechain.chain.OkHttpEthRpc
import com.tangem.feature.safechain.chain.SafeChainCalls
import com.tangem.feature.safechain.chain.SepoliaConfig
import com.tangem.feature.safechain.chain.ZERO_ADDRESS
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.math.BigInteger
import java.security.SecureRandom

/**
 * Opt-in (SAFECHAIN_LIVE=1), sends nothing: asks the real Sepolia factory to simulate creating a vault whose
 * backup is a fresh phone key that signed its own consent. The factory checks the EIP-712 signature, so a
 * successful estimate proves the whole phone-backup setup path; a wrong digest or signature reverts.
 */
@EnabledIfEnvironmentVariable(named = "SAFECHAIN_LIVE", matches = "1")
internal class LivePhoneConsentTest {

    private val card = "0xcbd2cCdB0F1e6ABb6f764Da75859e81718E92f14"

    @Test
    fun `GIVEN a phone key's own consent WHEN the factory simulates createVault THEN it accepts it, and rejects a tampered one`() = runBlocking {
        val rpc = OkHttpEthRpc(SepoliaConfig.RPC_URLS.first(), OkHttpClient())
        val phoneKey = SoftKey.generate()
        val phone = SoftKey.address(phoneKey)
        val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val delay = 86_400L
        val consent = SoftKey.sign(phoneKey, Eip712.recoveryConsentDigest(card, phone, delay, ZERO_ADDRESS, salt)).toEip712Bytes()

        val gas = rpc.estimateGas(card, SepoliaConfig.FACTORY, SafeChainCalls.createVault(card, phone, delay, salt, consent, ZERO_ADDRESS, ByteArray(0)))
        println("createVault with a phone-signed consent: estimate $gas gas")
        assertThat(gas).isGreaterThan(BigInteger.valueOf(100_000))

        // The same consent can't be reused for a different configuration (here: a 48h delay).
        val tampered = runCatching {
            rpc.estimateGas(card, SepoliaConfig.FACTORY, SafeChainCalls.createVault(card, phone, delay * 2, salt, consent, ZERO_ADDRESS, ByteArray(0)))
        }
        assertThat(tampered.isFailure).isTrue()
        println("tampered: ${tampered.exceptionOrNull()?.message}")
    }
}
