package com.v2ray.ang.amelia

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 1.8.2: "Вы на резервной локации" with "Вернуться на обычную", and "Pick/…" in the User-Agent. */
class Amelia182Test {

    private val reserve = "🆘 Резервный — только Telegram"
    private val moscow = "Москва 🇷🇺"
    private val regular = "Казахстан 1"

    // ---- the banner -----------------------------------------------------------------------

    @Test
    fun bannerOnReserveChosenByHandWithValidSubscription() {
        assertTrue(AmeliaReserve.showBackBanner(reserve, reserve, subActive = true))
    }

    @Test
    fun noBannerWhenReserveWasPickedByTheApp() {
        assertFalse(AmeliaReserve.showBackBanner(reserve, null, subActive = true))
        // the person chose another location by hand, the daemon is elsewhere now
        assertFalse(AmeliaReserve.showBackBanner(reserve, regular, subActive = true))
    }

    @Test
    fun noBannerAfterTheSubscriptionEnded() {
        // the red "Подписка закончилась" with "Продлить" stays instead
        assertFalse(AmeliaReserve.showBackBanner(reserve, reserve, subActive = false))
    }

    @Test
    fun noBannerOnMoscowOrRegular() {
        assertFalse(AmeliaReserve.showBackBanner(moscow, moscow, subActive = true))
        assertFalse(AmeliaReserve.showBackBanner(regular, regular, subActive = true))
        assertFalse(AmeliaReserve.showBackBanner(null, null, subActive = true))
        // a reserve location that exits in Moscow is Moscow: picked on purpose from abroad
        assertFalse(AmeliaReserve.showBackBanner("Резервный Москва 🇷🇺", "Резервный Москва 🇷🇺", subActive = true))
    }

    private fun ui(selected: String, manual: String?, expireSec: Long?) = AmeliaUiState(
        locations = listOf(AmeliaManager.Location("r", reserve), AmeliaManager.Location("k", regular)),
        selectedGuid = selected,
        manualName = manual,
        info = AmeliaSubInfo(expire = expireSec),
    )

    @Test
    fun screenStateUsesSelectionManualChoiceAndSubscription() {
        val future = System.currentTimeMillis() / 1000 + 86_400
        val past = System.currentTimeMillis() / 1000 - 86_400
        assertTrue(ui("r", reserve, future).onManualReserve)
        // no end date: the subscription counts as valid (AmeliaPolicy.subscriptionActive)
        assertTrue(ui("r", reserve, null).onManualReserve)
        assertFalse(ui("r", reserve, past).onManualReserve)
        assertFalse(ui("r", null, future).onManualReserve)
        assertFalse(ui("k", regular, future).onManualReserve)
        // after "Вернуться на обычную": the app picked a regular one, nothing chosen by hand
        assertFalse(ui("k", null, future).onManualReserve)
    }

    @Test
    fun journalReason() {
        assertEquals("вернулись с резервной по кнопке", AmeliaReserve.BACK_REASON)
    }

    // ---- User-Agent -----------------------------------------------------------------------

    @Test
    fun userAgentKeepsItsStartAndEndsWithThePick() {
        val manual = AmeliaVersions.userAgentOf("1.8.2", "2.3.11", "26.10.31", manual = true)
        val auto = AmeliaVersions.userAgentOf("1.8.2", "2.3.11", "26.10.31", manual = false)
        assertEquals("AmeliaNet/1.8.2 v2rayNG/2.3.11 Xray/26.10.31 Pick/manual", manual)
        assertEquals("AmeliaNet/1.8.2 v2rayNG/2.3.11 Xray/26.10.31 Pick/auto", auto)
        for (ua in listOf(manual, auto)) {
            // the bot counts installations by the start and reads the core from "Xray/<version>"
            assertTrue(ua.startsWith("AmeliaNet/1.8.2 "))
            assertTrue(Regex("""(^| )Xray/26\.10\.31( |$)""").containsMatchIn(ua))
            assertEquals(1, Regex("""Pick/""").findAll(ua).count())
        }
        assertTrue(manual.endsWith(" Pick/manual"))
        assertTrue(auto.endsWith(" Pick/auto"))
    }

    @Test
    fun userAgentFitsTheServersLimitAndKeepsThePick() {
        val long = "9".repeat(500)
        val ua = AmeliaVersions.userAgentOf(long, long, long, manual = true)
        assertTrue(ua.length <= AmeliaVersions.MAX_USER_AGENT, "length ${ua.length}")
        assertEquals(200, AmeliaVersions.MAX_USER_AGENT)
        assertTrue(ua.startsWith("AmeliaNet/"))
        assertTrue(ua.endsWith(" Pick/manual"))
        assertTrue(ua.contains(" Xray/"))
    }
}
