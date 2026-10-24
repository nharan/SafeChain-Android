package com.tangem.feature.safechain.signing

import com.google.common.truth.Truth.assertThat
import com.tangem.feature.safechain.chain.Eip712
import com.tangem.feature.safechain.chain.Hex
import com.tangem.feature.safechain.chain.SafeChainCalls
import com.tangem.feature.safechain.chain.ZERO_ADDRESS
import org.junit.jupiter.api.Test
import org.spongycastle.crypto.ec.CustomNamedCurves
import org.spongycastle.crypto.params.ECDomainParameters
import org.spongycastle.crypto.params.ECPublicKeyParameters
import org.spongycastle.crypto.signers.ECDSASigner
import java.math.BigInteger

/**
 * The phone's backup key and what it signs. Digest vectors come from the deployed Sepolia contracts'
 * own view functions (`cast call factory recoveryConsentDigest(...)`, `vault acceptRecoveryDigest(...)`);
 * calldata vectors from `cast calldata`. The key is Anvil's PUBLIC test key #1, never a real one.
 */
internal class PhoneKeyCoreTest {

    private val anvilKey1 = Hex.decode("0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d")
    private val anvilAddress1 = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8"
    private val card = "0xcbd2cCdB0F1e6ABb6f764Da75859e81718E92f14"
    private val vault = "0xBe3C849Bd25a52dA49C6e3C16A53426290C35A8C"
    private val ones = "0x1111111111111111111111111111111111111111"

    private fun hex(b: ByteArray) = Hex.encode(b)

    @Test
    fun `GIVEN a create config WHEN hashing the recovery consent THEN it equals the factory's own digest`() {
        val digest = Eip712.recoveryConsentDigest(card, ones, 86_400, ZERO_ADDRESS, ByteArray(32) { 0xab.toByte() })
        // From the current factory (0xf7a8…4873, deployed 2026-10-24); the domain includes the factory address.
        assertThat(hex(digest)).isEqualTo("0xf43f1072f439fcf4ab7fefe39f6c07a5abc8838a110885aa4328634126177b30")
    }

    @Test
    fun `GIVEN a rotation WHEN hashing AcceptRecovery THEN it equals the vault's own digest`() {
        val digest = Eip712.acceptRecoveryDigest(vault, ones, BigInteger.ONE)
        assertThat(hex(digest)).isEqualTo("0x07011e682e7e535e2cd782639d059b5699de77f3f9a4cd135cb41f562d25e4d6")
    }

    @Test
    fun `GIVEN a private key WHEN deriving the address THEN it is the Ethereum address`() {
        assertThat(SoftKey.address(anvilKey1)).isEqualTo(anvilAddress1)
    }

    @Test
    fun `GIVEN a digest WHEN the phone key signs THEN the signature verifies and is low-S with a recovery id`() {
        // Arrange
        val digest = Eip712.recoveryConsentDigest(card, anvilAddress1, 86_400, ZERO_ADDRESS, ByteArray(32) { 7 })
        // Act
        val rsv = SoftKey.sign(anvilKey1, digest)
        // Assert: plain ECDSA verifies it against the key (independently of the code under test); low S
        // (Ethereum rejects high S); v is 27/28 for SignatureChecker
        assertThat(ecdsaVerifies(SoftKey.publicKey(anvilKey1), digest, rsv.r, rsv.s)).isTrue()
        assertThat(ecdsaVerifies(SoftKey.publicKey(anvilKey1), ByteArray(32), rsv.r, rsv.s)).isFalse()
        val halfN = BigInteger("7fffffffffffffffffffffffffffffff5d576e7357a4501ddfe92f46681b20a0", 16)
        assertThat(BigInteger(1, rsv.s) <= halfN).isTrue()
        val bytes = rsv.toEip712Bytes()
        assertThat(bytes.size).isEqualTo(65)
        assertThat(bytes[64].toInt()).isAnyOf(27, 28)
    }

    private fun ecdsaVerifies(uncompressedPublicKey: ByteArray, hash: ByteArray, r: ByteArray, s: ByteArray): Boolean {
        val curve = CustomNamedCurves.getByName("secp256k1")
        val domain = ECDomainParameters(curve.curve, curve.g, curve.n, curve.h)
        val verifier = ECDSASigner().apply { init(false, ECPublicKeyParameters(curve.curve.decodePoint(uncompressedPublicKey), domain)) }
        return verifier.verifySignature(hash, BigInteger(1, r), BigInteger(1, s))
    }

    @Test
    fun `GIVEN fresh keys WHEN generated THEN each is valid and different`() {
        val a = SoftKey.generate()
        val b = SoftKey.generate()
        assertThat(a.size).isEqualTo(32)
        assertThat(a.contentEquals(b)).isFalse()
        assertThat(SoftKey.address(a)).matches("0x[0-9a-fA-F]{40}")
    }

    @Test
    fun `GIVEN the new calls WHEN ABI encoded THEN calldata matches cast`() {
        assertThat(hex(SafeChainCalls.transfer(ones, BigInteger.TEN.pow(21)))).isEqualTo(
            "0xa9059cbb000000000000000000000000111111111111111111111111111111111111111100000000000000000000000000000000000000000000003635c9adc5dea00000",
        )
        assertThat(hex(SafeChainCalls.unlock())).isEqualTo("0xa69df4b5")
        assertThat(hex(SafeChainCalls.settlePayment(BigInteger.valueOf(5))))
            .isEqualTo("0x9f20c4f70000000000000000000000000000000000000000000000000000000000000005")
        assertThat(hex(SafeChainCalls.getPayment(BigInteger.valueOf(5))))
            .isEqualTo("0x3280a8360000000000000000000000000000000000000000000000000000000000000005")
        assertThat(hex(SafeChainCalls.paymentState(BigInteger.valueOf(5))))
            .isEqualTo("0xd035664d0000000000000000000000000000000000000000000000000000000000000005")
        assertThat(hex(SafeChainCalls.allowance(ones, "0x0c57772f68D4Ff4c432270b616F08c4884507913"))).isEqualTo(
            "0xdd62ed3e00000000000000000000000011111111111111111111111111111111111111110000000000000000000000000c57772f68d4ff4c432270b616f08c4884507913",
        )
        assertThat(hex(SafeChainCalls.releaseVoided(BigInteger.valueOf(4))))
            .isEqualTo("0x897d7cd50000000000000000000000000000000000000000000000000000000000000004")
        assertThat(hex(SafeChainCalls.isVoid(BigInteger.valueOf(4))))
            .isEqualTo("0xef18e6780000000000000000000000000000000000000000000000000000000000000004")
        assertThat(hex(SafeChainCalls.cancelPolicyChange())).isEqualTo("0x0ac393bd")
        assertThat(hex(SafeChainCalls.approvePolicyChange(BigInteger.valueOf(3))))
            .isEqualTo("0x85e0e5ff0000000000000000000000000000000000000000000000000000000000000003")
        assertThat(hex(SafeChainCalls.executePolicyChange(BigInteger.valueOf(3))))
            .isEqualTo("0x39b4fee70000000000000000000000000000000000000000000000000000000000000003")
        assertThat(hex(SafeChainCalls.proposeRecoveryChange("0xa0Ee7A142d267C1f36714E4a8F75612F20a79720", ByteArray(65) { 0xcd.toByte() })))
            .isEqualTo(
                "0x7153b939000000000000000000000000a0ee7a142d267c1f36714e4a8f75612f20a7972000000000000000000000000000000000000000000000000000000000000000400000000000000000000000000000000000000000000000000000000000000041cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd00000000000000000000000000000000000000000000000000000000000000",
            )
    }
}
