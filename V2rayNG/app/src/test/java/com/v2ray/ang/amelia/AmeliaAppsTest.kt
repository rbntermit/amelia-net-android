package com.v2ray.ang.amelia

import com.v2ray.ang.amelia.AmeliaApps.Preset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 1.5.0 "Приложения мимо VPN". */
class AmeliaAppsTest {

    private val installed = setOf(
        "ru.sberbankmobile", "com.idamob.tinkoff.android", "ru.rostel", "com.wildberries.ru", "org.example.game",
    )

    @Test
    fun newInstallStartsWithAnEmptyList() {
        assertEquals(emptySet<String>(), AmeliaApps.migrate(oldPerApp = null, oldSet = null))
    }

    @Test
    fun oneFourListIsTakenOverAsIs() {
        // what 1.4.0 wrote to upstream's per-app settings: built-in list with the person's changes
        val old = setOf("ru.sberbankmobile", "ru.rostel", "ru.vk.store", "org.example.bank")
        assertEquals(old, AmeliaApps.migrate(oldPerApp = true, oldSet = old))
        // "Российские приложения — мимо VPN" was off in 1.4.0: nothing goes around the VPN now either
        assertEquals(emptySet<String>(), AmeliaApps.migrate(oldPerApp = false, oldSet = old))
    }

    @Test
    fun removedAppLeavesTheListSilently() {
        val selected = setOf("ru.sberbankmobile", "ru.deleted.app")
        assertEquals(setOf("ru.sberbankmobile"), AmeliaApps.prune(selected, installed))
        // the installed list could not be read: nothing is dropped
        assertEquals(selected, AmeliaApps.prune(selected, null))
        assertEquals(selected, AmeliaApps.prune(selected, emptySet()))
        assertEquals(emptySet<String>(), AmeliaApps.prune(emptySet(), installed))
    }

    @Test
    fun presetsAddOnlyInstalledApps() {
        assertEquals(setOf("ru.sberbankmobile", "com.idamob.tinkoff.android"), AmeliaApps.presetInstalled(Preset.BANKS, installed))
        assertEquals(setOf("ru.rostel"), AmeliaApps.presetInstalled(Preset.GOV, installed))
        val after = AmeliaApps.applyPreset(setOf("org.example.game"), Preset.BANKS, installed)
        assertEquals(setOf("org.example.game", "ru.sberbankmobile", "com.idamob.tinkoff.android"), after)
        // tapping again takes the set off, the person's own app stays
        assertEquals(setOf("org.example.game"), AmeliaApps.applyPreset(after, Preset.BANKS, installed))
        // nothing of the set installed: no change
        assertEquals(setOf("x.y"), AmeliaApps.applyPreset(setOf("x.y"), Preset.SHOPS, setOf("x.y")))
    }

    @Test
    fun tunnelIsRebuiltOnlyWhenOnAndChanged() {
        val a = setOf("ru.rostel")
        assertTrue(AmeliaApps.shouldRecreateTunnel(emptySet(), a, vpnRunning = true))
        assertFalse(AmeliaApps.shouldRecreateTunnel(emptySet(), a, vpnRunning = false))
        assertFalse(AmeliaApps.shouldRecreateTunnel(a, a, vpnRunning = true))
        assertFalse(AmeliaApps.shouldRecreateTunnel(emptySet(), emptySet(), vpnRunning = true))
    }

    @Test
    fun presetPackagesAreWellFormed() {
        val all = Preset.entries.flatMap { it.packages }
        assertEquals(all.size, all.toSet().size, "duplicate package")
        val name = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
        all.forEach { assertTrue(name.matches(it), "bad package name $it") }
        // the ones named in the 1.5.0 task
        listOf(
            "ru.sberbankmobile", "com.idamob.tinkoff.android", "ru.vtb24.mobilebanking.android",
            "ru.alfabank.mobile.android", "ru.gazprombank.android.mobilebank.app",
        ).forEach { assertTrue(it in Preset.BANKS.packages, it) }
        assertTrue("ru.rostel" in Preset.GOV.packages)
        listOf("ru.ozon.app.android", "com.wildberries.ru", "ru.beru.android").forEach { assertTrue(it in Preset.SHOPS.packages, it) }
    }
}
