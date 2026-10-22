package com.tangem.datasource.local.config.environment.converter

import com.tangem.blockchain.common.*
import com.tangem.datasource.local.config.environment.EnvironmentConfig
import com.tangem.datasource.local.config.environment.generated.GeneratedEnvironmentConfig
import com.tangem.datasource.local.config.environment.generated.GeneratedEnvironmentConfig.AppsFlyer
import com.tangem.datasource.local.config.environment.generated.GeneratedEnvironmentConfig.DevExpress
import com.tangem.datasource.local.config.environment.generated.GeneratedEnvironmentConfig.Express
import com.tangem.datasource.local.config.environment.generated.GeneratedEnvironmentConfig.GetBlockAccessTokens
import com.tangem.datasource.local.config.environment.generated.GeneratedEnvironmentConfig.P2pApiKey
import com.tangem.datasource.local.config.environment.generated.GeneratedEnvironmentConfig.TonCenterApiKey
import com.tangem.datasource.local.config.environment.models.ExpressModel
import com.tangem.datasource.local.config.environment.models.P2PKeys
import com.tangem.datasource.local.config.environment.models.SurveySparrowSwapRatingConfig

/**
 * Converts [GeneratedEnvironmentConfig] to [EnvironmentConfig]
 *
 * This converter maps the auto-generated config (from JSON) to the domain model.
 * The generated config has nested objects that mirror the JSON structure.
 */
internal object GeneratedEnvironmentConfigConverter {

    fun convert(): EnvironmentConfig {
        return EnvironmentConfig(
            moonPayApiKey = GeneratedEnvironmentConfig.moonPayApiKey,
            moonPayApiSecretKey = GeneratedEnvironmentConfig.moonPayApiSecretKey,
            mercuryoWidgetId = GeneratedEnvironmentConfig.mercuryoWidgetId,
            mercuryoSecret = GeneratedEnvironmentConfig.mercuryoSecret,
            blockchainSdkConfig = createBlockchainSdkConfig(),
            amplitudeApiKey = GeneratedEnvironmentConfig.amplitudeApiKey,
            amplitudeApiKeyDev = GeneratedEnvironmentConfig.amplitudeApiKeyDev,
            appsFlyerApiKey = AppsFlyer.appsFlyerDevKey,
            appsAppId = AppsFlyer.appsFlyerAppID,
            walletConnectProjectId = GeneratedEnvironmentConfig.walletConnectProjectId,
            express = createExpressModel(
                apiKey = Express.apiKey,
                signVerifierPublicKey = Express.signVerifierPublicKey,
            ),
            devExpress = createExpressModel(
                apiKey = DevExpress.apiKey,
                signVerifierPublicKey = DevExpress.signVerifierPublicKey,
            ),
            stakeKitApiKey = GeneratedEnvironmentConfig.stakeKitApiKey,
            p2pApiKey = createP2PKeys(),
            blockAidApiKey = GeneratedEnvironmentConfig.blockaidApiKey,
            tangemApiKey = GeneratedEnvironmentConfig.tangemApiKey,
            tangemApiKeyDev = GeneratedEnvironmentConfig.tangemApiKeyDev,
            tangemApiKeyStage = GeneratedEnvironmentConfig.tangemApiKeyStage,
            yieldModuleApiKey = GeneratedEnvironmentConfig.yieldModuleApiKey,
            yieldModuleApiKeyDev = GeneratedEnvironmentConfig.yieldModuleApiKeyDev,
            bffStaticToken = GeneratedEnvironmentConfig.bffStaticToken,
            bffStaticTokenDev = GeneratedEnvironmentConfig.bffStaticTokenDev,
            gaslessTxApiKeyDev = GeneratedEnvironmentConfig.gaslessTxApiKeyDev,
            gaslessTxApiKey = GeneratedEnvironmentConfig.gaslessTxApiKey,
            customerIoCdpApiKey = GeneratedEnvironmentConfig.CustomerIO.androidApiKey,
            surveySparrowToken = GeneratedEnvironmentConfig.SurveySparrow.apiKey,
            surveySparrowSwapRating = createSurveySparrowSwapRating(),
            // the JSON key was created with a typo ("otpl"), kept as is to match the deployed configs
            otlpApiKey = GeneratedEnvironmentConfig.otplApiKey,
        )
    }

