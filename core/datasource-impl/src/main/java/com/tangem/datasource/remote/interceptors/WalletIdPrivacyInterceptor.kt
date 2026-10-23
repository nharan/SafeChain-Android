package com.tangem.datasource.remote.interceptors

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Hardened build: keeps the user's walletIds away from Tangem's backend, at the one place every Retrofit client
 * passes through, so no repository — present or future — can send them.
 *
 * - Requests to Tangem hosts on walletId-keyed endpoints (or carrying a walletId query parameter) are refused with an
 *   [IOException] before anything leaves the device. Callers see the same failure as being offline, which every path
 *   already tolerates because the app routinely runs without network.
 * - The `user-id` header (the walletId, sent to Express / Onramp / Collectibles) is replaced with
 *   HMAC-SHA256(on-device secret, walletId): stable per wallet so swap history still resolves, but not linkable to
 *   the walletId or across wallets.
 */
internal class WalletIdPrivacyInterceptor(
    private val pseudonymSecretProvider: () -> ByteArray,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url

        if (isTangemHost(url.host) && (isWalletKeyedPath(url.encodedPath) || hasWalletIdQuery(url.queryParameterNames))) {
            throw IOException("Blocked by hardened build: walletId request to ${url.host}${url.encodedPath}")
        }

        val userId = request.header(USER_ID_HEADER) ?: return chain.proceed(request)
        val pseudonymous = request.newBuilder()
            .header(USER_ID_HEADER, pseudonymize(userId))
            .build()
        return chain.proceed(pseudonymous)
    }

    private fun isTangemHost(host: String): Boolean = TANGEM_DOMAINS.any { domain ->
        host.equals(domain, ignoreCase = true) || host.endsWith(".$domain", ignoreCase = true)
    }

    private fun isWalletKeyedPath(path: String): Boolean {
        val normalized = path.lowercase(Locale.US)
        return WALLET_KEYED_PATH_PREFIXES.any { prefix -> normalized.contains(prefix) }
    }

    private fun hasWalletIdQuery(names: Set<String>): Boolean =
        names.any { it.lowercase(Locale.US) in WALLET_ID_QUERY_NAMES }

    private fun pseudonymize(value: String): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(pseudonymSecretProvider(), HMAC_ALGORITHM))
        return mac.doFinal(value.encodeToByteArray()).joinToString(separator = "") { "%02x".format(it) }
    }

    internal companion object {
        const val USER_ID_HEADER = "user-id"
        private const val HMAC_ALGORITHM = "HmacSHA256"

        private val TANGEM_DOMAINS = listOf("tangem.org", "tangem.com", "tangem-tech.com")

        private val WALLET_KEYED_PATH_PREFIXES = listOf(
            "/notification-preferences",
            "/v1/referral",
            "/v1/banner/displays",
            "/v2/promotion",
            "/v1/address-books",
            "/v1/wallets",
            "/v2/wallets",
            "/v1/user-wallets",
            "/v1/seedphrase-notification",
            "/user-tokens",
        )

        private val WALLET_ID_QUERY_NAMES = setOf("walletid", "wallet_id", "walletids", "wallet_ids")
    }
}
