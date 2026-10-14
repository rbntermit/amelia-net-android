package com.v2ray.ang.amelia

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Pure formatting helpers for the account card (unit-tested). */
object AmeliaFormat {

    /**
     * 1536 → "1.5" + unit index 1 (KB). Binary units, one decimal below 100, none above.
     * @return value text and unit index: 0=B, 1=KB, 2=MB, 3=GB, 4=TB
     */
    fun bytes(value: Long): Pair<String, Int> {
        var v = value.coerceAtLeast(0L).toDouble()
        var unit = 0
        while (v >= 1024 && unit < 4) {
            v /= 1024
            unit++
        }
        val text = when {
            unit == 0 || v >= 100 -> String.format(Locale.US, "%.0f", v)
            else -> String.format(Locale.US, "%.1f", v).removeSuffix(".0")
        }
        return text.replace('.', ',') to unit
    }

    fun date(epochSeconds: Long): String =
        SimpleDateFormat("dd.MM.yyyy", Locale.US).format(Date(epochSeconds * 1000))

    fun isExpired(info: AmeliaSubInfo, nowMillis: Long): Boolean =
        info.hasExpire && (info.expire ?: 0L) * 1000 < nowMillis
}
