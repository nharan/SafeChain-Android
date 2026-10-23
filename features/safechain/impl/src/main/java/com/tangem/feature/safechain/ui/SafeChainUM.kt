package com.tangem.feature.safechain.ui

import androidx.compose.runtime.Immutable

@Immutable
internal data class SafeChainUM(
    val accountAddress: String? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val health: HealthUM = HealthUM.Unknown,
    val ethBalance: String = "—",
    val tokenBalance: String = "—",
    val vaults: List<VaultUM> = emptyList(),
    /** Something in progress ("Tap your card…", "Waiting for the network…"). */
    val busy: String? = null,
    /** Result of the last action. */
    val message: String? = null,
    val create: CreateVaultUM = CreateVaultUM(),
    val confirm: ConfirmUM? = null,
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
    val locked: Boolean,
    val isPrimary: Boolean,
    val isRecovery: Boolean,
    val recovery: String,
    val haven: String?,
    val protection: String,
    val warnings: List<String>,
    val withdrawals: List<WithdrawalUM>,
    val disputed: Boolean,
    val withdrawAmount: String = "",
    val withdrawTo: String = "",
    val depositAmount: String = "",
)

@Immutable
internal data class WithdrawalUM(
    val id: String,
    val amount: String,
    val to: String,
    val toHaven: Boolean,
    val stateLabel: String,
    val countdown: String?,
    val canCancel: Boolean,
    val canExecute: Boolean,
    val disputed: Boolean,
)

@Immutable
internal data class CreateVaultUM(
    val recovery: String = "",
    val delayHours: String = "24",
    val consentLink: String? = null,
    val consentSignature: String = "",
)

/** What the card is about to sign, shown in full before the tap: the card itself has no screen. */
@Immutable
internal data class ConfirmUM(
    val title: String,
    val lines: List<String>,
    val maxFee: String,
)

internal interface SafeChainActions {
    fun onBack()
    fun onRefresh()
    fun onDismissMessage()
    fun onRecoveryChange(value: String)
    fun onDelayHoursChange(value: String)
    fun onStartCreate()
    fun onConsentSignatureChange(value: String)
    fun onCreateVault()
    fun onDepositAmountChange(vault: String, value: String)
    fun onDeposit(vault: String)
    fun onWithdrawAmountChange(vault: String, value: String)
    fun onWithdrawToChange(vault: String, value: String)
    fun onRequestWithdrawal(vault: String)
    fun onCancelWithdrawal(vault: String, id: String)
    fun onExecuteWithdrawal(vault: String, id: String)
    fun onLock(vault: String)
    fun onMoveToHaven(vault: String)
    fun onConfirm()
    fun onDismissConfirm()
}
