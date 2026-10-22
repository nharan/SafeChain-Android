package com.tangem.tap

import android.app.Application
import android.os.Build
import android.os.StrictMode
import android.os.StrictMode.ThreadPolicy
import android.os.StrictMode.VmPolicy
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.tangem.blockchain.common.ExceptionHandler
import com.tangem.core.abtests.manager.ABTestsManager
import com.tangem.core.analytics.Analytics
import com.tangem.core.analytics.models.event.TechAnalyticsEvent
import com.tangem.core.ui.components.background.northernlights.NorthernLightsCrashGuard
import com.tangem.core.ui.components.background.northernlights.NorthernLightsCrashGuardPreferences
import com.tangem.core.analytics.filter.AppsFlyerEventFilter
import com.tangem.core.analytics.filter.OneTimeEventFilter
import com.tangem.core.configtoggle.blockchain.ExcludedBlockchainsManager
import com.tangem.core.configtoggle.feature.FeatureTogglesManager
import com.tangem.core.remote.config.managers.ApiConfigsManager
import com.tangem.datasource.local.config.environment.EnvironmentConfig
import com.tangem.domain.apptheme.GetAppThemeModeUseCase
import com.tangem.domain.common.LogConfig
import com.tangem.domain.common.wallets.UserWalletsListRepository
import com.tangem.domain.wallets.repository.WalletsRepository
import com.tangem.lib.auth.AuthFeatureToggles
import com.tangem.lib.auth.devicekey.DeviceKeyManager
import com.tangem.lib.auth.session.DeviceRegistrar
import com.tangem.tap.common.analytics.AnalyticsFactory
import com.tangem.tap.common.analytics.api.AnalyticsHandlerBuilder
import com.tangem.tap.common.analytics.handlers.BlockchainExceptionHandler
import com.tangem.tap.common.analytics.handlers.amplitude.AmplitudeAnalyticsHandler
import com.tangem.tap.common.analytics.handlers.appsflyer.AppsFlyerAnalyticsHandler
import com.tangem.tap.common.analytics.handlers.appsflyer.AppsFlyerClient
import com.tangem.tap.common.analytics.handlers.customerio.CustomerIoAnalyticsHandler
import com.tangem.tap.common.analytics.handlers.firebase.FirebaseAnalyticsHandler
import com.tangem.tap.common.analytics.handlers.opentelemetry.OpenTelemetryAnalyticsHandler
import com.tangem.tap.common.analytics.handlers.opentelemetry.OpenTelemetryMetricsHolder
import com.tangem.tap.common.images.createCoilImageLoader
import com.tangem.tap.common.log.TangemLoggingInitializer
import com.tangem.tap.domain.walletregistration.WalletRegistrationLauncher
import com.tangem.utils.coroutines.runSuspendCatching
import com.tangem.utils.logging.TangemLogger
import com.tangem.wallet.BuildConfig
import dagger.hilt.EntryPoints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

lateinit var walletsRepository: WalletsRepository

val foregroundActivityObserver = ForegroundActivityObserver

open class TangemApplication : Application(), ImageLoaderFactory, Configuration.Provider {

    // region DI
    private val entryPoint: ApplicationEntryPoint
        get() = EntryPoints.get(this, ApplicationEntryPoint::class.java)

    private val environmentConfig: EnvironmentConfig
        get() = entryPoint.getEnvironmentConfig()

    private val featureTogglesManager: FeatureTogglesManager
        get() = entryPoint.getFeatureTogglesManager()

    private val excludedBlockchainsManager: ExcludedBlockchainsManager
        get() = entryPoint.getExcludedBlockchainsManager()

    val getAppThemeModeUseCase: GetAppThemeModeUseCase
        get() = entryPoint.getGetAppThemeModeUseCase()

    private val oneTimeEventFilter: OneTimeEventFilter
        get() = entryPoint.getOneTimeEventFilter()

