package com.tangem.data.wallets

import arrow.core.right
import com.google.common.truth.Truth.assertThat
import com.tangem.data.wallets.store.PendingWalletCardsBackupStore
import com.tangem.data.wallets.store.PendingWalletCardsBackups
import com.tangem.datasource.api.tangemTech.TangemTechApi
import com.tangem.domain.models.wallet.UserWalletId
import com.tangem.domain.wallets.models.backup.CardBackupStatus
import com.tangem.domain.wallets.models.backup.WalletCardBackup
import com.tangem.test.core.datastore.MockStateDataStore
import com.tangem.utils.coroutines.TestingCoroutineDispatcherProvider
import io.mockk.clearMocks
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class DefaultWalletCardsBackupRepositoryTest {

    private val tangemTechApi: TangemTechApi = mockk(relaxed = true)
    private val dataStore = MockStateDataStore<PendingWalletCardsBackups>(default = emptyList())

    private val pendingStore = PendingWalletCardsBackupStore(dataStore = dataStore)

    private val repository = DefaultWalletCardsBackupRepository(
        tangemTechApi = tangemTechApi,
        pendingStore = pendingStore,
        dispatchers = TestingCoroutineDispatcherProvider(),
    )

    @BeforeEach
    fun resetMocks() {
        clearMocks(tangemTechApi)
        runBlocking { dataStore.updateData { emptyList() } }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    inner class SaveWalletCards {

        @Test
        fun `WHEN saveWalletCards THEN cards are persisted locally with usedSeed false and api is never called`() =
            runTest {
                // Act
                val actual = repository.saveWalletCards(
                    userWalletId = WALLET_ID,
                    cards = listOf(domainCard(CardBackupStatus.ACTIVE)),
                    usedSeed = true,
                )

                // Assert
                assertThat(actual).isEqualTo(Unit.right())
                coVerify(exactly = 0) { tangemTechApi.saveWalletCards(any(), any()) }
                val stored = pendingStore.getAll()
                assertThat(stored).hasSize(1)
                assertThat(stored.single().walletId).isEqualTo(WALLET_ID.stringValue)
                assertThat(stored.single().usedSeed).isFalse()
            }

        @Test
        fun `GIVEN existing wallet entry WHEN saveWalletCards called again THEN latest card statuses replace old`() =
            runTest {
                // Arrange
                repository.saveWalletCards(
                    userWalletId = WALLET_ID,
                    cards = listOf(
                        domainCard(CardBackupStatus.CARD_LINKED, WalletCardBackup.Role.PRIMARY),
                        domainCard(CardBackupStatus.NO_BACKUP, WalletCardBackup.Role.BACKUP_1),
                    ),
                    usedSeed = false,
                )

                // Act
                repository.saveWalletCards(
                    userWalletId = WALLET_ID,
                    cards = listOf(
                        domainCard(CardBackupStatus.ACTIVE, WalletCardBackup.Role.PRIMARY),
                        domainCard(CardBackupStatus.ACTIVE, WalletCardBackup.Role.BACKUP_1),
                    ),
                    usedSeed = false,
                )

                // Assert
                val stored = pendingStore.getAll()
                assertThat(stored).hasSize(1)
                assertThat(repository.getWalletCards(WALLET_ID)).isEqualTo(
                    listOf(
                        domainCard(CardBackupStatus.ACTIVE, WalletCardBackup.Role.PRIMARY),
                        domainCard(CardBackupStatus.ACTIVE, WalletCardBackup.Role.BACKUP_1),
                    ).right(),
                )
            }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    inner class SendPendingWalletCards {

        @Test
        fun `WHEN sendPendingWalletCards THEN local records remain intact and api is never called`() = runTest {
            // Arrange
            repository.saveWalletCards(
                userWalletId = WALLET_ID,
                cards = listOf(domainCard(CardBackupStatus.ACTIVE)),
                usedSeed = false,
            )

            // Act
            val actual = repository.sendPendingWalletCards()

            // Assert
            assertThat(actual).isEqualTo(Unit.right())
            coVerify(exactly = 0) { tangemTechApi.saveWalletCards(any(), any()) }
            assertThat(pendingStore.getAll()).hasSize(1)
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    inner class GetWalletCards {

        @Test
        fun `GIVEN cards saved locally WHEN getWalletCards THEN they are returned as domain models without api call`() =
            runTest {
                // Arrange
                val cards = listOf(
                    domainCard(CardBackupStatus.ACTIVE, WalletCardBackup.Role.PRIMARY),
                    domainCard(CardBackupStatus.ACTIVE, WalletCardBackup.Role.BACKUP_1),
                )
                repository.saveWalletCards(userWalletId = WALLET_ID, cards = cards, usedSeed = false)

                // Act
                val actual = repository.getWalletCards(WALLET_ID)

                // Assert
                assertThat(actual).isEqualTo(cards.right())
                coVerify(exactly = 0) { tangemTechApi.getWalletCards(any()) }
            }

        @Test
        fun `GIVEN unknown wallet WHEN getWalletCards THEN empty list is returned without api call`() = runTest {
            // Act
            val actual = repository.getWalletCards(WALLET_ID)

            // Assert
            assertThat(actual).isEqualTo(emptyList<WalletCardBackup>().right())
            coVerify(exactly = 0) { tangemTechApi.getWalletCards(any()) }
        }
    }

    private companion object {

        val WALLET_ID = UserWalletId(stringValue = "0102030405060708")
        const val CARD_ID = "AC01000000000000"
        const val CARD_PUBLIC_KEY = "0AFF"

        fun domainCard(
            backupStatus: CardBackupStatus,
            role: WalletCardBackup.Role = WalletCardBackup.Role.PRIMARY,
        ) = WalletCardBackup(
            cardId = CARD_ID,
            cardPublicKey = CARD_PUBLIC_KEY,
            role = role,
            backupStatus = backupStatus,
            curves = emptyList(),
        )
    }
}