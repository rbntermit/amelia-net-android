package com.v2ray.ang.amelia

import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 1.10.0 "Проверка сервисов" in the "🩺 Не работает?" window: popular services opened through the
 * tunnel, all at once, up to [TIMEOUT_MS] each. Only whether each one opens; nothing is sent to us.
 */
object AmeliaServices {

    /** [name]: as people call it; [gemini]: the answer is also read for "not in your country". */
    data class Service(val name: String, val url: String, val gemini: Boolean = false)

    val SERVICES = listOf(
        Service("YouTube", "https://www.youtube.com/generate_204"),
        Service("Gemini", "https://gemini.google.com/", gemini = true),
        Service("ChatGPT", "https://chatgpt.com/"),
        Service("Instagram", "https://www.instagram.com/"),
        Service("Telegram", "https://web.telegram.org/"),
        Service("Discord", "https://discord.com/"),
    )

    const val TIMEOUT_MS = 8_000L

    /** How much of Gemini's page is read for the country check. */
    private const val PAGE_BYTES = 256L * 1024

    enum class Status {
        /** ✅ it opens. */
        OK,
        /** ❌ timeout, connection error or a server error (5xx). */
        FAIL,
        /** ⚠️ Gemini answered "not available in your country". */
        NOT_IN_COUNTRY,
    }

    data class Result(val name: String, val status: Status)

    // ---- pure (unit-tested) -------------------------------------------------------------------

    /** Any answer below 500 means the service is reachable; no answer or 5xx means it is not. */
    fun status(code: Int?): Status = if (code != null && code in 100..499) Status.OK else Status.FAIL

    /** Where Google sends people from countries without Gemini (the page about where it is available). */
    private val GEMINI_UNAVAILABLE_URL = listOf(
        "support.google.com/gemini/answer/13575153",
        "gemini.google.com/unavailable",
        "gemini.google.com/faq#location",
        "/unsupported",
        "not-available",
        "unavailable-country",
    )

    /** The texts of that page (also in Russian, for the Russian page). */
    private val GEMINI_UNAVAILABLE_TEXT = listOf(
        "isn't currently supported in your country",
        "isn’t currently supported in your country",
        "is not currently supported in your country",
        "isn't available in your country",
        "isn’t available in your country",
        "is not available in your country",
        "not supported in your country",
        "недоступен в вашей стране",
        "недоступно в вашей стране",
        "не поддерживается в вашей стране",
    )

    /**
     * Gemini's answer looks like "not available in your country": a redirect to the page about where it
     * works, or that text on the page. Only when sure; otherwise false and the result is ✅/❌.
     */
    fun geminiBlocked(finalUrl: String?, page: String?): Boolean {
        val url = finalUrl.orEmpty().lowercase()
        if (GEMINI_UNAVAILABLE_URL.any { it in url }) return true
        val text = page.orEmpty().lowercase()
        return text.isNotEmpty() && GEMINI_UNAVAILABLE_TEXT.any { it in text }
    }

    /** One service's result from what came back ([code] null: no answer at all). */
    fun judge(s: Service, code: Int?, finalUrl: String?, page: String?): Status = when {
        s.gemini && code != null && geminiBlocked(finalUrl, page) -> Status.NOT_IN_COUNTRY
        else -> status(code)
    }

    // ---- requests ---------------------------------------------------------------------------

    /** All services at once through the tunnel (the core's local inbound); each one at most [TIMEOUT_MS]. */
    suspend fun checkAll(): List<Result> = coroutineScope {
        SERVICES.map { s -> async(Dispatchers.IO) { Result(s.name, check(s)) } }.awaitAll()
    }

    private fun check(s: Service): Status = try {
        val client = AmeliaHttp.clientFor(AmeliaHttp.Route.TUNNEL).newBuilder()
            .callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
        client.newCall(Request.Builder().url(s.url).build()).execute().use { resp ->
            val page = if (s.gemini) runCatching { resp.peekBody(PAGE_BYTES).string() }.getOrNull() else null
            judge(s, resp.code, resp.request.url.toString(), page)
        }
    } catch (e: Exception) {
        LogUtil.w(AppConfig.TAG, "Amelia: service check ${s.name} failed", e)
        Status.FAIL
    }
}
