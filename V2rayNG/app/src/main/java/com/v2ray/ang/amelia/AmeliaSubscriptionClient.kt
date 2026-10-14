package com.v2ray.ang.amelia

import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Downloads the subscription for a key. Unlike upstream HttpUtil it keeps the HTTP status
 * (404 = unknown key) and the response headers, and falls back to the backup domain.
 * The app's own traffic bypasses the VPN tunnel, so this always uses the device network.
 */
object AmeliaSubscriptionClient {

    sealed class Result {
        data class Ok(val body: String, val info: AmeliaSubInfo, val baseUrl: String) : Result()
        /** A server answered 404 and none answered with a subscription: no such key. */
        data object UnknownKey : Result()
        /** No server could be reached or all returned errors. */
        data object NetworkError : Result()
    }

    /** Outcome of one request to one server. */
    internal sealed class Attempt {
        data class Ok(val body: String, val info: AmeliaSubInfo) : Attempt()
        data object NotFound : Attempt()
        data object Failed : Attempt()
    }

    private val client: OkHttpClient by lazy {
        AmeliaHttp.client.newBuilder()
            .callTimeout(AmeliaConfig.HTTP_TIMEOUT_SECONDS * 2, TimeUnit.SECONDS)
            .build()
    }

    val userAgent: String get() = AmeliaHttp.userAgent

    /** The server that answered last time goes first, then the rest in config order. */
    internal fun orderBases(preferred: String?, all: List<String> = AmeliaConfig.SUB_BASE_URLS): List<String> =
        buildList {
            preferred?.takeIf { it in all }?.let { add(it) }
            all.forEach { if (it !in this) add(it) }
        }

    /**
     * Tries servers in order until one returns the subscription. A failure (timeout, TLS,
     * 5xx, empty body) moves on to the next server; 404 is remembered but also moves on,
     * so a stale primary cannot hide a key the backup knows.
     */
    internal fun fetchWith(bases: List<String>, attempt: (String) -> Attempt): Result {
        var sawNotFound = false
        for (base in bases) {
            when (val a = attempt(base)) {
                is Attempt.Ok -> return Result.Ok(a.body, a.info, base)
                Attempt.NotFound -> sawNotFound = true
                Attempt.Failed -> Unit
            }
        }
        return if (sawNotFound) Result.UnknownKey else Result.NetworkError
    }

    /**
     * Blocking; call from Dispatchers.IO.
     * @param preferredBaseUrl the server that answered last time, tried first.
     */
    fun fetch(key: String, preferredBaseUrl: String? = null): Result =
        fetchWith(orderBases(preferredBaseUrl)) { base -> request(base, key) }

    private fun request(base: String, key: String): Attempt {
        return try {
            val request = Request.Builder()
                .url(AmeliaKey.subscriptionUrl(base, key))
                .header("User-Agent", userAgent)
                .header("Accept", "text/plain, */*")
                .build()
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> {
                        val body = response.body.string()
                        if (body.isNotBlank()) {
                            Attempt.Ok(
                                body,
                                AmeliaSubInfo.fromHeaders(
                                    header = { response.header(it) },
                                    now = System.currentTimeMillis(),
                                ),
                            )
                        } else {
                            LogUtil.w(AppConfig.TAG, "Amelia: empty subscription from $base")
                            Attempt.Failed
                        }
                    }

                    response.code == 404 -> Attempt.NotFound
                    else -> {
                        LogUtil.w(AppConfig.TAG, "Amelia: subscription HTTP ${response.code} from $base")
                        Attempt.Failed
                    }
                }
            }
        } catch (e: Exception) {
            // Never log the URL: it contains the key.
            LogUtil.w(AppConfig.TAG, "Amelia: subscription fetch failed from $base", e)
            Attempt.Failed
        }
    }
}
