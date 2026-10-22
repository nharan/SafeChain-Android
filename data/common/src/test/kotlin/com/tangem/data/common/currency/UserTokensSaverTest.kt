package com.tangem.data.common.currency

import com.tangem.data.common.cache.etag.ETagsStore
import com.tangem.data.common.cache.etag.WalletAccountsETags
import com.tangem.data.common.wallet.WalletServerBinder
import com.tangem.core.remote.response.ApiResponse
import com.tangem.core.remote.response.ApiResponseError
import com.tangem.core.remote.response.ETAG_HEADER
import com.tangem.datasource.api.tangemTech.TangemTechApi
import com.tangem.datasource.api.tangemTech.models.UserTokensResponse
import com.tangem.datasource.api.tangemTech.models.WalletType
import com.tangem.datasource.api.tangemTech.models.account.GetWalletAccountsV2Response
import com.tangem.datasource.api.tangemTech.models.account.WalletAccountDTO
import com.tangem.domain.common.wallets.UserWalletsListRepository
import com.tangem.domain.models.wallet.UserWallet
import com.tangem.domain.models.wallet.UserWalletId
import com.tangem.features.jointaccount.JointAccountFeatureToggles
import com.tangem.utils.coroutines.TestingCoroutineDispatcherProvider
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserTokensSaverTest {

    private val tangemTechApi: TangemTechApi = mockk()
    private val userWalletsListRepository: UserWalletsListRepository = mockk(relaxUnitFun = true)
    private val enricher: UserTokensResponseAddressesEnricher = mockk()
    private val walletServerBinder: WalletServerBinder = mockk()
    private val eTagsStore: ETagsStore = mockk(relaxUnitFun = true)
    private val jointAccountFeatureToggles: JointAccountFeatureToggles = mockk()

    private val userTokensSaver: UserTokensSaver = UserTokensSaver(
        tangemTechApi = tangemTechApi,
        userWalletsListRepository = userWalletsListRepository,
        dispatchers = TestingCoroutineDispatcherProvider(),
        addressesEnricher = enricher,
        walletServerBinder = walletServerBinder,
        pushTokensRetryerPool = mockk(),
        walletAccountsETags = WalletAccountsETags(eTagsStore = eTagsStore),
        jointAccountFeatureToggles = jointAccountFeatureToggles,
    )

    @BeforeEach
    fun resetMocks() {
        clearMocks(
            tangemTechApi,
            userWalletsListRepository,
            enricher,
            walletServerBinder,
            eTagsStore,
            jointAccountFeatureToggles,
        )
        every { jointAccountFeatureToggles.isJointAccountEnabled } returns false
        coEvery { eTagsStore.getSyncOrNull(any(), any()) } returns null
    }

    @Test
    fun `GIVEN user wallet id and response WHEN push THEN does not call remote API`() = runTest {
        // Arrange
        val userWalletId = UserWalletId("1234567890abcdef")
        stubUserWallet(userWalletId)

        val response = createResponse(tokens = listOf(createToken()), sort = UserTokensResponse.SortType.BALANCE)
        var onFailSendCalled = false

        // Act
        userTokensSaver.push(
            userWalletId = userWalletId,
            response = response,
            onFailSend = { onFailSendCalled = true },
        )

        // Assert
        coVerify(exactly = 0) {
            tangemTechApi.saveTokens(any(), any(), any())
            tangemTechApi.getWalletAccountsV2(walletId = any())
            walletServerBinder.bind(any<UserWallet>())
        }
        assert(!onFailSendCalled) { "onFailSend callback should not be called on local no-op" }
    }

    private fun stubUserWallet(userWalletId: UserWalletId) {
        val userWallet = mockk<UserWallet.Cold> {
            every { this@mockk.walletId } returns userWalletId
            every { this@mockk.name } returns ""
        }
        every { userWalletsListRepository.userWallets } returns MutableStateFlow(listOf(userWallet))
    }

    private fun preconditionFailed(): ApiResponse<Unit> {
        val cause = ApiResponseError.HttpException(
            code = ApiResponseError.HttpException.Code.PRECONDITION_FAILED,
            message = null,
            errorBody = null,
        )
        return ApiResponse.Error(cause) as ApiResponse<Unit>
    }

    private fun createAccountsV2Response(tokens: List<UserTokensResponse.Token>): GetWalletAccountsV2Response {
        return GetWalletAccountsV2Response(
            wallet = GetWalletAccountsV2Response.Wallet(
                version = 0,
                group = UserTokensResponse.GroupType.NETWORK,
                sort = UserTokensResponse.SortType.BALANCE,
                totalCryptoAccounts = 1,
                totalArchivedAccounts = 0,
                totalJointAccounts = 0,
            ),
            accounts = listOf(
                WalletAccountDTO(
                    id = "account",
                    name = "Main",
                    derivationIndex = 0,
                    icon = "Family",
                    iconColor = "Azure",
                    tokens = tokens,
                ),
            ),
            unassignedTokens = emptyList(),
        )
    }

    private fun createResponse(
        tokens: List<UserTokensResponse.Token>,
        sort: UserTokensResponse.SortType = UserTokensResponse.SortType.BALANCE,
    ): UserTokensResponse {
        return UserTokensResponse(
            version = 0,
            group = UserTokensResponse.GroupType.NETWORK,
            sort = sort,
            tokens = tokens,
            walletName = null,
            walletType = WalletType.COLD,
        )
    }

    private fun createToken(): UserTokensResponse.Token {
        return UserTokensResponse.Token(
            id = "ethereum",
            accountId = "account",
            networkId = "ethereum",
            derivationPath = "m/44'/60'/0'/0/1",
            name = "Ethereum",
            symbol = "ETH",
            decimals = 18,
            contractAddress = null,
        )
    }

    private companion object {
        const val STALE_ETAG = """{"a":1,"t":1}"""
        const val FRESH_ETAG = """{"a":1,"t":2}"""
    }
}