package com.v2ray.ang.amelia

import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager

/**
 * "Российские приложения — мимо VPN". Routing by domains already sends Russian sites
 * directly, but Android still tells every app that a VPN is up (TRANSPORT_VPN), and banks,
 * Gosuslugi and marketplaces react to that. Apps from this list are excluded from the VPN
 * entirely (VpnService.Builder.addDisallowedApplication via upstream's per-app settings), so
 * they do not see the tunnel at all.
 *
 * The person's own changes are kept as two sets — added and removed — on top of [BUILT_IN],
 * so they survive app updates, and packages we add to [BUILT_IN] later reach everyone who
 * has not removed them.
 */
object AmeliaApps {

    data class App(val pkg: String, val label: String)

    /**
     * Each package was checked in RuStore (and Google Play where the app is still there).
     * To add one: append a line here; nothing else is needed.
     * Not included: Почта Банк (no current app found in RuStore or Google Play).
     */
    val BUILT_IN: List<App> = listOf(
        // banks and payments
        App("ru.sberbankmobile", "СберБанк Онлайн"),
        App("com.idamob.tinkoff.android", "Т-Банк"),
        App("ru.vtb24.mobilebanking.android", "ВТБ Онлайн"),
        App("ru.alfabank.mobile.android", "Альфа-Банк"),
        App("ru.gazprombank.android.mobilebank.app", "Газпромбанк"),
        App("ru.raiffeisennews", "Райффайзен Онлайн"),
        App("ru.sovcomcard.halva.v1", "Халва – Совкомбанк"),
        App("ru.otpbank.mobile", "ОТП Банк"),
        App("ru.lewis.dbo", "МТС Деньги (МТС Банк)"),
        App("ru.ozon.fintech.finance", "Ozon Банк"),
        App("ru.yoo.money", "ЮMoney"),
        App("ru.nspk.sbpay", "СБПэй"),
        App("ru.sberbank_sbbol", "СберБизнес"),
        App("ru.tinkoff.investing", "Т-Инвестиции"),
        App("ru.sberbank.investor", "СберИнвестиции"),
        // government
        App("ru.rostel", "Госуслуги"),
        App("ru.gosuslugi.auto", "Госуслуги Авто"),
        App("ru.sigma.gisgkh", "Госуслуги Дом"),
        App("ru.fns.lkfl", "Налоги ФЛ (ФНС)"),
        App("com.gnivts.selfemployed", "Мой налог"),
        // shopping and delivery
        App("ru.ozon.app.android", "Ozon"),
        App("com.wildberries.ru", "Wildberries"),
        App("ru.beru.android", "Яндекс Маркет"),
        App("com.avito.android", "Авито"),
        App("ru.yandex.taxi", "Яндекс Go"),
        App("ru.yandex.yandexmaps", "Яндекс Карты"),
        App("ru.foodfox.client", "Яндекс Еда"),
        App("ru.sbcs.store", "Самокат"),
        App("ru.instamart", "Купер"),
        App("com.icemobile.lenta.prod", "Лента"),
        App("ru.lenta.lentochka", "Лента Онлайн"),
        App("ru.vkusvill", "ВкусВилл"),
        App("ru.tander.magnit", "Магнит"),
        App("ru.pyaterochka.app.browser", "Пятёрочка"),
        // social and messengers
        App("com.vkontakte.android", "ВКонтакте"),
        App("com.vk.im", "VK Мессенджер"),
        App("ru.oneme.app", "MAX"),
        App("ru.ok.android", "Одноклассники"),
        App("ru.vk.store", "RuStore"),
        // mobile operators
        App("ru.mts.mymts", "Мой МТС"),
        App("ru.beeline.services", "билайн"),
        App("ru.megafon.mlk", "МегаФон"),
        App("ru.tele2.mytele2", "t2"),
        // video and music
        App("ru.kinopoisk", "Кинопоиск"),
        App("ru.more.play", "Okko"),
        App("ru.ivi.client", "Иви"),
        App("ru.rt.video.app.mobile", "Wink"),
        App("ru.yandex.music", "Яндекс Музыка"),
        // travel and maps
        App("ru.dublgis.dgismobile", "2ГИС"),
        App("ru.rzd.pass", "РЖД Пассажирам"),
        App("ru.aeroflot", "Аэрофлот"),
    )

    val BUILT_IN_PACKAGES: Set<String> = BUILT_IN.map { it.pkg }.toSet()

    const val PREF_ENABLED = "amelia_ru_apps_bypass"
    private const val PREF_ADDED = "amelia_ru_apps_added"
    private const val PREF_REMOVED = "amelia_ru_apps_removed"

    // ---- pure logic (unit-tested) ---------------------------------------------------------

    /** Built-in list minus what the person removed, plus what they added. */
    fun effective(builtIn: Set<String>, added: Set<String>, removed: Set<String>): Set<String> =
        (builtIn - removed) + added

    /**
     * New added/removed sets after the person turns [pkg] on or off. Only differences from the
     * built-in list are stored, so a built-in package switched back on leaves no trace.
     */
    fun toggle(
        builtIn: Set<String>,
        added: Set<String>,
        removed: Set<String>,
        pkg: String,
        bypass: Boolean,
    ): Pair<Set<String>, Set<String>> = if (pkg in builtIn) {
        added - pkg to if (bypass) removed - pkg else removed + pkg
    } else {
        (if (bypass) added + pkg else added - pkg) to removed - pkg
    }

    // ---- storage --------------------------------------------------------------------------

    fun isEnabled(): Boolean = MmkvManager.decodeSettingsBool(PREF_ENABLED, true)

    private fun added(): Set<String> = MmkvManager.decodeSettingsStringSet(PREF_ADDED)?.toSet().orEmpty()
    private fun removed(): Set<String> = MmkvManager.decodeSettingsStringSet(PREF_REMOVED)?.toSet().orEmpty()

    /** Packages that go around the VPN (installed or not). */
    fun bypassed(): Set<String> = effective(BUILT_IN_PACKAGES, added(), removed())

    fun setEnabled(enabled: Boolean) {
        MmkvManager.encodeSettings(PREF_ENABLED, enabled)
        apply()
    }

    fun setBypass(pkg: String, bypass: Boolean) {
        val (a, r) = toggle(BUILT_IN_PACKAGES, added(), removed(), pkg, bypass)
        MmkvManager.encodeSettings(PREF_ADDED, a.toMutableSet())
        MmkvManager.encodeSettings(PREF_REMOVED, r.toMutableSet())
        apply()
    }

    /**
     * Writes upstream's per-app settings, read by CoreVpnService when the VPN starts:
     * bypass mode with our list (CoreVpnService adds this app itself to the list), or off,
     * which excludes only this app as before. Takes effect on the next VPN (re)start.
     */
    fun apply() {
        val on = isEnabled()
        MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY, on)
        MmkvManager.encodeSettings(AppConfig.PREF_BYPASS_APPS, true)
        MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY_SET, if (on) bypassed().toMutableSet() else mutableSetOf())
    }
}
