package com.tangem.feature.safechain.chain

/**
 * An address in [known] that [address] imitates: a different address with the same first four and last four
 * characters after 0x. That's address poisoning: an attacker makes such an address, sends a tiny transfer so it
 * sits in your history, and hopes you copy it next time. Same rule as the explorer (web/src/lib/data.ts looksAlike).
 */
internal fun lookAlike(address: String, known: Iterable<String>): String? {
    val a = address.lowercase()
    return known.firstOrNull { k ->
        val b = k.lowercase()
        a != b && a.length == b.length && a.substring(2, 6) == b.substring(2, 6) && a.takeLast(4) == b.takeLast(4)
    }
}
