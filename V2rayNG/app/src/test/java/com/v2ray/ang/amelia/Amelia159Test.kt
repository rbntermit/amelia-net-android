package com.v2ray.ang.amelia

import com.google.gson.JsonParser
import com.v2ray.ang.amelia.AmeliaWhitelist.Verdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 1.5.9: hints "good", quarantine, switch limit, "трафика нет", operator white lists, "wl". */
class Amelia159Test {

    private fun e(name: String, ms: Long) = AmeliaChecks.Entry(name, "h", 1, ms, if (ms >= 0) "ok" else "data", 1L, "mobile|AS8359")

    private val locs = listOf(
        AmeliaQueue.Location("nl", "Нидерланды 1", "1.1.1.1:443"),
        AmeliaQueue.Location("fi", "Финляндия 2", "2.2.2.2:443"),
        AmeliaQueue.Location("se", "Швеция 1", "3.3.3.3:443"),
        AmeliaQueue.Location("de", "Германия 1", "4.4.4.4:443"),
        AmeliaQueue.Location("pl", "Польша 1", "5.5.5.5:443"),
        AmeliaQueue.Location("tg", "Только Telegram", "6.6.6.6:443"),
    )

    // ---- 1. hints: good → the rest → bad --------------------------------------------------------

    @Test
    fun goodFirstThenTheRestByPingThenBad() {
        val known = mapOf("Нидерланды 1" to e("Нидерланды 1", 40), "Финляндия 2" to e("Финляндия 2", 300))
        val good = mapOf("3.3.3.3:443" to 7, "5.5.5.5:443" to 12)
        val bad = setOf("4.4.4.4:443")
        val q = AmeliaQueue.order(locs, known, bad, preferred = null, allowLastResort = false, good = good)
        // good by passed checks (Польша 12, Швеция 7), then working by ping, then bad
        assertEquals(listOf("pl", "se", "nl", "fi", "de"), q)
    }

    @Test
    fun goodWithEqualCountsGoesByOwnPing() {
        val known = mapOf("Швеция 1" to e("Швеция 1", 200), "Польша 1" to e("Польша 1", 90))
        val good = mapOf("3.3.3.3:443" to 5, "5.5.5.5:443" to 5)
        val q = AmeliaQueue.order(locs, known, emptySet(), preferred = null, allowLastResort = false, good = good)
        assertEquals(listOf("pl", "se"), q.take(2))
    }

    @Test
    fun ownFailedCheckOutweighsGood() {
        val known = mapOf("Швеция 1" to e("Швеция 1", -1), "Нидерланды 1" to e("Нидерланды 1", 50))
        val q = AmeliaQueue.order(locs, known, emptySet(), null, allowLastResort = false, good = mapOf("3.3.3.3:443" to 9))
        assertEquals("nl", q.first())
        assertEquals("se", q.last())
    }

    @Test
    fun noHintsMeansTheOldOrder() {
        val known = mapOf("Финляндия 2" to e("Финляндия 2", 80))
        assertEquals(
            AmeliaQueue.order(locs, known, emptySet(), null, allowLastResort = false),
            AmeliaQueue.order(locs, known, emptySet(), null, allowLastResort = false, good = emptyMap()),
        )
    }

    @Test
    fun hintsAnswerWithGoodIsRead() {
        val h = AmeliaHints.parse(
            """{"asn":8359,"as":"MTS PJSC","net":"mobile","bad":[{"host":"4.4.4.4","port":443,"bad":5,"of":6,"stage":"connect"}],""" +
                """"good":[{"host":"3.3.3.3","port":443,"ok":9,"of":10},{"host":"7.7.7.7","port":443,"ok":0,"of":3}],"dev":14,"ts":1792600000,"src":"probe"}""",
        )!!
        assertEquals("mobile", h.net)
        assertEquals(mapOf("3.3.3.3:443" to 9), AmeliaHints.goodHostPorts(h))
        assertEquals(setOf("4.4.4.4:443"), AmeliaHints.badHostPorts(h))
        // an answer from before 1.5.9 (no "good") still works
        assertEquals(emptyMap<String, Int>(), AmeliaHints.goodHostPorts(AmeliaHints.parse("""{"asn":1,"bad":[]}""")))
    }

    @Test
    fun hintsAskForTheNetworkType() {
        assertEquals("/app/hints?net=mobile", AmeliaHints.query(AmeliaNetwork.NET_MOBILE))
        assertEquals("/app/hints?net=wifi", AmeliaHints.query(AmeliaNetwork.NET_WIFI))
        assertEquals("/app/hints", AmeliaHints.query(AmeliaNetwork.NET_OTHER))
    }

