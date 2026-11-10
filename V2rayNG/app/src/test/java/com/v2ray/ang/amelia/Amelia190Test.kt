package com.v2ray.ang.amelia

import com.v2ray.ang.AppConfig
import com.v2ray.ang.amelia.AmeliaWhoami.Kind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 1.9.0: "Что видно снаружи" (/app/whoami) and "Российские приложения — мимо VPN" (/app/ruapps).
 * The pause behind an Amelia router of 1.9.0 is gone since 1.9.1 (Amelia191Test).
 */
class Amelia190Test {

    // ---- /app/whoami: the answer --------------------------------------------------------------

    @Test
    fun whoamiAnswersAreRead() {
        val ours = AmeliaWhoami.parse("""{"ok":true,"ours":true,"via":"nl2","cc":"NL","ip":"192.0.2.10"}""")!!
        assertEquals(true, ours.ours)
        assertEquals("nl2", ours.via)
        assertEquals("NL", ours.cc)
        val direct = AmeliaWhoami.parse("""{"ok":true,"ours":false,"cc":"RU","ip":"203.0.113.5","as":"MTS PJSC"}""")!!
        assertEquals(false, direct.ours)
        assertEquals("MTS PJSC", direct.asName)
        // too often / address unknown, no "ours", garbage: no answer
        assertNull(AmeliaWhoami.parse("""{"ok":false}"""))
        assertNull(AmeliaWhoami.parse("""{"ok":true}"""))
        assertNull(AmeliaWhoami.parse("<html>"))
        assertNull(AmeliaWhoami.parse(null))
    }

    @Test
    fun whatThePersonIsTold() {
        val nl = AmeliaWhoami.parse("""{"ok":true,"ours":true,"via":"nl2","cc":"NL","ip":"192.0.2.10"}""")
        assertEquals(AmeliaWhoami.Verdict(Kind.ABROAD, "Нидерланды", "192.0.2.10", null), AmeliaWhoami.verdict(nl, vpnOn = true))
        // "Москва": our server, Russian address
        val msk = AmeliaWhoami.parse("""{"ok":true,"ours":true,"via":"msk","cc":"RU","ip":"192.0.2.20"}""")
        assertEquals(Kind.OUR_RUSSIAN, AmeliaWhoami.verdict(msk, vpnOn = true).kind)
        // VPN on but the operator's address: a leak, with the operator's name in words
        val leak = AmeliaWhoami.parse("""{"ok":true,"ours":false,"cc":"RU","ip":"203.0.113.5","as":"MTS PJSC"}""")
        val v = AmeliaWhoami.verdict(leak, vpnOn = true)
        assertEquals(Kind.LEAK, v.kind)
        assertEquals("MTS", v.operator)
        // VPN off: how the person is seen
        val off = AmeliaWhoami.verdict(leak, vpnOn = false)
        assertEquals(Kind.VPN_OFF, off.kind)
        assertEquals("MTS, Россия, 203.0.113.5", AmeliaWhoami.seenAs(off))
        // VPN off yet ours: the network goes through an Amelia router
        assertEquals(Kind.ROUTER, AmeliaWhoami.verdict(nl, vpnOn = false).kind)
        // no answer
        assertEquals(Kind.UNKNOWN, AmeliaWhoami.verdict(null, vpnOn = true).kind)
        assertEquals(Kind.UNKNOWN, AmeliaWhoami.verdict(AmeliaWhoami.Answer(ok = true, ours = null), vpnOn = false).kind)
    }

    @Test
    fun countriesInWords() {
        assertEquals("Нидерланды", AmeliaWhoami.countryName("NL"))
        assertEquals("Финляндия", AmeliaWhoami.countryName("fi"))
        assertEquals("Россия", AmeliaWhoami.countryName("RU"))
        assertNull(AmeliaWhoami.countryName(""))
        assertNull(AmeliaWhoami.countryName(null))
        assertNull(AmeliaWhoami.countryName("X1"))
        assertNull(AmeliaWhoami.countryName("NLD"))
    }

    // ---- /app/whoami: where it is asked ---------------------------------------------------------

