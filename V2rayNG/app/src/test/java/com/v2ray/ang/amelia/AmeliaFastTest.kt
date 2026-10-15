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
        // the network's remembered best goes first, unless it was just seen failing
        assertEquals("fi", AmeliaQueue.order(locs, known, bad, preferred = "Финляндия 2").first())
        assertEquals("se", AmeliaQueue.order(locs, known, bad, preferred = "Нидерланды 1").first())
        // a hint says bad, but our own check says it works: we believe our check
        val ownOk = known + ("Польша 1" to e("Польша 1", 90))
        assertEquals("pl", AmeliaQueue.order(locs, ownOk, bad, preferred = null).first())
        // excluded (tried) ones are skipped
        assertEquals("fi", AmeliaQueue.order(locs, known, bad, null, exclude = setOf("se")).first())
    }

    @Test
    fun suggestOnlyWhenOneOrTwoPassNoDataAndTheRestWork() {
        val names = listOf("A", "B", "C", "D", "🆘 Резервный — только Telegram")
        val base = listOf(e("A", 100), e("B", 200), e("C", 150), e("D", -1, "data"), e("🆘 Резервный — только Telegram", -1))
        assertEquals(listOf("D"), AmeliaSuggest.candidates(base, names))
        // "does not connect" is not "data does not pass"
        assertEquals(emptyList<String>(), AmeliaSuggest.candidates(base.dropLast(2) + e("D", -1, "connect"), names))
        // three cut: something bigger than one server, the report button is for that
        assertEquals(
            emptyList<String>(),
            AmeliaSuggest.candidates(listOf(e("A", -1), e("B", -1), e("C", -1), e("D", 50)), names),
        )
        // results incomplete: wait for the whole check
        assertEquals(emptyList<String>(), AmeliaSuggest.candidates(base.take(3) + e("D", -1), names + "E"))
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
