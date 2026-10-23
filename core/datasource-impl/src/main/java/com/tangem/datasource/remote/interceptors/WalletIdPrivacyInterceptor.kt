package com.tangem.datasource.remote.interceptors

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Hardened build: a default-deny policy for every request a Retrofit client sends to a Tangem-operated host.
 *
 * Request bodies can't be inspected without buffering them, so instead of looking for walletIds the policy only lets
 * through operations that were reviewed and carry none:
 * - `api.tangem.org` — public catalogue/price/market data GETs, plus two user-initiated operations (Hedera account
 *   creation, Polymarket). Everything else on it — walletId-keyed endpoints, referral, promo, card-backup, transaction
 *   event and auth calls, and any endpoint added later — is refused.
 * - `express`, `yield`, `gasless` hosts — the swap/onramp, yield and gasless services the user invokes. They see
 *   addresses and transaction data of those operations by necessity; the walletId in their `user-id` header is
 *   replaced with HMAC-SHA256(on-device secret, walletId).
 * - Any other Tangem host is refused.
 * Any Tangem request carrying a walletId query parameter is refused regardless. A refused request fails with an
 * [IOException], the same failure as having no network, before anything leaves the device.
 */
internal class WalletIdPrivacyInterceptor(
    private val pseudonymSecretProvider: () -> ByteArray,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        if (isTangemHost(request.url.host) && !isAllowedTangemRequest(request)) {
            // the path is deliberately left out: it may itself contain a walletId
            throw IOException(BLOCKED_MESSAGE)
        }

        val userId = request.header(USER_ID_HEADER) ?: return chain.proceed(request)
        val pseudonymous = request.newBuilder()
            .header(USER_ID_HEADER, pseudonymize(userId))
            .build()
        return chain.proceed(pseudonymous)
    }

    private fun isTangemHost(host: String): Boolean = TANGEM_DOMAINS.any { domain -> host.isSameOrSubdomainOf(domain) }

    private fun isAllowedTangemRequest(request: Request): Boolean {
        val url = request.url
        if (url.queryParameterNames.any { it.lowercase(Locale.US) in WALLET_ID_QUERY_NAMES }) return false

        val host = url.host.lowercase(Locale.US)
        return when {
            host == GATEWAY_HOST -> GATEWAY_ALLOWLIST.any { it.matches(request.method, url.encodedPath) }
            else -> SERVICE_HOSTS.any { host == it }
        }
    }

    private fun String.isSameOrSubdomainOf(domain: String): Boolean =
        equals(domain, ignoreCase = true) || endsWith(".$domain", ignoreCase = true)

    private fun pseudonymize(value: String): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(pseudonymSecretProvider(), HMAC_ALGORITHM))
        return mac.doFinal(value.encodeToByteArray()).joinToString(separator = "") { "%02x".format(it) }
    }

    private class Rule(private val method: String, pathPattern: String) {
        private val path = Regex(pathPattern, RegexOption.IGNORE_CASE)

        fun matches(method: String, encodedPath: String): Boolean =
            this.method.equals(method, ignoreCase = true) && path.matches(encodedPath)
    }

    internal companion object {
        const val USER_ID_HEADER = "user-id"
        const val BLOCKED_MESSAGE = "Request refused by the hardened network policy"
        private const val HMAC_ALGORITHM = "HmacSHA256"

        private val TANGEM_DOMAINS = listOf("tangem.org", "tangem.com", "tangem-tech.com")
        private const val GATEWAY_HOST = "api.tangem.org"
        private val SERVICE_HOSTS = setOf("express.tangem.com", "yield.tangem.org", "gasless.tangem.org")

        private val GATEWAY_ALLOWLIST = listOf(
            Rule("GET", "/v1/coins(/.*)?"),
            Rule("GET", "/api/v1/coins/indicators"),
            Rule("GET", "/api/v1/market-indicators"),
            Rule("GET", "/v1/currencies"),
            Rule("GET", "/v1/geo"),
            Rule("GET", "/v1/application/versions"),
            Rule("GET", "/v1/quotes"),
            Rule("GET", "/v1/hot_crypto"),
            Rule("GET", "/v1/stories/[^/]+"),
            Rule("GET", "/v1/earn/(markets|networks)"),
            Rule("GET", "/v1/networks/providers"),
            Rule("GET", "/v1/news(/.*)?"),
            // user-initiated: creating a Hedera account sends that network's public key
            Rule("POST", "/v1/user-network-account"),
            // user-initiated: the Polymarket feature, which needs its own wallet addresses
            Rule("GET", "/api/predictions/v1/.+"),
            Rule("POST", "/api/predictions/v1/.+"),
        )

        private val WALLET_ID_QUERY_NAMES = setOf("walletid", "wallet_id", "walletids", "wallet_ids")
    }
}
