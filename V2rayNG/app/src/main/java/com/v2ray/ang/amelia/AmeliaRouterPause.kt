package com.v2ray.ang.amelia

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.core.content.IntentCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 1.9.0 "Пауза за роутером Amelia". On Wi-Fi or Ethernet (network change, app start, VPN start) the
 * app asks /app/whoami around the tunnel (AmeliaWhoami.ROUTER_BASES, 3 s). "ours": the network itself
 * already goes through our server, so blocked sites open without the VPN on the phone. The first time
 * the app offers to pause the VPN in such networks ([Setting], kept as a setting, not per network).
 *
 * The pause is a plain stop (LauncherManager.stopService); the VPN comes back by itself through the same
 * start as the widget and Always-on (AmeliaBoot.ensureLocation, LauncherManager.startServiceFromToggle)
 * when the network changes or the check stops saying "ours". A start by hand during the pause (button,
 * tile, widget: [onManualStart]) keeps the VPN on in this network until the network changes.
 * No answer, an error or "not ours" outside a pause: nothing is done or shown.
 */
object AmeliaRouterPause {

    enum class Setting { ASK, ON, OFF }

    enum class Action {
        NOTHING,
        /** First time on a router network: "Поставить VPN на паузу в таких сетях?". */
        OFFER,
        PAUSE,
        /** Paused, and the network is still ours: stay paused (the network handle may be new). */
        STAY,
        RESUME,
        /** Paused, but something started the VPN anyway (Always-on): no stop loop, it stays on here. */
        HOLD,
    }

    private const val PREF_SETTING = "amelia_router_pause"
    private const val PREF_PAUSED = "amelia_router_paused"
    private const val PREF_HOLD = "amelia_router_hold"
    private const val PREF_OFFER = "amelia_router_offer"

    private const val WORK_NAME = "amelia_router_resume"
    /** Paused on a metered Wi-Fi: the "left for mobile data" job would run at once; it waits this long then. */
    private const val STAY_RECHECK_MIN = 15L
    private const val NOTIFICATION_ID = 7306

    /** [net]: Network.getNetworkHandle of the network the VPN was paused on. */
    data class Paused(val net: Long = 0L, val at: Long = 0L)

    // ---- pure logic (unit-tested) ---------------------------------------------------------

    /**
     * [ourNet]: Wi-Fi or Ethernet and /app/whoami said "ours". [held]: the VPN was started by hand in
     * this very network. [vpnRunning]: the core is up now.
     */
    fun decide(ourNet: Boolean, setting: Setting, vpnRunning: Boolean, paused: Boolean, held: Boolean): Action = when {
        paused && vpnRunning -> Action.HOLD
        paused -> if (ourNet && setting == Setting.ON) Action.STAY else Action.RESUME
        !ourNet -> Action.NOTHING
        setting == Setting.ASK -> Action.OFFER
        setting == Setting.OFF -> Action.NOTHING
        held || !vpnRunning -> Action.NOTHING
        else -> Action.PAUSE
    }

    /** Wi-Fi and Ethernet are asked; mobile data and anything else never. */
    fun isHomeKind(wifi: Boolean, ethernet: Boolean): Boolean = wifi || ethernet

    // ---- storage --------------------------------------------------------------------------

    fun setting(): Setting = when (MmkvManager.decodeSettingsString(PREF_SETTING)) {
        "on" -> Setting.ON
        "off" -> Setting.OFF
        else -> Setting.ASK
    }

    fun setSetting(on: Boolean) {
        MmkvManager.encodeSettings(PREF_SETTING, if (on) "on" else "off")
        MmkvManager.encodeSettings(PREF_OFFER, false)
    }

    fun paused(): Paused? =
        MmkvManager.decodeSettingsString(PREF_PAUSED)?.takeIf { it.isNotBlank() }?.let { JsonUtil.fromJsonSafe(it, Paused::class.java) }

    private fun setPaused(p: Paused?) = MmkvManager.encodeSettings(PREF_PAUSED, p?.let { JsonUtil.toJson(it) } ?: "")

    private fun hold(): Long = MmkvManager.decodeSettingsLong(PREF_HOLD, 0L)

    /** The offer waits for the screen (the check may run in the daemon or a background job). */
    fun offerPending(): Boolean = MmkvManager.decodeSettingsBool(PREF_OFFER, false) && setting() == Setting.ASK

    /** The offer window was closed without an answer: it comes again on the next router network. */
    fun offerClosed() = MmkvManager.encodeSettings(PREF_OFFER, false)

    // ---- Android ----------------------------------------------------------------------------

    private fun homeNetwork(context: Context): Network? {
        val n = AmeliaNetwork.physicalNetwork(context) ?: return null
        val caps = context.getSystemService(ConnectivityManager::class.java)?.getNetworkCapabilities(n) ?: return null
        return n.takeIf {
            isHomeKind(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI), caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }
    }

    private fun currentHandle(context: Context): Long = AmeliaNetwork.physicalNetwork(context)?.networkHandle ?: 0L

