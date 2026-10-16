package com.v2ray.ang.amelia

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import java.util.Collections

class AmeliaFastTest {

    private fun e(name: String, ms: Long, stage: String = if (ms >= 0) "ok" else "data") =
        AmeliaChecks.Entry(name, "h", 1, ms, stage, 1L, "mobile|MTS")

    private val locs = listOf(
        AmeliaQueue.Location("nl", "Нидерланды 1", "1.1.1.1:2079"),
        AmeliaQueue.Location("fi", "Финляндия 2", "2.2.2.2:443"),
        AmeliaQueue.Location("se", "Швеция 1", "3.3.3.3:443"),
        AmeliaQueue.Location("de", "Германия 1", "4.4.4.4:443"),
        AmeliaQueue.Location("res", "🆘 Резервный — только Telegram", "5.5.5.5:443"),
        AmeliaQueue.Location("pl", "Польша 1", "6.6.6.6:443"),
    )

    @Test
    fun queueOrder() {
        val known = mapOf(
            "Нидерланды 1" to e("Нидерланды 1", -1),
            "Финляндия 2" to e("Финляндия 2", 420),
            "Швеция 1" to e("Швеция 1", 180),
            "🆘 Резервный — только Telegram" to e("🆘 Резервный — только Telegram", 300),
        )
        // working by ping, then unchecked, then bad for the provider, then failed, reserve last
        val bad = setOf("6.6.6.6:443")
        assertEquals(
            listOf("se", "fi", "de", "pl", "nl", "res"),
            AmeliaQueue.order(locs, known, bad, preferred = null),
        )
        // 1.2.1: the remembered best never jumps ahead of a faster working location
        assertEquals("se", AmeliaQueue.order(locs, known, bad, preferred = "Финляндия 2").first())
        assertEquals("se", AmeliaQueue.order(locs, known, bad, preferred = "Нидерланды 1").first())
        // ...it only goes first among the unchecked ones
        assertEquals(listOf("se", "fi", "pl", "de"), AmeliaQueue.order(locs, known, emptySet(), preferred = "Польша 1").take(4))
        // a hint says bad, but our own check says it works: we believe our check
        val ownOk = known + ("Польша 1" to e("Польша 1", 90))
        assertEquals("pl", AmeliaQueue.order(locs, ownOk, bad, preferred = null).first())
        // excluded (tried) ones are skipped
        assertEquals("fi", AmeliaQueue.order(locs, known, bad, null, exclude = setOf("se")).first())
    }

    @Test
    fun hintsParseAndNetworkKey() {
        val h = AmeliaHints.parse(
            """{"asn": 8359, "as": "MTS PJSC", "cc": "RU", "bad": [{"host": "79.137.192.178", "port": 2079, "bad": 3, "of": 4, "stage": "data"}], "ts": 1700000000}""",
        )!!
        assertEquals(8359L, h.asn)
        assertEquals("MTS PJSC", h.asName)
        assertEquals(setOf("79.137.192.178:2079"), AmeliaHints.badHostPorts(h))
        assertEquals("mobile|AS8359", AmeliaHints.key("mobile", "mobile|MTS RUS", 8359))
        assertEquals("mobile|MTS RUS", AmeliaHints.key("mobile", "mobile|MTS RUS", null))
        assertNull(AmeliaHints.parse("<html>"))
        assertTrue(AmeliaHints.badHostPorts(null).isEmpty())
    }

    @Test
    fun eventLogKeepsTheLast20() {
        var log = emptyList<AmeliaEvents.Event>()
        repeat(25) { log = AmeliaEvents.append(log, AmeliaEvents.Event(it.toLong(), "a", "b", "нет ответа 2 раза")) }
        assertEquals(20, log.size)
        assertEquals(5L, log.first().ts)
        assertEquals(24L, log.last().ts)
    }

    @Test
    fun noTrafficAtAll() {
        val now = 100_000L
        // requests go out, nothing came back for 25 s, counters are being read: one failure is enough
        assertTrue(AmeliaDaemon.noTraffic(now, lastUp = now - 1_000, lastDown = now - 25_000, lastSample = now - 2_000))
        // data came recently
        assertFalse(AmeliaDaemon.noTraffic(now, now - 1_000, now - 5_000, now - 2_000))
        // screen off: the counters are not read, nothing can be said
        assertFalse(AmeliaDaemon.noTraffic(now, now - 1_000, now - 60_000, now - 30_000))
    }

    /**
     * 12 locations, [AmeliaCheck.PARALLEL] at a time, times scaled down 10×: 9 answer in
     * 0.3–1.2 s, 3 hit the 5 s limit (one of them a hysteria2 one). The whole check must stay
     * under 10 s, and the first result must arrive long before the end.
     */
    @Test
    fun twelveLocationsWithinTenSeconds() = runBlocking {
        val scale = 10
        val times = listOf(300L, 5_000L, 450L, 800L, 1_200L, 5_000L, 600L, 350L, 900L, 5_000L, 700L, 400L)
        val arrived = Collections.synchronizedList(mutableListOf<Pair<String, Long>>())
        val context = mock<Context>()
        val started = System.currentTimeMillis()
        val total = AmeliaCheck.checkAll(context, times.indices.map { "g$it" }, check = { guid ->
            val t = times[guid.removePrefix("g").toInt()]
            Thread.sleep(t / scale)
            val ok = t < 5_000
            AmeliaCheck.Result(guid, guid, "h", 1, if (ok) t else -1, if (ok) "ok" else "data", null)
        }) { r -> arrived += r.guid to (System.currentTimeMillis() - started) }
        assertEquals(12, arrived.size)
        assertTrue(total * scale < 10_000, "took ${total * scale} ms (scaled)")
        assertTrue(arrived.first().second * scale < 1_000, "first result after ${arrived.first().second * scale} ms (scaled)")
    }
}

