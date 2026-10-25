package com.v2ray.ang.amelia

import com.v2ray.ang.R
import com.v2ray.ang.amelia.AmeliaSwitchLimit.Decision
import com.v2ray.ang.amelia.AmeliaWidget.Button
import com.v2ray.ang.amelia.AmeliaWidget.Status
import com.v2ray.ang.amelia.AmeliaWidget.Tap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** 1.6.0: the switch limit never leaves a person on a dead location; the widget in "белые списки". */
class Amelia160Test {

    private val now = 1_800_000_000_000L
    private val min = 60_000L
    private val NET = "mobile|AS8359"

    private fun e(name: String, ms: Long, ageMs: Long) =
        AmeliaChecks.Entry(name, "h", 1, ms, if (ms >= 0) "ok" else "data", now - ageMs, "mobile|AS8359")

    private val locs = listOf(
        AmeliaQueue.Location("nl", "Нидерланды 1", "1.1.1.1:443"),
        AmeliaQueue.Location("fi", "Финляндия 2", "2.2.2.2:443"),
        AmeliaQueue.Location("se", "Швеция 1", "3.3.3.3:443"),
        AmeliaQueue.Location("msk", "Москва 🇷🇺", "7.7.7.7:443"),
        AmeliaQueue.Location("tg", "Только Telegram", "6.6.6.6:443"),
    )
    private val names = locs.associate { it.guid to it.name }

    /** Four automatic switches in the last 30 minutes: the limit is used up. */
    private val fourSwitches = (1..4).map { AmeliaEvents.Event((now - it * min) / 1000, "a", "b", "нет ответа 2 раза · 3 с") }
    private val limitAllowed get() = AmeliaSwitchLimit.allowed(AmeliaSwitchLimit.times(fourSwitches), now)

    // ---- 1. switch limit ----------------------------------------------------------------------

    @Test
    fun deadCurrentLimitUsedUpAndAVerifiedOneMovesOverTheLimit() {
        assertFalse(limitAllowed)
        // Финляндия passed a check a minute ago
        val known = mapOf("Финляндия 2" to e("Финляндия 2", 120, 1 * min))
        val targets = AmeliaSwitchLimit.verifiedTargets(listOf("fi", "se"), { names[it] }, known, now, NET)
        assertEquals(listOf("fi"), targets)
        assertEquals(Decision.OVER_LIMIT, AmeliaSwitchLimit.decide(limitAllowed, failures = 2, haveVerified = targets.isNotEmpty()))
        // a location chosen by hand is judged broken after 3 failures: also dead
        assertEquals(Decision.OVER_LIMIT, AmeliaSwitchLimit.decide(limitAllowed, failures = 3, haveVerified = true))
    }

    @Test
    fun aliveCurrentWithLimitUsedUpStays() {
        // one failed check is not "surely dead"
        assertFalse(AmeliaSwitchLimit.currentDead(1))
        assertFalse(AmeliaSwitchLimit.currentDead(0))
        assertEquals(Decision.STAY, AmeliaSwitchLimit.decide(limitAllowed, failures = 1, haveVerified = true))
        assertEquals(Decision.STAY, AmeliaSwitchLimit.decide(limitAllowed, failures = 0, haveVerified = true))
    }

    @Test
    fun nothingWorkingStays() {
        assertEquals(Decision.STAY, AmeliaSwitchLimit.decide(limitAllowed, failures = 2, haveVerified = false))
        // a failed check or one older than 2 minutes does not count as working
        val known = mapOf(
            "Финляндия 2" to e("Финляндия 2", -1, 10_000),
            "Швеция 1" to e("Швеция 1", 90, 2 * min + 1),
        )
        assertTrue(AmeliaSwitchLimit.verifiedTargets(listOf("fi", "se", "nl"), { names[it] }, known, now, NET).isEmpty())
        assertEquals(5 * min, AmeliaSwitchLimit.RECHECK_MS)
    }

    @Test
    fun withinTheLimitTheUsualSwitch() {
        val three = fourSwitches.take(3)
        assertTrue(AmeliaSwitchLimit.allowed(AmeliaSwitchLimit.times(three), now))
        assertEquals(Decision.SWITCH, AmeliaSwitchLimit.decide(true, failures = 2, haveVerified = false))
    }

    @Test
    fun verifiedMeansPassedAtMostTwoMinutesAgo() {
        assertTrue(AmeliaSwitchLimit.verified(e("A", 50, 0), now, NET))
        assertTrue(AmeliaSwitchLimit.verified(e("A", 50, 2 * min), now, NET))
        assertFalse(AmeliaSwitchLimit.verified(e("A", 50, 2 * min + 1), now, NET))
        assertFalse(AmeliaSwitchLimit.verified(e("A", -1, 0), now, NET))
        assertFalse(AmeliaSwitchLimit.verified(null, now, NET))
        // a clock jump back does not make a check "fresh"
        assertFalse(AmeliaSwitchLimit.verified(e("A", 50, -5 * min), now, NET))
    }

    @Test
    fun verifiedTargetsKeepTheQueueOrder() {
        val known = mapOf(
            "Финляндия 2" to e("Финляндия 2", 300, 30_000),
            "Швеция 1" to e("Швеция 1", 40, 30_000),
        )
        // the queue (hints good first, then by ping) decides, not the filter
        assertEquals(listOf("fi", "se"), AmeliaSwitchLimit.verifiedTargets(listOf("fi", "nl", "se"), { names[it] }, known, now, NET))
    }

