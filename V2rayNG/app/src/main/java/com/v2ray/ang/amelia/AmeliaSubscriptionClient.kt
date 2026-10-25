package com.v2ray.ang.amelia

import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Downloads the subscription for a key. Unlike upstream HttpUtil it keeps the HTTP status
 * (404 = unknown key) and the response headers, and walks [AmeliaHttp.plan]: through the
 * tunnel when the VPN is on, then directly, over the Russian mirrors and the main domains.
 */
object AmeliaSubscriptionClient {

    sealed class Result {
        data class Ok(
            val body: String,
            val info: AmeliaSubInfo,
            val baseUrl: String,
            val route: AmeliaHttp.Route = AmeliaHttp.Route.DIRECT,
        ) : Result()
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

    val userAgent: String get() = AmeliaHttp.userAgent

    /**
     * Tries attempts in order until one returns the subscription. A failure (timeout, TLS,
     * 5xx, empty body) moves on; 404 is remembered but also moves on, so a stale server cannot
     * hide a key another one knows.
     */
    internal fun fetchWith(plan: List<AmeliaHttp.Attempt>, attempt: (AmeliaHttp.Attempt) -> Attempt): Result {
        var sawNotFound = false
        for (a in plan) {
            when (val r = attempt(a)) {
                is Attempt.Ok -> return Result.Ok(r.body, r.info, a.base, a.route)
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
    fun fetch(key: String, preferredBaseUrl: String? = AmeliaHttp.lastBase()): Result {
        val plan = AmeliaHttp.plan(AmeliaHttp.orderBases(preferredBaseUrl), AmeliaHttp.tunnelAvailable())
        val result = fetchWith(plan) { a -> request(a, key) }
        if (result is Result.Ok) AmeliaHttp.rememberBase(result.baseUrl)
        return result
    }

    /**
     * 1.6.1, blocking: the subscription from [AmeliaConfig.RESUBSCRIBE_BASE_URLS] over [direct] (the phone's own
     * network, never the tunnel), [timeoutMs] for the whole download, shared by the addresses in turn.
     */
    fun fetchDirect(key: String, direct: OkHttpClient, timeoutMs: Long): Result {
        val deadline = System.currentTimeMillis() + timeoutMs
        val plan = AmeliaHttp.plan(AmeliaConfig.RESUBSCRIBE_BASE_URLS, tunnel = false)
        val result = fetchWith(plan) { a ->
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) Attempt.Failed
            else request(a, key, direct.newBuilder().callTimeout(left, TimeUnit.MILLISECONDS).build())
        }
        if (result is Result.Ok) AmeliaHttp.rememberBase(result.baseUrl)
        return result
    }

    private fun request(a: AmeliaHttp.Attempt, key: String, clientOverride: OkHttpClient? = null): Attempt {
        return try {
            val request = Request.Builder()
                .url(AmeliaKey.subscriptionUrl(a.base, key))
                .header("User-Agent", userAgent)
                .header("Accept", "text/plain, */*")
                .build()
            val client = clientOverride ?: AmeliaHttp.clientFor(a.route).newBuilder()
                .callTimeout(AmeliaConfig.HTTP_TIMEOUT_SECONDS * 2, TimeUnit.SECONDS)
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
                            LogUtil.w(AppConfig.TAG, "Amelia: empty subscription from ${a.base} (${a.route})")
                            Attempt.Failed
                        }
                    }

                    response.code == 404 -> Attempt.NotFound
                    else -> {
                        LogUtil.w(AppConfig.TAG, "Amelia: subscription HTTP ${response.code} from ${a.base} (${a.route})")
                        Attempt.Failed
                    }
                }
            }
        } catch (e: Exception) {
            // Never log the URL: it contains the key.
            LogUtil.w(AppConfig.TAG, "Amelia: subscription fetch failed from ${a.base} (${a.route})", e)
            Attempt.Failed
        }
    }
}
