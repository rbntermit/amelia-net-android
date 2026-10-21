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

    /** Domain matchers Xray understands as they are; anything else is a plain domain. */
    private val DOMAIN_PREFIXES = listOf("domain:", "full:", "regexp:", "keyword:", "dotless:", "ext:")

    /**
     * One DirectSites/ProxySites entry of the subscription profile as an Xray domain matcher:
     * "notes.example.net" → "domain:notes.example.net" (the domain and its subdomains);
     * "geosite:<tag>" only when the geo base is there and has the tag (else Xray would not
     * start); null for entries that cannot be used.
     */
    fun domainRule(entry: String, hasGeo: (String) -> Boolean): String? {
        val e = entry.trim()
        if (e.isEmpty()) return null
        val lower = e.lowercase()
        return when {
            lower.startsWith("geosite:") -> e.takeIf { hasGeo(it) }
            lower.startsWith("geoip:") -> null
            DOMAIN_PREFIXES.any { lower.startsWith(it) } -> e
            e.contains(' ') || e.contains('/') -> null
            else -> "domain:${e.trimStart('.').trimEnd('.')}"
        }
    }

    /** One DirectIp/ProxyIp entry: an address or CIDR as is, "geoip:<tag>" only when present in the base. */
    fun ipRule(entry: String, hasGeo: (String) -> Boolean): String? {
        val e = entry.trim()
        if (e.isEmpty() || e.contains(' ')) return null
        return when {
            e.lowercase().startsWith("geoip:") -> e.takeIf { hasGeo(it) }
            e.lowercase().startsWith("geosite:") -> null
            else -> e
        }
    }

    /**
     * Rules in Happ's order block → proxy → direct ("RouteOrder": "block-proxy-direct"): the
     * proxy lists come first, so a ProxySites entry wins over DirectSites (youtube.com stays in
     * the VPN even with geosite:category-ru direct). The subscription [profile] (1.5.2) adds to
     * the built-in lists; [hasGeo] says whether a geosite:/geoip: tag can be used.
     */
    fun buildRulesets(
        withGeo: Boolean,
        profile: AmeliaRouteProfile.Profile? = null,
        hasGeo: (String) -> Boolean = { withGeo && (it == GEOSITE_RU || it == GEOIP_RU) },
    ): MutableList<RulesetItem> {
        val geo: (String) -> Boolean = { withGeo && hasGeo(it) }
        fun domains(builtIn: List<String>, extra: List<String>?) =
            (builtIn.map { "domain:$it" } + extra.orEmpty().mapNotNull { domainRule(it, geo) }).distinct()
        fun ips(builtIn: List<String>, extra: List<String>?) =
            (builtIn + extra.orEmpty().mapNotNull { ipRule(it, geo) }).distinct()

        val rules = mutableListOf(
            RulesetItem(
                id = "amelia-proxy-sites",
                remarks = "Amelia: always via VPN",
                domain = domains(PROXY_SITES, profile?.proxySites),
                outboundTag = AppConfig.TAG_PROXY,
                locked = true,
            ),
        )
        val proxyIp = ips(emptyList(), profile?.proxyIp)
        if (proxyIp.isNotEmpty()) {
            rules += RulesetItem(
                id = "amelia-proxy-ip",
                remarks = "Amelia: IP always via VPN",
                ip = proxyIp,
                outboundTag = AppConfig.TAG_PROXY,
                locked = true,
            )
        }
        rules += RulesetItem(
            id = "amelia-direct-sites",
            remarks = "Amelia: direct sites",
            domain = domains(DIRECT_SITES, profile?.directSites)
                .let { if (withGeo && GEOSITE_RU !in it) it + GEOSITE_RU else it },
            outboundTag = AppConfig.TAG_DIRECT,
            locked = true,
        )
        rules += RulesetItem(
            id = "amelia-direct-ip",
            remarks = "Amelia: direct IP",
            ip = ips(DIRECT_IPS, profile?.directIp)
                .let { if (withGeo && GEOIP_RU !in it) it + GEOIP_RU else it },
            outboundTag = AppConfig.TAG_DIRECT,
            locked = true,
        )
        return rules
    }

    /**
     * Does the downloaded geo base have [tag] ("geosite:category-ru")? Our bases hold only a
     * few countries; Xray refuses to start on a missing tag. The tag name is stored in the
     * file as the protobuf field country_code: byte 0x0A, its length, the upper-case name.
     */
    fun geoHasTag(context: Context, tag: String): Boolean {
        val (kind, name) = tag.split(':', limit = 2).takeIf { it.size == 2 } ?: return false
        val file = File(Utils.userAssetPath(context), if (kind.equals("geosite", true)) AppConfig.GEOSITE_DAT else AppConfig.GEOIP_DAT)
        if (!file.isFile) return false
        val key = "${file.path}:${file.length()}:${file.lastModified()}:$name"
        geoTagCache[key]?.let { return it }
        val found = runCatching { hasCountryCode(file.readBytes(), name) }.getOrDefault(false)
        geoTagCache[key] = found
        return found
    }

    /** Pure: the geo base bytes [data] have the list [name] ("category-ru", attributes after @ ignored). */
    fun hasCountryCode(data: ByteArray, name: String): Boolean {
        val name8 = name.substringBefore('@').uppercase().toByteArray(Charsets.US_ASCII)
        if (name8.isEmpty() || name8.size > 127) return false
        val needle = byteArrayOf(0x0A, name8.size.toByte()) + name8
        return indexOf(data, needle) >= 0
    }

    private val geoTagCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private fun indexOf(data: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty()) return -1
        outer@ for (i in 0..data.size - needle.size) {
            for (j in needle.indices) if (data[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    /** Writes routing and DNS settings. Users cannot edit them, so it is safe to apply on every start. */
    fun apply(context: Context) {
        val withGeo = geoFilesReady(context)
        MmkvManager.encodeRoutingRulesets(
            buildRulesets(withGeo, AmeliaRouteProfile.load()) { tag ->
                tag == GEOSITE_RU || tag == GEOIP_RU || geoHasTag(context, tag)
            },
        )
        MmkvManager.encodeSettings(AppConfig.PREF_ROUTING_DOMAIN_STRATEGY, DOMAIN_STRATEGY)
        MmkvManager.encodeSettings(AppConfig.PREF_REMOTE_DNS, REMOTE_DNS)
        MmkvManager.encodeSettings(AppConfig.PREF_DOMESTIC_DNS, DOMESTIC_DNS)
        MmkvManager.encodeSettings(AppConfig.PREF_DNS_HOSTS, DNS_HOSTS)
        MmkvManager.encodeSettings(AppConfig.PREF_FAKE_DNS_ENABLED, false)
        MmkvManager.encodeSettings(AppConfig.PREF_LOCAL_DNS_ENABLED, false)
        MmkvManager.encodeSettings(AppConfig.PREF_MODE, AppConfig.VPN)
        // No IPv6 in the VPN: Android then blocks IPv6 of the apps inside it, so nothing leaks around
        // the tunnel when the provider has IPv6 (VpnService.Builder.allowFamily)
        MmkvManager.encodeSettings(AppConfig.PREF_IPV6_ENABLED, false)
        // Fixed local inbound port: the app reaches the tunnel through it from any process
        // (a dynamic port is generated per process and would differ from the daemon's)
        MmkvManager.encodeSettings(AppConfig.PREF_DYNAMIC_SOCKS_PORT, false)
        // Traffic counters in the core (session traffic on the main screen, speed in the notification)
        MmkvManager.encodeSettings(AppConfig.PREF_SPEED_ENABLED, true)
        // 1.2.0: six locations are checked at the same time (AmeliaCheck.PARALLEL)
        MmkvManager.encodeSettings(AppConfig.PREF_REAL_PING_CONCURRENCY, AmeliaCheck.PARALLEL.toString())
        // "Ping all" must only measure: never delete or reorder the user's locations
        MmkvManager.encodeSettings(AppConfig.PREF_AUTO_REMOVE_INVALID_AFTER_TEST, false)
        MmkvManager.encodeSettings(AppConfig.PREF_AUTO_SORT_AFTER_TEST, false)
        // "Приложения мимо VPN" (per-app exclusion), kept in sync with the person's list
        AmeliaApps.apply(context)
    }
}
