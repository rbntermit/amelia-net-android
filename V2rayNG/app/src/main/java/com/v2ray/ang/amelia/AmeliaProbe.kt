package com.v2ray.ang.amelia

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * "Помогать находить блокировки": results of the server checks the app already makes (ping
 * all, the problem report, the daemon's connection check) are sent now and then to
 * POST /app/probe, so we can see which servers are cut on which networks.
 *
 * Sent: a random installation id (made once, not tied to the subscription), app version,
 * network type, the SIM operator's name, and per server only host, port, ok, ms, stage.
 * Never the key, UUIDs, phone number, server names or the sites people open.
 *
 * When ([decide], [shouldSend]):
 * - at most once per [PERIOD_MS] per network (periodic worker);
 * - [REGRESSION_DELAY_MS] after a server that worked stops working, at most once an hour;
 * - once after the network changes (wifi ↔ mobile, another operator).
 * Sending goes around the tunnel ([AmeliaNetwork.post]); errors are ignored silently.
 */
object AmeliaProbe {

    const val PREF_ENABLED = "amelia_probe_enabled"
    private const val PREF_STATE = "amelia_probe_state"
    private const val PREF_INSTALL_ID = "amelia_install_id"

    const val PERIOD_MS = 6L * 60 * 60 * 1000
    const val REGRESSION_DELAY_MS = 10L * 60 * 1000
    const val REGRESSION_GAP_MS = 60L * 60 * 1000
    /** A flapping connection must not send on every switch. */
    const val NET_CHANGE_GAP_MS = 60L * 60 * 1000
    /** Results older than this are not sent. */
    const val MAX_AGE_MS = PERIOD_MS

    const val PATH = "/app/probe"

    enum class Reason { PERIODIC, NET_CHANGE, REGRESSION }

    /** One server check as kept between sends. [net] is the network key it was made on. */
    data class Entry(
        val host: String = "",
        val port: Int = 0,
        val ok: Boolean = false,
        val ms: Long? = null,
        val stage: String = AmeliaPingStage.DATA,
        val ts: Long = 0L,
        val net: String = "",
        /** 1.5.9: measured in white-list mode (AmeliaWhitelist). */
        val wl: Boolean = false,
    )

    data class State(
        /** Latest result per "host:port". */
        val results: Map<String, Entry> = emptyMap(),
        /** Last successful send per network key. */
        val lastSent: Map<String, Long> = emptyMap(),
        /** Network key of the last successful send. */
        val lastNetKey: String? = null,
        val lastRegressionSent: Long = 0L,
    )

    // ---- pure logic (unit-tested) ---------------------------------------------------------

    fun netKey(info: AmeliaNetwork.Info): String = "${info.net}|${info.op}"

    /** New state after [entries] were measured, and the send it calls for (null = none). */
    fun decide(state: State, entries: List<Entry>, netKey: String, now: Long): Pair<State, Reason?> {
        var regressed = false
        val results = state.results.toMutableMap()
        for (e in entries) {
            val id = "${e.host}:${e.port}"
            val before = results[id]
            if (!e.ok && before != null && before.ok && before.net == netKey) regressed = true
            results[id] = e
        }
        val next = state.copy(results = results)
        val reason = when {
            state.lastNetKey != netKey -> Reason.NET_CHANGE
            regressed && now - state.lastRegressionSent >= REGRESSION_GAP_MS -> Reason.REGRESSION
            else -> null
        }
        return next to reason
    }

    /** What to send now for [reason], or null to skip. */
    fun shouldSend(state: State, netKey: String, now: Long, reason: Reason): List<Entry>? {
        val fresh = state.results.values.filter { it.net == netKey && now - it.ts in 0..MAX_AGE_MS }
        if (fresh.isEmpty()) return null
        val last = state.lastSent[netKey]
        val due = when (reason) {
            Reason.PERIODIC -> last == null || now - last >= PERIOD_MS
            Reason.NET_CHANGE -> state.lastNetKey != netKey && (last == null || now - last >= NET_CHANGE_GAP_MS)
            Reason.REGRESSION -> now - state.lastRegressionSent >= REGRESSION_GAP_MS
        }
        return if (due) fresh.sortedBy { "${it.host}:${it.port}" } else null
    }

    fun afterSend(state: State, netKey: String, now: Long, reason: Reason): State = state.copy(
        lastSent = state.lastSent + (netKey to now),
        lastNetKey = netKey,
        lastRegressionSent = if (reason == Reason.REGRESSION) now else state.lastRegressionSent,
    )

    data class ProbeServer(val host: String, val port: Int, val ok: Boolean, val ms: Long?, val stage: String)

    data class ProbeBody(
        val iid: String,
        val ts: Long,
        val app: String,
        val net: String,
        val op: String,
        val path: String,
        val servers: List<ProbeServer>,
        /** 1.5.9: written as "wl": true only in white-list mode ([json]). */
        @Transient val wl: Boolean = false,
    )

    /**
     * Contract with the server: no server names here, only host and port. 1.5.9: [whitelist]
     * (the mode is on now) or any result measured in the mode → "wl": true; the server then
     * does not count these results as servers being cut.
     */
    fun body(
        iid: String,
        nowSec: Long,
        info: AmeliaNetwork.Info,
        path: String,
        entries: List<Entry>,
        whitelist: Boolean = false,
    ): ProbeBody =
        ProbeBody(
            iid = iid,
            ts = nowSec,
            app = BuildConfig.VERSION_NAME,
            net = info.net,
            op = info.op,
            path = path,
            servers = entries.map { ProbeServer(it.host, it.port, it.ok, it.ms?.takeIf { ms -> it.ok && ms >= 0 }, it.stage) },
            wl = whitelist || entries.any { it.wl },
        )

    fun json(body: ProbeBody): String = withWl(wireGson.toJson(body), body.wl)

    /** 1.5.9: adds "wl": true to a /app/probe or /app/report body; nothing at all otherwise. */
    fun withWl(json: String, wl: Boolean): String {
        if (!wl) return json
        val tree = JsonParser.parseString(json).asJsonObject
        tree.addProperty("wl", true)
        return wireGson.toJson(tree)
    }

    /** JSON for /app/report and /app/probe: nulls are written ("ms": null), "<" is not escaped. */
    internal val wireGson: Gson = GsonBuilder().serializeNulls().disableHtmlEscaping().create()

    // ---- storage --------------------------------------------------------------------------

    fun isEnabled(): Boolean = MmkvManager.decodeSettingsBool(PREF_ENABLED, true)

    fun setEnabled(enabled: Boolean) = MmkvManager.encodeSettings(PREF_ENABLED, enabled)

    /** Random id of this installation, created once; unrelated to the subscription. */
    fun installId(): String {
        MmkvManager.decodeSettingsString(PREF_INSTALL_ID)?.takeIf { it.isNotBlank() }?.let { return it }
        val id = UUID.randomUUID().toString()
        MmkvManager.encodeSettings(PREF_INSTALL_ID, id)
        return id
    }

    private val lock = Any()

    private fun load(): State =
        MmkvManager.decodeSettingsString(PREF_STATE)?.takeIf { it.isNotBlank() }
            ?.let { JsonUtil.fromJsonSafe(it, State::class.java) } ?: State()

    private fun save(state: State) = MmkvManager.encodeSettings(PREF_STATE, JsonUtil.toJson(state))

    // ---- recording (any process) ----------------------------------------------------------

    /** A "ping all" or report result from the test service. */
    fun recordPing(context: Context, guid: String, delayMs: Long, stage: String, error: String?) {
        val config = MmkvManager.decodeServerConfig(guid) ?: return
        if (config.subscriptionId != AmeliaConfig.SUBSCRIPTION_ID) return
        val port = config.serverPort?.toIntOrNull() ?: return
        record(context, config.server.orEmpty(), port, delayMs >= 0, delayMs, stage)
    }

    /** The daemon's connection check of the selected location. */
    fun recordDaemon(context: Context, guid: String?, delayMs: Long, stage: String) {
        val config = guid?.let { MmkvManager.decodeServerConfig(it) } ?: return
        val port = config.serverPort?.toIntOrNull() ?: return
        record(context, config.server.orEmpty(), port, delayMs >= 0, delayMs, stage)
    }

    private fun record(context: Context, host: String, port: Int, ok: Boolean, ms: Long, stage: String) {
        if (!isEnabled() || host.isBlank()) return
        try {
            val key = netKey(AmeliaNetwork.info(context))
            val now = System.currentTimeMillis()
            val entry = Entry(host, port, ok, ms.takeIf { ok }, stage, now, key, wl = AmeliaWhitelist.isActive())
            val reason = synchronized(lock) {
                val (state, reason) = decide(load(), listOf(entry), key, now)
                save(state)
                reason
            }
            when (reason) {
                Reason.NET_CHANGE -> enqueue(context, reason, 0L, ExistingWorkPolicy.KEEP)
                Reason.REGRESSION -> enqueue(context, reason, REGRESSION_DELAY_MS, ExistingWorkPolicy.KEEP)
                else -> Unit
            }
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "Amelia: cannot record a probe result", e)
        }
    }

    // ---- sending --------------------------------------------------------------------------

    /** Blocking; called by [Worker]. Every failure is silent. */
    fun send(context: Context, reason: Reason) {
        if (!isEnabled()) return
        val info = AmeliaNetwork.info(context)
        val key = netKey(info)
        val now = System.currentTimeMillis()
        val entries = synchronized(lock) { shouldSend(load(), key, now, reason) } ?: return
        val iid = installId()
        val whitelist = AmeliaWhitelist.isActive()
        val answer = AmeliaNetwork.post(context, PATH) { path ->
            json(body(iid, now / 1000, info, path, entries, whitelist))
        }
        if (answer != null) synchronized(lock) { save(afterSend(load(), key, now, reason)) }
    }

    private const val WORK_PERIODIC = "amelia_probe_periodic"
    private const val KEY_REASON = "reason"

    private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<Worker>(6, TimeUnit.HOURS)
            .setConstraints(constraints())
            .setInputData(Data.Builder().putString(KEY_REASON, Reason.PERIODIC.name).build())
            .build()
        RemoteWorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private fun enqueue(context: Context, reason: Reason, delayMs: Long, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<Worker>()
            .setConstraints(constraints())
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putString(KEY_REASON, reason.name).build())
            .build()
        RemoteWorkManager.getInstance(context)
            .enqueueUniqueWork("amelia_probe_${reason.name.lowercase()}", policy, request)
    }

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val reason = inputData.getString(KEY_REASON)?.let { runCatching { Reason.valueOf(it) }.getOrNull() }
                ?: Reason.PERIODIC
            try {
                send(applicationContext, reason)
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "Amelia: probe send failed", e)
            }
            return Result.success()
        }
    }
}
