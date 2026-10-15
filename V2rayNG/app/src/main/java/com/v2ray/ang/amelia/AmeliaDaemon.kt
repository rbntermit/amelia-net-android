package com.v2ray.ang.amelia

import android.app.Service
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.Serializable

/**
 * Amelia.Net logic that lives in the VPN daemon process, next to the core:
 *
 * - **Health watchdog**: checks internet through the tunnel right after connecting and then
 *   every [CHECK_INTERVAL_MS], also when the app is closed. A location that fails twice in a
 *   row while the phone itself is online is replaced by the next one (see [AmeliaFailover]).
 * - **Session traffic**: totals and speed since the VPN was turned on, fed by the upstream
 *   speed-notification loop (the only reader of the core's reset-on-read counters).
 *
 * Both report to the app with broadcasts ([AmeliaConfig.MSG_HEALTH], [AmeliaConfig.MSG_TRAFFIC]).
 * Hooks: CoreServiceManager (start / stop / register / recheck) and NotificationManager (traffic).
 */
object AmeliaDaemon {

    /** Same names as the app's ConnPhase where they overlap. */
    enum class Phase { CHECKING, CONNECTED, SWITCHING, NO_INTERNET, ALL_FAILED }

    data class Health(val phase: String, val pingMs: Long = -1L, val guid: String? = null) : Serializable

    data class Traffic(
        val up: Long,
        val down: Long,
        val upSpeed: Long,
        val downSpeed: Long,
        /** Epoch millis when the VPN was turned on. */
        val startedAt: Long,
    ) : Serializable

    const val FIRST_CHECK_DELAY_MS = 3_000L
    const val CHECK_INTERVAL_MS = 3 * 60_000L
    const val CONFIRM_DELAY_MS = 8_000L
    const val NO_INTERNET_RETRY_MS = 30_000L

