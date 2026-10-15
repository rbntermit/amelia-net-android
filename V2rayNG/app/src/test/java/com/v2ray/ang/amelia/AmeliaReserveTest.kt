package com.v2ray.ang.amelia

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AmeliaReserveTest {

    @Test
    fun recognisedByName() {
        assertTrue(AmeliaReserve.isReserve("🆘 Резервный — только Telegram"))
        assertTrue(AmeliaReserve.isReserve("🆘 Резервный 2 — только Telegram"))
        assertTrue(AmeliaReserve.isReserve("резервный"))
        assertTrue(AmeliaReserve.isReserve("Канал ТОЛЬКО TELEGRAM"))
        assertFalse(AmeliaReserve.isReserve("🇫🇮 Финляндия 1"))
        assertFalse(AmeliaReserve.isReserve("Telegram-friendly NL"))
        assertFalse(AmeliaReserve.isReserve(null))
        assertEquals(AmeliaReserve.TEST_URL, AmeliaReserve.testUrl("🆘 Резервный — только Telegram", "https://x/204"))
        assertEquals("https://x/204", AmeliaReserve.testUrl("Финляндия 1", "https://x/204"))
    }

    @Test
    fun anyHttpAnswerFromTelegramCounts() {
        // the core rejects statuses other than 200/204: for a reserve location that is an answer
        assertEquals(150L to null, AmeliaReserve.interpret(true, -1, "invalid status: 302 Found", 300))
        assertEquals(1L to null, AmeliaReserve.interpret(true, -1, "invalid status: 404 Not Found", 0))
        assertEquals(120L to null, AmeliaReserve.interpret(true, 120, null, 500))
        // no answer at all is still a failure
        assertEquals(-1L to "EOF", AmeliaReserve.interpret(true, -1, "EOF", 12_000))
        // regular locations keep the strict check
        assertEquals(-1L to "invalid status: 302 Found", AmeliaReserve.interpret(false, -1, "invalid status: 302 Found", 300))
    }

    @Test
    fun failoverTakesReserveOnlyWhenNoRegularIsLeft() {
        val order = listOf("fi", "res1", "nl", "res2", "se")
        val reserves = setOf("res1", "res2")
        assertEquals("nl", AmeliaReserve.next(order, "fi", setOf("fi"), reserves))
        assertEquals("se", AmeliaReserve.next(order, "nl", setOf("fi", "nl"), reserves))
        // every regular location failed: now a reserve one, the next after the current
        assertEquals("res1", AmeliaReserve.next(order, "se", setOf("fi", "nl", "se"), reserves))
        assertEquals("res2", AmeliaReserve.next(order, "res1", setOf("fi", "nl", "se", "res1"), reserves))
        assertNull(AmeliaReserve.next(order, "res2", order.toSet(), reserves))
        // a regular one not tried yet always wins over a reserve one
        assertEquals("se", AmeliaReserve.next(order, "res2", setOf("res2"), reserves))
    }

    @Test
    fun automaticChoiceSkipsReserve() {
        val list = listOf(
            AmeliaManager.Location("r", "🆘 Резервный — только Telegram"),
            AmeliaManager.Location("fi", "Финляндия 1"),
        )
        assertEquals("fi", AmeliaReserve.firstChoice(list)?.guid)
        assertEquals("r", AmeliaReserve.firstChoice(list.take(1))?.guid)
        assertNull(AmeliaReserve.firstChoice(emptyList()))
    }

    @Test
    fun reportNeverListsReserveAsNotWorking() {
        val servers = listOf(
            AmeliaReport.Server("Финляндия 1", "1.1.1.1", 443, true, 100, "ok", null),
            AmeliaReport.Server("Нидерланды 1", "2.2.2.2", 443, false, null, "data", "EOF"),
            AmeliaReport.Server("🆘 Резервный — только Telegram", "3.3.3.3", 443, false, null, "data", "EOF"),
            AmeliaReport.Server("🆘 Резервный 2 — только Telegram", "4.4.4.4", 443, true, 210, "ok", null),
        )
        val (working, failing) = AmeliaReport.split(servers)
        assertEquals(listOf("Финляндия 1", "🆘 Резервный 2 — только Telegram"), working)
        assertEquals(listOf("Нидерланды 1"), failing)
    }
}