    private fun createExpressModel(apiKey: String?, signVerifierPublicKey: String?): ExpressModel? {
        return if (!apiKey.isNullOrEmpty() && !signVerifierPublicKey.isNullOrEmpty()) {
            ExpressModel(apiKey = apiKey, signVerifierPublicKey = signVerifierPublicKey)
        } else {
            null
        }
    }

    private fun createP2PKeys(): P2PKeys? {
        val mainnet = P2pApiKey.mainnet
        val hoodi = P2pApiKey.hoodi
        return if (mainnet.isNotEmpty() && hoodi.isNotEmpty()) {
            P2PKeys(mainnet = mainnet, hoodi = hoodi)
        } else {
            null
        }
    }

    private fun createBlockchainSdkConfig(): BlockchainSdkConfig {
        return BlockchainSdkConfig(
            blockchairCredentials = BlockchairCredentials(
                // Map PLACEHOLDER to "" (non-empty list of blank key) so BlockchairNetworkProviderFactory
                // still instantiates BlockchairNetworkProvider on https://api.blockchair.com/ using its
                // free keyless tier (currentApiKey = null) unless rate-limited.
                apiKey = GeneratedEnvironmentConfig.blockchairApiKeys.map { it.noPlaceholder() },
                authToken = GeneratedEnvironmentConfig.blockchairAuthorizationToken.noPlaceholderOrNull(),
            ),
            blockcypherTokens = GeneratedEnvironmentConfig.blockcypherTokens
                .mapNotNull { it.noPlaceholderOrNull() }
                .toSet(),
            quickNodeSolanaCredentials = QuickNodeCredentials(
                apiKey = GeneratedEnvironmentConfig.quiknodeApiKey.noPlaceholder(),
                subdomain = GeneratedEnvironmentConfig.quiknodeSubdomain.noPlaceholder(),
            ),
            quickNodeBscCredentials = QuickNodeCredentials(
                apiKey = GeneratedEnvironmentConfig.bscQuiknodeApiKey.noPlaceholder(),
                subdomain = GeneratedEnvironmentConfig.bscQuiknodeSubdomain.noPlaceholder(),
            ),
            quickNodePlasmaCredentials = QuickNodeCredentials(
                apiKey = GeneratedEnvironmentConfig.quiknodePlasmaApiKey.noPlaceholder(),
                subdomain = GeneratedEnvironmentConfig.quiknodePlasmaSubdomain.noPlaceholder(),
            ),
            quickNodeMonadCredentials = QuickNodeCredentials(
                apiKey = GeneratedEnvironmentConfig.quiknodeMonadApiKey.noPlaceholder(),
                subdomain = GeneratedEnvironmentConfig.quiknodeMonadSubdomain.noPlaceholder(),
            ),
            quickNodeRobinhoodCredentials = QuickNodeCredentials(
                apiKey = GeneratedEnvironmentConfig.quiknodeRobinhoodApiKey.noPlaceholder(),
                subdomain = GeneratedEnvironmentConfig.quiknodeRobinhoodSubdomain.noPlaceholder(),
            ),
            quickNodeXrpCredentials = QuickNodeCredentials(
                apiKey = GeneratedEnvironmentConfig.quiknodeXRPLApiKey.noPlaceholder(),
                subdomain = GeneratedEnvironmentConfig.quiknodeXRPLSubdomain.noPlaceholder(),
            ),
            quickNodeArcCredentials = QuickNodeCredentials(
                apiKey = GeneratedEnvironmentConfig.quiknodeArcApiKey.noPlaceholder(),
                subdomain = GeneratedEnvironmentConfig.quiknodeArcSubdomain.noPlaceholder(),
            ),
            infuraProjectId = GeneratedEnvironmentConfig.infuraProjectId.noPlaceholder(),
            tronGridApiKey = GeneratedEnvironmentConfig.tronGridApiKey.noPlaceholder(),
            nowNodeCredentials = NowNodeCredentials(
                apiKey = GeneratedEnvironmentConfig.nowNodesApiKey.noPlaceholder(),
            ),
            getBlockCredentials = createGetBlockCredentials(),
            kaspaSecondaryApiUrl = GeneratedEnvironmentConfig.kaspaSecondaryApiUrl.noPlaceholder(),
            tonCenterCredentials = TonCenterCredentials(
                mainnetApiKey = TonCenterApiKey.mainnet.noPlaceholder(),
                testnetApiKey = TonCenterApiKey.testnet.noPlaceholder(),
            ),
            chiaFireAcademyApiKey = GeneratedEnvironmentConfig.chiaFireAcademyApiKey.noPlaceholder(),
            chiaTangemApiKey = GeneratedEnvironmentConfig.chiaTangemApiKey.noPlaceholder(),
            hederaArkhiaApiKey = GeneratedEnvironmentConfig.hederaArkhiaKey.noPlaceholder(),
            polygonScanApiKey = GeneratedEnvironmentConfig.polygonScanApiKey.noPlaceholder(),
            bittensorDwellirApiKey = GeneratedEnvironmentConfig.bittensorDwellirKey.noPlaceholder(),
            bittensorOnfinalityApiKey = GeneratedEnvironmentConfig.bittensorOnfinalityKey.noPlaceholder(),
            dwellirApiKey = GeneratedEnvironmentConfig.dwellirApiKey.noPlaceholder(),
            koinosProApiKey = GeneratedEnvironmentConfig.koinosProApiKey.noPlaceholder(),
            alephiumApiKey = GeneratedEnvironmentConfig.alephiumTangemApiKey.noPlaceholder(),
            moralisApiKey = GeneratedEnvironmentConfig.moralisApiKey.noPlaceholder(),
            etherscanApiKey = GeneratedEnvironmentConfig.etherscanApiKey.noPlaceholder(),
            blinkApiKey = GeneratedEnvironmentConfig.blinkApiKey.noPlaceholder(),
            tatumApiKey = GeneratedEnvironmentConfig.tatumApiKey.noPlaceholder(),
            alchemyApiKey = GeneratedEnvironmentConfig.alchemyApiKey.noPlaceholder(),
        )
    }

