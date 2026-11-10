package com.v2ray.ang.amelia

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** 1.9.1: "Пауза за роутером Amelia" (1.9.0) is removed; an update from 1.9.0 leaves nothing hanging. */
class Amelia191Test {

    /** The settings in a map, as AmeliaLegacy.Store. */
    private class MapStore(val map: MutableMap<String, String>) : AmeliaLegacy.Store {
        override fun contains(key: String) = key in map
        override fun remove(keys: Collection<String>) {
            keys.forEach { map.remove(it) }
        }
    }

    /** What 1.9.0 stored while the VPN was paused behind a router (answer "Да", held network, an offer). */
    private fun pausedIn190() = mutableMapOf(
        "amelia_router_pause" to "on",
        "amelia_router_paused" to """{"net":433791696907,"at":1800000000000}""",
        "amelia_router_hold" to "0",
        "amelia_router_offer" to "false",
        // settings of other features stay
        "amelia_key" to "0123456789abcdef",
        "amelia_ruapps_on" to "true",
    )

    @Test
    fun pausedStateOf190IsClearedOnce() {
        val store = MapStore(pausedIn190())
        var watchStopped = 0
        assertTrue(AmeliaLegacy.clearRouterPause(store) { watchStopped++ })
        assertEquals(1, watchStopped)
        assertTrue(AmeliaLegacy.ROUTER_PAUSE_KEYS.none { it in store.map })
        assertEquals(setOf("amelia_key", "amelia_ruapps_on"), store.map.keys)
        // the next start: nothing left to do
        assertFalse(AmeliaLegacy.clearRouterPause(store) { watchStopped++ })
        assertEquals(1, watchStopped)
    }

    @Test
    fun notPausedOnlyTheSettingGoes() {
        // "Нет" to the offer, never paused: no network watch existed
        val store = MapStore(mutableMapOf("amelia_router_pause" to "off", "amelia_key" to "k"))
        var watchStopped = false
        assertTrue(AmeliaLegacy.clearRouterPause(store) { watchStopped = true })
        assertFalse(watchStopped)
        assertEquals(setOf("amelia_key"), store.map.keys)
        // a new install or an update from before 1.9.0: nothing at all
        val clean = MapStore(mutableMapOf("amelia_key" to "k"))
        assertFalse(AmeliaLegacy.clearRouterPause(clean) { watchStopped = true })
        assertFalse(watchStopped)
    }

    @Test
    fun afterThePauseTheVpnStartsTheUsualWay() {
        val store = MapStore(pausedIn190())
        AmeliaLegacy.clearRouterPause(store) {}
        // VPN off after the pause: the button, the tile and the widget start it (nothing else is asked)
        assertEquals(AmeliaTile.Action.START, AmeliaTile.onTap(running = false, vpnPermission = true, hasLocation = true, updateRequired = false))
        // boot and Always-on start it as well
        assertEquals(
            AmeliaBoot.Decision.START,
            AmeliaBoot.decide(enabled = true, hasLocations = true, vpnPermission = true, subscriptionActive = true, updateRequired = false, alreadyHandled = false),
        )
        assertEquals("g1", AmeliaBoot.chooseLocation("g1", listOf("g1", "g2"), "g2"))
        // and nothing on the way of a start knows the pause any more
        listOf(
            "com/v2ray/ang/core/LauncherManager.kt",
            "com/v2ray/ang/amelia/AmeliaBoot.kt",
            "com/v2ray/ang/amelia/AmeliaDaemon.kt",
            "com/v2ray/ang/amelia/AmeliaWidgetTapActivity.kt",
            "com/v2ray/ang/amelia/AmeliaViewModel.kt",
            "com/v2ray/ang/service/QSTileService.kt",
            "com/v2ray/ang/service/CoreVpnService.kt",
            "com/v2ray/ang/core/CoreServiceManager.kt",
        ).forEach { path ->
            val src = File(javaDir(), path).readText()
            assertFalse("RouterPause" in src || "amelia_router_" in src || "onManualStart" in src || "routerCheck" in src, path)
        }
    }

    @Test
    fun nothingOfThePauseIsLeft() {
        assertFalse(File(javaDir(), "com/v2ray/ang/amelia/AmeliaRouterPause.kt").exists())
        // only the cleanup still names its settings
        javaDir().walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "AmeliaLegacy.kt" }.forEach {
            val src = it.readText()
            assertFalse("AmeliaRouterPause" in src || "amelia_router_" in src, it.path)
        }
        assertFalse("AmeliaRouterPause" in File(resDir(), "../AndroidManifest.xml").readText())
        // its strings, in every locale
        resDir().listFiles()!!.filter { it.isDirectory && it.name.startsWith("values") }.forEach { dir ->
            dir.listFiles()!!.filter { it.extension == "xml" }.forEach { assertFalse("amelia_router_" in it.readText(), it.path) }
        }
        // /app/whoami around the tunnel at .net was the pause's only; "Что видно снаружи" keeps its two ways
        val whoami = File(javaDir(), "com/v2ray/ang/amelia/AmeliaWhoami.kt").readText()
        assertFalse("ROUTER_BASES" in whoami || "ROUTER_TIMEOUT" in whoami)
        assertEquals(listOf("https://sub.ameliajustamelia.com:9443", "https://sub.ameliajustamelia.net:9443"), AmeliaWhoami.TUNNEL_BASES)
        assertEquals(AmeliaWhoami.TUNNEL_BASES, AmeliaWhoami.DIRECT_BASES.take(2))
        // the cleanup runs at every start of every process
        assertTrue("AmeliaLegacy.cleanUp(this)" in File(javaDir(), "com/v2ray/ang/AngApplication.kt").readText())
    }

    private fun resDir(): File =
        listOf(File("src/main/res"), File("app/src/main/res"), File("V2rayNG/app/src/main/res")).first { it.isDirectory }

    private fun javaDir(): File = File(resDir(), "../java")
}
