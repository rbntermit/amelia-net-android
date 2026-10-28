package com.v2ray.ang.amelia

import com.v2ray.ang.amelia.AmeliaExpiry.Stage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.TimeZone

class AmeliaExpiryAnnounceTest {

    private val msk = TimeZone.getTimeZone("Europe/Moscow")
    private val day = 24L * 60 * 60 * 1000
    // 2026-11-12 18:00 MSK
    private val expireSec = 1_794_495_600L
    private val end = expireSec * 1000

    @Test
    fun expiryStages() {
        assertNull(AmeliaExpiry.stage(0, end, msk), "expire=0: no end date")
        assertNull(AmeliaExpiry.stage(null, end, msk))
        assertNull(AmeliaExpiry.stage(expireSec, end - 4 * day, msk))
        assertEquals(Stage.DAYS_3, AmeliaExpiry.stage(expireSec, end - 3 * day + 1, msk))
        assertEquals(Stage.DAY_1, AmeliaExpiry.stage(expireSec, end - 20 * 60 * 60 * 1000L, msk))
        // same calendar day, a few hours before the end
        assertEquals(Stage.LAST_DAY, AmeliaExpiry.stage(expireSec, end - 5 * 60 * 60 * 1000L, msk))
        assertEquals(Stage.LAST_DAY, AmeliaExpiry.stage(expireSec, end + day, msk))
    }

    @Test
    fun eachReminderOncePerEndDate() {
        assertTrue(AmeliaExpiry.shouldNotify(Stage.DAYS_3, expireSec, null))
        assertFalse(AmeliaExpiry.shouldNotify(Stage.DAYS_3, expireSec, "$expireSec:0"))
        assertTrue(AmeliaExpiry.shouldNotify(Stage.DAY_1, expireSec, "$expireSec:0"))
        assertTrue(AmeliaExpiry.shouldNotify(Stage.LAST_DAY, expireSec, "$expireSec:1"))
        assertFalse(AmeliaExpiry.shouldNotify(Stage.LAST_DAY, expireSec, "$expireSec:2"))
        // after a renewal the end date moves: reminders start over for the new date
        assertTrue(AmeliaExpiry.shouldNotify(Stage.DAYS_3, expireSec + 30 * 86400, "$expireSec:2"))
        assertFalse(AmeliaExpiry.shouldNotify(null, expireSec, null))
    }

    @Test
    fun announceLinks() {
        val text = "Новый сервер! Подробнее: https://ameliajustamelia.com/news. Чат: t.me/amelia_net, бот @amelianet_bot"
        val links = AmeliaAnnounce.links(text)
        assertEquals(listOf("https://ameliajustamelia.com/news", "https://t.me/amelia_net"), links.map { it.url })
        assertEquals("https://ameliajustamelia.com/news", text.substring(links[0].start, links[0].end))
        assertEquals("t.me/amelia_net", text.substring(links[1].start, links[1].end))
        assertTrue(AmeliaAnnounce.links("без ссылок").isEmpty())
        // http:// is not linked, and "xt.me/..." is not a t.me link
        assertTrue(AmeliaAnnounce.links("http://example.com and next.me/x").isEmpty())
    }

    @Test
    fun closedAnnouncementStaysHiddenNewOneShows() {
        val a = "Плановые работы в 03:00"
        assertEquals(a, AmeliaAnnounce.visible(a, null))
        val closed = AmeliaAnnounce.fingerprint(a)
        assertNull(AmeliaAnnounce.visible(a, closed))
        assertNull(AmeliaAnnounce.visible("  $a \n", closed))
        assertEquals("Новое объявление", AmeliaAnnounce.visible("Новое объявление", closed))
        assertNull(AmeliaAnnounce.visible("   ", null))
        assertNull(AmeliaAnnounce.visible(null, null))
    }

    @Test
    fun xrayVersionFromTheCoreAndAddressLabels() {
        assertEquals("26.10.10", AmeliaVersions.parseXray("Lib v39, Xray-core v26.10.10 (Xray, Penetrates Everything.)"))
        assertEquals("26.10.10", AmeliaVersions.parseXray("Xray-core 26.10.10"))
        assertNull(AmeliaVersions.parseXray("Unknown"))
        assertEquals("msk1", AmeliaAbout.baseLabel("https://msk1.ameliajustamelia.com:9443", "запасной"))
        assertEquals("msk2", AmeliaAbout.baseLabel("https://msk2.ameliajustamelia.com:9443", "запасной"))
        assertEquals(
            "запасной (sub.ameliajustamelia.net)",
            AmeliaAbout.baseLabel("https://sub.ameliajustamelia.net:9443", "запасной"),
        )
        assertNull(AmeliaAbout.baseLabel(null, "запасной"))
        // contract: report/probe go to msk1, msk2, msk3 (1.7.3), sub.com in this order
        assertEquals(
            listOf(
                "https://msk1.ameliajustamelia.com:9443",
                "https://msk2.ameliajustamelia.com:9443",
                "https://msk3.ameliajustamelia.com:9443",
                "https://sub.ameliajustamelia.com:9443",
            ),
            AmeliaNetwork.POST_BASE_URLS,
        )
    }
}
