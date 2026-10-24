package com.tangem.feature.safechain.ui

import androidx.compose.runtime.Immutable

@Immutable
internal data class SafeChainUM(
    val accountAddress: String? = null,
    val loading: Boolean = true,
    val loadError: String? = null,
    val health: HealthUM = HealthUM.Unknown,
    val spendingBalance: String = "—",
    val gasBalance: String = "—",
    val vault: VaultUM? = null,
    val setup: SetupUM = SetupUM(),
    val sheet: SheetUM = SheetUM.None,
)

internal sealed interface HealthUM {
    data object Unknown : HealthUM
    data object Agree : HealthUM
    data class Degraded(val text: String) : HealthUM
    data class Disagree(val text: String) : HealthUM
}

@Immutable
internal data class VaultUM(
    val address: String,
    val balance: String,
    val available: String,
    val protection: String,
    val locked: Boolean,
    val isPrimary: Boolean,
    val isRecovery: Boolean,
    val recovery: String,
    val haven: String?,
    /** Red alerts pinned to the top: pending withdrawals, a scheduled takeover, a rescue window. */
    val alerts: List<String>,
    val pending: List<WithdrawalUM>,
    val activity: List<ActivityUM>,
    val disputed: Boolean,
)

@Immutable
internal data class WithdrawalUM(
    val id: String,
    val amount: String,
    val to: String,
    val toHaven: Boolean,
    val isFinal: Boolean,
    val countdown: String?,
    val deadline: String,
    val canCancel: Boolean,
    val canRelease: Boolean,
    val disputed: Boolean,
)

@Immutable
internal data class ActivityUM(val title: String, val detail: String, val time: String, val url: String, val tone: Tone) {
    enum class Tone { Neutral, Good, Warning, Danger }
}

/** Creating a vault: four steps on one card, with Copy and Paste so nothing is typed across devices. */
@Immutable
internal data class SetupUM(
    val recovery: String = "",
    val delayHours: String = "24",
    val consentLink: String? = null,
    val signature: String = "",
    val error: String? = null,
)

/** What a bottom sheet shows. */
internal sealed interface SheetUM {
    data object None : SheetUM

    data class AddFunds(val amount: String = "", val max: String, val error: String? = null) : SheetUM

    data class Withdraw(val amount: String = "", val to: String = "", val max: String, val error: String? = null) : SheetUM

    /** Exactly what the card is about to sign, in full, before the tap: the card itself has no screen. */
    data class Review(
        val title: String,
        val step: String?,
        val rows: List<Pair<String, String>>,
        val note: String?,
        val fee: String,
        val danger: Boolean = false,
    ) : SheetUM

    data class Working(val title: String, val text: String, val holdCard: Boolean) : SheetUM

    data class Result(val ok: Boolean, val title: String, val text: String, val url: String?) : SheetUM
}

internal interface SafeChainActions {
    fun onBack()
    fun onRefresh()

    // setup
    fun onSetupRecoveryChange(value: String)
    fun onSetupDelayChange(value: String)
    fun onSetupMakeLink()
    fun onSetupSignatureChange(value: String)
    fun onSetupCreate()
    fun onScanRecovery()
    fun onScanSignature()

    // vault actions
    fun onOpenAddFunds()
    fun onOpenWithdraw()
    fun onAmountChange(value: String)
    fun onDestinationChange(value: String)
    fun onScanDestination()
    fun onMax()
    fun onContinue()
    fun onCancelWithdrawal(id: String)
    fun onReleaseWithdrawal(id: String)
    fun onLock()
    fun onMoveToHaven()

    // sheets
    fun onSign()
    fun onCloseSheet()
}
