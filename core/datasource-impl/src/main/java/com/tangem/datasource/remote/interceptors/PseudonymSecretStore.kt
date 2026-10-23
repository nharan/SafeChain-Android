package com.tangem.datasource.remote.interceptors

import android.content.Context
import android.util.Base64
import java.security.SecureRandom

/**
 * Random per-install secret keying the `user-id` pseudonyms of [WalletIdPrivacyInterceptor]. Generated on first use
 * and kept in private app storage, which data-extraction rules exclude from cloud backup and device transfer, so a
 * reinstall or a new phone gets new pseudonyms that do not match the old ones.
 */
internal object PseudonymSecretStore {

    private const val PREFS_NAME = "hardened_privacy"
    private const val KEY_SECRET = "user_id_pseudonym_secret"
    private const val SECRET_SIZE_BYTES = 32

    @Volatile
    private var cached: ByteArray? = null

    fun getOrCreate(context: Context): ByteArray {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: loadOrGenerate(context).also { cached = it }
        }
    }

    private fun loadOrGenerate(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_SECRET, null)?.let { return Base64.decode(it, Base64.NO_WRAP) }

        val secret = ByteArray(SECRET_SIZE_BYTES).also(SecureRandom()::nextBytes)
        prefs.edit().putString(KEY_SECRET, Base64.encodeToString(secret, Base64.NO_WRAP)).apply()
        return secret
    }
}
