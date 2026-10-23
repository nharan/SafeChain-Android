package com.tangem.feature.safechain.chain

import java.math.BigInteger

/** Recursive-length-prefix encoding (items are ByteArray or List of items). */
internal object Rlp {

    fun encode(item: Any): ByteArray = when (item) {
        is ByteArray -> encodeBytes(item)
        is List<*> -> {
            val payload = item.fold(ByteArray(0)) { acc, i -> acc + encode(requireNotNull(i)) }
            lengthPrefix(payload.size, SHORT_LIST) + payload
        }
        else -> error("RLP: unsupported ${item::class}")
    }

    /** Minimal big-endian bytes of a non-negative integer (zero is the empty string). */
    fun int(v: BigInteger): ByteArray {
        require(v.signum() >= 0)
        if (v.signum() == 0) return ByteArray(0)
        val b = v.toByteArray()
        return if (b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b
    }

    private fun encodeBytes(b: ByteArray): ByteArray =
        if (b.size == 1 && (b[0].toInt() and 0xff) < SHORT_STRING) b else lengthPrefix(b.size, SHORT_STRING) + b

    private fun lengthPrefix(len: Int, offset: Int): ByteArray = if (len <= MAX_SHORT) {
        byteArrayOf((offset + len).toByte())
    } else {
        val lenBytes = int(BigInteger.valueOf(len.toLong()))
        byteArrayOf((offset + MAX_SHORT + lenBytes.size).toByte()) + lenBytes
    }

    private const val SHORT_STRING = 0x80
    private const val SHORT_LIST = 0xc0
    private const val MAX_SHORT = 55
}

/** An EIP-1559 (type 2) transaction with no access list. Checked against viem (Eip1559TxTest). */
internal data class Eip1559Tx(
    val chainId: Long,
    val nonce: BigInteger,
    val maxPriorityFeePerGas: BigInteger,
    val maxFeePerGas: BigInteger,
    val gas: BigInteger,
    val to: String,
    val value: BigInteger,
    val data: ByteArray,
) {
    private fun fields(): List<Any> = listOf(
        Rlp.int(BigInteger.valueOf(chainId)),
        Rlp.int(nonce),
        Rlp.int(maxPriorityFeePerGas),
        Rlp.int(maxFeePerGas),
        Rlp.int(gas),
        Hex.decode(to),
        Rlp.int(value),
        data,
        emptyList<Any>(),
    )

    fun unsignedPayload(): ByteArray = byteArrayOf(TYPE) + Rlp.encode(fields())

    /** The 32 bytes the card signs. */
    fun signingHash(): ByteArray = unsignedPayload().keccak()

    fun signed(yParity: Int, r: ByteArray, s: ByteArray): ByteArray {
        require(yParity == 0 || yParity == 1) { "yParity must be 0 or 1" }
        val sig = listOf(Rlp.int(BigInteger.valueOf(yParity.toLong())), Rlp.int(BigInteger(1, r)), Rlp.int(BigInteger(1, s)))
        return byteArrayOf(TYPE) + Rlp.encode(fields() + sig)
    }

    override fun equals(other: Any?) = other is Eip1559Tx && unsignedPayload().contentEquals(other.unsignedPayload())
    override fun hashCode() = unsignedPayload().contentHashCode()

    private companion object {
        const val TYPE: Byte = 0x02
    }
}
