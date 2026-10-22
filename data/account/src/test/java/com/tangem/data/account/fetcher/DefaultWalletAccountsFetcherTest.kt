package com.tangem.data.account.fetcher

import arrow.core.right
import com.google.common.truth.Truth
import com.tangem.core.remote.response.ApiResponse
import com.tangem.core.remote.response.ApiResponseError
import com.tangem.data.account.api.WalletAccountsApi
import com.tangem.data.account.converter.createGetWalletAccountsResponse
import com.tangem.data.account.converter.createWalletAccountDTO
import com.tangem.data.account.fetcher.DefaultWalletAccountsFetcher.FetchResult
import com.tangem.data.account.store.AccountsResponseStore
import com.tangem.data.account.store.AccountsResponseStoreFactory
import com.tangem.data.account.tokens.DefaultMainAccountTokensMigration
import com.tangem.data.account.utils.DefaultWalletAccountsResponseFactory
import com.tangem.data.common.cache.etag.ETagsStore
import com.tangem.data.common.cache.etag.WalletAccountsETags
import com.tangem.data.common.currency.UserTokensSaver
import com.tangem.core.remote.response.ETAG_HEADER
import com.tangem.datasource.api.tangemTech.models.UserTokensResponse
import com.tangem.datasource.api.tangemTech.models.account.GetWalletAccountsResponse
import com.tangem.datasource.api.tangemTech.models.account.SaveWalletAccountsResponse
import com.tangem.datasource.api.tangemTech.models.account.WalletAccountDTO
import com.tangem.datasource.api.tangemTech.models.account.toUserTokensResponse
import com.tangem.domain.models.wallet.UserWalletId
import com.tangem.features.jointaccount.JointAccountFeatureToggles
import com.tangem.test.core.getEmittedValues
import com.tangem.utils.coroutines.TestingCoroutineDispatcherProvider
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.*

/**
[REDACTED_AUTHOR]
 */
