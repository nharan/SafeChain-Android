package com.tangem.features.onboarding.v2.multiwallet.impl.common

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import com.tangem.core.decompose.ui.UiMessageSender
import com.tangem.core.ui.extensions.stringReference
import com.tangem.core.ui.message.DialogMessage
import com.tangem.core.ui.message.EventMessageAction
import com.tangem.features.onboarding.v2.common.ui.OnboardingDialogUM
import com.tangem.features.onboarding.v2.multiwallet.impl.child.backup.ui.state.MultiWalletBackupUMDialog
import java.net.NetworkInterface
import java.util.Collections

/**
 * Hardened security guardrail that verifies Airplane Mode is ON and all network radios
 * (Wi-Fi, Cellular, Ethernet, VPN/Tether) are completely disconnected before allowing
 * seed phrase entry/import or backup finalization (encrypted key transfer).
 */
internal object AirplaneModeGuardrail {

    @Volatile
    private var appContext: Context? = null

    fun registerContext(context: Context) {
        appContext = context.applicationContext ?: context
    }

    private fun resolveContext(): Context? {
        appContext?.let { return it }
        return runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val app = activityThread.getMethod("currentApplication").invoke(null) as? Context
            app?.also { appContext = it }
        }.getOrNull()
    }

    data class RadioStatus(
        val isAirplaneModeOn: Boolean,
        val isWifiActive: Boolean,
        val isCellularActive: Boolean,
        val isOtherNetworkActive: Boolean,
    ) {
        val isStrictlyOffline: Boolean
            get() = isAirplaneModeOn && !isWifiActive && !isCellularActive && !isOtherNetworkActive

        fun describeActiveRadios(): String {
            val issues = mutableListOf<String>()
            if (!isAirplaneModeOn) issues.add("Airplane Mode is OFF")
            if (isWifiActive) issues.add("Wi-Fi is ON/Connected")
            if (isCellularActive) issues.add("Mobile Data is ON/Connected")
            if (isOtherNetworkActive) issues.add("Active network interface detected")
            return if (issues.isEmpty()) "All radios OFF (Offline)" else issues.joinToString(", ")
        }
    }

    fun checkStatus(): RadioStatus {
        val context = resolveContext()
        val isAirplaneOn = if (context != null) {
            runCatching {
                Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
            }.getOrDefault(false)
        } else {
            false
        }

        var wifiActive = false
        var cellularActive = false
        var otherActive = false

        if (context != null) {
            runCatching {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                @Suppress("DEPRECATION")
                val networks = cm?.allNetworks.orEmpty()
                for (network in networks) {
                    val caps = cm?.getNetworkCapabilities(network) ?: continue
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        wifiActive = true
                    }
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                        cellularActive = true
                    }
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)
                    ) {
                        otherActive = true
                    }
                }
            }
        }

        runCatching {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (ni in interfaces) {
                if (!ni.isUp || ni.isLoopback) continue
                val name = ni.name.lowercase()
                val hasAddresses = Collections.list(ni.inetAddresses).any { !it.isLoopbackAddress }
                if (!hasAddresses) continue
                when {
                    name.startsWith("wlan") || name.startsWith("swlan") || name.startsWith("ap") -> wifiActive = true
                    name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp") -> {
                        cellularActive = true
                    }
                    name.startsWith("eth") || name.startsWith("rndis") || name.startsWith("usb") -> {
                        otherActive = true
                    }
                }
            }
        }

        return RadioStatus(
            isAirplaneModeOn = isAirplaneOn,
            isWifiActive = wifiActive,
            isCellularActive = cellularActive,
            isOtherNetworkActive = otherActive,
        )
    }

    fun openAirplaneSettings() {
        val context = resolveContext() ?: return
        runCatching {
            val intent = Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    fun buildBlockedDialogMessage(actionName: String, status: RadioStatus = checkStatus()): DialogMessage {
        return DialogMessage(
            title = stringReference("🛑 AIRPLANE MODE REQUIRED"),
            message = stringReference(
                "Hardened Security Guardrail blocked: $actionName.\n\n" +
                    "Current Status: ${status.describeActiveRadios()}\n\n" +
                    "To guarantee zero network activity while handling key material, please:\n" +
                    "1. Turn ON Airplane Mode\n" +
                    "2. Ensure Wi-Fi and Mobile Data are OFF\n" +
                    "3. Tap Retry.",
            ),
            firstAction = EventMessageAction(
                title = stringReference("Open Settings"),
                onClick = ::openAirplaneSettings,
            ),
            secondAction = EventMessageAction(
                title = stringReference("OK"),
                onClick = {},
            ),
        )
    }

    fun buildOnboardingDialogUM(
        actionName: String,
        status: RadioStatus = checkStatus(),
        onDismiss: () -> Unit,
    ): OnboardingDialogUM {
        return OnboardingDialogUM(
            title = stringReference("🛑 AIRPLANE MODE REQUIRED"),
            message = stringReference(
                "Hardened Security Guardrail blocked: $actionName.\n\n" +
                    "Current Status: ${status.describeActiveRadios()}\n\n" +
                    "Please turn ON Airplane Mode and ensure Wi-Fi & Mobile Data are completely OFF before continuing.",
            ),
            confirmButtonText = stringReference("Open Settings"),
            dismissButtonText = stringReference("OK"),
            onConfirmClick = {
                onDismiss()
                openAirplaneSettings()
            },
            onDismissButtonClick = onDismiss,
            onDismiss = onDismiss,
        )
    }

    fun buildBackupDialogUM(
        titleText: String,
        bodyText: String,
        onDismiss: () -> Unit,
        showSettingsButton: Boolean = true,
    ): MultiWalletBackupUMDialog {
        return MultiWalletBackupUMDialog(
            title = stringReference(titleText),
            bodyText = stringReference(bodyText),
            confirmText = if (showSettingsButton) stringReference("Open Settings") else stringReference("OK"),
            cancelText = if (showSettingsButton) stringReference("OK") else null,
            warningCancelColor = false,
            onDismiss = onDismiss,
            onConfirm = {
                onDismiss()
                if (showSettingsButton) openAirplaneSettings()
            },
            onCancel = if (showSettingsButton) onDismiss else null,
        )
    }

    fun requireOfflineOrWarn(uiMessageSender: UiMessageSender, actionName: String): Boolean {
        val status = checkStatus()
        if (status.isStrictlyOffline) return true
        uiMessageSender.send(buildBlockedDialogMessage(actionName, status))
        return false
    }
}
