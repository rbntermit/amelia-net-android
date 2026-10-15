package com.v2ray.ang.amelia

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.R
import com.v2ray.ang.core.LauncherManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
) {
    val selectedName: String?
        get() = locations.firstOrNull { it.guid == selectedGuid }?.name

    /** Installed version is below the server's minVersionCode: no connecting until updated. */
    val updateRequired: Boolean
        get() = AmeliaUpdater.isRequired(update)
}

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
        }
        updateGeoIfDue()
        viewModelScope.launch {
            // A forced update found earlier must hold even when the server is unreachable now
            val cached = withContext(Dispatchers.IO) { AmeliaUpdater.cached() }
            if (cached != null) _state.update { it.copy(update = it.update ?: cached) }
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
            if (manual && current == null) {
                showMessage(if (answered) R.string.amelia_update_none else R.string.amelia_update_check_failed)
            }
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

    fun submitKey(raw: String) = runLoad(silent = false) { AmeliaManager.activate(raw) }

    fun refresh(silent: Boolean = false) = runLoad(silent) { AmeliaManager.refresh() }

    fun select(guid: String) {
        if (guid == _state.value.selectedGuid) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { AmeliaManager.select(guid) }
            _state.update { it.copy(selectedGuid = guid) }
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

    /** Starts measuring every location; a second tap cancels. */
    fun pingAll() {
        if (_state.value.pinging) {
            serviceEvents.cancelPingAll()
            finishPing()
            return
        }
        if (_state.value.locations.isEmpty()) return
        val id = java.util.UUID.randomUUID().toString()
        pingRequestId = id
        _state.update { it.copy(pinging = true, pings = emptyMap()) }
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

    private fun finishPing() {
        pingRequestId = null
        pingTimeoutJob?.cancel()
        _state.update { it.copy(pinging = false) }
    }

    /** Called by the activity right before it starts the VPN. */
    fun onUserConnect() {
        _state.update { it.copy(phase = ConnPhase.STARTING, pingMs = null) }
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
        _state.update {
            it.copy(
                phase = phase,
                pingMs = h.pingMs.takeIf { ms -> phase == ConnPhase.CONNECTED && ms >= 0 },
                selectedGuid = h.guid ?: it.selectedGuid,
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

    private fun runLoad(silent: Boolean, block: () -> AmeliaManager.Outcome) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val outcome = withContext(Dispatchers.IO) { block() }
            reload()
            val msg = when (outcome) {
                AmeliaManager.Outcome.OK -> if (silent) null else R.string.amelia_updated
                AmeliaManager.Outcome.INVALID_FORMAT -> R.string.amelia_err_invalid_format
                AmeliaManager.Outcome.UNKNOWN_KEY -> R.string.amelia_err_unknown_key
                AmeliaManager.Outcome.NETWORK_ERROR -> R.string.amelia_err_network
                AmeliaManager.Outcome.EMPTY -> R.string.amelia_err_empty
            }
            // A silent background refresh only reports problems that need the user.
            val shown = if (silent && outcome == AmeliaManager.Outcome.NETWORK_ERROR) null else msg
            _state.update { it.copy(busy = false, message = shown ?: it.message) }
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
                pings = AmeliaManager.getPings(),
            )
        }
        _state.update {
            it.copy(
                loaded = true,
                hasKey = snapshot.hasKey,
                locations = snapshot.locations,
                selectedGuid = snapshot.selectedGuid,
                info = snapshot.info,
                // keep live results while a ping round is running
                pings = if (it.pinging) it.pings else snapshot.pings,
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
                    it.copy(isRunning = false, phase = ConnPhase.OFF, pingMs = null, messageText = event.message)
                }
            }

            is AmeliaServiceEvents.Event.Health -> onHealth(event.health)

            is AmeliaServiceEvents.Event.Traffic -> _state.update { it.copy(traffic = event.traffic) }

            is AmeliaServiceEvents.Event.Ping -> if (event.requestId == pingRequestId) {
                _state.update { it.copy(pings = it.pings + (event.guid to event.delayMs)) }
            }

            is AmeliaServiceEvents.Event.PingDone -> if (event.requestId == pingRequestId) finishPing()
        }
    }

    override fun onCleared() {
        serviceEvents.close()
        super.onCleared()
    }
}
