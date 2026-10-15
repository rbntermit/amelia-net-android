package com.v2ray.ang.amelia

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.Serializable
import java.util.concurrent.TimeUnit

/**
 * Amelia.Net logic in the VPN daemon process, next to the core (1.2.0):
 *
 * - **Watch**: every [INTERVAL_SCREEN_ON_MS] ([INTERVAL_SCREEN_OFF_MS] with the screen off) a
 *   short request through the current location (generate_204 via the core's local HTTP inbound,
 *   [PROBE_TIMEOUT_MS]). Two failures in a row — or one when no data has come in at all — and
 *   the location counts as broken.
 * - **Fast switch**: no full re-check first. The next location comes from the known results
 *   (AmeliaQueue: working by ping, bad-for-this-provider later, reserve last); the core is
 *   restarted in place with it (the VPN interface stays up) and checked the same way. The full
 *   check of every location runs in parallel and refines the queue as results come.
 * - **No internet** (ya.ru around the tunnel fails too): "Нет интернета", checks back off to
 *   30 s, 1, 2, 5 min, and locations are not switched.
 * - **Network change** (from upstream's handover): hints refreshed, full check, immediate watch.
 * - **Session traffic**: fed by the upstream speed-notification loop.
 */
object AmeliaDaemon {

    /** Same names as the app's ConnPhase where they overlap. */
    enum class Phase { CHECKING, CONNECTED, SWITCHING, NO_INTERNET, ALL_FAILED }

    data class Health(
        val phase: String,
        val pingMs: Long = -1L,
        val guid: String? = null,
        /** Connected to a reserve location because no regular one works (AmeliaReserve). */
        val reserveFallback: Boolean = false,
    ) : Serializable

    data class Traffic(
        val up: Long,
        val down: Long,
        val upSpeed: Long,
        val downSpeed: Long,
        /** Epoch millis when the VPN was turned on. */
        val startedAt: Long,
    ) : Serializable

    const val PROBE_URL = "https://www.gstatic.com/generate_204"
    const val PROBE_TIMEOUT_MS = 4_000L
    const val FIRST_CHECK_DELAY_MS = 1_500L
    const val INTERVAL_SCREEN_ON_MS = 30_000L
    const val INTERVAL_SCREEN_OFF_MS = 120_000L
    /** Pause before the confirming second probe. */
    const val CONFIRM_DELAY_MS = 1_000L
    /** Uplink without any downlink for this long: no traffic at all, one failure is enough. */
    const val NO_TRAFFIC_MS = 20_000L
    val NO_INTERNET_BACKOFF_MS = listOf(30_000L, 60_000L, 120_000L, 300_000L)
    /** Nothing works although the phone is online: try again after this. */
    const val ALL_FAILED_RETRY_MS = 120_000L

    private const val SWITCH_CHANNEL = "amelia_switch"
    private const val SWITCH_NOTIFICATION_ID = 7303

    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watchJob: Job? = null
    private var checkJob: Job? = null

    @Volatile private var lastHealth: Health? = null
    @Volatile private var lastTraffic: Traffic? = null
    @Volatile private var noInternetStep = 0

    // session traffic
    private var startedAt = 0L
    private var totalUp = 0L
    private var totalDown = 0L
    @Volatile private var lastProxyDownAt = 0L
    @Volatile private var lastProxyUpAt = 0L
    @Volatile private var lastTrafficAt = 0L

    // ---- lifecycle hooks (called by CoreServiceManager) -----------------------------------

    /** Core started by a connect or a full restart (not by an in-place reload). */
    fun onCoreStarted(service: Service) {
        startedAt = System.currentTimeMillis()
        totalUp = 0L
        totalDown = 0L
        lastProxyDownAt = startedAt
        lastProxyUpAt = 0L
        noInternetStep = 0
        lastTraffic = Traffic(0, 0, 0, 0, startedAt)
        startWatch(service, FIRST_CHECK_DELAY_MS)
        bgScope.launch {
            // the native library is loaded here anyway: cache the core's own version for the app
            AmeliaVersions.xray()
            AmeliaHints.refreshIfStale(service)
        }
        if (System.currentTimeMillis() - AmeliaChecks.load().lastFullAt > AmeliaChecks.FRESH_MS) startFullCheck(service)
    }

