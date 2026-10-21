package com.v2ray.ang.amelia

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.RulesetItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

/** 1.5.2: site lists from the subscription's `routing` header. */
class Amelia152Test {

    private val decode: (String) -> String? = { runCatching { String(Base64.getDecoder().decode(it)) }.getOrNull() }

    /** What sub_server sends: Happ routing profile, base64 JSON after happ://routing/onadd/. */
    private val profileJson = """
        {"Name":"Amelia","GlobalProxy":"true","RouteOrder":"block-proxy-direct","DomainStrategy":"IPIfNonMatch",
         "ProxySites":["googlevideo.com","youtube.com","youtu.be"],
         "DirectSites":["geosite:category-ru","kinopoisk.ru","notes.ameliajustamelia.net"],
         "DirectIp":["geoip:ru","10.0.0.0/8"],
         "ProxyIp":[],"BlockSites":[],"BlockIp":[]}
    """.trimIndent()
    private val header = "happ://routing/onadd/" + Base64.getEncoder().encodeToString(profileJson.toByteArray())

    /** geosite:category-ru as in our base; youtube.com is in it on purpose. */
    private val categoryRu = setOf("yandex.ru", "kinopoisk.ru", "youtube.com")

    /** Xray's choice for a sniffed domain: first matching rule in order, otherwise the proxy. */
    private fun route(rules: List<RulesetItem>, host: String): String {
        for (r in rules) {
            if (r.domain.orEmpty().any { matches(it, host) }) return r.outboundTag
        }
        return AppConfig.TAG_PROXY
    }

    private fun matches(rule: String, host: String): Boolean = when {
        rule.startsWith("domain:") -> rule.removePrefix("domain:").let { host == it || host.endsWith(".$it") }
        rule.startsWith("full:") -> host == rule.removePrefix("full:")
        rule.startsWith("keyword:") -> host.contains(rule.removePrefix("keyword:"))
        rule == AmeliaRouting.GEOSITE_RU -> categoryRu.any { host == it || host.endsWith(".$it") }
        else -> false
    }

    private fun rules(profile: AmeliaRouteProfile.Profile?, withGeo: Boolean = true) =
        AmeliaRouting.buildRulesets(withGeo, profile)

    @Test
    fun headerParses() {
        val p = AmeliaRouteProfile.parse(header, decode)
        assertNotNull(p)
        assertTrue("notes.ameliajustamelia.net" in p!!.directSites)
        assertEquals(listOf("geoip:ru", "10.0.0.0/8"), p.directIp)
        assertTrue("youtube.com" in p.proxySites)
        // URL-safe base64 without padding, and the "add" form, work too
        val urlSafe = Base64.getUrlEncoder().withoutPadding().encodeToString(profileJson.toByteArray())
        assertEquals(p, AmeliaRouteProfile.parse("happ://routing/add/$urlSafe", decode))
    }

    @Test
    fun telegramProxyAndItsSubdomainsGoDirect() {
        val r = rules(AmeliaRouteProfile.parse(header, decode))
        assertEquals(AppConfig.TAG_DIRECT, route(r, "notes.ameliajustamelia.net"))
        assertEquals(AppConfig.TAG_DIRECT, route(r, "cdn.notes.ameliajustamelia.net"))
        // not the rest of the domain, and not a look-alike
        assertEquals(AppConfig.TAG_PROXY, route(r, "ameliajustamelia.net"))
        assertEquals(AppConfig.TAG_PROXY, route(r, "xnotes.ameliajustamelia.net"))
        // without the header (1.5.1 rules) it went through the VPN
        assertEquals(AppConfig.TAG_PROXY, route(rules(null), "notes.ameliajustamelia.net"))
    }

    @Test
    fun proxySitesWinOverDirectSites() {
        val r = rules(AmeliaRouteProfile.parse(header, decode))
        assertTrue("domain:youtube.com" in r.first().domain.orEmpty())
        assertEquals(AppConfig.TAG_PROXY, route(r, "youtube.com"))
        assertEquals(AppConfig.TAG_PROXY, route(r, "m.youtube.com"))
        assertEquals(AppConfig.TAG_DIRECT, route(r, "yandex.ru"))
        // rule order as Happ's RouteOrder block-proxy-direct
        assertEquals(listOf("amelia-proxy-sites", "amelia-direct-sites", "amelia-direct-ip"), r.map { it.id })
    }

    @Test
    fun emptyOrBrokenHeaderChangesNothing() {
        listOf(
            null, "", "   ", "happ://routing/onadd/", "happ://routing/onadd/!!!not-base64!!!",
            "happ://routing/onadd/" + Base64.getEncoder().encodeToString("[1,2]".toByteArray()),
            "happ://routing/onadd/" + Base64.getEncoder().encodeToString("{\"Name\":\"x\"}".toByteArray()),
            "something else",
        ).forEach { assertNull(AmeliaRouteProfile.parse(it, decode), "header: $it") }
        // no profile: exactly the built-in rules
        assertEquals(AmeliaRouting.buildRulesets(true), AmeliaRouting.buildRulesets(true, null))
    }

    @Test
    fun geoTagsOnlyWhenTheBaseHasThem() {
        val p = AmeliaRouteProfile.Profile(directSites = listOf("geosite:category-ru", "geosite:private"), directIp = listOf("geoip:private"))
        // geo files not downloaded yet: no geo rules at all (Xray would not start)
        val noGeo = rules(p, withGeo = false).flatMap { it.domain.orEmpty() + it.ip.orEmpty() }
        assertFalse(noGeo.any { it.startsWith("geo") })
        // downloaded, but our base has only the Russian lists: geosite:private is left out
        val withGeo = rules(p).flatMap { it.domain.orEmpty() + it.ip.orEmpty() }
        assertTrue(AmeliaRouting.GEOSITE_RU in withGeo)
        assertFalse("geosite:private" in withGeo)
        assertFalse("geoip:private" in withGeo)
        assertEquals(1, withGeo.count { it == AmeliaRouting.GEOSITE_RU }, "no duplicate")
    }

    @Test
    fun entriesBecomeXrayMatchers() {
        val none: (String) -> Boolean = { false }
        assertEquals("domain:notes.ameliajustamelia.net", AmeliaRouting.domainRule(" notes.ameliajustamelia.net ", none))
        assertEquals("full:a.example.com", AmeliaRouting.domainRule("full:a.example.com", none))
        assertEquals("regexp:^x\\.ru$", AmeliaRouting.domainRule("regexp:^x\\.ru$", none))
        assertNull(AmeliaRouting.domainRule("https://bad/entry", none))
        assertEquals("1.2.3.0/24", AmeliaRouting.ipRule("1.2.3.0/24", none))
        assertNull(AmeliaRouting.ipRule("geoip:ru", none))
        assertEquals("geoip:ru", AmeliaRouting.ipRule("geoip:ru") { true })
    }

    @Test
    fun geoBaseTagLookup() {
        // GeoSiteList { entry { country_code: "CATEGORY-RU" … } }
        val name = "CATEGORY-RU".toByteArray()
        val data = byteArrayOf(0x0A, 50, 0x0A, name.size.toByte()) + name + byteArrayOf(0x12, 3, 1, 2, 3)
        assertTrue(AmeliaRouting.hasCountryCode(data, "category-ru"))
        assertTrue(AmeliaRouting.hasCountryCode(data, "category-ru@ads"))
        assertFalse(AmeliaRouting.hasCountryCode(data, "private"))
        assertFalse(AmeliaRouting.hasCountryCode(data, "ru"), "RU inside CATEGORY-RU is not the list RU")
    }
}