    private fun createGetBlockCredentials(): GetBlockCredentials {
        return GetBlockCredentials(
            xrp = GetBlockAccessToken(jsonRpc = GetBlockAccessTokens.Xrp.jsonRpc.noPlaceholder()),
            cardano = GetBlockAccessToken(rosetta = GetBlockAccessTokens.Cardano.rosetta.noPlaceholder()),
            avalanche = GetBlockAccessToken(jsonRpc = GetBlockAccessTokens.Avalanche.jsonRpc.noPlaceholder()),
            eth = GetBlockAccessToken(jsonRpc = GetBlockAccessTokens.Ethereum.jsonRpc.noPlaceholder()),
            etc = GetBlockAccessToken(jsonRpc = GetBlockAccessTokens.EthereumClassic.jsonRpc.noPlaceholder()),
            fantom = GetBlockAccessToken(jsonRpc = GetBlockAccessTokens.Fantom.jsonRpc.noPlaceholder()),
            rsk = GetBlockAccessToken(jsonRpc = GetBlockAccessTokens.Rsk.jsonRpc.noPlaceholder()),
            bsc = GetBlockAccessToken(jsonRpc = GetBlockAccessTokens.Bsc.jsonRpc.noPlaceholder()),
            polygon = GetBlockAccessToken(jsonRpc = GetBlockAccessTokens.Polygon.jsonRpc.noPlaceholder()),
            gnosis = GetBlockAccessToken(jsonRpc = GetBlockAccessTokens.Xdai.jsonRpc.noPlaceholder()),
            cronos = GetBlockAccessToken(jsonRpc = GetBlockAccessTokens.Cronos.jsonRpc.noPlaceholder()),
            solana = GetBlockAccessToken(jsonRpc = GetBlockAccessTokens.Solana.jsonRpc.noPlaceholder()),
            ton = GetBlockAccessToken(jsonRpc = GetBlockAccessTokens.Ton.jsonRpc.noPlaceholder()),
            tron = GetBlockAccessToken(rest = GetBlockAccessTokens.Tron.rest.noPlaceholder()),
            cosmos = GetBlockAccessToken(rest = GetBlockAccessTokens.CosmosHub.rest.noPlaceholder()),
            near = GetBlockAccessToken(jsonRpc = GetBlockAccessTokens.Near.jsonRpc.noPlaceholder()),
            aptos = GetBlockAccessToken(rest = GetBlockAccessTokens.Aptos.rest.noPlaceholder()),
            dogecoin = GetBlockAccessToken(
                jsonRpc = GetBlockAccessTokens.Dogecoin.jsonRpc.noPlaceholder(),
                blockBookRest = GetBlockAccessTokens.Dogecoin.blockBookRest.noPlaceholder(),
            ),
            litecoin = GetBlockAccessToken(
                jsonRpc = GetBlockAccessTokens.Litecoin.jsonRpc.noPlaceholder(),
                blockBookRest = GetBlockAccessTokens.Litecoin.blockBookRest.noPlaceholder(),
            ),
            dash = GetBlockAccessToken(
                jsonRpc = GetBlockAccessTokens.Dash.jsonRpc.noPlaceholder(),
                blockBookRest = GetBlockAccessTokens.Dash.blockBookRest.noPlaceholder(),
            ),
            bitcoin = GetBlockAccessToken(
                jsonRpc = GetBlockAccessTokens.Bitcoin.jsonRpc.noPlaceholder(),
                blockBookRest = GetBlockAccessTokens.Bitcoin.blockBookRest.noPlaceholder(),
            ),
            algorand = GetBlockAccessToken(rest = GetBlockAccessTokens.Algorand.rest.noPlaceholder()),
            zkSyncEra = GetBlockAccessToken(jsonRpc = GetBlockAccessTokens.Zksync.jsonRpc.noPlaceholder()),
            polygonZkEvm = GetBlockAccessToken(jsonRpc = GetBlockAccessTokens.PolygonZkevm.jsonRpc.noPlaceholder()),
            base = GetBlockAccessToken(jsonRpc = GetBlockAccessTokens.Base.jsonRpc.noPlaceholder()),
            blast = GetBlockAccessToken(jsonRpc = GetBlockAccessTokens.Blast.jsonRpc.noPlaceholder()),
            filecoin = GetBlockAccessToken(jsonRpc = GetBlockAccessTokens.Filecoin.jsonRpc.noPlaceholder()),
            arbitrum = GetBlockAccessToken(jsonRpc = GetBlockAccessTokens.ArbitrumOne.jsonRpc.noPlaceholder()),
            bitcoinCash = GetBlockAccessToken(
                jsonRpc = GetBlockAccessTokens.BitcoinCash.jsonRpc.noPlaceholder(),
                blockBookRest = GetBlockAccessTokens.BitcoinCash.blockBookRest.noPlaceholder(),
            ),
            kusama = GetBlockAccessToken(jsonRpc = GetBlockAccessTokens.Kusama.jsonRpc.noPlaceholder()),
            moonbeam = GetBlockAccessToken(jsonRpc = GetBlockAccessTokens.Moonbeam.jsonRpc.noPlaceholder()),
            optimism = GetBlockAccessToken(jsonRpc = GetBlockAccessTokens.Optimism.jsonRpc.noPlaceholder()),
            polkadot = GetBlockAccessToken(jsonRpc = GetBlockAccessTokens.Polkadot.jsonRpc.noPlaceholder()),
            shibarium = GetBlockAccessToken(jsonRpc = GetBlockAccessTokens.Shibarium.jsonRpc.noPlaceholder()),
            sui = GetBlockAccessToken(jsonRpc = GetBlockAccessTokens.Sui.jsonRpc.noPlaceholder()),
            telos = GetBlockAccessToken(jsonRpc = GetBlockAccessTokens.Telos.jsonRpc.noPlaceholder()),
            tezos = GetBlockAccessToken(rest = GetBlockAccessTokens.Tezos.rest.noPlaceholder()),
            monad = GetBlockAccessToken(rest = GetBlockAccessTokens.Monad.rest.noPlaceholder()),
            stellar = GetBlockAccessToken(rest = GetBlockAccessTokens.Stellar.rest.noPlaceholder()),
        )
    }

    private fun String.noPlaceholder(): String = if (this == "PLACEHOLDER") "" else this

    private fun String?.noPlaceholderOrNull(): String? =
        if (this == "PLACEHOLDER" || this.isNullOrEmpty()) null else this

    private fun createSurveySparrowSwapRating(): SurveySparrowSwapRatingConfig? {
        val surveyId = GeneratedEnvironmentConfig.SurveySparrow.SwapRating.surveyId.toLongOrNull()
        val ratingQuestionId = GeneratedEnvironmentConfig.SurveySparrow.SwapRating.ratingQuestionId.toLongOrNull()
        val feedbackQuestionId = GeneratedEnvironmentConfig.SurveySparrow.SwapRating.feedbackQuestionId.toLongOrNull()
        return if (surveyId != null && ratingQuestionId != null && feedbackQuestionId != null) {
            SurveySparrowSwapRatingConfig(surveyId, ratingQuestionId, feedbackQuestionId)
        } else {
            null
        }
    }
}