    /**
     * Blocking. The check itself, from any process: the screen (app start), the daemon (VPN start,
     * network change), the network watch while paused. Mobile data outside a pause: no request.
     */
    fun check(context: Context, vpnRunning: Boolean): Action {
        val home = homeNetwork(context)
        val pausedNow = paused()
        if (home == null && pausedNow == null) return Action.NOTHING
        val ours = home != null &&
            AmeliaWhoami.direct(context, AmeliaWhoami.ROUTER_BASES, AmeliaWhoami.ROUTER_TIMEOUT_MS)?.ours == true
        val net = home?.networkHandle ?: currentHandle(context)
        val action = decide(ours, setting(), vpnRunning, pausedNow != null, held = net != 0L && hold() == net)
        if (action != Action.NOTHING) LogUtil.i(AppConfig.TAG, "Amelia router: $action (ours=$ours, running=$vpnRunning)")
        apply(context, action, net)
        return action
    }

    private fun apply(context: Context, action: Action, net: Long) {
        when (action) {
            Action.NOTHING -> Unit
            Action.OFFER -> MmkvManager.encodeSettings(PREF_OFFER, true)
            Action.PAUSE -> {
                setPaused(Paused(net, System.currentTimeMillis()))
                AmeliaJournal.add(context.getString(R.string.amelia_router_journal_pause))
                LauncherManager.stopService(context)
                watch(context)
            }
            // the watch stays as it is: registering again would deliver the networks that are up once
            // more, and every one of them would be checked again
            Action.STAY -> setPaused(Paused(net, paused()?.at ?: System.currentTimeMillis()))
            Action.HOLD -> {
                setPaused(null)
                unwatch(context)
                MmkvManager.encodeSettings(PREF_HOLD, net)
            }
            Action.RESUME -> resume(context)
        }
    }

    /** The pause ends: the VPN starts again on the last location (the best one if it is gone). */
    private fun resume(context: Context) {
        setPaused(null)
        unwatch(context)
        AmeliaJournal.add(context.getString(R.string.amelia_router_journal_resume))
        if (AmeliaBoot.ensureLocation(context) == null) return
        // Android 12+ may refuse a start from the background: then a quiet notice opens the app
        if (!LauncherManager.startServiceFromToggle(context)) {
            LogUtil.w(AppConfig.TAG, "Amelia router: VPN start after the pause was refused")
            AmeliaDaemon.notifyQuiet(context, NOTIFICATION_ID, context.getString(R.string.amelia_router_resume_notice))
        }
    }

    /**
     * The person starts the VPN (button, tile, widget): a pause ends without a restart of its own, and
     * this network is not paused again until the network changes. Blocking, short.
     */
    fun onManualStart(context: Context) {
        if (paused() != null) {
            setPaused(null)
            unwatch(context)
        }
        MmkvManager.encodeSettings(PREF_HOLD, currentHandle(context))
    }

    /** A new boot: handles of networks and the watch of the last boot mean nothing any more. */
    fun forgetAtBoot() {
        setPaused(null)
        MmkvManager.encodeSettings(PREF_HOLD, 0L)
    }

    // ---- watching the network while paused ---------------------------------------------------

    /**
     * While paused nothing of the app runs. A new network (another Wi-Fi, mobile data coming up) is
     * delivered to [NetReceiver] by ConnectivityManager even to a stopped app; the move from Wi-Fi to
     * mobile data that was already up in the background comes as the job "a metered network is the
     * default one" ([Worker]).
     */
    private fun watch(context: Context) {
        try {
            val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
            context.getSystemService(ConnectivityManager::class.java)?.registerNetworkCallback(request, netIntent(context))
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "Amelia router: cannot watch the network", e)
        }
        scheduleJob(context, delayMin = 0L, ExistingWorkPolicy.REPLACE)
    }

    /** [policy]: REPLACE when the pause begins; APPEND_OR_REPLACE from the job itself (after it ends). */
    private fun scheduleJob(context: Context, delayMin: Long, policy: ExistingWorkPolicy) {
        try {
            val work = OneTimeWorkRequestBuilder<Worker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.METERED).build())
                .setInitialDelay(delayMin, TimeUnit.MINUTES)
                .build()
            RemoteWorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, work)
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "Amelia router: cannot schedule the network job", e)
        }
    }

    private fun unwatch(context: Context) {
        runCatching { context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(netIntent(context)) }
        runCatching { RemoteWorkManager.getInstance(context).cancelUniqueWork(WORK_NAME) }
    }

    /**
     * ConnectivityManager adds the network to this intent, so it must be mutable (Android 12+ asks
     * for the flag; below it every PendingIntent is mutable). The intent is explicit: our receiver only.
     */
    private fun netIntent(context: Context): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(context, 0, Intent(context, NetReceiver::class.java), flags)
    }

    /** A network became available while paused (see [watch]). */
    class NetReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val p = paused() ?: return
            val network = IntentCompat.getParcelableExtra(intent, ConnectivityManager.EXTRA_NETWORK, Network::class.java)
            // the paused network itself (sent right after the watch began): nothing changed
            if (network != null && network.networkHandle == p.net) return
            val app = context.applicationContext
            val pending = goAsync()
            thread(name = "amelia-router") {
                try {
                    check(app, vpnRunning = AmeliaHttp.tunnelAvailable())
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Amelia router: network check failed", e)
                } finally {
                    pending.finish()
                }
            }
        }
    }

    /** Mobile data became the default network while paused (see [watch]). */
    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            if (paused() == null) return Result.success()
            check(applicationContext, vpnRunning = AmeliaHttp.tunnelAvailable())
            // still paused (a metered Wi-Fi behind the router lets this job run at once): again later
            if (paused() != null) scheduleJob(applicationContext, STAY_RECHECK_MIN, ExistingWorkPolicy.APPEND_OR_REPLACE)
            return Result.success()
        }
    }
}
