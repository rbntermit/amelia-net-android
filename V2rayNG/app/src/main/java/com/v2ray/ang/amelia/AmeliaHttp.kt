package com.v2ray.ang.amelia

import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * HTTP for the subscription, version.json, APK and geo bases.
 *
 * The app's own traffic bypasses the VPN tunnel, and from Russia TLS to foreign addresses is
 * often cut. So every request goes through a plan of attempts ([plan]):
 * - with the VPN on, first **through the tunnel** (the core's local HTTP inbound) to each
 *   address of [AmeliaConfig.SUB_BASE_URLS], then **directly** to each address;
 * - with the VPN off, directly to each address.
 * The address that answered last is tried first next time ([lastBase]).
 */
object AmeliaHttp {

    enum class Route { TUNNEL, DIRECT }

    data class Attempt(val base: String, val route: Route)

    private const val PREF_LAST_BASE = "amelia_last_base"

    /** Direct client (bypasses the VPN). */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // Short connect timeout: a dead address must not delay the next one
            .connectTimeout(AmeliaConfig.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(AmeliaConfig.HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /** See [AmeliaVersions.userAgent]: the bot counts installations by its leading "AmeliaNet/<version>". */
    val userAgent: String
        get() = AmeliaVersions.userAgent()

    fun get(url: String): Request =
        Request.Builder().url(url).header("User-Agent", userAgent).build()

    // ---- route plan (pure, unit-tested) ---------------------------------------------------

    /** The address that answered last goes first, then the rest in config order. */
    fun orderBases(preferred: String?, all: List<String> = AmeliaConfig.SUB_BASE_URLS): List<String> =
        buildList {
            preferred?.takeIf { it in all }?.let { add(it) }
            all.forEach { if (it !in this) add(it) }
        }

    /** Tunnel to every address first (when the VPN is on), then direct to every address. */
    fun plan(bases: List<String>, tunnel: Boolean): List<Attempt> =
        (if (tunnel) bases.map { Attempt(it, Route.TUNNEL) } else emptyList()) +
            bases.map { Attempt(it, Route.DIRECT) }

    /**
     * version.json may point the APK at any of our addresses; download it from [base] (the one
     * that answered) instead. URLs on other hosts (GitHub) are left alone.
     */
    fun rewriteOrigin(url: String, base: String): String {
        if (!isOwnUrl(url)) return url
        val u = URI(url)
        val rest = buildString {
            append(u.rawPath ?: "")
            u.rawQuery?.let { append('?').append(it) }
        }
        return base.trimEnd('/') + rest
    }

    /** https URL on ameliajustamelia.com / .net or a subdomain. */
    fun isOwnUrl(url: String): Boolean {
        val u = runCatching { URI(url) }.getOrNull() ?: return false
        if (!u.scheme.equals("https", ignoreCase = true)) return false
        val host = u.host?.lowercase() ?: return false
        return AmeliaConfig.OWN_DOMAINS.any { host == it || host.endsWith(".$it") }
    }

    // ---- state ----------------------------------------------------------------------------

    /**
     * 1.8.3, pure: a stored address only while it is still one of ours ([AmeliaConfig.SUB_BASE_URLS]).
     * An address saved by an older version whose server was removed (msk3 until 1.8.2, off since
     * 1.8.3) is passed over: the usual order, never the dead address first.
     */
    internal fun knownBase(stored: String?): String? = stored?.takeIf { it in AmeliaConfig.SUB_BASE_URLS }

    /**
     * 1.8.3, pure: "<base> <TUNNEL|DIRECT>" as AmeliaManager stores the last subscription source;
     * null when it is unreadable or its address is no longer ours ([knownBase]).
     */
    internal fun sourceOf(stored: String?): Pair<String, Route>? {
        val parts = stored?.split(' ') ?: return null
        val base = knownBase(parts[0]) ?: return null
        val route = parts.getOrNull(1)?.let { runCatching { Route.valueOf(it) }.getOrNull() } ?: return null
        return base to route
    }

    fun lastBase(): String? = knownBase(MmkvManager.decodeSettingsString(PREF_LAST_BASE))

    fun rememberBase(base: String) {
        if (base in AmeliaConfig.SUB_BASE_URLS) MmkvManager.encodeSettings(PREF_LAST_BASE, base)
    }

    /** Attempts for a request now: last good address first, tunnel first if the VPN is on. */
    fun currentPlan(): List<Attempt> = plan(orderBases(lastBase()), tunnelAvailable())

    // ---- tunnel ---------------------------------------------------------------------------

    private fun proxyPort(): Int = SettingsManager.getHttpPort()

    /**
     * True when the core's local inbound accepts connections, i.e. the VPN is running.
     * Works from any app process (UI, background workers, the VPN daemon).
     */
    fun tunnelAvailable(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", proxyPort()), 300); true }
    } catch (_: Exception) {
        false
    }

    /** 1.7.0: the local inbound's port and login (AmeliaLocalProxy) may change: one client per set. */
    private data class TunnelKey(val port: Int, val user: String?, val pass: String?)

    @Volatile
    private var tunnelClient: Pair<TunnelKey, OkHttpClient>? = null

    private fun buildTunnelClient(key: TunnelKey): OkHttpClient {
        val b = client.newBuilder().proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", key.port)))
        val user = key.user
        val pass = key.pass
        if (user != null && pass != null) {
            // also called before the first CONNECT (OkHttp's preemptive challenge): the login goes with it
            b.proxyAuthenticator { _, response ->
                response.request.newBuilder().header("Proxy-Authorization", Credentials.basic(user, pass)).build()
            }
        }
        return b.build()
    }

    private fun tunnel(): OkHttpClient {
        val key = TunnelKey(proxyPort(), SettingsManager.getSocksUsername(), SettingsManager.getSocksPassword())
        tunnelClient?.let { (k, c) -> if (k == key) return c }
        return buildTunnelClient(key).also { tunnelClient = key to it }
    }

    fun clientFor(route: Route): OkHttpClient = if (route == Route.TUNNEL) tunnel() else client
}
