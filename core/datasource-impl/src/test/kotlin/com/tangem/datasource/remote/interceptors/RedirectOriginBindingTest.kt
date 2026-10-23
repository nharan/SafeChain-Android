package com.tangem.datasource.remote.interceptors

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Real HTTP redirects between two servers, through a client wired the way `DefaultRetrofitFactory` wires it.
 */
internal class RedirectOriginBindingTest {

    private val origin = MockWebServer()
    private val elsewhere = MockWebServer()

    private val client = OkHttpClient.Builder()
        .addInterceptor(WalletIdPrivacyInterceptor(pseudonymSecretProvider = { ByteArray(32) }))
        .addNetworkInterceptor(TangemRequestPolicyNetworkInterceptor)
        .build()

    @BeforeEach
    fun setUp() {
        origin.start()
        elsewhere.start()
    }

    @AfterEach
    fun tearDown() {
        origin.shutdown()
        elsewhere.shutdown()
    }

    @Test
    fun `GIVEN a 307 redirect to another origin WHEN a POST is sent THEN refused AND nothing reaches it`() {
        // Arrange
        origin.enqueue(redirectTo(elsewhere.url("/collect").toString(), code = 307))
        elsewhere.enqueue(MockResponse().setBody("stolen"))
        val request = Request.Builder()
            .url(origin.url("/v1/assets"))
            .header("api-key", "secret-key")
            .post("""{"tokensList":[]}""".toRequestBody())
            .build()

        // Act
        val error = runCatching { client.newCall(request).execute().close() }.exceptionOrNull()

        // Assert
        assertThat(error).isInstanceOf(IOException::class.java)
        assertThat(origin.requestCount).isEqualTo(1)
        assertThat(elsewhere.requestCount).isEqualTo(0)
    }

    @Test
    fun `GIVEN a 302 redirect to another origin WHEN a GET is sent THEN refused AND nothing reaches it`() {
        // Arrange
        origin.enqueue(redirectTo(elsewhere.url("/collect").toString(), code = 302))
        elsewhere.enqueue(MockResponse().setBody("stolen"))

        // Act
        val error = runCatching {
            client.newCall(Request.Builder().url(origin.url("/v1/quotes")).build()).execute().close()
        }.exceptionOrNull()

        // Assert
        assertThat(error).isInstanceOf(IOException::class.java)
        assertThat(elsewhere.requestCount).isEqualTo(0)
    }

    @Test
    fun `GIVEN a redirect within the same origin WHEN a GET is sent THEN it is followed`() {
        // Arrange
        origin.enqueue(redirectTo("/v1/quotes/moved", code = 302))
        origin.enqueue(MockResponse().setBody("ok"))

        // Act
        val body = client.newCall(Request.Builder().url(origin.url("/v1/quotes")).build()).execute()
            .use { it.body?.string() }

        // Assert
        assertThat(body).isEqualTo("ok")
        assertThat(origin.requestCount).isEqualTo(2)
        assertThat(origin.takeRequest(1, TimeUnit.SECONDS)?.path).isEqualTo("/v1/quotes")
        assertThat(origin.takeRequest(1, TimeUnit.SECONDS)?.path).isEqualTo("/v1/quotes/moved")
    }

    private fun redirectTo(location: String, code: Int) = MockResponse()
        .setResponseCode(code)
        .setHeader("Location", location)
}
