package com.v2ray.ang.amelia

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.service.quicksettings.TileService
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.service.QSTileService
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.io.Serializable
import java.util.concurrent.TimeUnit

/**
 * Amelia.Net logic in the VPN daemon process, next to the core (1.4.0, see docs/LOGIC.md):
 *
 * - **Watch**: every [INTERVAL_SCREEN_ON_MS] ([INTERVAL_SCREEN_OFF_MS] with the screen off) a
 *   request through the current location to two generate_204 addresses ([PROBE_URLS], any one
 *   is enough, [PROBE_TIMEOUT_MS]). Broken: two failures in a row for an automatic choice (1.5.9:
 *   also when no data came back at all); for a location chosen by hand three failures over at
 *   least 30 s and a last 10 s attempt (AmeliaPolicy).
 * - **1.5.9**: the hints' "good" list goes first in the queue; a location left after a failure is
 *   in quarantine for 30 min (AmeliaQuarantine); at most 4 switches in 30 min
 *   (AmeliaSwitchLimit); "белые списки" stop trying locations (AmeliaWhitelist).
 * - **1.6.0**: over the switch limit a dead location (tunnel check failed twice in a row) is still
 *   left for one that passed a check in the last 2 minutes ("сверх лимита: текущая не работает").
 * - **No internet**: without a validated network, or when ya.ru / google do not answer around
 *   the tunnel, nothing is judged or switched; checks back off (30 s … 5 min), and after the
 *   network is back the daemon waits [SETTLE_MS] before the next check.
 * - **Switch** (AmeliaPolicy.Mode): from the known results (AmeliaQueue); reserve and Moscow only
 *   when the subscription ended. The core restarts in place; the new location is judged only
 *   after the tunnel is up, [SETTLE_MS] and one warm-up request. Nothing works: back to where
 *   it was. One switch at a time ([switching]); nothing interrupts it.
 * - **Leaving reserve/Moscow**: on such a location with a valid subscription and not chosen by
 *   hand, foreign locations are checked every minute and the first working one is taken.
 * - **Network change** (upstream handover): hints, full check, watch after [SETTLE_MS].
 * - **Session traffic**: fed by the upstream speed-notification loop.
 */
object AmeliaDaemon {

    /** Same names as the app's ConnPhase where they overlap. WHITELIST: 1.5.9, see AmeliaWhitelist. */
    enum class Phase { CHECKING, CONNECTED, SWITCHING, NO_INTERNET, ALL_FAILED, MANUAL_FAILED, WHITELIST }

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

    /** Any one answering is enough (1.4.0). */
    val PROBE_URLS = listOf("https://www.gstatic.com/generate_204", "https://cp.cloudflare.com/generate_204")
    const val PROBE_TIMEOUT_MS = 6_000L
    /** Last attempt before leaving a location chosen by hand. */
    const val FINAL_PROBE_TIMEOUT_MS = 10_000L
    /** After the tunnel is up (connect, switch, network back) before the first real check. */
    const val SETTLE_MS = 3_000L
    /** After the network comes back, before anything is judged. */
    const val NETWORK_SETTLE_MS = 5_000L
    const val INTERVAL_SCREEN_ON_MS = 30_000L
    const val INTERVAL_SCREEN_OFF_MS = 120_000L
    /** While failures are being counted. */
    const val FAILING_RETRY_MS = 10_000L
    /** Pause before the confirming second probe of an automatic choice. */
    const val CONFIRM_DELAY_MS = 1_000L
    /** Uplink without any downlink for this long: no traffic at all, one failure is enough. */
    const val NO_TRAFFIC_MS = 20_000L
    /** Nothing works although the phone is online: try again after this. */
    const val ALL_FAILED_RETRY_MS = 120_000L
    /** On reserve/Moscow with a valid subscription: how often foreign locations are tried. */
    const val LEAVE_LAST_RESORT_MS = 60_000L

    private const val SWITCH_CHANNEL = "amelia_switch"
    private const val SWITCH_NOTIFICATION_ID = 7303
    /** 1.5.9: "Оператор режет часть серверов" (switch limit) and "белые списки". */
    private const val LIMIT_NOTIFICATION_ID = 7304
    private const val WHITELIST_NOTIFICATION_ID = 7305

    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watchJob: Job? = null
    private var checkJob: Job? = null

    /** Held for a whole check or switch: a second one waits instead of interrupting it. */
    private val switching = Mutex()

