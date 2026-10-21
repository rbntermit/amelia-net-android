package com.v2ray.ang.amelia

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import com.google.gson.annotations.SerializedName
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.handler.MmkvManager

/**
 * "Не работает — сообщить": every location is checked with a real request through it
 * (the same test as "ping all"), the person sees which ones work, and can send the result to
 * POST /app/report. The answer comes from the bot @amelianet_bot.
 * The body follows the contract with the server (see README, "Контракт report/probe").
 */
object AmeliaReport {

    const val PATH = "/app/report"
    /** All locations are checked in parallel; the core gives up on one after ~12 s. */
    const val TEST_TIMEOUT_MS = 30_000L
    const val MAX_COMMENT = 500
    /** A location without a result: the app stopped waiting for it after 5 s. */
    const val NO_RESULT_ERROR = AmeliaCheck.TIMEOUT_ERROR

    /** One location's result, as the test service reported it. */
    data class Check(val guid: String, val ms: Long, val stage: String, val error: String?)

    data class Server(
        val name: String,
        val host: String,
        val port: Int?,
        val ok: Boolean,
        val ms: Long?,
        val stage: String,
        val err: String?,
    )

    data class Body(
        val key: String,
        val ts: Long,
        val app: String,
        val code: Int,
        val xray: String,
        val hy2: String,
        val android: String,
        val device: String,
        val tv: Boolean,
        val net: String,
        val op: String,
        val path: String,
        val active: String?,
        @SerializedName("bypass_ru") val bypassRu: Boolean,
        val comment: String,
        val servers: List<Server>,
        /** 1.2.0: how long the check of all locations took, ms. */
        @SerializedName("check_ms") val checkMs: Long? = null,
        /** 1.2.0: the last automatic switches (AmeliaEvents), oldest first. */
        val events: List<AmeliaEvents.Event> = emptyList(),
    )

    // ---- pure logic (unit-tested) ---------------------------------------------------------

    /** Locations in subscription order with their check; a location without a result failed. */
    fun servers(locations: List<Location>, checks: Map<String, Check>): List<Server> = locations.map { l ->
        val c = checks[l.guid]
        val ok = c != null && c.ms >= 0
        Server(
            name = l.name,
            host = l.host,
            port = l.port,
            ok = ok,
            ms = c?.ms?.takeIf { ok },
            stage = when {
                c == null -> AmeliaPingStage.DATA
                ok -> AmeliaPingStage.OK
                else -> c.stage
            },
            err = if (ok) null else (c?.error ?: NO_RESULT_ERROR).take(AmeliaPingStage.MAX_ERROR),
        )
    }

    /** Server details the report needs; the name is only for people, never for /app/probe. */
    data class Location(val guid: String, val name: String, val host: String, val port: Int?)

    /**
     * Names that work and names that do not, in subscription order. Reserve locations (only
     * Telegram) are never listed as not working: they are for paying and reaching the bot.
     */
    fun split(servers: List<Server>): Pair<List<String>, List<String>> =
        servers.filter { it.ok }.map { it.name } to
            servers.filter { !it.ok && !AmeliaReserve.isReserve(it.name) }.map { it.name }

    /**
     * The body as JSON within [AmeliaNetwork.MAX_BODY_BYTES]: long error texts are cut first,
     * then servers are dropped from the end (each keeps its own result).
     */
    fun toJson(body: Body): String {
        var b = body.copy(comment = body.comment.take(MAX_COMMENT))
        var json = AmeliaProbe.wireGson.toJson(b)
        if (json.toByteArray().size <= AmeliaNetwork.MAX_BODY_BYTES) return json
        b = b.copy(servers = b.servers.map { it.copy(err = it.err?.take(60)) })
        json = AmeliaProbe.wireGson.toJson(b)
        while (json.toByteArray().size > AmeliaNetwork.MAX_BODY_BYTES && b.servers.isNotEmpty()) {
            b = b.copy(servers = b.servers.dropLast(1))
            json = AmeliaProbe.wireGson.toJson(b)
        }
        return json
    }

    data class Answer(val ok: Boolean = false, val id: String? = null)

    /** `{"ok": true, "id": "R1234"}` → "R1234"; null when the server did not accept it. */
    fun parseAnswer(json: String?): String? {
        // A proxy or captive portal may answer with HTML: that is "not accepted", not an error to log
        val a = json?.let { runCatching { AmeliaProbe.wireGson.fromJson(it, Answer::class.java) }.getOrNull() } ?: return null
        return a.id?.takeIf { a.ok && it.isNotBlank() }
    }

    /**
     * Text to paste into the bot when the server cannot be reached. Same facts as the report,
     * without the subscription key.
     */
    fun plainText(body: Body): String = buildString {
        appendLine("Amelia.Net: не работает")
        appendLine("Приложение ${body.app} (${body.code}), Xray ${body.xray}, Android ${body.android}, ${body.device}")
        appendLine("Сеть: ${body.net}${if (body.op.isNotBlank()) ", " + body.op else ""}")
        body.active?.let { appendLine("Локация: $it") }
        appendLine("Приложения мимо VPN: ${if (body.bypassRu) "да" else "нет"}")
        if (body.comment.isNotBlank()) appendLine("Что случилось: ${body.comment}")
        appendLine()
        body.servers.forEach { s ->
            val result = if (s.ok) "${s.ms} мс" else "${s.stage}: ${s.err.orEmpty()}"
            appendLine("${s.name} (${s.host}:${s.port ?: "?"}) — $result")
        }
    }.trimEnd()

    // ---- device facts ---------------------------------------------------------------------

    fun locations(): List<Location> =
        MmkvManager.decodeServerList(AmeliaConfig.SUBSCRIPTION_ID).mapNotNull { guid ->
            MmkvManager.decodeServerConfig(guid)?.let {
                Location(guid, it.remarks, it.server.orEmpty(), it.serverPort?.toIntOrNull())
            }
        }

    fun isTv(context: Context): Boolean =
        context.getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    fun body(
        context: Context,
        key: String,
        servers: List<Server>,
        active: String?,
        comment: String,
        net: AmeliaNetwork.Info,
        path: String,
        checkMs: Long? = null,
    ) = Body(
        key = key,
        ts = System.currentTimeMillis() / 1000,
        app = BuildConfig.VERSION_NAME,
        code = BuildConfig.VERSION_CODE,
        xray = AmeliaVersions.xray(),
        hy2 = AmeliaVersions.hysteria2(),
        android = Build.VERSION.RELEASE.orEmpty(),
        device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
        tv = isTv(context),
        net = net.net,
        op = net.op,
        path = path,
        active = active,
        bypassRu = AmeliaApps.isEnabled(),
        comment = comment.trim().take(MAX_COMMENT),
        servers = servers,
        checkMs = checkMs,
        events = AmeliaEvents.list(),
    )

    /** Blocking. Sends the report; the report id on success, null when no server took it. */
    fun send(
        context: Context,
        key: String,
        servers: List<Server>,
        active: String?,
        comment: String,
        checkMs: Long? = null,
    ): String? {
        val net = AmeliaNetwork.info(context)
        val answer = AmeliaNetwork.post(context, PATH) { path ->
            toJson(body(context, key, servers, active, comment, net, path, checkMs))
        } ?: return null
        return parseAnswer(answer.first)
    }
}
