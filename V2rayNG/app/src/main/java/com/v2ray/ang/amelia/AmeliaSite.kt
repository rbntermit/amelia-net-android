package com.v2ray.ang.amelia

import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 1.5.7: the service's site on the Russian subscription mirror (opens without VPN and without
 * Telegram): "Личный кабинет", payment by card / СБП and gifts. Opened in the browser.
 * Only the person's key goes into the links, nothing else.
 */
object AmeliaSite {

    /** Main address, then the spare one (same mirrors as the subscription, but msk2 first). */
    val BASES = listOf(
        "https://msk2.ameliajustamelia.com:9443",
        "https://msk1.ameliajustamelia.com:9443",
    )

    /** The main address must answer within this, or the spare one is opened. */
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

    /** Pure: the main address when it answered, otherwise the spare one. */
    fun pickBase(mainAnswered: Boolean): String = if (mainAnswered) BASES[0] else BASES[1]

    private val client by lazy {
        OkHttpClient.Builder()
            .callTimeout(CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .build()
    }

    /** Any HTTP answer from the main address counts; no answer within 5 s → the spare one. */
    suspend fun base(): String = withContext(Dispatchers.IO) {
        val answered = runCatching {
            client.newCall(Request.Builder().url(BASES[0] + "/").head().build()).execute().use { true }
        }.getOrElse {
            LogUtil.w(AppConfig.TAG, "Amelia site: main address did not answer, using the spare one", it)
            false
        }
        pickBase(answered)
    }
}
