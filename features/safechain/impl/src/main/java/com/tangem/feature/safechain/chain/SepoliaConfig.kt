package com.tangem.feature.safechain.chain

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

    const val TOKEN = "0x16822965d34e80D7E700Fa519C2aCa4c71835032"
    const val TOKEN_SYMBOL = "PWTEST"
    const val TOKEN_DECIMALS = 18
    const val FACTORY = "0x1f153E74c6Aa3cc1FFB775f8166C028654625665"
    const val ESCROW = "0x0c57772f68D4Ff4c432270b616F08c4884507913"
    const val START_BLOCK = 11_979_489L

    const val EXPLORER_TX = "https://sepolia.etherscan.io/tx/"

    /**
     * Where the recovery device signs its consent: the hosted SafeChain site's consent page
     * (SafeChain/web, deployed with `npm run deploy`), opened where the recovery key's wallet is.
     */
    const val CONSENT_PAGE = "https://safechain.rep.run/#/consent/"
}