    fun onCoreStopped() {
        watchJob?.cancel()
        watchJob = null
        checkJob?.cancel()
        checkJob = null
        lastHealth = null
        lastTraffic = null
        startedAt = 0L
    }

    /** The app asked for the current state (MSG_REGISTER_CLIENT). */
    fun sendSnapshot(service: Service) {
        lastHealth?.let { MessageHelper.sendMsg2UI(service, AmeliaConfig.MSG_HEALTH, it) }
        lastTraffic?.let { MessageHelper.sendMsg2UI(service, AmeliaConfig.MSG_TRAFFIC, it) }
    }

    /** "Проверить соединение" / "Не работает": check right away and switch if needed. */
    fun recheck(service: Service) {
        noInternetStep = 0
        startWatch(service, 0L)
    }

    /** The phone moved to another network (upstream handover reloaded the core). */
    fun onNetworkChanged(service: Service) {
        LogUtil.i(AppConfig.TAG, "Amelia watch: network changed, checking again")
        noInternetStep = 0
        bgScope.launch {
            // the hints tell this provider's AS: the network key and what is cut here
            AmeliaHints.refreshIfStale(service, force = true)
            startWatch(service, 0L)
        }
        startFullCheck(service)
    }

    // ---- traffic (called by NotificationManager every few seconds) ------------------------

    fun onTraffic(service: Service, up: Long, down: Long, intervalMs: Long, proxyUp: Long = up, proxyDown: Long = down) {
        if (startedAt == 0L) return
        val now = System.currentTimeMillis()
        lastTrafficAt = now
        if (proxyDown > 0) lastProxyDownAt = now
        if (proxyUp > 0) lastProxyUpAt = now
        totalUp += up
        totalDown += down
        val secs = (intervalMs / 1000.0).coerceAtLeast(1.0)
        val t = Traffic(totalUp, totalDown, (up / secs).toLong(), (down / secs).toLong(), startedAt)
        lastTraffic = t
        MessageHelper.sendMsg2UI(service, AmeliaConfig.MSG_TRAFFIC, t)
    }

    /** Requests go out through the tunnel but nothing has come back for a while. */
    internal fun noTraffic(now: Long, lastUp: Long, lastDown: Long, lastSample: Long): Boolean =
        now - lastSample < 10_000L && lastUp > lastDown && now - lastDown > NO_TRAFFIC_MS

    // ---- watch ----------------------------------------------------------------------------

    private fun startWatch(service: Service, firstDelay: Long) {
        watchJob?.cancel()
        watchJob = watchScope.launch {
            delay(firstDelay)
            while (isActive) {
                val next = runCatching { tick(service) }.getOrElse {
                    LogUtil.w(AppConfig.TAG, "Amelia watch: check failed", it)
                    INTERVAL_SCREEN_ON_MS
                }
                delay(next)
            }
        }
    }

    private fun interval(context: Context): Long =
        if (context.getSystemService(PowerManager::class.java)?.isInteractive != false) INTERVAL_SCREEN_ON_MS
        else INTERVAL_SCREEN_OFF_MS

