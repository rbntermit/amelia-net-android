package com.v2ray.ang.amelia

import com.v2ray.ang.amelia.AmeliaBoot.Decision
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 1.5.4: VPN at boot, Always-on start, quick settings tile, problem report. */
class Amelia154Test {

    private fun boot(
        enabled: Boolean = true,
        hasLocations: Boolean = true,
        permission: Boolean = true,
        subActive: Boolean = true,
        updateRequired: Boolean = false,
        handled: Boolean = false,
    ) = AmeliaBoot.decide(enabled, hasLocations, permission, subActive, updateRequired, handled)

    @Test
    fun startOnBoot() {
        // off (the default): nothing at all
        assertEquals(Decision.NOTHING, boot(enabled = false))
        assertFalse(AmeliaBoot.showPermissionHint(enabled = false, vpnPermission = false))
        // on, but Android never let the app make a VPN: nothing now, the line in the app
        assertEquals(Decision.NEEDS_PERMISSION, boot(permission = false))
        assertTrue(AmeliaBoot.showPermissionHint(enabled = true, vpnPermission = false))
        assertFalse(AmeliaBoot.showPermissionHint(enabled = true, vpnPermission = true))
        // on with the permission: start
        assertEquals(Decision.START, boot())
        // subscription ended: no connecting at boot
        assertEquals(Decision.EXPIRED, boot(subActive = false))
        // the same boot reported twice (BOOT_COMPLETED + QUICKBOOT_POWERON): once
        assertEquals(Decision.NOTHING, boot(handled = true))
        // no key or no locations yet; a required update blocks the connect button too
        assertEquals(Decision.NOTHING, boot(hasLocations = false))
        assertEquals(Decision.UPDATE_REQUIRED, boot(updateRequired = true))
        assertTrue(AmeliaBoot.BOOT_ACTIONS.contains("android.intent.action.BOOT_COMPLETED"))
        assertFalse(AmeliaBoot.BOOT_ACTIONS.contains("android.intent.action.MY_PACKAGE_REPLACED"))
    }

    @Test
    fun systemStartAlwaysOnPicksALocation() {
        val existing = listOf("g1", "g2", "g3")
        // the last location still exists: that one
        assertEquals("g2", AmeliaBoot.chooseLocation("g2", existing, best = "g3"))
        // a subscription refresh removed it (new guids): the best one, as "Подключить лучший"
        assertEquals("g3", AmeliaBoot.chooseLocation("old", existing, best = "g3"))
        assertEquals("g3", AmeliaBoot.chooseLocation(null, existing, best = "g3"))
        // no answer about the best: the first location rather than an empty start
        assertEquals("g1", AmeliaBoot.chooseLocation(null, existing, best = null))
        // no locations at all (logged out): nothing, the service reports it and stops
        assertNull(AmeliaBoot.chooseLocation("g1", emptyList(), best = null))
    }

    @Test
    fun quickSettingsTile() {
        assertEquals(AmeliaTile.Action.STOP, AmeliaTile.onTap(running = true, vpnPermission = true, hasLocation = true, updateRequired = false))
        assertEquals(AmeliaTile.Action.START, AmeliaTile.onTap(running = false, vpnPermission = true, hasLocation = true, updateRequired = false))
        // no VPN permission: open the app (Android asks there) instead of doing nothing
        assertEquals(AmeliaTile.Action.OPEN_APP, AmeliaTile.onTap(running = false, vpnPermission = false, hasLocation = true, updateRequired = false))
        assertEquals(AmeliaTile.Action.OPEN_APP, AmeliaTile.onTap(running = false, vpnPermission = true, hasLocation = false, updateRequired = false))
        assertEquals(AmeliaTile.Action.OPEN_APP, AmeliaTile.onTap(running = false, vpnPermission = true, hasLocation = true, updateRequired = true))
    }

    private val key = "a1b2c3d4e5f6a7b8"
    private val body = AmeliaReport.Body(
        key = key, ts = 1_792_600_000, app = "1.5.4", code = 18, xray = "26.10.21", hy2 = "xray-26.10.21",
        android = "16", device = "samsung SM-S711B", tv = false, net = "mobile", op = "MTS RUS", path = "direct",
        active = "Финляндия 2 🇫🇮", bypassRu = false, comment = "не открывается youtube",
        servers = listOf(
            AmeliaReport.Server("Финляндия 2 🇫🇮", "95.0.0.1", 443, ok = true, ms = 80, stage = "ok", err = null),
            AmeliaReport.Server("Нидерланды 1 🇳🇱", "79.0.0.2", 2079, ok = false, ms = null, stage = "connect", err = "timeout"),
        ),
        checkMs = 6100,
        vpn = true, subActive = true,
        journal = AmeliaReport.journalLines(
            (1..25).map { AmeliaJournal.Entry(it * 60_000L, "Нидерланды 1 не отвечает — переключились на Финляндия 2") },
        ) { "t$it" },
    )

    @Test
    fun problemReportHasTheDiagnostics() {
        val json = AmeliaReport.toJson(body)
        listOf(
            "\"app\":\"1.5.4\"", "\"xray\":\"26.10.21\"", "\"device\":\"samsung SM-S711B\"", "\"android\":\"16\"",
            "\"active\":\"Финляндия 2", "\"net\":\"mobile\"", "\"op\":\"MTS RUS\"", "\"vpn\":true", "\"sub_active\":true",
            "\"journal\":[", "\"comment\":\"не открывается youtube\"", "\"ms\":80",
        ).forEach { assertTrue(it in json, it) }
        // at most 20 journal lines, the newest ones, each cut
        assertEquals(AmeliaReport.MAX_JOURNAL, body.journal.size)
        assertTrue(body.journal.first().startsWith("t360000 "))
        assertTrue(body.journal.all { it.length <= AmeliaReport.MAX_JOURNAL_LINE })
    }

    @Test
    fun problemReportHasNoSubscriptionLinkOrSecrets() {
        val json = AmeliaReport.toJson(body)
        // no subscription address or link of any form
        listOf("/sub/", "ameliajustamelia", "vless://", "hy2://", "hysteria2://", "uuid", "password").forEach {
            assertFalse(it in json.lowercase(), it)
        }
        // the key travels only in the contract's "key" field (the bot answers to that subscription) …
        assertEquals(1, Regex(Regex.escape(key)).findAll(json).count())
        // … and never in the text people paste into the bot
        val text = AmeliaReport.plainText(body)
        assertFalse(key in text)
        assertFalse("/sub/" in text)
        assertTrue("VPN включён: да" in text)
        assertTrue("Журнал:" in text)
    }
}
