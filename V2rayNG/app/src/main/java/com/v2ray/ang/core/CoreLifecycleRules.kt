package com.v2ray.ang.core

/**
 * Amelia.Net 1.7.0: lifecycle decisions of the core and the VPN service, kept pure so they can be
 * tested on the JVM (CoreServiceManager loads the native library and cannot be).
 *
 * Start, in-place reload, revive and stop of the core run under one lock in CoreServiceManager.
 * A stop marks "stopping" before it takes the lock, so a reload or start waiting for the lock (a
 * location switch, a network change, a VPN setup in flight) sees it and starts nothing; until 1.6.1
 * a reload could bring the core back after the VPN was turned off.
 */
object CoreLifecycleRules {

    /** What a start command to CoreVpnService does. */
    enum class StartCommand {
        /** Nothing runs yet and no setup is in flight: set up the VPN and start the core. */
        START,
        /** The core already runs for this service: keep it (a second start leaves one core). */
        ALREADY_RUNNING,
        /** Another start is setting up right now: it decides; nothing new is started. */
        IN_PROGRESS,
    }

    /**
     * [coreRunning]: the core runs and no stop was asked; [startLockFree]: no setup in flight.
     * Both duplicates return a sticky mode: the running or the in-flight start owns the service,
     * and a failed setup stops the service itself, so Android never restarts a dead one.
     */
    fun startCommand(coreRunning: Boolean, startLockFree: Boolean): StartCommand = when {
        coreRunning -> StartCommand.ALREADY_RUNNING
        !startLockFree -> StartCommand.IN_PROGRESS
        else -> StartCommand.START
    }

    /** A first start of the core: not after a stop was asked, not over a running core. */
    fun mayStart(stopping: Boolean, running: Boolean): Boolean = !stopping && !running

    /**
     * An in-place reload (location switch, network change): only while the core runs, no stop
     * was asked, and — for a network change — the network monitor that asked is still the one
     * the core manager owns ([ownMonitor]).
     */
    fun mayReload(stopping: Boolean, running: Boolean, ownMonitor: Boolean): Boolean = !stopping && running && ownMonitor

    /**
     * The service is up (started once and not stopping) but the core is not running, e.g. after a
     * failed reload: the daemon may start it again on the same VPN interface.
     */
    fun mayRevive(started: Boolean, stopping: Boolean, running: Boolean): Boolean = started && !stopping && !running

    /** The core may be started again once it has been down this long while the service lives. */
    const val REVIVE_AFTER_MS = 10_000L

    /** [downSince]: when the daemon first saw the core down (0: it was running). */
    fun reviveDue(downSince: Long, now: Long): Boolean = downSince > 0 && now - downSince >= REVIVE_AFTER_MS
}
