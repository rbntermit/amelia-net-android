package com.v2ray.ang.amelia

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress

/**
 * The phone's own network (not the VPN) for /app/report and /app/probe, so the server sees the
 * provider's real address; and what kind of network it is, for the blocking map.
 * No permissions: network type from NetworkCapabilities, operator from
 * TelephonyManager.getNetworkOperatorName (allowed without READ_PHONE_STATE).
 */
object AmeliaNetwork {

    const val NET_WIFI = "wifi"
    const val NET_MOBILE = "mobile"
    const val NET_OTHER = "other"

    const val PATH_DIRECT = "direct"
    const val PATH_TUNNEL = "tunnel"

    /** /app/report and /app/probe go to these servers, in this order (contract with the server). */
    val POST_BASE_URLS = AmeliaConfig.SUB_BASE_URLS.filter { !it.contains(".net:") }

    /** Request bodies above this are cut down by the caller (server limit). */
    const val MAX_BODY_BYTES = 64 * 1024

    private val JSON = "application/json; charset=utf-8".toMediaType()

    data class Info(val net: String, val op: String)

    fun info(context: Context): Info {
        val caps = physicalNetwork(context)?.let { capabilities(context, it) }
        val net = when {
            caps == null -> NET_OTHER
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NET_WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NET_MOBILE
            else -> NET_OTHER
        }
        val op = try {
            context.getSystemService(TelephonyManager::class.java)?.networkOperatorName.orEmpty().trim()
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "Amelia: cannot read the operator name", e)
            ""
        }
        return Info(net, op.take(64))
    }

    private fun capabilities(context: Context, network: Network): NetworkCapabilities? =
        context.getSystemService(ConnectivityManager::class.java)?.getNetworkCapabilities(network)

    /**
     * A connected network that is not a VPN. Our app is excluded from its own VPN, so its
     * default network normally is exactly that; the scan of all networks is the fallback.
     */
    fun physicalNetwork(context: Context): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        cm.activeNetwork?.let { n ->
            val caps = cm.getNetworkCapabilities(n)
            if (caps != null && isPhysical(caps)) return n
        }
        return allNetworksCompat(cm).firstOrNull { n -> cm.getNetworkCapabilities(n)?.let(::isPhysical) == true }
    }

    private fun isPhysical(caps: NetworkCapabilities) =
        !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

    /**
     * ConnectivityManager.getAllNetworks is deprecated since API 31 in favour of network
     * callbacks, which need a long-lived registration; this is a one-shot lookup used only when
     * the default network is the VPN. Remove when minSdk allows a callback-based snapshot here.
     */
    @Suppress("DEPRECATION")
    private fun allNetworksCompat(cm: ConnectivityManager): List<Network> = cm.allNetworks.toList()

    private fun clientOn(network: Network): OkHttpClient =
        AmeliaHttp.client.newBuilder()
            .socketFactory(network.socketFactory)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = network.getAllByName(hostname).toList()
            })
            .build()

    /**
     * Blocking. POSTs [json] to each of [POST_BASE_URLS] + [path], first over the phone's own
     * network, then through the tunnel. [json] is built per path, so the body can say which
     * path it took. Returns the answer body and the path, or null.
     */
    fun post(context: Context, path: String, json: (String) -> String): Pair<String, String>? {
        val routes = buildList {
            physicalNetwork(context)?.let { add(PATH_DIRECT to clientOn(it)) }
            if (AmeliaHttp.tunnelAvailable()) add(PATH_TUNNEL to AmeliaHttp.clientFor(AmeliaHttp.Route.TUNNEL))
        }
        for ((name, client) in routes) {
            val body = json(name)
            for (base in POST_BASE_URLS) {
                try {
                    val request = Request.Builder()
                        .url(base + path)
                        .header("User-Agent", AmeliaVersions.userAgent())
                        .post(body.toRequestBody(JSON))
                        .build()
                    client.newCall(request).execute().use { resp ->
                        if (resp.isSuccessful) return resp.body.string() to name
                        LogUtil.w(AppConfig.TAG, "Amelia: POST $path HTTP ${resp.code} from $base ($name)")
                    }
                } catch (e: Exception) {
                    LogUtil.w(AppConfig.TAG, "Amelia: POST $path failed at $base ($name)", e)
                }
            }
        }
        return null
    }
}