    /** Generate-204 endpoints; Google ones are routed through the proxy by our rules. */
    val PROBE_URLS = listOf(
        "https://www.gstatic.com/generate_204",
        "https://www.google.com/generate_204",
        "https://cp.cloudflare.com/generate_204",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watchJob: Job? = null

    @Volatile private var lastHealth: Health? = null
    @Volatile private var lastTraffic: Traffic? = null

    // Failover state, kept across the restart a switch causes. Also saved to MMKV: if Android
    // kills the daemon during that restart, the round must not start over and loop forever.
    private val tried = mutableSetOf<String>()
    @Volatile private var switching = false
    private const val PREF_TRIED = "amelia_failover_tried"
    private const val PREF_SWITCHING = "amelia_failover_switching"

    private fun persist() {
        MmkvManager.encodeSettings(PREF_TRIED, synchronized(tried) { tried.joinToString(",") })
        MmkvManager.encodeSettings(PREF_SWITCHING, switching)
    }

    private fun restoreIfFresh() {
        synchronized(tried) {
            if (switching || tried.isNotEmpty()) return
            switching = MmkvManager.decodeSettingsBool(PREF_SWITCHING, false)
            MmkvManager.decodeSettingsString(PREF_TRIED)?.split(',')?.filter { it.isNotBlank() }?.let { tried += it }
        }
    }

    // session traffic
    private var startedAt = 0L
    private var totalUp = 0L
    private var totalDown = 0L

    // ---- lifecycle hooks (called by CoreServiceManager) -----------------------------------

    /** Core started (not a reload). */
    fun onCoreStarted(service: Service) {
        restoreIfFresh()
        val selected = MmkvManager.getSelectServer()
        synchronized(tried) {
            if (switching) {
                switching = false // restart caused by our own switch: keep the round going
            } else {
                tried.clear() // user connected or picked a location: new round
                startedAt = System.currentTimeMillis()
                totalUp = 0L
                totalDown = 0L
            }
            selected?.let { tried += it }
        }
        persist()
        if (startedAt == 0L) startedAt = System.currentTimeMillis()
        lastTraffic = Traffic(totalUp, totalDown, 0, 0, startedAt)
        startWatch(service, FIRST_CHECK_DELAY_MS)
    }

    fun onCoreStopped() {
        watchJob?.cancel()
        watchJob = null
        if (!switching) {
            lastHealth = null
            lastTraffic = null
            startedAt = 0L
            synchronized(tried) { tried.clear() }
            persist()
        }
    }

    /** The app asked for the current state (MSG_REGISTER_CLIENT). */
    fun sendSnapshot(service: Service) {
        lastHealth?.let { MessageHelper.sendMsg2UI(service, AmeliaConfig.MSG_HEALTH, it) }
        lastTraffic?.let { MessageHelper.sendMsg2UI(service, AmeliaConfig.MSG_TRAFFIC, it) }
    }

    /** "Не работает?": check right away, trying every location again if needed. */
    fun recheck(service: Service) {
        synchronized(tried) {
            tried.clear()
            MmkvManager.getSelectServer()?.let { tried += it }
        }
        persist()
        startWatch(service, 0L)
    }

    // ---- traffic (called by NotificationManager every few seconds) ------------------------

    fun onTraffic(service: Service, up: Long, down: Long, intervalMs: Long) {
        if (startedAt == 0L) return
        totalUp += up
        totalDown += down
        val secs = (intervalMs / 1000.0).coerceAtLeast(1.0)
        val t = Traffic(totalUp, totalDown, (up / secs).toLong(), (down / secs).toLong(), startedAt)
        lastTraffic = t
        MessageHelper.sendMsg2UI(service, AmeliaConfig.MSG_TRAFFIC, t)
    }

    // ---- watchdog -------------------------------------------------------------------------

    private fun startWatch(service: Service, firstDelay: Long) {
        watchJob?.cancel()
        scope.coroutineContext.cancelChildren()
        watchJob = scope.launch {
            delay(firstDelay)
            while (isActive) {
                val next = runCheck(service)
                delay(next)
            }
        }
    }

    /** One round; returns how long to wait before the next one. */
    private suspend fun runCheck(service: Service): Long {
        if (lastHealth?.phase != Phase.CONNECTED.name) {
            report(service, Health(Phase.CHECKING.name, guid = MmkvManager.getSelectServer()))
        }
        var ms = probeTunnel()
        if (ms < 0) {
            // one slow answer is not a dead server: confirm before acting
            delay(CONFIRM_DELAY_MS)
            ms = probeTunnel()
        }
        if (ms >= 0) {
            synchronized(tried) {
                tried.clear()
                MmkvManager.getSelectServer()?.let { tried += it }
            }
            persist()
            report(service, Health(Phase.CONNECTED.name, ms, MmkvManager.getSelectServer()))
            return CHECK_INTERVAL_MS
        }

        val directOk = AmeliaFailover.directInternetOk()
        return when (AmeliaFailover.verdict(ms, directOk)) {
            AmeliaFailover.Verdict.CONNECTED -> CHECK_INTERVAL_MS
            AmeliaFailover.Verdict.NO_INTERNET -> {
                report(service, Health(Phase.NO_INTERNET.name, guid = MmkvManager.getSelectServer()))
                NO_INTERNET_RETRY_MS
            }
            AmeliaFailover.Verdict.SWITCH -> switchToNext(service)
        }
    }

    private fun switchToNext(service: Service): Long {
        val order = MmkvManager.decodeServerList(AmeliaConfig.SUBSCRIPTION_ID)
        val current = MmkvManager.getSelectServer()
        val next = synchronized(tried) { AmeliaFailover.next(order, current, tried)?.also { tried += it } }
        if (next == null) {
            report(service, Health(Phase.ALL_FAILED.name, guid = current))
            synchronized(tried) {
                tried.clear()
                current?.let { tried += it }
            }
            persist()
            return CHECK_INTERVAL_MS
        }
        LogUtil.i(AppConfig.TAG, "Amelia watchdog: location does not answer, switching")
        MmkvManager.setSelectServer(next)
        switching = true
        persist()
        report(service, Health(Phase.SWITCHING.name, guid = next))
        MessageHelper.sendMsg2Service(service, AppConfig.MSG_STATE_RESTART, "")
        return CHECK_INTERVAL_MS // the restart cancels this loop and starts a new one
    }

    /**
     * Fetches a generate-204 page through the core, trying the URLs in turn.
     * While the core is reloading (network change) it waits instead of failing.
     * @return delay in ms, or -1 when every URL failed
     */
    private suspend fun probeTunnel(): Long {
        for (url in PROBE_URLS) {
            var waited = 0
            while (true) {
                val r = CoreServiceManager.measureDelayForAmelia(url)
                if (r != null) {
                    if (r >= 0) return r
                    break // this URL failed, try the next one
                }
                if (waited >= 15) return -1 // core not running for 15 s
                waited++
                delay(1_000)
            }
        }
        return -1
    }

    private fun report(service: Service, h: Health) {
        lastHealth = h
        MessageHelper.sendMsg2UI(service, AmeliaConfig.MSG_HEALTH, h)
    }
}
