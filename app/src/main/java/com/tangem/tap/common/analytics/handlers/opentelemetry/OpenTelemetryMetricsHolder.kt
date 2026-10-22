package com.tangem.tap.common.analytics.handlers.opentelemetry

import android.app.Application
import com.tangem.datasource.local.config.environment.EnvironmentConfig
import com.tangem.domain.settings.repositories.SettingsRepository
import com.tangem.utils.coroutines.AppCoroutineScope
import com.tangem.utils.coroutines.CoroutineDispatcherProvider
import com.tangem.utils.logging.TangemLogger
import io.opentelemetry.api.metrics.Meter
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**

 * (its disk storage must not be touched on the main thread) and only when the feature toggle is on
 * and the OTLP api key is configured, so consumers observe it through this holder instead of
 * injecting the client directly.
 */
@Singleton
class OpenTelemetryMetricsHolder @Inject constructor(
    private val application: Application,
    private val environmentConfig: EnvironmentConfig,
    private val featureToggles: OtelFeatureToggles,
    private val settingsRepository: SettingsRepository,
    private val dispatchers: CoroutineDispatcherProvider,
    private val appScope: AppCoroutineScope,
) {

    @Volatile
    private var client: OpenTelemetryMetricsClient? = null

    fun initialize() {
        // Telemetry stripped: OpenTelemetry metrics disabled
    }

    fun getMeter(): Meter? = client?.getMeter()

    fun flush() {
        client?.flush()
    }
}