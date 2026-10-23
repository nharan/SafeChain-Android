package com.tangem.feature.safechain.chain

import com.tangem.blockchain.common.UnmarshalHelper
import com.tangem.blockchain.common.Wallet

/** A canonical (low-S) secp256k1 signature with its recovery id, as an EIP-1559 transaction needs it. */
internal data class Rsv(val r: ByteArray, val s: ByteArray, val yParity: Int) {
    override fun equals(other: Any?) =
        other is Rsv && r.contentEquals(other.r) && s.contentEquals(other.s) && yParity == other.yParity
    override fun hashCode() = 31 * (31 * r.contentHashCode() + s.contentHashCode()) + yParity
}

internal object EvmSignature {

    /**
     * The card returns a raw 64-byte r‖s (possibly high-S, no recovery id). The SDK's UnmarshalHelper
     * canonicalises it and finds the recovery id that maps back to [publicKey]; asRSV() gives v = recId (0/1).
     */
    fun fromCard(signature: ByteArray, hash: ByteArray, publicKey: Wallet.PublicKey): Rsv =
        split(UnmarshalHelper.unmarshalSignatureExtended(signature, hash, publicKey).asRSV())

    /** Same, for a raw uncompressed public key (tests and software keys). */
    fun fromRaw(signature: ByteArray, hash: ByteArray, uncompressedPublicKey: ByteArray): Rsv =
        split(UnmarshalHelper.unmarshalSignatureExtended(signature, hash, uncompressedPublicKey).asRSV())

    private fun split(rsv: ByteArray): Rsv {
        require(rsv.size == 65) { "expected r‖s‖v (65 bytes), got ${rsv.size}" }
        return Rsv(r = rsv.copyOfRange(0, 32), s = rsv.copyOfRange(32, 64), yParity = rsv[64].toInt())
    }
}