    @Test
    fun hintsAreCachedPerNetwork() {
        var nets = emptyMap<String, AmeliaHints.Cached>()
        nets = AmeliaHints.store(nets, "mobile|MTS", AmeliaHints.Cached(at = 1, net = "mobile|MTS"))
        nets = AmeliaHints.store(nets, "wifi|MTS", AmeliaHints.Cached(at = 2, net = "wifi|MTS"))
        assertEquals(setOf("mobile|MTS", "wifi|MTS"), nets.keys)
        repeat(AmeliaHints.MAX_NETS + 3) { i -> nets = AmeliaHints.store(nets, "n$i", AmeliaHints.Cached(at = 10L + i, net = "n$i")) }
        assertEquals(AmeliaHints.MAX_NETS, nets.size)
        assertFalse("mobile|MTS" in nets, "the oldest go first")
    }

    // ---- 2. quarantine ----------------------------------------------------------------------------

    private val t = 1_800_000_000_000L
    private val mts = "mobile|AS8359"

    @Test
    fun quarantineHoldsThirtyMinutesOnThatNetworkOnly() {
        val items = AmeliaQuarantine.add(emptyList(), "Нидерланды 1", mts, t)
        assertEquals(setOf("Нидерланды 1"), AmeliaQuarantine.active(items, mts, t + 29 * 60_000))
        assertEquals(emptySet<String>(), AmeliaQuarantine.active(items, mts, t + AmeliaQuarantine.QUARANTINE_MS))
        // another network (Wi-Fi, another operator): not held there
        assertEquals(emptySet<String>(), AmeliaQuarantine.active(items, "wifi|AS12389", t + 60_000))
    }

    @Test
    fun manualChoiceLiftsQuarantine() {
        val items = AmeliaQuarantine.add(AmeliaQuarantine.add(emptyList(), "Нидерланды 1", mts, t), "Швеция 1", mts, t)
        val after = AmeliaQuarantine.release(items, "Нидерланды 1")
        assertEquals(setOf("Швеция 1"), AmeliaQuarantine.active(after, mts, t + 1))
    }

    @Test
    fun quarantinedOnlyWhenNothingElseIsLeft() {
        val known = mapOf("Нидерланды 1" to e("Нидерланды 1", 30), "Финляндия 2" to e("Финляндия 2", 400))
        val q = AmeliaQueue.order(locs, known, emptySet(), null, allowLastResort = false, quarantined = setOf("Нидерланды 1"))
        assertEquals("fi", q.first(), "the fast one was just left after a failure")
        assertEquals("nl", q.last(), "still there: tried when all others fail")
        // with only quarantined ones left, they are still offered
        val only = AmeliaQueue.order(locs.take(1), known, emptySet(), null, allowLastResort = false, quarantined = setOf("Нидерланды 1"))
        assertEquals(listOf("nl"), only)
    }

    @Test
    fun goodButQuarantinedWaits() {
        val q = AmeliaQueue.order(
            locs, emptyMap(), emptySet(), null, allowLastResort = false,
            good = mapOf("1.1.1.1:443" to 20, "2.2.2.2:443" to 3), quarantined = setOf("Нидерланды 1"),
        )
        assertEquals("fi", q.first())
    }

    // ---- 2. switch limit --------------------------------------------------------------------------

    @Test
    fun atMostFourSwitchesInThirtyMinutes() {
        val min = 60_000L
        val three = listOf(t - 25 * min, t - 10 * min, t - 1 * min)
        assertTrue(AmeliaSwitchLimit.allowed(three, t))
        val four = three + (t - 30_000)
        assertFalse(AmeliaSwitchLimit.allowed(four, t))
        // the oldest one leaves the window: allowed again
        assertTrue(AmeliaSwitchLimit.allowed(four, t + 5 * min + 1))
        assertEquals(5 * min, AmeliaSwitchLimit.RECHECK_MS)
        // counted from the switch log (seconds)
        val events = four.map { AmeliaEvents.Event(it / 1000, "a", "b", "нет ответа 2 раза") }
        assertFalse(AmeliaSwitchLimit.allowed(AmeliaSwitchLimit.times(events), t))
    }

    // ---- 2. "трафика нет" ---------------------------------------------------------------------------

    @Test
    fun noTrafficAloneDoesNotSwitch() {
        // one failed check through the tunnel (no traffic seen): not broken yet
        assertFalse(AmeliaPolicy.broken(manual = false, failures = 1, firstFailAt = t, now = t))
        // the confirming check failed too: two in a row
        assertTrue(AmeliaPolicy.broken(manual = false, failures = 2, firstFailAt = t, now = t))
        // the log says which reason was confirmed
        assertEquals("трафика нет, проверка через туннель не прошла 2 раза", AmeliaPolicy.switchReason(manual = false, noTraffic = true))
        assertEquals("нет ответа 2 раза", AmeliaPolicy.switchReason(manual = false, noTraffic = false))
    }

