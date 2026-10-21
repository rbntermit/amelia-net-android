package com.v2ray.ang.amelia

import android.content.Context
import android.content.Intent
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil

/**
 * "Приложения мимо VPN" (1.5.0). Routing by domains already sends Russian sites directly, but
 * Android still tells every app that a VPN is up, and some banks and Gosuslugi refuse to work.
 * Apps from this list are excluded from the VPN entirely (VpnService.Builder.addDisallowedApplication
 * via upstream's per-app settings, read by CoreVpnService when the tunnel is built).
 *
 * The list is the person's own: empty on a new install. 1.1.0–1.4.0 had a built-in list switched
 * on by default; on the first 1.5.0 start that effective list is taken over as is ([migrate]), so
 * nothing changes for people who already use the app.
 */
object AmeliaApps {

    /** One-tap sets on top of the screen; only installed apps of a set are added. */
    enum class Preset(val packages: List<String>) {
        BANKS(
            listOf(
                "ru.sberbankmobile", // СберБанк Онлайн
                "com.idamob.tinkoff.android", // Т-Банк
                "ru.vtb24.mobilebanking.android", // ВТБ Онлайн
                "ru.alfabank.mobile.android", // Альфа-Банк
                "ru.gazprombank.android.mobilebank.app", // Газпромбанк
                "ru.raiffeisennews", // Райффайзен Онлайн
                "ru.rshb.dbo", // Россельхозбанк
                "ru.otpbank.mobile", // ОТП Банк
                "ru.lewis.dbo", // МТС Деньги (МТС Банк)
                "ru.sovcomcard.halva.v1", // Халва – Совкомбанк
                "ru.ozon.fintech.finance", // Ozon Банк
                "ru.yoo.money", // ЮMoney
                "ru.nspk.sbpay", // СБПэй
                "ru.sberbank_sbbol", // СберБизнес
            ),
        ),
        GOV(
            listOf(
                "ru.rostel", // Госуслуги
                "ru.gosuslugi.goskey", // Госключ
                "ru.gosuslugi.auto", // Госуслуги Авто
                "ru.sigma.gisgkh", // Госуслуги Дом
                "ru.fns.lkfl", // Налоги ФЛ
                "com.gnivts.selfemployed", // Мой налог
            ),
        ),
        SHOPS(
            listOf(
                "ru.ozon.app.android", // Ozon
                "com.wildberries.ru", // Wildberries
                "ru.beru.android", // Яндекс Маркет
                "ru.megamarket.marketplace", // Мегамаркет
                "com.avito.android", // Авито
                "com.lamoda.lite", // Lamoda
            ),
        ),
    }

    private const val PREF_LIST = "amelia_bypass_apps"
    private const val PREF_MIGRATED = "amelia_bypass_apps_v15"

    // ---- pure logic (unit-tested) ---------------------------------------------------------

    /**
     * The list on the first 1.5.0 start. [oldPerApp]/[oldSet] are what 1.1.0–1.4.0 wrote to
     * upstream's per-app settings (the built-in list with the person's changes, or nothing when
     * "Российские приложения — мимо VPN" was off). A new install has neither: empty list.
     */
    fun migrate(oldPerApp: Boolean?, oldSet: Set<String>?): Set<String> =
        if (oldPerApp == true) oldSet.orEmpty() else emptySet()

    /** Packages that are no longer installed are dropped silently. Unknown installed list: keep all. */
    fun prune(selected: Set<String>, installed: Set<String>?): Set<String> =
        if (installed.isNullOrEmpty()) selected else selected.filterTo(LinkedHashSet()) { it in installed }

    /** Installed packages of [preset]. */
    fun presetInstalled(preset: Preset, installed: Set<String>): Set<String> =
        preset.packages.filterTo(LinkedHashSet()) { it in installed }

    /**
     * Tapping a set: adds its installed apps; when all of them are on the list already, takes
     * them off again (the button works as a switch).
     */
    fun applyPreset(selected: Set<String>, preset: Preset, installed: Set<String>): Set<String> {
        val apps = presetInstalled(preset, installed)
        return if (apps.isNotEmpty() && selected.containsAll(apps)) selected - apps else selected + apps
    }

    /** The tunnel is rebuilt only when the list really changed and the VPN is on. */
    fun shouldRecreateTunnel(before: Set<String>, after: Set<String>, vpnRunning: Boolean): Boolean =
        vpnRunning && before != after

    // ---- storage --------------------------------------------------------------------------

    private fun ensureMigrated() {
        if (MmkvManager.decodeSettingsBool(PREF_MIGRATED, false)) return
        val old = migrate(
            MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY, false),
            MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET)?.toSet(),
        )
        MmkvManager.encodeSettings(PREF_LIST, old.toMutableSet())
        MmkvManager.encodeSettings(PREF_MIGRATED, true)
    }

    /** Packages that go around the VPN. */
    fun bypassed(): Set<String> {
        ensureMigrated()
        return MmkvManager.decodeSettingsStringSet(PREF_LIST)?.toSet().orEmpty()
    }

    /** Something is on the list (the report's `bypass_ru`). */
    fun isEnabled(): Boolean = bypassed().isNotEmpty()

    fun save(list: Set<String>) {
        ensureMigrated()
        MmkvManager.encodeSettings(PREF_LIST, list.toMutableSet())
        writePerApp(list)
    }

    fun setBypass(pkg: String, bypass: Boolean) = save(if (bypass) bypassed() + pkg else bypassed() - pkg)

    /** Packages with a launcher icon (visible thanks to the LAUNCHER <queries>); null when the query failed. */
    fun installedLaunchable(context: Context): Set<String>? = try {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        AmeliaPackages.launcherActivities(context.packageManager, intent)
            .mapTo(HashSet()) { it.activityInfo.packageName }
            .takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        LogUtil.w(AppConfig.TAG, "Amelia: cannot list installed apps", e)
        null
    }

    /**
     * Writes upstream's per-app settings, read by CoreVpnService when the VPN starts: bypass mode
     * with our list (CoreVpnService adds this app itself). Removed apps leave the list here, so
     * the service never meets a package that is gone. Takes effect on the next VPN (re)start.
     */
    fun apply(context: Context) {
        val selected = bypassed()
        val kept = prune(selected, installedLaunchable(context))
        if (kept != selected) MmkvManager.encodeSettings(PREF_LIST, kept.toMutableSet())
        writePerApp(kept)
    }

    private fun writePerApp(list: Set<String>) {
        MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY, list.isNotEmpty())
        MmkvManager.encodeSettings(AppConfig.PREF_BYPASS_APPS, true)
        MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY_SET, list.toMutableSet())
    }
}