    @Test
    fun throughTheTunnelOnlyAbroad() {
        // never a Russian mirror: the entry is reached directly and would see the entry, not the exit
        assertEquals(listOf("https://sub.ameliajustamelia.com:9443", "https://sub.ameliajustamelia.net:9443"), AmeliaWhoami.TUNNEL_BASES)
        assertTrue(AmeliaWhoami.TUNNEL_BASES.none { b -> AmeliaConfig.ENTRIES.any { it.baseUrl == b } })
        // VPN off: the same addresses first, then the mirrors
        assertEquals(AmeliaWhoami.TUNNEL_BASES, AmeliaWhoami.DIRECT_BASES.take(2))
    }

    @Test
    fun ourForeignDomainsAlwaysGoThroughTheVpn() {
        val rules = AmeliaRouting.buildRulesets(withGeo = true, AmeliaRouteProfile.Profile(directSites = listOf("ameliajustamelia.com")))
        val proxy = rules.first()
        assertEquals(AppConfig.TAG_PROXY, proxy.outboundTag)
        assertTrue("domain:sub.ameliajustamelia.com" in proxy.domain.orEmpty())
        assertTrue("domain:sub.ameliajustamelia.net" in proxy.domain.orEmpty())
        // the proxy rule comes before a direct list of the subscription that would cover the domain
        assertTrue(rules.indexOfFirst { it.outboundTag == AppConfig.TAG_DIRECT } > 0)
    }

    // ---- IPv6 and DNS by the tunnel's own settings ----------------------------------------------

    @Test
    fun ipv6AndDnsFromTheConfig() {
        val l = AmeliaWhoami.local(ipv6Enabled = false, vpnDns = listOf("1.1.1.1"), directIps = AmeliaRouting.DIRECT_IPS)
        assertFalse(l.ipv6InTunnel)
        assertTrue(l.dnsInTunnel)
        assertTrue(AmeliaWhoami.local(true, listOf("1.1.1.1"), AmeliaRouting.DIRECT_IPS).ipv6InTunnel)
        // a DNS server in a direct network goes around the tunnel
        assertFalse(AmeliaWhoami.local(false, listOf("192.168.1.1"), AmeliaRouting.DIRECT_IPS).dnsInTunnel)
        assertFalse(AmeliaWhoami.local(false, listOf("1.1.1.1"), listOf("1.1.1.1")).dnsInTunnel)
        assertFalse(AmeliaWhoami.local(false, emptyList(), AmeliaRouting.DIRECT_IPS).dnsInTunnel)
    }

    @Test
    fun ipv4Networks() {
        assertTrue(AmeliaWhoami.ipv4InCidr("10.1.2.3", "10.0.0.0/8"))
        assertTrue(AmeliaWhoami.ipv4InCidr("172.31.0.1", "172.16.0.0/12"))
        assertFalse(AmeliaWhoami.ipv4InCidr("172.32.0.1", "172.16.0.0/12"))
        assertTrue(AmeliaWhoami.ipv4InCidr("255.255.255.255", "255.255.255.255"))
        assertTrue(AmeliaWhoami.ipv4InCidr("8.8.8.8", "0.0.0.0/0"))
        assertFalse(AmeliaWhoami.ipv4InCidr("1.1.1.1", "geoip:ru"))
        assertFalse(AmeliaWhoami.ipv4InCidr("2001:db8::1", "10.0.0.0/8"))
        assertFalse(AmeliaWhoami.ipv4InCidr("10.0.0.1", "10.0.0.0/33"))
        assertFalse(AmeliaWhoami.ipv4InCidr("10.0.0.256", "10.0.0.0/8"))
    }

    // ---- Russian apps around the VPN ------------------------------------------------------------

