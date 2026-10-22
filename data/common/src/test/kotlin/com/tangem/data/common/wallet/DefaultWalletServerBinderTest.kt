package com.tangem.data.common.wallet

import com.google.common.truth.Truth
import com.tangem.core.remote.response.ApiResponse
import com.tangem.core.remote.response.ApiResponseError
import com.tangem.core.remote.response.ETAG_HEADER
import com.tangem.datasource.api.tangemTech.TangemTechApi
import com.tangem.datasource.api.tangemTech.models.WalletIdBody
import com.tangem.datasource.api.tangemTech.models.WalletType
import com.tangem.domain.appsflyer.repository.AppsFlyerRepository
import com.tangem.domain.common.wallets.UserWalletsListRepository
import com.tangem.domain.models.wallet.UserWallet
import com.tangem.domain.models.wallet.UserWalletId
import com.tangem.domain.wallets.models.AppsFlyerConversionData
import com.tangem.utils.coroutines.CoroutineDispatcherProvider
import com.tangem.utils.coroutines.TestingCoroutineDispatcherProvider
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
[REDACTED_AUTHOR]
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class DefaultWalletServerBinderTest {

    private val userWalletsListRepository: UserWalletsListRepository = mockk()
    private val appsFlyerRepository: AppsFlyerRepository = mockk()
    private val tangemTechApi: TangemTechApi = mockk()
    private val dispatchers: CoroutineDispatcherProvider = TestingCoroutineDispatcherProvider()

    private val binder = DefaultWalletServerBinder(
        userWalletsListRepository = userWalletsListRepository,
        appsFlyerRepository = appsFlyerRepository,
        tangemTechApi = tangemTechApi,
        dispatchers = dispatchers
    )

    private val userWalletId = UserWalletId("011")
    private val userWallet = mockk<UserWallet.Cold> {
        every { this@mockk.walletId } returns userWalletId
        every { this@mockk.name } returns "Wallet"
    }
    private val conversionData = AppsFlyerConversionData(refcode = "refcode", campaign = "campaign")

    @AfterEach
    fun tearDown() {
        clearMocks(userWalletsListRepository, appsFlyerRepository, tangemTechApi)
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    inner class BindUserWalletId {

        @Test
        fun `bind resolves locally without calling remote API`() = runTest {
            val expectedResponse = ApiResponse.Success(
                data = Unit,
                code = ApiResponseError.HttpException.Code.CREATED,
                headers = mapOf(ETAG_HEADER to listOf("local-only-etag")),
            )

            val userWalletsFlow = MutableStateFlow(listOf(userWallet))

            every { userWalletsListRepository.userWallets } returns userWalletsFlow

            val actual = binder.bind(userWalletId = userWalletId)

            Truth.assertThat(actual).isEqualTo(expectedResponse)

            coVerifyOrder {
                userWalletsListRepository.userWallets
            }

            coVerify(inverse = true) {
                appsFlyerRepository.getConversionData()
                tangemTechApi.createWallet(any())
            }
        }

        @Test
        fun `bind will skipped if userWalletsListRepository returns null`() = runTest {
            val userWalletsFlow = MutableStateFlow<List<UserWallet>?>(null)

            every { userWalletsListRepository.userWallets } returns userWalletsFlow

            val actual = binder.bind(userWalletId = userWalletId)

            Truth.assertThat(actual).isEqualTo(null)

            coVerifyOrder { userWalletsListRepository.userWallets }

            coVerify(inverse = true) {
                appsFlyerRepository.getConversionData()
                tangemTechApi.createWallet(any())
            }
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    inner class BindUserWallet {

        @Test
        fun `bind resolves locally without calling remote API`() = runTest {
            val expectedResponse = ApiResponse.Success(
                data = Unit,
                code = ApiResponseError.HttpException.Code.CREATED,
                headers = mapOf(ETAG_HEADER to listOf("local-only-etag")),
            )

            val actual = binder.bind(userWallet = userWallet)

            Truth.assertThat(actual).isEqualTo(expectedResponse)

            coVerify(inverse = true) {
                appsFlyerRepository.getConversionData()
                tangemTechApi.createWallet(any())
            }
        }
    }
}