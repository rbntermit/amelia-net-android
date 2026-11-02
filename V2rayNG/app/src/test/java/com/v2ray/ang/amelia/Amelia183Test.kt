package com.v2ray.ang.amelia

import com.v2ray.ang.amelia.AmeliaHttp.Route
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 1.8.3: the third Russian entry is spb1 (St Petersburg) instead of msk3 (Kemerovo, switched off). */
class Amelia183Test {

    private val msk1 = "https://msk1.ameliajustamelia.com:9443"
    private val msk2 = "https://msk2.ameliajustamelia.com:9443"
    private val spb1 = "https://spb1.ameliajustamelia.com:9443"
    private val com = "https://sub.ameliajustamelia.com:9443"

    /** What 1.7.3 … 1.8.2 stored for the removed entry. */
    private val oldMsk3 = "https://msk3.ameliajustamelia.com:9443"
    private val oldMsk3Ipv4 = "138.124.77.193"
    private val oldMsk3Ipv6 = "2a05:541:142:1aa::1"

    // ---- AmeliaConfig ---------------------------------------------------------------------

    @Test
    fun spb1IsTheThirdEntryAndMsk3IsGone() {
        assertEquals(listOf("msk1", "msk2", "spb1"), AmeliaConfig.ENTRIES.map { it.name })
        val third = AmeliaConfig.ENTRIES[2]
        assertEquals(AmeliaConfig.Entry("spb1", "spb1.ameliajustamelia.com", "170.168.151.63", "2a0a:2b41:7:8f88::"), third)
        assertEquals(spb1, third.baseUrl)
        assertTrue(AmeliaConfig.ENTRIES.none { "msk3" in it.name || "msk3" in it.domain })
        assertEquals("spb1", AmeliaConfig.entryNameOf("https://spb1.ameliajustamelia.com:9443"))
        assertNull(AmeliaConfig.entryNameOf(oldMsk3))
    }

    @Test
    fun mirrorOrdersEndWithSpb1() {
        assertEquals(listOf(msk2, msk1, spb1), AmeliaConfig.SITE_BASE_URLS)
        assertEquals(listOf(msk2, msk1, spb1), AmeliaConfig.RESUBSCRIBE_BASE_URLS)
        assertTrue(AmeliaConfig.SUB_BASE_URLS.none { "msk3" in it })
        assertTrue(AmeliaNetwork.POST_BASE_URLS.none { "msk3" in it })
    }

    @Test
    fun ourAddressesHaveSpb1AndNotMsk3() {
        assertTrue("170.168.151.63" in AmeliaConfig.OUR_SERVER_IPS)
        assertTrue("2a0a:2b41:7:8f88::" in AmeliaConfig.OUR_SERVER_IPS)
        assertFalse(oldMsk3Ipv4 in AmeliaConfig.OUR_SERVER_IPS)
        assertFalse(oldMsk3Ipv6 in AmeliaConfig.OUR_SERVER_IPS)
    }

    // ---- values stored by 1.8.2 with the msk3 address --------------------------------------

    @Test
    fun lastAnsweringMirrorMsk3FromOldVersionIsPassedOver() {
        // "amelia_last_base" as 1.8.2 wrote it after msk3 answered
        assertNull(AmeliaHttp.knownBase(oldMsk3))
        assertEquals(AmeliaConfig.SUB_BASE_URLS, AmeliaHttp.orderBases(AmeliaHttp.knownBase(oldMsk3)))
        assertEquals(AmeliaConfig.SUB_BASE_URLS, AmeliaHttp.orderBases(oldMsk3))
        // the first attempt is the usual first mirror, never the dead one
        val plan = AmeliaHttp.plan(AmeliaHttp.orderBases(AmeliaHttp.knownBase(oldMsk3)), tunnel = true)
        assertEquals(msk1, plan.first().base)
        assertTrue(plan.none { it.base == oldMsk3 })
        // an APK on our domain is not moved to the dead address either
        val apk = AmeliaUpdater.downloadPlan("$com/app/AmeliaNet_1.8.3_universal.apk", AmeliaHttp.knownBase(oldMsk3), tunnel = false)
        assertEquals("$msk1/app/AmeliaNet_1.8.3_universal.apk", apk.first().first)
        assertTrue(apk.none { it.first.startsWith(oldMsk3) })
        // nothing stored or blank: the usual order too
        assertNull(AmeliaHttp.knownBase(null))
        assertNull(AmeliaHttp.knownBase(""))
        // a live mirror still goes first
        assertEquals(spb1, AmeliaHttp.knownBase(spb1))
        assertEquals(spb1, AmeliaHttp.orderBases(AmeliaHttp.knownBase(spb1)).first())
    }

