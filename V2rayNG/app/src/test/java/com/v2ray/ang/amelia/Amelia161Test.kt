package com.v2ray.ang.amelia

import com.v2ray.ang.amelia.AmeliaHints.Mark
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** 1.6.1: marks "у вашего оператора" in the list; the subscription again when nothing works. */
class Amelia161Test {

    private val now = 1_800_000_000_000L
    private val min = 60_000L
    private val net = "mobile|AS8359"

    private val hints = AmeliaHints.Hints(
        asn = 8359,
        asName = "MTS PJSC",
        net = "mobile",
        bad = listOf(AmeliaHints.Bad("2.2.2.2", 443, bad = 9, of = 10)),
        good = listOf(AmeliaHints.Good("1.1.1.1", 443, ok = 8, of = 10), AmeliaHints.Good("5.5.5.5", 443, ok = 0, of = 10)),
    )

    private fun own(ok: Boolean, ageMs: Long, onNet: String = net) =
        AmeliaChecks.Entry("x", "h", 1, if (ok) 120 else -1, if (ok) "ok" else "data", now - ageMs, onNet)

    // ---- marks --------------------------------------------------------------------------------

    @Test
    fun goodGetsTheGreenMarkAndBadTheYellowOne() {
        assertEquals(Mark.GOOD, AmeliaHints.mark("1.1.1.1:443", hints, null, net, now))
        assertEquals(Mark.BAD, AmeliaHints.mark("2.2.2.2:443", hints, null, net, now))
        // in neither list, or "good" without a single passed check: no mark
        assertNull(AmeliaHints.mark("3.3.3.3:443", hints, null, net, now))
        assertNull(AmeliaHints.mark("5.5.5.5:443", hints, null, net, now))
    }

    @Test
    fun ownFreshCheckOutweighsTheHintBothWays() {
        // our check here says it fails: no "✓"
        assertNull(AmeliaHints.mark("1.1.1.1:443", hints, own(ok = false, ageMs = 5 * min), net, now))
        // our check here says it works: no "⚠"
        assertNull(AmeliaHints.mark("2.2.2.2:443", hints, own(ok = true, ageMs = 5 * min), net, now))
        // agreeing checks keep the mark
        assertEquals(Mark.GOOD, AmeliaHints.mark("1.1.1.1:443", hints, own(ok = true, ageMs = 5 * min), net, now))
        assertEquals(Mark.BAD, AmeliaHints.mark("2.2.2.2:443", hints, own(ok = false, ageMs = 5 * min), net, now))
    }

    @Test
    fun ownCheckOlderThanTenMinutesOrFromAnotherNetworkDoesNotCount() {
        assertEquals(Mark.GOOD, AmeliaHints.mark("1.1.1.1:443", hints, own(ok = false, ageMs = 11 * min), net, now))
        assertEquals(Mark.BAD, AmeliaHints.mark("2.2.2.2:443", hints, own(ok = true, ageMs = 11 * min), net, now))
        assertEquals(Mark.GOOD, AmeliaHints.mark("1.1.1.1:443", hints, own(ok = false, ageMs = 1 * min, onNet = "wifi|AS12389"), net, now))
        // exactly 10 minutes still counts
        assertNull(AmeliaHints.mark("1.1.1.1:443", hints, own(ok = false, ageMs = 10 * min), net, now))
    }

    @Test
    fun noHintsNoMarks() {
        assertNull(AmeliaHints.mark("1.1.1.1:443", null, null, net, now))
        // asn 0: the server could not tell the provider
        assertNull(AmeliaHints.mark("1.1.1.1:443", hints.copy(asn = 0), null, net, now))
        assertNull(AmeliaHints.mark("2.2.2.2:443", hints.copy(asn = null), null, net, now))
        assertFalse(AmeliaHints.usable(hints.copy(asn = 0)))
        assertTrue(AmeliaHints.usable(hints))
    }

