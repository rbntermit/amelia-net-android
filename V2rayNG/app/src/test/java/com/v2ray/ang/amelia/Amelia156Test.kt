package com.v2ray.ang.amelia

import com.v2ray.ang.amelia.AmeliaPolicy.Mode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 1.5.6: auto-switch "Всегда" by default. */
class Amelia156Test {

    private val now = 1_792_800_000_000L

    @Test
    fun newInstallIsAlways() {
        // nothing stored on a fresh install
        assertEquals(Mode.ALWAYS, AmeliaPolicy.modeOf(null))
        assertEquals(Mode.ALWAYS, AmeliaPolicy.DEFAULT_MODE)
    }

    @Test
    fun updateWithUntouchedSettingIsAlways() {
        // 1.4.0–1.5.5 stored the mode only on a tap in Settings: untouched = no value
        assertEquals(Mode.ALWAYS, AmeliaPolicy.modeOf(null))
        assertEquals(Mode.ALWAYS, AmeliaPolicy.modeOf(""))
        assertEquals(Mode.ALWAYS, AmeliaPolicy.modeOf("SOMETHING_OLD"))
    }

    @Test
    fun manualChoicesAreKept() {
        assertEquals(Mode.UNLESS_MANUAL, AmeliaPolicy.modeOf("UNLESS_MANUAL"))
        assertEquals(Mode.OFF, AmeliaPolicy.modeOf("OFF"))
        assertEquals(Mode.ALWAYS, AmeliaPolicy.modeOf("ALWAYS"))
    }

    @Test
    fun alwaysLeavesAFallenLocationChosenByHand() {
        // "Казахстан 1" chosen by hand, 3 failures over 30 s
        val chosen = AmeliaManual.chosen(AmeliaManual.State(), "Казахстан 1", now - 60_000)
        val first = now - AmeliaPolicy.MANUAL_SPAN_MS
        assertTrue(AmeliaPolicy.broken(manual = true, failures = 3, firstFailAt = first, now = now, noTraffic = false))
        assertTrue(AmeliaPolicy.maySwitch(AmeliaPolicy.modeOf(null), manual = true, respectUntil = chosen.respectUntil, now = now))
        // the switch remembers what was left: the notice says "Казахстан 1 не отвечает — переключились на …"
        val left = AmeliaManual.left(chosen, "Казахстан 1", now)
        assertEquals("Казахстан 1", left.leftName)
        assertEquals(null, left.name, "the new location is an automatic choice")
        // the rules for the other modes did not change
        assertFalse(AmeliaPolicy.maySwitch(Mode.UNLESS_MANUAL, manual = true, respectUntil = 0L, now = now))
        assertFalse(AmeliaPolicy.maySwitch(Mode.OFF, manual = true, respectUntil = 0L, now = now))
        assertFalse(AmeliaPolicy.maySwitch(Mode.ALWAYS, manual = true, respectUntil = now + 1, now = now), "10 min after «Вернуться на …»")
    }
}
