package com.tangem.datasource.remote.header

import com.tangem.core.remote.config.ApiEnvironment
import com.tangem.core.remote.header.RequestHeader
import com.tangem.datasource.remote.auth.AuthProvider
import com.tangem.utils.Provider

/**
 * Use ONLY for tangemApi (not express or yields)
 */
internal class TangemApiKeyHeader(authProvider: AuthProvider, apiEnvironment: Provider<ApiEnvironment>) : RequestHeader(
    "api-key" to authProvider.getApiKey(apiEnvironment),
)