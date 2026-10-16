package com.v2ray.ang.amelia

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.R
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.handler.MmkvManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Opening the app checks every location in the background when the last check is older. */
private const val BACKGROUND_CHECK_AFTER_MS = 5L * 60 * 1000

/** Upper bound for a "ping all" round; the core gives up on each location after ~12 s. */
private const val PING_ALL_TIMEOUT_MS = 120_000L

/** Russia-only geo bases as the core uses them; counts are 0 when not downloaded yet. */
data class GeoInfo(val sites: Int, val networks: Int, val updatedAt: Long)

/** What the big button shows. */
enum class ConnPhase {
    OFF,
    /** VPN is starting on the chosen location. */
    STARTING,
    /** VPN is up, internet through it is being checked. */
    CHECKING,
    /** The location did not pass the check; moving to the next one. */
    SWITCHING,
    CONNECTED,
    /** VPN is up but the phone has no internet at all. */
    NO_INTERNET,
    /** Every location failed the check. */
    ALL_FAILED,
}

data class AmeliaUiState(
    val loaded: Boolean = false,
    val hasKey: Boolean = false,
    val busy: Boolean = false,
    val isRunning: Boolean = false,
    val phase: ConnPhase = ConnPhase.OFF,
    /** Delay through the tunnel from the last successful check. */
    val pingMs: Long? = null,
    /** "Ping all" results per location guid: ms, or -1 = no answer. */
    val pings: Map<String, Long> = emptyMap(),
    /** "Ping all" is running. */
    val pinging: Boolean = false,
    /** Traffic since the VPN was turned on, from the daemon; null while off. */
    val traffic: AmeliaDaemon.Traffic? = null,
    /** Epoch millis of the last successful subscription update, 0 = never. */
    val subUpdatedAt: Long = 0L,
    // ---- settings screen ----
    val updateChecking: Boolean = false,
    /** Epoch millis of the last successful update check, 0 = never. */
    val lastUpdateCheck: Long = 0L,
    val geoInfo: GeoInfo? = null,
    val routeChecking: Boolean = false,
    val routeCheck: AmeliaRouteCheck.Result? = null,
    /** For a direct route: how fast the site answered without the VPN, -1 = did not answer. */
    val routeDirectMs: Long? = null,
    val locations: List<AmeliaManager.Location> = emptyList(),
    val selectedGuid: String? = null,
    val info: AmeliaSubInfo? = null,
    /** One-shot message for the snackbar; cleared by [AmeliaViewModel.messageShown]. */
    @StringRes val message: Int? = null,
    /** Free-form text for the snackbar (service start errors). */
    val messageText: String? = null,
    /** A newer app version offered by the server. */
    val update: AmeliaUpdater.AppVersion? = null,
    /** Download progress 0..100, -1 = unknown size, null = not downloading. */
    val updateProgress: Int? = null,
    /** Verified APK ready to install. */
    val updateApkPath: String? = null,
    /** What this phone will download for the update (arm64 APK when it can), bytes. */
    val updateSize: Long? = null,
    // ---- 1.1.0 ----
    /** "О программе" facts, loaded when the screen opens. */
    val about: AmeliaAbout.Info? = null,
    /** "Не работает — сообщить" flow; null when closed. */
    val report: ReportUi? = null,
    /** The last start failed (shown until the next connect). */
    val startFailed: Boolean = false,
    val bypassRu: Boolean = true,
    /** Installed apps for the picker; null until loaded. */
    val apps: List<AppRow>? = null,
    val probeEnabled: Boolean = true,
    /** Fingerprint of the announcement the person closed. */
    val announceDismissed: String? = null,
    /** How many packages go around the VPN (built-in list with the person's changes). */
    val bypassCount: Int = 0,
    /** Connected to a reserve location (only Telegram) because no regular one works. */
    val reserveFallback: Boolean = false,
    // ---- 1.3.0 ----
    val crashEnabled: Boolean = true,
    /** The app is not under battery optimisation (may run in the background). */
    val batteryOk: Boolean = true,
    /** Once after the first connection while battery optimisation is on. */
    val showBatteryCard: Boolean = false,
    /** Speed per location guid from the last 24 h, Mbit/s. */
    val speeds: Map<String, Double> = emptyMap(),
    /** Guid of the location whose speed is being measured. */
    val speedTesting: String? = null,
    // ---- 1.2.0 ----
    /** When the newest shown result was measured, epoch millis; 0 = never. */
    val checkedAt: Long = 0L,
    /** How long the last check of all locations took, ms; 0 = unknown. */
    val lastCheckMs: Long = 0L,
    /** Locations the server says do not work for this provider (and our check does not contradict). */
    val providerBad: Set<String> = emptySet(),
) {
    val selectedName: String?
        get() = locations.firstOrNull { it.guid == selectedGuid }?.name

    /** Installed version is below the server's minVersionCode: no connecting until updated. */
    val updateRequired: Boolean
        get() = AmeliaUpdater.isRequired(update)

    /**
     * Working only on a reserve location (only Telegram): the daemon fell back to it, or the
     * subscription has ended and that is where the VPN is.
     */
    val onReserveFallback: Boolean
        get() = phase == ConnPhase.CONNECTED && AmeliaReserve.isReserve(selectedName) &&
            (reserveFallback || (info != null && AmeliaFormat.isExpired(info, System.currentTimeMillis())))

    /** Our announcement to show above the locations, unless the person closed this text. */
    val announce: String?
        get() = AmeliaAnnounce.visible(info?.announce, announceDismissed)

    /**
     * "Не работает — сообщить" on the main screen: the connection failed, or it is up but no
     * data has come through it for [NO_TRAFFIC_MS]. The menu always has it.
     */
    val showReportButton: Boolean
        get() = startFailed || phase == ConnPhase.NO_INTERNET || phase == ConnPhase.ALL_FAILED ||
            (isRunning && traffic != null && traffic.down == 0L &&
                System.currentTimeMillis() - traffic.startedAt > NO_TRAFFIC_MS)
}

