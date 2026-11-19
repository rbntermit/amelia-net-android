package com.v2ray.ang.amelia

import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 1.5.7: the service's site on the Russian subscription mirror (opens without VPN and without
 * Telegram): "Личный кабинет", payment by card / СБП and gifts. Opened in the browser.
 * Only the person's key goes into the links, nothing else.
 *
 * The cabinet and payment open with a one-time link ([token]) so the browser receives only
 * the temporary token.
 */
object AmeliaSite {

    /**
     * Main address, then the spare ones: msk2 → msk1 → spb1 (1.7.3), the Russian mirrors of the
     * subscription in the order of AmeliaConfig.SITE_BASE_URLS.
     */
    val BASES: List<String> get() = AmeliaConfig.SITE_BASE_URLS

    /** The addresses must answer within this (all are asked at the same time), or the spare one is opened. */
    const val CHECK_TIMEOUT_MS = 5_000L

    /** "🎁 Подарить подписку": no key, the gift is not for this subscription. */
    fun giftUrl(base: String): String = "$base/buy?gift=1"

    enum class Page(val botScreen: AmeliaConfig.BotScreen) {
        CABINET(AmeliaConfig.BotScreen.MY_SUB),
        PAYMENT(AmeliaConfig.BotScreen.PAY),
    }

    /** 1.5.8: tariffs and payment for someone without a key yet; the key is shown on the page after paying. */
    fun shopUrl(base: String): String = "$base/shop"

    /** 1.5.8: what "Тарифы и оплата" on the key screen does. */
    enum class KeyPay {
        /** Telegram is installed: "Купить в Telegram-боте" or "Купить на сайте". */
        ASK,
        /** No Telegram: the site's shop straight away (the bot cannot be reached). */
        SITE,
    }

    fun keyPay(telegramInstalled: Boolean): KeyPay = if (telegramInstalled) KeyPay.ASK else KeyPay.SITE

    /** 1.8.0: the service's state page, open without VPN and without Telegram. */
    fun statusUrl(base: String): String = "$base/status"

    /** 1.8.0: what "🩺 Не работает?" does. */
    enum class NotWorking {
        /** Check every location and offer to send the result, as before 1.8.0. */
        REPORT,
        /** The VPN is off and Telegram is not installed: the state page on the site ([statusUrl]). */
        STATUS_PAGE,
    }

    fun notWorking(vpnOn: Boolean, telegramInstalled: Boolean): NotWorking =
        if (!vpnOn && !telegramInstalled) NotWorking.STATUS_PAGE else NotWorking.REPORT

    /**
     * 1.7.3, pure: the first of [BASES] that answered ([answered] in the same order); none
     * answered — the spare one (msk1), as before spb1: a new mirror may not answer yet.
     */
    fun pickBase(answered: List<Boolean>): String =
        BASES.indices.firstOrNull { answered.getOrNull(it) == true }?.let { BASES[it] } ?: BASES[1]

    private val client by lazy {
        OkHttpClient.Builder()
            .callTimeout(CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .build()
    }

    // ---- 1.7.0: the key never goes into the browser's address ------------------------------

    /**
     * POST <base>/app/site-token {"key": …} → {"ok": true, "t": "t.…", "ttl": 1800, "me": "/me/t.…",
     * "buy": "/buy?sid=t.…"}; the paths are on the same base. 429: too often, 503: not available.
     */
    const val TOKEN_PATH = "/app/site-token"
    /** The whole request, both addresses together. */
    const val TOKEN_TIMEOUT_MS = 5_000L
    /** A token is used at most this long, whatever its ttl says. */
    const val TOKEN_MAX_CACHE_MS = 20L * 60 * 1000

    /** A one-time link pair from the server, valid until [until] (epoch millis). */
    data class Token(val base: String, val me: String, val buy: String, val until: Long) {
        val cabinetUrl: String get() = base + me
        val buyUrl: String get() = base + buy
        fun url(page: Page): String = when (page) {
            Page.CABINET -> cabinetUrl
            Page.PAYMENT -> buyUrl
        }
    }

    private class Wire(val ok: Boolean? = null, val t: String? = null, val ttl: Long? = null, val me: String? = null, val buy: String? = null)
    private val TOKEN_ID = Regex("""t\.[A-Za-z0-9._~-]+""")

    /** Pure: the request body. */
    fun tokenRequest(key: String): String = com.google.gson.JsonObject().apply { addProperty("key", key) }.toString()

    /**
     * Pure: the answer of [base] at [now], or null when it is not a usable token. The paths must be
     * on the same site ("/…"): never a link to somewhere else. Kept for at most 20 minutes.
     */
    fun parseToken(base: String, body: String?, now: Long): Token? {
        val w = body?.let { runCatching { com.google.gson.Gson().fromJson(it, Wire::class.java) }.getOrNull() } ?: return null
        val id = w.t?.takeIf(TOKEN_ID::matches) ?: return null
        if (w.ok != true) return null
        val me = w.me?.takeIf { it == "/me/$id" } ?: return null
        val buy = w.buy?.takeIf { it == "/buy?sid=$id" } ?: return null
        val keep = w.ttl?.takeIf { it > 0 }?.coerceAtMost(TOKEN_MAX_CACHE_MS / 1000)?.times(1000) ?: TOKEN_MAX_CACHE_MS
        return Token(base, me, buy, now + keep)
    }

    /** Pure: a token for the same key that has not run out (a minute is left for the page to open). */
    fun usable(t: Token?, now: Long): Boolean = t != null && now < t.until - 60_000L

    @Volatile private var cached: Pair<String, Token>? = null

    /**
     * Blocking, any thread: a one-time link pair for [key] from msk2, then msk1, then spb1 (1.7.3;
     * [BASES]), over the phone's own network (not the tunnel), 5 s in all.
     */
    fun token(context: android.content.Context, key: String): Token? {
        val now = System.currentTimeMillis()
        cached?.let { (k, t) -> if (k == key && usable(t, now)) return t }
        val direct = AmeliaNetwork.directClient(context, AmeliaHttp.client).newBuilder()
            .callTimeout(TOKEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .build()
        val deadline = now + TOKEN_TIMEOUT_MS
        for (base in BASES) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) break
            try {
                val request = Request.Builder()
                    .url(base + TOKEN_PATH)
                    .header("User-Agent", AmeliaVersions.userAgent())
                    .post(tokenRequest(key).toRequestBody(JSON))
                    .build()
                direct.newBuilder().callTimeout(left, TimeUnit.MILLISECONDS).build().newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        parseToken(base, resp.body.string(), System.currentTimeMillis())?.let { t ->
                            cached = key to t
                            return t
                        }
                    }
                    LogUtil.w(AppConfig.TAG, "Amelia site: no link token from $base, HTTP ${resp.code}")
                }
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "Amelia site: link token request failed at $base", e)
            }
        }
        return null
    }

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /**
     * Any HTTP answer counts. 1.7.3: every address is asked at the same time (still 5 s at most)
     * and the first one in [BASES] order that answered is opened; none — the spare one.
     */
    suspend fun base(): String = withContext(Dispatchers.IO) {
        val answered = BASES.map { b ->
            async {
                runCatching {
                    client.newCall(Request.Builder().url("$b/").head().build()).execute().use { true }
                }.getOrElse {
                    LogUtil.w(AppConfig.TAG, "Amelia site: $b did not answer", it)
                    false
                }
            }
        }.awaitAll()
        pickBase(answered)
    }
}
