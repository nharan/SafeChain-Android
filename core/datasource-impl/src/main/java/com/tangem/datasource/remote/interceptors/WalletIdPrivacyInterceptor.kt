package com.tangem.datasource.remote.interceptors

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Hardened build: application-level guard on every Retrofit client.
 *
 * - Refuses Tangem requests that [TangemRequestPolicy] does not allow, before anything leaves the device. The failure
 *   is an [IOException] — the same as having no network, which every path already tolerates.
 * - Replaces the value of every wallet-identifier header ([TangemRequestPolicy.isWalletIdentifierName]: `user-id`,
 *   `wallet-id`, `userWalletId`, …) with HMAC-SHA256(on-device secret, host ‖ value). The pseudonym is stable per
 *   wallet *and service*, so each service keeps working but two services can't join their records by it, and the
 *   walletId itself is never sent. It is still a stable identifier within one service, which can link it to on-chain
 *   activity through the addresses of the operations it handles.
 * - Tags the request with its [RequestOrigin], which [TangemRequestPolicyNetworkInterceptor] enforces on every hop.
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

        val builder = request.newBuilder().tag(RequestOrigin::class.java, RequestOrigin.of(request.url))
        val service = request.url.host.lowercase(Locale.US)
        request.headers.names().filter(TangemRequestPolicy::isWalletIdentifierName).forEach { name ->
            builder.removeHeader(name)
            request.headers(name).forEach { value -> builder.addHeader(name, pseudonymize(service, value)) }
        }
        return chain.proceed(builder.build())
    }

    private fun pseudonymize(service: String, value: String): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(pseudonymSecretProvider(), HMAC_ALGORITHM))
        // the NUL separator keeps (service, value) pairs unambiguous
        mac.update(service.encodeToByteArray())
        mac.update(0)
        return mac.doFinal(value.encodeToByteArray()).joinToString(separator = "") { "%02x".format(it) }
    }

    internal companion object {
        const val USER_ID_HEADER = "user-id"
        const val BLOCKED_MESSAGE = "Request refused by the hardened network policy"
        private const val HMAC_ALGORITHM = "HmacSHA256"
    }
}

/** Scheme, host and port a request was issued to; OkHttp carries request tags over to redirect requests. */
internal data class RequestOrigin(val scheme: String, val host: String, val port: Int) {

    fun matches(url: HttpUrl): Boolean = this == of(url)

    companion object {
        fun of(url: HttpUrl) = RequestOrigin(url.scheme, url.host.lowercase(Locale.US), url.port)
    }
}

/**
 * Hardened build: network-level check run by OkHttp for every request it actually sends, redirects included.
 * - A hop to a different origin than the call was issued to is refused, whatever the destination: a redirect would
 *   otherwise carry the request's headers (api keys, session ids, pseudonyms) and, for 307/308, its body elsewhere.
 * - [TangemRequestPolicy] is re-applied, so a same-origin redirect to a refused endpoint is stopped too.
 * Blocking only — the headers it sees were already pseudonymized by [WalletIdPrivacyInterceptor].
 */
internal object TangemRequestPolicyNetworkInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val origin = request.tag(RequestOrigin::class.java)
        if (origin != null && !origin.matches(request.url)) {
            throw IOException(WalletIdPrivacyInterceptor.BLOCKED_MESSAGE)
        }
        if (!TangemRequestPolicy.isAllowed(request.method, request.url)) {
            throw IOException(WalletIdPrivacyInterceptor.BLOCKED_MESSAGE)
        }
        return chain.proceed(request)
    }
}