    @Test
    fun goodFromTheHintsIsCheckedNowExceptAlreadyVerifiedAndLastResort() {
        val good = mapOf("1.1.1.1:443" to 3, "3.3.3.3:443" to 9, "2.2.2.2:443" to 5, "7.7.7.7:443" to 20, "6.6.6.6:443" to 20)
        val known = mapOf("Финляндия 2" to e("Финляндия 2", 80, 30_000))
        val list = AmeliaSwitchLimit.goodToCheck(locs, good, known, exclude = setOf("nl"), now = now, net = NET)
        // most passed checks first; the current one, a verified one, Moscow and reserve are not checked
        assertEquals(listOf("se"), list)
        assertTrue(AmeliaSwitchLimit.goodToCheck(locs, emptyMap(), emptyMap(), emptySet(), now, NET).isEmpty())
    }

    @Test
    fun moveOverTheLimitIsMarkedAndNotCounted() {
        val why = AmeliaSwitchLimit.overLimitReason("нет ответа 2 раза")
        assertEquals("нет ответа 2 раза · сверх лимита: текущая не работает", why)
        assertTrue(AmeliaSwitchLimit.isOverLimit("$why · 4 с"))
        // three usual switches and any number over the limit: a fourth usual one is still allowed
        val events = fourSwitches.take(3) + (1..5).map { AmeliaEvents.Event((now - it * 1000) / 1000, "a", "b", "$why · 2 с") }
        assertEquals(3, AmeliaSwitchLimit.times(events).size)
        assertTrue(AmeliaSwitchLimit.allowed(AmeliaSwitchLimit.times(events), now))
    }

    // ---- 2. widget in "белые списки" ------------------------------------------------------------

    private val active = AmeliaSubInfo(expire = now / 1000 + 10 * 24 * 3600)
    private val ended = AmeliaSubInfo(expire = now / 1000 - 60)

    @Test
    fun whitelistPhaseIsNotConnected() {
        val s = AmeliaWidget.status(true, AmeliaDaemon.Phase.WHITELIST.name, 0L, now)
        assertEquals(Status.WHITELIST, s)
        assertNotEquals(Status.CONNECTED, s)
        // the VPN is off: no "белые списки" left over
        assertEquals(Status.OFF, AmeliaWidget.status(false, AmeliaDaemon.Phase.WHITELIST.name, 0L, now))
    }

    @Test
    fun whitelistWidgetSaysWhiteListsAndNeedWifi() {
        val m = AmeliaWidget.model(Status.WHITELIST, "Нидерланды 1", active, now) { "05.11" }
        val lines = AmeliaWidget.lines(m)
        assertEquals(R.string.amelia_widget_whitelist, lines.status)
        assertEquals(R.string.amelia_widget_need_wifi, lines.second)
        val strings = File(resDir(), "values/amelia_strings.xml").readText()
        assertTrue(strings.contains("<string name=\"amelia_widget_whitelist\">Белые списки</string>"))
        assertTrue(strings.contains("<string name=\"amelia_widget_need_wifi\">нужен Wi‑Fi</string>"))
    }

    @Test
    fun whitelistButtonOpensTheAppAndLeavesTheVpnAlone() {
        val m = AmeliaWidget.model(Status.WHITELIST, "A", active, now) { "x" }
        assertEquals(Button.OPEN_APP, m.button)
        // the VPN is on (busy), yet the tap does not turn it off
        assertEquals(Tap.OPEN_APP, AmeliaWidget.tap(expired = false, busy = true, vpnPermission = true, hasLocation = true, updateRequired = false, whitelist = true))
        // an ended subscription still shows "Продлить"
        assertEquals(Tap.RENEW, AmeliaWidget.tap(expired = true, busy = true, vpnPermission = true, hasLocation = true, updateRequired = false, whitelist = true))
        assertEquals(Button.RENEW, AmeliaWidget.model(Status.WHITELIST, "A", ended, now) { "x" }.button)
    }

    @Test
    fun leavingTheModeBringsBackTheUsualLook() {
        // the daemon reports the next phase (checking, then connected) when the mode ends
        val checking = AmeliaWidget.status(true, AmeliaDaemon.Phase.CHECKING.name, 0L, now)
        val connected = AmeliaWidget.status(true, AmeliaDaemon.Phase.CONNECTED.name, 0L, now)
        assertEquals(Status.CONNECTING, checking)
        assertEquals(Status.CONNECTED, connected)
        val m = AmeliaWidget.model(connected, "Нидерланды 1", active, now) { "x" }
        assertEquals(Button.DISCONNECT, m.button)
        val lines = AmeliaWidget.lines(m)
        assertEquals(R.string.amelia_phase_connected, lines.status)
        assertNull(lines.second, "the location's name again")
        assertEquals("Нидерланды 1", m.location)
        assertEquals(Tap.STOP, AmeliaWidget.tap(expired = false, busy = true, vpnPermission = true, hasLocation = true, updateRequired = false, whitelist = false))
    }

    private fun resDir(): File =
        listOf(File("src/main/res"), File("app/src/main/res"), File("V2rayNG/app/src/main/res")).first { it.isDirectory }
}
