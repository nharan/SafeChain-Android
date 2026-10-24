package com.tangem.feature.safechain.chain

import java.math.BigInteger

/**
 * Minimal Solidity ABI encoding for the SafeChain calls this app makes (static words, `bytes`, `uint256[]`).
 * Checked byte for byte against viem, the library the SafeChain web wallet uses (AbiTest).
 */
internal object Abi {

    sealed interface Arg {
        data class Address(val value: String) : Arg
        data class Uint(val value: BigInteger) : Arg
        data class Bool(val value: Boolean) : Arg
        data class Bytes32(val value: ByteArray) : Arg
        data class DynBytes(val value: ByteArray) : Arg
        data class UintArray(val values: List<BigInteger>) : Arg
    }

    private const val WORD = 32
    private val MAX_UINT = BigInteger.ONE.shiftLeft(256)

    fun selector(signature: String): ByteArray = signature.toByteArray(Charsets.US_ASCII).keccak().copyOf(4)

    fun call(signature: String, vararg args: Arg): ByteArray = selector(signature) + encode(args.toList())

    fun encode(args: List<Arg>): ByteArray {
        val heads = mutableListOf<ByteArray>()
        val tails = mutableListOf<ByteArray>()
        var tailOffset = args.size * WORD
        for (a in args) {
            when (a) {
                is Arg.DynBytes -> {
                    heads += uintWord(BigInteger.valueOf(tailOffset.toLong()))
                    val t = uintWord(BigInteger.valueOf(a.value.size.toLong())) + padRight(a.value)
                    tails += t
                    tailOffset += t.size
                }
                is Arg.UintArray -> {
                    heads += uintWord(BigInteger.valueOf(tailOffset.toLong()))
                    val t = a.values.fold(uintWord(BigInteger.valueOf(a.values.size.toLong()))) { acc, v -> acc + uintWord(v) }
                    tails += t
                    tailOffset += t.size
                }
                else -> heads += staticWord(a)
            }
        }
        return (heads + tails).fold(ByteArray(0)) { acc, b -> acc + b }
    }

    private fun staticWord(a: Arg): ByteArray = when (a) {
        is Arg.Address -> ByteArray(12) + Hex.decode(a.value).also { require(it.size == 20) { "bad address ${a.value}" } }
        is Arg.Uint -> uintWord(a.value)
        is Arg.Bool -> uintWord(if (a.value) BigInteger.ONE else BigInteger.ZERO)
        is Arg.Bytes32 -> a.value.also { require(it.size == WORD) { "bytes32 must be 32 bytes" } }
        is Arg.DynBytes, is Arg.UintArray -> error("dynamic")
    }

    fun uintWord(v: BigInteger): ByteArray {
        require(v.signum() >= 0 && v < MAX_UINT) { "uint256 out of range: $v" }
        val raw = v.toByteArray().let { if (it.size > WORD) it.copyOfRange(it.size - WORD, it.size) else it }
        return ByteArray(WORD - raw.size) + raw
    }

    private fun padRight(b: ByteArray): ByteArray {
        val rem = b.size % WORD
        return if (rem == 0) b else b + ByteArray(WORD - rem)
    }

    // ------------------------------------------------------------ decoding (static words only)

    fun words(data: ByteArray): List<ByteArray> = (0 until data.size / WORD).map { data.copyOfRange(it * WORD, (it + 1) * WORD) }
    fun uint(word: ByteArray): BigInteger = BigInteger(1, word)
    fun address(word: ByteArray): String = checksumAddress(Hex.encode(word.copyOfRange(12, WORD)))
    fun bool(word: ByteArray): Boolean = uint(word).signum() != 0
}

/** The SafeChain contract calls (signatures from contracts/src). */
internal object SafeChainCalls {
    private fun addr(a: String) = Abi.Arg.Address(a)
    private fun uint(v: BigInteger) = Abi.Arg.Uint(v)
    private fun uint(v: Long) = Abi.Arg.Uint(BigInteger.valueOf(v))

