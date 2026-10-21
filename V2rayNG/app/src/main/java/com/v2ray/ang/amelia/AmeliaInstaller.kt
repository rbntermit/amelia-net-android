package com.v2ray.ang.amelia

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Installs a downloaded update with the PackageInstaller session API.
 *
 * Android 12+ (API 31): with UPDATE_PACKAGES_WITHOUT_USER_ACTION and
 * setRequireUserAction(USER_ACTION_NOT_REQUIRED) an app may update itself without the
 * system confirmation screen. Older Android versions, and cases the system decides to
 * confirm anyway, get STATUS_PENDING_USER_ACTION and the usual confirmation screen.
 * The one-time "allow installing from this source" setting is still required.
 */
object AmeliaInstaller {

    private const val ACTION_STATUS = "com.ameliajustamelia.vpn.INSTALL_STATUS"
    /** Set before the update replaces the app; read by [PackageReplacedReceiver]. */
    private const val PREF_RESTART_VPN = "amelia_restart_vpn_after_update"

    /** The APK and version being installed: the status receiver falls back to them (1.4.0). */
    private const val PREF_APK = "amelia_install_apk"
    private const val PREF_CODE = "amelia_install_code"
    /** versionCode whose install the phone blocked: the app shows the help screen. */
    const val PREF_BLOCKED = "amelia_install_blocked"
    private const val PREF_BLOCKED_REPORTED = "amelia_install_blocked_reported"

    /** 1.5.1: when the last install was started and from which installed versionCode. */
    private const val PREF_STARTED_AT = "amelia_install_started_at"
    private const val PREF_FROM_CODE = "amelia_install_from_code"

    /**
     * A committed session counts as running this long even where Android cannot tell
     * (SessionInfo.isCommitted is API 29+); the result normally comes within seconds.
     */
    const val RUNNING_MS = 3 * 60 * 1000L

    fun blockedCode(): Int = MmkvManager.decodeSettingsString(PREF_BLOCKED)?.toIntOrNull() ?: 0

    fun clearBlocked() = MmkvManager.encodeSettings(PREF_BLOCKED, "")

    // ---- pure logic (unit-tested) ---------------------------------------------------------

    /** One of this app's PackageInstaller sessions, as far as the decision needs it. */
    data class SessionView(val forUs: Boolean, val active: Boolean, val committed: Boolean)

    /**
     * 1.5.1: one install at a time. Busy when a session for our package is being written or
     * waits for its result, or an install started less than [RUNNING_MS] ago.
     */
    fun busy(sessions: List<SessionView>, startedAt: Long, now: Long): Boolean =
        sessions.any { it.forUs && (it.active || it.committed) } || (startedAt > 0 && now - startedAt in 0 until RUNNING_MS)

    enum class Failure {
        /** A second session while the first one is still pending: not a failure, wait for the first. */
        DUPLICATE,
        /** The phone or the person refused: help screen, system installer, "update_blocked". */
        REFUSED,
        /** Anything else (storage, broken file …): the system installer, no help screen. */
        OTHER,
    }

    fun classify(status: Int, message: String?): Failure {
        val m = message.orEmpty()
        return when {
            m.contains("INSTALL_FAILED_DUPLICATE_PACKAGE") || m.contains("pending install", ignoreCase = true) -> Failure.DUPLICATE
            status == PackageInstaller.STATUS_FAILURE_BLOCKED || status == PackageInstaller.STATUS_FAILURE_ABORTED ||
                m.contains("INSTALL_FAILED_VERIFICATION_FAILURE") -> Failure.REFUSED
            else -> Failure.OTHER
        }
    }

    /** What a failed session leads to. */
    data class Reaction(val helpScreen: Boolean, val report: String?, val systemInstaller: Boolean)

    fun reaction(failure: Failure): Reaction = when (failure) {
        Failure.DUPLICATE -> Reaction(helpScreen = false, report = null, systemInstaller = false)
        Failure.REFUSED -> Reaction(helpScreen = true, report = "update_blocked", systemInstaller = true)
        Failure.OTHER -> Reaction(helpScreen = false, report = "update", systemInstaller = true)
    }