/** Connected this long without a single byte down counts as "no traffic". */
const val NO_TRAFFIC_MS = 20_000L

enum class ReportPhase {
    /** Every location is being checked. */
    TESTING,
    /** Results shown, waiting for "Отправить в поддержку". */
    RESULT,
    SENDING,
    SENT,
    /** No server took the report: copy it and open the bot. */
    FAILED,
}

data class ReportUi(
    val phase: ReportPhase = ReportPhase.TESTING,
    val total: Int = 0,
    val checks: Map<String, AmeliaReport.Check> = emptyMap(),
    val servers: List<AmeliaReport.Server> = emptyList(),
    val reportId: String? = null,
    /** Report as text for the bot, when sending failed. */
    val fallbackText: String? = null,
    /** How long the check of all locations took, ms (sent as check_ms). */
    val checkMs: Long? = null,
) {
    val working: List<String> get() = AmeliaReport.split(servers).first
    val failing: List<String> get() = AmeliaReport.split(servers).second
}

/** One installed app in the "мимо VPN" picker. */
data class AppRow(val pkg: String, val label: String, val bypass: Boolean, val builtIn: Boolean)

class AmeliaViewModel(app: Application) : AndroidViewModel(app) {

    private val serviceEvents = AmeliaServiceEvents(app)

    private val _state = MutableStateFlow(AmeliaUiState())
    val state: StateFlow<AmeliaUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    // Declared before init: init launches the first update check.
    private var updateJob: Job? = null

    init {
        viewModelScope.launch {
            serviceEvents.events.collect(::onServiceEvent)
        }
        viewModelScope.launch {
            reload()
            if (_state.value.hasKey) refresh(silent = true)
            refreshInBackground()
        }
        // crash reports saved by an earlier run (any process), around the tunnel
        viewModelScope.launch(Dispatchers.IO) { AmeliaCrash.sendPending(getApplication()) }
        updateGeoIfDue()
        viewModelScope.launch {
            // A forced update found earlier must hold even when the server is unreachable now
            val cached = withContext(Dispatchers.IO) { AmeliaUpdater.cached() }
            if (cached != null) _state.update { it.copy(update = it.update ?: cached) }
            cached?.let { onUpdateFound(it) }
            if (AmeliaUpdater.isCheckDue()) checkUpdate(manual = false)
        }
    }

