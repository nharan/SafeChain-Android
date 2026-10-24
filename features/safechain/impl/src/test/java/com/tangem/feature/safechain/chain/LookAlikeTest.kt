package com.tangem.feature.safechain.chain

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

internal class LookAlikeTest {

    private val friend = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8"
    private val poisoned = "0x7099aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa79C8"
    private val other = "0x3C44CdDdB6a900fa2b585dd299e03d12FA4293BC"

    @Test
    fun `an address with the same start and end but a different middle is flagged`() {
        assertThat(lookAlike(poisoned, listOf(other, friend))).isEqualTo(friend)
    }

    @Test
    fun `the same address in other capitals, or an unrelated one, is not`() {
        assertThat(lookAlike(friend.lowercase(), listOf(friend))).isNull()
        assertThat(lookAlike(other, listOf(friend))).isNull()
        assertThat(lookAlike(friend, emptyList())).isNull()
    }
}