    /** One round; returns how long to wait before the next one. */
    private suspend fun tick(service: Service): Long {
        if (!waitCoreReady()) return 2_000L
        val selected = MmkvManager.getSelectServer()
        val reserve = AmeliaReserveState.isReserveGuid(selected)
        if (lastHealth?.phase != Phase.CONNECTED.name) report(service, Health(Phase.CHECKING.name, guid = selected))

        var ms = probe(reserve)
        val silent = noTraffic(System.currentTimeMillis(), lastProxyUpAt, lastProxyDownAt, lastTrafficAt)
        if (ms < 0 && !silent) {
            // one slow answer is not a dead server: confirm right away
            delay(CONFIRM_DELAY_MS)
            ms = probe(reserve)
        }
        if (ms >= 0) {
            onWorking(service, selected, ms)
            return interval(service)
        }

        // The location does not answer. Is it the location or the phone's internet?
        if (!AmeliaFailover.directInternetOk(service)) {
            report(service, Health(Phase.NO_INTERNET.name, guid = selected))
            val wait = NO_INTERNET_BACKOFF_MS[noInternetStep.coerceAtMost(NO_INTERNET_BACKOFF_MS.lastIndex)]
            noInternetStep++
            return wait
        }
        noInternetStep = 0
        selected?.let { putFailure(service, it) }
        return switchFast(service, selected, if (silent) "трафика нет" else "нет ответа 2 раза")
    }

    private fun onWorking(service: Service, guid: String?, ms: Long) {
        noInternetStep = 0
        val fallback = AmeliaReserveState.isAuto() && AmeliaReserveState.isReserveGuid(guid)
        report(service, Health(Phase.CONNECTED.name, ms, guid, reserveFallback = fallback))
        val config = guid?.let { MmkvManager.decodeServerConfig(it) } ?: return
        val net = AmeliaHints.netKey(service)
        AmeliaChecks.put(
            service,
            AmeliaCheck.Result(guid, config.remarks, config.server.orEmpty(), config.serverPort?.toIntOrNull(), ms, AmeliaPingStage.OK, null),
            net,
        )
        if (!AmeliaReserve.isReserve(config.remarks)) AmeliaBest.remember(net, config.remarks)
        bgScope.launch { AmeliaHints.refreshIfStale(service) }
    }

    private fun putFailure(service: Service, guid: String) {
        val config = MmkvManager.decodeServerConfig(guid) ?: return
        AmeliaChecks.put(
            service,
            AmeliaCheck.Result(
                guid, config.remarks, config.server.orEmpty(), config.serverPort?.toIntOrNull(), -1,
                AmeliaFailover.failureStage(guid), "no answer through the tunnel",
            ),
        )
    }

    /**
     * Moves to the first location of the queue that answers, without a full re-check first.
     * The full check runs at the same time and its results reorder what is left.
     */
    private suspend fun switchFast(service: Service, failed: String?, why: String): Long {
        val t0 = System.currentTimeMillis()
        val fromName = failed?.let { MmkvManager.decodeServerConfig(it)?.remarks }.orEmpty()
        startFullCheck(service)
        val tried = mutableSetOf<String>().apply { failed?.let { add(it) } }
        while (true) {
            // locations the parallel check has just seen failing are skipped
            val failedNow = AmeliaChecks.load().entries.values.filter { !it.ok && it.ts >= t0 }.map { it.name }.toSet()
            val skip = tried + AmeliaQueue.locations().filter { it.name in failedNow }.map { it.guid }
            val next = AmeliaQueue.current(service, exclude = skip).firstOrNull() ?: break
            tried += next
            val reserve = AmeliaReserveState.isReserveGuid(next)
            MmkvManager.setSelectServer(next)
            AmeliaReserveState.setAuto(reserve)
            report(service, Health(Phase.SWITCHING.name, guid = next))
            if (!CoreServiceManager.reloadForAmelia()) {
                delay(500)
                if (!CoreServiceManager.reloadForAmelia()) continue
            }
            val ms = if (waitCoreReady()) probe(reserve) else -1L
            if (ms >= 0) {
                val toName = MmkvManager.decodeServerConfig(next)?.remarks.orEmpty()
                val secs = (System.currentTimeMillis() - t0 + 999) / 1000
                LogUtil.i(AppConfig.TAG, "Amelia watch: switched in $secs s")
                AmeliaEvents.add(AmeliaEvents.Event(System.currentTimeMillis() / 1000, fromName, toName, "$why · $secs с"))
                notifySwitch(service, fromName, toName)
                onWorking(service, next, ms)
                return interval(service)
            }
            putFailure(service, next)
        }
        report(service, Health(Phase.ALL_FAILED.name, guid = MmkvManager.getSelectServer()))
        return ALL_FAILED_RETRY_MS
    }

