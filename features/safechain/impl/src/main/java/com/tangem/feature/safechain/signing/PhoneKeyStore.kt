package com.tangem.feature.safechain.signing

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.tangem.core.decompose.utils.ForegroundActivityProvider
import com.tangem.feature.safechain.chain.Hex
import com.tangem.feature.safechain.chain.Rsv
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal sealed class PhoneKeyError(message: String) : Exception(message) {
    class NoFingerprint : PhoneKeyError("Set up a fingerprint on this phone first (Settings → Security). It protects the backup key.")
    class Unavailable(detail: String) : PhoneKeyError("This phone can't protect a backup key with a fingerprint ($detail).")
    class Cancelled : PhoneKeyError("Fingerprint check cancelled.")
    class Missing : PhoneKeyError("This phone doesn't hold the backup key.")

    /** A fingerprint was added or all were removed: Android destroyed the key, by design. */
    class Invalidated : PhoneKeyError(
        "The fingerprints on this phone changed, so Android destroyed the backup key to protect it. Move your savings to a new vault from this phone.",
    )
}

/**
 * The phone's backup key for one card's vault. The secp256k1 key ([SoftKey]) is encrypted with an AES key in
 * the Android Keystore (StrongBox when the phone has one) that:
 * - only works right after a strong-biometric check, bound to this one operation (CryptoObject);
 * - is destroyed by Android if a fingerprint is added, so someone who learns the screen PIN can't enrol their
 *   own finger and use it;
 * - never leaves the secure hardware.
 * The encrypted key sits in the app's no-backup folder; it's decrypted only for the moment it signs, then wiped.
 */
