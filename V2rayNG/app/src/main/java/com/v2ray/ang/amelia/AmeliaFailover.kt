package com.v2ray.ang.amelia

import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Internet check after connecting, and automatic switch to the next location.
 *
 * Two probes:
 * - through the tunnel: the core fetches Google generate_204 (upstream MSG_MEASURE_DELAY).
 *   Google is routed via the proxy, so success means this location works;
 * - direct: the app itself fetches ya.ru. The app's own traffic bypasses the VPN, so this
 *   tells whether the phone has internet at all.
 *
 * Google fails, Yandex works → this location is broken → try the next one.
 * Both fail → the phone is offline → switching would not help.
 */
object AmeliaFailover {

    enum class Verdict { CONNECTED, NO_INTERNET, SWITCH }

    /** Wait after the core reports "running" before probing, so the tunnel is up. */
    const val PROBE_DELAY_MS = 1500L
    /** The core gives up after 12 s per URL and tries two URLs. */
    const val PROBE_TIMEOUT_MS = 30_000L

    val DIRECT_PROBE_URLS = listOf("https://ya.ru/", "https://www.google.com/generate_204")

    fun verdict(tunnelDelayMs: Long, directOk: Boolean): Verdict = when {
        tunnelDelayMs >= 0 -> Verdict.CONNECTED
        !directOk -> Verdict.NO_INTERNET
        else -> Verdict.SWITCH
    }

    /** Next location after [current] (wrapping around) that was not tried yet, or null. */
    fun next(order: List<String>, current: String?, tried: Set<String>): String? {
        if (order.isEmpty()) return null
        val start = order.indexOf(current)
        for (i in 1..order.size) {
            val guid = order[Math.floorMod(start + i, order.size)]
            if (guid !in tried) return guid
        }
        return null
    }

    private val directClient: OkHttpClient by lazy {
        AmeliaHttp.client.newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /** Blocking. True if the device itself can reach Yandex (or Google) without the VPN. */
    fun directInternetOk(): Boolean = DIRECT_PROBE_URLS.any { url ->
        try {
            directClient.newCall(AmeliaHttp.get(url)).execute().use { it.code in 200..399 }
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "Amelia: direct probe failed for $url", e)
            false
        }
    }
}
