package com.tangem.feature.safechain.chain

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.math.BigInteger

/**
 * Reference vectors come from SafeChain's web/scripts/gen-android-vectors.mjs (viem, the library the web
 * wallet signs with), using Anvil's PUBLIC test key #1. The phone must agree with it byte for byte.
 */
internal class SigningCoreTest {

    private val token = "0x16822965d34e80D7E700Fa519C2aCa4c71835032"
    private val to = "0xa0Ee7A142d267C1f36714E4a8F75612F20a79720"
    private val vault = "0x1111111111111111111111111111111111111111"
    private val signer = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8"
    private val publicKey = Hex.decode(
        "0x04ba5734d8f7091719471e7f7ed6b9df170dc70cc661ca05e688601ad984f068b0d67351e5f06073092499336ab0839ef8a521afd334e53807205fa2f08eec74f4",
    )

    private fun hex(b: ByteArray) = Hex.encode(b)

    @Test
    fun `GIVEN known inputs WHEN keccak and addresses THEN they match viem`() {
        assertThat(hex("abc".toByteArray().keccak())).isEqualTo("0x4e03657aea45a94fc7d47ba826c8d667c0d1e6e33a64a036ec44f58fa12d6c45")
        assertThat(addressOfPublicKey(publicKey)).isEqualTo(signer)
        assertThat(checksumAddress(signer.lowercase())).isEqualTo(signer)
    }

    @Test
    fun `GIVEN SafeChain calls WHEN ABI encoded THEN calldata matches viem`() {
        assertThat(hex(SafeChainCalls.requestWithdrawal(token, BigInteger("1234567890123456789"), to))).isEqualTo(
            "0x9463c4bb00000000000000000000000016822965d34e80d7e700fa519c2aca4c71835032000000000000000000000000000000000000000000000000112210f47de98115000000000000000000000000a0ee7a142d267c1f36714e4a8f75612f20a79720",
        )
        assertThat(hex(SafeChainCalls.cancelWithdrawal(BigInteger.valueOf(7))))
            .isEqualTo("0x3efcfda40000000000000000000000000000000000000000000000000000000000000007")
        assertThat(hex(SafeChainCalls.executeWithdrawal(BigInteger.valueOf(7))))
            .isEqualTo("0x24f13a760000000000000000000000000000000000000000000000000000000000000007")
        assertThat(hex(SafeChainCalls.lock())).isEqualTo("0xf83d08ba")
        val max = BigInteger.ONE.shiftLeft(256) - BigInteger.ONE
        assertThat(hex(SafeChainCalls.requestHavenWithdrawal(token, max, listOf(BigInteger.valueOf(3), BigInteger.valueOf(9))))).isEqualTo(
            "0xb493940100000000000000000000000016822965d34e80d7e700fa519c2aca4c71835032ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff0000000000000000000000000000000000000000000000000000000000000060000000000000000000000000000000000000000000000000000000000000000200000000000000000000000000000000000000000000000000000000000000030000000000000000000000000000000000000000000000000000000000000009",
        )
        assertThat(hex(SafeChainCalls.requestHavenWithdrawal(token, BigInteger.valueOf(5), emptyList()))).isEqualTo(
            "0xb493940100000000000000000000000016822965d34e80d7e700fa519c2aca4c71835032000000000000000000000000000000000000000000000000000000000000000500000000000000000000000000000000000000000000000000000000000000600000000000000000000000000000000000000000000000000000000000000000",
        )
        val thousand = BigInteger.TEN.pow(21)
        assertThat(hex(SafeChainCalls.deposit(token, thousand))).isEqualTo(
            "0x47e7ef2400000000000000000000000016822965d34e80d7e700fa519c2aca4c7183503200000000000000000000000000000000000000000000003635c9adc5dea00000",
        )
        assertThat(hex(SafeChainCalls.approve(vault, thousand))).isEqualTo(
            "0x095ea7b3000000000000000000000000111111111111111111111111111111111111111100000000000000000000000000000000000000000000003635c9adc5dea00000",
        )
        assertThat(hex(SafeChainCalls.createPayment(token, to, BigInteger("25000000000000000000"), 86_400))).isEqualTo(
            "0xd635018e00000000000000000000000016822965d34e80d7e700fa519c2aca4c71835032000000000000000000000000a0ee7a142d267c1f36714e4a8f75612f20a797200000000000000000000000000000000000000000000000015af1d78b58c400000000000000000000000000000000000000000000000000000000000000015180",
        )
        assertThat(hex(SafeChainCalls.cancelPayment(BigInteger.ONE)))
            .isEqualTo("0x8422927d0000000000000000000000000000000000000000000000000000000000000001")
        val salt = ByteArray(32) { 0xab.toByte() }
        val consent = ByteArray(65) { 0xcd.toByte() }
        assertThat(
            hex(SafeChainCalls.createVault(signer, to, 86_400, salt, consent, "0x2222222222222222222222222222222222222222", ByteArray(0))),
        ).isEqualTo(
            "0x8defad2200000000000000000000000070997970c51812dc3a010c7d01b50e0d17dc79c8000000000000000000000000a0ee7a142d267c1f36714e4a8f75612f20a797200000000000000000000000000000000000000000000000000000000000015180abababababababababababababababababababababababababababababababab00000000000000000000000000000000000000000000000000000000000000e0000000000000000000000000222222222222222222222222222222222222222200000000000000000000000000000000000000000000000000000000000001600000000000000000000000000000000000000000000000000000000000000041cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
        )
    }