class AmeliaQueueRussiaTest {

    private fun e(name: String, ms: Long) =
        AmeliaChecks.Entry(name, "h", 1, ms, if (ms >= 0) "ok" else "data", 1L, "mobile|MTS")

    private val locs = listOf(
        AmeliaQueue.Location("msk", "Москва 🇷🇺", "10.0.0.1:443"),
        AmeliaQueue.Location("se1", "Швеция 1", "1.1.1.1:443"),
        AmeliaQueue.Location("fi1", "Финляндия 1", "2.2.2.2:443"),
        AmeliaQueue.Location("fi2", "Финляндия 2", "3.3.3.3:443"),
        AmeliaQueue.Location("nl", "Нидерланды 1", "4.4.4.4:443"),
        AmeliaQueue.Location("res", "🆘 Резервный — только Telegram", "5.5.5.5:443"),
    )

    /** The live case: Швеция 1 failed, Москва (546 ms) was taken over Финляндия 1/2 (59/60 ms). */
    @Test
    fun fastestForeignFirstRussianExitLast() {
        val known = mapOf(
            "Москва 🇷🇺" to e("Москва 🇷🇺", 546),
            "Швеция 1" to e("Швеция 1", -1),
            "Финляндия 1" to e("Финляндия 1", 59),
            "Финляндия 2" to e("Финляндия 2", 60),
            "🆘 Резервный — только Telegram" to e("🆘 Резервный — только Telegram", 200),
        )
        val q = AmeliaQueue.order(locs, known, emptySet(), preferred = "Москва 🇷🇺", exclude = setOf("se1"))
        assertEquals(listOf("fi1", "fi2", "nl", "msk", "res"), q)
        // even a broken-down foreign list keeps Russia and reserve at the end
        val none = mapOf("Москва 🇷🇺" to e("Москва 🇷🇺", 546))
        assertEquals(listOf("msk", "res"), AmeliaQueue.order(locs, none, emptySet(), null).takeLast(2))
        assertTrue(AmeliaReserve.isRussianExit("Москва 🇷🇺"))
        assertTrue(AmeliaReserve.isRussianExit("москва-2"))
        assertTrue(AmeliaReserve.isRussianExit("RU 🇷🇺 exit"))
        assertFalse(AmeliaReserve.isRussianExit("Финляндия 1 🇫🇮"))
        assertEquals("fi1", AmeliaReserve.firstChoice(listOf(
            AmeliaManager.Location("msk", "Москва 🇷🇺"), AmeliaManager.Location("fi1", "Финляндия 1"),
        ))?.guid)
    }

    @Test
    fun quickPickChecksTheBestFewAndTakesTheFastest() = runBlocking {
        val last = mapOf(
            "Финляндия 1" to e("Финляндия 1", 59),
            "Финляндия 2" to e("Финляндия 2", 60),
            "Нидерланды 1" to e("Нидерланды 1", 120),
            "Швеция 1" to e("Швеция 1", -1),
            "Москва 🇷🇺" to e("Москва 🇷🇺", 30),
        )
        // foreign only, best past ping first, the failed one excluded
        val c = AmeliaQuickPick.candidates(locs, last, setOf("se1"))
        assertEquals(listOf("fi1", "fi2", "nl"), c)
        // fi1 is slow now, fi2 fast, nl does not answer within the deadline
        val delays = mapOf("fi1" to 250L, "fi2" to 40L, "nl" to 2_000L)
        val stored = Collections.synchronizedList(mutableListOf<String>())
        val answered = AmeliaQuickPick.quickest(c, deadlineMs = 500, check = { g ->
            Thread.sleep(delays.getValue(g))
            AmeliaCheck.Result(g, g, "h", 1, delays.getValue(g), "ok", null)
        }) { stored += it.guid }
        assertEquals(listOf("fi2", "fi1"), answered.map { it.guid })
        Thread.sleep(1_700)
        // the late one still reaches the store
        assertTrue("nl" in stored)
    }

    @Test
    fun timeoutTextInReports() {
        assertEquals("нет ответа за 5 с", AmeliaReport.NO_RESULT_ERROR)
        // hysteria2 (no TCP check) that timed out did not connect; TCP that connected: data cut
        assertEquals("connect", AmeliaPingStage.classify(-1, null, AmeliaCheck.TIMEOUT_ERROR))
        assertEquals("data", AmeliaPingStage.classify(-1, true, AmeliaCheck.TIMEOUT_ERROR))
    }
}
