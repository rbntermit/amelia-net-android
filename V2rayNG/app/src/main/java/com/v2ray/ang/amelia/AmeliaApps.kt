package com.v2ray.ang.amelia

import android.content.Context
import android.content.Intent
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.JsonUtil
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

    /** The person's own change of the list (switch, set): 1.9.0 also remembers apps taken off by hand. */
    fun save(list: Set<String>) = MmkvManager.withSettingsLock {
        ensureMigrated()
        val edit = afterHandEdit(bypassed(), list, ruAdded(), ruRemoved())
        MmkvManager.encodeSettings(PREF_RU_ADDED, edit.added.toMutableSet())
        MmkvManager.encodeSettings(PREF_RU_REMOVED, edit.removed.toMutableSet())
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

    // ---- 1.9.0: "Российские приложения — мимо VPN" -------------------------------------------

    /**
     * GET /app/ruapps: `{"v": 20261111, "apps": ["ru.sberbankmobile", …]}`. Same addresses and order as
     * /app/hints, around the tunnel, no key or id; nothing about the installed apps goes to the server.
     */
    const val RU_PATH = "/app/ruapps"
    const val RU_TIMEOUT_MS = 3_000L
    /** The server caches the list for an hour; so does the app. */
    const val RU_CACHE_MS = 60L * 60 * 1000
    private const val RU_MAX = 500

    /** The server's list of 11.11.2026, used when no server answers and nothing was downloaded before. */
    val RU_BUILT_IN = listOf(
        "ru.sberbankmobile", "com.idamob.tinkoff.android", "ru.alfabank.mobile.android",
        "ru.vtb24.mobilebanking.android", "ru.gazprombank.android.mobilebank.app", "ru.raiffeisennews",
        "ru.sovcomcard.halva.v1", "ru.letobank.Prometheus", "ru.nspk.mirpay", "ru.nspk.sbpay", "ru.yoo.money",
        "ru.rostel", "ru.mos.app", "ru.fns.lkfl", "com.octopod.russianpost.client.android", "ru.ozon.app.android",
        "com.wildberries.ru", "ru.beru.android", "com.avito.android", "ru.yandex.searchplugin", "ru.yandex.taxi",
        "ru.yandex.yandexmaps", "ru.yandex.music", "ru.dublgis.dgismobile", "com.vkontakte.android", "ru.oneme.app",
        "ru.vk.store", "ru.kinopoisk", "ru.rutube.app", "ru.ivi.client", "ru.mts.mymts", "ru.beeline.services",
        "ru.megafon.mlk", "ru.tele2.mytele2",
    )

    private const val PREF_RU_ON = "amelia_ruapps_on"
    /** What the switch put on the list itself (taken off again when it is switched off). */
    private const val PREF_RU_ADDED = "amelia_ruapps_added"
    /** Taken off by hand: never put back by the switch. */
    private const val PREF_RU_REMOVED = "amelia_ruapps_removed"
    private const val PREF_RU_CACHE = "amelia_ruapps_cache"

    private class RuAnswer(val v: Long? = null, val apps: List<String>? = null)

    private class RuCache(val apps: List<String> = emptyList(), val at: Long = 0L)

    private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

    /** Package names of the answer, checked; null when it is unreadable or empty. */
    fun parseRuList(json: String?): List<String>? =
        json?.let { runCatching { AmeliaProbe.wireGson.fromJson(it, RuAnswer::class.java) }.getOrNull() }
            ?.apps.orEmpty()
            .map { it.trim() }.filter { it.length <= 255 && PACKAGE.matches(it) }
            .distinct().take(RU_MAX)
            .takeIf { it.isNotEmpty() }

    data class RuSync(val selected: Set<String>, val added: Set<String>)

    /**
     * The switch on, or a check after a subscription update: installed apps of [list] join the list,
     * except those taken off by hand ([removedByHand]) and those already on it.
     */
    fun ruAdd(selected: Set<String>, list: List<String>, installed: Set<String>, added: Set<String>, removedByHand: Set<String>): RuSync {
        val fresh = list.filter { it in installed && it !in removedByHand && it !in selected }
        return RuSync(LinkedHashSet(selected) + fresh, LinkedHashSet(added) + fresh)
    }

    /** The switch off: what it added leaves the list; what the person added by hand stays. */
    fun ruRemove(selected: Set<String>, added: Set<String>): Set<String> = selected.filterTo(LinkedHashSet()) { it !in added }

    data class HandEdit(val added: Set<String>, val removed: Set<String>)

    /** A change by hand from [before] to [after]: an added app taken off is remembered, one put back is forgiven. */
    fun afterHandEdit(before: Set<String>, after: Set<String>, added: Set<String>, removed: Set<String>): HandEdit {
        val gone = (before - after).filterTo(LinkedHashSet()) { it in added }
        val back = after - before
        return HandEdit(added - gone, (removed + gone) - back)
    }

    fun ruEnabled(): Boolean = MmkvManager.decodeSettingsBool(PREF_RU_ON, false)

    fun ruAdded(): Set<String> = MmkvManager.decodeSettingsStringSet(PREF_RU_ADDED)?.toSet().orEmpty()

    private fun ruRemoved(): Set<String> = MmkvManager.decodeSettingsStringSet(PREF_RU_REMOVED)?.toSet().orEmpty()

    /**
     * Blocking (network up to 3 s). The list from the server, kept for an hour; no answer: the last one
     * downloaded, or [RU_BUILT_IN].
     */
    private fun ruList(context: Context): List<String> {
        val cached = MmkvManager.decodeSettingsString(PREF_RU_CACHE)?.takeIf { it.isNotBlank() }
            ?.let { JsonUtil.fromJsonSafe(it, RuCache::class.java) }
        val now = System.currentTimeMillis()
        if (cached != null && cached.apps.isNotEmpty() && now - cached.at in 0 until RU_CACHE_MS) return cached.apps
        val fresh = parseRuList(AmeliaNetwork.getDirect(context, RU_PATH, RU_TIMEOUT_MS))
        if (fresh != null) {
            MmkvManager.encodeSettings(PREF_RU_CACHE, JsonUtil.toJson(RuCache(fresh, now)))
            return fresh
        }
        LogUtil.w(AppConfig.TAG, "Amelia: $RU_PATH did not answer, using ${if (cached?.apps.isNullOrEmpty()) "the built-in list" else "the last one"}")
        return cached?.apps?.takeIf { it.isNotEmpty() } ?: RU_BUILT_IN
    }

    /**
     * Blocking. The switch on: the list is checked at once ([syncRu]); off: what it added leaves the list.
     * @return true when the list of apps around the VPN changed (the tunnel needs a rebuild)
     */
    fun setRuEnabled(context: Context, on: Boolean): Boolean {
        MmkvManager.encodeSettings(PREF_RU_ON, on)
        if (on) return syncRu(context)
        return MmkvManager.withSettingsLock {
            val before = bypassed()
            val after = ruRemove(before, ruAdded())
            MmkvManager.encodeSettings(PREF_RU_ADDED, mutableSetOf<String>())
            if (after == before) return@withSettingsLock false
            MmkvManager.encodeSettings(PREF_LIST, after.toMutableSet())
            writePerApp(after)
            true
        }
    }

    /**
     * Blocking. While the switch is on (switched on, every subscription update): installed apps of the
     * list that are not there yet join it. @return true when the list changed.
     */
    fun syncRu(context: Context): Boolean {
        if (!ruEnabled()) return false
        val installed = installedLaunchable(context) ?: return false
        val list = ruList(context)
        return MmkvManager.withSettingsLock {
            val before = bypassed()
            val r = ruAdd(before, list, installed, ruAdded(), ruRemoved())
            MmkvManager.encodeSettings(PREF_RU_ADDED, r.added.toMutableSet())
            if (r.selected == before) return@withSettingsLock false
            MmkvManager.encodeSettings(PREF_LIST, r.selected.toMutableSet())
            writePerApp(r.selected)
            LogUtil.i(AppConfig.TAG, "Amelia: Russian apps around the VPN: ${r.selected.size - before.size} added")
            true
        }
    }
}
