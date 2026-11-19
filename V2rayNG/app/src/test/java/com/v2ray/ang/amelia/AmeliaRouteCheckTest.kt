package com.v2ray.ang.amelia

import com.v2ray.ang.amelia.AmeliaRouteCheck.Reason
import com.v2ray.ang.amelia.AmeliaRouteCheck.Route
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress

/** Uses the real Russia-only bases from the subscription server (test resources). */
class AmeliaRouteCheckTest {

    private fun res(name: String) = javaClass.getResourceAsStream("/amelia/$name")!!.readBytes()
    private val site = AmeliaRouteCheck.parseGeoSite(res("geosite_ru.bin"), "CATEGORY-RU")
    private val ip = AmeliaRouteCheck.parseGeoIp(res("geoip_ru.bin"), "RU")

    private fun noDns(h: String): List<InetAddress> = emptyList()
    private fun dns(addr: String): (String) -> List<InetAddress> = { listOf(InetAddress.getByName(addr)) }

    @Test
    fun basesParse() {
        assertNotNull(site)
        assertNotNull(ip)
        assertTrue(site!!.size > 100)
        assertTrue(ip!!.size > 1000)
        assertNull(AmeliaRouteCheck.parseGeoSite(res("geosite_ru.bin"), "NOPE"))
    }

    @Test
    fun normalize() {
        assertEquals("www.kinopoisk.ru", AmeliaRouteCheck.normalize(" https://www.Kinopoisk.ru:443/film/1?x=1 "))
        assertEquals("1.2.3.4", AmeliaRouteCheck.normalize("1.2.3.4:80"))
        assertNull(AmeliaRouteCheck.normalize("not a host"))
        assertNull(AmeliaRouteCheck.normalize(""))
    }

    @Test
    fun ruleOrderLikeTheCore() {
        // always via VPN wins even for a domain that is also Russian
        assertEquals(Reason.PROXY_SITE, AmeliaRouteCheck.classify("rr1.googlevideo.com", site, ip, ::noDns).reason)
        val kp = AmeliaRouteCheck.classify("hd.kinopoisk.ru", site, ip, ::noDns)
        assertEquals(Route.DIRECT, kp.route)
        assertEquals(Reason.DIRECT_SITE, kp.reason)
        assertEquals("kinopoisk.ru", kp.rule)
    }

    @Test
    fun russianSitesAndAddresses() {
        val ya = AmeliaRouteCheck.classify("yandex.ru", site, ip, ::noDns)
        assertEquals(Route.DIRECT, ya.route)
        assertEquals(Reason.RU_SITE, ya.reason)
        // 77.88.8.8 is Yandex DNS, a Russian address
        val byIp = AmeliaRouteCheck.classify("77.88.8.8", site, ip, ::noDns)
        assertEquals(Reason.RU_IP, byIp.reason)
        // unknown domain resolved to a Russian address goes direct (IPIfNonMatch)
        assertEquals(Reason.RU_IP, AmeliaRouteCheck.classify("example-shop.com", site, ip, dns("77.88.8.8")).reason)
    }

    @Test
    fun localAndDefault() {
        assertEquals(Reason.LOCAL_NETWORK, AmeliaRouteCheck.classify("192.168.1.1", site, ip, ::noDns).reason)
        val g = AmeliaRouteCheck.classify("example.com", site, ip, dns("93.184.216.34"))
        assertEquals(Route.VPN, g.route)
        assertEquals(Reason.DEFAULT, g.reason)
        assertEquals(Reason.BAD_INPUT, AmeliaRouteCheck.classify("??", site, ip, ::noDns).reason)
        // without the bases only the built-in lists apply
        assertEquals(Reason.DEFAULT, AmeliaRouteCheck.classify("yandex.ru", null, null, ::noDns).reason)
    }

    @Test
    fun generatedRulesIncludeSubscriptionDomainsAndKeepDomainPriority() {
        val profile = AmeliaRouteProfile.Profile(
            proxySites = listOf("full:secure.example.com", "regexp:^cdn[0-9]+[.]example[.]net$"),
            proxyIp = listOf("203.0.113.0/24"),
            directSites = listOf("example.com", "keyword:billing"),
        )
        val generated = AmeliaRouting.buildRulesets(false, profile)
        fun check(host: String) = AmeliaRouteCheck.classify(host, generated, { null }, { null }) {
            error("A domain rule already matched")
        }
        assertEquals(Route.DIRECT, check("www.example.com").route)
        assertEquals("example.com", check("www.example.com").siteRuleDomain)
        assertEquals(Route.VPN, check("secure.example.com").route)
        assertEquals(Route.VPN, check("cdn4.example.net").route)
        assertEquals(Route.DIRECT, check("my-billing.test").route)
    }

    @Test
    fun allResolvedAddressesAreCheckedBeforeMovingToTheNextIpRule() {
        val profile = AmeliaRouteProfile.Profile(
            proxyIp = listOf("203.0.113.0/24"),
            directIp = listOf("198.51.100.0/24"),
        )
        val generated = AmeliaRouting.buildRulesets(false, profile)
        val result = AmeliaRouteCheck.classify("example.com", generated, { null }, { null }) {
            listOf(InetAddress.getByName("198.51.100.1"), InetAddress.getByName("203.0.113.1"))
        }
        assertEquals(Route.VPN, result.route)
        assertEquals(Reason.PROXY_IP, result.reason)
        assertEquals("203.0.113.1", result.ip)
        val direct = AmeliaRouteCheck.classify("198.51.100.1", generated, { null }, { null }, ::noDns)
        assertEquals(Reason.DIRECT_IP, direct.reason)
        assertNull(direct.siteRuleDomain)
    }

    @Test
    fun unavailableDataIsReportedWithoutGuessingAndServiceHostIsProtected() {
        val profile = AmeliaRouteProfile.Profile(proxySites = listOf("geosite:category-ru"))
        val generated = AmeliaRouting.buildRulesets(true, profile)
        val unknown = AmeliaRouteCheck.classify("example.net", generated, { null }, { null }, ::noDns)
        assertEquals(Reason.UNAVAILABLE, unknown.reason)
        assertNull(unknown.route)
        assertEquals("example.net", unknown.siteRuleDomain)
        val protected = AmeliaRouteCheck.classify(
            "sub.ameliajustamelia.com", generated, { null }, { null }, ::noDns,
        )
        assertEquals(Route.VPN, protected.route)
        assertNull(protected.siteRuleDomain)

        val dnsFailure = AmeliaRouteCheck.classify(
            "example.net", AmeliaRouting.buildRulesets(false), { null }, { null }, { null },
        )
        assertEquals(Reason.UNAVAILABLE, dnsFailure.reason)
        assertEquals("example.net", dnsFailure.siteRuleDomain)
    }
}