    @Test
    fun subscriptionSourceMsk3FromOldVersionIsForgotten() {
        // "amelia_sub_source" as 1.8.2 wrote it: "<base> <route>"
        assertNull(AmeliaHttp.sourceOf("$oldMsk3 TUNNEL"))
        assertNull(AmeliaHttp.sourceOf("$oldMsk3 DIRECT"))
        assertEquals(spb1 to Route.TUNNEL, AmeliaHttp.sourceOf("$spb1 TUNNEL"))
        assertEquals(com to Route.DIRECT, AmeliaHttp.sourceOf("$com DIRECT"))
        // garbage and the cleared value after logout
        assertNull(AmeliaHttp.sourceOf(null))
        assertNull(AmeliaHttp.sourceOf(""))
        assertNull(AmeliaHttp.sourceOf(spb1))
        assertNull(AmeliaHttp.sourceOf("$spb1 SIDEWAYS"))
    }

    @Test
    fun oldJournalLineStaysReadable() {
        // the journal keeps "Подписка скачана: msk3, …" as text; a new msk3 base gets no entry name
        assertEquals("запасной (msk3.ameliajustamelia.com)", AmeliaAbout.baseLabel(oldMsk3, "запасной"))
        assertEquals("spb1", AmeliaAbout.baseLabel(spb1, "запасной"))
    }

    // ---- crash reports: spb1's IPv6 ends with "::" ------------------------------------------

    @Test
    fun spb1Ipv6IsOursInEverySpelling() {
        val key = "2a0a:2b41:7:8f88:0:0:0:0"
        val spellings = listOf(
            "2a0a:2b41:7:8f88::",
            "2a0a:2b41:7:8f88:0:0:0:0",
            "2a0a:2b41:0007:8f88:0000:0000:0000:0000",
            "2A0A:2B41:0007:8F88:0000:0000:0000:0000",
            "2a0a:2b41:7:8f88:0::",
            "2a0a:2b41:7:8f88::0",
            "2a0a:2b41:7:8f88::%wlan0",
        )
        spellings.forEach {
            assertEquals(key, AmeliaCrash.ipv6Key(it), it)
            assertTrue(AmeliaCrash.isOurIpv6(it), it)
        }
        assertFalse(AmeliaCrash.isOurIpv6("2a0a:2b41:7:8f88::1"))
        assertFalse(AmeliaCrash.isOurIpv6("2a0a:2b41:7:8f89::"))
        assertFalse(AmeliaCrash.isOurIpv6(oldMsk3Ipv6))
    }

    @Test
    fun crashScrubKeepsSpb1AndRemovesOldMsk3() {
        val text = """
            dial udp [2a0a:2b41:7:8f88::]:8444 failed; dial tcp [2a0a:2b41:0007:8f88:0000:0000:0000:0000]:8471
            route via 2A0A:2B41:7:8F88:0:0:0:0, last 2a0a:2b41:7:8f88::
            and 170.168.151.63:9443
            old msk3 $oldMsk3Ipv4:9443 and [$oldMsk3Ipv6]:8444, someone 2a0a:2b41:7:8f88::1
        """.trimIndent()
        val s = AmeliaCrash.scrub(text)
        listOf(
            "[2a0a:2b41:7:8f88::]:8444", "[2a0a:2b41:0007:8f88:0000:0000:0000:0000]:8471",
            "2A0A:2B41:7:8F88:0:0:0:0", "last 2a0a:2b41:7:8f88::", "170.168.151.63:9443",
        ).forEach { assertTrue(it in s, "$it must stay: $s") }
        listOf(oldMsk3Ipv4, oldMsk3Ipv6, "2a0a:2b41:7:8f88::1").forEach { assertFalse(it in s, "$it must go: $s") }
    }
}
