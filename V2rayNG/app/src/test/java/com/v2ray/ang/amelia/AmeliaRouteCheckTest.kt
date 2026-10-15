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
}
