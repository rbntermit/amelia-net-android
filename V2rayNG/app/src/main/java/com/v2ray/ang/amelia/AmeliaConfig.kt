package com.v2ray.ang.amelia

/**
 * Everything specific to the Amelia.Net service in one place.
 *
 * Amelia.Net is a derivative of v2rayNG (https://github.com/2dust/v2rayNG), GPL-3.0.
 */
object AmeliaConfig {

    // ---- 1.7.3: our Russian entries, the only place their names and addresses are written ----

    /**
     * One Russian entry of the service: a server in Russia with the subscription mirror on
     * [MIRROR_PORT] (subscription, /app/…, the site) and locations on its address. [ipv6]: null
     * when it has none.
     */
    data class Entry(val name: String, val domain: String, val ipv4: String, val ipv6: String? = null) {
        val baseUrl: String get() = "https://$domain:$MIRROR_PORT"
    }

    const val MIRROR_PORT = 9443

    /** In the order the mirrors are asked (msk1 → msk2 → msk3). A new entry is added here only. */
    val ENTRIES = listOf(
        Entry("msk1", "msk1.ameliajustamelia.com", "79.137.192.178"),
        Entry("msk2", "msk2.ameliajustamelia.com", "188.120.243.206"),
        // 1.7.3: Kemerovo, a mirror of msk2 (same site and /app/ paths, certificate on .com and .net)
        Entry("msk3", "msk3.ameliajustamelia.com", "138.124.77.193", "2a05:541:142:1aa::1"),
    )

    fun entry(name: String): Entry = ENTRIES.first { it.name == name }

    /** The main domain and its backup on .net: abroad, asked after every Russian mirror. */
    val BACKUP_BASE_URLS = listOf(
        "https://sub.ameliajustamelia.com:$MIRROR_PORT",
        "https://sub.ameliajustamelia.net:$MIRROR_PORT",
    )

    /**
     * Servers for the subscription (`/sub/<key>`), version.json and APK (`/app/`) and geo bases
     * (`/geo/`), tried in order (the one that answered last goes first, see AmeliaHttp): the
     * Russian mirrors ([ENTRIES]), then the main domain and its backup on .net.
     */
    val SUB_BASE_URLS: List<String> =
        // Russian mirrors first: from Russia TLS to foreign addresses is often cut (1.0.5)
        ENTRIES.map { it.baseUrl } + BACKUP_BASE_URLS

    /**
     * The subscription base (msk2) first, then its backup (msk1), then msk3 (1.7.3). Used by
     * the site, its one-time links (/app/site-token) and, since 1.6.1, the download of the
     * subscription when nothing works (AmeliaResubscribe: the server may have moved a location
     * to another port). Russian mirrors only, never the tunnel.
     */
    val SITE_BASE_URLS: List<String> = listOf("msk2", "msk1", "msk3").map { entry(it).baseUrl }

    val RESUBSCRIBE_BASE_URLS: List<String> = SITE_BASE_URLS

    /**
     * Foreign servers of the service (exits). Not entries (no mirror on them), but their
     * addresses are ours too ([OUR_SERVER_IPS]).
     */
    val FOREIGN_SERVER_IPS = listOf("91.184.241.246", "153.80.241.66", "139.28.240.139", "199.189.253.231")

    /**
     * Addresses of our own servers, IPv4 and IPv6: kept in crash reports (they are not a
     * person's address and help to read the report), everything else is removed (AmeliaCrash).
     */
    val OUR_SERVER_IPS: Set<String> =
        (ENTRIES.flatMap { listOfNotNull(it.ipv4, it.ipv6) } + FOREIGN_SERVER_IPS).toSet()

    /** Short name of the entry whose mirror is [baseUrl] ("msk3"), null for any other address. */
    fun entryNameOf(baseUrl: String?): String? = ENTRIES.firstOrNull { it.baseUrl == baseUrl?.trimEnd('/') }?.name

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
        /** 1.5.0: the bot sends the person's own Telegram proxy link (subscription valid). */
        TGPROXY("tgproxy"),
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
