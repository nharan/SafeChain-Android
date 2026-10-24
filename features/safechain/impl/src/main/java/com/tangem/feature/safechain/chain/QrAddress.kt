package com.tangem.feature.safechain.chain

/** What a scanned QR code means as a destination. */
internal sealed interface ScannedAddress {
    /** The address to pay. [token] is set when the QR asked for a token transfer (EIP-681 `/transfer`). */
    data class Address(val address: String, val token: String? = null) : ScannedAddress
    data class Rejected(val reason: String) : ScannedAddress
}

/**
 * Reads a destination from a QR code: a plain address, or an EIP-681 payment request such as
 * `ethereum:0xTOKEN@11155111/transfer?address=0xRECIPIENT&uint256=15e6`. The first address in such a code is
 * the token contract, not the recipient: taking it would send the tokens to the token contract, lost for good
 * (security review 2026-10-24). A request for another network, or for any other contract call, is refused.
 */
internal fun scannedAddress(raw: String, chainId: Long = SepoliaConfig.CHAIN_ID): ScannedAddress {
    val text = raw.trim()
    if (!text.startsWith("ethereum:", ignoreCase = true)) {
        return parseUserAddress(text)?.let { ScannedAddress.Address(it) }
            ?: ScannedAddress.Rejected("That QR code isn't an address.")
    }
    val body = text.substring("ethereum:".length).removePrefix("pay-")
    val target = body.takeWhile { it != '@' && it != '/' && it != '?' }
    val rest = body.substring(target.length)
    val chain = if (rest.startsWith("@")) rest.drop(1).takeWhile { it != '/' && it != '?' } else null
    if (chain != null && chain.toLongOrNull() != chainId) {
        return ScannedAddress.Rejected("That QR code is for another network (chain $chain), not ${SepoliaConfig.NAME}.")
    }
    val afterChain = if (chain != null) rest.drop(1 + chain.length) else rest
    val function = if (afterChain.startsWith("/")) afterChain.drop(1).takeWhile { it != '?' } else null
    val query = afterChain.substringAfter('?', "").split('&').filter { '=' in it }
        .associate { it.substringBefore('=') to it.substringAfter('=') }
    val targetAddress = parseUserAddress(target) ?: return ScannedAddress.Rejected("That QR code has no valid address.")
    return when (function) {
        null -> ScannedAddress.Address(targetAddress)
        "transfer" -> query["address"]?.let(::parseUserAddress)?.let { ScannedAddress.Address(it, token = targetAddress) }
            ?: ScannedAddress.Rejected("That payment QR code has no recipient address.")
        else -> ScannedAddress.Rejected("That QR code asks for a contract call ($function), not a payment address.")
    }
}
