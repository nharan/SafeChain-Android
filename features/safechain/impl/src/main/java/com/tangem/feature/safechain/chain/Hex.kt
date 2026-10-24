package com.tangem.feature.safechain.chain

import com.tangem.blockchain.blockchains.ethereum.EthereumUtils.toKeccak
import java.math.BigInteger

internal object Hex {

    private const val DIGITS = "0123456789abcdef"

    fun encode(bytes: ByteArray, prefix: Boolean = true): String {
        val sb = StringBuilder(bytes.size * 2 + 2)
        if (prefix) sb.append("0x")
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append(DIGITS[v ushr 4]).append(DIGITS[v and 0x0f])
        }
        return sb.toString()
    }

    fun decode(hex: String): ByteArray {
        val s = hex.removePrefix("0x").removePrefix("0X").let { if (it.length % 2 == 1) "0$it" else it }
        require(s.all(::isHexDigit)) { "not hex: $hex" }
        return ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    fun toBigInteger(hex: String): BigInteger {
        val s = hex.removePrefix("0x")
        return if (s.isEmpty()) BigInteger.ZERO else BigInteger(s, 16)
    }

    fun fromBigInteger(v: BigInteger): String = "0x" + v.toString(16)
}

internal fun ByteArray.keccak(): ByteArray = toKeccak()

/** ASCII hex only: Kotlin's isDigit() also accepts other scripts' digits (fullwidth, Arabic-Indic…). */
internal fun isHexDigit(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

/** EIP-55 checksummed form of a 20-byte address. */
internal fun checksumAddress(address: String): String {
    val lower = address.removePrefix("0x").lowercase()
    require(lower.length == 40 && lower.all(::isHexDigit)) { "not an address: $address" }
    val hash = Hex.encode(lower.toByteArray().keccak(), prefix = false)
    return "0x" + lower.mapIndexed { i, c -> if (c.isLetter() && hash[i].digitToInt(16) >= 8) c.uppercaseChar() else c }
        .joinToString("")
}

/**
 * An address someone typed, pasted or scanned. All-lowercase or all-uppercase is accepted as is; mixed case
 * must be a correct EIP-55 checksum, which catches a mistyped character instead of silently fixing it.
 */
internal fun parseUserAddress(input: String): String? {
    val s = input.trim()
    if (!s.startsWith("0x") || s.length != 42) return null
    val body = s.substring(2)
    if (!body.all(::isHexDigit)) return null
    val checked = checksumAddress(s)
    val mixed = body.any { it in 'a'..'f' } && body.any { it in 'A'..'F' }
    return if (!mixed || checked == s) checked else null
}

/** Address of an uncompressed secp256k1 public key (65 bytes, 0x04 prefix). */
internal fun addressOfPublicKey(uncompressed: ByteArray): String {
    require(uncompressed.size == 65 && uncompressed[0] == 0x04.toByte()) { "expected an uncompressed public key" }
    val hash = uncompressed.copyOfRange(1, 65).keccak()
    return checksumAddress(Hex.encode(hash.copyOfRange(12, 32)))
}

/** The whole address in groups of four, for comparing against what the other side gave you. */
internal fun groupedAddress(address: String): String =
    "0x " + address.removePrefix("0x").chunked(4).joinToString(" ")