    @Test
    fun operatorNameWithoutTechnicalTails() {
        assertEquals("MTS", AmeliaHints.operatorName("MTS PJSC"))
        assertEquals("MTS", AmeliaHints.operatorName("MTS-AS"))
        assertEquals("MegaFon", AmeliaHints.operatorName("PJSC MegaFon"))
        assertEquals("Megafon", AmeliaHints.operatorName("MEGAFON-AS"))
        assertEquals("Rostelecom", AmeliaHints.operatorName("AS12389 Rostelecom"))
        assertEquals("T2 Mobile", AmeliaHints.operatorName("T2 Mobile LLC"))
        assertEquals("Vimpelcom", AmeliaHints.operatorName("\"VIMPELCOM\", PJSC"))
        assertEquals("МТС", AmeliaHints.operatorName("ПАО «МТС»"))
        // nothing readable left: the line says "вашего оператора"
        assertNull(AmeliaHints.operatorName("AS-PJSC"))
        assertNull(AmeliaHints.operatorName("AS8359"))
        assertNull(AmeliaHints.operatorName(""))
        assertNull(AmeliaHints.operatorName(null))
    }

    @Test
    fun markTextsInTheResources() {
        val strings = File(resDir(), "values/amelia_strings.xml").readText()
        assertTrue(strings.contains("<string name=\"amelia_operator_good\">✓ работает у вашего оператора</string>"))
        assertTrue(strings.contains("<string name=\"amelia_operator_bad\">⚠ у вашего оператора режут</string>"))
        assertTrue(strings.contains("<string name=\"amelia_operator_note\">Пометки — по проверкам других абонентов %1\$s, %2\$s</string>"))
        assertTrue(strings.contains("<string name=\"amelia_operator_yours\">вашего оператора</string>"))
    }

    // ---- the subscription again -----------------------------------------------------------------

    @Test
    fun allDeadAndSubscriptionOlderThanTenMinutesDownloadsAgain() {
        assertTrue(AmeliaResubscribe.due(lastUpdate = now - 11 * min, lastTry = null, now = now))
        assertTrue(AmeliaResubscribe.due(lastUpdate = now - 3 * 60 * min, lastTry = now - 11 * min, now = now))
    }

    @Test
    fun freshSubscriptionIsNotDownloadedAgain() {
        assertFalse(AmeliaResubscribe.due(lastUpdate = now - 9 * min, lastTry = null, now = now))
        assertFalse(AmeliaResubscribe.due(lastUpdate = now - 10 * min, lastTry = null, now = now))
    }

    @Test
    fun atMostOnceInTenMinutesPerNetwork() {
        val old = now - 60 * min
        assertFalse(AmeliaResubscribe.due(old, lastTry = now - 9 * min, now = now))
        assertFalse(AmeliaResubscribe.due(old, lastTry = now, now = now))
        assertTrue(AmeliaResubscribe.due(old, lastTry = now - 10 * min, now = now))
        // tries are kept per network: another network has its own time
        val tries = AmeliaResubscribe.record(emptyMap(), "mobile|AS8359", now)
        assertEquals(now, tries["mobile|AS8359"])
        assertNull(tries["wifi|AS12389"])
        assertTrue(AmeliaResubscribe.due(old, tries["wifi|AS12389"], now))
        // the map stays small
        val many = (1..30).fold(emptyMap<String, Long>()) { m, i -> AmeliaResubscribe.record(m, "net$i", now + i) }
        assertEquals(20, many.size)
        assertTrue("net30" in many && "net1" !in many)
    }

