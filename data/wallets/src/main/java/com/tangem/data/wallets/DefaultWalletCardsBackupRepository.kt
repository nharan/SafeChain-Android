package com.tangem.data.wallets

import arrow.core.Either
import arrow.core.right
import com.tangem.data.wallets.converters.PendingWalletCardsBackupConverter
import com.tangem.data.wallets.converters.WalletCardBackupConverter
import com.tangem.data.wallets.converters.WalletCardDTOConverter
import com.tangem.data.wallets.store.PendingWalletCardsBackup
import com.tangem.data.wallets.store.PendingWalletCardsBackupStore
import com.tangem.datasource.api.tangemTech.TangemTechApi
import com.tangem.domain.models.wallet.UserWalletId
import com.tangem.domain.wallets.models.backup.WalletCardBackup
import com.tangem.domain.wallets.models.errors.WalletCardsBackupError
import com.tangem.domain.wallets.repository.WalletCardsBackupRepository
import com.tangem.utils.coroutines.CoroutineDispatcherProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Local-only storage for per-card backup verification records.
 *
 * Persists the verified state of each card (`PRIMARY`, `BACKUP_1`, `BACKUP_2`) locally in [PendingWalletCardsBackupStore]
 * so [com.tangem.domain.wallets.usecase.GetWalletBackupIntegrityUseCase] can distinguish a cryptographically verified
 * multi-card backup from an incomplete or unverified backup without transmitting card clusters or `usedSeed` to Tangem HQ.
 */
@Suppress("UnusedPrivateProperty")
internal class DefaultWalletCardsBackupRepository(
    private val tangemTechApi: TangemTechApi,
    private val pendingStore: PendingWalletCardsBackupStore,
    private val dispatchers: CoroutineDispatcherProvider,
) : WalletCardsBackupRepository {

    private val storeMutex = Mutex()

    override suspend fun saveWalletCards(
        userWalletId: UserWalletId,
        cards: List<WalletCardBackup>,
        usedSeed: Boolean,
    ): Either<WalletCardsBackupError, Unit> = withContext(dispatchers.io) {
        storeMutex.withLock {
            val dtoCards = cards.map(WalletCardDTOConverter::convert)
            val entry = PendingWalletCardsBackup(
                id = UUID.randomUUID().toString(),
                walletId = userWalletId.stringValue,
                cards = PendingWalletCardsBackupConverter.convert(dtoCards),
                // Privacy hardening: never persist or transmit whether a seed phrase was used.
                usedSeed = false,
            )
            pendingStore.putForWallet(entry)
        }
        Unit.right()
    }

    override suspend fun sendPendingWalletCards(): Either<WalletCardsBackupError, Unit> = withContext(dispatchers.io) {
        // Local-only mode: never drain or transmit card backup records to Tangem HQ.
        Unit.right()
    }

    override suspend fun getWalletCards(
        userWalletId: UserWalletId,
    ): Either<WalletCardsBackupError, List<WalletCardBackup>> = withContext(dispatchers.io) {
        storeMutex.withLock {
            val stored = pendingStore.getForWallet(userWalletId.stringValue)
                ?: return@withLock emptyList<WalletCardBackup>().right()
            val body = PendingWalletCardsBackupConverter.convertBack(stored)
            body.cards.map(WalletCardBackupConverter::convert).right()
        }
    }
}