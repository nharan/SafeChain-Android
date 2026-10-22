package com.tangem.data.notifications

import androidx.datastore.preferences.core.edit
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.tangem.data.notifications.converters.NotificationsEligibleNetworkConverter
import com.tangem.core.remote.response.ApiResponse
import com.tangem.core.remote.response.ApiResponseError
import com.tangem.datasource.api.common.response.getOrThrow
import com.tangem.datasource.api.tangemTech.TangemTechApi
import com.tangem.datasource.api.tangemTech.models.NotificationApplicationCreateBody
import com.tangem.domain.appsflyer.repository.AppsFlyerRepository
import com.tangem.datasource.local.preferences.AppPreferencesStore
import com.tangem.store.datasource.preferences.StorePreferencesKeys
import com.tangem.datasource.local.preferences.utils.getSyncOrNull
import com.tangem.datasource.local.preferences.utils.store
import com.tangem.domain.models.wallet.UserWalletId
import com.tangem.domain.notifications.models.ApplicationId
import com.tangem.domain.notifications.models.NotificationsError
import com.tangem.domain.notifications.repository.PushNotificationsRepository
import com.tangem.domain.notifications.models.NotificationsEligibleNetwork
import com.tangem.datasource.local.preferences.utils.getObjectMap
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import com.tangem.utils.coroutines.CoroutineDispatcherProvider
import com.tangem.utils.info.AppInfoProvider
import kotlinx.coroutines.withContext
import javax.inject.Inject

internal class DefaultPushNotificationsRepository @Inject constructor(
    private val tangemTechApi: TangemTechApi,
    private val appInfoProvider: AppInfoProvider,
    private val appPreferencesStore: AppPreferencesStore,
    private val storePreferencesKeys: StorePreferencesKeys,
    private val appsFlyerRepository: AppsFlyerRepository,
    private val dispatchers: CoroutineDispatcherProvider,
) : PushNotificationsRepository {

    override suspend fun createApplicationId(pushToken: String?): ApplicationId {
        // Hardened build: never register device metadata, AppsFlyer UID, or push tokens with Tangem backend
        return ApplicationId("local-disabled")
    }

    override suspend fun saveApplicationId(appId: ApplicationId) {
        appPreferencesStore.store(storePreferencesKeys.notificationsApplicationIdKey, appId.value)
    }

    override suspend fun getApplicationId(): ApplicationId? {
        return appPreferencesStore.getSyncOrNull(storePreferencesKeys.notificationsApplicationIdKey)
            ?.let(::ApplicationId)
    }

    override suspend fun clearApplicationId() {
        appPreferencesStore.edit { mutablePreferences ->
            mutablePreferences.remove(storePreferencesKeys.notificationsApplicationIdKey)
        }
    }

    override suspend fun sendPushToken(appId: ApplicationId, pushToken: String): Either<NotificationsError, Unit> {
        // Hardened build: never send push token or OS/timezone telemetry to Tangem backend
        return Unit.right()
    }

    override suspend fun getEligibleNetworks(): List<NotificationsEligibleNetwork> {
        return emptyList()
    }

    override suspend fun isNotificationsEnabled(userWalletId: UserWalletId): Boolean =
        appPreferencesStore.getObjectMap<Boolean>(storePreferencesKeys.notificationsEnabledStatesKey)
            .map { it[userWalletId.stringValue] == true }
            .firstOrNull() == true
}