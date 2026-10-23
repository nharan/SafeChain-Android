package com.tangem.datasource.remote.interceptors

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Hardened build: application-level guard on every Retrofit client.
 *
 * - Refuses Tangem requests that [TangemRequestPolicy] does not allow, before anything leaves the device. The failure
 *   is an [IOException] — the same as having no network, which every path already tolerates.
 * - Replaces the value of every wallet-identifier header ([TangemRequestPolicy.isWalletIdentifierName]: `user-id`,
 *   `wallet-id`, `userWalletId`, …) with HMAC-SHA256(on-device secret, value). The pseudonym is stable per wallet, so
 *   services keep working, but the walletId itself is never sent. It is still a stable identifier: a service that
 *   also sees an operation's addresses can link it to on-chain activity.
 *
 * Redirects are re-checked by [TangemRequestPolicyNetworkInterceptor]; this interceptor sees only the first request.
 */
internal class WalletIdPrivacyInterceptor(
    private val pseudonymSecretProvider: () -> ByteArray,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        if (!TangemRequestPolicy.isAllowed(request.method, request.url)) {
            // the path is deliberately left out: it may itself contain a walletId
            throw IOException(BLOCKED_MESSAGE)
        }

        val identifierHeaders = request.headers.names().filter(TangemRequestPolicy::isWalletIdentifierName)
        if (identifierHeaders.isEmpty()) return chain.proceed(request)

        val builder = request.newBuilder()
        identifierHeaders.forEach { name ->
            builder.removeHeader(name)
            request.headers(name).forEach { value -> builder.addHeader(name, pseudonymize(value)) }
        }
        return chain.proceed(builder.build())
    }

    private fun pseudonymize(value: String): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(pseudonymSecretProvider(), HMAC_ALGORITHM))
        return mac.doFinal(value.encodeToByteArray()).joinToString(separator = "") { "%02x".format(it) }
    }

    internal companion object {
        const val USER_ID_HEADER = "user-id"
        const val BLOCKED_MESSAGE = "Request refused by the hardened network policy"
        private const val HMAC_ALGORITHM = "HmacSHA256"
    }
}

/**
 * Hardened build: network-level re-check of [TangemRequestPolicy]. OkHttp runs network interceptors for every request
 * it actually sends, so a redirect from an allowed endpoint to a refused one is stopped here. Blocking only — the
 * headers it sees were already pseudonymized by [WalletIdPrivacyInterceptor].
 */
internal object TangemRequestPolicyNetworkInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!TangemRequestPolicy.isAllowed(request.method, request.url)) {
            throw IOException(WalletIdPrivacyInterceptor.BLOCKED_MESSAGE)
        }
        return chain.proceed(request)
    }
}
