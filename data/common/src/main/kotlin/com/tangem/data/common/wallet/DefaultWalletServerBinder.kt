package com.tangem.data.common.wallet

import com.tangem.core.remote.response.ApiResponse
import com.tangem.core.remote.response.ETAG_HEADER
import com.tangem.datasource.api.tangemTech.TangemTechApi
import com.tangem.datasource.api.tangemTech.converters.WalletIdBodyConverter
import com.tangem.domain.appsflyer.repository.AppsFlyerRepository
import com.tangem.domain.common.wallets.UserWalletsListRepository
import com.tangem.domain.common.wallets.getSyncOrNull
import com.tangem.domain.models.wallet.UserWallet
import com.tangem.domain.models.wallet.UserWalletId
import com.tangem.utils.coroutines.CoroutineDispatcherProvider
import com.tangem.utils.logging.TangemLogger
import kotlinx.coroutines.withContext

internal class DefaultWalletServerBinder(
    private val userWalletsListRepository: UserWalletsListRepository,
    private val appsFlyerRepository: AppsFlyerRepository,
    private val tangemTechApi: TangemTechApi,
    private val dispatchers: CoroutineDispatcherProvider,
) : WalletServerBinder {

    override suspend fun bind(userWalletId: UserWalletId): ApiResponse<Unit>? {
        val userWallet = userWalletsListRepository.getSyncOrNull(id = userWalletId)

        if (userWallet == null) {
            TangemLogger.e("bind wallet=$userWalletId: user wallet not found locally, skipping createWallet call")
            return null
        }

        return bind(userWallet)
    }

    override suspend fun bind(userWallet: UserWallet): ApiResponse<Unit> {
        // Hardened build: never register walletId or AppsFlyer conversion data with Tangem backend
        return ApiResponse.Success(
            data = Unit,
            code = com.tangem.core.remote.response.ApiResponseError.HttpException.Code.CREATED,
            headers = mapOf(ETAG_HEADER to listOf("local-only-etag")),
        )
    }
}