    /** "Телефон не дал установить обновление": only for the version on offer, newer than the installed one, and not while installing. */
    fun showBlocked(blockedCode: Int, installedCode: Int, offeredCode: Int?, busy: Boolean): Boolean =
        !busy && blockedCode > installedCode && blockedCode == offeredCode

    /** The app is newer than when the last install started: that install went through. */
    fun updatedSince(currentCode: Int, fromCode: Int): Boolean = fromCode > 0 && currentCode > fromCode

    // ---- state ----------------------------------------------------------------------------

    private val starting = AtomicBoolean(false)

    private fun sessions(installer: PackageInstaller, pkg: String): List<SessionView> =
        installer.mySessions.map {
            SessionView(
                forUs = it.appPackageName == null || it.appPackageName == pkg,
                active = it.isActive,
                committed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && it.isCommitted,
            )
        }

    /** An install is running (the update button shows "Устанавливается…"). Any thread. */
    fun isBusy(context: Context): Boolean = starting.get() || runCatching {
        busy(
            sessions(context.packageManager.packageInstaller, context.packageName),
            MmkvManager.decodeSettingsString(PREF_STARTED_AT)?.toLongOrNull() ?: 0L,
            System.currentTimeMillis(),
        )
    }.getOrDefault(false)

    private fun finished() = MmkvManager.encodeSettings(PREF_STARTED_AT, "")

    /**
     * App start: if the version rose since the last install began, that install worked —
     * no "не установлено" state, no update notification, installer state cleared.
     */
    fun resetIfUpdated(context: Context, currentCode: Int) {
        val from = MmkvManager.decodeSettingsString(PREF_FROM_CODE)?.toIntOrNull() ?: 0
        if (!updatedSince(currentCode, from)) return
        clearBlocked()
        finished()
        MmkvManager.encodeSettings(PREF_FROM_CODE, "")
        MmkvManager.encodeSettings(PREF_APK, "")
        MmkvManager.encodeSettings(PREF_CODE, "")
        AmeliaUpdateWorker.cancelNotification(context)
    }

    enum class Start { STARTED, ALREADY_RUNNING }

    /**
     * Blocking (copies the APK into the session). [Start.ALREADY_RUNNING] when an install is
     * going on already: nothing is created. Throws on failure; the caller may fall back to
     * ACTION_VIEW unless [isBusy].
     */
    fun install(context: Context, apk: File, vpnRunning: Boolean, versionCode: Int = 0): Start {
        if (!starting.compareAndSet(false, true)) return Start.ALREADY_RUNNING
        try {
            return startSession(context, apk, vpnRunning, versionCode)
        } finally {
            starting.set(false)
        }
    }