    /** Waits up to 10 s while the core reloads (network change, our own switch). */
    private suspend fun waitCoreReady(): Boolean {
        repeat(20) {
            if (CoreServiceManager.isReadyForAmelia()) return true
            if (!CoreServiceManager.isRunning() && it > 4) return false
            delay(500)
        }
        return CoreServiceManager.isReadyForAmelia()
    }

    private val probeClient: OkHttpClient by lazy {
        AmeliaHttp.clientFor(AmeliaHttp.Route.TUNNEL).newBuilder()
            .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .build()
    }

    /**
     * One request through the current location (the core's local HTTP inbound), at most
     * [PROBE_TIMEOUT_MS]. Reserve locations: Telegram, any HTTP answer counts.
     * @return delay in ms, -1 when it did not answer
     */
    private fun probe(reserve: Boolean): Long {
        val url = if (reserve) AmeliaReserve.TEST_URL else PROBE_URL
        val start = System.nanoTime()
        return try {
            probeClient.newCall(AmeliaHttp.get(url)).execute().use { resp ->
                val ms = ((System.nanoTime() - start) / 1_000_000).coerceAtLeast(1L)
                if (reserve || resp.code in 200..399) ms else -1L
            }
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "Amelia watch: probe through the location failed: ${e.message}")
            -1L
        }
    }

    // ---- full check in the background -----------------------------------------------------

    /** Every location in parallel (AmeliaCheck); results reach the app as they come. */
    private fun startFullCheck(service: Service) {
        if (checkJob?.isActive == true) return
        checkJob = bgScope.launch {
            val guids = MmkvManager.decodeServerList(AmeliaConfig.SUBSCRIPTION_ID)
            if (guids.isEmpty()) return@launch
            val net = AmeliaHints.netKey(service)
            val ms = AmeliaCheck.checkAll(service, guids) { r ->
                AmeliaChecks.put(service, r, net)
                MessageHelper.sendMsg2UI(service, AmeliaConfig.MSG_CHECKS, "")
            }
            AmeliaChecks.putFull(ms)
            MessageHelper.sendMsg2UI(service, AmeliaConfig.MSG_CHECKS, "")
            LogUtil.i(AppConfig.TAG, "Amelia: full check of ${guids.size} locations took $ms ms")
        }
    }

    // ---- notifications --------------------------------------------------------------------

    /** Quiet notice on its own channel (people can turn it off in the system settings). */
    private fun notifySwitch(context: Context, from: String, to: String) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    SWITCH_CHANNEL, context.getString(R.string.amelia_switch_channel), NotificationManager.IMPORTANCE_LOW,
                ).apply { setSound(null, null) },
            )
        }
        val open = PendingIntent.getActivity(
            context, 3,
            Intent(context, AmeliaActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = if (from.isNotEmpty()) {
            context.getString(R.string.amelia_switch_text, from, to)
        } else {
            context.getString(R.string.amelia_switch_text_short, to)
        }
        val n = NotificationCompat.Builder(context, SWITCH_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(SWITCH_NOTIFICATION_ID, n)
        } catch (e: SecurityException) {
            LogUtil.w(AppConfig.TAG, "Amelia: no permission for the switch notification", e)
        }
    }

    private fun report(service: Service, h: Health) {
        lastHealth = h
        MessageHelper.sendMsg2UI(service, AmeliaConfig.MSG_HEALTH, h)
    }
}
