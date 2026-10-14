package com.v2ray.ang.amelia

import com.v2ray.ang.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** One HTTP client for subscription, geo bases and app updates. Traffic bypasses the VPN tunnel. */
object AmeliaHttp {

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // Short connect timeout: a dead primary must not delay the switch to the backup domain
            .connectTimeout(AmeliaConfig.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(AmeliaConfig.HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    val userAgent: String
        get() = "AmeliaNet/${BuildConfig.VERSION_NAME} v2rayNG/${BuildConfig.UPSTREAM_VERSION}"

    fun get(url: String): Request =
        Request.Builder().url(url).header("User-Agent", userAgent).build()
}