    private fun startSession(context: Context, apk: File, vpnRunning: Boolean, versionCode: Int): Start {
        val installer = context.packageManager.packageInstaller
        val now = System.currentTimeMillis()
        val mine = installer.mySessions
        val startedAt = MmkvManager.decodeSettingsString(PREF_STARTED_AT)?.toLongOrNull() ?: 0L
        if (busy(sessions(installer, context.packageName), startedAt, now)) {
            LogUtil.i(AppConfig.TAG, "Amelia: an update install is running already, not starting another")
            return Start.ALREADY_RUNNING
        }
        // left over from a process killed while writing: never committed, would only get in the way
        mine.filter { !it.isActive && !(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && it.isCommitted) }
            .forEach { runCatching { installer.abandonSession(it.sessionId) } }
        MmkvManager.encodeSettings(PREF_APK, apk.absolutePath)
        MmkvManager.encodeSettings(PREF_CODE, versionCode.toString())
        MmkvManager.encodeSettings(PREF_FROM_CODE, BuildConfig.VERSION_CODE.toString())
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                // The update kills this process and stops the VPN: bring it back afterwards
                MmkvManager.encodeSettings(PREF_RESTART_VPN, vpnRunning)
                val status = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    Intent(context, StatusReceiver::class.java).setAction(ACTION_STATUS),
                    // MUTABLE: the system adds the status extras to this intent
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(status.intentSender)
                MmkvManager.encodeSettings(PREF_STARTED_AT, System.currentTimeMillis().toString())
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            MmkvManager.encodeSettings(PREF_RESTART_VPN, false)
            throw e
        }
        return Start.STARTED
    }

    /** Session status: asks for confirmation when the system wants it, falls back on failure. */
    class StatusReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = intent.serializableIntent()
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { context.startActivity(confirm) }
                            .onFailure { LogUtil.e(AppConfig.TAG, "Amelia: cannot show install confirmation", it) }
                    }
                }

                PackageInstaller.STATUS_SUCCESS -> {
                    finished()
                    LogUtil.i(AppConfig.TAG, "Amelia: update installed")
                }

                else -> {
                    val text = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                    val message = "install failed, status=$status $text"
                    val failure = classify(status, text)
                    if (failure == Failure.DUPLICATE) {
                        // 1.5.1: a second session while the first is pending; the first one decides
                        LogUtil.i(AppConfig.TAG, "Amelia: duplicate install session ignored ($message)")
                        return
                    }
                    finished()
                    MmkvManager.encodeSettings(PREF_RESTART_VPN, false)
                    LogUtil.w(AppConfig.TAG, "Amelia: update $message")
                    onSessionFailed(context, message, failure)
                }
            }
        }

        /**
         * 1.4.0: the session install was refused (Samsung Auto Blocker, Play Protect: BLOCKED,
         * INSTALL_FAILED_VERIFICATION_FAILURE, the person said no: ABORTED). Right away the
         * classic system installer screen; the app also shows how to let the install through.
         * Reported to /app/crash once per version as "update_blocked". 1.5.1: other failures
         * only get the system installer screen and an "update" report, no help screen.
         */
        private fun onSessionFailed(context: Context, message: String, failure: Failure) {
            val code = MmkvManager.decodeSettingsString(PREF_CODE)?.toIntOrNull() ?: 0
            val r = reaction(failure)
            if (r.helpScreen) MmkvManager.encodeSettings(PREF_BLOCKED, code.toString())
            when (r.report) {
                null -> Unit
                "update_blocked" -> if (MmkvManager.decodeSettingsString(PREF_BLOCKED_REPORTED) != code.toString()) {
                    MmkvManager.encodeSettings(PREF_BLOCKED_REPORTED, code.toString())
                    AmeliaCrash.nonFatal(r.report, null, message)
                }
                else -> AmeliaCrash.nonFatal(r.report, null, message)
            }
            if (!r.systemInstaller) return
            val apk = MmkvManager.decodeSettingsString(PREF_APK)?.let { File(it) }?.takeIf { it.isFile } ?: return
            runCatching { context.startActivity(AmeliaUpdater.installIntent(context, apk)) }
                .onFailure { LogUtil.e(AppConfig.TAG, "Amelia: cannot open the system installer", it) }
        }

        private fun Intent.serializableIntent(): Intent? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION") getParcelableExtra(Intent.EXTRA_INTENT)
            }
    }

    /**
     * After the app replaced itself: restart the VPN if it was on before the update.
     * MY_PACKAGE_REPLACED is exempt from the background foreground-service start limits.
     */
    class PackageReplacedReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
            // 1.3.0: the downloaded APK is installed now, free the space
            AmeliaUpdater.deleteDownloads(context)
            clearBlocked()
            finished()
            if (!MmkvManager.decodeSettingsBool(PREF_RESTART_VPN, false)) return
            MmkvManager.encodeSettings(PREF_RESTART_VPN, false)
            if (MmkvManager.getSelectServer().isNullOrEmpty()) return
            LogUtil.i(AppConfig.TAG, "Amelia: restarting VPN after update")
            LauncherManager.startService(context)
        }
    }
}
