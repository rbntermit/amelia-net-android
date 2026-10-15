package com.v2ray.ang.amelia

import java.net.InetAddress

/**
 * "Where does this site go?" — answers the way the core routes it with [AmeliaRouting] rules:
 *
 * 1. always-via-VPN sites (YouTube…)            → VPN
 * 2. direct sites, then geosite:category-ru     → direct
 * 3. otherwise the address is resolved (domainStrategy IPIfNonMatch):
 *    local networks or geoip:ru                 → direct
 * 4. everything else                            → VPN
 *
 * The geo bases are the same files the core uses (downloaded by [AmeliaGeoUpdater]).
 */
object AmeliaRouteCheck {

    enum class Route { DIRECT, VPN }

    enum class Reason {
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
        /** No rule matched: default route through the VPN. */
        DEFAULT,
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
    )

    // ---- input ------------------------------------------------------------------------------

    private val HOST_RE = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*$")

    /** "https://www.Kinopoisk.ru:443/film/1" → "www.kinopoisk.ru"; null if it is not a host. */
    fun normalize(input: String): String? {
        var s = input.trim().lowercase()
        s = s.substringAfter("://")
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        s = s.substringAfter('@')
        if (s.startsWith("[")) return s.substringAfter('[').substringBefore(']').takeIf { isIpLiteral(it) }
        if (s.count { it == ':' } == 1) s = s.substringBefore(':')
        s = s.trimEnd('.')
        if (s.isEmpty() || s.length > 253) return null
        return s.takeIf { isIpLiteral(it) || HOST_RE.matches(it) }
    }

    private val IPV4_RE = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    fun isIpLiteral(s: String): Boolean =
        IPV4_RE.matches(s) && s.split('.').all { it.toInt() <= 255 } ||
            (s.contains(':') && s.all { it.isDigit() || it in 'a'..'f' || it == ':' || it == '.' })

    // ---- rules ------------------------------------------------------------------------------

    /** Xray "domain:" rule: the domain itself and all its subdomains. */
    fun domainMatches(domain: String, host: String): Boolean =
        host == domain || host.endsWith(".$domain")

    fun classify(
        input: String,
        geoSite: GeoSite?,
        geoIp: CidrSet?,
        resolve: (String) -> List<InetAddress>,
    ): Result {
        val host = normalize(input) ?: return Result(input.trim(), null, Reason.BAD_INPUT)

        if (!isIpLiteral(host)) {
            AmeliaRouting.PROXY_SITES.firstOrNull { domainMatches(it, host) }
                ?.let { return Result(host, Route.VPN, Reason.PROXY_SITE, it) }
            AmeliaRouting.DIRECT_SITES.firstOrNull { domainMatches(it, host) }
                ?.let { return Result(host, Route.DIRECT, Reason.DIRECT_SITE, it) }
            geoSite?.match(host)?.let { return Result(host, Route.DIRECT, Reason.RU_SITE, it) }
        }

        val addresses = if (isIpLiteral(host)) {
            listOfNotNull(runCatching { InetAddress.getByName(host) }.getOrNull())
        } else {
            runCatching { resolve(host) }.getOrDefault(emptyList())
        }
        for (a in addresses) {
            LOCAL_NETS.match(a)?.let { return Result(host, Route.DIRECT, Reason.LOCAL_NETWORK, it, a.hostAddress) }
            geoIp?.match(a)?.let { return Result(host, Route.DIRECT, Reason.RU_IP, it, a.hostAddress) }
        }
        return Result(host, Route.VPN, Reason.DEFAULT, ip = addresses.firstOrNull()?.hostAddress)
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
                    return InetAddress.getByAddress(net).hostAddress + "/" + prefix
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
                val bytes = runCatching { InetAddress.getByName(ip).address }.getOrNull() ?: return@mapNotNull null
                val prefix = c.substringAfter('/', "").toIntOrNull() ?: bytes.size * 8
                bytes to prefix
            })
        }
    }

    private val LOCAL_NETS: CidrSet by lazy { CidrSet.parse(AmeliaRouting.DIRECT_IPS) }

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
                2 -> pos += varint().toInt()
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
