package com.tangem.datasource.remote.interceptors

import com.google.common.truth.Truth.assertThat
import com.tangem.test.core.ProvideTestModels
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import java.io.IOException

internal class WalletIdPrivacyInterceptorTest {

    private val chain: Interceptor.Chain = mockk()
    private val response: Response = mockk()
    private val proceeded = slot<Request>()

    private val interceptor = WalletIdPrivacyInterceptor(pseudonymSecretProvider = { SECRET })

    @BeforeEach
    fun setUp() {
        clearMocks(chain, response)
        every { chain.proceed(capture(proceeded)) } returns response
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    inner class Policy {

        @ParameterizedTest
        @ProvideTestModels
        fun `GIVEN request WHEN intercept THEN only reviewed Tangem operations and non-Tangem hosts pass`(
            model: RequestModel,
        ) {
            // Arrange
            every { chain.request() } returns model.toRequest()

            // Act
            val error = runCatching { interceptor.intercept(chain) }.exceptionOrNull()

            // Assert
            assertBlockedOrProceeded(error, model.isBlocked)
        }

        @Test
        fun `GIVEN blocked walletId path WHEN intercept THEN error message does not contain the path`() {
            // Arrange
            every { chain.request() } returns Request.Builder().url("$GATEWAY/v1/referral/$WALLET_ID").build()

            // Act
            val error = runCatching { interceptor.intercept(chain) }.exceptionOrNull()

            // Assert
            assertThat(error).hasMessageThat().isEqualTo(WalletIdPrivacyInterceptor.BLOCKED_MESSAGE)
        }

        private fun provideTestModels() = listOf(
            // api.tangem.org: walletId-keyed, body-carrying and unknown endpoints are refused
            RequestModel(url = "$GATEWAY/api/v1/notification-preferences/$WALLET_ID", isBlocked = true),
            RequestModel(url = "$GATEWAY/v1/referral/$WALLET_ID", isBlocked = true),
            RequestModel(url = "$GATEWAY/v1/referral", method = "POST", isBlocked = true),
            RequestModel(url = "$GATEWAY/v1/referral/bind-wallets-by-code", method = "POST", isBlocked = true),
            RequestModel(url = "$GATEWAY/v1/banner/displays", isBlocked = true),
            RequestModel(url = "$GATEWAY/v2/promotion", isBlocked = true),
            RequestModel(url = "$GATEWAY/v1/address-books/sync", method = "POST", isBlocked = true),
            RequestModel(url = "$GATEWAY/api/v1/wallets/$WALLET_ID/card-backups", method = "POST", isBlocked = true),
            RequestModel(url = "$GATEWAY/v2/transaction-events", method = "POST", isBlocked = true),
            RequestModel(url = "$GATEWAY/promo/v1/promo-codes/activate", method = "POST", isBlocked = true),
            RequestModel(url = "$GATEWAY/api/authentication/v1/mobile/register", method = "POST", isBlocked = true),
            RequestModel(url = "$GATEWAY/api/v1/marketing/campaigns", isBlocked = true),
            RequestModel(url = "$GATEWAY/v1/some-endpoint-added-later", isBlocked = true),
            RequestModel(url = "$GATEWAY/v1/quotes", method = "POST", isBlocked = true),
            // wallet identifiers in the query are refused whatever their spelling
            RequestModel(url = "$GATEWAY/v1/quotes?walletId=$WALLET_ID", isBlocked = true),
            RequestModel(url = "$GATEWAY/v1/quotes?userWalletId=$WALLET_ID", isBlocked = true),
            RequestModel(url = "$GATEWAY/v1/quotes?user_wallet_id=$WALLET_ID", isBlocked = true),
            RequestModel(url = "$GATEWAY/v1/quotes?WalletIds=$WALLET_ID", isBlocked = true),
            RequestModel(url = "$GATEWAY/v1/quotes?userId=$WALLET_ID", isBlocked = true),
            RequestModel(url = "$YIELD/api/v1/yield/markets?wallet_id=$WALLET_ID", isBlocked = true),
            // service hosts: only their reviewed endpoints and methods
            RequestModel(url = "$EXPRESS/v1/some-endpoint-added-later", isBlocked = true),
            RequestModel(url = "$EXPRESS/v1/assets", isBlocked = true),
            RequestModel(url = "$YIELD/api/v1/some-endpoint-added-later", method = "POST", isBlocked = true),
            RequestModel(url = "$YIELD/api/v1/module/activate", isBlocked = true),
            RequestModel(url = "$GASLESS/api/v1/some-endpoint-added-later", isBlocked = true),
            RequestModel(url = "$GASLESS/api/v1/transaction/sign", isBlocked = true),
            // other Tangem hosts are refused
            RequestModel(url = "https://otlp.services.tangem.org/v1/metrics", method = "POST", isBlocked = true),
            RequestModel(url = "https://api.tangem-tech.com/v1/coins", isBlocked = true),
            // allowed
            RequestModel(url = "$GATEWAY/v1/quotes?coin_ids=bitcoin&currency_id=usd", isBlocked = false),
            RequestModel(url = "$GATEWAY/v1/coins/list", isBlocked = false),
            RequestModel(url = "$GATEWAY/v1/coins/bitcoin/history", isBlocked = false),
            RequestModel(url = "$GATEWAY/v1/networks/providers", isBlocked = false),
            RequestModel(url = "$GATEWAY/v1/news/trending", isBlocked = false),
            RequestModel(url = "$GATEWAY/v1/user-network-account", method = "POST", isBlocked = false),
            // Polymarket identifies users by raw walletId in bodies and queries, so it is refused entirely
            RequestModel(url = "$GATEWAY/api/predictions/v1/events", isBlocked = true),
            RequestModel(url = "$GATEWAY/api/predictions/v1/wallet/deploy", method = "POST", isBlocked = true),
            RequestModel(url = "$EXPRESS/v1/assets", method = "POST", isBlocked = false),
            RequestModel(url = "$EXPRESS/v1/exchange-quote?amount=1", isBlocked = false),
            RequestModel(url = "$EXPRESS/v1/history/delta/onramp", isBlocked = false),
            RequestModel(url = "$YIELD/api/v1/yield/markets", isBlocked = false),
            RequestModel(url = "$YIELD/api/v1/yield/token/1/0xabc/chart", isBlocked = false),
            RequestModel(url = "$YIELD/api/v1/module/activate", method = "POST", isBlocked = false),
            RequestModel(url = "$GASLESS/api/v2/transaction/batch-sign", method = "POST", isBlocked = false),
            RequestModel(url = "$GASLESS/api/v1/tron/tokens", isBlocked = false),
            // non-Tangem hosts are outside the policy
            RequestModel(url = "https://api.us.paera.com/v1/customer/wallets/1", isBlocked = false),
            RequestModel(url = "https://nottangem.org/v1/referral/$WALLET_ID", isBlocked = false),
        )
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    inner class Redirects {

        @Test
        fun `GIVEN a redirected hop to a refused endpoint WHEN network interceptor runs THEN it is blocked`() {
            // Arrange
            every { chain.request() } returns Request.Builder().url("$GATEWAY/v1/referral/$WALLET_ID").build()

            // Act
            val error = runCatching { TangemRequestPolicyNetworkInterceptor.intercept(chain) }.exceptionOrNull()

            // Assert
            assertBlockedOrProceeded(error, isBlocked = true)
        }

        @Test
        fun `GIVEN a redirected hop to another Tangem host WHEN network interceptor runs THEN it is blocked`() {
            // Arrange
            every { chain.request() } returns Request.Builder().url("https://otlp.services.tangem.org/v1/m").build()

            // Act
            val error = runCatching { TangemRequestPolicyNetworkInterceptor.intercept(chain) }.exceptionOrNull()

            // Assert
            assertBlockedOrProceeded(error, isBlocked = true)
        }

        @Test
        fun `GIVEN an allowed hop WHEN network interceptor runs THEN it proceeds unchanged`() {
            // Arrange
            val request = Request.Builder().url("$GATEWAY/v1/quotes").build()
            every { chain.request() } returns request

            // Act
            TangemRequestPolicyNetworkInterceptor.intercept(chain)

            // Assert
            assertThat(proceeded.captured).isSameInstanceAs(request)
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    inner class IdentifierHeaders {

        @ParameterizedTest
        @ProvideTestModels
        fun `GIVEN wallet identifier header WHEN intercept THEN value replaced by a pseudonym`(headerName: String) {
            // Act
            val actual = proceededHeader(headerName, WALLET_ID)

            // Assert
            assertThat(actual).doesNotContain(WALLET_ID)
            assertThat(actual).hasLength(PSEUDONYM_HEX_LENGTH)
        }

        private fun provideTestModels() = listOf("user-id", "wallet-id", "userWalletId", "User_Id", "x-wallet-ids")

        @Test
        fun `GIVEN same wallet WHEN intercept twice THEN pseudonym is stable`() {
            // Act
            val first = proceededHeader("wallet-id", WALLET_ID)
            val second = proceededHeader("wallet-id", WALLET_ID)

            // Assert
            assertThat(first).isEqualTo(second)
        }

        @Test
        fun `GIVEN two wallets WHEN intercept THEN pseudonyms differ`() {
            // Act
            val first = proceededHeader("user-id", WALLET_ID)
            val other = proceededHeader("user-id", OTHER_WALLET_ID)

            // Assert
            assertThat(first).isNotEqualTo(other)
        }

        @Test
        fun `GIVEN unrelated headers WHEN intercept THEN they are left unchanged`() {
            // Arrange
            every { chain.request() } returns Request.Builder()
                .url("$EXPRESS/v1/providers")
                .header("refcode", "ref")
                .header("api-key", "key")
                .build()

            // Act
            interceptor.intercept(chain)

            // Assert
            assertThat(proceeded.captured.header("refcode")).isEqualTo("ref")
            assertThat(proceeded.captured.header("api-key")).isEqualTo("key")
        }

        @Test
        fun `GIVEN same wallet on two services WHEN intercept THEN pseudonyms differ`() {
            // Act
            val express = proceededHeader("user-id", WALLET_ID, url = "$EXPRESS/v1/providers")
            val yield = proceededHeader("user-id", WALLET_ID, url = "$YIELD/api/v1/yield/markets")

            // Assert
            assertThat(express).isNotEqualTo(yield)
        }

        @Test
        fun `GIVEN no identifier header WHEN intercept THEN only the origin tag is added`() {
            // Arrange
            val request = Request.Builder().url("$EXPRESS/v1/providers").header("api-key", "key").build()
            every { chain.request() } returns request

            // Act
            interceptor.intercept(chain)

            // Assert
            assertThat(proceeded.captured.headers).isEqualTo(request.headers)
            assertThat(proceeded.captured.tag(RequestOrigin::class.java))
                .isEqualTo(RequestOrigin(scheme = "https", host = "express.tangem.com", port = 443))
        }

        private fun proceededHeader(name: String, value: String, url: String = "$EXPRESS/v1/providers"): String? {
            every { chain.request() } returns Request.Builder()
                .url(url)
                .header(name, value)
                .build()
            interceptor.intercept(chain)
            return proceeded.captured.header(name)
        }
    }

    private fun assertBlockedOrProceeded(error: Throwable?, isBlocked: Boolean) {
        if (isBlocked) {
            assertThat(error).isInstanceOf(IOException::class.java)
            verify(exactly = 0) { chain.proceed(any()) }
        } else {
            assertThat(error).isNull()
            verify(exactly = 1) { chain.proceed(any()) }
        }
    }

    data class RequestModel(val url: String, val isBlocked: Boolean, val method: String = "GET") {
        fun toRequest(): Request = Request.Builder()
            .url(url)
            .method(method, if (method == "GET") null else "{}".toRequestBody())
            .build()
    }

    private companion object {
        const val GATEWAY = "https://api.tangem.org"
        const val EXPRESS = "https://express.tangem.com"
        const val YIELD = "https://yield.tangem.org"
        const val GASLESS = "https://gasless.tangem.org"
        const val WALLET_ID = "A1B2C3D4E5F60718293A4B5C6D7E8F90A1B2C3D4E5F60718293A4B5C6D7E8F90"
        const val OTHER_WALLET_ID = "0F1E2D3C4B5A69788796A5B4C3D2E1F00F1E2D3C4B5A69788796A5B4C3D2E1F0"
        const val PSEUDONYM_HEX_LENGTH = 64
        val SECRET = ByteArray(32) { it.toByte() }
    }
}
