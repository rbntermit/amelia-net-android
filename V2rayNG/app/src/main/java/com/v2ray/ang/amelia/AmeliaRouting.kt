package com.v2ray.ang.amelia

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.Utils
import java.io.File

/**
 * Built-in routing, mirrors HAPP_ROUTING from sub_server.py:
 * Russian sites and private networks go direct, the rest through the tunnel,
 * YouTube always through the tunnel (Google caches in RU networks have RU addresses).
 *
 * Rule order is block → proxy → direct (Happ "RouteOrder": "block-proxy-direct");
 * anything unmatched goes to the first outbound, which is the proxy.
 */
object AmeliaRouting {

    /** Always through VPN, even when the address is Russian. */
    val PROXY_SITES = listOf(
        "googlevideo.com", "youtube.com", "youtu.be", "ytimg.com",
        "ggpht.com", "youtube-nocookie.com", "youtubei.googleapis.com",
    )

    /** Kinopoisk and Max block datacenter IPs, so they must bypass the tunnel. */
    val DIRECT_SITES = listOf(
        "kinopoisk.ru", "max.ru", "oneme.ru", "okcdn.ru", "vk-analytics.ru",
        "mobileproxy.passport.yandex.net",
    )

    val DIRECT_IPS = listOf(
        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
        "169.254.0.0/16", "224.0.0.0/4", "255.255.255.255",
    )

    // Our geo bases contain only the Russian segment, so no geoip:private/geosite:private here.
    const val GEOSITE_RU = "geosite:category-ru"
    const val GEOIP_RU = "geoip:ru"

    const val REMOTE_DNS = "https://dns.adguard-dns.com/dns-query"
    const val DOMESTIC_DNS = "77.88.8.8"
    const val DNS_HOSTS = "dns.adguard-dns.com:94.140.14.14"
    const val DOMAIN_STRATEGY = "IPIfNonMatch"

    /**
     * Xray refuses to start when a rule references a missing geo file, so the
     * geosite/geoip rules are added only after both files are downloaded.
     */
    fun geoFilesReady(context: Context): Boolean {
        val dir = Utils.userAssetPath(context)
        return listOf(AppConfig.GEOSITE_DAT, AppConfig.GEOIP_DAT)
            .all { File(dir, it).let { f -> f.isFile && f.length() > MIN_GEO_SIZE } }
    }

    const val MIN_GEO_SIZE = 1000L

    fun buildRulesets(withGeo: Boolean): MutableList<RulesetItem> {
        fun domains(list: List<String>) = list.map { "domain:$it" }
        return mutableListOf(
            RulesetItem(
                id = "amelia-proxy-sites",
                remarks = "Amelia: always via VPN",
                domain = domains(PROXY_SITES),
                outboundTag = AppConfig.TAG_PROXY,
                locked = true,
            ),
            RulesetItem(
                id = "amelia-direct-sites",
                remarks = "Amelia: direct sites",
                domain = domains(DIRECT_SITES) + if (withGeo) listOf(GEOSITE_RU) else emptyList(),
                outboundTag = AppConfig.TAG_DIRECT,
                locked = true,
            ),
            RulesetItem(
                id = "amelia-direct-ip",
                remarks = "Amelia: direct IP",
                ip = DIRECT_IPS + if (withGeo) listOf(GEOIP_RU) else emptyList(),
                outboundTag = AppConfig.TAG_DIRECT,
                locked = true,
            ),
        )
    }

    /** Writes routing and DNS settings. Users cannot edit them, so it is safe to apply on every start. */
    fun apply(context: Context) {
        MmkvManager.encodeRoutingRulesets(buildRulesets(geoFilesReady(context)))
        MmkvManager.encodeSettings(AppConfig.PREF_ROUTING_DOMAIN_STRATEGY, DOMAIN_STRATEGY)
        MmkvManager.encodeSettings(AppConfig.PREF_REMOTE_DNS, REMOTE_DNS)
        MmkvManager.encodeSettings(AppConfig.PREF_DOMESTIC_DNS, DOMESTIC_DNS)
        MmkvManager.encodeSettings(AppConfig.PREF_DNS_HOSTS, DNS_HOSTS)
        MmkvManager.encodeSettings(AppConfig.PREF_FAKE_DNS_ENABLED, false)
        MmkvManager.encodeSettings(AppConfig.PREF_LOCAL_DNS_ENABLED, false)
        MmkvManager.encodeSettings(AppConfig.PREF_MODE, AppConfig.VPN)
        // Fixed local inbound port: the app reaches the tunnel through it from any process
        // (a dynamic port is generated per process and would differ from the daemon's)
        MmkvManager.encodeSettings(AppConfig.PREF_DYNAMIC_SOCKS_PORT, false)
        // Traffic counters in the core (session traffic on the main screen, speed in the notification)
        MmkvManager.encodeSettings(AppConfig.PREF_SPEED_ENABLED, true)
        // "Ping all" must only measure: never delete or reorder the user's locations
        MmkvManager.encodeSettings(AppConfig.PREF_AUTO_REMOVE_INVALID_AFTER_TEST, false)
        MmkvManager.encodeSettings(AppConfig.PREF_AUTO_SORT_AFTER_TEST, false)
        // Russian apps around the VPN (per-app exclusion), kept in sync with the person's list
        AmeliaApps.apply()
    }
}
