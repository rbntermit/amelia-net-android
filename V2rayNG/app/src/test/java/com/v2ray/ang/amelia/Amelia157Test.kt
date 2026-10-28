package com.v2ray.ang.amelia

import com.v2ray.ang.amelia.AmeliaPolicy.Mode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.URI

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

    private val key = "a1B2-c3D4_e5"
    private val main = "https://msk2.ameliajustamelia.com:9443"
    private val spare = "https://msk1.ameliajustamelia.com:9443"

    @Test
    fun renewWithAndWithoutTelegram() {
        assertEquals("https://t.me/amelianet_bot?start=pay", AmeliaSite.renewUrl(telegramInstalled = true, base = main, key = key))
        assertEquals("$main/buy?sid=$key", AmeliaSite.renewUrl(telegramInstalled = false, base = main, key = key))
        // no key (not signed in): the bot, as before
        assertEquals("https://t.me/amelianet_bot?start=pay", AmeliaSite.renewUrl(telegramInstalled = false, base = main, key = null))
    }

    @Test
    fun siteLinksCarryOnlyTheKey() {
        assertEquals("$main/me/$key", AmeliaSite.cabinetUrl(main, key))
        assertEquals("$main/buy?sid=$key", AmeliaSite.buyUrl(main, key))
        assertEquals("$main/buy?gift=1", AmeliaSite.giftUrl(main))
        val buy = URI(AmeliaSite.buyUrl(spare, key))
        assertEquals("sid=$key", buy.rawQuery, "nothing but the key")
        assertEquals(null, URI(AmeliaSite.cabinetUrl(main, key)).rawQuery)
        assertEquals(null, URI(AmeliaSite.cabinetUrl(main, key)).rawFragment)
        // a key with characters a URL cannot carry is escaped, not cut
        assertEquals("$main/buy?sid=a%2Fb%20c", AmeliaSite.buyUrl(main, "a/b c"))
    }

    @Test
    fun mainAddressThenSpare() {
        assertEquals(main, AmeliaSite.pickBase(listOf(true, true, true)))
        assertEquals(spare, AmeliaSite.pickBase(listOf(false, true, true)))
    }
}
