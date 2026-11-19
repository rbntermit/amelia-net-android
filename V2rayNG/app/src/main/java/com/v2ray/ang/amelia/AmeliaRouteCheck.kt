package com.v2ray.ang.amelia

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.RulesetItem
import java.net.InetAddress
import java.util.Locale

/**
 * Estimates the route from the generated rules used by local Xray. With IPIfNonMatch, the
 * first pass checks domain rules; the second checks IP rules in order using the resolved addresses.
 */
object AmeliaRouteCheck {

    enum class Route { DIRECT, VPN }

    enum class Reason {
        /** The person's explicit domain rule. */
        PERSONAL,
        /** Listed in "always via VPN" (YouTube and Google video hosts). */
        PROXY_SITE,
        /** Listed in "direct" (Kinopoisk, Max…). */
        DIRECT_SITE,
        /** In geosite:category-ru. */
        RU_SITE,
        /** Address in a local / private network. */
        LOCAL_NETWORK,
        /** Address in geoip:ru. */
        RU_IP,
        PROXY_IP,
        DIRECT_IP,
        /** No rule matched: default route through the VPN. */
        DEFAULT,
        /** A rule requires data or connection details the checker does not have. */
        UNAVAILABLE,
        /** Not a host name or IP address. */
        BAD_INPUT,
    }

    data class Result(
        val host: String,
        val route: Route?,
        val reason: Reason,
        /** The matching list entry or network, when there is one. */
        val rule: String? = null,
        /** Resolved address used for the IP rules. */
        val ip: String? = null,
        /** Exact domain that the editor would save. */
        val siteRuleDomain: String? = null,
    )

    // ---- input ------------------------------------------------------------------------------

    private val HOST_RE = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*$")

    /** "https://www.Kinopoisk.ru:443/film/1" → "www.kinopoisk.ru"; null if it is not a host. */
    fun normalize(input: String): String? {
        var s = input.trim().lowercase()
        s = s.substringAfter("://")
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        s = s.substringAfter('@')
        if (s.startsWith("[")) {
            val end = s.indexOf(']')
            if (end < 0) return null
            return s.substring(1, end).takeIf { it.contains(':') && isIpLiteral(it) }
        }
        if (s.count { it == ':' } == 1) s = s.substringBefore(':')
        s = s.trimEnd('.')
        if (s.isEmpty() || s.length > 253) return null
        s = runCatching { java.net.IDN.toASCII(s).lowercase(java.util.Locale.ROOT) }.getOrNull() ?: return null
        return s.takeIf { isIpLiteral(it) || HOST_RE.matches(it) }
    }

    private val IPV4_RE = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    private fun literalAddress(s: String): InetAddress? {
        val ipv4 = IPV4_RE.matches(s) && s.split('.').all { it.toInt() <= 255 }
        val ipv6 = ':' in s && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
        return if (ipv4 || ipv6) runCatching { InetAddress.getByName(s) }.getOrNull() else null
    }

    fun isIpLiteral(s: String): Boolean = literalAddress(s) != null

    // ---- rules ------------------------------------------------------------------------------

    /** Xray "domain:" rule: the domain itself and all its subdomains. */
    fun domainMatches(domain: String, host: String): Boolean =
        host == domain || host.endsWith(".$domain")

    fun classify(
        input: String,
        geoSite: GeoSite?,
        geoIp: CidrSet?,
        resolve: (String) -> List<InetAddress>,
    ): Result = classify(input, geoSite, geoIp, emptyList(), resolve)

    fun classify(
        input: String,
        geoSite: GeoSite?,
        geoIp: CidrSet?,
        siteRules: List<AmeliaRouting.SiteRule>,
        resolve: (String) -> List<InetAddress>,
    ): Result = classify(
        input,
        AmeliaRouting.buildRulesets(
            withGeo = true,
            hasGeo = { tag ->
                (tag == AmeliaRouting.GEOSITE_RU && geoSite != null) ||
                    (tag == AmeliaRouting.GEOIP_RU && geoIp != null)
            },
            siteRules = siteRules,
        ),
        { code -> geoSite.takeIf { code.equals("CATEGORY-RU", true) } },
        { code -> geoIp.takeIf { code.equals("RU", true) } },
        resolve,
    )

