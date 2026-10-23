package com.tangem.grow.datasource.config

import com.google.common.truth.Truth.assertThat
import com.tangem.core.remote.config.ApiEnvironment
import com.tangem.utils.info.AppInfoProvider
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class YieldSupplyConfigTest {

    private val growEnvironmentConfig = mockk<GrowEnvironmentConfig>()
    private val appInfoProvider = mockk<AppInfoProvider>()

    // Lazy: YieldSupply builds its headers eagerly while building environmentConfigs, so the
    // config must be constructed after the mocks are stubbed in setup().
    private val config by lazy {
        YieldSupply(
            growEnvironmentConfig = growEnvironmentConfig,
            appInfoProvider = appInfoProvider,
        )
    }

    @BeforeEach
    fun setup() {
        clearMocks(growEnvironmentConfig, appInfoProvider)

        every { growEnvironmentConfig.yieldModuleApiKey } returns YIELD_API_KEY
        every { appInfoProvider.osVersion } returns OS_VERSION
        every { appInfoProvider.appVersion } returns VERSION_NAME
        every { appInfoProvider.language } returns LANGUAGE
        every { appInfoProvider.device } returns DEVICE
    }

    @Test
    fun `GIVEN config WHEN read id THEN YieldSupply id`() {
        // Assert
        assertThat(config.id).isEqualTo(YieldSupply.ID)
    }

    @Test
    fun `GIVEN config WHEN read environments THEN expected base urls`() {
        // Act
        val actual = config.environmentConfigs.associate { it.environment to it.baseUrl }

        // Assert
        assertThat(actual).containsExactlyEntriesIn(
            mapOf(
                ApiEnvironment.DEV to LOCALHOST_URL,
                ApiEnvironment.STAGE to LOCALHOST_URL,
                ApiEnvironment.MOCK to LOCALHOST_URL,
                ApiEnvironment.PROD to "https://yield.tangem.org/",
            ),
        )
    }

    @Test
    fun `GIVEN config WHEN read PROD headers THEN expected headers without card auth`() {
        // Arrange
        val prod = config.environmentConfigs.first { it.environment == ApiEnvironment.PROD }

        // Act
        val actual = prod.headers.mapValues { runBlocking { it.value() } }

        // Assert
        assertThat(actual.keys).containsExactly(
            "api-key", "system_version", "version", "platform", "language", "timezone", "device",
        )
        assertThat(actual).containsAtLeastEntriesIn(
            mapOf(
                "api-key" to YIELD_API_KEY,
                "system_version" to OS_VERSION,
                "version" to VERSION_NAME,
                "platform" to "android",
                "language" to LANGUAGE,
                "device" to DEVICE,
            ),
        )
        // timezone is derived from TimeZone.getDefault() at runtime — assert presence, not a fixed value.
        assertThat(actual["timezone"]).isNotNull()
    }

    private companion object {
        const val LOCALHOST_URL = "https://localhost/"
        const val YIELD_API_KEY = "yield_api_key"
        const val OS_VERSION = "Android 16"
        const val VERSION_NAME = "debug"
        const val LANGUAGE = "en-US"
        const val DEVICE = "manufacturer model"
    }
}