    @Volatile private var lastHealth: Health? = null
    @Volatile private var lastTraffic: Traffic? = null
    @Volatile private var noInternetStep = 0
    /** Offline seen: wait [NETWORK_SETTLE_MS] before judging again. */
    @Volatile private var settleNetwork = false
    /** The core was (re)started: the next check waits [SETTLE_MS] and warms the connection up. */
    @Volatile private var freshTunnel = true
    @Volatile private var failures = 0
    @Volatile private var firstFailAt = 0L
    @Volatile private var lastLeaveTry = 0L
    /** 1.5.9: switch rounds in a row that found nothing working (AmeliaWhitelist.EMPTY_ROUNDS). */
    @Volatile private var emptyRounds = 0
    /** 1.5.9: the next round checks for white lists first. */
    @Volatile private var whitelistCheckDue = false
    /** 1.5.9: last check of group B while in white-list mode (0: check at once). */
    @Volatile private var lastWhitelistCheck = 0L
    /** 1.5.9: "Оператор режет часть серверов" already said for this run of the switch limit. */
    @Volatile private var limitNotified = false

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
        resetFailures()
        freshTunnel = true
        lastLeaveTry = 0L
        emptyRounds = 0
        whitelistCheckDue = false
        lastWhitelistCheck = 0L
        lastTraffic = Traffic(0, 0, 0, 0, startedAt)
        startWatch(service, 0L)
        bgScope.launch {
            // 1.5.9: a white-list mode from an earlier run is found again by the checks if it still holds
            AmeliaWhitelist.exit()
            // the native library is loaded here anyway: cache the core's own version for the app
            AmeliaVersions.xray()
            AmeliaHints.refreshIfStale(service)
        }
        if (System.currentTimeMillis() - AmeliaChecks.load().lastFullAt > AmeliaChecks.FRESH_MS) startFullCheck(service)
        refreshTile(service)
    }

    fun onCoreStopped() {
        watchJob?.cancel()
        watchJob = null
        checkJob?.cancel()
        checkJob = null
        lastHealth = null
        lastTraffic = null
        startedAt = 0L
        // 1.5.9: probe and report must not say "white lists" while the VPN is off
        bgScope.launch { AmeliaWhitelist.exit() }
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
        AmeliaJournal.add("Сеть сменилась — проверяю заново")
        noInternetStep = 0
        resetFailures()
        settleNetwork = true
        freshTunnel = true
        // 1.5.9: in white-list mode group B is checked again right away (Wi-Fi ends the mode)
        lastWhitelistCheck = 0L
        bgScope.launch {
            // the hints tell this provider's AS: the network key and what is cut here
            AmeliaHints.refreshIfStale(service, force = true)
        }
        startWatch(service, 0L)
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

    /**
     * (Re)starts the watch loop. A check or switch in progress is not interrupted: the new loop
     * waits for it ([switching]) and checks right after.
     */
    private fun startWatch(service: Service, firstDelay: Long) {
        if (switching.isLocked && watchJob?.isActive == true) {
            // a check or switch is running: let it finish, then check again right away
            nextSoon = true
            return
        }
        watchJob?.cancel()
        watchJob = watchScope.launch {
            delay(firstDelay)
            while (isActive) {
                val next = runCatching { switching.withLock { tick(service) } }.getOrElse {
                    LogUtil.w(AppConfig.TAG, "Amelia watch: check failed", it)
                    INTERVAL_SCREEN_ON_MS
                }
                delay(if (nextSoon) 0L.also { nextSoon = false } else next)
            }
        }
    }

    @Volatile private var nextSoon = false

    private fun interval(context: Context): Long =
        if (context.getSystemService(PowerManager::class.java)?.isInteractive != false) INTERVAL_SCREEN_ON_MS
        else INTERVAL_SCREEN_OFF_MS

    private fun resetFailures() {
        failures = 0
        firstFailAt = 0L
    }

    /** One round; returns how long to wait before the next one. Runs under [switching]. */
    private suspend fun tick(service: Service): Long {
        if (!waitCoreReady()) return 2_000L
        // No internet on the phone itself: nothing is judged or switched. Android's own check
        // (VALIDATED) can fail on some vendor builds, so ya.ru / google around the tunnel decide too.
        if (AmeliaPolicy.isOffline(AmeliaNetwork.validated(service), AmeliaFailover.directInternetOk(service))) {
            return offline(service, "сеть без интернета")
        }
        if (settleNetwork) {
            settleNetwork = false
            delay(NETWORK_SETTLE_MS)
        }
        // 1.5.9: white lists: locations are not tried, only group B is checked again
        if (AmeliaWhitelist.isActive()) {
            val wait = whitelistTick(service)
            if (wait > 0) return wait
        } else if (whitelistCheckDue) {
            whitelistCheckDue = false
            checkWhitelist(service)?.let { return it }
        }
        if (freshTunnel) warmUp()

        val selected = MmkvManager.getSelectServer()
        val name = selected?.let { MmkvManager.decodeServerConfig(it)?.remarks }
        val reserve = AmeliaReserve.isReserve(name)
        val manual = AmeliaManual.isManual(selected)
        if (lastHealth?.phase != Phase.CONNECTED.name) report(service, Health(Phase.CHECKING.name, guid = selected))

        var ms = probe(reserve)
        val now0 = System.currentTimeMillis()
        val silent = noTraffic(now0, lastProxyUpAt, lastProxyDownAt, lastTrafficAt)
        if (ms < 0 && !manual && failures == 0) {
            // one slow answer is not a dead server: confirm right away. 1.5.9: also when no
            // traffic came back ("трафика нет" alone never switches)
            delay(CONFIRM_DELAY_MS)
            ms = probe(reserve)
        }
        if (ms >= 0) {
            resetFailures()
            onWorking(service, selected, ms)
            val subActive = AmeliaPolicy.subscriptionActive(AmeliaManager.getSubInfo(), System.currentTimeMillis())
            if (AmeliaPolicy.shouldLeaveLastResort(name, manual, subActive)) return leaveLastResort(service, selected)
            return interval(service)
        }

        val now = System.currentTimeMillis()
        if (failures == 0) firstFailAt = now
        failures += if (!manual && failures == 0) 2 else 1 // the confirming probe above counted too
        if (!AmeliaPolicy.broken(manual, failures, firstFailAt, now)) return FAILING_RETRY_MS
        if (manual) {
            // last chance with a longer wait before leaving what the person chose
            val last = probe(reserve, FINAL_PROBE_TIMEOUT_MS)
            if (last >= 0) {
                resetFailures()
                onWorking(service, selected, last)
                return interval(service)
            }
        }

        // The location does not answer. Is it the location or the phone's internet?
        if (!AmeliaFailover.directInternetOk(service)) return offline(service, "нет интернета")
        noInternetStep = 0
        selected?.let { putFailure(service, it) }
        val why = AmeliaPolicy.switchReason(manual, silent)
        val respect = AmeliaManual.load().respectUntil
        if (!AmeliaPolicy.maySwitch(AmeliaPolicy.mode(), manual, respect, now)) {
            AmeliaJournal.add("${name.orEmpty()} $why — автопереключение не трогает этот выбор")
            report(service, Health(if (manual) Phase.MANUAL_FAILED.name else Phase.ALL_FAILED.name, guid = selected))
            return INTERVAL_SCREEN_ON_MS
        }
        // 1.5.9: at most 4 automatic switches in 30 minutes, then stay and check every 5 minutes.
        // 1.6.0: but a dead location is left for one that surely works, over the limit.
        if (!AmeliaSwitchLimit.allowed(AmeliaSwitchLimit.times(AmeliaEvents.list()), now)) {
            val failedInRow = failures
            resetFailures()
            return switchOverLimit(service, selected, name.orEmpty(), why, failedInRow)
        }
        limitNotified = false
        val result = switchFast(service, selected, why)
        resetFailures()
        return result
    }

    /** 1.5.9: the switch limit is reached: stay on the current location, say so once. */
    private fun switchLimited(service: Service, name: String, why: String): Long {
        AmeliaJournal.add("$name $why — уже ${AmeliaSwitchLimit.MAX} переключения за 30 мин, остаюсь и проверяю раз в 5 мин")
        if (!limitNotified) {
            limitNotified = true
            notifyQuiet(service, LIMIT_NOTIFICATION_ID, service.getString(R.string.amelia_limit_notice, name))
        }
        return AmeliaSwitchLimit.RECHECK_MS
    }

    /**
     * 1.6.0: the switch limit is reached. A location whose tunnel check failed twice in a row is
     * left anyway for one that passed a check in the last 2 minutes (a "good" one from the hints is
     * checked right now first), over the limit. Nothing like that: stay as in 1.5.9.
     */
    private suspend fun switchOverLimit(service: Service, current: String?, name: String, why: String, failedInRow: Int): Long {
        var targets = emptyList<String>()
        if (AmeliaSwitchLimit.currentDead(failedInRow)) {
            AmeliaHints.refreshIfOlder(service, AmeliaHints.BEFORE_SWITCH_MS)
            targets = verifiedTargets(service, current)
            if (targets.isEmpty()) {
                checkCandidatesNow(service, current)
                targets = verifiedTargets(service, current)
            }
        }
        return when (AmeliaSwitchLimit.decide(limitAllowed = false, failures = failedInRow, haveVerified = targets.isNotEmpty())) {
            AmeliaSwitchLimit.Decision.OVER_LIMIT -> switchFast(service, current, why, overLimit = true)
            else -> stayLimited(service, name, why)
        }
    }

    /** 1.6.0: stay under the limit; with nothing working at all it may be white lists (as after an empty round). */
    private fun stayLimited(service: Service, name: String, why: String): Long {
        val wait = switchLimited(service, name, why)
        emptyRounds++
        if (!AmeliaWhitelist.isActive() && AmeliaWhitelist.shouldCheck(onMobile(service), tooManyFailed = false, emptyRounds = emptyRounds)) {
            whitelistCheckDue = true
            return FAILING_RETRY_MS
        }
        return wait
    }

    /** 1.6.0: queue (without [current]) of locations that passed a check in the last 2 minutes. */
    private fun verifiedTargets(service: Service, current: String?): List<String> {
        val names = AmeliaQueue.locations().associate { it.guid to it.name }
        return AmeliaSwitchLimit.verifiedTargets(
            AmeliaQueue.current(service, exclude = setOfNotNull(current)),
            { names[it] },
            AmeliaChecks.load().entries,
            System.currentTimeMillis(),
        )
    }

    /**
     * 1.6.0: nothing passed a check recently. The hints' "good" ones are checked now (3 s, in
     * parallel); none answered — the best few by past ping, as the quick pick before a switch.
     */
    private suspend fun checkCandidatesNow(service: Service, current: String?) {
        val locations = AmeliaQueue.locations()
        val good = AmeliaHints.goodHostPorts(AmeliaHints.current(AmeliaProbe.netKey(AmeliaNetwork.info(service))))
        val exclude = setOfNotNull(current)
        val net = AmeliaHints.netKey(service)
        suspend fun checkNow(guids: List<String>) {
            if (guids.isEmpty()) return
            val answered = AmeliaQuickPick.quickest(guids, check = { AmeliaCheck.checkOne(service, it) }) { r ->
                AmeliaChecks.put(service, r, net)
                MessageHelper.sendMsg2UI(service, AmeliaConfig.MSG_CHECKS, "")
            }
            LogUtil.i(AppConfig.TAG, "Amelia watch: over the limit, ${answered.size} of ${guids.size} answered in 3 s")
        }
        val first = AmeliaSwitchLimit.goodToCheck(locations, good, AmeliaChecks.load().entries, exclude, System.currentTimeMillis())
        checkNow(first)
        if (first.isNotEmpty() && verifiedTargets(service, current).isNotEmpty()) return
        checkNow(AmeliaQuickPick.candidates(locations, AmeliaChecks.load().entries, exclude + first, good))
    }

    /**
     * 1.5.9: most locations fail. Does the phone reach only allowed Russian sites (white lists)?
     * Returns the wait before the next round when the round ends here, null to go on as usual.
     */
    private suspend fun checkWhitelist(service: Service): Long? {
        val info = AmeliaNetwork.info(service)
        if (info.net != AmeliaNetwork.NET_MOBILE) return null
        val (a, b) = coroutineScope {
            val a = async { AmeliaWhitelist.anyOpens(service, AmeliaWhitelist.GROUP_A) }
            val b = async { AmeliaWhitelist.anyOpens(service, AmeliaWhitelist.groupB(AmeliaWhitelist.subscriptionBase())) }
            a.await() to b.await()
        }
        val verdict = AmeliaWhitelist.verdict(a, b)
        LogUtil.i(AppConfig.TAG, "Amelia white lists: A=$a B=$b → $verdict")
        return when (verdict) {
            AmeliaWhitelist.Verdict.WHITELIST -> enterWhitelist(service)
            AmeliaWhitelist.Verdict.OFFLINE -> offline(service, "не открываются даже российские сайты")
            AmeliaWhitelist.Verdict.NORMAL -> {
                AmeliaJournal.add("Обычные сайты открываются — оператор режет отдельные серверы")
                null
            }
        }
    }

    private fun enterWhitelist(service: Service): Long {
        val wasActive = AmeliaWhitelist.isActive()
        AmeliaWhitelist.enter(AmeliaHints.netKey(service))
        lastWhitelistCheck = System.currentTimeMillis()
        resetFailures()
        report(service, Health(Phase.WHITELIST.name, guid = MmkvManager.getSelectServer()))
        if (!wasActive) {
            AmeliaJournal.add("Оператор включил «белые списки» — локации не перебираю, проверяю раз в 5 мин")
            notifyQuiet(service, WHITELIST_NOTIFICATION_ID, service.getString(R.string.amelia_whitelist_notice))
        }
        return AmeliaWhitelist.RECHECK_MS
    }

    /**
     * 1.5.9: one round in white-list mode. Group B every 5 minutes and right after a network
     * change; B opens or the phone is on Wi-Fi → the mode ends and the round goes on as usual (0).
     */
    private suspend fun whitelistTick(service: Service): Long {
        val now = System.currentTimeMillis()
        val onWifi = AmeliaNetwork.info(service).net == AmeliaNetwork.NET_WIFI
        val due = now - lastWhitelistCheck !in 0 until AmeliaWhitelist.RECHECK_MS
        if (!onWifi && !due) {
            report(service, Health(Phase.WHITELIST.name, guid = MmkvManager.getSelectServer()))
            return AmeliaWhitelist.RECHECK_MS - (now - lastWhitelistCheck)
        }
        val bOk = !onWifi && AmeliaWhitelist.anyOpens(service, AmeliaWhitelist.groupB(AmeliaWhitelist.subscriptionBase()))
        lastWhitelistCheck = now
        if (!AmeliaWhitelist.shouldExit(bOk, onWifi)) {
            report(service, Health(Phase.WHITELIST.name, guid = MmkvManager.getSelectServer()))
            return AmeliaWhitelist.RECHECK_MS
        }
        AmeliaWhitelist.exit()
        AmeliaJournal.add(if (onWifi) "Подключились к Wi‑Fi — «белые списки» позади, подключаюсь как обычно" else "«Белые списки» сняли — подключаюсь как обычно")
        NotificationManagerCompat.from(service).cancel(WHITELIST_NOTIFICATION_ID)
        emptyRounds = 0
        resetFailures()
        freshTunnel = true
        return 0L
    }

    private fun offline(service: Service, why: String): Long {
        val selected = MmkvManager.getSelectServer()
        if (lastHealth?.phase != Phase.NO_INTERNET.name) AmeliaJournal.add("Нет интернета ($why) — локации не меняю")
        report(service, Health(Phase.NO_INTERNET.name, guid = selected))
        settleNetwork = true
        resetFailures()
        return AmeliaPolicy.offlineWait(noInternetStep++)
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
        // only foreign locations are worth starting with on this network
        if (!AmeliaPolicy.isLastResort(config.remarks)) AmeliaBest.remember(net, config.remarks)
        emptyRounds = 0
        // 1.5.9: what works for this provider, asked again every 15 minutes while the VPN is on
        bgScope.launch { AmeliaHints.refreshIfOlder(service, AmeliaHints.WHILE_ON_MS) }
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
     * On reserve / Moscow with a valid subscription: once a minute check the foreign locations
     * (outside the tunnel, in parallel) and move to the first one that works through the tunnel.
     */
    private suspend fun leaveLastResort(service: Service, current: String?): Long {
        val now = System.currentTimeMillis()
        if (now - lastLeaveTry < LEAVE_LAST_RESORT_MS) return LEAVE_LAST_RESORT_MS
        lastLeaveTry = now
        val locations = AmeliaQueue.locations()
        val guids = AmeliaQuickPick.candidates(locations, AmeliaChecks.load().entries, setOfNotNull(current))
        if (guids.isEmpty()) return LEAVE_LAST_RESORT_MS
        val net = AmeliaHints.netKey(service)
        AmeliaQuickPick.quickest(guids, deadlineMs = PROBE_TIMEOUT_MS, check = { AmeliaCheck.checkOne(service, it) }) { r ->
            AmeliaChecks.put(service, r, net)
        }
        val fromName = current?.let { MmkvManager.decodeServerConfig(it)?.remarks }.orEmpty()
        val queue = AmeliaQueue.current(service, exclude = setOfNotNull(current))
            .filter { g -> !AmeliaPolicy.isLastResort(MmkvManager.decodeServerConfig(g)?.remarks) }
        val known = AmeliaChecks.fresh(AmeliaChecks.load(), System.currentTimeMillis())
        val target = queue.firstOrNull { g -> known[MmkvManager.decodeServerConfig(g)?.remarks]?.ok == true }
            ?: return LEAVE_LAST_RESORT_MS
        val ms = switchTo(service, target)
        if (ms >= 0) {
            done(service, current, target, fromName, "подписка действует — ушли с «$fromName»", now, ms)
            return interval(service)
        }
        restore(service, current)
        return LEAVE_LAST_RESORT_MS
    }

    /**
     * Moves to the first location of the queue that answers, without a full re-check first.
     * The full check runs at the same time and its results reorder what is left. Nothing
     * works: back to the location it started from.
     *
     * 1.6.0 [overLimit]: the switch limit is reached and [failed] is dead; only locations that
     * passed a check in the last 2 minutes are tried (AmeliaSwitchLimit.verifiedTargets), the
     * reason gets "сверх лимита: текущая не работает". None answers: stay, as under the limit.
     */
    private suspend fun switchFast(service: Service, failed: String?, why: String, overLimit: Boolean = false): Long {
        val t0 = System.currentTimeMillis()
        val fromName = failed?.let { MmkvManager.decodeServerConfig(it)?.remarks }.orEmpty()
        // 1.5.9: what works for this provider right now, if the hints are older than 2 minutes (≤ 3 s)
        AmeliaHints.refreshIfOlder(service, AmeliaHints.BEFORE_SWITCH_MS)
        // over the limit the candidates were checked just now (switchOverLimit)
        if (!overLimit) quickPickIfStale(service, failed)
        startFullCheck(service)
        val reason = if (overLimit) AmeliaSwitchLimit.overLimitReason(why) else why
        val tried = mutableSetOf<String>().apply { failed?.let { add(it) } }
        while (true) {
            // the person picked a location meanwhile: their choice wins, nothing is restored
            if (AmeliaManual.load().at > t0) return interval(service)
            // locations the parallel check has just seen failing are skipped
            val failedNow = AmeliaChecks.load().entries.values.filter { !it.ok && it.ts >= t0 }.map { it.name }.toSet()
            val locations = AmeliaQueue.locations()
            val skip = tried + locations.filter { it.name in failedNow }.map { it.guid }
            val queue = AmeliaQueue.current(service, exclude = skip)
            val candidates = if (!overLimit) {
                queue
            } else {
                val names = locations.associate { it.guid to it.name }
                AmeliaSwitchLimit.verifiedTargets(queue, { names[it] }, AmeliaChecks.load().entries, System.currentTimeMillis())
            }
            val next = candidates.firstOrNull() ?: break
            tried += next
            val ms = switchTo(service, next)
            if (ms >= 0) {
                // 1.5.9: the one we left is not chosen automatically for 30 min on this network
                if (fromName.isNotEmpty()) AmeliaQuarantine.put(fromName, AmeliaHints.netKey(service))
                done(service, failed, next, fromName, reason, t0, ms)
                return interval(service)
            }
            putFailure(service, next)
        }
        restore(service, failed)
        if (overLimit) {
            AmeliaJournal.add("$fromName $why — проверенные локации не ответили через туннель")
            return stayLimited(service, fromName, why)
        }
        AmeliaJournal.add("$fromName $why — ни одна локация не ответила")
        report(service, Health(Phase.ALL_FAILED.name, guid = MmkvManager.getSelectServer()))
        // 1.5.9: two rounds in a row with nothing working on mobile: check for white lists next
        emptyRounds++
        if (AmeliaWhitelist.shouldCheck(onMobile(service), tooManyFailed = false, emptyRounds = emptyRounds)) {
            whitelistCheckDue = true
            return FAILING_RETRY_MS
        }
        return ALL_FAILED_RETRY_MS
    }

    private fun onMobile(context: Context) = AmeliaNetwork.info(context).net == AmeliaNetwork.NET_MOBILE

    /** Selects [guid], restarts the core in place and judges it once it has settled. */
    private suspend fun switchTo(service: Service, guid: String): Long {
        val reserve = AmeliaReserveState.isReserveGuid(guid)
        MmkvManager.setSelectServer(guid)
        AmeliaReserveState.setAuto(reserve)
        report(service, Health(Phase.SWITCHING.name, guid = guid))
        if (!CoreServiceManager.reloadForAmelia()) {
            delay(500)
            if (!CoreServiceManager.reloadForAmelia()) return -1L
        }
        if (!waitCoreReady()) return -1L
        warmUp()
        return probe(reserve)
    }

    /** Nothing worked: the core goes back to [guid] (not left on the last one tried). */
    private suspend fun restore(service: Service, guid: String?) {
        if (guid == null || MmkvManager.getSelectServer() == guid) return
        MmkvManager.setSelectServer(guid)
        AmeliaReserveState.setAuto(AmeliaReserveState.isReserveGuid(guid) && AmeliaReserveState.isAuto())
        CoreServiceManager.reloadForAmelia()
        waitCoreReady()
        freshTunnel = true
        refreshTile(service)
    }

    private fun done(service: Service, from: String?, to: String, fromName: String, why: String, t0: Long, ms: Long) {
        val toName = MmkvManager.decodeServerConfig(to)?.remarks.orEmpty()
        val secs = (System.currentTimeMillis() - t0 + 999) / 1000
        val wasManual = AmeliaManual.isManual(from)
        AmeliaManual.markLeft(from)
        LogUtil.i(AppConfig.TAG, "Amelia watch: switched in $secs s")
        AmeliaEvents.add(AmeliaEvents.Event(System.currentTimeMillis() / 1000, fromName, toName, "$why · $secs с"))
        AmeliaJournal.add("$fromName $why → $toName")
        notifySwitch(service, fromName, toName, if (wasManual) from else null)
        onWorking(service, to, ms)
        refreshTile(service)
    }

    /**
     * No fresh working foreign location known: check the best few by past ping for 3 s in
     * parallel; their results go to the store, so the queue then starts with the fastest.
     */
    private suspend fun quickPickIfStale(service: Service, failed: String?) {
        val now = System.currentTimeMillis()
        val store = AmeliaChecks.load()
        val fresh = AmeliaChecks.fresh(store, now)
        val locations = AmeliaQueue.locations()
        val haveFresh = locations.any {
            it.guid != failed && fresh[it.name]?.ok == true && !AmeliaPolicy.isLastResort(it.name)
        }
        if (haveFresh) return
        val good = AmeliaHints.goodHostPorts(AmeliaHints.current(AmeliaProbe.netKey(AmeliaNetwork.info(service))))
        val guids = AmeliaQuickPick.candidates(locations, store.entries, setOfNotNull(failed), good)
        if (guids.isEmpty()) return
        val net = AmeliaHints.netKey(service)
        val answered = AmeliaQuickPick.quickest(guids, check = { AmeliaCheck.checkOne(service, it) }) { r ->
            AmeliaChecks.put(service, r, net)
            MessageHelper.sendMsg2UI(service, AmeliaConfig.MSG_CHECKS, "")
        }
        LogUtil.i(AppConfig.TAG, "Amelia watch: quick pick, ${answered.size} of ${guids.size} answered in 3 s")
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

    /**
     * After the tunnel came up: wait [SETTLE_MS], then one request whose result is ignored
     * (the first one through a fresh XHTTP/QUIC connection pays for its setup).
     */
    private suspend fun warmUp() {
        freshTunnel = false
        delay(SETTLE_MS)
        probe(reserve = AmeliaReserveState.isReserveGuid(MmkvManager.getSelectServer()))
    }

    private fun client(timeoutMs: Long): OkHttpClient =
        AmeliaHttp.clientFor(AmeliaHttp.Route.TUNNEL).newBuilder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .build()

    private val probeClient: OkHttpClient by lazy { client(PROBE_TIMEOUT_MS) }
    private val finalProbeClient: OkHttpClient by lazy { client(FINAL_PROBE_TIMEOUT_MS) }

    /**
     * Requests through the current location (the core's local HTTP inbound) to [PROBE_URLS] at
     * the same time; the first answer counts. Reserve locations: Telegram, any HTTP answer.
     * @return delay in ms, -1 when none answered within the timeout
     */
    private suspend fun probe(reserve: Boolean, timeoutMs: Long = PROBE_TIMEOUT_MS): Long = coroutineScope {
        val c = if (timeoutMs == PROBE_TIMEOUT_MS) probeClient else finalProbeClient
        val urls = if (reserve) listOf(AmeliaReserve.TEST_URL) else PROBE_URLS
        urls.map { url ->
            async(Dispatchers.IO) {
                val start = System.nanoTime()
                try {
                    c.newCall(AmeliaHttp.get(url)).execute().use { resp ->
                        val ms = ((System.nanoTime() - start) / 1_000_000).coerceAtLeast(1L)
                        if (reserve || resp.code in 200..399) ms else -1L
                    }
                } catch (e: Exception) {
                    LogUtil.w(AppConfig.TAG, "Amelia watch: probe through the location failed: ${e.message}")
                    -1L
                }
            }
        }.awaitAll().filter { it >= 0 }.minOrNull() ?: -1L
    }

    // ---- full check in the background -----------------------------------------------------

    /** Every location in parallel (AmeliaCheck); results reach the app as they come. */
    private fun startFullCheck(service: Service) {
        if (checkJob?.isActive == true) return
        checkJob = bgScope.launch {
            val guids = MmkvManager.decodeServerList(AmeliaConfig.SUBSCRIPTION_ID)
            if (guids.isEmpty()) return@launch
            val net = AmeliaHints.netKey(service)
            var total = 0
            var failed = 0
            val ms = AmeliaCheck.checkAll(service, guids) { r ->
                AmeliaChecks.put(service, r, net)
                MessageHelper.sendMsg2UI(service, AmeliaConfig.MSG_CHECKS, "")
                synchronized(this@AmeliaDaemon) {
                    total++
                    if (!r.ok) failed++
                }
            }
            AmeliaChecks.putFull(ms)
            MessageHelper.sendMsg2UI(service, AmeliaConfig.MSG_CHECKS, "")
            LogUtil.i(AppConfig.TAG, "Amelia: full check of ${guids.size} locations took $ms ms, $failed of $total failed")
            // 1.5.9: 80% and more fail on mobile: the next round checks for white lists first
            val many = AmeliaWhitelist.tooManyFailed(failed, total)
            if (!AmeliaWhitelist.isActive() && AmeliaWhitelist.shouldCheck(onMobile(service), many, emptyRounds)) {
                whitelistCheckDue = true
                startWatch(service, 0L)
            }
        }
    }

    // ---- tile, notifications --------------------------------------------------------------

    /** The quick settings tile and the home-screen widget show the current location (1.4.0). */
    private fun refreshTile(context: Context) {
        runCatching { TileService.requestListeningState(context, ComponentName(context, QSTileService::class.java)) }
        AmeliaWidget.requestUpdate(context)
    }

    /** 1.5.9: a quiet notice on the switch channel that opens the app ([id]: one per kind). */
    private fun notifyQuiet(context: Context, id: Int, text: String) {
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
            context, id,
            Intent(context, AmeliaActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
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
            nm.notify(id, n)
        } catch (e: SecurityException) {
            LogUtil.w(AppConfig.TAG, "Amelia: no permission for notice $id", e)
        }
    }

    /**
     * Quiet notice on its own channel (people can turn it off in the system settings). When a
     * location chosen by hand was left, it offers "Вернуться на …" ([leftManual]).
     */
    private fun notifySwitch(context: Context, from: String, to: String, leftManual: String?) {
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
        val text = when {
            leftManual != null -> context.getString(R.string.amelia_switch_text_manual, from, to)
            from.isNotEmpty() -> context.getString(R.string.amelia_switch_text, from, to)
            else -> context.getString(R.string.amelia_switch_text_short, to)
        }
        val builder = NotificationCompat.Builder(context, SWITCH_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .setAutoCancel(true)
        if (leftManual != null) {
            val back = PendingIntent.getBroadcast(
                context, 4,
                Intent(context, ReturnReceiver::class.java).putExtra(ReturnReceiver.EXTRA_NAME, from),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, context.getString(R.string.amelia_switch_return, from), back)
        }
        try {
            nm.notify(SWITCH_NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            LogUtil.w(AppConfig.TAG, "Amelia: no permission for the switch notification", e)
        }
    }

    /**
     * "Вернуться на …" from the switch notification: back to the location chosen by hand; the
     * daemon then leaves it alone for 10 minutes (AmeliaManual).
     */
    class ReturnReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // by name: the subscription may have been refreshed since (new guids)
            val name = intent.getStringExtra(EXTRA_NAME) ?: return
            val guid = AmeliaManual.guidOf(name) ?: return
            NotificationManagerCompat.from(context).cancel(SWITCH_NOTIFICATION_ID)
            MmkvManager.setSelectServer(guid)
            AmeliaReserveState.setAuto(false)
            AmeliaManual.markChosen(guid)
            AmeliaJournal.add("Вернулись на «${MmkvManager.decodeServerConfig(guid)?.remarks.orEmpty()}» вручную")
            LauncherManager.restartService(context)
        }

        companion object {
            const val EXTRA_NAME = "name"
        }
    }

    private fun report(service: Service, h: Health) {
        lastHealth = h
        MessageHelper.sendMsg2UI(service, AmeliaConfig.MSG_HEALTH, h)
    }
}
