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
    inner class Blocking {

        @ParameterizedTest
        @ProvideTestModels
        fun `GIVEN request WHEN intercept THEN only reviewed Tangem operations and non-Tangem hosts pass`(
            model: BlockingModel,
        ) {
            // Arrange
            every { chain.request() } returns model.toRequest()

            // Act
            val error = runCatching { interceptor.intercept(chain) }.exceptionOrNull()

            // Assert
            if (model.isBlocked) {
                assertThat(error).isInstanceOf(IOException::class.java)
                verify(exactly = 0) { chain.proceed(any()) }
            } else {
                assertThat(error).isNull()
                verify(exactly = 1) { chain.proceed(any()) }
            }
        }

        @Test
        fun `GIVEN blocked walletId path WHEN intercept THEN error message does not contain the path`() {
            // Arrange
            every { chain.request() } returns
                Request.Builder().url("https://api.tangem.org/v1/referral/$WALLET_ID").build()

            // Act
            val error = runCatching { interceptor.intercept(chain) }.exceptionOrNull()

            // Assert
            assertThat(error).hasMessageThat().isEqualTo(WalletIdPrivacyInterceptor.BLOCKED_MESSAGE)
        }

        private fun provideTestModels() = listOf(
            BlockingModel(url = "$GATEWAY/api/v1/notification-preferences/$WALLET_ID", isBlocked = true),
            BlockingModel(url = "$GATEWAY/v1/referral/$WALLET_ID", isBlocked = true),
            BlockingModel(url = "$GATEWAY/v1/referral", method = "POST", isBlocked = true),
            BlockingModel(url = "$GATEWAY/v1/referral/bind-wallets-by-code", method = "POST", isBlocked = true),
            BlockingModel(url = "$GATEWAY/v1/banner/displays", isBlocked = true),
            BlockingModel(url = "$GATEWAY/v2/promotion", isBlocked = true),
            BlockingModel(url = "$GATEWAY/v1/address-books/sync", method = "POST", isBlocked = true),
            BlockingModel(url = "$GATEWAY/api/v1/wallets/$WALLET_ID/card-backups", method = "POST", isBlocked = true),
            BlockingModel(url = "$GATEWAY/v2/transaction-events", method = "POST", isBlocked = true),
            BlockingModel(url = "$GATEWAY/promo/v1/promo-codes/activate", method = "POST", isBlocked = true),
            BlockingModel(url = "$GATEWAY/api/authentication/v1/mobile/register", method = "POST", isBlocked = true),
            BlockingModel(url = "$GATEWAY/api/v1/marketing/campaigns", isBlocked = true),
            BlockingModel(url = "$GATEWAY/v1/some-endpoint-added-later", isBlocked = true),
            BlockingModel(url = "$GATEWAY/v1/quotes", method = "POST", isBlocked = true),
            BlockingModel(url = "$GATEWAY/v1/quotes?walletId=$WALLET_ID", isBlocked = true),
            BlockingModel(url = "https://otlp.services.tangem.org/v1/metrics", method = "POST", isBlocked = true),
            BlockingModel(url = "https://yield.tangem.org/api/v1/yield/markets?wallet_id=$WALLET_ID", isBlocked = true),
            BlockingModel(url = "$GATEWAY/v1/quotes?coin_ids=bitcoin&currency_id=usd", isBlocked = false),
            BlockingModel(url = "$GATEWAY/v1/coins/list", isBlocked = false),
            BlockingModel(url = "$GATEWAY/v1/coins/bitcoin/history", isBlocked = false),
            BlockingModel(url = "$GATEWAY/v1/networks/providers", isBlocked = false),
            BlockingModel(url = "$GATEWAY/v1/news/trending", isBlocked = false),
            BlockingModel(url = "$GATEWAY/v1/user-network-account", method = "POST", isBlocked = false),
            BlockingModel(url = "https://express.tangem.com/v1/assets", method = "POST", isBlocked = false),
            BlockingModel(url = "https://yield.tangem.org/api/v1/yield/markets", isBlocked = false),
            BlockingModel(url = "https://api.us.paera.com/v1/customer/wallets/1", isBlocked = false),
            BlockingModel(url = "https://nottangem.org/v1/referral/$WALLET_ID", isBlocked = false),
        )
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    inner class UserIdHeader {

        @Test
        fun `GIVEN user-id header WHEN intercept THEN walletId replaced by a stable pseudonym`() {
            // Act
            val first = proceededUserId(WALLET_ID)
            val second = proceededUserId(WALLET_ID)

            // Assert
            assertThat(first).isNotEqualTo(WALLET_ID)
            assertThat(first).doesNotContain(WALLET_ID)
            assertThat(first).isEqualTo(second)
            assertThat(first).hasLength(PSEUDONYM_HEX_LENGTH)
        }

        @Test
        fun `GIVEN two wallets WHEN intercept THEN pseudonyms differ`() {
            // Act
            val first = proceededUserId(WALLET_ID)
            val other = proceededUserId(OTHER_WALLET_ID)

            // Assert
            assertThat(first).isNotEqualTo(other)
        }

        @Test
        fun `GIVEN no user-id header WHEN intercept THEN request passes unchanged`() {
            // Arrange
            val request = Request.Builder().url("https://express.tangem.com/v1/assets").build()
            every { chain.request() } returns request

            // Act
            interceptor.intercept(chain)

            // Assert
            assertThat(proceeded.captured).isSameInstanceAs(request)
        }

        private fun proceededUserId(walletId: String): String? {
            every { chain.request() } returns Request.Builder()
                .url("https://express.tangem.com/v1/assets")
                .header(WalletIdPrivacyInterceptor.USER_ID_HEADER, walletId)
                .build()
            interceptor.intercept(chain)
            return proceeded.captured.header(WalletIdPrivacyInterceptor.USER_ID_HEADER)
        }
    }

    data class BlockingModel(val url: String, val isBlocked: Boolean, val method: String = "GET") {
        fun toRequest(): Request = Request.Builder()
            .url(url)
            .method(method, if (method == "GET") null else "{}".toRequestBody())
            .build()
    }

    private companion object {
        const val GATEWAY = "https://api.tangem.org"
        const val WALLET_ID = "A1B2C3D4E5F60718293A4B5C6D7E8F90A1B2C3D4E5F60718293A4B5C6D7E8F90"
        const val OTHER_WALLET_ID = "0F1E2D3C4B5A69788796A5B4C3D2E1F00F1E2D3C4B5A69788796A5B4C3D2E1F0"
        const val PSEUDONYM_HEX_LENGTH = 64
        val SECRET = ByteArray(32) { it.toByte() }
    }
}
