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
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 1.5.7: the service's site on the Russian subscription mirror (opens without VPN and without
 * Telegram): "Личный кабинет", payment by card / СБП and gifts. Opened in the browser.
 * Only the person's key goes into the links, nothing else.
 *
 * 1.7.0: the cabinet and payment open with a one-time link ([token]): the key stays out of the
 * browser's history, its sync and the address checks some browsers send to their servers. No
 * token (no answer, an error, an older server): the page opens with the key as before.
 */
object AmeliaSite {

    /**
     * Main address, then the spare ones: msk2 → msk1 → msk3 (1.7.3), the Russian mirrors of the
     * subscription in the order of AmeliaConfig.SITE_BASE_URLS.
     */
    val BASES: List<String> get() = AmeliaConfig.SITE_BASE_URLS

    /** The addresses must answer within this (all are asked at the same time), or the spare one is opened. */
    const val CHECK_TIMEOUT_MS = 5_000L

    private fun enc(key: String): String = URLEncoder.encode(key, "UTF-8").replace("+", "%20")

    /** "📊 Личный кабинет": term, devices connected now, traffic, renewal, gift, Telegram link. */
    fun cabinetUrl(base: String, key: String): String = "$base/me/${enc(key)}"

    /** Payment on the site (card or СБП). */
    fun buyUrl(base: String, key: String): String = "$base/buy?sid=${enc(key)}"

    /** "🎁 Подарить подписку": no key, the gift is not for this subscription. */
    fun giftUrl(base: String): String = "$base/buy?gift=1"

    /** "💳 Продлить": the bot when Telegram is installed (as before 1.5.7), otherwise the site. */
    fun renewUrl(telegramInstalled: Boolean, base: String, key: String?): String =
        if (telegramInstalled || key == null) AmeliaConfig.botUrl(AmeliaConfig.BotScreen.PAY) else buyUrl(base, key)

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
     * answered — the spare one (msk1), as before msk3: a new mirror may not answer yet.
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
    }

    private class Wire(val ok: Boolean? = null, val t: String? = null, val ttl: Long? = null, val me: String? = null, val buy: String? = null)

    /** Pure: the request body. */
    fun tokenRequest(key: String): String = com.google.gson.JsonObject().apply { addProperty("key", key) }.toString()

    /**
     * Pure: the answer of [base] at [now], or null when it is not a usable token. The paths must be
     * on the same site ("/…"): never a link to somewhere else. Kept for at most 20 minutes.
     */
    fun parseToken(base: String, body: String?, now: Long): Token? {
        val w = body?.let { runCatching { com.google.gson.Gson().fromJson(it, Wire::class.java) }.getOrNull() } ?: return null
        if (w.ok != true || w.t.isNullOrBlank()) return null
        fun path(p: String?) = p?.takeIf { it.startsWith("/") && !it.startsWith("//") && it.none(Char::isWhitespace) }
        val me = path(w.me) ?: return null
        val buy = path(w.buy) ?: return null
        val ttlMs = (w.ttl ?: 0L).coerceAtLeast(0L) * 1000
        val keep = if (ttlMs > 0) minOf(ttlMs, TOKEN_MAX_CACHE_MS) else TOKEN_MAX_CACHE_MS
        return Token(base, me, buy, now + keep)
    }

    /** Pure: a token for the same key that has not run out (a minute is left for the page to open). */
    fun usable(t: Token?, now: Long): Boolean = t != null && now < t.until - 60_000L

    @Volatile private var cached: Pair<String, Token>? = null

    /**
     * Blocking, any thread: a one-time link pair for [key] from msk2, then msk1, then msk3 (1.7.3;
     * [BASES]), over the phone's own network (not the tunnel), 5 s in all. Null: no answer or an
     * error → the caller opens the page with the key as before (better opened than not).
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
