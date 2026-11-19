package com.v2ray.ang.amelia

import com.v2ray.ang.amelia.AmeliaPolicy.Mode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** 1.5.7: one-time move to "Всегда"; the site's cabinet, payment and gift links. */
class Amelia157Test {

    /** What AmeliaPolicy.mode() does with MMKV, on a plain map. */
    private class Store(var mode: String?, var migrated: Boolean = false) {
        fun start(): Mode {
            if (!migrated) {
                mode = AmeliaPolicy.migrate157(mode, done = false)
                migrated = true
            }
            return AmeliaPolicy.modeOf(mode)
        }
    }

    @Test
    fun unlessManualFrom156BecomesAlwaysOnce() {
        val s = Store(Mode.UNLESS_MANUAL.name)
        assertEquals(Mode.ALWAYS, s.start())
        // chosen by hand after the move: kept on every next start
        s.mode = Mode.UNLESS_MANUAL.name
        assertEquals(Mode.UNLESS_MANUAL, s.start())
        assertEquals(Mode.UNLESS_MANUAL, s.start())
    }

    @Test
    fun offAndOthersAreNotTouched() {
        assertEquals(Mode.OFF, Store(Mode.OFF.name).start())
        assertEquals(Mode.ALWAYS, Store(Mode.ALWAYS.name).start())
        assertEquals(Mode.ALWAYS, Store(null).start())
        assertEquals(Mode.UNLESS_MANUAL.name, AmeliaPolicy.migrate157(Mode.UNLESS_MANUAL.name, done = true))
    }

    private val main = "https://msk2.ameliajustamelia.com:9443"
    private val spare = "https://msk1.ameliajustamelia.com:9443"

    @Test
    fun eachSitePageOpensTheMatchingBotScreen() {
        assertEquals("https://t.me/amelianet_bot?start=pay", AmeliaConfig.botUrl(AmeliaSite.Page.PAYMENT.botScreen))
        assertEquals("https://t.me/amelianet_bot?start=mysub", AmeliaConfig.botUrl(AmeliaSite.Page.CABINET.botScreen))
    }

    @Test
    fun siteLinksUseTheTemporaryToken() {
        val token = AmeliaSite.parseToken(
            main,
            """{"ok":true,"t":"t.K-9_3","me":"/me/t.K-9_3","buy":"/buy?sid=t.K-9_3"}""",
            0L,
        )!!
        assertEquals("$main/me/t.K-9_3", token.url(AmeliaSite.Page.CABINET))
        assertEquals("$main/buy?sid=t.K-9_3", token.url(AmeliaSite.Page.PAYMENT))
        assertEquals("$main/buy?gift=1", AmeliaSite.giftUrl(main))
    }

    @Test
    fun mainAddressThenSpare() {
        assertEquals(main, AmeliaSite.pickBase(listOf(true, true, true)))
        assertEquals(spare, AmeliaSite.pickBase(listOf(false, true, true)))
    }
}
