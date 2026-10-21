package com.v2ray.ang.amelia

import com.v2ray.ang.amelia.AmeliaExpiry.Stage
import com.v2ray.ang.amelia.AmeliaFormat.SubLine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.TimeZone

/** 1.5.0: subscription line, reminders, Telegram proxy. */
class Amelia150Test {

    private val msk = TimeZone.getTimeZone("Europe/Moscow")
    private val hour = 60L * 60 * 1000
    private val day = 24 * hour
    // 2026-10-21 12:00 MSK
    private val now = 1_792_573_200_000L
    private val gb = 1024L * 1024 * 1024

    private fun info(userinfo: String?) = AmeliaSubInfo.fromHeaders(
        { name -> if (name.equals(AmeliaSubInfo.HEADER_USERINFO, ignoreCase = true)) userinfo else null },
        now,
        decodeBase64 = { null },
    )

    @Test
    fun userinfoExpireZeroPastFuture() {
        assertEquals(SubLine.Unlimited, AmeliaFormat.subLine(info("upload=0; download=0; total=0; expire=0"), now, msk))
        assertEquals(SubLine.Ended, AmeliaFormat.subLine(info("expire=${(now - hour) / 1000}"), now, msk))
        // 05.11 18:00 MSK: 15 calendar days from 21.10
        val nov5 = (now + 15 * day + 6 * hour) / 1000
        assertEquals(SubLine.Until(nov5, 15, lastDay = false, soon = false), AmeliaFormat.subLine(info("expire=$nov5"), now, msk))
        // not loaded yet, or no expire in the header: no line
        assertNull(AmeliaFormat.subLine(null, now, msk))
        assertNull(AmeliaFormat.subLine(info(null), now, msk))
        assertNull(AmeliaFormat.subLine(info("upload=1; download=2; total=3"), now, msk))
    }

    @Test
    fun highlightFromThreeDays() {
        fun at(days: Int) = AmeliaFormat.subLine(AmeliaSubInfo(expire = (now + days * day) / 1000), now, msk) as SubLine.Until
        assertEquals(false, at(4).soon)
        assertEquals(true, at(3).soon)
        assertEquals(true, at(1).soon)
        // later today: the last day
        val today = AmeliaFormat.subLine(AmeliaSubInfo(expire = (now + 5 * hour) / 1000), now, msk) as SubLine.Until
        assertEquals(0, today.days)
        assertEquals(true, today.lastDay)
    }

    @Test
    fun usageOnlyWithALimit() {
        assertNull(AmeliaFormat.usageGb(info("upload=1000; download=2000; total=0; expire=0")))
        assertNull(AmeliaFormat.usageGb(info("upload=1000; download=2000; expire=0")))
        val used = (12.4 * gb).toLong()
        assertEquals("12,4" to "100", AmeliaFormat.usageGb(info("upload=${used / 2}; download=${used - used / 2}; total=${100 * gb}; expire=0")))
        assertEquals("0" to "50", AmeliaFormat.usageGb(AmeliaSubInfo(total = 50 * gb)))
    }

    @Test
    fun remindersThreeDaysOneDayLastDayOnce() {
        val expire = (now + 10 * day) / 1000
        val end = expire * 1000
        var mark: String? = null
        fun check(at: Long): Stage? = AmeliaExpiry.decide(expire, at, mark, msk).also { mark = it.mark }.notify

        assertNull(check(end - 5 * day))
        assertEquals(Stage.DAYS_3, check(end - 3 * day + hour))
        assertNull(check(end - 2 * day), "3 days: once")
        assertEquals(Stage.DAY_1, check(end - 20 * hour))
        assertNull(check(end - 19 * hour), "1 day: once")
        assertEquals(Stage.LAST_DAY, check(end - 2 * hour))
        assertNull(check(end + hour), "last day: once, also after the end")
    }

    @Test
    fun renewalStartsRemindersOver() {
        val expire = (now + 2 * day) / 1000
        var d = AmeliaExpiry.decide(expire, now, null, msk)
        assertEquals(Stage.DAYS_3, d.notify)
        d = AmeliaExpiry.decide(expire, now + 30 * hour, d.mark, msk)
        assertEquals(Stage.DAY_1, d.notify)
        // renewed for 30 days: nothing now, the 3-day reminder comes again before the new end
        val renewed = expire + 30 * 86_400
        assertNull(AmeliaExpiry.decide(renewed, now + 31 * hour, d.mark, msk).notify)
        assertEquals(Stage.DAYS_3, AmeliaExpiry.decide(renewed, renewed * 1000 - 2 * day, d.mark, msk).notify)
        // no end date: never
        assertNull(AmeliaExpiry.decide(0, now, null, msk).notify)
    }

    @Test
    fun telegramProxyLinkAndApp() {
        assertEquals("https://t.me/amelianet_bot?start=tgproxy", AmeliaConfig.botUrl(AmeliaConfig.BotScreen.TGPROXY))
        assertEquals("https://t.me/amelianet_bot?start=pay", AmeliaConfig.botUrl(AmeliaConfig.BotScreen.PAY))
        assertEquals("org.telegram.messenger", AmeliaPackages.pickTelegram(listOf("org.example.fork", "org.telegram.messenger")))
        assertEquals("org.example.fork", AmeliaPackages.pickTelegram(listOf("org.example.fork")))
        assertNull(AmeliaPackages.pickTelegram(emptyList()))
    }
}
