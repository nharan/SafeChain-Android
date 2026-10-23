package com.tangem.tap

import com.tangem.utils.notifications.PushNotificationsTokenProvider
import javax.inject.Inject

/**
 * Hardened build: never request an FCM or HMS push token — either one registers the device with Google/Huawei
 * on every app start, even though push notifications are disabled.
 */
internal class HuaweiPushNotificationsTokenProvider @Inject constructor() : PushNotificationsTokenProvider {
    override suspend fun getToken(): String = ""
}
