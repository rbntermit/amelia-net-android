package com.v2ray.ang.amelia

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AmeliaGeoUpdaterTest {

    private val day = AmeliaGeoUpdater.MAX_AGE_MS

    @Test
    fun dueWhenMissingOrStale() {
        assertTrue(AmeliaGeoUpdater.isDue(filesReady = false, lastUpdated = 1000, now = 1001))
        assertTrue(AmeliaGeoUpdater.isDue(filesReady = true, lastUpdated = 0, now = day))
        assertTrue(AmeliaGeoUpdater.isDue(filesReady = true, lastUpdated = 5 * day, now = day)) // clock moved back
        assertFalse(AmeliaGeoUpdater.isDue(filesReady = true, lastUpdated = day, now = day + day - 1))
    }

    @Test
    fun rejectsHtmlInsteadOfDat(@TempDir dir: File) {
        val dat = File(dir, "geoip.dat").apply { writeBytes(byteArrayOf(0x0A, 0x05) + ByteArray(2000)) }
        val html = File(dir, "error.html").apply { writeText("<html>" + "x".repeat(2000)) }
        assertTrue(AmeliaGeoUpdater.looksLikeGeoDat(dat))
        assertFalse(AmeliaGeoUpdater.looksLikeGeoDat(html))
    }
}