    fun checkUpdate(manual: Boolean) {
        if (updateJob?.isActive == true) return
        updateJob = viewModelScope.launch {
            _state.update { it.copy(updateChecking = true) }
            val before = AmeliaUpdater.lastCheck()
            val v = withContext(Dispatchers.IO) { AmeliaUpdater.check() }
            val answered = AmeliaUpdater.lastCheck() != before
            // check() refreshed the cache: null now means "up to date" or "no answer"
            val current = v ?: withContext(Dispatchers.IO) { AmeliaUpdater.cached() }
            _state.update {
                it.copy(update = current, updateChecking = false, lastUpdateCheck = AmeliaUpdater.lastCheck())
            }
            current?.let { onUpdateFound(it) }
            if (manual && current == null) {
                showMessage(if (answered) R.string.amelia_update_none else R.string.amelia_update_check_failed)
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // "О программе"
    // ---------------------------------------------------------------------------------------

    fun loadAbout() {
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) { AmeliaAbout.load(getApplication()) }
            _state.update { it.copy(about = info) }
        }
    }

    fun supportText(): String? = _state.value.about?.let { AmeliaAbout.supportText(getApplication(), it) }

    // ---------------------------------------------------------------------------------------
    // "Не работает — сообщить"
    // ---------------------------------------------------------------------------------------

    private var reportRequestId: String? = null
    private var reportStartedAt = 0L
    private var reportJob: Job? = null

    /** Checks every location with a real request through it, at most [AmeliaReport.TEST_TIMEOUT_MS]. */
    fun startReport() {
        val locations = _state.value.locations
        if (_state.value.pinging) {
            serviceEvents.cancelPingAll()
            finishPing()
        }
        val id = java.util.UUID.randomUUID().toString()
        reportRequestId = id
        reportStartedAt = System.currentTimeMillis()
        _state.update { it.copy(report = ReportUi(ReportPhase.TESTING, total = locations.size)) }
        if (locations.isEmpty()) {
            finishReportTest(id)
            return
        }
        serviceEvents.requestPingAll(id, AmeliaConfig.SUBSCRIPTION_ID)
        reportJob?.cancel()
        reportJob = viewModelScope.launch {
            delay(AmeliaReport.TEST_TIMEOUT_MS)
            if (reportRequestId == id) {
                serviceEvents.cancelPingAll()
                finishReportTest(id)
            }
        }
    }

    private fun onReportPing(e: AmeliaServiceEvents.Event.Ping) {
        val check = AmeliaReport.Check(e.guid, e.delayMs, e.stage ?: AmeliaPingStage.classify(e.delayMs, null, e.error), e.error)
        _state.update { s ->
            val r = s.report ?: return@update s
            s.copy(report = r.copy(checks = r.checks + (e.guid to check)), pings = s.pings + (e.guid to e.delayMs))
        }
        val r = _state.value.report ?: return
        if (r.checks.size >= r.total) finishReportTest(e.requestId)
    }

    private fun finishReportTest(id: String) {
        if (reportRequestId != id) return
        reportRequestId = null
        reportJob?.cancel()
        viewModelScope.launch {
            val servers = withContext(Dispatchers.IO) {
                AmeliaReport.servers(AmeliaReport.locations(), _state.value.report?.checks.orEmpty())
            }
            val checkMs = System.currentTimeMillis() - reportStartedAt
            withContext(Dispatchers.IO) { AmeliaChecks.putFull(checkMs) }
            _state.update { s -> s.copy(report = s.report?.copy(phase = ReportPhase.RESULT, servers = servers, checkMs = checkMs)) }
            reloadChecks()
            // With the VPN on, let the daemon move to a location that works
            val s = _state.value
            if (s.isRunning && s.phase != ConnPhase.CONNECTED && servers.any { it.ok }) recheck()
        }
    }

    fun sendReport(comment: String) {
        val r = _state.value.report ?: return
        if (r.phase != ReportPhase.RESULT && r.phase != ReportPhase.FAILED) return
        _state.update { it.copy(report = r.copy(phase = ReportPhase.SENDING)) }
        viewModelScope.launch {
            val app = getApplication<Application>()
            val key = AmeliaManager.getKey().orEmpty()
            val active = _state.value.selectedName
            val id = withContext(Dispatchers.IO) { AmeliaReport.send(app, key, r.servers, active, comment, r.checkMs) }
            if (id != null) {
                _state.update { it.copy(report = it.report?.copy(phase = ReportPhase.SENT, reportId = id)) }
            } else {
                val text = withContext(Dispatchers.IO) {
                    AmeliaReport.plainText(
                        AmeliaReport.body(app, key, r.servers, active, comment, AmeliaNetwork.info(app), AmeliaNetwork.PATH_DIRECT, r.checkMs),
                    )
                }
                _state.update { it.copy(report = it.report?.copy(phase = ReportPhase.FAILED, fallbackText = text)) }
            }
        }
    }

    fun closeReport() {
        if (reportRequestId != null) {
            reportRequestId = null
            reportJob?.cancel()
            serviceEvents.cancelPingAll()
        }
        _state.update { it.copy(report = null) }
    }

    // ---------------------------------------------------------------------------------------
    // Russian apps around the VPN, blocking map, hints
    // ---------------------------------------------------------------------------------------

    /** Turning the list on or off applies when the VPN starts, so a running VPN is restarted. */
    fun setBypassRu(enabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { AmeliaApps.setEnabled(enabled) }
            _state.update { it.copy(bypassRu = enabled) }
            restartIfRunning()
        }
    }

