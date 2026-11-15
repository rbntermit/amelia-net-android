package com.v2ray.ang.amelia

import com.v2ray.ang.AppConfig
import com.v2ray.ang.amelia.AmeliaRouting.SiteRule
import com.v2ray.ang.amelia.AmeliaServices.Status
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class Amelia1110Test {
    @Test
    fun httpErrorsNeverClaimSuccessfulAccess() {
        (200..299).forEach { assertEquals(Status.OK, AmeliaServices.status(it)) }
        listOf(401, 403, 451).forEach { assertEquals(Status.DENIED, AmeliaServices.status(it)) }
        assertEquals(Status.RATE_LIMITED, AmeliaServices.status(429))
        listOf(301, 302, 304, 404, 408, 418).forEach {
            assertEquals(Status.UNCONFIRMED, AmeliaServices.status(it))
        }
        listOf(null, 0, 100, 199, 500, 503, 599, 600).forEach {
            assertEquals(Status.FAIL, AmeliaServices.status(it))
        }
    }

    @Test
    fun onlyExplicitEvidenceMeansUnsupportedCountry() {
        val gemini = AmeliaServices.SERVICES.first { it.gemini }
        assertEquals(Status.DENIED, AmeliaServices.judge(gemini, 403, gemini.url, "Access denied"))
        assertEquals(Status.RATE_LIMITED, AmeliaServices.judge(gemini, 429, gemini.url, "Slow down"))
        assertEquals(Status.NOT_IN_COUNTRY, AmeliaServices.judge(gemini, 403, gemini.url, "Not available in your country"))
        assertEquals(Status.FAIL, AmeliaServices.judge(gemini, 503, gemini.url, "Not available in your country"))
    }

    @Test
    fun siteInputDropsCredentialsPathsAndUsesAsciiDomains() {
        assertEquals("example.com", AmeliaRouting.siteDomain(" HTTPS://user:secret@WWW.Example.com:443/path?q=secret#x "))
        assertEquals("shop.example.com", AmeliaRouting.siteDomain("shop.example.com."))
        assertEquals("xn--d1abbgf6aiiy.xn--p1ai", AmeliaRouting.siteDomain("https://президент.рф/"))
        listOf("", " ", "hello", "1.2.3.4", "[::1]", "a b.com", "-x.com", "a..com", "geosite:ru").forEach {
            assertNull(AmeliaRouting.siteDomain(it), it)
        }
    }

    @Test
    fun createChangeDeleteAndReloadRules() {
        assertTrue(AmeliaRouting.parseSiteRules(null).isEmpty())
        val first = AmeliaRouting.changeSiteRules(emptyList(), "example.com", true)
        val second = AmeliaRouting.changeSiteRules(first, "example.com", false)
        assertEquals(listOf(SiteRule("example.com", false)), second)
        val several = AmeliaRouting.changeSiteRules(second, "shop.example.com", true)
        assertEquals(several, AmeliaRouting.parseSiteRules(AmeliaRouting.siteRulesJson(several)))
        assertFalse(AmeliaRouting.siteRulesJson(several).contains("secret"))
        assertEquals(listOf(SiteRule("shop.example.com", true)), AmeliaRouting.changeSiteRules(several, "example.com", null))
        assertTrue(AmeliaRouting.changeSiteRules(first, "example.com", null).isEmpty())
    }

    @Test
    fun malformedSavedDataIsNotSilentlyOverwritten() {
        listOf("not json", "[]", "{\"example.com\":\"false\"}", "{\"bad domain\":true}").forEach { json ->
            assertThrows(Exception::class.java) { AmeliaRouting.parseSiteRules(json) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            AmeliaRouting.changeSiteRules(emptyList(), "EXAMPLE.COM", true)
        }
    }

    @Test
    fun subdomainWinsAndDoesNotMatchUnrelatedSuffixes() {
        val rules = listOf(SiteRule("example.com", false), SiteRule("shop.example.com", true))
        assertEquals(false, AmeliaRouting.personalSiteRule("www.example.com", rules)?.viaVpn)
        assertEquals(true, AmeliaRouting.personalSiteRule("img.shop.example.com", rules)?.viaVpn)
        assertNull(AmeliaRouting.personalSiteRule("notexample.com", rules))
        assertNull(AmeliaRouting.personalSiteRule("example.com.evil.org", rules))
    }

    @Test
    fun personalRulesPrecedeBuiltInAndRussianListsInCoreAndChecker() {
        val personal = listOf(SiteRule("kinopoisk.ru", true), SiteRule("youtube.com", false))
        val built = AmeliaRouting.buildRulesets(true, siteRules = personal)
        assertEquals(AppConfig.TAG_PROXY, built.first { "domain:kinopoisk.ru" in it.domain.orEmpty() }.outboundTag)
        assertEquals(AppConfig.TAG_DIRECT, built.first { "domain:youtube.com" in it.domain.orEmpty() }.outboundTag)
        for (rule in personal) {
            val result = AmeliaRouteCheck.classify(rule.domain, null, null, personal) { error("DNS should not run") }
            assertEquals(if (rule.viaVpn) AmeliaRouteCheck.Route.VPN else AmeliaRouteCheck.Route.DIRECT, result.route)
            assertEquals(AmeliaRouteCheck.Reason.PERSONAL, result.reason)
        }
    }

    @Test
    fun coreAndCheckerUseTheSameMostSpecificRule() {
        val personal = listOf(SiteRule("example.com", true), SiteRule("shop.example.com", false))
        val native = AmeliaRouting.buildRulesets(false, siteRules = personal)
        val host = "img.shop.example.com"
        val matched = native.first { rule -> rule.domain.orEmpty().any {
            it.startsWith("domain:") && AmeliaRouteCheck.domainMatches(it.removePrefix("domain:"), host)
        } }
        assertEquals(AppConfig.TAG_DIRECT, matched.outboundTag)
        assertEquals(AmeliaRouteCheck.Route.DIRECT, AmeliaRouteCheck.classify(host, null, null, personal) { emptyList() }.route)
    }

    @Test
    fun deletingTheExceptionRestoresOriginalRouting() {
        val personal = AmeliaRouting.changeSiteRules(listOf(SiteRule("youtube.com", false)), "youtube.com", null)
        assertEquals(AmeliaRouting.buildRulesets(true), AmeliaRouting.buildRulesets(true, siteRules = personal))
        assertEquals(AmeliaRouteCheck.Reason.PROXY_SITE, AmeliaRouteCheck.classify("youtube.com", null, null, personal) { emptyList() }.reason)
    }

    @Test
    fun serviceEndpointStaysInTunnelEvenWithParentException() {
        val personal = listOf(SiteRule("ameliajustamelia.com", false))
        assertNull(AmeliaRouting.personalSiteRule("sub.ameliajustamelia.com", personal))
        assertEquals(AmeliaRouteCheck.Route.VPN, AmeliaRouteCheck.classify("sub.ameliajustamelia.com", null, null, personal) { emptyList() }.route)
        val native = AmeliaRouting.buildRulesets(false, siteRules = personal)
        assertEquals(AppConfig.TAG_PROXY, native.first().outboundTag)
        assertTrue("domain:sub.ameliajustamelia.com" in native.first().domain.orEmpty())
    }

    @Test
    fun initialScreenDoesNotPretendRulesWereLoaded() {
        val state = AmeliaUiState()
        assertFalse(state.siteRulesReady)
        assertFalse(state.siteRulesBusy)
        assertNull(state.siteRulesError)
        assertTrue(state.siteRules.isEmpty())
    }
}