@Suppress("Unchecked_Cast", "UnusedFlow")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DefaultWalletAccountsFetcherTest {

    private val walletAccountsApi: WalletAccountsApi = mockk()

    private val accountsResponseStoreFactory: AccountsResponseStoreFactory = mockk()
    private val accountsResponseStore: AccountsResponseStore = mockk()
    private val accountsResponseStoreFlow = MutableStateFlow<GetWalletAccountsResponse?>(value = null)
    private val tokensMigration: DefaultMainAccountTokensMigration = mockk()

    private val userTokensSaver: UserTokensSaver = mockk(relaxUnitFun = true)
    private val fetchWalletAccountsErrorHandler: FetchWalletAccountsErrorHandler = mockk()
    private val defaultWalletAccountsResponseFactory: DefaultWalletAccountsResponseFactory = mockk()
    private val eTagsStore: ETagsStore = mockk(relaxUnitFun = true)
    private val jointAccountFeatureToggles: JointAccountFeatureToggles = mockk()

    private val fetcher: DefaultWalletAccountsFetcher = DefaultWalletAccountsFetcher(
        walletAccountsApi = walletAccountsApi,
        accountsResponseStoreFactory = accountsResponseStoreFactory,
        userTokensSaver = userTokensSaver,
        fetchWalletAccountsErrorHandler = fetchWalletAccountsErrorHandler,
        defaultWalletAccountsResponseFactory = defaultWalletAccountsResponseFactory,
        walletAccountsETags = WalletAccountsETags(eTagsStore = eTagsStore),
        dispatchers = TestingCoroutineDispatcherProvider(),
        mainAccountTokensMigration = tokensMigration,
        jointAccountFeatureToggles = jointAccountFeatureToggles,
    )

    private val userWalletId = UserWalletId("011")
    private val eTag = "etag"
    private val eTagKey = ETagsStore.Key.WalletAccounts
    private val migratedAccountsResponse = createGetWalletAccountsResponse(userWalletId)

    @BeforeEach
    fun setUpEach() {
        every { accountsResponseStoreFactory.create(userWalletId) } returns accountsResponseStore
        every { accountsResponseStore.data } returns accountsResponseStoreFlow

        coEvery { tokensMigration.migrate(userWalletId) } returns migratedAccountsResponse.right()
        coEvery { eTagsStore.getSyncOrNull(userWalletId, eTagKey) } returns eTag
        every { jointAccountFeatureToggles.isJointAccountEnabled } returns false
    }

    @AfterEach
    fun tearDown() {
        clearMocks(
            walletAccountsApi,
            accountsResponseStoreFactory,
            accountsResponseStore,
            tokensMigration,
            userTokensSaver,
            fetchWalletAccountsErrorHandler,
            defaultWalletAccountsResponseFactory,
            eTagsStore,
            jointAccountFeatureToggles,
        )

        accountsResponseStoreFlow.value = null
    }


    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    inner class JointAccountToggle {

        private val ownAccount = createWalletAccountDTO(userWalletId = userWalletId)
        private val jointAccount = createWalletAccountDTO(
            userWalletId = userWalletId,
            accountId = "9CC9C1C9A1F1B62B1F0FCBB5D4C7A34F1B8B98A6D3B3E1B94F6C8F0A1E2D3C4B",
            accountName = "Family",
            derivationIndex = 0,
            type = WalletAccountDTO.Type.JOINT,
        )

        @Test
        fun `GIVEN toggle is off WHEN fetch THEN joint records do not reach the store`() = runTest {
            // Arrange
            every { jointAccountFeatureToggles.isJointAccountEnabled } returns false

            val stored = arrangeFetch(accounts = listOf(ownAccount, jointAccount))

            // Act
            fetcher.fetch(userWalletId)

            // Assert
            Truth.assertThat(stored.captured.invoke(null)?.accounts).containsExactly(ownAccount)
            coVerify(exactly = 0) { walletAccountsApi.getAccounts(any(), any()) }
        }

        @Test
        fun `GIVEN toggle is on WHEN fetch THEN joint records reach the store`() = runTest {
            // Arrange
            every { jointAccountFeatureToggles.isJointAccountEnabled } returns true

            val stored = arrangeFetch(accounts = listOf(ownAccount, jointAccount))

            // Act
            fetcher.fetch(userWalletId)

            // Assert
            Truth.assertThat(stored.captured.invoke(null)?.accounts)
                .containsExactly(ownAccount, jointAccount)
                .inOrder()
            coVerify(exactly = 0) { walletAccountsApi.getAccounts(any(), any()) }
        }

        private fun arrangeFetch(
            accounts: List<WalletAccountDTO>,
        ): CapturingSlot<suspend (GetWalletAccountsResponse?) -> GetWalletAccountsResponse?> {
            val response = createGetWalletAccountsResponse(userWalletId).copy(accounts = accounts)
            accountsResponseStoreFlow.value = response

            val transform = slot<suspend (GetWalletAccountsResponse?) -> GetWalletAccountsResponse?>()
            coEvery { accountsResponseStore.updateData(capture(transform)) } returns response

            return transform
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    inner class Fetch {

        @Test
        fun `fetch should initialize default accounts locally when no saved accounts exist and never call API`() =
            runTest {
                // Arrange
                val defaultAccountsResponse = createGetWalletAccountsResponse(userWalletId)
                accountsResponseStoreFlow.value = null

                coEvery {
                    fetchWalletAccountsErrorHandler.handle(
                        error = any(),
                        userWalletId = userWalletId,
                        savedAccountsResponse = null,
                        pushWalletAccounts = any(),
                        storeWalletAccounts = any(),
                    )
                } returns FetchResult(defaultAccountsResponse)

                coEvery { accountsResponseStore.updateData(any()) } returns defaultAccountsResponse

                // Act
                val result = fetcher.fetch(userWalletId)

                // Assert
                Truth.assertThat(result).isEqualTo(migratedAccountsResponse)
                coVerify(exactly = 0) {
                    walletAccountsApi.getAccounts(any(), any())
                    walletAccountsApi.saveAccounts(any(), any(), any())
                }
                coVerify(exactly = 1) {
                    fetchWalletAccountsErrorHandler.handle(
                        error = any(),
                        userWalletId = userWalletId,
                        savedAccountsResponse = null,
                        pushWalletAccounts = any(),
                        storeWalletAccounts = any(),
                    )
                    tokensMigration.migrate(userWalletId)
                }
            }

        @Test
        fun `fetch should call assignTokens locally when unassignedTokens are not empty`() = runTest {
            // Arrange
            val unassignedToken = createToken(accountId = null)
            val accounts = listOf(createWalletAccountDTO(userWalletId = userWalletId, tokens = null))
            val savedAccountsResponse = createGetWalletAccountsResponse(
                userWalletId = userWalletId,
                unassignedTokens = listOf(unassignedToken),
            ).copy(accounts = accounts)

            accountsResponseStoreFlow.value = savedAccountsResponse
            val transforms = mutableListOf<suspend (GetWalletAccountsResponse?) -> GetWalletAccountsResponse?>()
            coEvery { accountsResponseStore.updateData(capture(transforms)) } returns savedAccountsResponse

            // Act
            fetcher.fetch(userWalletId)

            // Assert
            Truth.assertThat(transforms).hasSize(2)
            val assigned = transforms.last().invoke(null)
            Truth.assertThat(assigned?.unassignedTokens).isEmpty()
            Truth.assertThat(assigned?.accounts?.first()?.tokens).hasSize(1)

            coVerify(exactly = 0) {
                walletAccountsApi.getAccounts(any(), any())
                walletAccountsApi.saveAccounts(any(), any(), any())
                fetchWalletAccountsErrorHandler.handle(
                    error = any(),
                    userWalletId = any(),
                    savedAccountsResponse = any(),
                    pushWalletAccounts = any(),
                    storeWalletAccounts = any(),
                )
            }
            coVerify(exactly = 1) {
                userTokensSaver.pushWithRetryer(
                    userWalletId = userWalletId,
                    response = any(),
                )
                tokensMigration.migrate(userWalletId)
            }
        }

        @Test
        fun `fetch should not call assignTokens when unassignedTokens are empty`() = runTest {
            // Arrange
            val savedAccountsResponse = createGetWalletAccountsResponse(
                userWalletId = userWalletId,
                unassignedTokens = emptyList(),
            )
            accountsResponseStoreFlow.value = savedAccountsResponse
            coEvery { accountsResponseStore.updateData(any()) } returns savedAccountsResponse

            // Act
            fetcher.fetch(userWalletId)

            // Assert
            coVerifyOrder {
                accountsResponseStoreFactory.create(userWalletId = userWalletId)
                accountsResponseStore.data
                accountsResponseStoreFactory.create(userWalletId = userWalletId)
                accountsResponseStore.updateData(any())
                tokensMigration.migrate(userWalletId)
            }

            coVerify(inverse = true) {
                fetchWalletAccountsErrorHandler.handle(
                    error = any(),
                    userWalletId = any(),
                    savedAccountsResponse = any(),
                    pushWalletAccounts = any(),
                    storeWalletAccounts = any(),
                )
                walletAccountsApi.getAccounts(walletId = any(), eTag = any())
                walletAccountsApi.saveAccounts(walletId = any(), eTag = any(), body = any())
                userTokensSaver.push(any(), any())
            }
        }

        @Test
        fun `GIVEN saved response with empty accounts WHEN fetch THEN initializes default accounts locally`() = runTest {
            // Arrange
            val emptySavedResponse = GetWalletAccountsResponse(
                wallet = GetWalletAccountsResponse.Wallet(
                    group = null,
                    sort = null,
                    totalAccounts = 0,
                    totalArchivedAccounts = 0,
                ),
                accounts = emptyList(),
                unassignedTokens = emptyList(),
            )
            val initializedResponse = createGetWalletAccountsResponse(userWalletId)

            accountsResponseStoreFlow.value = emptySavedResponse

            coEvery {
                defaultWalletAccountsResponseFactory.create(userWalletId = userWalletId, userTokensResponse = null)
            } returns initializedResponse

            coEvery { accountsResponseStore.updateData(any()) } returns initializedResponse

            // Act
            fetcher.fetch(userWalletId)

            // Assert
            coVerify {
                defaultWalletAccountsResponseFactory.create(userWalletId = userWalletId, userTokensResponse = null)
                userTokensSaver.push(
                    userWalletId = userWalletId,
                    response = initializedResponse.toUserTokensResponse(),
                )
                tokensMigration.migrate(userWalletId)
            }
            coVerify(exactly = 0) {
                walletAccountsApi.getAccounts(any(), any())
                walletAccountsApi.saveAccounts(any(), any(), any())
            }
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    inner class Get {

        @Test
        fun get() = runTest {
            // Arrange
            val response = createGetWalletAccountsResponse(userWalletId = userWalletId)
            accountsResponseStoreFlow.value = response

            // Act
            val actual = getEmittedValues(fetcher.get(userWalletId))

            // Assert
            val expected = response
            Truth.assertThat(actual).containsExactly(expected)

            coVerifyOrder {
                accountsResponseStoreFactory.create(userWalletId)
                accountsResponseStore.data
            }
        }

        @Test
        fun `get if store is empty`() = runTest {
            // Arrange
            accountsResponseStoreFlow.value = null

            // Act
            val actual = getEmittedValues(fetcher.get(userWalletId))

            // Assert
            Truth.assertThat(actual).isEmpty()

            coVerifyOrder {
                accountsResponseStoreFactory.create(userWalletId)
                accountsResponseStore.data
            }
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    inner class Store {

        @Test
        fun `store should update data in AccountsResponseStore`() = runTest {
            // Arrange
            val response = createGetWalletAccountsResponse(userWalletId = userWalletId)
            coEvery { accountsResponseStore.updateData(any()) } returns response

            // Act
            fetcher.store(userWalletId = userWalletId, response = response)

            // Assert
            coVerifyOrder {
                accountsResponseStoreFactory.create(userWalletId)
                accountsResponseStore.updateData(any())
            }
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    inner class Push {

        @Test
        fun `push should persist newly created renamed and reordered accounts locally without calling API`() = runTest {
            // Arrange
            val mainToken = createToken(accountId = "acc-main")
            val mainAccount = createWalletAccountDTO(
                userWalletId = userWalletId,
                accountId = "acc-main",
                accountName = "Main",
                derivationIndex = 0,
                tokens = listOf(mainToken),
            )
            val currentResponse = createGetWalletAccountsResponse(userWalletId).copy(
                accounts = listOf(mainAccount),
            )
            accountsResponseStoreFlow.value = currentResponse
            coEvery { accountsResponseStore.updateData(any()) } returns currentResponse

            val secondAccountSaveDto = SaveWalletAccountsResponse.AccountDTO(
                id = "acc-second",
                name = "Savings",
                derivationIndex = 1,
                icon = mainAccount.icon,
                iconColor = mainAccount.iconColor,
                type = WalletAccountDTO.Type.CRYPTO,
            )
            val renamedMainSaveDto = SaveWalletAccountsResponse.AccountDTO(
                id = "acc-main",
                name = "Primary Renamed",
                derivationIndex = 0,
                icon = mainAccount.icon,
                iconColor = mainAccount.iconColor,
                type = WalletAccountDTO.Type.CRYPTO,
            )
            val saveRequest = SaveWalletAccountsResponse(
                accounts = listOf(secondAccountSaveDto, renamedMainSaveDto),
            )

            // Act
            val result = fetcher.push(userWalletId, saveRequest)

            // Assert
            Truth.assertThat(result).isNotNull()
            Truth.assertThat(result!!.accounts.map { it.id }).containsExactly("acc-second", "acc-main").inOrder()
            Truth.assertThat(result.accounts[1].name).isEqualTo("Primary Renamed")
            Truth.assertThat(result.accounts[1].tokens).containsExactly(mainToken.copy(accountId = "acc-main"))
            Truth.assertThat(result.wallet.totalAccounts).isEqualTo(2)

            coVerify(exactly = 0) {
                walletAccountsApi.saveAccounts(any(), any(), any())
                walletAccountsApi.getAccounts(any(), any())
            }
            coVerify(exactly = 1) {
                accountsResponseStore.updateData(any())
            }
        }

        @Test
        fun `push should remove archived accounts from active list and increment totalArchivedAccounts locally`() =
            runTest {
                // Arrange
                val acc1 = createWalletAccountDTO(
                    userWalletId = userWalletId,
                    accountId = "acc-1",
                    accountName = "Main",
                    derivationIndex = 0,
                )
                val acc2 = createWalletAccountDTO(
                    userWalletId = userWalletId,
                    accountId = "acc-2",
                    accountName = "Trading",
                    derivationIndex = 1,
                )
                val currentResponse = createGetWalletAccountsResponse(userWalletId).copy(
                    wallet = GetWalletAccountsResponse.Wallet(
                        group = null,
                        sort = null,
                        totalAccounts = 2,
                        totalArchivedAccounts = 0,
                    ),
                    accounts = listOf(acc1, acc2),
                )
                accountsResponseStoreFlow.value = currentResponse
                coEvery { accountsResponseStore.updateData(any()) } returns currentResponse

                // Archive acc-2 by pushing only acc-1
                val saveRequest = SaveWalletAccountsResponse(accounts = listOf(acc1))

                // Act
                val result = fetcher.push(userWalletId, saveRequest)

                // Assert
                Truth.assertThat(result).isNotNull()
                Truth.assertThat(result!!.accounts.map { it.id }).containsExactly("acc-1")
                Truth.assertThat(result.wallet.totalArchivedAccounts).isEqualTo(1)
                coVerify(exactly = 0) {
                    walletAccountsApi.saveAccounts(any(), any(), any())
                }
            }
    }

    private fun createToken(
        networkId: String = "ethereum",
        derivationPath: String = "m/44'/60'/0'/0/0",
        list: List<String> = emptyList(),
        name: String = "Ethereum",
        symbol: String = "ETH",
        decimals: Int = 18,
        contractAddress: String? = null,
        id: String? = null,
        accountId: String? = null,
    ) = UserTokensResponse.Token(
        id = id,
        accountId = accountId,
        networkId = networkId,
        derivationPath = derivationPath,
        name = name,
        symbol = symbol,
        decimals = decimals,
        contractAddress = contractAddress,
        addresses = list,
    )
}