    @Test
    fun ruAppsListIsRead() {
        assertEquals(
            listOf("ru.sberbankmobile", "com.wildberries.ru"),
            AmeliaApps.parseRuList("""{"v":20261111,"apps":["ru.sberbankmobile"," com.wildberries.ru ","ru.sberbankmobile","bad name","x","../etc"]}"""),
        )
        assertNull(AmeliaApps.parseRuList("""{"v":1,"apps":[]}"""))
        assertNull(AmeliaApps.parseRuList("""{"v":1}"""))
        assertNull(AmeliaApps.parseRuList("<html>"))
        assertNull(AmeliaApps.parseRuList(null))
        // the built-in copy: the server's list of 11.11.2026, all valid package names
        assertEquals(34, AmeliaApps.RU_BUILT_IN.size)
        assertEquals(AmeliaApps.RU_BUILT_IN, AmeliaApps.parseRuList("""{"apps":${AmeliaApps.RU_BUILT_IN.joinToString(",", "[", "]") { "\"$it\"" }}}"""))
    }

    @Test
    fun ruAppsJoinOnlyWhenInstalledAndNotTakenOff() {
        val list = listOf("ru.sberbankmobile", "ru.rostel", "com.avito.android", "ru.yandex.taxi")
        val installed = setOf("ru.sberbankmobile", "ru.rostel", "com.avito.android", "org.telegram.messenger")
        // Госуслуги was on the list by hand already; Avito was taken off by hand before
        val r = AmeliaApps.ruAdd(setOf("ru.rostel"), list, installed, added = emptySet(), removedByHand = setOf("com.avito.android"))
        assertEquals(setOf("ru.rostel", "ru.sberbankmobile"), r.selected)
        assertEquals(setOf("ru.sberbankmobile"), r.added)
        // a later check (subscription update): a newly installed app of the list joins
        val later = AmeliaApps.ruAdd(r.selected, list, installed + "ru.yandex.taxi", r.added, setOf("com.avito.android"))
        assertEquals(setOf("ru.rostel", "ru.sberbankmobile", "ru.yandex.taxi"), later.selected)
        assertEquals(setOf("ru.sberbankmobile", "ru.yandex.taxi"), later.added)
        // switched off: only what the switch added leaves; Госуслуги (by hand) stays
        assertEquals(setOf("ru.rostel"), AmeliaApps.ruRemove(later.selected, later.added))
    }

    @Test
    fun takenOffByHandIsNeverPutBack() {
        val added = setOf("ru.sberbankmobile", "ru.yandex.taxi")
        val before = setOf("ru.rostel", "ru.sberbankmobile", "ru.yandex.taxi")
        // the person takes Сбер off
        val e = AmeliaApps.afterHandEdit(before, before - "ru.sberbankmobile", added, removed = emptySet())
        assertEquals(setOf("ru.yandex.taxi"), e.added)
        assertEquals(setOf("ru.sberbankmobile"), e.removed)
        val again = AmeliaApps.ruAdd(before - "ru.sberbankmobile", listOf("ru.sberbankmobile"), setOf("ru.sberbankmobile"), e.added, e.removed)
        assertFalse("ru.sberbankmobile" in again.selected)
        // an app the person added by hand and takes off is not remembered
        val own = AmeliaApps.afterHandEdit(before, before - "ru.rostel", added, emptySet())
        assertTrue(own.removed.isEmpty())
        // put back by hand: forgiven
        val back = AmeliaApps.afterHandEdit(before - "ru.sberbankmobile", before, e.added, e.removed)
        assertTrue(back.removed.isEmpty())
    }

    // ---- privacy -------------------------------------------------------------------------------

    @Test
    fun whoamiAndRuappsCarryNoKeyAndNothingAboutTheApps() {
        val whoami = File(javaDir(), "com/v2ray/ang/amelia/AmeliaWhoami.kt").readText()
        val apps = File(javaDir(), "com/v2ray/ang/amelia/AmeliaApps.kt").readText()
        listOf(whoami, apps).forEach { src ->
            assertFalse("getKey" in src)
            assertFalse(".post(" in src)
            assertFalse("installId" in src || "AmeliaProbe.iid" in src)
        }
        // both are plain GETs of a fixed path, no query
        assertEquals("/app/whoami", AmeliaWhoami.PATH)
        assertEquals("/app/ruapps", AmeliaApps.RU_PATH)
    }

    private fun resDir(): File =
        listOf(File("src/main/res"), File("app/src/main/res"), File("V2rayNG/app/src/main/res")).first { it.isDirectory }

    private fun javaDir(): File = File(resDir(), "../java")
}
