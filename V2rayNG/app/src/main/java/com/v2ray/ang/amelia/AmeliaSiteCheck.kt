package com.v2ray.ang.amelia

import android.content.Context
import com.google.gson.JsonParser
import com.v2ray.ang.BuildConfig

/**
 * 1.10.0 "Сайт не открывается" in the "🩺 Не работает?" window: the person names a site, we look at it.
 * POST /app/sitecheck on the main domain, then .net:
 * `{"iid": <install id, as /app/crash>, "d": "<domain>", "app": "<versionName>", "vpn": true, "loc": "<location>"}`.
 * No key and no address. The server takes up to 5 per hour from one install and answers ok to the rest too.
 */
object AmeliaSiteCheck {

    const val PATH = "/app/sitecheck"

    /** The main domain, then its backup. */
    val BASES: List<String> = AmeliaConfig.BACKUP_BASE_URLS

    enum class Outcome {
        /** `{"ok": true}`: "Отправили, разберёмся". */
        SENT,
        /** 400: "Не похоже на адрес сайта" (also when the app sees it before sending). */
        NOT_A_SITE,
        /** No answer: "Не получилось отправить, попробуйте позже". */
        FAILED,
    }

    // ---- pure (unit-tested) -------------------------------------------------------------------

    /**
     * The domain as the server keeps it: no scheme, login, path, query, port or "www.", lower case,
     * no dot at the end. Null when it does not look like a site.
     */
    fun cleanDomain(input: String?): String? {
        var s = input?.trim()?.lowercase() ?: return null
        s = s.substringAfter("://", s)
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        s = s.substringAfterLast('@')
        s = s.substringBefore(':')
        s = s.trimEnd('.')
        while (s.startsWith("www.")) s = s.removePrefix("www.")
        if (s.length !in 3..253 || '.' !in s) return null
        val labels = s.split('.')
        val ok = labels.all { l ->
            l.length in 1..63 && !l.startsWith('-') && !l.endsWith('-') && l.all { it.isLetterOrDigit() || it == '-' }
        }
        // the last part is a word (com, ru, рф, xn--p1ai), not a number
        return s.takeIf { ok && labels.last().any(Char::isLetter) }
    }

    fun body(iid: String, domain: String, vpn: Boolean, location: String?, app: String = BuildConfig.VERSION_NAME): String =
        AmeliaProbe.wireGson.toJson(
            mapOf("iid" to iid, "d" to domain, "app" to app, "vpn" to vpn, "loc" to location.orEmpty().take(100)),
        )

    /** [code]: HTTP code of the answer (null: nobody answered); [answer]: its body. */
    fun outcome(code: Int?, answer: String?): Outcome = when {
        code == 400 -> Outcome.NOT_A_SITE
        code in 200..299 && okTrue(answer) -> Outcome.SENT
        else -> Outcome.FAILED
    }

    // ---- 1.10.1: the address field and "Отправить в поддержку" (pure, unit-tested) -------------------

    /**
     * What "Отправить в поддержку" does with the "Адрес сайта" field. In 1.10.0 a site typed there and sent
     * with the bottom button was lost: only the report went. [domain]: the cleaned site for the report's line;
     * [sendSiteCheck]: /app/sitecheck goes too (not when "Проверить этот сайт" already sent this very site in
     * this window); [notASite]: something is typed that is no site (the field says so, the report still goes).
     */
    data class WithReport(val domain: String?, val sendSiteCheck: Boolean, val notASite: Boolean = false)

    fun withReport(input: String?, sentByButton: String?): WithReport {
        val domain = cleanDomain(input) ?: return WithReport(null, false, notASite = !input.isNullOrBlank())
        return WithReport(domain, sendSiteCheck = domain != sentByButton)
    }

    const val REPORT_LINE = "Не открывается сайт: "

    /** The report's comment with "Не открывается сайт: <домен>" first, all within [max] (the site line is kept). */
    fun reportComment(comment: String, domain: String?, max: Int = AmeliaReport.MAX_COMMENT): String {
        if (domain == null) return comment
        val line = REPORT_LINE + domain
        val rest = comment.trim()
        if (rest.isEmpty()) return line.take(max)
        return (line + "\n" + rest).take(max)
    }

    private fun okTrue(json: String?): Boolean = runCatching {
        JsonParser.parseString(json.orEmpty()).asJsonObject.get("ok")?.asBoolean == true
    }.getOrDefault(false)

    // ---- request ----------------------------------------------------------------------------

    /** Blocking. [input] as typed; checked here first, so nothing is sent for "привет". */
    fun send(context: Context, input: String, vpn: Boolean, location: String?): Outcome {
        val domain = cleanDomain(input) ?: return Outcome.NOT_A_SITE
        val iid = AmeliaProbe.installId()
        val answer = AmeliaNetwork.postAnswer(context, PATH, final = { it == 400 }, bases = BASES, tunnelFirst = true) {
            body(iid, domain, vpn, location)
        }
        return outcome(answer?.code, answer?.body)
    }
}