@Singleton
internal class PhoneKeyStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val foregroundActivityProvider: ForegroundActivityProvider,
) {

    data class Prompt(val title: String, val subtitle: String)

    /** The backup key's address for the card [owner], if this phone holds one. */
    fun address(owner: String): String? = read(owner)?.address

    fun checkAvailable() {
        when (BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> Unit
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> throw PhoneKeyError.NoFingerprint()
            else -> throw PhoneKeyError.Unavailable("no strong biometric sensor")
        }
    }

    /**
     * Creates the backup key for [owner] and, before storing it, signs what [toSign] asks for given the new
     * key's address (the vault's recovery consent names that address). One fingerprint check covers both.
     */
    suspend fun create(owner: String, prompt: Prompt, toSign: (address: String) -> List<ByteArray>): Pair<String, List<Rsv>> {
        checkAvailable()
        // Never replace a key that may already protect a vault: setup reuses it instead ([sign]).
        check(read(owner) == null) { "This phone already holds a backup key for this card" }
        val privateKey = SoftKey.generate()
        try {
            val address = SoftKey.address(privateKey)
            val signatures = toSign(address).map { SoftKey.sign(privateKey, it) }
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, newWrappingKey(owner)) }
            val unlocked = authenticate(prompt, cipher)
            val sealed = unlocked.doFinal(privateKey)
            write(owner, Stored(address, unlocked.iv, sealed))
            return address to signatures
        } finally {
            privateKey.fill(0)
        }
    }

    /** One fingerprint check, then [hashes] signed with the backup key and the key wiped from memory. */
    suspend fun sign(owner: String, prompt: Prompt, hashes: List<ByteArray>): List<Rsv> {
        checkAvailable()
        val stored = read(owner) ?: throw PhoneKeyError.Missing()
        val key = keyStore().getKey(alias(owner), null) as? SecretKey ?: throw PhoneKeyError.Missing()
        val cipher = try {
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, stored.iv)) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw PhoneKeyError.Invalidated()
        }
        val privateKey = authenticate(prompt, cipher).doFinal(stored.sealed)
        try {
            check(SoftKey.address(privateKey).equals(stored.address, ignoreCase = true)) { "Backup key doesn't match its address" }
            return hashes.map { SoftKey.sign(privateKey, it) }
        } finally {
            privateKey.fill(0)
        }
    }

    /**
     * Discards a key Android has already destroyed ([PhoneKeyError.Invalidated]), so a new one can be made.
     * Refuses while the key still works: a working backup key is never thrown away.
     */
    fun forgetInvalidated(owner: String) {
        val key = keyStore().getKey(alias(owner), null) as? SecretKey
        if (key != null) {
            try {
                Cipher.getInstance(TRANSFORMATION).init(Cipher.ENCRYPT_MODE, key)
                error("The backup key still works; it isn't discarded")
            } catch (e: KeyPermanentlyInvalidatedException) {
                // destroyed by Android: safe to remove
            }
        }
        keyStore().deleteEntry(alias(owner))
        file(owner).delete()
    }

    // ------------------------------------------------------------------ Keystore

    private fun keyStore() = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun alias(owner: String) = "safechain_backup_${owner.lowercase()}"

    private fun newWrappingKey(owner: String): SecretKey {
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(alias(owner), KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Every use needs a fresh strong-biometric check; the screen PIN alone isn't enough.
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(-1)
                }
                if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true)
            }
            .build()

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        return try {
            generator.init(spec(strongBox = true))
            generator.generateKey()
        } catch (e: StrongBoxUnavailableException) {
            generator.init(spec(strongBox = false))
            generator.generateKey()
        }
    }

    private suspend fun authenticate(prompt: Prompt, cipher: Cipher): Cipher = withContext(Dispatchers.Main) {
        val activity = foregroundActivityProvider.foregroundActivity ?: throw PhoneKeyError.Unavailable("the app isn't in the foreground")
        suspendCancellableCoroutine { continuation ->
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val unlocked = result.cryptoObject?.cipher
                    if (unlocked == null) {
                        continuation.resumeWithException(PhoneKeyError.Unavailable("no key operation returned"))
                    } else {
                        continuation.resume(unlocked)
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (!continuation.isActive) return
                    continuation.resumeWithException(
                        when (errorCode) {
                            BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON, BiometricPrompt.ERROR_CANCELED ->
                                PhoneKeyError.Cancelled()
                            BiometricPrompt.ERROR_NO_BIOMETRICS -> PhoneKeyError.NoFingerprint()
                            else -> PhoneKeyError.Unavailable(errString.toString())
                        },
                    )
                }
            }
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(prompt.title)
                .setSubtitle(prompt.subtitle)
                .setAllowedAuthenticators(BIOMETRIC_STRONG)
                .setNegativeButtonText("Cancel")
                .setConfirmationRequired(true)
                .build()
            val executor = ContextCompat.getMainExecutor(activity)
            val biometricPrompt = BiometricPrompt(activity, executor, callback)
            continuation.invokeOnCancellation { executor.execute(biometricPrompt::cancelAuthentication) }
            biometricPrompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
        }
    }

    // ------------------------------------------------------------------ storage

    /** The encrypted key and its public address. Nothing here is usable without the Keystore key. */
    private data class Stored(val address: String, val iv: ByteArray, val sealed: ByteArray)

    private fun file(owner: String) = File(context.noBackupFilesDir, "safechain_backup_${owner.lowercase()}.key")

    private fun read(owner: String): Stored? {
        val lines = file(owner).takeIf { it.exists() }?.readLines()?.takeIf { it.size == STORED_LINES } ?: return null
        return Stored(address = lines[0], iv = Hex.decode(lines[1]), sealed = Hex.decode(lines[2]))
    }

    private fun write(owner: String, stored: Stored) {
        val tmp = File(context.noBackupFilesDir, "${file(owner).name}.tmp")
        tmp.writeText(listOf(stored.address, Hex.encode(stored.iv), Hex.encode(stored.sealed)).joinToString("\n"))
        check(tmp.renameTo(file(owner)) || (file(owner).delete() && tmp.renameTo(file(owner)))) { "Couldn't save the backup key" }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val TAG_BITS = 128
        const val STORED_LINES = 3
    }
}
