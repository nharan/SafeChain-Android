package com.tangem.feature.safechain.chain

import java.math.BigInteger

/**
 * EIP-712 digests the SafeChain contracts check (OpenZeppelin's `_hashTypedDataV4`). Computed here, not
 * asked of an RPC, so a lying endpoint can't get the phone key to sign something else. Checked against
 * the deployed contracts' own `recoveryConsentDigest` / `acceptRecoveryDigest` (SigningCoreTest).
 */
internal object Eip712 {

    private val DOMAIN_TYPEHASH = typeHash("EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)")
    private val RECOVERY_CONSENT_TYPEHASH =
        typeHash("RecoveryConsent(address primary,address recovery,uint64 withdrawalDelay,address haven,bytes32 salt)")
    private val ACCEPT_RECOVERY_TYPEHASH = typeHash("AcceptRecovery(address newRecovery,uint256 proposalNonce)")

    private const val FACTORY_NAME = "SafeChain SavingsVaultFactory"
    private const val VAULT_NAME = "SafeChain SavingsVault"
    private const val VERSION = "1"

    /** What a new vault's recovery key signs: it agrees to protect exactly this configuration. */
    @Suppress("LongParameterList")
    fun recoveryConsentDigest(
        primary: String,
        recovery: String,
        delaySeconds: Long,
        haven: String,
        salt: ByteArray,
        factory: String = SepoliaConfig.FACTORY,
        chainId: Long = SepoliaConfig.CHAIN_ID,
    ): ByteArray = digest(
        domain(FACTORY_NAME, chainId, factory),
        Abi.encode(
            listOf(
                Abi.Arg.Bytes32(RECOVERY_CONSENT_TYPEHASH),
                Abi.Arg.Address(primary),
                Abi.Arg.Address(recovery),
                Abi.Arg.Uint(BigInteger.valueOf(delaySeconds)),
                Abi.Arg.Address(haven),
                Abi.Arg.Bytes32(salt),
            ),
        ).keccak(),
    )

    /** What a replacement recovery key signs for an existing vault's proposal number [proposalNonce]. */
    fun acceptRecoveryDigest(vault: String, newRecovery: String, proposalNonce: BigInteger, chainId: Long = SepoliaConfig.CHAIN_ID): ByteArray =
        digest(
            domain(VAULT_NAME, chainId, vault),
            Abi.encode(listOf(Abi.Arg.Bytes32(ACCEPT_RECOVERY_TYPEHASH), Abi.Arg.Address(newRecovery), Abi.Arg.Uint(proposalNonce))).keccak(),
        )

    private fun domain(name: String, chainId: Long, verifyingContract: String): ByteArray = Abi.encode(
        listOf(
            Abi.Arg.Bytes32(DOMAIN_TYPEHASH),
            Abi.Arg.Bytes32(name.toByteArray(Charsets.UTF_8).keccak()),
            Abi.Arg.Bytes32(VERSION.toByteArray(Charsets.UTF_8).keccak()),
            Abi.Arg.Uint(BigInteger.valueOf(chainId)),
            Abi.Arg.Address(verifyingContract),
        ),
    ).keccak()

    private fun digest(domainSeparator: ByteArray, structHash: ByteArray): ByteArray =
        (byteArrayOf(0x19, 0x01) + domainSeparator + structHash).keccak()

    private fun typeHash(type: String) = type.toByteArray(Charsets.US_ASCII).keccak()
}
