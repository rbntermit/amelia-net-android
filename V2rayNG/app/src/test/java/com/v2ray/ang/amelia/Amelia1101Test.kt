package com.v2ray.ang.amelia

import com.v2ray.ang.amelia.AmeliaSiteCheck.WithReport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 1.10.1: a site typed in "Адрес сайта" and sent with "Отправить в поддержку" was lost in 1.10.0 (twice on
 * 13.11): only the report went. Now the report carries "Не открывается сайт: <домен>" and /app/sitecheck goes
 * too, once per window.
 */
class Amelia1101Test {

    @Test
    fun emptyFieldOnlyTheReport() {
        assertEquals(WithReport(null, sendSiteCheck = false), AmeliaSiteCheck.withReport("", sentByButton = null))
        assertEquals(WithReport(null, sendSiteCheck = false), AmeliaSiteCheck.withReport("   ", sentByButton = null))
        assertEquals(WithReport(null, sendSiteCheck = false), AmeliaSiteCheck.withReport(null, sentByButton = null))
        // the comment stays as typed
        assertEquals("не грузит", AmeliaSiteCheck.reportComment("не грузит", null))
    }

    @Test
    fun filledFieldGoesWithTheReport() {
        val w = AmeliaSiteCheck.withReport("https://www.gosuslugi.ru/help", sentByButton = null)
        assertEquals(WithReport("gosuslugi.ru", sendSiteCheck = true), w)
        // the same cleaned domain in the report, first, the person's comment after it
        assertEquals("Не открывается сайт: gosuslugi.ru\nдолго грузится", AmeliaSiteCheck.reportComment("  долго грузится ", w.domain))
        assertEquals("Не открывается сайт: gosuslugi.ru", AmeliaSiteCheck.reportComment("", w.domain))
        // a long comment is cut, the site line never
        val long = AmeliaSiteCheck.reportComment("я".repeat(1000), w.domain)
        assertEquals(AmeliaReport.MAX_COMMENT, long.length)
        assertTrue(long.startsWith("Не открывается сайт: gosuslugi.ru\n"))
    }

    @Test
    fun alreadySentByTheButtonIsNotSentAgain() {
        // "Проверить этот сайт" took gosuslugi.ru in this window: the report still names it, no second /app/sitecheck
        assertEquals(WithReport("gosuslugi.ru", sendSiteCheck = false), AmeliaSiteCheck.withReport("gosuslugi.ru", sentByButton = "gosuslugi.ru"))
        assertEquals(WithReport("gosuslugi.ru", sendSiteCheck = false), AmeliaSiteCheck.withReport("http://GOSUSLUGI.ru/", sentByButton = "gosuslugi.ru"))
        // another site typed after that: it goes
        assertEquals(WithReport("ozon.ru", sendSiteCheck = true), AmeliaSiteCheck.withReport("ozon.ru", sentByButton = "gosuslugi.ru"))
    }

    @Test
    fun notASiteTheReportStillGoes() {
        assertEquals(WithReport(null, sendSiteCheck = false, notASite = true), AmeliaSiteCheck.withReport("привет", sentByButton = null))
        assertEquals("ничего", AmeliaSiteCheck.reportComment("ничего", null))
    }

    @Test
    fun theReportButtonUsesTheField() {
        val vm = File(javaDir(), "com/v2ray/ang/amelia/AmeliaViewModel.kt").readText()
        val send = vm.substringAfter("fun sendReport(comment: String) {").substringBefore("fun closeReport()")
        assertTrue("AmeliaSiteCheck.withReport(_state.value.siteInput, siteSentDomain)" in send)
        assertTrue("AmeliaReport.send(app, key, r.servers, active, text," in send)
        assertTrue("AmeliaSiteCheck.send(app, domain," in send)
        // the button under the field says what it does
        val strings = File(resDir(), "values/amelia_strings.xml").readText()
        assertTrue("<string name=\"amelia_site_check\">Проверить этот сайт</string>" in strings)
    }

    private fun resDir(): File =
        listOf(File("src/main/res"), File("app/src/main/res"), File("V2rayNG/app/src/main/res")).first { it.isDirectory }

    private fun javaDir(): File = File(resDir(), "../java")
}
