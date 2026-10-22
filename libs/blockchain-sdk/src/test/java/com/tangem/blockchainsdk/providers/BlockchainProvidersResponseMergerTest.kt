package com.tangem.blockchainsdk.providers

import com.google.common.truth.Truth
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.crashlytics.recordException
import com.tangem.blockchainsdk.BlockchainProvidersResponse
import com.tangem.core.analytics.api.AnalyticsExceptionHandler
import com.tangem.core.analytics.models.ExceptionAnalyticsEvent
import com.tangem.blockchainsdk.providers.models.ProviderModel
import io.mockk.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
[REDACTED_AUTHOR]
 */
internal class BlockchainProvidersResponseMergerTest {

    private val blockchainProvidersResponseMerger = BlockchainProvidersResponseMerger(
        object : AnalyticsExceptionHandler {
            override fun sendException(event: ExceptionAnalyticsEvent) {
                FirebaseCrashlytics.getInstance().recordException(event.exception)
            }
        },
    )

    @BeforeEach
    fun setup() {
        mockkStatic(FirebaseCrashlytics::class)
        val firebaseCrashlytics = mockk<FirebaseCrashlytics>()
        every { FirebaseCrashlytics.getInstance() } returns firebaseCrashlytics
        every { firebaseCrashlytics.recordException(any()) } just Runs
    }

    @Test
    fun test_if_both_configs_are_empty() {
        val expected = emptyMap<String, List<ProviderModel>>()

        val actual = blockchainProvidersResponseMerger.merge(
            local = emptyMap(),
            remote = emptyMap(),
        )

        Truth.assertThat(actual).isEqualTo(expected)
    }

    @Test
    fun test_if_local_config_is_empty() {
        val expected = remoteResponse

        val actual = blockchainProvidersResponseMerger.merge(
            local = emptyMap(),
            remote = remoteResponse,
        )

        Truth.assertThat(actual).isEqualTo(expected)
    }

    @Test
    fun test_if_remote_config_is_empty() {
        val expected = localResponse

        val actual = blockchainProvidersResponseMerger.merge(
            local = localResponse,
            remote = emptyMap(),
        )

        Truth.assertThat(actual).isEqualTo(expected)
    }

    @Test
    fun test_if_both_configs_are_not_empty() {
        val expected = remoteResponse

        val actual = blockchainProvidersResponseMerger.merge(
            local = localResponse,
            remote = remoteResponse,
        )

        Truth.assertThat(actual).isEqualTo(expected)
    }

    @Test
    fun test_if_configs_are_equal() {
        val expected = remoteResponse

        val actual = blockchainProvidersResponseMerger.merge(
            local = remoteResponse,
            remote = remoteResponse,
        )

        Truth.assertThat(actual).isEqualTo(expected)
    }

    @Test
    fun test_if_local_config_has_blockchain_with_empty_providers() {
        val eth = "ethereum" to emptyList<ProviderModel>()
        val localResponseWithEth = localResponse + eth

        val expected = localResponseWithEth + remoteResponse

        val actual = blockchainProvidersResponseMerger.merge(
            local = localResponseWithEth,
            remote = remoteResponse,
        )

        Truth.assertThat(actual).isEqualTo(expected)
    }

    @Test
    fun test_if_remote_config_has_blockchain_with_empty_providers() {
        val expected = remoteResponse

        val eth = "ethereum" to emptyList<ProviderModel>()
        val remoteWithEth = remoteResponse + eth

        val actual = blockchainProvidersResponseMerger.merge(
            local = localResponse,
            remote = remoteWithEth,
        )

        Truth.assertThat(actual).isEqualTo(expected)
    }

    @Test
    fun test_if_remote_config_doesnt_contain_local_providers() {
        val remoteWithoutLocal = remoteResponse - localResponse.keys

        /**
         * The expected result does not contain local blockchains, since they can be disabled remotely.
         * For the opposite case, see [test_if_both_configs_are_not_empty].
         */
        val expected = remoteResponse

        val actual = blockchainProvidersResponseMerger.merge(
            local = localResponse,
            remote = remoteWithoutLocal,
        )

        Truth.assertThat(actual).isEqualTo(expected)
    }

    @Test
    fun test_if_remote_config_contains_unsupported_providers() {
        val nowNodesProvider = ProviderModel.Private(name = "nownodes")
        val eth = "ethereum" to listOf(ProviderModel.UnsupportedType, nowNodesProvider)
        val remoteWithEth = remoteResponse + eth

        val expected = remoteResponse + ("ethereum" to listOf(nowNodesProvider))

        val actual = blockchainProvidersResponseMerger.merge(
            local = localResponse,
            remote = remoteWithEth,
        )

        Truth.assertThat(actual).isEqualTo(expected)
    }

