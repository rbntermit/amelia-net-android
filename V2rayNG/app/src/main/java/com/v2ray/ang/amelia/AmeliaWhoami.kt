package com.v2ray.ang.amelia

import android.content.Context
import com.google.gson.annotations.SerializedName
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.LogUtil
import java.util.IllformedLocaleException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 1.9.0: GET /app/whoami — from which address the server sees the request. No key, no install id,
 * no body; the server stores nothing. Used by "Что видно снаружи" (the "🩺 Не работает?" window) and
 * by the pause behind an Amelia router (AmeliaRouterPause).
 */
object AmeliaWhoami {

    const val PATH = "/app/whoami"

    /**
     * Through the tunnel: the foreign main domain, then its backup. Never a Russian mirror: the entry
     * is reached directly, so the server there would see the entry, not the exit. The domains are
     * "always via VPN" in the routing (AmeliaRouting.PROXY_SITES), so a direct list of the
     * subscription cannot send this request around the tunnel.
     */
    val TUNNEL_BASES: List<String> = AmeliaConfig.BACKUP_BASE_URLS

    /** The VPN off: the same addresses directly, then the Russian mirrors (each sees the operator's address). */
    val DIRECT_BASES: List<String> = AmeliaConfig.BACKUP_BASE_URLS + AmeliaConfig.ENTRIES.map { it.baseUrl }

    /** The router check: .net only, around the tunnel. */
    val ROUTER_BASES: List<String> = listOf(AmeliaConfig.BACKUP_BASE_URLS.last())

    /** The router check: the whole request, 3 s. */
    const val ROUTER_TIMEOUT_MS = 3_000L

    /** "Что видно снаружи": every address together. */
    const val CHECK_TIMEOUT_MS = 12_000L

    data class Answer(
        val ok: Boolean = false,
        val ours: Boolean? = null,
        /** Our server the request came from ("nl2"). */
        val via: String? = null,
        val cc: String? = null,
        val ip: String? = null,
        @SerializedName("as") val asName: String? = null,
    )

    /** Null: unreadable, `{"ok":false}` (too often, address unknown) or no "ours" at all. */
    fun parse(json: String?): Answer? =
        json?.let { runCatching { AmeliaProbe.wireGson.fromJson(it, Answer::class.java) }.getOrNull() }
            ?.takeIf { it.ok && it.ours != null }

    // ---- what the person is told (pure, unit-tested) -----------------------------------------

    enum class Kind {
        /** VPN on, the request came from our foreign server. */
        ABROAD,
        /** VPN on, from our server in Russia ("Москва"). */
        OUR_RUSSIAN,
        /** VPN on, but the request came from the operator's address. */
        LEAK,
        /** VPN off, the operator's address. */
        VPN_OFF,
        /** VPN off, yet from our server: the network itself goes through an Amelia router. */
        ROUTER,
        /** No answer. */
        UNKNOWN,
    }

    /** [country]: in words ("Нидерланды"); [operator]: readable name of the AS (AmeliaHints.operatorName). */
    data class Verdict(val kind: Kind, val country: String? = null, val ip: String? = null, val operator: String? = null)

    fun verdict(a: Answer?, vpnOn: Boolean, locale: Locale = RUSSIAN): Verdict {
        if (a?.ours == null) return Verdict(Kind.UNKNOWN)
        val country = countryName(a.cc, locale)
        val ip = a.ip?.takeIf { it.isNotBlank() }?.take(64)
        val operator = AmeliaHints.operatorName(a.asName)
        val kind = when {
            !vpnOn -> if (a.ours == true) Kind.ROUTER else Kind.VPN_OFF
            a.ours != true -> Kind.LEAK
            a.cc.equals("RU", ignoreCase = true) -> Kind.OUR_RUSSIAN
            else -> Kind.ABROAD
        }
        return Verdict(kind, country, ip, operator)
    }

    /** "Ростелеком, Россия, 203.0.113.5": what is known, in this order. */
    fun seenAs(v: Verdict): String = listOfNotNull(v.operator, v.country, v.ip).joinToString(", ")

    val RUSSIAN: Locale = Locale.forLanguageTag("ru")

