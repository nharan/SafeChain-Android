package com.tangem.feature.safechain.ui

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

internal class HoldTextTest {

    @Test
    fun `custom holds take decimals in any unit`() {
        assertThat(holdSeconds("5", HoldUnit.Minutes)).isEqualTo(300)
        assertThat(holdSeconds("0.1", HoldUnit.Hours)).isEqualTo(360)
        assertThat(holdSeconds("1,5", HoldUnit.Hours)).isEqualTo(5_400)
        assertThat(holdSeconds("2.5", HoldUnit.Days)).isEqualTo(216_000)
        assertThat(holdSeconds("", HoldUnit.Hours)).isNull()
        assertThat(holdSeconds(".", HoldUnit.Hours)).isNull()
        assertThat(holdSeconds("0", HoldUnit.Hours)).isNull()
    }

    @Test
    fun `the escrow's limits are 5 minutes and 30 days`() {
        assertThat(MIN_HOLD_SECONDS).isEqualTo(300)
        assertThat(MAX_HOLD_SECONDS).isEqualTo(2_592_000)
    }

    @Test
    fun `holds read as words`() {
        assertThat(holdText(300)).isEqualTo("5 minutes")
        assertThat(holdText(60)).isEqualTo("1 minute")
        assertThat(holdText(360)).isEqualTo("6 minutes")
        assertThat(holdText(3_600)).isEqualTo("1 hour")
        assertThat(holdText(5_400)).isEqualTo("1h 30m")
        assertThat(holdText(86_400)).isEqualTo("1 day")
        assertThat(holdText(259_200)).isEqualTo("3 days")
        assertThat(holdText(604_800)).isEqualTo("1 week")
        assertThat(holdText(216_000)).isEqualTo("60 hours")
    }
}
