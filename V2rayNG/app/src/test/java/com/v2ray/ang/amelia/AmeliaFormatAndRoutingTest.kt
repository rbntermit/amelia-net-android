package com.v2ray.ang.amelia

import com.v2ray.ang.AppConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AmeliaFormatAndRoutingTest {

    @Test
    fun formatsBytes() {
        assertEquals("0" to 0, AmeliaFormat.bytes(0))
        assertEquals("1,5" to 1, AmeliaFormat.bytes(1536))
        assertEquals("1" to 3, AmeliaFormat.bytes(1024L * 1024 * 1024))
        assertEquals("47" to 3, AmeliaFormat.bytes(47L * 1024 * 1024 * 1024))
        assertEquals("150" to 3, AmeliaFormat.bytes(150L * 1024 * 1024 * 1024))
    }

    @Test
    fun expiry() {
        val info = AmeliaSubInfo(expire = 1000)
        assertTrue(AmeliaFormat.isExpired(info, 1_000_001))
        assertFalse(AmeliaFormat.isExpired(info, 999_999))
        assertFalse(AmeliaFormat.isExpired(AmeliaSubInfo(expire = 0), Long.MAX_VALUE))
    }

    @Test
    fun pingLevels() {
        assertEquals(AmeliaFormat.PingLevel.FAILED, AmeliaFormat.pingLevel(-1))
        assertEquals(AmeliaFormat.PingLevel.GOOD, AmeliaFormat.pingLevel(0))
        assertEquals(AmeliaFormat.PingLevel.GOOD, AmeliaFormat.pingLevel(299))
        assertEquals(AmeliaFormat.PingLevel.MEDIUM, AmeliaFormat.pingLevel(300))
        assertEquals(AmeliaFormat.PingLevel.MEDIUM, AmeliaFormat.pingLevel(799))
        assertEquals(AmeliaFormat.PingLevel.BAD, AmeliaFormat.pingLevel(800))
    }

    @Test
    fun daysLeft() {
        val day = 24L * 60 * 60 * 1000
        val now = 1_000_000_000_000L
        assertEquals(10, AmeliaFormat.daysLeft(AmeliaSubInfo(expire = (now + 10 * day + 5) / 1000), now))
        assertEquals(0, AmeliaFormat.daysLeft(AmeliaSubInfo(expire = (now + day / 2) / 1000), now))
        assertEquals(null, AmeliaFormat.daysLeft(AmeliaSubInfo(expire = (now - day) / 1000), now))
        assertEquals(null, AmeliaFormat.daysLeft(AmeliaSubInfo(expire = 0), now))
    }

    @Test
    fun routingOrderProxyBeforeDirect() {
        val rules = AmeliaRouting.buildRulesets(withGeo = false)
        assertEquals(listOf(AppConfig.TAG_PROXY, AppConfig.TAG_DIRECT, AppConfig.TAG_DIRECT), rules.map { it.outboundTag })
        assertTrue(rules[0].domain!!.contains("domain:googlevideo.com"))
        assertTrue(rules[1].domain!!.contains("domain:kinopoisk.ru"))
        assertTrue(rules[2].ip!!.contains("192.168.0.0/16"))
    }

    @Test
    fun geoRulesOnlyWhenFilesPresent() {
        val without = AmeliaRouting.buildRulesets(withGeo = false)
        assertFalse(without.any { r -> (r.domain.orEmpty() + r.ip.orEmpty()).any { it.startsWith("geo") } })

        val with = AmeliaRouting.buildRulesets(withGeo = true)
        assertTrue(with[1].domain!!.contains(AmeliaRouting.GEOSITE_RU))
        assertTrue(with[2].ip!!.contains(AmeliaRouting.GEOIP_RU))
        // Our geo bases have only the Russian segment: never reference private/cn lists
        assertFalse(with.any { r -> (r.domain.orEmpty() + r.ip.orEmpty()).any { it.endsWith(":private") || it.endsWith(":cn") } })
    }
}
