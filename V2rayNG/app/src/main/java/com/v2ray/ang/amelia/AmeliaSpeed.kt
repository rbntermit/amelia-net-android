package com.v2ray.ang.amelia

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import libv2ray.CoreCallbackHandler
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * "Замерить скорость" (1.3.0, long press on a location): 10 MB through that location, at most
 * [TIMEOUT_MS]. Only when the person asks: their traffic may be paid for.
 *
 * A separate core instance runs in this process with a local HTTP inbound and the location as
 * its only outbound; the download goes through it. The app is excluded from its own VPN, so
 * this works with the VPN on or off and does not touch the running connection.
 */
object AmeliaSpeed {

    val URLS = listOf(
        "https://speed.cloudflare.com/__down?bytes=10000000",
        "https://proof.ovh.net/files/10Mb.dat",
    )
    const val TIMEOUT_MS = 20_000L
    /** Less than this downloaded: no result. */
    private const val MIN_BYTES = 1_000_000L
    const val KEEP_MS = 24L * 60 * 60 * 1000
    private const val PREF = "amelia_speeds"

    data class Entry(val mbps: Double = 0.0, val at: Long = 0L)

    /** Mbit/s from bytes and milliseconds. */
    fun mbps(bytes: Long, ms: Long): Double = if (ms <= 0) 0.0 else bytes * 8.0 / ms / 1000.0

    /** "≈ 48 Мбит/с": whole numbers from 10, one decimal below. */
    fun format(mbps: Double): String =
        if (mbps >= 10) "%.0f".format(java.util.Locale.US, mbps) else "%.1f".format(java.util.Locale.US, mbps).replace('.', ',')

    /**
     * Adds a local HTTP inbound on [port] to a speed-test config (no inbounds, no routing).
     * 1.7.0: with a login ([user], [pass]): other apps cannot use the location through it.
     */
    fun withInbound(configJson: String, port: Int, user: String, pass: String): String {
        val root = JsonParser.parseString(configJson).asJsonObject
        val inbound = JsonObject().apply {
            addProperty("tag", "amelia-speed-in")
            addProperty("listen", AppConfig.LOOPBACK)
            addProperty("port", port)
            addProperty("protocol", "http")
            add(
                "settings",
                JsonObject().apply {
                    add(
                        "accounts",
                        JsonArray().apply {
                            add(JsonObject().apply { addProperty("user", user); addProperty("pass", pass) })
                        },
                    )
                },
            )
        }
        root.add("inbounds", JsonArray().apply { add(inbound) })
        return root.toString()
    }

    // ---- storage --------------------------------------------------------------------------

    private fun load(): Map<String, Entry> =
        MmkvManager.decodeSettingsString(PREF)?.takeIf { it.isNotBlank() }?.let { s ->
            runCatching {
                JsonParser.parseString(s).asJsonObject.entrySet().associate { (k, v) ->
                    k to JsonUtil.fromJson(v.toString(), Entry::class.java)!!
                }
            }.getOrNull()
        }.orEmpty()

    private fun save(name: String, e: Entry) {
        val now = System.currentTimeMillis()
        val m = load().filterValues { now - it.at < KEEP_MS } + (name to e)
        MmkvManager.encodeSettings(PREF, JsonUtil.toJson(m))
    }

    /** Results from the last 24 h by location guid (stored by name: guids change on refresh). */
    fun byGuid(): Map<String, Double> {
        val now = System.currentTimeMillis()
        val m = load().filterValues { now - it.at < KEEP_MS }
        return MmkvManager.decodeServerList(AmeliaConfig.SUBSCRIPTION_ID).mapNotNull { guid ->
            MmkvManager.decodeServerConfig(guid)?.remarks?.let { m[it] }?.let { guid to it.mbps }
        }.toMap()
    }

    // ---- measuring ------------------------------------------------------------------------

    private val noCallbacks = object : CoreCallbackHandler {
        override fun startup(): Long = 0
        override fun shutdown(): Long = 0
        override fun onEmitStatus(l: Long, s: String?): Long = 0
    }

    /** Blocking. Mbit/s through the location [guid], or null when nothing came through. */
    fun measure(context: Context, guid: String): Double? {
        val name = MmkvManager.decodeServerConfig(guid)?.remarks ?: return null
        val config = CoreConfigManager.getV2rayConfig4Speedtest(context, guid)
        if (!config.status) return null
        val port = Utils.findRandomFreePort()
        // 1.7.0: a one-off login for this test's inbound
        val (user, pass) = AmeliaLocalProxy.oneOffLogin()
        val controller = try {
            CoreNativeManager.initCoreEnv(context)
            CoreNativeManager.newCoreController(noCallbacks).also { it.startLoop(withInbound(config.content, port, user, pass), 0) }
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "Amelia speed: cannot start a core for the test", e)
            return null
        }
        try {
            val client = OkHttpClient.Builder()
                .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(AppConfig.LOOPBACK, port)))
                .proxyAuthenticator { _, response ->
                    response.request.newBuilder().header("Proxy-Authorization", Credentials.basic(user, pass)).build()
                }
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                // a stalled download must not outlast the 20 s limit
                .callTimeout(TIMEOUT_MS + 2_000, TimeUnit.MILLISECONDS)
                .build()
            for (url in URLS) {
                val result = download(client, url)
                if (result != null) {
                    save(name, Entry(result, System.currentTimeMillis()))
                    return result
                }
            }
            return null
        } finally {
            runCatching { controller.stopLoop() }
        }
    }

    /** Downloads until done or [TIMEOUT_MS]; speed from what arrived, if at least 1 MB. */
    private fun download(client: OkHttpClient, url: String): Double? {
        val started = System.currentTimeMillis()
        var bytes = 0L
        try {
            client.newCall(Request.Builder().url(url).header("User-Agent", AmeliaVersions.userAgent()).build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (System.currentTimeMillis() - started < TIMEOUT_MS) {
                        val n = input.read(buf)
                        if (n < 0) break
                        bytes += n
                    }
                }
            }
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "Amelia speed: download stopped: ${e.message}")
        }
        val ms = System.currentTimeMillis() - started
        return if (bytes >= MIN_BYTES) mbps(bytes, ms) else null
    }
}
