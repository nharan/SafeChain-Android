package com.tangem.domain.wallets.usecase

import com.tangem.domain.card.IsWalletBackupProblematicUseCase
import com.tangem.domain.models.scan.CardDTO
import com.tangem.domain.models.wallet.UserWallet
import com.tangem.domain.wallets.models.backup.CardBackupStatus
import com.tangem.domain.wallets.models.backup.WalletBackupIntegrity
import com.tangem.domain.wallets.models.backup.WalletCardBackup
import com.tangem.domain.wallets.repository.WalletCardsBackupRepository

/**
 * Decides whether a wallet's backup can be trusted, and what to ask the user to do about it.
 *
 * Local state wins over the backend: the app can only ever know *more* than the backend about a wallet it holds,
 * so the backend is consulted purely to recover knowledge the app has lost — a reinstall, cleared data, or a
 * login from another device.
 *
 * Wallets that cannot have a card backup return [WalletBackupIntegrity.NotApplicable], so callers may pass every
 * wallet they have without pre-filtering.
 */
class GetWalletBackupIntegrityUseCase(
    private val walletCardsBackupRepository: WalletCardsBackupRepository,
    private val isWalletBackupProblematicUseCase: IsWalletBackupProblematicUseCase,
) {

    suspend operator fun invoke(userWallet: UserWallet): WalletBackupIntegrity {
        if (userWallet !is UserWallet.Cold) return WalletBackupIntegrity.NotApplicable

        val localBackupStatus = userWallet.scanResponse.card.backupStatus
        // the same null check BackupValidator treats as "nothing to validate": firmware without backup support
        if (localBackupStatus == null) return WalletBackupIntegrity.NotApplicable

        if (isWalletBackupProblematicUseCase(userWallet)) return WalletBackupIntegrity.LocallyDetectedProblem

        return walletCardsBackupRepository.getWalletCards(userWallet.walletId).fold(
            ifLeft = { WalletBackupIntegrity.Undetermined },
            ifRight = { cards -> resolve(cards = cards, localBackupStatus = localBackupStatus) },
        )
    }

    private fun resolve(
        cards: List<WalletCardBackup>,
        localBackupStatus: CardDTO.BackupStatus,
    ): WalletBackupIntegrity {
        if (cards.isEmpty()) {
            return when (localBackupStatus) {
                is CardDTO.BackupStatus.CardLinked -> WalletBackupIntegrity.MandatoryRescan
                is CardDTO.BackupStatus.Active,
                CardDTO.BackupStatus.NoBackup,
                -> WalletBackupIntegrity.RecommendedRescan
            }
        }

        val statuses = cards.map { it.backupStatus }
        val expectedMinCards = when (localBackupStatus) {
            is CardDTO.BackupStatus.Active -> 1 + localBackupStatus.cardCount
            is CardDTO.BackupStatus.CardLinked -> 1 + localBackupStatus.cardCount
            CardDTO.BackupStatus.NoBackup -> 1
        }

        return when {
            statuses.contains(CardBackupStatus.CARD_LINKED) -> WalletBackupIntegrity.MandatoryRescan
            statuses.all { it == CardBackupStatus.ACTIVE } -> {
                if (cards.size >= expectedMinCards) {
                    WalletBackupIntegrity.FullyActivated
                } else {
                    WalletBackupIntegrity.RecommendedRescan
                }
            }
            statuses.contains(CardBackupStatus.ACTIVE) -> WalletBackupIntegrity.MandatoryRescan
            else -> WalletBackupIntegrity.RecommendedRescan
        }
    }
}