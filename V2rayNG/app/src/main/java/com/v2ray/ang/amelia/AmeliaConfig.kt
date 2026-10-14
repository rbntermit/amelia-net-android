package com.v2ray.ang.amelia

/**
 * Everything specific to the Amelia.Net service in one place.
 *
 * Amelia.Net is a derivative of v2rayNG (https://github.com/2dust/v2rayNG), GPL-3.0.
 */
object AmeliaConfig {
    /**
     * Subscription servers, tried in order. The key is appended as `/sub/<key>`.
     * The second entry is the backup domain (own TLS certificate since 14.10.2026), used for
     * the subscription, geo bases and app updates whenever the primary does not answer.
     */
    val SUB_BASE_URLS = listOf(
        "https://sub.ameliajustamelia.com:9443",
        "https://sub.ameliajustamelia.net:9443",
    )
    const val SUB_PATH = "/sub/"
    const val GEO_PATH = "/geo/"

    /** Deep link: amelia://key/<key> */
    const val DEEP_LINK_SCHEME = "amelia"
    const val DEEP_LINK_HOST_KEY = "key"

    const val BOT_USERNAME = "@amelianet_bot"
    const val BOT_URL = "https://t.me/amelianet_bot"

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

    const val HTTP_TIMEOUT_SECONDS = 15L
    const val CONNECT_TIMEOUT_SECONDS = 8L
}