    @Test
    fun test_if_remote_config_contains_invalid_public_providers() {
        val eth = "ethereum" to listOf(ProviderModel.UnsupportedType, ProviderModel.Public("adbw2138"))
        val remoteWithEth = remoteResponse + eth

        val expected = remoteResponse

        val actual = blockchainProvidersResponseMerger.merge(
            local = localResponse,
            remote = remoteWithEth,
        )

        Truth.assertThat(actual).isEqualTo(expected)
    }

    @Test
    fun test_if_configs_contain_public_providers_without_slash_in_the_end() {
        val eth = "ethereum" to listOf(ProviderModel.Public("https://qwe.com"))
        val kaspa = "kaspa" to listOf(ProviderModel.Public("https://qwe.com"))

        val localWithKaspa = localResponse + kaspa
        val remoteWithEth = remoteResponse + eth

        val expected = remoteResponse + eth.addSlash() + kaspa.addSlash()

        val actual = blockchainProvidersResponseMerger.merge(
            local = localWithKaspa,
            remote = remoteWithEth,
        )

        Truth.assertThat(actual).isEqualTo(expected)
    }

    @Test
    fun test_if_local_public_fallback_providers_are_preserved_when_remote_overrides_blockchain() {
        val localNear = "near-protocol" to listOf(
            ProviderModel.Public("https://rpc.mainnet.fastnear.com/"),
            ProviderModel.Public("https://archival-rpc.mainnet.near.org/"),
        )
        val remoteNear = "near-protocol" to listOf(
            ProviderModel.Private(name = "nownodes"),
            ProviderModel.Public("https://archival-rpc.mainnet.near.org"),
        )

        val actual = blockchainProvidersResponseMerger.merge(
            local = localResponse + localNear,
            remote = remoteResponse + remoteNear,
        )

        val expectedNear = "near-protocol" to listOf(
            ProviderModel.Public("https://rpc.mainnet.fastnear.com/"),
            ProviderModel.Private(name = "nownodes"),
            ProviderModel.Public("https://archival-rpc.mainnet.near.org/"),
        )
        Truth.assertThat(actual).isEqualTo(remoteResponse + expectedNear)
    }

    @Test
    fun test_if_non_https_and_unsupported_local_providers_are_filtered_out() {
        val localNear = "near-protocol" to listOf(
            ProviderModel.Public("http://insecure.rpc.example.com/"),
            ProviderModel.Public("https://rpc.mainnet.fastnear.com"),
            ProviderModel.Private(name = "nownodes"),
            ProviderModel.UnsupportedType,
        )
        val remoteNear = "near-protocol" to listOf(
            ProviderModel.Public("http://insecure-remote.rpc.example.com/"),
            ProviderModel.Public("https://archival-rpc.mainnet.near.org"),
        )

        val actual = blockchainProvidersResponseMerger.merge(
            local = localResponse + localNear,
            remote = remoteResponse + remoteNear,
        )

        val expectedNear = "near-protocol" to listOf(
            ProviderModel.Public("https://rpc.mainnet.fastnear.com/"),
            ProviderModel.Public("https://archival-rpc.mainnet.near.org/"),
        )
        Truth.assertThat(actual).isEqualTo(remoteResponse + expectedNear)
    }

    companion object {

        val localResponse: BlockchainProvidersResponse = mapOf(
            "aptos" to listOf(ProviderModel.Private(name = "nownodes")),
            "algorand" to listOf(
                ProviderModel.Private(name = "nownodes"),
                ProviderModel.Public(url = "https://public_alg.com/"),
            ),
        )

        // local + bitcoin
        val remoteResponse: BlockchainProvidersResponse = mapOf(
            "aptos" to listOf(ProviderModel.Private(name = "nownodes")),
            "algorand" to listOf(
                ProviderModel.Private(name = "nownodes"),
                ProviderModel.Public(url = "https://public_alg.com/"),
            ),
            "bitcoin" to listOf(ProviderModel.Private(name = "blockchair")),
        )

        private fun Pair<String, List<ProviderModel.Public>>.addSlash(): Pair<String, List<ProviderModel.Public>> {
            return first to second.map { it.copy(url = "${it.url}/") }
        }
    }
}