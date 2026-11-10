package com.v2ray.ang.amelia

import com.v2ray.ang.amelia.AmeliaQueue.Why
import com.v2ray.ang.dto.V2rayConfig.OutboundBean
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 1.8.4: Xray-core 26.11.9. Its mux.cool raises any concurrency 0..128 to 128 when mux is enabled:
 * the app keeps mux off, so nothing changes. The server now always puts "Москва" at the bottom,
 * right before "🆘 Резервный N — только Telegram": the app keeps the subscription's order.
 */
class Amelia184Test {

    private val now = 1_800_000_000_000L

    private fun loc(guid: String, name: String, hostPort: String = "$guid:443") = AmeliaQueue.Location(guid, name, hostPort)

    private fun ok(name: String, ms: Long) = name to AmeliaChecks.Entry(name, ms = ms, ts = now, net = "n")

    /** The subscription as the server sends it now. */
    private val list = listOf(
        loc("nl1", "Нидерланды 1 🇳🇱"),
        loc("fi1", "Финляндия 1 🇫🇮"),
        loc("kz1", "Казахстан 1 🇰🇿"),
        loc("msk", "Москва 🇷🇺"),
        loc("r1", "🆘 Резервный 1 — только Telegram"),
    )

    // ---- order of locations ------------------------------------------------------------------

    @Test
    fun failoverGoesDownTheSubscriptionAndLeavesMoscowAndReserveOut() {
        val plan = AmeliaQueue.failoverPlan(list, "Финляндия 1 🇫🇮", setOf("Финляндия 1 🇫🇮"), emptySet(), emptyMap(), emptySet(), allowLastResort = false)
        assertEquals(listOf("kz1", "nl1"), plan.steps.map { it.guid })
        assertTrue(plan.steps.all { it.why == Why.LIST })
        assertEquals(listOf("msk" to Why.SKIP_RUSSIAN, "r1" to Why.SKIP_RESERVE), plan.skipped.map { it.guid to it.why })
    }

    @Test
    fun afterTheSubscriptionEndedMoscowComesBeforeReserveAsInTheList() {
        val plan = AmeliaQueue.failoverPlan(list, "Казахстан 1 🇰🇿", setOf("Казахстан 1 🇰🇿"), emptySet(), emptyMap(), emptySet(), allowLastResort = true)
        assertEquals(listOf("nl1", "fi1", "msk", "r1"), plan.steps.map { it.guid })
        assertEquals(listOf(Why.LIST, Why.LIST, Why.LAST_RESORT, Why.LAST_RESORT), plan.steps.map { it.why })
    }

    @Test
    fun autoPickDoesNotDependOnWhereMoscowStands() {
        // Moscow answers fastest, yet a working foreign location wins; the same for the old layout
        val known = mapOf(ok("Москва 🇷🇺", 10), ok("Нидерланды 1 🇳🇱", 90), ok("Финляндия 1 🇫🇮", 60), ok("Казахстан 1 🇰🇿", 120))
        val bottom = AmeliaQueue.order(list, known, emptySet(), null, allowLastResort = true)
        assertEquals(listOf("fi1", "nl1", "kz1", "msk", "r1"), bottom)
        val top = listOf(list[3]) + list.take(3) + list[4]
        assertEquals(bottom, AmeliaQueue.order(top, known, emptySet(), null, allowLastResort = true))
        // while the subscription is valid neither is picked automatically
        assertEquals(listOf("fi1", "nl1", "kz1"), AmeliaQueue.order(list, known, emptySet(), null, allowLastResort = false))
    }

    @Test
    fun firstChoiceIsTheFirstForeignOne() {
        val locs = list.map { AmeliaManager.Location(it.guid, it.name) }
        assertEquals("nl1", AmeliaReserve.firstChoice(locs)?.guid)
        assertEquals("msk", AmeliaReserve.firstChoice(locs.drop(3))?.guid)
    }

    // ---- mux stays off with the new core ------------------------------------------------------

    @Test
    fun muxIsOffByDefaultInTheOutbound() {
        val mux = OutboundBean(protocol = "vless").mux
        assertNotNull(mux)
        assertFalse(mux!!.enabled)
        listOf("v2ray_config.json", "v2ray_config_with_tun.json").forEach { name ->
            val json = File(assetsDir(), name).readText()
            assertTrue(Regex("\"mux\"\\s*:\\s*\\{\\s*\"enabled\"\\s*:\\s*false\\s*}").containsMatchIn(json), name)
        }
    }

    @Test
    fun muxOffBranchSendsConcurrencyMinusOne() {
        // Xray 26.11.9 raises 0..128 to 128 only inside "if enabled": -1 with enabled=false stays off
        val builder = File(javaDir(), "com/v2ray/ang/core/CoreOutboundBuilder.kt").readText()
        assertTrue("decodeSettingsBool(AppConfig.PREF_MUX_ENABLED, false)" in builder)
        val off = builder.substringAfter("if (muxEnabled) {").substringAfter("} else {").substringBefore("}")
        assertTrue("outbound.mux?.enabled = false" in off, off)
        assertTrue("outbound.mux?.concurrency = -1" in off, off)
        // nothing in the app turns mux on, and finalRules (blockDelay renamed in 26.11.9) are not used
        val sources = javaDir().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(sources.none { "encodeSettings(AppConfig.PREF_MUX_ENABLED" in it.readText() })
        assertTrue(sources.none { it.readText().contains("blockDelay") || it.readText().contains("finalRules") })
    }

    // ---- local proxy is still protected ------------------------------------------------------

    @Test
    fun localProxyStillGetsRandomPortLoginAndPassword() {
        val v = AmeliaLocalProxy.plan(done = false, port = "10808", user = null, pass = null, random = kotlin.random.Random(184))!!
        assertTrue(v.port in AmeliaLocalProxy.PORT_MIN..AmeliaLocalProxy.PORT_MAX)
        assertTrue(v.user.length >= 12 && v.pass.length >= 24)
        assertTrue(v.user.all { it.isLetterOrDigit() } && v.pass.all { it.isLetterOrDigit() })
    }

    private fun resDir(): File =
        listOf(File("src/main/res"), File("app/src/main/res"), File("V2rayNG/app/src/main/res")).first { it.isDirectory }

    private fun javaDir(): File = File(resDir(), "../java")

    private fun assetsDir(): File = File(resDir(), "../assets")
}