    private val tangemLoggingInitializer: TangemLoggingInitializer
        get() = entryPoint.getTangemLoggingInitializer()

    private val blockchainExceptionHandler: BlockchainExceptionHandler
        get() = entryPoint.getBlockchainExceptionHandler()

    private val workerFactory: HiltWorkerFactory
        get() = entryPoint.getWorkerFactory()

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    private val apiConfigsManager: ApiConfigsManager
        get() = entryPoint.getApiConfigsManager()

    private val wcInitializeUseCase
        get() = entryPoint.getWcInitializeUseCase()

    private val abTestsManager: ABTestsManager
        get() = entryPoint.getABTestsManager()

    private val appsFlyerClientFactory: AppsFlyerClient.Factory
        get() = entryPoint.getAppsFlyerClientFactory()

    private val sendTransactionSignerInfoInterceptor
        get() = entryPoint.getSendTransactionSignerInfoInterceptor()

    private val deviceKeyManager: DeviceKeyManager
        get() = entryPoint.getDeviceKeyManager()

    private val deviceRegistrar: DeviceRegistrar
        get() = entryPoint.getDeviceRegistrar()

    private val authFeatureToggles: AuthFeatureToggles
        get() = entryPoint.getAuthFeatureToggles()

    private val walletRegistrationLauncher: WalletRegistrationLauncher
        get() = entryPoint.getWalletRegistrationLauncher()

    private val userWalletsListRepository: UserWalletsListRepository
        get() = entryPoint.getUserWalletsListRepository()

    private val openTelemetryMetricsHolder: OpenTelemetryMetricsHolder
        get() = entryPoint.getOpenTelemetryMetricsHolder()

    // endregion

    private val appScope = MainScope()

    override fun onCreate() {
        enableStrictModeInDebug()
        preInit()
        super.onCreate()
        init()
    }

    private fun enableStrictModeInDebug() {
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectAll()
                    .penaltyLog()
                    .build(),
            )
            StrictMode.setVmPolicy(
                VmPolicy.Builder()
                    .detectLeakedSqlLiteObjects()
                    .detectLeakedClosableObjects()
                    .penaltyLog()
                    .build(),
            )
        }
    }

    /**
     * Initialize components that need to be initialized before [super.onCreate] is called
     */
    fun preInit() {
        tangemLoggingInitializer.initAppLogging()
        NorthernLightsCrashGuard.init(
            store = NorthernLightsCrashGuardPreferences(context = this),
            appVersion = BuildConfig.VERSION_CODE,
        )
        registerActivityLifecycleCallbacks(foregroundActivityObserver.callbacks)
    }

    fun init() {
        // Telemetry & backend device/wallet registration stripped
        walletsRepository = entryPoint.getWalletsRepository()

        apiConfigsManager.initialize()

        TangemLogger.i("APP STARTED")
        if (BuildConfig.TESTER_MENU_ENABLED) {
            TangemLogger.i(featureTogglesManager.toString())
            TangemLogger.i(excludedBlockchainsManager.toString())
        }

        appScope.launch {
            launch(Dispatchers.IO) {
                loadNativeLibraries()
            }
        }

        tangemLoggingInitializer.initSdkLogging(this)

        wcInitializeUseCase.init(
            projectId = environmentConfig.walletConnectProjectId,
        )
    }

    override fun newImageLoader(): ImageLoader = createCoilImageLoader(context = this)

    private fun loadNativeLibraries() {
        System.loadLibrary("TrustWalletCore")
    }

    private fun reportShaderCrashIfDetected() {
        // Telemetry stripped
    }

    private fun initAnalytics(application: Application, environmentConfig: EnvironmentConfig) {
        // Telemetry stripped: Amplitude, Firebase, AppsFlyer, Customer.io, OpenTelemetry disabled
    }

    private fun initOpenTelemetry() {
        // Telemetry stripped
    }
}