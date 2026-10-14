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
    /** VPN started outside the app (tile, boot) and its location fails; no automatic switching. */
    LOCATION_FAILED,
}

data class AmeliaUiState(
    val loaded: Boolean = false,
    val hasKey: Boolean = false,
    val busy: Boolean = false,
    val isRunning: Boolean = false,
    val phase: ConnPhase = ConnPhase.OFF,
    /** Delay through the tunnel from the last successful check. */
    val pingMs: Long? = null,
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
        if (AmeliaUpdater.isCheckDue()) checkUpdate(manual = false)
    }

    fun checkUpdate(manual: Boolean) {
        if (updateJob?.isActive == true) return
        updateJob = viewModelScope.launch {
            val v = withContext(Dispatchers.IO) { AmeliaUpdater.check() }
            _state.update { it.copy(update = v ?: it.update) }
            if (manual && v == null && _state.value.update == null) showMessage(R.string.amelia_update_none)
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
                // A manual choice starts a fresh round of checks from this location.
                beginConnect(guid)
                expectRestart = true
                _state.update { it.copy(phase = ConnPhase.STARTING, pingMs = null) }
                LauncherManager.restartService(getApplication())
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Connect, check internet, switch to the next location on failure (see AmeliaFailover)
    // ---------------------------------------------------------------------------------------

    /** Locations already checked in this connect attempt. */
    private val tried = mutableSetOf<String>()
    /** Failover only for connects the user started in the app, not for tile/boot starts. */
    private var failoverEnabled = false
    /** A restart is in flight: its "stopped" event must not reset the phase. */
    private var expectRestart = false
    private var probeJob: Job? = null
    private var pendingProbeId: String? = null

    private fun beginConnect(guid: String?) {
        tried.clear()
        guid?.let { tried += it }
        failoverEnabled = true
        probeJob?.cancel()
        pendingProbeId = null
    }

    /** Called by the activity right before it starts the VPN. */
    fun onUserConnect() {
        beginConnect(_state.value.selectedGuid)
        _state.update { it.copy(phase = ConnPhase.STARTING, pingMs = null) }
    }

    /** Called by the activity right before it stops the VPN. */
    fun onUserDisconnect() {
        failoverEnabled = false
        expectRestart = false
        probeJob?.cancel()
        pendingProbeId = null
        _state.update { it.copy(phase = ConnPhase.OFF, pingMs = null) }
    }

    /** "Проверить соединение" — re-run the check (and failover) on the running VPN. */
    fun recheck() {
        if (!_state.value.isRunning) return
        beginConnect(_state.value.selectedGuid)
        startProbe()
    }

    private fun startProbe() {
        probeJob?.cancel()
        _state.update { it.copy(phase = ConnPhase.CHECKING) }
        val id = java.util.UUID.randomUUID().toString()
        pendingProbeId = id
        probeJob = viewModelScope.launch {
            delay(AmeliaFailover.PROBE_DELAY_MS)
            serviceEvents.requestTunnelProbe(id)
            delay(AmeliaFailover.PROBE_TIMEOUT_MS)
            // No answer from the core in time: count as a failed check
            if (pendingProbeId == id) onProbe(id, -1L)
        }
    }

    private fun onProbe(requestId: String, delayMs: Long) {
        if (requestId != pendingProbeId) return
        pendingProbeId = null
        probeJob?.cancel()
        if (delayMs >= 0) {
            _state.update { it.copy(phase = ConnPhase.CONNECTED, pingMs = delayMs) }
            return
        }
        viewModelScope.launch {
            val directOk = withContext(Dispatchers.IO) { AmeliaFailover.directInternetOk() }
            when (AmeliaFailover.verdict(delayMs, directOk)) {
                AmeliaFailover.Verdict.CONNECTED -> Unit
                AmeliaFailover.Verdict.NO_INTERNET ->
                    _state.update { it.copy(phase = ConnPhase.NO_INTERNET, pingMs = null) }
                AmeliaFailover.Verdict.SWITCH -> switchToNext()
            }
        }
    }

    private suspend fun switchToNext() {
        val s = _state.value
        if (!failoverEnabled) {
            // VPN was already on when the app opened: report, but don't move the user around
            _state.update { it.copy(phase = ConnPhase.LOCATION_FAILED, pingMs = null) }
            return
        }
        val next = AmeliaFailover.next(s.locations.map { it.guid }, s.selectedGuid, tried)
        if (next == null) {
            failoverEnabled = false
            _state.update { it.copy(phase = ConnPhase.ALL_FAILED, pingMs = null) }
            return
        }
        tried += next
        withContext(Dispatchers.IO) { AmeliaManager.select(next) }
        expectRestart = true
        _state.update { it.copy(selectedGuid = next, phase = ConnPhase.SWITCHING, pingMs = null) }
        LauncherManager.restartService(getApplication())
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
            )
        }
        _state.update {
            it.copy(
                loaded = true,
                hasKey = snapshot.hasKey,
                locations = snapshot.locations,
                selectedGuid = snapshot.selectedGuid,
                info = snapshot.info,
            )
        }
    }

    private fun onServiceEvent(event: AmeliaServiceEvents.Event) {
        when (event) {
            AmeliaServiceEvents.Event.Running -> {
                val wasRunning = _state.value.isRunning
                expectRestart = false
                _state.update { it.copy(isRunning = true) }
                val phase = _state.value.phase
                when {
                    // our own start or switch: check it
                    phase == ConnPhase.STARTING || phase == ConnPhase.SWITCHING -> startProbe()
                    // VPN was already on when the app opened (tile, boot): show its state
                    !wasRunning && phase == ConnPhase.OFF -> startProbe()
                }
            }

            AmeliaServiceEvents.Event.Stopped -> {
                _state.update { it.copy(isRunning = false) }
                if (!expectRestart) {
                    failoverEnabled = false
                    probeJob?.cancel()
                    pendingProbeId = null
                    _state.update { it.copy(phase = ConnPhase.OFF, pingMs = null) }
                }
            }

            is AmeliaServiceEvents.Event.StartFailed -> {
                expectRestart = false
                _state.update {
                    it.copy(isRunning = false, phase = ConnPhase.OFF, pingMs = null, messageText = event.message)
                }
            }

            is AmeliaServiceEvents.Event.Probe -> onProbe(event.requestId, event.delayMs)
        }
    }

    override fun onCleared() {
        serviceEvents.close()
        super.onCleared()
    }
}
