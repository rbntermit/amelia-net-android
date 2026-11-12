package com.v2ray.ang.amelia

import com.google.gson.JsonParser
import com.v2ray.ang.AppConfig
import com.v2ray.ang.amelia.AmeliaServices.Status
import com.v2ray.ang.amelia.AmeliaSiteCheck.Outcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 1.10.0: "Проверка сервисов", "Сайт не открывается" (/app/sitecheck), "Российские сайты — напрямую",
 * "Как у меня".
 */
class Amelia1100Test {

    // ---- "Проверка сервисов" ---------------------------------------------------------------------

    @Test
    fun theServicesInPlainNames() {
        assertEquals(listOf("YouTube", "Gemini", "ChatGPT", "Instagram", "Telegram", "Discord"), AmeliaServices.SERVICES.map { it.name })
        assertEquals("https://www.youtube.com/generate_204", AmeliaServices.SERVICES.first().url)
        assertTrue(AmeliaServices.SERVICES.all { it.url.startsWith("https://") })
        assertEquals(listOf("Gemini"), AmeliaServices.SERVICES.filter { it.gemini }.map { it.name })
        assertEquals(8_000L, AmeliaServices.TIMEOUT_MS)
    }

    @Test
    fun opensOrNot() {
        assertEquals(Status.OK, AmeliaServices.status(204))
        assertEquals(Status.OK, AmeliaServices.status(200))
        // a page that answers at all is reachable, even "forbidden" or "not found"
        assertEquals(Status.OK, AmeliaServices.status(403))
        assertEquals(Status.OK, AmeliaServices.status(404))
        // server errors, timeouts and connection errors are not
        assertEquals(Status.FAIL, AmeliaServices.status(500))
        assertEquals(Status.FAIL, AmeliaServices.status(503))
        assertEquals(Status.FAIL, AmeliaServices.status(null))
    }

    @Test
    fun geminiNotInThisCountry() {
        val gemini = AmeliaServices.SERVICES.first { it.gemini }
        // a redirect to the page about where Gemini works
        assertTrue(AmeliaServices.geminiBlocked("https://support.google.com/gemini/answer/13575153?hl=ru", null))
        assertEquals(Status.NOT_IN_COUNTRY, AmeliaServices.judge(gemini, 200, "https://gemini.google.com/faq#location", ""))
        // the text on the page, in English or in Russian
        assertTrue(AmeliaServices.geminiBlocked("https://gemini.google.com/", "<p>Gemini isn’t currently supported in your country.</p>"))
        assertTrue(AmeliaServices.geminiBlocked("https://gemini.google.com/", "Gemini НЕДОСТУПЕН в вашей стране"))
        // a normal page: just ✅
        assertFalse(AmeliaServices.geminiBlocked("https://gemini.google.com/app", "<title>Gemini</title> Chat with Gemini"))
        assertEquals(Status.OK, AmeliaServices.judge(gemini, 200, "https://gemini.google.com/app", "<title>Gemini</title>"))
        // nothing to read: ✅/❌ by the answer only
        assertFalse(AmeliaServices.geminiBlocked(null, null))
        assertEquals(Status.FAIL, AmeliaServices.judge(gemini, null, null, null))
        assertEquals(Status.FAIL, AmeliaServices.judge(gemini, 502, "https://gemini.google.com/", "bad gateway"))
        // only Gemini is read like that
        val chatgpt = AmeliaServices.SERVICES.first { it.name == "ChatGPT" }
        assertEquals(Status.OK, AmeliaServices.judge(chatgpt, 200, "https://chatgpt.com/", "not available in your country"))
    }

    // ---- "Сайт не открывается" ------------------------------------------------------------------

    @Test
    fun theDomainIsCleanedAsOnTheServer() {
        assertEquals("gosuslugi.ru", AmeliaSiteCheck.cleanDomain("https://www.gosuslugi.ru/help?x=1#top"))
        assertEquals("ozon.ru", AmeliaSiteCheck.cleanDomain("  OZON.ru  "))
        assertEquals("lk.sber.ru", AmeliaSiteCheck.cleanDomain("http://user:pass@lk.sber.ru:8443/login"))
        assertEquals("example.com", AmeliaSiteCheck.cleanDomain("www.www.example.com."))
        assertEquals("президент.рф", AmeliaSiteCheck.cleanDomain("https://президент.рф/"))
        assertEquals("xn--d1abbgf6aiiy.xn--p1ai", AmeliaSiteCheck.cleanDomain("xn--d1abbgf6aiiy.xn--p1ai"))
        // not a site
        listOf(null, "", "привет", "youtube", "a b.com", "-bad.com", "bad-.com", "1.2.3.4", "..", "x.", "http://").forEach {
            assertNull(AmeliaSiteCheck.cleanDomain(it), "$it")
        }
    }

    @Test
    fun theBodyHasNoKeyAndNoAddress() {
        val json = JsonParser.parseString(
            AmeliaSiteCheck.body("iid-1", "gosuslugi.ru", vpn = true, location = "Нидерланды 1 🇳🇱", app = "1.10.0"),
        ).asJsonObject
        assertEquals(setOf("iid", "d", "app", "vpn", "loc"), json.keySet())
        assertEquals("iid-1", json["iid"].asString)
        assertEquals("gosuslugi.ru", json["d"].asString)
        assertEquals("1.10.0", json["app"].asString)
        assertTrue(json["vpn"].asBoolean)
        assertEquals("Нидерланды 1 🇳🇱", json["loc"].asString)
        // no location yet: an empty one
        assertEquals("", JsonParser.parseString(AmeliaSiteCheck.body("i", "a.ru", false, null, "1")).asJsonObject["loc"].asString)
    }