    /** "NL" → "Нидерланды"; null for an empty or malformed code. */
    fun countryName(cc: String?, locale: Locale = RUSSIAN): String? {
        val code = cc?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.length == 2 && it.all(Char::isLetter) } ?: return null
        val region = try {
            Locale.Builder().setRegion(code).build()
        } catch (_: IllformedLocaleException) {
            return null
        }
        return region.getDisplayCountry(locale).takeIf { it.isNotBlank() && it != code } ?: code
    }

    // ---- what the app knows itself: the tunnel's config (pure, unit-tested) -----------------

    /**
     * [ipv6InTunnel]: the VPN takes IPv6 (AppConfig.PREF_IPV6_ENABLED); without it Android blocks the
     * IPv6 of every app inside the VPN (VpnService.Builder: no address or route of a family), so it does
     * not go around the tunnel either. [dnsInTunnel]: every DNS server of the VPN interface lies outside
     * the direct networks, so its queries enter the tunnel and leave through the location.
     */
    data class Local(val ipv6InTunnel: Boolean, val dnsInTunnel: Boolean, val dnsServers: List<String>)

    fun local(ipv6Enabled: Boolean, vpnDns: List<String>, directIps: List<String>): Local =
        Local(
            ipv6InTunnel = ipv6Enabled,
            dnsInTunnel = vpnDns.isNotEmpty() && vpnDns.none { dns -> directIps.any { ipv4InCidr(dns, it) } },
            dnsServers = vpnDns,
        )

    /** "10.1.2.3" in "10.0.0.0/8"; a plain address matches itself; IPv6 and geoip: tags never match. */
    fun ipv4InCidr(ip: String, cidr: String): Boolean {
        val addr = ipv4(ip) ?: return false
        val net = ipv4(cidr.substringBefore('/')) ?: return false
        val bits = if ('/' in cidr) cidr.substringAfter('/').toIntOrNull()?.takeIf { it in 0..32 } ?: return false else 32
        if (bits == 0) return true
        val mask = (-1L shl (32 - bits)) and 0xFFFFFFFFL
        return (addr and mask) == (net and mask)
    }

    private fun ipv4(s: String): Long? {
        val parts = s.trim().split('.')
        if (parts.size != 4) return null
        var v = 0L
        for (p in parts) {
            val n = p.toIntOrNull()?.takeIf { it in 0..255 && p.length in 1..3 } ?: return null
            v = (v shl 8) or n.toLong()
        }
        return v
    }

    // ---- requests ---------------------------------------------------------------------------

    /** Blocking. Through the tunnel (the core's local inbound), [TUNNEL_BASES] in order. */
    fun throughTunnel(timeoutMs: Long = CHECK_TIMEOUT_MS): Answer? {
        val deadline = System.currentTimeMillis() + timeoutMs
        val base = AmeliaHttp.clientFor(AmeliaHttp.Route.TUNNEL)
        for (url in TUNNEL_BASES) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) break
            try {
                base.newBuilder().callTimeout(left, TimeUnit.MILLISECONDS).build()
                    .newCall(AmeliaHttp.get(url + PATH)).execute().use { resp ->
                        if (resp.isSuccessful) parse(resp.body.string())?.let { return it }
                        else LogUtil.w(AppConfig.TAG, "Amelia: GET $PATH HTTP ${resp.code} from $url (tunnel)")
                    }
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "Amelia: GET $PATH failed at $url (tunnel)", e)
            }
        }
        return null
    }

    /** Blocking. Over the phone's own network (around the tunnel), [bases] in order within [timeoutMs]. */
    fun direct(context: Context, bases: List<String> = DIRECT_BASES, timeoutMs: Long = CHECK_TIMEOUT_MS): Answer? =
        parse(AmeliaNetwork.getDirect(context, PATH, timeoutMs, bases))

    /** Blocking. What this phone's VPN does with IPv6 and DNS, from the settings the tunnel is built from. */
    fun localNow(): Local =
        local(
            ipv6Enabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_IPV6_ENABLED, false),
            vpnDns = SettingsManager.getVpnDnsServers(),
            directIps = AmeliaRouting.DIRECT_IPS + AmeliaRouteProfile.load()?.directIp.orEmpty(),
        )
}
