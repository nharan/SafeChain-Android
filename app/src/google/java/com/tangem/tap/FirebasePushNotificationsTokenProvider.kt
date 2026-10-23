package com.tangem.tap

import com.tangem.utils.notifications.PushNotificationsTokenProvider
import javax.inject.Inject

/**
 * Hardened build: never request an FCM token. Fetching one creates a Firebase Installation and registers the
 * device with Google on every app start, even though push notifications are disabled.
 */
internal class FirebasePushNotificationsTokenProvider @Inject constructor() : PushNotificationsTokenProvider {
    override suspend fun getToken(): String = ""
}
