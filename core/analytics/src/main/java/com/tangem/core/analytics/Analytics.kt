package com.tangem.core.analytics

import com.tangem.common.extensions.calculateSha256
import com.tangem.common.extensions.hexToBytes
import com.tangem.common.extensions.toHexString
import com.tangem.core.analytics.api.*
import com.tangem.core.analytics.models.AnalyticsEvent
import com.tangem.core.analytics.models.ExceptionAnalyticsEvent
import com.tangem.core.analytics.models.OneTimePerSessionEvent
import com.tangem.utils.coroutines.FeatureCoroutineExceptionHandler
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
[REDACTED_AUTHOR]
 */
interface GlobalAnalyticsEventHandler :
    AnalyticsEventHandler,
    AnalyticsHandlerHolder,
    AnalyticsFilterHolder,
    ParamsInterceptorHolder,
    AnalyticsErrorHandler,
    AnalyticsExceptionHandler,
    AnalyticsUserIdHandler

object Analytics : GlobalAnalyticsEventHandler {

    private val analyticsScope: CoroutineScope by lazy { createScope() }

    private val handlers = mutableMapOf<String, AnalyticsHandler>()
    private val paramsInterceptors = ConcurrentHashMap<String, ParamsInterceptor>()
    private val throttledEventsState = ConcurrentHashMap<String, Long>()
    private val analyticsFilters = mutableSetOf<AnalyticsEventFilter>()
    private val analyticsMutex = Mutex()

    private val analyticsHandlers: List<AnalyticsHandler>
        get() = handlers.values.toList()

    override fun addHandler(name: String, handler: AnalyticsHandler) {
        // Telemetry stripped: do not register any analytics handlers
    }

    override fun removeHandler(name: String): AnalyticsHandler? {
        return handlers.remove(name)
    }

    override fun addFilter(filter: AnalyticsEventFilter) {
        // Telemetry stripped
    }

    override fun removeFilter(filter: AnalyticsEventFilter): Boolean {
        return analyticsFilters.remove(filter)
    }

    override fun addParamsInterceptor(interceptor: ParamsInterceptor) {
        // Telemetry stripped
    }

    override fun removeParamsInterceptor(interceptorId: String): ParamsInterceptor? {
        return paramsInterceptors.remove(interceptorId)
    }

    override fun setUserId(userId: String) {
        // Telemetry stripped: never set or hash userId
    }

    override fun clearUserId() {
        // Telemetry stripped
    }

    override fun send(event: AnalyticsEvent) {
        // Telemetry stripped: drop all analytics events
    }

    override fun sendErrorEvent(event: AnalyticsEvent) {
        // Telemetry stripped: drop all error analytics events
    }

    override fun sendException(event: ExceptionAnalyticsEvent) {
        // Telemetry stripped: drop all exception analytics events
    }

    private suspend fun applyParamsInterceptors(event: AnalyticsEvent): MutableMap<String, String> {
        val interceptedParams = event.params.toMutableMap()
        analyticsMutex.withLock {
            paramsInterceptors.values
                .filter { it.canBeAppliedTo(event) }
                .forEach { it.intercept(interceptedParams) }
        }
        return interceptedParams
    }

    private fun shouldSendThrottledEvent(event: OneTimePerSessionEvent): Boolean {
        val now = System.currentTimeMillis()
        val id = event.oneTimeEventId
        return when (val throttleMs = event.throttleSeconds?.let(TimeUnit.SECONDS::toMillis)) {
            null -> throttledEventsState.putIfAbsent(id, now) == null
            else -> throttledEventsState.compute(id) { _, lastSendTime ->
                when {
                    lastSendTime == null -> now
                    now - lastSendTime >= throttleMs -> now
                    else -> lastSendTime
                }
            } == now
        }
    }

    private fun createScope(): CoroutineScope {
        val name = "Analytics"
        val dispatcher = Executors.newFixedThreadPool(1).asCoroutineDispatcher()
        val exHandler = FeatureCoroutineExceptionHandler.create(name)
        return CoroutineScope(SupervisorJob() + dispatcher + CoroutineName(name) + exHandler)
    }
}