    private var appsChanged = false

    fun loadApps() {
        viewModelScope.launch {
            val rows = withContext(Dispatchers.IO) { installedApps() }
            _state.update { it.copy(apps = rows) }
        }
    }

    fun setAppBypass(pkg: String, bypass: Boolean) {
        appsChanged = true
        _state.update { s -> s.copy(apps = s.apps?.map { if (it.pkg == pkg) it.copy(bypass = bypass) else it }) }
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) {
                AmeliaApps.setBypass(pkg, bypass)
                AmeliaApps.bypassed().size
            }
            _state.update { it.copy(bypassCount = count) }
        }
    }

    /** The picker was closed: changes reach a running VPN with one restart. */
    fun appsClosed() {
        if (!appsChanged) return
        appsChanged = false
        restartIfRunning()
    }

    private fun restartIfRunning() {
        if (!_state.value.isRunning) return
        expectRestart = true
        _state.update { it.copy(phase = ConnPhase.STARTING, pingMs = null) }
        LauncherManager.restartService(getApplication())
    }

    private fun installedApps(): List<AppRow> {
        val app = getApplication<Application>()
        val pm = app.packageManager
        val bypassed = AmeliaApps.bypassed()
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        return AmeliaPackages.launcherActivities(pm, intent)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != app.packageName }
            .distinctBy { it.first }
            .map { (pkg, label) -> AppRow(pkg, label, pkg in bypassed, pkg in AmeliaApps.BUILT_IN_PACKAGES) }
            .sortedWith(compareByDescending<AppRow> { it.bypass }.thenBy { it.label.lowercase() })
    }

    fun setProbeEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { AmeliaProbe.setEnabled(enabled) }
        _state.update { it.copy(probeEnabled = enabled) }
    }

    fun dismissAnnounce() {
        val text = _state.value.announce ?: return
        viewModelScope.launch(Dispatchers.IO) { AmeliaAnnounce.dismiss(text) }
        _state.update { it.copy(announceDismissed = AmeliaAnnounce.fingerprint(text)) }
    }

    private var lastResumeRefresh = 0L

    /** Back from the bot after paying: if the end was near, see whether it moved. */
    fun onResume() {
        val s = _state.value
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { AmeliaBattery.isAllowed(getApplication()) }
            _state.update { it.copy(batteryOk = ok, showBatteryCard = it.showBatteryCard && !ok) }
        }
        val now = System.currentTimeMillis()
        if (!s.hasKey || s.busy || now - lastResumeRefresh < 60_000L) return
        if (!AmeliaExpiry.needsRefresh(s.info, now)) return
        lastResumeRefresh = now
        refresh(silent = true)
    }

    // ---------------------------------------------------------------------------------------
    // 1.2.0: last known results, hints, best location
    // ---------------------------------------------------------------------------------------

    private class ChecksSnapshot(
        val pings: Map<String, Long>,
        val checkedAt: Long,
        val lastCheckMs: Long,
        val providerBad: Set<String>,
        val lastFullAt: Long,
    )

    /** Blocking: what AmeliaChecks and AmeliaHints know about the current locations. */
    private fun checksSnapshot(): ChecksSnapshot {
        val app = getApplication<Application>()
        val store = AmeliaChecks.load()
        val locations = AmeliaQueue.locations()
        val names = locations.map { it.name }.toSet()
        val fresh = AmeliaChecks.fresh(store, System.currentTimeMillis())
        val bad = AmeliaHints.badHostPorts(AmeliaHints.current(AmeliaProbe.netKey(AmeliaNetwork.info(app))))
        return ChecksSnapshot(
            pings = AmeliaManager.getPings(),
            checkedAt = store.entries.values.filter { it.name in names }.maxOfOrNull { it.ts } ?: 0L,
            lastCheckMs = store.lastFullMs,
            // our own check saying it works beats the hint
            providerBad = locations.filter { it.hostPort in bad && fresh[it.name]?.ok != true }.map { it.guid }.toSet(),
            lastFullAt = store.lastFullAt,
        )
    }

    /** New results from the daemon (or a finished round): refresh the list. */
    private fun reloadChecks() {
        viewModelScope.launch {
            val c = withContext(Dispatchers.IO) { checksSnapshot() }
            _state.update {
                it.copy(
                    pings = if (it.pinging) it.pings else c.pings,
                    checkedAt = c.checkedAt,
                    lastCheckMs = c.lastCheckMs,
                    providerBad = c.providerBad,
                )
            }
        }
    }

    /** App opened: hints for this provider, and fresh results in the background if they are old. */
    private fun refreshInBackground() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { AmeliaHints.refreshIfStale(getApplication()) }
            reloadChecks()
            val s = _state.value
            val stale = withContext(Dispatchers.IO) {
                System.currentTimeMillis() - AmeliaChecks.load().lastFullAt > BACKGROUND_CHECK_AFTER_MS
            }
            // with the VPN on the daemon keeps the results fresh itself
            if (s.hasKey && s.locations.isNotEmpty() && !s.isRunning && stale && !s.pinging) pingAll(silent = true)
        }
    }

    /** "⚡ Подключить лучший": the first location of the failover queue; [connect] starts the VPN. */
    fun connectBest(connect: () -> Unit) {
        viewModelScope.launch {
            val guid = withContext(Dispatchers.IO) { AmeliaQueue.current(getApplication()).firstOrNull() } ?: return@launch
            if (guid != _state.value.selectedGuid) {
                withContext(Dispatchers.IO) {
                    AmeliaManager.select(guid)
                    AmeliaReserveState.setAuto(false)
                }
                _state.update { it.copy(selectedGuid = guid, reserveFallback = false) }
                if (_state.value.isRunning) {
                    restartIfRunning()
                    return@launch
                }
            }
            if (!_state.value.isRunning) connect()
        }
    }

    // ---------------------------------------------------------------------------------------
    // 1.3.0: crash reports, background work, speed test
    // ---------------------------------------------------------------------------------------

    fun setCrashEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { AmeliaCrash.setEnabled(enabled) }
        _state.update { it.copy(crashEnabled = enabled) }
    }

    /** The card was closed or its button pressed: not shown again (settings keep the entry). */
    fun batteryCardDone() {
        viewModelScope.launch(Dispatchers.IO) { MmkvManager.encodeSettings(AmeliaBattery.PREF_CARD_SHOWN, true) }
        _state.update { it.copy(showBatteryCard = false) }
    }

    private var speedJob: Job? = null

    /** Long press → "Замерить скорость": 10 MB through that location, only when asked. */
    fun measureSpeed(guid: String) {
        if (speedJob?.isActive == true) return
        speedJob = viewModelScope.launch {
            _state.update { it.copy(speedTesting = guid) }
            val mbps = withContext(Dispatchers.IO) { AmeliaSpeed.measure(getApplication(), guid) }
            val speeds = withContext(Dispatchers.IO) { AmeliaSpeed.byGuid() }
            _state.update { it.copy(speedTesting = null, speeds = speeds) }
            if (mbps == null) showMessage(R.string.amelia_speed_failed)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Settings: what bypasses the VPN
    // ---------------------------------------------------------------------------------------

    private var geoSite: AmeliaRouteCheck.GeoSite? = null
    private var geoIp: AmeliaRouteCheck.CidrSet? = null
    private var routeJob: Job? = null

    /** Settings screen opened: geo base info and the last update check time. */
    fun loadSettingsInfo() {
        _state.update { it.copy(lastUpdateCheck = AmeliaUpdater.lastCheck()) }
        viewModelScope.launch {
            val app = getApplication<Application>()
            withContext(Dispatchers.IO) { loadGeo(app) }
            _state.update {
                it.copy(geoInfo = GeoInfo(geoSite?.size ?: 0, geoIp?.size ?: 0, AmeliaGeoUpdater.lastUpdated()))
            }
        }
    }

    private fun loadGeo(app: Application) {
        val dir = java.io.File(com.v2ray.ang.util.Utils.userAssetPath(app))
        geoSite = runCatching {
            AmeliaRouteCheck.parseGeoSite(java.io.File(dir, com.v2ray.ang.AppConfig.GEOSITE_DAT).readBytes(), "CATEGORY-RU")
        }.getOrNull()
        geoIp = runCatching {
            AmeliaRouteCheck.parseGeoIp(java.io.File(dir, com.v2ray.ang.AppConfig.GEOIP_DAT).readBytes(), "RU")
        }.getOrNull()
    }

    /** Where does [input] go — directly or through the VPN — and, if directly, does it open. */
    fun checkRoute(input: String) {
        routeJob?.cancel()
        routeJob = viewModelScope.launch {
            _state.update { it.copy(routeChecking = true, routeCheck = null, routeDirectMs = null) }
            val app = getApplication<Application>()
            val result = withContext(Dispatchers.IO) {
                if (geoSite == null && geoIp == null) loadGeo(app)
                AmeliaRouteCheck.classify(input, geoSite, geoIp) { host ->
                    java.net.InetAddress.getAllByName(host).toList()
                }
            }
            _state.update { it.copy(routeCheck = result) }
            val directMs = if (result.route == AmeliaRouteCheck.Route.DIRECT) {
                // The app's own traffic bypasses the VPN, so this is exactly the direct path
                withContext(Dispatchers.IO) { AmeliaFailover.directReachMs(result.host) }
            } else null
            _state.update { it.copy(routeChecking = false, routeDirectMs = directMs) }
        }
    }

    /**
     * 1.3.0: an APK downloaded earlier (Wi-Fi in the background) is offered right away; on Wi-Fi
     * the download starts by itself; on mobile data the dialog asks and shows the size.
     */
    private fun onUpdateFound(v: AmeliaUpdater.AppVersion) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val (ready, size, unmetered) = withContext(Dispatchers.IO) {
                Triple(AmeliaUpdater.readyApk(app, v), AmeliaUpdater.downloadSize(v), AmeliaUpdater.isUnmetered(app))
            }
            _state.update { it.copy(updateSize = size, updateApkPath = ready?.absolutePath ?: it.updateApkPath) }
            if (ready == null && unmetered) {
                updateJob?.join() // the check that found it must finish first
                if (_state.value.updateProgress == null && _state.value.updateApkPath == null) downloadUpdate()
            }
        }
    }

    /** Opened from "Обновление … готово — установить": [install] once the APK is known. */
    fun installWhenReady(install: (String) -> Unit) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val v = _state.value.update ?: withContext(Dispatchers.IO) { AmeliaUpdater.cached() } ?: return@launch
            val f = withContext(Dispatchers.IO) { AmeliaUpdater.readyApk(app, v) } ?: return@launch
            _state.update { it.copy(update = it.update ?: v, updateApkPath = f.absolutePath) }
            install(f.absolutePath)
        }
    }

    fun downloadUpdate() {
        val v = _state.value.update ?: return
        if (updateJob?.isActive == true) return
        updateJob = viewModelScope.launch {
            _state.update { it.copy(updateProgress = 0, updateApkPath = null) }
            val file = withContext(Dispatchers.IO) {
                AmeliaUpdater.download(getApplication(), v) { p -> _state.update { it.copy(updateProgress = p) } }
            }
            _state.update { it.copy(updateProgress = null, updateApkPath = file?.absolutePath) }
            if (file == null) showMessage(R.string.amelia_update_failed)
        }
    }

    /** First start and then once a day; the background worker covers days the app is not opened. */
    private fun updateGeoIfDue() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val switchedOn = withContext(Dispatchers.IO) {
                AmeliaGeoUpdater.isDue(app) && AmeliaGeoUpdater.update(app)
            }
            // Russian sites go direct only after the core reloads with the geo rules.
            if (switchedOn && _state.value.isRunning) LauncherManager.restartService(app)
        }
    }

    fun submitKey(raw: String) = runLoad(silent = false, isRefresh = false) { AmeliaManager.activate(raw) }

    fun refresh(silent: Boolean = false) = runLoad(silent, isRefresh = true) { AmeliaManager.refresh() }

    fun select(guid: String) {
        if (guid == _state.value.selectedGuid) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                AmeliaManager.select(guid)
                // the person chose: no longer an automatic fallback to a reserve location
                AmeliaReserveState.setAuto(false)
            }
            _state.update { it.copy(selectedGuid = guid, reserveFallback = false) }
            if (_state.value.isRunning) {
                // The daemon checks the new location after the restart (a fresh round of failover).
                expectRestart = true
                _state.update { it.copy(phase = ConnPhase.STARTING, pingMs = null) }
                LauncherManager.restartService(getApplication())
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Connection state. The internet check and the switch to the next location run in the
    // VPN daemon (AmeliaDaemon), also with the app closed; the app only shows what it reports.
    // ---------------------------------------------------------------------------------------

    /** A restart is in flight (user picked a location or the daemon switched): ignore its "stopped". */
    private var expectRestart = false
    // ---------------------------------------------------------------------------------------
    // Ping all locations (like Happ)
    // ---------------------------------------------------------------------------------------

    private var pingRequestId: String? = null
    private var pingTimeoutJob: Job? = null

    private var pingStartedAt = 0L

    /**
     * Starts measuring every location; a second tap cancels. [silent]: refresh in the background
     * without clearing the last known results first.
     */
    fun pingAll(silent: Boolean = false) {
        if (_state.value.pinging) {
            serviceEvents.cancelPingAll()
            finishPing()
            return
        }
        if (_state.value.locations.isEmpty()) return
        val id = java.util.UUID.randomUUID().toString()
        pingRequestId = id
        pingStartedAt = System.currentTimeMillis()
        _state.update { it.copy(pinging = true, pings = if (silent) it.pings else emptyMap()) }
        serviceEvents.requestPingAll(id, AmeliaConfig.SUBSCRIPTION_ID)
        pingTimeoutJob?.cancel()
        pingTimeoutJob = viewModelScope.launch {
            // safety net if the test service never reports back
            delay(PING_ALL_TIMEOUT_MS)
            if (pingRequestId == id) {
                serviceEvents.cancelPingAll()
                finishPing()
            }
        }
    }

    private fun finishPing(completed: Boolean = false) {
        pingRequestId = null
        pingTimeoutJob?.cancel()
        _state.update { it.copy(pinging = false) }
        if (completed) {
            val ms = System.currentTimeMillis() - pingStartedAt
            viewModelScope.launch {
                withContext(Dispatchers.IO) { AmeliaChecks.putFull(ms) }
                reloadChecks()
            }
        }
    }

    /** Called by the activity right before it starts the VPN. */
    fun onUserConnect() {
        _state.update { it.copy(phase = ConnPhase.STARTING, pingMs = null, startFailed = false) }
    }

    /** Called by the activity right before it stops the VPN. */
    fun onUserDisconnect() {
        expectRestart = false
        _state.update { it.copy(phase = ConnPhase.OFF, pingMs = null) }
    }

    /** "Не работает?" with the VPN on: the daemon re-checks now and tries every location again. */
    fun recheck() {
        if (!_state.value.isRunning) return
        _state.update { it.copy(phase = ConnPhase.CHECKING) }
        serviceEvents.requestRecheck()
    }

    private var batteryChecked = false

    private fun onHealth(h: AmeliaDaemon.Health) {
        val phase = when (h.phase) {
            AmeliaDaemon.Phase.CHECKING.name -> ConnPhase.CHECKING
            AmeliaDaemon.Phase.CONNECTED.name -> ConnPhase.CONNECTED
            AmeliaDaemon.Phase.SWITCHING.name -> ConnPhase.SWITCHING
            AmeliaDaemon.Phase.NO_INTERNET.name -> ConnPhase.NO_INTERNET
            AmeliaDaemon.Phase.ALL_FAILED.name -> ConnPhase.ALL_FAILED
            else -> return
        }
        if (phase == ConnPhase.SWITCHING) expectRestart = true
        if (phase == ConnPhase.CONNECTED && !batteryChecked) {
            batteryChecked = true
            viewModelScope.launch {
                val show = withContext(Dispatchers.IO) {
                    !AmeliaBattery.isAllowed(getApplication()) && !MmkvManager.decodeSettingsBool(AmeliaBattery.PREF_CARD_SHOWN, false)
                }
                if (show) _state.update { it.copy(showBatteryCard = true, batteryOk = false) }
            }
        }
        _state.update {
            it.copy(
                phase = phase,
                pingMs = h.pingMs.takeIf { ms -> phase == ConnPhase.CONNECTED && ms >= 0 },
                selectedGuid = h.guid ?: it.selectedGuid,
                reserveFallback = phase == ConnPhase.CONNECTED && h.reserveFallback,
            )
        }
    }

    fun logout() {
        loadJob?.cancel()
        viewModelScope.launch {
            onUserDisconnect()
            if (_state.value.isRunning) LauncherManager.stopService(getApplication())
            withContext(Dispatchers.IO) { AmeliaManager.logout() }
            reload()
        }
    }

    fun messageShown() = _state.update { it.copy(message = null, messageText = null) }

    fun showMessage(@StringRes res: Int) = _state.update { it.copy(message = res) }

    private fun runLoad(silent: Boolean, isRefresh: Boolean, block: () -> AmeliaManager.Outcome) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val before = _state.value.selectedName
            val outcome = withContext(Dispatchers.IO) { block() }
            reload()
            // e.g. renewed after a fallback to a reserve location: move the VPN to the new choice
            if (outcome == AmeliaManager.Outcome.OK && before != null && _state.value.selectedName != before) {
                restartIfRunning()
            }
            val msg = when (outcome) {
                AmeliaManager.Outcome.OK -> if (silent) null else R.string.amelia_updated
                AmeliaManager.Outcome.INVALID_FORMAT -> R.string.amelia_err_invalid_format
                AmeliaManager.Outcome.UNKNOWN_KEY -> R.string.amelia_err_unknown_key
                // A failed refresh is always reported (also the silent one on app start): the app
                // keeps working on old locations, and the person should know it is stale.
                AmeliaManager.Outcome.NETWORK_ERROR ->
                    if (isRefresh) R.string.amelia_err_sub_refresh else R.string.amelia_err_network
                AmeliaManager.Outcome.EMPTY -> R.string.amelia_err_empty
            }
            _state.update { it.copy(busy = false, message = msg ?: it.message) }
        }
    }

    private suspend fun reload() {
        val snapshot = withContext(Dispatchers.IO) {
            AmeliaUiState(
                loaded = true,
                hasKey = AmeliaManager.hasKey(),
                locations = AmeliaManager.getLocations(),
                selectedGuid = AmeliaManager.getSelectedGuid(),
                info = AmeliaManager.getSubInfo(),
                subUpdatedAt = AmeliaManager.lastSubscriptionUpdate(),
                pings = AmeliaManager.getPings(),
                bypassRu = AmeliaApps.isEnabled(),
                bypassCount = AmeliaApps.bypassed().size,
                probeEnabled = AmeliaProbe.isEnabled(),
                crashEnabled = AmeliaCrash.isEnabled(),
                batteryOk = AmeliaBattery.isAllowed(getApplication()),
                speeds = AmeliaSpeed.byGuid(),
                announceDismissed = AmeliaAnnounce.dismissed(),
            ).also { AmeliaExpiry.check(getApplication(), it.info) }
        }
        val checks = withContext(Dispatchers.IO) { checksSnapshot() }
        _state.update {
            it.copy(
                loaded = true,
                hasKey = snapshot.hasKey,
                locations = snapshot.locations,
                selectedGuid = snapshot.selectedGuid,
                info = snapshot.info,
                subUpdatedAt = snapshot.subUpdatedAt,
                // keep live results while a ping round is running
                pings = if (it.pinging) it.pings else snapshot.pings,
                bypassRu = snapshot.bypassRu,
                bypassCount = snapshot.bypassCount,
                probeEnabled = snapshot.probeEnabled,
                crashEnabled = snapshot.crashEnabled,
                batteryOk = snapshot.batteryOk,
                speeds = snapshot.speeds,
                announceDismissed = snapshot.announceDismissed,
                checkedAt = checks.checkedAt,
                lastCheckMs = checks.lastCheckMs,
                providerBad = checks.providerBad,
            )
        }
    }

    private fun onServiceEvent(event: AmeliaServiceEvents.Event) {
        when (event) {
            AmeliaServiceEvents.Event.Running -> {
                expectRestart = false
                _state.update {
                    // the daemon's first health report follows within seconds
                    it.copy(isRunning = true, phase = if (it.phase == ConnPhase.OFF) ConnPhase.CHECKING else it.phase)
                }
            }

            AmeliaServiceEvents.Event.Stopped -> {
                _state.update { it.copy(isRunning = false) }
                if (!expectRestart) {
                    _state.update { it.copy(phase = ConnPhase.OFF, pingMs = null, traffic = null) }
                }
            }

            is AmeliaServiceEvents.Event.StartFailed -> {
                expectRestart = false
                _state.update {
                    it.copy(isRunning = false, phase = ConnPhase.OFF, pingMs = null, messageText = event.message, startFailed = true)
                }
            }

            is AmeliaServiceEvents.Event.Health -> onHealth(event.health)

            is AmeliaServiceEvents.Event.Traffic -> _state.update { it.copy(traffic = event.traffic) }

            is AmeliaServiceEvents.Event.Ping -> when (event.requestId) {
                pingRequestId -> _state.update { it.copy(pings = it.pings + (event.guid to event.delayMs)) }
                reportRequestId -> onReportPing(event)
            }

            AmeliaServiceEvents.Event.ChecksUpdated -> reloadChecks()

            is AmeliaServiceEvents.Event.PingDone -> when (event.requestId) {
                pingRequestId -> finishPing(completed = true)
                reportRequestId -> finishReportTest(event.requestId)
            }
        }
    }

    override fun onCleared() {
        serviceEvents.close()
        super.onCleared()
    }
}
