package com.tangem.datasource.remote.interceptors

import okhttp3.HttpUrl
import java.util.Locale

/**
 * Hardened build: the default-deny policy for requests to Tangem-operated hosts, shared by
 * [WalletIdPrivacyInterceptor] (every call) and [TangemRequestPolicyNetworkInterceptor] (every request actually sent,
 * redirects included).
 *
 * Request bodies can't be inspected without buffering them, so only operations that were reviewed are allowed, as
 * an explicit method + path per host:
 * - `api.tangem.org` — public catalogue/price/market data GETs, plus two user-initiated operations (Hedera account
 *   creation, Polymarket).
 * - `express.tangem.com` (swap, onramp), `yield.tangem.org`, `gasless.tangem.org` — the operations of those services,
 *   which the user invokes and which see the addresses and transaction data of those operations by necessity.
 * Every other Tangem host and endpoint is refused, as is any Tangem request whose query carries a wallet identifier.
 */
internal object TangemRequestPolicy {

    fun isTangemHost(host: String): Boolean = TANGEM_DOMAINS.any { domain ->
        host.equals(domain, ignoreCase = true) || host.endsWith(".$domain", ignoreCase = true)
    }

    fun isAllowed(method: String, url: HttpUrl): Boolean {
        if (!isTangemHost(url.host)) return true
        if (url.queryParameterNames.any(::isWalletIdentifierName)) return false

        val rules = ALLOWLIST[url.host.lowercase(Locale.US)] ?: return false
        return rules.any { it.matches(method, url.encodedPath) }
    }

    /**
     * Whether a header or query parameter name denotes a wallet identifier, whatever its spelling: `user-id`,
     * `wallet-id`, `userWalletId`, `wallet_ids`, …
     */
    fun isWalletIdentifierName(name: String): Boolean {
        val normalized = name.lowercase(Locale.US).filter(Char::isLetterOrDigit)
        return normalized == "userid" || normalized.contains("walletid")
    }

    private class Rule(private val method: String, pathPattern: String) {
        private val path = Regex(pathPattern, RegexOption.IGNORE_CASE)

        fun matches(method: String, encodedPath: String): Boolean =
            this.method.equals(method, ignoreCase = true) && path.matches(encodedPath)
    }

    private val TANGEM_DOMAINS = listOf("tangem.org", "tangem.com", "tangem-tech.com")

    private val ALLOWLIST: Map<String, List<Rule>> = mapOf(
        "api.tangem.org" to listOf(
            Rule("GET", "/v1/coins(/.*)?"),
            Rule("GET", "/api/v1/coins/indicators"),
            Rule("GET", "/api/v1/market-indicators"),
            Rule("GET", "/v1/currencies"),
            Rule("GET", "/v1/geo"),
            Rule("GET", "/v1/application/versions"),
            Rule("GET", "/v1/quotes"),
            Rule("GET", "/v1/hot_crypto"),
            Rule("GET", "/v1/stories/[^/]+"),
            Rule("GET", "/v1/earn/(markets|networks)"),
            Rule("GET", "/v1/networks/providers"),
            Rule("GET", "/v1/news(/.*)?"),
            // user-initiated: creating a Hedera account sends that network's public key
            Rule("POST", "/v1/user-network-account"),
            // user-initiated: the Polymarket feature, which needs its own wallet addresses
            Rule("GET", "/api/predictions/v1/.+"),
            Rule("POST", "/api/predictions/v1/.+"),
        ),
        "express.tangem.com" to listOf(
            Rule("POST", "/v1/(assets|pairs|exchange-sent|onramp-pairs)"),
            Rule(
                "GET",
                "/v1/(providers|exchange-quote|exchange-data|exchange-status|history/exchange|history/delta/exchange" +
                    "|currencies|countries|country-by-ip|payment-methods|onramp-quote|onramp-data|onramp-status" +
                    "|history/onramp|history/delta/onramp)",
            ),
        ),
        "yield.tangem.org" to listOf(
            Rule("GET", "/api/v1/yield/markets"),
            Rule("GET", "/api/v1/yield/token/[^/]+/[^/]+(/chart)?"),
            Rule("GET", "/api/v1/yield-modules/markets"),
            Rule("GET", "/api/v1/yield-modules/[^/]+/[^/]+/(incentives|apr-history|claim-calldata|claim-history)"),
            Rule("POST", "/api/v1/module/(activate|deactivate)"),
        ),
        "gasless.tangem.org" to listOf(
            Rule("GET", "/api/v1/(tokens|config/fee-recipient|tron/tokens)"),
            Rule("POST", "/api/v1/transaction/sign"),
            Rule("POST", "/api/v2/transaction/(sign|batch-sign)"),
            Rule("POST", "/api/v1/tron/transaction/(estimate|submit)"),
        ),
    )
}
