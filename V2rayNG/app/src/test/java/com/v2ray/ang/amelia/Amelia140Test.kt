package com.v2ray.ang.amelia

import com.v2ray.ang.amelia.AmeliaPolicy.Mode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Amelia140Test {

    private val now = 1_800_000_000_000L
    private fun info(expireSec: Long?) = AmeliaSubInfo(expire = expireSec)

    // ---- subscription and last-resort locations ----------------------------------------------

    @Test
    fun subscriptionActiveRules() {
        assertTrue(AmeliaPolicy.subscriptionActive(info(0), now), "expire=0: no end date")
        assertTrue(AmeliaPolicy.subscriptionActive(info(null), now))
        assertTrue(AmeliaPolicy.subscriptionActive(null, now), "unknown: never send to «только Telegram»")
        assertTrue(AmeliaPolicy.subscriptionActive(info(now / 1000 + 3600), now))
        assertFalse(AmeliaPolicy.subscriptionActive(info(now / 1000 - 1), now))
        assertFalse(AmeliaPolicy.lastResortAllowed(info(0), now))
        assertTrue(AmeliaPolicy.lastResortAllowed(info(now / 1000 - 86400), now))
    }

    private fun e(name: String, ms: Long) =
        AmeliaChecks.Entry(name, "h", 1, ms, if (ms >= 0) "ok" else "data", 1L, "wifi|")

    private val locs = listOf(
        AmeliaQueue.Location("res", "🆘 Резервный — только Telegram", "1.1.1.1:443"),
        AmeliaQueue.Location("res2", "🆘 Резервный 2 — только Telegram", "1.1.1.2:443"),
        AmeliaQueue.Location("msk", "Москва 🇷🇺", "2.2.2.2:443"),
        AmeliaQueue.Location("nl", "Нидерланды 1 🇳🇱", "3.3.3.3:2079"),
        AmeliaQueue.Location("fi", "Финляндия 2 🇫🇮", "4.4.4.4:443"),
        AmeliaQueue.Location("se", "Запасной 🇸🇪", "5.5.5.5:443"),
    )

    @Test
    fun queueWhileSubscriptionValidNeverOffersReserveOrMoscow() {
        // network just came back: nothing foreign answered yet, only Telegram through the reserve
        val known = mapOf(
            "🆘 Резервный — только Telegram" to e("🆘 Резервный — только Telegram", 120),
            "Москва 🇷🇺" to e("Москва 🇷🇺", 40),
            "Нидерланды 1 🇳🇱" to e("Нидерланды 1 🇳🇱", -1),
        )
        val q = AmeliaQueue.order(locs, known, emptySet(), null, allowLastResort = false)
        assertEquals(listOf("fi", "se", "nl"), q)
        assertTrue(q.none { it in setOf("res", "res2", "msk") })
    }

    @Test
    fun queueAfterTheSubscriptionEnded() {
        val known = mapOf(
            "Нидерланды 1 🇳🇱" to e("Нидерланды 1 🇳🇱", -1),
            "Финляндия 2 🇫🇮" to e("Финляндия 2 🇫🇮", -1),
            "🆘 Резервный 2 — только Telegram" to e("🆘 Резервный 2 — только Telegram", 90),
        )
        val q = AmeliaQueue.order(locs, known, emptySet(), null, allowLastResort = true)
        // foreign first (they may work after renewal), then Moscow, then reserve, working first
        assertEquals(listOf("se", "nl", "fi", "msk", "res2", "res"), q)
    }

    @Test
    fun queueByPingHintsAndUnchecked() {
        val known = mapOf(
            "Нидерланды 1 🇳🇱" to e("Нидерланды 1 🇳🇱", 210),
            "Финляндия 2 🇫🇮" to e("Финляндия 2 🇫🇮", 60),
        )
        // Запасной is unchecked: after the working ones; a hint does not beat our own check
        assertEquals(listOf("fi", "nl", "se"), AmeliaQueue.order(locs, known, setOf("4.4.4.4:443"), null, allowLastResort = false))
    }

    @Test
    fun zapasnoyIsARegularLocation() {
        assertFalse(AmeliaReserve.isReserve("Запасной 🇸🇪"))
        assertFalse(AmeliaReserve.isRussianExit("Запасной 🇸🇪"))
        assertFalse(AmeliaPolicy.isLastResort("Запасной 🇸🇪"))
        assertTrue(AmeliaPolicy.isLastResort("🆘 Резервный 2 — только Telegram"))
        assertTrue(AmeliaPolicy.isLastResort("Москва 🇷🇺"))
    }

    @Test
    fun leaveReserveOrMoscowOnlyWhenNotChosenByHandAndSubscriptionValid() {
        assertTrue(AmeliaPolicy.shouldLeaveLastResort("🆘 Резервный 2 — только Telegram", manual = false, subActive = true))
        assertTrue(AmeliaPolicy.shouldLeaveLastResort("Москва 🇷🇺", manual = false, subActive = true))
        assertFalse(AmeliaPolicy.shouldLeaveLastResort("Москва 🇷🇺", manual = true, subActive = true))
        assertFalse(AmeliaPolicy.shouldLeaveLastResort("🆘 Резервный — только Telegram", manual = false, subActive = false))
        assertFalse(AmeliaPolicy.shouldLeaveLastResort("Финляндия 2 🇫🇮", manual = false, subActive = true))
    }

    // ---- manual choice and failures ----------------------------------------------------------

    @Test
    fun manualChoiceNeedsThreeFailuresOverThirtySeconds() {
        val t = 1_000_000L
        assertFalse(AmeliaPolicy.broken(manual = true, failures = 2, firstFailAt = t, now = t + 40_000, noTraffic = true))
        assertFalse(AmeliaPolicy.broken(manual = true, failures = 3, firstFailAt = t, now = t + 20_000, noTraffic = false))
        assertTrue(AmeliaPolicy.broken(manual = true, failures = 3, firstFailAt = t, now = t + 30_000, noTraffic = false))
        // automatic choice: two in a row, or one when no data came back at all
        assertTrue(AmeliaPolicy.broken(manual = false, failures = 2, firstFailAt = t, now = t, noTraffic = false))
        assertTrue(AmeliaPolicy.broken(manual = false, failures = 1, firstFailAt = t, now = t, noTraffic = true))
        assertFalse(AmeliaPolicy.broken(manual = false, failures = 1, firstFailAt = t, now = t, noTraffic = false))
    }

    @Test
    fun autoSwitchModes() {
        assertFalse(AmeliaPolicy.maySwitch(Mode.OFF, manual = false, respectUntil = 0, now = now))
        assertTrue(AmeliaPolicy.maySwitch(Mode.UNLESS_MANUAL, manual = false, respectUntil = 0, now = now))
        assertFalse(AmeliaPolicy.maySwitch(Mode.UNLESS_MANUAL, manual = true, respectUntil = 0, now = now))
        assertTrue(AmeliaPolicy.maySwitch(Mode.ALWAYS, manual = true, respectUntil = 0, now = now))
        // came back by hand: left alone for 10 minutes even in "Всегда"
        assertFalse(AmeliaPolicy.maySwitch(Mode.ALWAYS, manual = true, respectUntil = now + 1, now = now))
        assertEquals(Mode.ALWAYS, AmeliaPolicy.DEFAULT_MODE) // 1.5.6; was UNLESS_MANUAL
    }

    @Test
    fun returningToTheLeftLocationIsRespectedForTenMinutes() {
        var s = AmeliaManual.chosen(AmeliaManual.State(), "Нидерланды 1 🇳🇱", now)
        assertEquals("Нидерланды 1 🇳🇱", s.name)
        assertEquals(0L, s.respectUntil)
        s = AmeliaManual.left(s, "Нидерланды 1 🇳🇱", now + 60_000)
        assertNull(s.name)
        assertEquals("Нидерланды 1 🇳🇱", s.leftName)
        val back = AmeliaManual.chosen(s, "Нидерланды 1 🇳🇱", now + 120_000)
        assertEquals(now + 120_000 + AmeliaPolicy.RESPECT_RETURN_MS, back.respectUntil)
        // another location, or too late: an ordinary manual choice
        assertEquals(0L, AmeliaManual.chosen(s, "Финляндия 2 🇫🇮", now + 120_000).respectUntil)
        assertEquals(0L, AmeliaManual.chosen(s, "Нидерланды 1 🇳🇱", now + 60_000 + AmeliaPolicy.RETURN_WINDOW_MS + 1).respectUntil)
        // an automatic location left: no "вернуться", the manual mark is simply gone
        val auto = AmeliaManual.left(AmeliaManual.State(name = "X"), "Y", now)
        assertNull(auto.name)
        assertNull(auto.leftName)
    }

    // ---- network loss and return -------------------------------------------------------------

    @Test
    fun networkLossAndReturn() {
        // Android not sure, but ya.ru answers: online (vendor builds without validation)
        assertFalse(AmeliaPolicy.isOffline(validated = false, directOk = true))
        assertFalse(AmeliaPolicy.isOffline(validated = true, directOk = false))
        assertTrue(AmeliaPolicy.isOffline(validated = false, directOk = false))
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 300_000L, 300_000L), (0..4).map { AmeliaPolicy.offlineWait(it) })
        assertEquals(5_000L, AmeliaDaemon.NETWORK_SETTLE_MS)
        assertEquals(3_000L, AmeliaDaemon.SETTLE_MS)
        assertEquals(6_000L, AmeliaDaemon.PROBE_TIMEOUT_MS)
        assertEquals(2, AmeliaDaemon.PROBE_URLS.size)
    }

    // ---- journal, subscription headers, announce ---------------------------------------------

    @Test
    fun journal() {
        var log = emptyList<AmeliaJournal.Entry>()
        repeat(25) { log = AmeliaJournal.append(log, AmeliaJournal.Entry(it.toLong(), "событие $it")) }
        assertEquals(20, log.size)
        // the same line twice in a row is kept once, with the newer time
        log = AmeliaJournal.append(log, AmeliaJournal.Entry(100, "Нет интернета"))
        log = AmeliaJournal.append(log, AmeliaJournal.Entry(200, "Нет интернета"))
        assertEquals(200L, log.last().ts)
        assertEquals(1, log.count { it.text == "Нет интернета" })
    }

    @Test
    fun subscriptionUserinfo() {
        assertEquals(
            mapOf("upload" to 10L, "download" to 2048L, "total" to 0L, "expire" to 1_794_495_600L),
            AmeliaSubInfo.parseUserinfo(" upload=10; download=2048 ;total=0; expire=1794495600"),
        )
        // floats from some panels, junk parts ignored
        assertEquals(mapOf("upload" to 1L, "expire" to 0L), AmeliaSubInfo.parseUserinfo("upload=1.0; bad; expire=0; x=abc"))
        assertTrue(AmeliaSubInfo.parseUserinfo(null).isEmpty())
        val i = AmeliaSubInfo.fromHeaders(
            header = { mapOf("Subscription-Userinfo" to "upload=1; download=2; total=0; expire=0")[it] },
            now = 5, decodeBase64 = { null },
        )
        assertFalse(i.hasExpire)
        assertFalse(i.hasLimit)
        assertNull(i.remaining)
    }

    @Test
    fun announceHeader() {
        val decode = { s: String -> if (s == "0J/RgNC40LLQtdGC") "Привет" else null }
        assertEquals("Привет", AmeliaSubInfo.decodeHeaderValue("base64:0J/RgNC40LLQtdGC", decode))
        assertEquals("Plain text", AmeliaSubInfo.decodeHeaderValue("  Plain text ", decode))
        assertNull(AmeliaSubInfo.decodeHeaderValue("base64:!!!", decode))
        assertNull(AmeliaSubInfo.decodeHeaderValue("   ", decode))
    }

    // ---- update APK checks -------------------------------------------------------------------

    @Test
    fun downloadedApkMustBeOursAndTheRightVersion() {
        val ours = "com.ameliajustamelia.vpn"
        assertTrue(AmeliaApkCheck.sameSigner(setOf("a", "b"), setOf("b")))
        assertFalse(AmeliaApkCheck.sameSigner(setOf("a"), setOf("c")))
        assertFalse(AmeliaApkCheck.sameSigner(setOf("a"), emptySet()))
        val code = com.v2ray.ang.BuildConfig.VERSION_CODE + 1
        assertEquals(AmeliaApkCheck.Verdict.OK, AmeliaApkCheck.judge(ours, code.toLong(), "x", true, code, ours).verdict)
        assertEquals(AmeliaApkCheck.Verdict.BAD_SIGNATURE, AmeliaApkCheck.judge(ours, code.toLong(), "x", false, code, ours).verdict)
        assertEquals(AmeliaApkCheck.Verdict.BAD_SIGNATURE, AmeliaApkCheck.judge("evil.app", code.toLong(), "x", true, code, ours).verdict)
        assertEquals(AmeliaApkCheck.Verdict.BAD_VERSION, AmeliaApkCheck.judge(ours, (code + 1).toLong(), "x", true, code, ours).verdict)
        assertEquals(
            AmeliaApkCheck.Verdict.BAD_VERSION,
            AmeliaApkCheck.judge(ours, com.v2ray.ang.BuildConfig.VERSION_CODE.toLong(), "x", true, 0, ours).verdict,
        )
    }
}
