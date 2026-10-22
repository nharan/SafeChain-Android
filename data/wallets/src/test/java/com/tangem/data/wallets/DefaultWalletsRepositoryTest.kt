package com.tangem.data.wallets

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.Moshi
import com.tangem.data.common.wallet.WalletServerBinder
import com.tangem.core.remote.response.ApiResponse
import com.tangem.core.remote.response.ApiResponseError.HttpException
import com.tangem.datasource.api.tangemTech.TangemTechApi
import com.tangem.datasource.api.tangemTech.models.PromocodeActivationBody
import com.tangem.datasource.api.tangemTech.models.PromocodeActivationResponse
import com.tangem.datasource.api.tangemTech.models.WalletResponse
import com.tangem.store.datasource.preferences.StorePreferencesKeys
import com.tangem.domain.models.wallet.UserWallet
import com.tangem.domain.models.wallet.UserWalletId
import com.tangem.domain.wallets.models.errors.ActivatePromoCodeError
import com.tangem.test.core.datastore.createAppPreferencesStore
import com.tangem.utils.coroutines.TestingCoroutineDispatcherProvider
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Tests for [DefaultWalletsRepository]
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DefaultWalletsRepositoryTest {

    private val preferencesDataStore: DataStore<Preferences> = mockk(relaxed = true)
    private val tangemTechApi: TangemTechApi = mockk()
    private val walletServerBinder: WalletServerBinder = mockk()

    private val appPreferenceStore = createAppPreferencesStore(
        moshi = Moshi.Builder().build(),
        dispatchers = TestingCoroutineDispatcherProvider(),
        preferencesDataStore = preferencesDataStore,
    )

    private val storePreferencesKeys = StorePreferencesKeys()

    private val repository = DefaultWalletsRepository(
        appPreferencesStore = appPreferenceStore,
        storePreferencesKeys = storePreferencesKeys,
        tangemTechApi = tangemTechApi,
        userWalletsListRepository = mockk(),
        dispatchers = TestingCoroutineDispatcherProvider(),
        walletServerBinder = walletServerBinder,
        moshi = mockk(),
    )

    private val testWalletId = UserWalletId("1234567890abcdef")

    @AfterEach
    fun tearDown() {
        clearMocks(tangemTechApi, preferencesDataStore)
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    inner class IsNotificationsEnabled {

        @Test
        fun `should return local value when local storage has value`() = runTest {
            // Arrange
            val expectedPreferences = """{"${testWalletId.stringValue}":true}"""
            val preferences = mockk<Preferences>()
            coEvery { preferencesDataStore.data } returns flowOf(preferences)
            coEvery { preferences[storePreferencesKeys.notificationsEnabledStatesKey] } returns expectedPreferences

            // Act
            val result = repository.isNotificationsEnabled(testWalletId)

            // Assert
            assertThat(result).isTrue()
        }

        @Test
        fun `should return false when local storage is empty`() = runTest {
            // Arrange
            val preferences = mockk<Preferences>()
            coEvery { preferencesDataStore.data } returns flowOf(preferences)
            coEvery { preferences[storePreferencesKeys.notificationsEnabledStatesKey] } returns "{}"

            // Act
            val result = repository.isNotificationsEnabled(testWalletId)

            // Assert
            assertThat(result).isFalse()
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    inner class SetNotificationsEnabled {

        @Test
        fun `should update local storage when enabled status`() = runTest {
            // Arrange
            val preferences = mockk<Preferences>()
            coEvery { preferencesDataStore.data } returns flowOf(preferences)
            coEvery { preferences[storePreferencesKeys.notificationsEnabledStatesKey] } returns "{}"
            coEvery { preferencesDataStore.updateData(any()) } returns mockk()

            // Act
            repository.setNotificationsEnabled(testWalletId, isEnabled = true)

            // Assert
            coVerify(exactly = 1) { preferencesDataStore.updateData(any()) }
        }

        @Test
        fun `should update local storage when disabled status`() = runTest {
            // Arrange
            val preferences = mockk<Preferences>()
            coEvery { preferencesDataStore.data } returns flowOf(preferences)
            coEvery { preferences[storePreferencesKeys.notificationsEnabledStatesKey] } returns "{}"
            coEvery { preferencesDataStore.updateData(any()) } returns mockk()

            // Act
            repository.setNotificationsEnabled(testWalletId, isEnabled = false)

            // Assert
            coVerify(exactly = 1) { preferencesDataStore.updateData(any()) }
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    inner class GetWalletsInfo {

        @Test
        fun `should return empty list without calling Tangem backend when updateCache is true`() = runTest {
            // Arrange
            val applicationId = "test_app_id"

            // Act
            val result = repository.getWalletsInfo(applicationId, updateCache = true)

            // Assert
            assertThat(result).isEmpty()
            coVerify(exactly = 0) { tangemTechApi.getWallets(any()) }
        }

        @Test
        fun `should return empty list without calling Tangem backend when updateCache is false`() = runTest {
            // Arrange
            val applicationId = "test_app_id"

            // Act
            val result = repository.getWalletsInfo(applicationId, updateCache = false)

            // Assert
            assertThat(result).isEmpty()
            coVerify(exactly = 0) { tangemTechApi.getWallets(any()) }
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    inner class AssociateWallets {

        @Test
        fun `should not send walletIds to associateApplicationIdWithWallets`() = runTest {
            // Arrange
            val applicationId = "test_app_id"
            val wallet1Id = "1234567890abcdef"
            val wallet2Id = "fedcba0987654321"

            val userWallets = listOf(
                mockk<UserWallet.Cold> {
                    every { walletId } returns UserWalletId(wallet1Id)
                },
                mockk<UserWallet.Cold> {
                    every { walletId } returns UserWalletId(wallet2Id)
                },
            )

            // Act
            repository.associateWallets(applicationId, userWallets)

            // Assert
            coVerify(exactly = 0) {
                tangemTechApi.associateApplicationIdWithWallets(any(), any())
            }
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    inner class ActivatePromoCode {

        @Test
        fun `should return Left ActivationFailed locally without sending walletId or bitcoinAddress to API`() = runTest {
            // Arrange
            val walletId = UserWalletId("1234567890abcdef")
            val promoCode = "PROMO123"
            val address = "bc1qexampleaddress"

            // Act
            val result = repository.activatePromoCode(
                userWalletId = walletId,
                promoCode = promoCode,
                bitcoinAddress = address,
            )

            // Assert
            var error: ActivatePromoCodeError? = null
            result.fold({ error = it }, { })
            assertThat(error).isEqualTo(ActivatePromoCodeError.ActivationFailed)

            coVerify(exactly = 0) {
                tangemTechApi.activatePromoCode(any())
            }
        }
    }
}