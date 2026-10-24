package com.tangem.feature.safechain.ui

import java.math.BigDecimal
import java.math.RoundingMode

/** Units for a custom hold, as the Send page offers them. */
internal enum class HoldUnit(val label: String, val seconds: Long) {
    Minutes("minutes", 60),
    Hours("hours", 3_600),
    Days("days", 86_400),
}

/** Shortest and longest hold the payment escrow accepts (ProtectedTransferEscrow.MIN/MAX_PROTECTION). */
internal const val MIN_HOLD_SECONDS = 5 * 60L
internal const val MAX_HOLD_SECONDS = 30 * 86_400L

/** "10" + Minutes → 600 s; decimals allowed ("0.1" hours = 360 s). Null if it isn't a number. */
internal fun holdSeconds(value: String, unit: HoldUnit): Long? =
    value.trim().replace(',', '.').toBigDecimalOrNull()
        ?.multiply(BigDecimal.valueOf(unit.seconds))
        ?.setScale(0, RoundingMode.HALF_UP)
        ?.toLong()
        ?.takeIf { it > 0 }

/** A hold in words: "5 minutes", "1 hour", "1 day", "3 days", "1 week", "1h 30m". */
internal fun holdText(seconds: Long): String {
    val minute = 60L
    val hour = 3_600L
    val day = 86_400L
    fun plural(n: Long, one: String) = if (n == 1L) "1 $one" else "$n ${one}s"
    return when {
        seconds == 7 * day -> "1 week"
        seconds % day == 0L -> plural(seconds / day, "day")
        seconds % hour == 0L -> plural(seconds / hour, "hour")
        seconds < hour && seconds % minute == 0L -> plural(seconds / minute, "minute")
        seconds < hour -> "${seconds / minute}m ${seconds % minute}s"
        else -> "${seconds / hour}h ${seconds % hour / minute}m"
    }
}
