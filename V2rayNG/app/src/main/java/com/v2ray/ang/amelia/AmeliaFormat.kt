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

    /** 754_000 ms → "12:34", 3_725_000 → "1:02:05". */
    fun duration(ms: Long): String {
        val s = (ms.coerceAtLeast(0L) / 1000)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%d:%02d", m, sec)
    }

    /** "15.10 14:05" for the last update check. */
    fun dateTime(epochMillis: Long): String =
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US).format(Date(epochMillis))

    /** 32 400 000 bytes → "31" (MB, binary, whole). */
    fun megabytes(bytes: Long): String = ((bytes + 512 * 1024) / (1024 * 1024)).toString()

    fun date(epochSeconds: Long): String =
        SimpleDateFormat("dd.MM.yyyy", Locale.US).format(Date(epochSeconds * 1000))

    /** "12.11" for the subscription line on the main screen. */
    fun dayMonth(epochSeconds: Long): String =
        SimpleDateFormat("dd.MM", Locale.US).format(Date(epochSeconds * 1000))

    fun isExpired(info: AmeliaSubInfo, nowMillis: Long): Boolean =
        info.hasExpire && (info.expire ?: 0L) * 1000 < nowMillis

    enum class PingLevel { GOOD, MEDIUM, BAD, FAILED }

    /** Colour bucket for a ping like Happ: green under 300 ms, amber under 800 ms. */
    fun pingLevel(ms: Long): PingLevel = when {
        ms < 0 -> PingLevel.FAILED
        ms < 300 -> PingLevel.GOOD
        ms < 800 -> PingLevel.MEDIUM
        else -> PingLevel.BAD
    }

    /** Whole days until expiry (0 on the last day), or null when there is no end date or it passed. */
    fun daysLeft(info: AmeliaSubInfo, nowMillis: Long): Int? {
        if (!info.hasExpire) return null
        val left = (info.expire ?: 0L) * 1000 - nowMillis
        if (left < 0) return null
        return (left / (24L * 60 * 60 * 1000)).toInt()
    }
}
