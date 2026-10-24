package com.tangem.feature.safechain.ui

import androidx.compose.runtime.Immutable

/**
 * The screen as a bank shows it: Checking (the card's own tokens, instant) and Savings (the protected vault,
 * every payment waits and can be cancelled), a total, upcoming payments and activity.
 */
@Immutable
internal data class SafeChainUM(
    val accountAddress: String? = null,
    val loading: Boolean = true,
    val loadError: String? = null,
    /** Debug-build demo on a pretend chain (no card, no network). */
    val demo: Boolean = false,
    val health: HealthUM = HealthUM.Unknown,
    /** Checking + Savings. */
    val total: String = "—",
    /** Checking: tokens on the card itself, spendable at once (not protected). */
    val cardBalance: String = "—",
    val gasBalance: String = "—",
    /** This phone's backup key for the card, if it holds one, and the ETH it has for emergency fees. */
    val phoneKey: String? = null,
    val phoneKeyGas: String = "—",
    /** Too little ETH on the phone key to pay for an emergency lock. */
    val phoneKeyLow: Boolean = false,
    val vault: VaultUM? = null,
    /** Payments on their way, from both accounts (and protected payments coming in), soonest first. */
    val upcoming: List<WithdrawalUM> = emptyList(),
    /** Both accounts' history, newest first. */
    val activity: List<ActivityUM> = emptyList(),
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
    /** True when some of the balance is held by payments on their way out. */
    val partlyHeld: Boolean,
    /** e.g. "24 hours". */
    val hold: String,
    val locked: Boolean,
    val isPrimary: Boolean,
    val recovery: String,
    /** The backup key is this phone's key, so the phone can lock in an emergency. */
    val backupIsThisPhone: Boolean,
    val haven: String?,
    /** Rare, serious warnings (a scheduled spending-key takeover, a rescue window). Payments on their way are in [pending]. */
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

/** Where a payment comes from: a Savings withdrawal, or a protected payment from Checking. */
internal enum class Source { Savings, Checking }

@Immutable
internal data class WithdrawalUM(
    val id: String,
    val source: Source = Source.Savings,
    /** A protected payment someone is sending to this card (shown, not cancellable here). */
    val incoming: Boolean = false,
    val amount: String,
    val to: String,
    /** Who it's going to, in words: "Your Checking", "Your new Savings", or the short address. */
    val toLabel: String,
    val toHaven: Boolean,
    val isFinal: Boolean,
    /** "Arrives in 23h 12m", or null once final. */
    val countdown: String?,
    /** How much of the hold has passed, 0..1, for the progress bar. */
    val progress: Float,
    val deadline: String,
    val canCancel: Boolean,
    val canRelease: Boolean,
    val disputed: Boolean,
    /** Its page on the SafeChain explorer. */
    val url: String,
)

@Immutable
internal data class ActivityUM(
    val title: String,
    val detail: String,
    val time: String,
    /** Signed amount ("+300", "−100"), or null when nothing moved. */
    val amount: String?,
    val url: String,
    val icon: Icon,
    val tone: Tone,
) {
    enum class Tone { Neutral, Good, Warning, Danger }
    enum class Icon { In, Out, Returned, Delivered, Lock, Unlock, Key }
}

/**
 * Opening Savings. By default this phone becomes the backup key: one fingerprint, one card tap. The other
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

/**
 * What's shown over the screen. Forms with text fields (Send, Move, another wallet) are full pages, so the
 * keyboard never fights a bottom sheet; Review, Working and Result are bottom sheets.
 */
internal sealed interface SheetUM {
    data object None : SheetUM

    /** Choose a direction: Checking → Savings or Savings → Checking (not while Savings is locked). */
    data class Move(val savingsLocked: Boolean) : SheetUM

    /** Checking → Savings (one card tap, instant). */
    data class AddFunds(val amount: String = "", val max: String, val error: String? = null) : SheetUM

    /**
     * Send from either account. Checking: instant, or with a hold you choose (1 hour to 30 days) through the
     * protected-payment escrow, cancellable until it ends. Savings: always its own hold.
     */
    data class Send(
        val from: Source,
        val amount: String = "",
        val to: String = "",
        val checkingMax: String,
        val savingsMax: String,
        /** The Savings hold, e.g. "24 hours"; null when there is no Savings yet. */
        val savingsHold: String?,
        val savingsLocked: Boolean = false,
        /** Hours for a Checking send: "0" (instant), "1", "24", "72", "168", or "custom". */
        val hold: String = "24",
        val customHours: String = "",
        val error: String? = null,
    ) : SheetUM {
        val max: String get() = if (from == Source.Checking) checkingMax else savingsMax
    }

    /** Savings → your own Checking; waits out the hold. */
    data class Withdraw(
        val amount: String = "",
        val to: String = "",
        val max: String,
        val toChecking: Boolean = false,
        val error: String? = null,
    ) : SheetUM

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

    // opening Savings
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
    fun onOpenWithdraw()
    fun onSendFrom(source: Source)
    fun onHoldChange(hours: String)
    fun onCustomHoursChange(value: String)
    fun onCancelPayment(id: String)
    fun onSettlePayment(id: String)
    fun onOpenReceive()
    fun onOpenMove()
    fun onMoveToSavings()
    fun onMoveToChecking()
    fun onAmountChange(value: String)
    fun onDestinationChange(value: String)
    fun onScanDestination()
    fun onMax()
    fun onContinue()
    fun onCancelWithdrawal(id: String)
    fun onReleaseWithdrawal(id: String)

    // emergencies (the phone's backup key) and the card's own lock
    fun onTopUpPhone()
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