    @Test
    fun changedPortIsFoundAndUnchangedListSaysSo() {
        val before = mapOf("Нидерланды 1" to "1.1.1.1:443", "Финляндия 2" to "2.2.2.2:443", "Швеция 1" to "3.3.3.3:443")
        val same = before.map { it.key to it.value }
        assertEquals(emptyList<String>(), AmeliaResubscribe.changed(before, same))
        assertEquals("подписка без изменений", AmeliaResubscribe.logLine(0))

        val moved = listOf("Нидерланды 1" to "1.1.1.1:8443", "Финляндия 2" to "2.2.2.2:443", "Швеция 1" to "3.3.3.9:443", "Польша 1" to "4.4.4.4:443")
        assertEquals(listOf("Нидерланды 1", "Швеция 1", "Польша 1"), AmeliaResubscribe.changed(before, moved))
        assertEquals("подписка обновлена: изменилось 3 локации", AmeliaResubscribe.logLine(3))
        assertEquals("подписка обновлена: изменилась 1 локация", AmeliaResubscribe.logLine(1))
        assertEquals("подписка обновлена: изменилось 5 локаций", AmeliaResubscribe.logLine(5))
        assertEquals("подписка обновлена: изменилось 11 локаций", AmeliaResubscribe.logLine(11))
        assertEquals("подписка обновлена: изменилась 21 локация", AmeliaResubscribe.logLine(21))
    }

    @Test
    fun movedLocationLosesItsQuarantineIsCheckedAndChosen() {
        // Нидерланды was left after a failure: in quarantine on this network
        var q = AmeliaQuarantine.add(emptyList(), "Нидерланды 1", net, now - 5 * min)
        assertEquals(setOf("Нидерланды 1"), AmeliaQuarantine.active(q, net, now))
        // the server moved it to 8443: quarantine dropped for it
        q = AmeliaQuarantine.release(q, "Нидерланды 1")
        assertTrue(AmeliaQuarantine.active(q, net, now).isEmpty())
        // after the download every location has a new guid; the moved one answered the check just now,
        // the others failed: it goes first in the usual queue and is a verified target over the limit
        val locs = listOf(
            AmeliaQueue.Location("n2", "Нидерланды 1", "1.1.1.1:8443"),
            AmeliaQueue.Location("f2", "Финляндия 2", "2.2.2.2:443"),
        )
        val known = mapOf(
            "Нидерланды 1" to AmeliaChecks.Entry("Нидерланды 1", "1.1.1.1", 8443, 90, "ok", now - 2_000, net),
            "Финляндия 2" to AmeliaChecks.Entry("Финляндия 2", "2.2.2.2", 443, -1, "data", now - 30_000, net),
        )
        val queue = AmeliaQueue.order(locs, known, bad = emptySet(), preferred = null, quarantined = AmeliaQuarantine.active(q, net, now))
        assertEquals("n2", queue.first())
        assertEquals(listOf("n2"), AmeliaSwitchLimit.verifiedTargets(queue, { g -> locs.first { it.guid == g }.name }, known, now))
    }

    @Test
    fun subscriptionLinesAreNotSwitchesForTheLimit() {
        val events = (1..4).map { AmeliaEvents.Event((now - it * min) / 1000, "", "", AmeliaResubscribe.logLine(it % 2)) } +
            (1..3).map { AmeliaEvents.Event((now - it * min) / 1000, "a", "b", "нет ответа 2 раза · 3 с") }
        assertEquals(3, AmeliaSwitchLimit.times(events).size)
        assertTrue(AmeliaSwitchLimit.allowed(AmeliaSwitchLimit.times(events), now))
    }

    @Test
    fun resubscribeUsesMsk2ThenMsk1AroundTheTunnel() {
        assertEquals(
            listOf("https://msk2.ameliajustamelia.com:9443", "https://msk1.ameliajustamelia.com:9443"),
            AmeliaConfig.RESUBSCRIBE_BASE_URLS,
        )
        val plan = AmeliaHttp.plan(AmeliaConfig.RESUBSCRIBE_BASE_URLS, tunnel = false)
        assertTrue(plan.all { it.route == AmeliaHttp.Route.DIRECT })
        assertEquals(10_000L, AmeliaResubscribe.TIMEOUT_MS)
        // not downloaded: the caller goes on as before (fetchWith reports the failure)
        val r = AmeliaSubscriptionClient.fetchWith(plan) { AmeliaSubscriptionClient.Attempt.Failed }
        assertEquals(AmeliaSubscriptionClient.Result.NetworkError, r)
    }

    private fun resDir(): File =
        listOf(File("src/main/res"), File("app/src/main/res"), File("V2rayNG/app/src/main/res")).first { it.isDirectory }
}
