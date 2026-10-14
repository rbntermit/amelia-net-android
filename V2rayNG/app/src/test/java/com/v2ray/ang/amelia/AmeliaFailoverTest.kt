package com.v2ray.ang.amelia

import com.v2ray.ang.amelia.AmeliaFailover.Verdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AmeliaFailoverTest {

    @Test
    fun verdict() {
        assertEquals(Verdict.CONNECTED, AmeliaFailover.verdict(tunnelDelayMs = 120, directOk = false))
        assertEquals(Verdict.CONNECTED, AmeliaFailover.verdict(tunnelDelayMs = 0, directOk = true))
        // Google through the tunnel fails, Yandex directly works: the location is broken
        assertEquals(Verdict.SWITCH, AmeliaFailover.verdict(tunnelDelayMs = -1, directOk = true))
        // Both fail: the phone is offline, switching would not help
        assertEquals(Verdict.NO_INTERNET, AmeliaFailover.verdict(tunnelDelayMs = -1, directOk = false))
    }

    @Test
    fun nextGoesForwardAndWraps() {
        val order = listOf("a", "b", "c", "d")
        assertEquals("c", AmeliaFailover.next(order, "b", setOf("b")))
        assertEquals("a", AmeliaFailover.next(order, "d", setOf("d")))
        // skips already tried ones
        assertEquals("d", AmeliaFailover.next(order, "b", setOf("b", "c")))
        // current not in list (subscription was refreshed): start from the top
        assertEquals("a", AmeliaFailover.next(order, "zz", emptySet()))
    }

    @Test
    fun nextStopsWhenAllTried() {
        val order = listOf("a", "b", "c")
        assertNull(AmeliaFailover.next(order, "b", setOf("a", "b", "c")))
        assertNull(AmeliaFailover.next(emptyList(), null, emptySet()))
    }

    @Test
    fun fullRoundVisitsEveryLocationOnce() {
        val order = listOf("a", "b", "c", "d")
        val tried = mutableSetOf("c")
        var current = "c"
        val visited = mutableListOf<String>()
        while (true) {
            val n = AmeliaFailover.next(order, current, tried) ?: break
            visited += n; tried += n; current = n
        }
        assertEquals(listOf("d", "a", "b"), visited)
    }
}