    @Test
    fun whatThePersonIsTold() {
        assertEquals(Outcome.SENT, AmeliaSiteCheck.outcome(200, """{"ok":true}"""))
        assertEquals(Outcome.NOT_A_SITE, AmeliaSiteCheck.outcome(400, """{"ok":false,"err":"bad domain"}"""))
        assertEquals(Outcome.FAILED, AmeliaSiteCheck.outcome(null, null))
        assertEquals(Outcome.FAILED, AmeliaSiteCheck.outcome(200, """{"ok":false}"""))
        assertEquals(Outcome.FAILED, AmeliaSiteCheck.outcome(500, null))
        assertEquals(Outcome.FAILED, AmeliaSiteCheck.outcome(200, "<html>"))
    }

    @Test
    fun sentToTheMainDomainThenNet() {
        assertEquals("/app/sitecheck", AmeliaSiteCheck.PATH)
        assertEquals(listOf("https://sub.ameliajustamelia.com:9443", "https://sub.ameliajustamelia.net:9443"), AmeliaSiteCheck.BASES)
        // the same install id as /app/crash, and no key
        val src = File(javaDir(), "com/v2ray/ang/amelia/AmeliaSiteCheck.kt").readText()
        assertTrue("AmeliaProbe.installId()" in src)
        assertFalse("getKey" in src)
    }

    // ---- "Российские сайты — напрямую" ------------------------------------------------------------

    private fun direct(rules: List<com.v2ray.ang.dto.entities.RulesetItem>) =
        rules.filter { it.outboundTag == AppConfig.TAG_DIRECT }.flatMap { it.domain.orEmpty() + it.ip.orEmpty() }

    @Test
    fun onByDefaultTheRulesAreAsBefore() {
        val before = AmeliaRouting.buildRulesets(withGeo = true)
        assertEquals(before, AmeliaRouting.buildRulesets(withGeo = true, ruDirect = true))
        assertTrue(AmeliaRouting.GEOSITE_RU in direct(before))
        assertTrue(AmeliaRouting.GEOIP_RU in direct(before))
        // on by default
        val src = File(javaDir(), "com/v2ray/ang/amelia/AmeliaRouting.kt").readText()
        assertTrue("decodeSettingsBool(PREF_RU_DIRECT, true)" in src)
    }

    @Test
    fun offTheRussianListsLeaveDirectOnly() {
        val profile = AmeliaRouteProfile.Profile(
            directSites = listOf("geosite:category-ru", "notes.ameliajustamelia.net", "example.ru"),
            directIp = listOf("geoip:ru", "203.0.113.0/24"),
            proxySites = listOf("chatgpt.com"),
        )
        val has: (String) -> Boolean = { true }
        val on = AmeliaRouting.buildRulesets(true, profile, has, ruDirect = true)
        val off = AmeliaRouting.buildRulesets(true, profile, has, ruDirect = false)
        // gone: geosite:category-ru and geoip:ru, also from the subscription's own lists
        assertTrue(direct(off).none { AmeliaRouting.isRussianList(it) })
        assertEquals(direct(on).filterNot { AmeliaRouting.isRussianList(it) }, direct(off))
        // kept: local networks, built-in and subscription sites, our own domains
        assertTrue(direct(off).containsAll(AmeliaRouting.DIRECT_IPS))
        assertTrue("domain:notes.ameliajustamelia.net" in direct(off))
        assertTrue("domain:kinopoisk.ru" in direct(off))
        assertTrue("203.0.113.0/24" in direct(off))
        // "always through the VPN" untouched, our foreign domains first
        assertEquals(on.first(), off.first())
        assertTrue("domain:sub.ameliajustamelia.com" in off.first().domain.orEmpty())
        assertTrue("domain:chatgpt.com" in off.first().domain.orEmpty())
        // without geo bases nothing changes either way
        assertEquals(AmeliaRouting.buildRulesets(false, ruDirect = true), AmeliaRouting.buildRulesets(false, ruDirect = false))
    }

    @Test
    fun russianListsAreKnownInAnySpelling() {
        assertTrue(AmeliaRouting.isRussianList("geosite:category-ru"))
        assertTrue(AmeliaRouting.isRussianList("GEOIP:RU"))
        assertTrue(AmeliaRouting.isRussianList("geosite:category-ru@cn"))
        assertFalse(AmeliaRouting.isRussianList("geosite:category-ru-gov"))
        assertFalse(AmeliaRouting.isRussianList("geoip:private"))
    }

    // ---- "Как у меня" -----------------------------------------------------------------------------

    @Test
    fun theLastCheckOfEveryLocation() {
        val now = 1_800_000_000_000L
        fun e(name: String, ms: Long) = name to AmeliaChecks.Entry(name, ms = ms, ts = now)
        val store = AmeliaChecks.Store(
            entries = mapOf(e("Нидерланды 1", 80), e("Финляндия 1", -1), e("Казахстан 1", 120), e("Старая", 50)),
            lastFullAt = now,
        )
        // only the locations there are now; one never checked does not count
        val s = AmeliaChecks.summary(store, listOf("Нидерланды 1", "Финляндия 1", "Казахстан 1", "Новая"))!!
        assertEquals(AmeliaChecks.Summary(now, working = 2, checked = 3), s)
        // never checked at all: "ещё не проверялись"
        assertNull(AmeliaChecks.summary(AmeliaChecks.Store(), listOf("Нидерланды 1")))
    }

    private fun resDir(): File =
        listOf(File("src/main/res"), File("app/src/main/res"), File("V2rayNG/app/src/main/res")).first { it.isDirectory }

    private fun javaDir(): File = File(resDir(), "../java")
}
