package com.tangem.feature.safechain.chain

/** An ERC-20 SafeChain shows. [usd] is its price, or null when it has none (test tokens). */
internal data class Token(
    val address: String,
    val symbol: String,
    val name: String,
    val decimals: Int,
    val usd: java.math.BigDecimal?,
) {
    val key: String get() = address.lowercase()
}

/**
 * SafeChain's Sepolia deployment (github.com/nharan/SafeChain, contracts/deployments/sepolia.json),
 * verified on Sourcify as an exact match. Contracts have no owner and can't be upgraded.
 */
internal object SepoliaConfig {
    const val CHAIN_ID = 11_155_111L
    const val NAME = "Sepolia"

    /** Two independent providers, cross-checked on every read (block hash included). */
    val RPC_URLS = listOf(
        "https://ethereum-sepolia-rpc.publicnode.com",
        "https://sepolia.gateway.tenderly.co",
    )

    /** SafeChain's own valueless test token. */
    const val TOKEN = "0x16822965d34e80D7E700Fa519C2aCa4c71835032"
    const val TOKEN_SYMBOL = "PWTEST"
    const val TOKEN_DECIMALS = 18

    /** Circle's Sepolia USDC (faucet.circle.com). Counted at $1. */
    const val USDC = "0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238"

    /**
     * The tokens both accounts hold, in display order. Savings and the escrow take any ERC-20; native ETH stays
     * in Checking (it pays fees and can only be sent instantly). Sepolia ETH has no dollar value here.
     */
    val TOKENS = listOf(
        Token(USDC, "USDC", "USD Coin", 6, usd = java.math.BigDecimal.ONE),
        Token(TOKEN, TOKEN_SYMBOL, TOKEN_SYMBOL, TOKEN_DECIMALS, usd = null),
    )

    fun token(address: String): Token? = TOKENS.firstOrNull { it.address.equals(address, ignoreCase = true) }

    /** Redeployed 2026-10-24: its vaults have releaseNow ("release now with both keys"). New Savings open here. */
    const val FACTORY = "0xf7a83cbd1f0C5DA59987bd6cCAF4830E766f4873"

    /** Earlier factories, oldest first. Their vaults keep working under their own rules and are still found. */
    val PREVIOUS_FACTORIES = listOf("0x1f153E74c6Aa3cc1FFB775f8166C028654625665")

    /** Every factory, oldest first, so vaults come out in the order they were opened. */
    val FACTORIES = PREVIOUS_FACTORIES + FACTORY
    /** Redeployed 2026-10-23 with a 5-minute shortest hold (was 0x0c57772f…7913, 1 hour). */
    const val ESCROW = "0x8F727931aB9a297EbfB90280060c3a54f1C979f2"
    const val START_BLOCK = 11_979_489L

    /**
     * SafeChain's own explorer (SafeChain/web, hosted): it shows a payment's countdown, destination and the
     * "is this the right address?" check, which a general block explorer can't.
     */
    const val EXPLORER = "https://safechain.rep.run/#/explorer"
    const val EXPLORER_TX = "$EXPLORER/tx/"

    fun explorerWithdrawal(vault: String, id: String) = "$EXPLORER/vault/$vault/w/$id"

    /**
     * Where the recovery device signs its consent: the hosted SafeChain site's consent page
     * (SafeChain/web, deployed with `npm run deploy`), opened where the recovery key's wallet is.
     */
    const val CONSENT_PAGE = "https://safechain.rep.run/#/consent/"
}
