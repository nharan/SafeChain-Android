package com.tangem.tap.common.log

import android.app.Application
import com.chuckerteam.chucker.api.ChuckerInterceptor
import com.tangem.Log
import com.tangem.TangemSdkLogger
import com.tangem.blockchain.common.BlockchainSdkConfig
import com.tangem.blockchain.network.BlockchainSdkRetrofitBuilder
import com.tangem.datasource.local.config.environment.EnvironmentConfig
import com.tangem.datasource.local.logs.AppLogsStore
import com.tangem.datasource.local.logs.SensitiveUrlMasker
import com.tangem.datasource.remote.interceptors.HqTransparencyInterceptor
import com.tangem.datasource.remote.interceptors.WireMockRedirectInterceptor
import com.tangem.datasource.remote.interceptors.createNetworkLoggingInterceptor
import com.tangem.operations.attestation.api.TangemApiServiceSettings
import com.tangem.utils.JsonStringValuesExtractor
import com.tangem.utils.logging.TangemLogger
import com.tangem.wallet.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.Interceptor

/**
 * Owns all app-startup wiring of the logging subsystem in a single place:
 *  - [initAppLogging] — registers [TangemLogger] writers (Logcat only; zero disk logging).
 *  - [initSdkLogging] — installs RAM-only [HqTransparencyInterceptor] for the Blockchain SDK and Tangem Card SDK API.
 */
class TangemLoggingInitializer(
    private val appLogsStore: AppLogsStore,
    private val tangemSdkLogger: TangemSdkLogger,
    private val environmentConfig: EnvironmentConfig,
) {

    fun initAppLogging() {
        // Hardened build: never register FileLogWriter (0 bytes written to disk)
        TangemLogger.setLogWriters(
            buildList {
                if (BuildConfig.LOG_ENABLED) {
                    add(LogcatLogWriter())
                }
            },
        )
    }

    fun initSdkLogging(application: Application) {
        HqTransparencyInterceptor.registerApplication(application)

        BlockchainSdkRetrofitBuilder.interceptors = buildList {
            if (BuildConfig.MOCK_DATA_SOURCE) {
                add(WireMockRedirectInterceptor())
            }
            add(HqTransparencyInterceptor)
            addAll(createConsoleInterceptors(application))
        }

        TangemApiServiceSettings.addInterceptors(
            *buildList {
                if (BuildConfig.MOCK_DATA_SOURCE) {
                    add(WireMockRedirectInterceptor())
                }
                add(HqTransparencyInterceptor)
                addAll(createConsoleInterceptors(application))
            }.toTypedArray(),
        )
    }

    /**
     * Hardened build: never install [ChuckerInterceptor] (which persists HTTP traffic in a local SQLite database)
     * or [NetworkLogsSaveInterceptor] in any build variant, ensuring unconditional zero-disk HTTP logging.
     */
    private fun createConsoleInterceptors(application: Application): List<Interceptor> {
        return if (BuildConfig.LOG_ENABLED) {
            listOf(createNetworkLoggingInterceptor())
        } else {
            emptyList()
        }
    }

    private fun createBlockchainSensitiveUrlMasker(): SensitiveUrlMasker {
        val json = Json.encodeToJsonElement(
            BlockchainSdkConfig.serializer(),
            environmentConfig.blockchainSdkConfig,
        )
        // Drop URL-shaped drawable (e.g. public endpoint URLs from BlockchainSdkConfig like
        // kaspaSecondaryApiUrl); they are not secrets and would obscure unrelated requests in logs.
        val values = JsonStringValuesExtractor.extract(json)
            .filter { it.isNotBlank() && !it.startsWith("http", ignoreCase = true) }
        return SensitiveUrlMasker(values)
    }
}