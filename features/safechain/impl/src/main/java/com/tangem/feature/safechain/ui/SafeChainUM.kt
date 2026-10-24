package com.tangem.feature.safechain.ui

import androidx.compose.runtime.Immutable

@Immutable
internal data class SafeChainUM(
    val accountAddress: String? = null,
    val loading: Boolean = true,
    val loadError: String? = null,
    val health: HealthUM = HealthUM.Unknown,
    /** Tokens still on the card itself (not yet protected). */
    val cardBalance: String = "—",
    val gasBalance: String = "—",
    /** This phone's backup key for the card, if it holds one, and the ETH it has for emergency fees. */
    val phoneKey: String? = null,
    val phoneKeyGas: String = "—",
    val vault: VaultUM? = null,
    /** Older vaults of this card that still hold money or have a move under way. */
    val previous: List<PreviousVaultUM> = emptyList(),
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
    /** e.g. "24h". */
    val delay: String,
    val locked: Boolean,
    val isPrimary: Boolean,
    val recovery: String,
    /** The backup key is this phone's key, so the phone can lock in an emergency. */
    val backupIsThisPhone: Boolean,
    val haven: String?,
    /** Red alerts pinned to the top: payments on their way out, a scheduled takeover, a rescue window. */
    val alerts: List<String>,
    val pending: List<WithdrawalUM>,
    val activity: List<ActivityUM>,
    val backupChange: BackupChangeUM?,
    val disputed: Boolean,
)

/** A backup-key change in progress (two-party, then a waiting period). */
@Immutable
internal data class BackupChangeUM(
    val newBackup: String,
    val approved: Boolean,
    /** "Takes effect after …", or null once it can be finished. */
    val waitText: String?,
    val canFinish: Boolean,
    val canApproveFromPhone: Boolean,
)

@Immutable
internal data class PreviousVaultUM(
    val address: String,
    val balance: String,
    val available: String,
    val canMove: Boolean,
    val moving: List<WithdrawalUM>,
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

/**
 * Creating a vault. By default this phone becomes the backup key: one fingerprint, one card tap. The other
 * path (another wallet as the backup) keeps the consent link, with Copy and Paste so nothing is typed.
 */
@Immutable
internal data class SetupUM(
    val delayHours: String = "24",
    val otherWallet: Boolean = false,
    val recovery: String = "",
    val consentLink: String? = null,
    val signature: String = "",
    val error: String? = null,
)

/** What a bottom sheet shows. */
internal sealed interface SheetUM {
    data object None : SheetUM

    data class AddFunds(val amount: String = "", val max: String, val error: String? = null) : SheetUM

    data class Withdraw(val amount: String = "", val to: String = "", val max: String, val error: String? = null) : SheetUM

    data class Receive(val address: String) : SheetUM

    /** Switching the backup from this phone to another wallet: its address, its consent, then two approvals. */
    data class OtherWallet(
        val address: String = "",
        val link: String? = null,
        val signature: String = "",
        val error: String? = null,
    ) : SheetUM

    /** Exactly what is about to be signed, in full, before the tap or fingerprint: the card has no screen. */
    data class Review(
        val title: String,
        val step: String?,
        val rows: List<Pair<String, String>>,
        val note: String?,
        val fee: String,
        val danger: Boolean = false,
        /** Signed with this phone's fingerprint instead of the card. */
        val withPhone: Boolean = false,
    ) : SheetUM

    data class Working(val title: String, val text: String, val holdCard: Boolean) : SheetUM

    data class Result(val ok: Boolean, val title: String, val text: String, val url: String?) : SheetUM
}

internal interface SafeChainActions {
    fun onBack()
    fun onRefresh()

    // setup
    fun onSetupDelayChange(value: String)
    fun onSetupCreate()
    fun onSetupUseOtherWallet(other: Boolean)
    fun onSetupRecoveryChange(value: String)
    fun onSetupMakeLink()
    fun onSetupSignatureChange(value: String)
    fun onSetupCreateWithOtherWallet()
    fun onScanRecovery()
    fun onScanSignature()

    // everyday
    fun onOpenAddFunds()
    fun onOpenWithdraw()
    fun onOpenReceive()
    fun onAmountChange(value: String)
    fun onDestinationChange(value: String)
    fun onScanDestination()
    fun onMax()
    fun onContinue()
    fun onCancelWithdrawal(id: String)
    fun onReleaseWithdrawal(id: String)

    // emergencies (the phone's backup key) and the card's own lock
    fun onEmergencyLock()
    fun onUnlock()
    fun onLock()
    fun onMoveToHaven()

    // backup key
    fun onOpenOtherWallet()
    fun onOtherWalletAddressChange(value: String)
    fun onOtherWalletMakeLink()
    fun onOtherWalletSignatureChange(value: String)
    fun onOtherWalletContinue()
    fun onScanOtherWallet()
    fun onScanOtherWalletSignature()
    fun onApproveBackupChange()
    fun onFinishBackupChange()
    fun onCancelBackupChange()
    fun onMoveToNewVault()

    // older vaults
    fun onMoveFromPrevious(vault: String)
    fun onReleaseFromPrevious(vault: String, id: String)

    // sheets
    fun onSign()
    fun onCloseSheet()
}
