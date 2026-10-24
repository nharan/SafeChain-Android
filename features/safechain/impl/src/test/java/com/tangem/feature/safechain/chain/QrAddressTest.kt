package com.tangem.feature.safechain.chain

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

internal class QrAddressTest {

    private val usdc = SepoliaConfig.USDC
    private val friend = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8"

    @Test
    fun `a token transfer request gives the recipient, not the token contract`() {
        val r = scannedAddress("ethereum:$usdc@11155111/transfer?address=$friend&uint256=15e6")
        assertThat(r).isEqualTo(ScannedAddress.Address(friend, token = usdc))
    }

    @Test
    fun `plain addresses and simple requests are accepted`() {
        assertThat(scannedAddress(friend)).isEqualTo(ScannedAddress.Address(friend))
        assertThat(scannedAddress("ethereum:$friend@11155111")).isEqualTo(ScannedAddress.Address(friend))
        assertThat(scannedAddress("ethereum:$friend")).isEqualTo(ScannedAddress.Address(friend))
    }

    @Test
    fun `another network, another call, or a bad checksum is refused`() {
        assertThat(scannedAddress("ethereum:$friend@1")).isInstanceOf(ScannedAddress.Rejected::class.java)
        assertThat(scannedAddress("ethereum:$usdc@11155111/approve?address=$friend&uint256=1"))
            .isInstanceOf(ScannedAddress.Rejected::class.java)
        // One character's case changed: no longer a valid EIP-55 checksum.
        assertThat(scannedAddress(friend.replace("C51812", "c51812"))).isInstanceOf(ScannedAddress.Rejected::class.java)
        assertThat(scannedAddress("hello 0x70997970C51812dc3A010C7d01b50e0d17dc79C8")).isInstanceOf(ScannedAddress.Rejected::class.java)
    }

    @Test
    fun `non-ASCII digits are not hex`() {
        assertThat(parseUserAddress("0x" + "１".repeat(40))).isNull()
    }
}
