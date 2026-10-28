package com.v2ray.ang.amelia

import com.v2ray.ang.amelia.AmeliaWidget.Button
import com.v2ray.ang.amelia.AmeliaWidget.Status
import com.v2ray.ang.amelia.AmeliaWidget.Tap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 1.5.8: the home-screen widget and "Тарифы и оплата" on the key screen. */
class Amelia158Test {

    private val now = 1_800_000_000_000L
    private val active = AmeliaSubInfo(expire = now / 1000 + 10 * 24 * 3600)
    private val ended = AmeliaSubInfo(expire = now / 1000 - 60)

    private fun tap(
        expired: Boolean = false,
        busy: Boolean = false,
        vpnPermission: Boolean = true,
        hasLocation: Boolean = true,
        updateRequired: Boolean = false,
    ) = AmeliaWidget.tap(expired, busy, vpnPermission, hasLocation, updateRequired)

    // ---- widget: the button ---------------------------------------------------------------

    @Test
    fun tapWithVpnOffConnects() {
        assertEquals(Tap.START, tap())
    }

    @Test
    fun tapWithVpnOnOrConnectingDisconnects() {
        assertEquals(Tap.STOP, tap(busy = true))
    }

    @Test
    fun noVpnPermissionOpensTheApp() {
        // like the tile: Android asks for the permission in the app, never a silent nothing
        assertEquals(Tap.OPEN_APP, tap(vpnPermission = false))
        assertEquals(
            AmeliaTile.onTap(running = false, vpnPermission = false, hasLocation = true, updateRequired = false).name,
            tap(vpnPermission = false).name,
        )
    }

    @Test
    fun noLocationOrRequiredUpdateOpensTheApp() {
        assertEquals(Tap.OPEN_APP, tap(hasLocation = false))
        assertEquals(Tap.OPEN_APP, tap(updateRequired = true))
    }

    @Test
    fun endedSubscriptionRenews() {
        assertEquals(Tap.RENEW, tap(expired = true))
        assertEquals(Tap.RENEW, tap(expired = true, vpnPermission = false))
        assertEquals(Tap.RENEW, tap(expired = true, busy = true))
    }

    @Test
    fun endedSubscriptionShowsRenewInsteadOfTheButton() {
        val m = AmeliaWidget.model(Status.OFF, "Нидерланды 🇳🇱", ended, now) { "x" }
        assertEquals(Button.RENEW, m.button)
        assertTrue(m.expired)
        assertNull(m.until, "no \"Подписка до\" for an ended subscription")
        assertEquals(Button.RENEW, AmeliaWidget.model(Status.CONNECTED, "Только Telegram", ended, now) { "x" }.button)
    }

    @Test
    fun buttonFollowsTheState() {
        assertEquals(Button.CONNECT, AmeliaWidget.model(Status.OFF, "A", active, now) { "x" }.button)
        assertEquals(Button.DISCONNECT, AmeliaWidget.model(Status.CONNECTED, "A", active, now) { "x" }.button)
        assertEquals(Button.DISCONNECT, AmeliaWidget.model(Status.CONNECTING, "A", active, now) { "x" }.button)
        // unknown subscription info is not "ended" (same rule as everywhere)
        assertEquals(Button.CONNECT, AmeliaWidget.model(Status.OFF, "A", null, now) { "x" }.button)
    }

    @Test
    fun subscriptionLineOnlyWhenTheEndIsKnown() {
        val m = AmeliaWidget.model(Status.OFF, "A", active, now) { "05.11" }
        assertEquals("05.11", m.until)
        assertFalse(m.expired)
        assertNull(AmeliaWidget.model(Status.OFF, "A", AmeliaSubInfo(expire = 0), now) { "05.11" }.until)
        assertNull(AmeliaWidget.model(Status.OFF, "A", null, now) { "05.11" }.until)
    }

    @Test
    fun locationAsTheAppShowsIt() {
        assertEquals("Нидерланды 🇳🇱", AmeliaWidget.model(Status.OFF, "Нидерланды 🇳🇱", active, now) { "" }.location)
        assertNull(AmeliaWidget.model(Status.OFF, " ", active, now) { "" }.location)
        assertNull(AmeliaWidget.model(Status.OFF, null, active, now) { "" }.location)
    }

    // ---- widget: the state line -------------------------------------------------------------

    @Test
    fun stateLine() {
        assertEquals(Status.OFF, AmeliaWidget.status(running = false, phase = null, connectingSince = 0, now = now))
        assertEquals(Status.CONNECTED, AmeliaWidget.status(true, AmeliaDaemon.Phase.CONNECTED.name, 0, now))
        assertEquals(Status.CONNECTED, AmeliaWidget.status(true, null, 0, now))
        // the daemon still checks the new connection or switches location
        assertEquals(Status.CONNECTING, AmeliaWidget.status(true, AmeliaDaemon.Phase.CHECKING.name, 0, now))
        assertEquals(Status.CONNECTING, AmeliaWidget.status(true, AmeliaDaemon.Phase.SWITCHING.name, 0, now))
    }

    @Test
    fun connectingAfterTapUntilTheServiceAnswersOrTimesOut() {
        assertEquals(Status.CONNECTING, AmeliaWidget.status(false, null, now - 1_000, now))
        assertEquals(Status.OFF, AmeliaWidget.status(false, null, now - AmeliaWidget.CONNECTING_MS, now))
        // a clock moved back is not "connecting forever"
        assertEquals(Status.OFF, AmeliaWidget.status(false, null, now + 60_000, now))
    }

    // ---- key screen: "Тарифы и оплата" ----------------------------------------------------------

    private val main = "https://msk2.ameliajustamelia.com:9443"
    private val spare = "https://msk1.ameliajustamelia.com:9443"

    @Test
    fun keyScreenWithTelegramAsks() {
        assertEquals(AmeliaSite.KeyPay.ASK, AmeliaSite.keyPay(telegramInstalled = true))
        // "Купить в Telegram-боте" stays the bot's pay screen
        assertEquals("https://t.me/amelianet_bot?start=pay", AmeliaConfig.botUrl(AmeliaConfig.BotScreen.PAY))
    }

    @Test
    fun keyScreenWithoutTelegramGoesToTheSite() {
        assertEquals(AmeliaSite.KeyPay.SITE, AmeliaSite.keyPay(telegramInstalled = false))
    }

    @Test
    fun shopOnMainThenSpare() {
        assertEquals("$main/shop", AmeliaSite.shopUrl(AmeliaSite.pickBase(listOf(true, false, false))))
        assertEquals("$spare/shop", AmeliaSite.shopUrl(AmeliaSite.pickBase(listOf(false, false, false))))
    }
}
