package com.tangem.feature.safechain.signing

import com.tangem.common.card.EllipticCurve
import com.tangem.crypto.CryptoUtils
import com.tangem.crypto.Secp256k1
import com.tangem.feature.safechain.chain.EvmSignature
import com.tangem.feature.safechain.chain.Rsv
import com.tangem.feature.safechain.chain.addressOfPublicKey

/**
 * A secp256k1 key held by this app (the phone's backup key). Ethereum accounts must be secp256k1, which the
 * Android Keystore can't hold, so the key lives in app memory only while it signs; at rest it's encrypted
 * by a Keystore key (PhoneKeyStore). The Tangem SDK's own secp256k1 code does the maths.
 */
internal object SoftKey {

    private const val PRIVATE_KEY_BYTES = 32

    fun generate(): ByteArray {
        ensureCrypto()
        while (true) {
            // The platform's CSPRNG directly, rather than a library helper whose source isn't in this repo.
            val candidate = ByteArray(PRIVATE_KEY_BYTES).also { java.security.SecureRandom().nextBytes(it) }
            if (CryptoUtils.isPrivateKeyValid(candidate, EllipticCurve.Secp256k1)) return candidate
        }
    }

    fun publicKey(privateKey: ByteArray): ByteArray {
        ensureCrypto()
        return CryptoUtils.generatePublicKey(privateKey, EllipticCurve.Secp256k1, false)
    }

    fun address(privateKey: ByteArray): String = addressOfPublicKey(publicKey(privateKey))

    /** Canonical (low-S) signature of the 32-byte [hash], with the recovery id that maps back to this key. */
    fun sign(privateKey: ByteArray, hash: ByteArray): Rsv {
        require(hash.size == PRIVATE_KEY_BYTES) { "expected a 32-byte hash" }
        ensureCrypto()
        return EvmSignature.fromRaw(Secp256k1.ecdsaSignDigest(hash, privateKey), hash, publicKey(privateKey))
    }

    private fun ensureCrypto() {
        if (!CryptoUtils.isInitialized) CryptoUtils.initCrypto()
    }
}

/** r‖s‖v with v = 27 or 28: the 65-byte form OpenZeppelin's SignatureChecker accepts for EIP-712. */
internal fun Rsv.toEip712Bytes(): ByteArray = r + s + byteArrayOf((27 + yParity).toByte())