    // SavingsVault — writes
    fun requestWithdrawal(token: String, amount: BigInteger, to: String) =
        Abi.call("requestWithdrawal(address,uint256,address)", addr(token), uint(amount), addr(to))
    fun cancelWithdrawal(id: BigInteger) = Abi.call("cancelWithdrawal(uint256)", uint(id))
    fun executeWithdrawal(id: BigInteger) = Abi.call("executeWithdrawal(uint256)", uint(id))
    fun lock() = Abi.call("lock()")
    fun unlock() = Abi.call("unlock()")
    /** Frees the reservation of a payment a lock stopped, so the money is spendable again. Anyone may call it. */
    fun releaseVoided(id: BigInteger) = Abi.call("releaseVoided(uint256)", uint(id))
    fun proposeRecoveryChange(newRecovery: String, consent: ByteArray) =
        Abi.call("proposeRecoveryChange(address,bytes)", addr(newRecovery), Abi.Arg.DynBytes(consent))
    fun approvePolicyChange(nonce: BigInteger) = Abi.call("approvePolicyChange(uint256)", uint(nonce))
    fun executePolicyChange(nonce: BigInteger) = Abi.call("executePolicyChange(uint256)", uint(nonce))
    fun cancelPolicyChange() = Abi.call("cancelPolicyChange()")
    fun requestHavenWithdrawal(token: String, amount: BigInteger, clearFirst: List<BigInteger>) = Abi.call(
        "requestHavenWithdrawal(address,uint256,uint256[])", addr(token), uint(amount), Abi.Arg.UintArray(clearFirst),
    )
    fun deposit(token: String, amount: BigInteger) = Abi.call("deposit(address,uint256)", addr(token), uint(amount))

    // ERC-20
    fun approve(spender: String, amount: BigInteger) = Abi.call("approve(address,uint256)", addr(spender), uint(amount))
    /** A plain transfer into a vault counts as a deposit: one tap instead of approve + deposit. */
    fun transfer(to: String, amount: BigInteger) = Abi.call("transfer(address,uint256)", addr(to), uint(amount))
    fun balanceOf(owner: String) = Abi.call("balanceOf(address)", addr(owner))

    // Escrow
    fun createPayment(token: String, recipient: String, amount: BigInteger, protectionSeconds: Long) = Abi.call(
        "createPayment(address,address,uint256,uint64)", addr(token), addr(recipient), uint(amount), uint(protectionSeconds),
    )
    fun cancelPayment(id: BigInteger) = Abi.call("cancelPayment(uint256)", uint(id))

    // Factory
    @Suppress("LongParameterList")
    fun createVault(
        primary: String,
        recovery: String,
        delaySeconds: Long,
        salt: ByteArray,
        recoveryConsent: ByteArray,
        haven: String,
        havenConsent: ByteArray,
    ) = Abi.call(
        "createVault(address,address,uint64,bytes32,bytes,address,bytes)",
        addr(primary), addr(recovery), uint(delaySeconds), Abi.Arg.Bytes32(salt), Abi.Arg.DynBytes(recoveryConsent),
        addr(haven), Abi.Arg.DynBytes(havenConsent),
    )
    fun predictVaultAddress(primary: String, recovery: String, delaySeconds: Long, haven: String, salt: ByteArray) = Abi.call(
        "predictVaultAddress(address,address,uint64,address,bytes32)",
        addr(primary), addr(recovery), uint(delaySeconds), addr(haven), Abi.Arg.Bytes32(salt),
    )
    fun isVault(a: String) = Abi.call("isVault(address)", addr(a))

    // SavingsVault — reads
    fun view(name: String) = Abi.call("$name()")
    fun available(token: String) = Abi.call("available(address)", addr(token))
    fun getWithdrawal(id: BigInteger) = Abi.call("getWithdrawal(uint256)", uint(id))
    fun withdrawalState(id: BigInteger) = Abi.call("withdrawalState(uint256)", uint(id))
    fun isVoid(id: BigInteger) = Abi.call("isVoid(uint256)", uint(id))
    /** (kind, proposer, approved, eta, newAddress, newDelay): six static words. */
    fun pendingPolicyChange() = Abi.call("pendingPolicyChange()")
}