    internal fun classify(
        input: String,
        rules: List<RulesetItem>,
        geoSite: (String) -> GeoSite?,
        geoIp: (String) -> CidrSet?,
        resolve: (String) -> List<InetAddress>?,
    ): Result {
        val host = normalize(input) ?: return Result(input.trim(), null, Reason.BAD_INPUT)
        val literal = literalAddress(host)
        val editableDomain = if (literal == null) AmeliaRouting.editableSiteDomain(host) else null
        val unavailable = Result(host, null, Reason.UNAVAILABLE, siteRuleDomain = editableDomain)
        val active = rules.filter { it.enabled }
        if (active.any { r ->
                r.outboundTag !in listOf(AppConfig.TAG_DIRECT, AppConfig.TAG_PROXY) ||
                    !r.port.isNullOrEmpty() || !r.network.isNullOrEmpty() ||
                    !r.process.isNullOrEmpty() || !r.protocol.isNullOrEmpty() ||
                    (!r.domain.isNullOrEmpty() && !r.ip.isNullOrEmpty())
            }
        ) return unavailable

        if (literal == null) {
            for (rule in active) {
                var missingData = false
                for (entry in rule.domain.orEmpty()) {
                    val match = domainMatch(entry, host, geoSite)
                    if (match == null) missingData = true
                    if (match.isNullOrEmpty()) continue
                    val route = rule.route()
                    val reason = when {
                        rule.id.startsWith("amelia-personal-") -> Reason.PERSONAL
                        route == Route.VPN -> Reason.PROXY_SITE
                        entry.equals(AmeliaRouting.GEOSITE_RU, true) -> Reason.RU_SITE
                        else -> Reason.DIRECT_SITE
                    }
                    return Result(host, route, reason, match, siteRuleDomain = editableDomain)
                }
                if (missingData) return unavailable
            }
        }

        val addresses = if (literal != null) listOf(literal) else resolve(host) ?: return unavailable
        for (rule in active) {
            var missingData = false
            for (entry in rule.ip.orEmpty()) {
                if (addresses.isEmpty()) break
                val nets = when {
                    entry.startsWith("geoip:", true) -> geoIp(entry.substringAfter(':'))
                    entry.startsWith("ext:", true) || entry.startsWith("!") -> null
                    else -> CidrSet.parse(listOf(entry)).takeIf { it.size != 0 }
                }
                if (nets == null) {
                    missingData = true
                    continue
                }
                for (address in addresses) {
                    val match = nets.match(address) ?: continue
                    val route = rule.route()
                    val reason = when {
                        route == Route.VPN -> Reason.PROXY_IP
                        entry.equals(AmeliaRouting.GEOIP_RU, true) -> Reason.RU_IP
                        entry in AmeliaRouting.DIRECT_IPS -> Reason.LOCAL_NETWORK
                        else -> Reason.DIRECT_IP
                    }
                    return Result(host, route, reason, match, address.hostAddress, editableDomain)
                }
            }
            if (missingData) return unavailable
        }

        return Result(host, Route.VPN, Reason.DEFAULT, ip = addresses.firstOrNull()?.hostAddress, siteRuleDomain = editableDomain)
    }

    private fun RulesetItem.route(): Route = if (outboundTag == AppConfig.TAG_DIRECT) Route.DIRECT else Route.VPN

    /** Null means the rule could not be checked. An empty string means that it did not match. */
    private fun domainMatch(entry: String, host: String, geoSite: (String) -> GeoSite?): String? {
        val value = entry.substringAfter(':')
        return when (entry.substringBefore(':').lowercase(Locale.ROOT)) {
            "domain" -> value.takeIf { domainMatches(it.lowercase(Locale.ROOT), host) }.orEmpty()
            "full" -> entry.takeIf { value.equals(host, true) }.orEmpty()
            "keyword" -> entry.takeIf { host.contains(value, ignoreCase = true) }.orEmpty()
            "regexp" -> runCatching { entry.takeIf { Regex(value).containsMatchIn(host) }.orEmpty() }.getOrNull()
            "dotless" -> entry.takeIf { '.' !in host && value in host }.orEmpty()
            "geosite" -> geoSite(value)?.let { it.match(host).orEmpty() }
            "ext" -> null
            else -> entry.takeIf { host.contains(it, ignoreCase = true) }.orEmpty()
        }
    }

    // ---- networks ---------------------------------------------------------------------------

    class CidrSet(private val nets: List<Pair<ByteArray, Int>>) {
        val size: Int get() = nets.size

        /** The matching network as "a.b.c.d/n", or null. */
        fun match(addr: InetAddress): String? {
            val ip = addr.address
            for ((net, prefix) in nets) {
                if (net.size != ip.size) continue
                if (inNet(ip, net, prefix)) {
                    return InetAddress.getByAddress(net).hostAddress?.let { "$it/$prefix" }
                }
            }
            return null
        }

        private fun inNet(ip: ByteArray, net: ByteArray, prefix: Int): Boolean {
            var bits = prefix
            var i = 0
            while (bits > 0 && i < ip.size) {
                val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
                if ((ip[i].toInt() and mask) != (net[i].toInt() and mask)) return false
                bits -= 8
                i++
            }
            return true
        }

        companion object {
            fun parse(cidrs: List<String>): CidrSet = CidrSet(cidrs.mapNotNull { c ->
                val ip = c.substringBefore('/')
                val bytes = literalAddress(ip)?.address ?: return@mapNotNull null
                val width = bytes.size * 8
                val prefix = if ('/' in c) c.substringAfter('/').toIntOrNull() ?: return@mapNotNull null else width
                if (prefix !in 0..width) return@mapNotNull null
                bytes to prefix
            })
        }
    }