    // ---- 3. white lists ----------------------------------------------------------------------------

    @Test
    fun whitelistVerdicts() {
        assertEquals(Verdict.WHITELIST, AmeliaWhitelist.verdict(aOk = true, bOk = false))
        assertEquals(Verdict.OFFLINE, AmeliaWhitelist.verdict(aOk = false, bOk = false))
        assertEquals(Verdict.NORMAL, AmeliaWhitelist.verdict(aOk = true, bOk = true))
    }

    @Test
    fun whitelistExit() {
        assertTrue(AmeliaWhitelist.shouldExit(bOk = true, onWifi = false), "B opened")
        assertTrue(AmeliaWhitelist.shouldExit(bOk = false, onWifi = true), "Wi-Fi came on")
        assertFalse(AmeliaWhitelist.shouldExit(bOk = false, onWifi = false))
    }

    @Test
    fun whitelistCheckWhenMostFailOrTwoEmptyRounds() {
        assertTrue(AmeliaWhitelist.tooManyFailed(failed = 8, total = 10))
        assertFalse(AmeliaWhitelist.tooManyFailed(failed = 7, total = 10))
        assertFalse(AmeliaWhitelist.tooManyFailed(failed = 2, total = 2), "too few to judge")
        assertTrue(AmeliaWhitelist.shouldCheck(onMobile = true, tooManyFailed = true, emptyRounds = 0))
        assertTrue(AmeliaWhitelist.shouldCheck(onMobile = true, tooManyFailed = false, emptyRounds = 2))
        assertFalse(AmeliaWhitelist.shouldCheck(onMobile = true, tooManyFailed = false, emptyRounds = 1))
        assertFalse(AmeliaWhitelist.shouldCheck(onMobile = false, tooManyFailed = true, emptyRounds = 3), "only on mobile")
    }

    @Test
    fun whitelistGroups() {
        assertEquals(listOf("https://ya.ru", "https://www.gosuslugi.ru", "https://vk.com"), AmeliaWhitelist.GROUP_A)
        assertEquals(
            listOf("https://www.google.com/generate_204", "https://1.1.1.1/cdn-cgi/trace", "https://msk1.ameliajustamelia.com:9443"),
            AmeliaWhitelist.groupB("https://msk1.ameliajustamelia.com:9443"),
        )
        assertEquals(4_000L, AmeliaWhitelist.TIMEOUT_MS)
    }

    // ---- "wl" in /app/probe and /app/report ---------------------------------------------------------

    private fun probeEntry(wl: Boolean) = AmeliaProbe.Entry("1.1.1.1", 443, false, null, "connect", 1L, "mobile|MTS", wl = wl)

    @Test
    fun probeSaysWlOnlyInTheMode() {
        val info = AmeliaNetwork.Info("mobile", "MTS RUS")
        val on = JsonParser.parseString(
            AmeliaProbe.json(AmeliaProbe.body("iid", 1, info, "direct", listOf(probeEntry(false)), whitelist = true)),
        ).asJsonObject
        assertTrue(on.get("wl").asBoolean)
        // measured in the mode, sent after it ended: still marked
        val measured = JsonParser.parseString(AmeliaProbe.json(AmeliaProbe.body("iid", 1, info, "direct", listOf(probeEntry(true))))).asJsonObject
        assertTrue(measured.get("wl").asBoolean)
        val off = JsonParser.parseString(AmeliaProbe.json(AmeliaProbe.body("iid", 1, info, "direct", listOf(probeEntry(false))))).asJsonObject
        assertFalse(off.has("wl"), "no field at all outside the mode")
    }

    private val report = AmeliaReport.Body(
        key = "k", ts = 1, app = "1.5.9", code = 23, xray = "x", hy2 = "h", android = "16", device = "d", tv = false,
        net = "mobile", op = "MTS RUS", path = "direct", active = "Финляндия 2", bypassRu = false, comment = "",
        servers = emptyList(),
    )

    @Test
    fun reportSaysWlOnlyInTheMode() {
        assertTrue(JsonParser.parseString(AmeliaReport.toJson(report.copy(wl = true))).asJsonObject.get("wl").asBoolean)
        assertNull(JsonParser.parseString(AmeliaReport.toJson(report)).asJsonObject.get("wl"))
        assertTrue("белые списки" in AmeliaReport.plainText(report.copy(wl = true)))
    }
}
