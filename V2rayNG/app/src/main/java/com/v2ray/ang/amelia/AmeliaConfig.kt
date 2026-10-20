package com.v2ray.ang.amelia

/**
 * Everything specific to the Amelia.Net service in one place.
 *
 * Amelia.Net is a derivative of v2rayNG (https://github.com/2dust/v2rayNG), GPL-3.0.
 */
object AmeliaConfig {
    /**
     * Servers for the subscription (`/sub/<key>`), version.json and APK (`/app/`) and geo bases
     * (`/geo/`), tried in order (the one that answered last goes first, see AmeliaHttp): two
     * Russian mirrors, then the main domain and its backup on .net.
     */
    val SUB_BASE_URLS = listOf(
        // Russian mirrors first: from Russia TLS to foreign addresses is often cut (1.0.5)
        "https://msk1.ameliajustamelia.com:9443",
        "https://msk2.ameliajustamelia.com:9443",
        "https://sub.ameliajustamelia.com:9443",
        "https://sub.ameliajustamelia.net:9443",
    )

    /** Our domains: URLs on them may be redirected to whichever mirror answered. */
    val OWN_DOMAINS = listOf("ameliajustamelia.com", "ameliajustamelia.net")
    const val SUB_PATH = "/sub/"
    const val GEO_PATH = "/geo/"

    /** Deep link: amelia://key/<key> */
    const val DEEP_LINK_SCHEME = "amelia"
    const val DEEP_LINK_HOST_KEY = "key"

    const val BOT_USERNAME = "@amelianet_bot"
    const val BOT_URL = "https://t.me/amelianet_bot"
    const val GROUP_URL = "https://t.me/amelia_net"

    /**
     * Bot screens opened from the app via /start <payload>. The bot already handles "pay";
     * the others need the small bot.py patch from docs/BOT_DEEPLINKS.md, until then they open
     * the bot's main menu.
     */
    enum class BotScreen(val payload: String) {
        PAY("pay"), STATUS("status"), HELP("help"), SUPPORT("support"), REFERRAL("referral"), MY_SUB("mysub"),
        /** 1.4.0: the bot sends the app's APK (when the phone blocks the in-app update). */
        APP("app"),
    }

    fun botUrl(screen: BotScreen? = null): String =
        if (screen == null) BOT_URL else "$BOT_URL?start=${screen.payload}"

    /** GPL-3.0 §6: where recipients of the APK get the corresponding source. */
    const val SOURCE_CODE_URL = "https://github.com/rbntermit/amelia-net-android"
    const val UPSTREAM_URL = "https://github.com/2dust/v2rayNG"

    /** Fixed id of the only subscription the app manages. */
    const val SUBSCRIPTION_ID = "amelia-net"
    const val SUBSCRIPTION_REMARKS = "Amelia.Net"

    /** Used when the server does not send Profile-Update-Interval. */
    const val DEFAULT_UPDATE_INTERVAL_HOURS = 12

    // MMKV settings keys (settings storage is shared by all app processes)
    const val PREF_KEY = "amelia_key"
    const val PREF_SUB_INFO = "amelia_sub_info"
    const val PREF_DEFAULTS_VERSION = "amelia_defaults_version"

    // Daemon <-> UI messages (same "key" extra as AppConfig.MSG_*, ids far above upstream's)
    /** Daemon -> UI: connection health, content = AmeliaDaemon.Health */
    const val MSG_HEALTH = 9101
    /** Daemon -> UI: session traffic, content = AmeliaDaemon.Traffic */
    const val MSG_TRAFFIC = 9102
    /** UI -> daemon: check now and switch locations if needed (user pressed "Не работает?") */
    const val MSG_RECHECK = 9103
    /** Daemon -> UI: new server check results are stored (AmeliaChecks) */
    const val MSG_CHECKS = 9104

    const val HTTP_TIMEOUT_SECONDS = 15L
    const val CONNECT_TIMEOUT_SECONDS = 8L
}