    private val tx = Eip1559Tx(
        chainId = SepoliaConfig.CHAIN_ID,
        nonce = BigInteger.valueOf(5),
        maxPriorityFeePerGas = BigInteger.valueOf(1_000_000_000),
        maxFeePerGas = BigInteger.valueOf(3_000_000_123),
        gas = BigInteger.valueOf(250_000),
        to = vault,
        value = BigInteger.ZERO,
        data = SafeChainCalls.requestWithdrawal(token, BigInteger("1234567890123456789"), to),
    )
    private val r = Hex.decode("0x3ee8fa0347eb761ce820218171ad75a55ea08e59284b2a2abf297774fd47cb52")
    private val s = Hex.decode("0x5616b80868b25f874f2d8d00cf35465373d585592951c6f2679d2bd666550e35")
    private val signedVector =
        "0x02f8d383aa36a705843b9aca0084b2d05e7b8303d09094111111111111111111111111111111111111111180b8649463c4bb00000000000000000000000016822965d34e80d7e700fa519c2aca4c71835032000000000000000000000000000000000000000000000000112210f47de98115000000000000000000000000a0ee7a142d267c1f36714e4a8f75612f20a79720c001a03ee8fa0347eb761ce820218171ad75a55ea08e59284b2a2abf297774fd47cb52a05616b80868b25f874f2d8d00cf35465373d585592951c6f2679d2bd666550e35"

    @Test
    fun `GIVEN a Sepolia EIP-1559 tx WHEN serialized THEN unsigned bytes and signing hash match viem`() {
        assertThat(hex(tx.unsignedPayload())).isEqualTo(
            "0x02f89083aa36a705843b9aca0084b2d05e7b8303d09094111111111111111111111111111111111111111180b8649463c4bb00000000000000000000000016822965d34e80d7e700fa519c2aca4c71835032000000000000000000000000000000000000000000000000112210f47de98115000000000000000000000000a0ee7a142d267c1f36714e4a8f75612f20a79720c0",
        )
        assertThat(hex(tx.signingHash())).isEqualTo("0x2948c1ab41daf27c932380d612d4a1972cdae935d2fe421ab29449dd557d1177")
    }

    @Test
    fun `GIVEN a card's raw r-s WHEN the recovery id is found THEN the signed tx matches viem`() {
        // Arrange: the card returns r‖s only, no recovery id
        val cardSignature = r + s
        // Act
        val rsv = EvmSignature.fromRaw(cardSignature, tx.signingHash(), publicKey)
        // Assert
        assertThat(rsv.yParity).isEqualTo(1)
        assertThat(hex(tx.signed(rsv.yParity, rsv.r, rsv.s))).isEqualTo(signedVector)
    }

    @Test
    fun `GIVEN a high-S signature WHEN canonicalised THEN the signed tx is still the valid low-S one`() {
        // The card may return s > n/2; Ethereum only accepts low S. n - s is the same signature, high-S.
        val n = BigInteger("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141", 16)
        val highS = Abi.uintWord(n - BigInteger(1, s))
        val rsv = EvmSignature.fromRaw(r + highS, tx.signingHash(), publicKey)
        assertThat(hex(tx.signed(rsv.yParity, rsv.r, rsv.s))).isEqualTo(signedVector)
    }

    @Test
    fun `GIVEN a word WHEN decoding THEN addresses, uints and bools round-trip`() {
        val w = Abi.uintWord(BigInteger.valueOf(86_400))
        assertThat(Abi.uint(w)).isEqualTo(BigInteger.valueOf(86_400))
        val a = Abi.encode(listOf(Abi.Arg.Address(to)))
        assertThat(Abi.address(a)).isEqualTo(to)
        assertThat(Abi.bool(Abi.uintWord(BigInteger.ONE))).isTrue()
        assertThat(groupedAddress(to)).isEqualTo("0x a0Ee 7A14 2d26 7C1f 3671 4E4a 8F75 612F 20a7 9720")
    }
}