    // ---- geosite --------------------------------------------------------------------------

    class GeoSite(
        private val keywords: List<String>,
        private val regexes: List<Regex>,
        private val roots: Set<String>,
        private val full: Set<String>,
    ) {
        val size: Int get() = keywords.size + regexes.size + roots.size + full.size

        /** The matching entry, or null. */
        fun match(host: String): String? {
            if (host in full) return "full:$host"
            var d = host
            while (true) {
                if (d in roots) return d
                val dot = d.indexOf('.')
                if (dot < 0) break
                d = d.substring(dot + 1)
            }
            keywords.firstOrNull { host.contains(it) }?.let { return "keyword:$it" }
            regexes.firstOrNull { it.containsMatchIn(host) }?.let { return "regexp:${it.pattern}" }
            return null
        }
    }

    // ---- protobuf (geosite.dat / geoip.dat are v2fly GeoSiteList / GeoIPList) --------------

    private class Pb(private val b: ByteArray, private var pos: Int = 0, private val end: Int = b.size) {
        fun more() = pos < end
        fun varint(): Long {
            var shift = 0
            var r = 0L
            while (true) {
                val x = b[pos++].toInt() and 0xFF
                r = r or ((x and 0x7F).toLong() shl shift)
                if (x < 0x80) return r
                shift += 7
            }
        }
        /** Returns (field, wireType). */
        fun tag(): Pair<Int, Int> { val t = varint(); return (t ushr 3).toInt() to (t and 7).toInt() }
        fun bytes(): Pb { val len = varint().toInt(); val p = Pb(b, pos, pos + len); pos += len; return p }
        fun raw(): ByteArray = b.copyOfRange(pos, end)
        fun string(): String = String(raw(), Charsets.UTF_8)
        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> pos += 8
                2 -> {
                    val length = varint().toInt()
                    pos += length
                }
                5 -> pos += 4
                else -> error("bad wire type $wire")
            }
        }
    }

    /** Entries of [code] (e.g. "CATEGORY-RU") from geosite.dat; null if the code is absent. */
    fun parseGeoSite(data: ByteArray, code: String): GeoSite? {
        val list = Pb(data)
        while (list.more()) {
            val (f, w) = list.tag()
            if (f != 1 || w != 2) { list.skip(w); continue }
            val site = list.bytes()
            var cc: String? = null
            val domains = mutableListOf<Pb>()
            while (site.more()) {
                val (sf, sw) = site.tag()
                when {
                    sf == 1 && sw == 2 -> cc = site.bytes().string()
                    sf == 2 && sw == 2 -> domains += site.bytes()
                    else -> site.skip(sw)
                }
            }
            if (!cc.equals(code, ignoreCase = true)) continue
            val kw = mutableListOf<String>(); val rx = mutableListOf<Regex>()
            val roots = HashSet<String>(); val full = HashSet<String>()
            for (d in domains) {
                var type = 0; var value = ""
                while (d.more()) {
                    val (df, dw) = d.tag()
                    when {
                        df == 1 && dw == 0 -> type = d.varint().toInt()
                        df == 2 && dw == 2 -> value = d.bytes().string().lowercase()
                        else -> d.skip(dw)
                    }
                }
                when (type) {
                    0 -> kw += value
                    1 -> runCatching { Regex(value) }.getOrNull()?.let { rx += it }
                    2 -> roots += value
                    3 -> full += value
                }
            }
            return GeoSite(kw, rx, roots, full)
        }
        return null
    }

    /** Networks of [code] (e.g. "RU") from geoip.dat; null if the code is absent. */
    fun parseGeoIp(data: ByteArray, code: String): CidrSet? {
        val list = Pb(data)
        while (list.more()) {
            val (f, w) = list.tag()
            if (f != 1 || w != 2) { list.skip(w); continue }
            val geo = list.bytes()
            var cc: String? = null
            val nets = mutableListOf<Pair<ByteArray, Int>>()
            while (geo.more()) {
                val (gf, gw) = geo.tag()
                when {
                    gf == 1 && gw == 2 -> cc = geo.bytes().string()
                    gf == 2 && gw == 2 -> {
                        val c = geo.bytes()
                        var ip = ByteArray(0); var prefix = 0
                        while (c.more()) {
                            val (cf, cw) = c.tag()
                            when {
                                cf == 1 && cw == 2 -> ip = c.bytes().raw()
                                cf == 2 && cw == 0 -> prefix = c.varint().toInt()
                                else -> c.skip(cw)
                            }
                        }
                        if (ip.size == 4 || ip.size == 16) nets += ip to prefix
                    }
                    else -> geo.skip(gw)
                }
            }
            if (cc.equals(code, ignoreCase = true)) return CidrSet(nets)
        }
        return null
    }
}
