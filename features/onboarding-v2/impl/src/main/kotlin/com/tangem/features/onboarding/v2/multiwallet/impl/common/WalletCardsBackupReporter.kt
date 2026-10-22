package com.tangem.features.onboarding.v2.multiwallet.impl.common

import com.tangem.domain.models.scan.ScanResponse
import com.tangem.domain.wallets.builder.UserWalletIdBuilder
import com.tangem.domain.wallets.models.backup.WalletCardBackup
import com.tangem.domain.wallets.usecase.ReportWalletCardsBackupUseCase
import com.tangem.features.onboarding.v2.OnboardingV2FeatureToggles
import com.tangem.utils.coroutines.AppCoroutineScope
import com.tangem.utils.logging.TangemLogger
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Reports the cards of a wallet and the state of their backup to the backend during onboarding.
 */
internal class WalletCardsBackupReporter @Inject constructor(
    private val reportWalletCardsBackupUseCase: ReportWalletCardsBackupUseCase,
    private val onboardingV2FeatureToggles: OnboardingV2FeatureToggles,
    private val appScope: AppCoroutineScope,
) {

    /**
     * Reports every card known to the app for this wallet, called on each step that changes what is known —

     *
     * @param scanResponse scan response holding the primary card, the source of the wallet id
     * @param cards        all known cards, primary first

     *                     wallet creation take it from [usedSeedPhrase]
     */
    @Suppress("UnusedPrivateMember", "UnusedParameter")
    fun report(scanResponse: ScanResponse, cards: List<WalletCardBackup>, usedSeed: Boolean) {
        // Telemetry disabled: never report card backup status or usedSeed to backend
    }

    internal companion object {

        /** Roles of the backup cards, in the order the cards are added to the backup */
        val BACKUP_ROLES = listOf(WalletCardBackup.Role.BACKUP_1, WalletCardBackup.Role.BACKUP_2)
    }
}

/**

 *
 * Only wallet creation knows this firsthand; every later step — a backup card is added, a card is finalized — may

 */
internal fun ScanResponse.usedSeedPhrase(): Boolean = card.wallets.any { it.isImported }