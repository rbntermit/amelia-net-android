package com.v2ray.ang.amelia

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Installs a downloaded update with the PackageInstaller session API.
 *
 * Android 12+ (API 31): with UPDATE_PACKAGES_WITHOUT_USER_ACTION and
 * setRequireUserAction(USER_ACTION_NOT_REQUIRED) an app may update itself without the
 * system confirmation screen. Older Android versions, and cases the system decides to
 * confirm anyway (e.g. the app was first installed from Telegram or a file manager), get
 * STATUS_PENDING_USER_ACTION and the usual confirmation screen.
 * The one-time "allow installing from this source" setting is still required.
 *
 * 1.5.3: only the session this process leads counts as an install in progress. Sessions it
 * does not lead (the process died, the confirmation intent is lost) are abandoned; an install
 * without a final status for [STUCK_MS] is abandoned and reported as "update_stuck".
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

    /** 1.5.1: installed versionCode when the last install began (reset after the update). */
    private const val PREF_FROM_CODE = "amelia_install_from_code"
    /** 1.5.1 marker, no longer used for decisions; cleared so it never lingers. */
    private const val PREF_STARTED_AT = "amelia_install_started_at"
    /** 1.5.3: sessions this app abandoned; their late statuses are ignored. */
    private const val PREF_ABANDONED = "amelia_install_abandoned"
    /** 1.5.5: when the person last cancelled the system install window / when update_cancel went out. */
    private const val PREF_CANCELLED_AT = "amelia_install_cancelled_at"
    private const val PREF_CANCEL_REPORTED_AT = "amelia_install_cancel_reported_at"
    /** 1.7.1: [PlayBlock] as JSON. */
    private const val PREF_PLAY = "amelia_install_play_protect"

    /** "Устанавливается…" longer than this without a final status: give up (1.5.3). */
    const val STUCK_MS = 3 * 60 * 1000L

    fun blockedCode(): Int = MmkvManager.decodeSettingsString(PREF_BLOCKED)?.toIntOrNull() ?: 0

    fun clearBlocked() = MmkvManager.encodeSettings(PREF_BLOCKED, "")

    /** 1.7.1: the update went through: no Play Protect screen and no "play_protect" in reports any more. */
    fun clearPlayBlock() = MmkvManager.encodeSettings(PREF_PLAY, "")

    // ---- pure logic (unit-tested) ---------------------------------------------------------

    /** One of this app's PackageInstaller sessions, as far as the decisions need it. */
    data class SessionView(val id: Int, val forUs: Boolean, val active: Boolean, val committed: Boolean)

    /** The session this process leads: committed by it, its status receiver alive in it. */
    data class Owned(val id: Int, val startedAt: Long, val awaitingConfirm: Boolean)

    enum class Phase {
        /** No install: the button says "Установить". */
        IDLE,
        /** Our session is being written or the system installs it: "Устанавливается…", disabled. */
        INSTALLING,
        /** The system wants the person to confirm: "Установить" opens the confirmation again. */
        AWAITING_CONFIRM,
        /** INSTALLING for [STUCK_MS] without a final status: abandon and report. */
        STUCK,
    }

    fun phase(owned: Owned?, sessions: List<SessionView>, now: Long): Phase = when {
        owned == null -> Phase.IDLE
        // finished or dropped by the system: nothing runs any more
        sessions.none { it.id == owned.id } -> Phase.IDLE
        owned.awaitingConfirm -> Phase.AWAITING_CONFIRM
        now - owned.startedAt >= STUCK_MS -> Phase.STUCK
        else -> Phase.INSTALLING
    }

    /**
     * Our sessions this process does not lead: left by a dead process or with the confirmation
     * intent lost. Nobody can finish them, so they are abandoned and a new install is allowed.
     */
    fun orphans(sessions: List<SessionView>, ownedId: Int?): List<Int> =
        sessions.filter { it.forUs && it.id != ownedId }.map { it.id }

    enum class StartDecision { NEW_SESSION, ALREADY_RUNNING, REOPEN_CONFIRM }

    /** A tap on "Установить" (or the notification, the dialog …) given what runs now. */
    fun decideStart(phase: Phase, hasConfirmScreen: Boolean): StartDecision = when (phase) {
        Phase.INSTALLING -> StartDecision.ALREADY_RUNNING
        Phase.AWAITING_CONFIRM -> if (hasConfirmScreen) StartDecision.REOPEN_CONFIRM else StartDecision.NEW_SESSION
        Phase.IDLE, Phase.STUCK -> StartDecision.NEW_SESSION
    }

    /** What a check ends with: the button's phase, whether to abandon our session and send "update_stuck". */
    data class Settled(val phase: Phase, val abandonOwned: Boolean, val reportStuck: Boolean)

    fun settle(phase: Phase): Settled = when (phase) {
        Phase.STUCK -> Settled(Phase.IDLE, abandonOwned = true, reportStuck = true)
        else -> Settled(phase, abandonOwned = false, reportStuck = false)
    }

    enum class ConfirmRoute { ACTIVITY, NOTIFICATION }

    /** Android 10+ does not let a background app open screens: then a notification opens it. */
    fun confirmRoute(appVisible: Boolean): ConfirmRoute = if (appVisible) ConfirmRoute.ACTIVITY else ConfirmRoute.NOTIFICATION

    enum class Failure {
        /** "Duplicate package … in pending install requests": not a failure, no screen, no report. */
        DUPLICATE,
        /** The phone refused, or installing apps is not allowed for us: help screen, system installer, "update_blocked". */
        REFUSED,
        /** 1.5.5: the person tapped "Отмена" in Android's "Обновить приложение?": not a refusal. */
        CANCELLED,
        /**
         * 1.7.1: Google Play Protect stopped the install ("Не устанавливать", or its check could
         * not reach Google, e.g. on mobile data with white lists). Not an app error: own screen,
         * a journal line and "play_protect" in /app/report, never /app/crash.
         */
        PLAY_PROTECT,
        /** Anything else (storage, broken file …): the system installer, no help screen. */
        OTHER,
    }

    private const val VERIFICATION_FAILURE = "INSTALL_FAILED_VERIFICATION_FAILURE"

    /** Samsung Auto Blocker sends the same code as Play Protect, with its own name in the text. */
    private fun isAutoBlocker(message: String): Boolean =
        message.contains("auto blocker", ignoreCase = true) || message.contains("autoblocker", ignoreCase = true) ||
            message.contains("auto_blocker", ignoreCase = true)

    /**
     * Final failure statuses only. STATUS_PENDING_USER_ACTION never comes here: it is not a
     * failure, it shows the confirmation (1.5.3).
     */
    fun classify(status: Int, message: String?, canInstallApps: Boolean = true): Failure {
        val m = message.orEmpty()
        return when {
            m.contains("INSTALL_FAILED_DUPLICATE_PACKAGE") || m.contains("in pending install requests", ignoreCase = true) -> Failure.DUPLICATE
            // 1.7.1: "INSTALL_FAILED_VERIFICATION_FAILURE: Install not allowed" (status 3 = ABORTED) is Play Protect
            m.contains(VERIFICATION_FAILURE) && !isAutoBlocker(m) -> Failure.PLAY_PROTECT
            status == PackageInstaller.STATUS_FAILURE_BLOCKED || m.contains(VERIFICATION_FAILURE) -> Failure.REFUSED
            // 1.5.5: "Отмена" in "Обновить приложение?" and "Установка неизвестных приложений"
            // denied both come as ABORTED "User rejected permissions"; the permission tells them apart
            status == PackageInstaller.STATUS_FAILURE_ABORTED -> if (canInstallApps) Failure.CANCELLED else Failure.REFUSED
            else -> Failure.OTHER
        }
    }

    /** 1.5.5: "update_cancel" (statistics only) at most once a day per device. */
    const val CANCEL_REPORT_MS = 24 * 60 * 60 * 1000L

    fun shouldReportCancel(lastReportedAt: Long, now: Long): Boolean =
        lastReportedAt <= 0 || now - lastReportedAt !in 0 until CANCEL_REPORT_MS

    /**
     * 1.5.5: after a cancel the update window does not pop up by itself for a day (the card
     * with "Установить" stays); a required update is always offered.
     */
    fun offerUpdateDialog(cancelledAt: Long, now: Long, required: Boolean, playOfferedAt: Long = 0L): Boolean =
        required || listOf(cancelledAt, playOfferedAt).all { it <= 0 || now - it !in 0 until CANCEL_REPORT_MS }

    fun cancelledAt(): Long = MmkvManager.decodeSettingsString(PREF_CANCELLED_AT)?.toLongOrNull() ?: 0L

    /** What a failed session leads to. */
    data class Reaction(
        val helpScreen: Boolean,
        val report: String?,
        val systemInstaller: Boolean,
        /** 1.7.1: "Google Play Защита не дала установить обновление" and a journal line. */
        val playProtectScreen: Boolean = false,
    )

    fun reaction(failure: Failure): Reaction = when (failure) {
        Failure.DUPLICATE -> Reaction(helpScreen = false, report = null, systemInstaller = false)
        Failure.REFUSED -> Reaction(helpScreen = true, report = "update_blocked", systemInstaller = true)
        // the card comes back with "Установить"; nothing opens by itself
        Failure.CANCELLED -> Reaction(helpScreen = false, report = "update_cancel", systemInstaller = false)
        // 1.7.1: no crash report; the system installer would only ask Play Protect again
        Failure.PLAY_PROTECT -> Reaction(helpScreen = false, report = null, systemInstaller = false, playProtectScreen = true)
        Failure.OTHER -> Reaction(helpScreen = false, report = "update", systemInstaller = true)
    }

    // ---- 1.7.1: Play Protect blocked the update ---------------------------------------------

    /** The update screen offers itself again after a Play Protect block at most this often. */
    const val PLAY_OFFER_MS = 24 * 60 * 60 * 1000L
    private const val MAX_PLAY_MESSAGE = 160

    /** The last Play Protect block, kept until the update goes through. */
    data class PlayBlock(
        /** versionCode whose install was stopped. */
        val code: Int = 0,
        /** When it last happened, ms. */
        val at: Long = 0L,
        /** How many times this version was stopped. */
        val count: Int = 0,
        val status: Int = 0,
        /** Android's text, e.g. "INSTALL_FAILED_VERIFICATION_FAILURE: Install not allowed". */
        val message: String = "",
        /** When the screen was last offered (0: not yet). */
        val offeredAt: Long = 0L,
        /** A fresh block: the screen opens at once, not a day later. */
        val pending: Boolean = false,
    )

    fun recordPlayBlock(prev: PlayBlock?, code: Int, status: Int, message: String, now: Long): PlayBlock = PlayBlock(
        code = code,
        at = now,
        count = if (prev != null && prev.code == code) prev.count + 1 else 1,
        status = status,
        message = message.take(MAX_PLAY_MESSAGE),
        offeredAt = prev?.takeIf { it.code == code }?.offeredAt ?: 0L,
        pending = true,
    )

    /** The block is about the version on offer, newer than the installed one. */
    fun playBlockApplies(b: PlayBlock?, installedCode: Int, offeredCode: Int?): Boolean =
        b != null && b.code > installedCode && b.code == offeredCode

    /**
     * The Play Protect screen: right after a block, after a manual "Проверить обновления", and
     * otherwise at most once a day; never while an install runs.
     */
    fun offerPlayProtect(b: PlayBlock?, installedCode: Int, offeredCode: Int?, busy: Boolean, now: Long, manual: Boolean): Boolean =
        !busy && playBlockApplies(b, installedCode, offeredCode) &&
            (b!!.pending || manual || b.offeredAt <= 0 || now - b.offeredAt !in 0 until PLAY_OFFER_MS)

    fun markPlayOffered(b: PlayBlock, now: Long): PlayBlock = b.copy(pending = false, offeredAt = now)

    fun playBlock(): PlayBlock? =
        MmkvManager.decodeSettingsString(PREF_PLAY)?.takeIf { it.isNotBlank() }?.let { JsonUtil.fromJsonSafe(it, PlayBlock::class.java) }

    private fun savePlayBlock(b: PlayBlock?) = MmkvManager.encodeSettings(PREF_PLAY, b?.let { JsonUtil.toJson(it) } ?: "")

    /** The screen is up now: the next offer waits a day. */
    fun playOffered(now: Long = System.currentTimeMillis()) {
        playBlock()?.let { savePlayBlock(markPlayOffered(it, now)) }
    }

    /** A status receiver got a failure (any process-wide listener refreshes its screen). */
    private val _failures = MutableStateFlow(0L)
    val failures: StateFlow<Long> = _failures

    /** "Телефон не дал установить обновление": only for the version on offer, newer than the installed one, and not while installing. */
    fun showBlocked(blockedCode: Int, installedCode: Int, offeredCode: Int?, busy: Boolean): Boolean =
        !busy && blockedCode > installedCode && blockedCode == offeredCode

    /** The app is newer than when the last install started: that install went through. */
    fun updatedSince(currentCode: Int, fromCode: Int): Boolean = fromCode > 0 && currentCode > fromCode

    /** The "update_stuck" report text: statuses that came, sessions as Android sees them, device. */
    fun stuckReport(statuses: List<String>, sessions: List<SessionView>, android: String, sdk: Int, maker: String): String =
        "install without a final status for ${STUCK_MS / 1000} s; statuses=[${statuses.joinToString()}]; " +
            "sessions=${sessions.size} [${sessions.joinToString { "${it.id}:${if (it.active) "active" else "idle"}/${if (it.committed) "committed" else "open"}${if (it.forUs) "" else "/other"}" }}]; " +
            "android=$android (sdk $sdk); maker=$maker"

    // ---- state of this process --------------------------------------------------------------

    private val starting = AtomicBoolean(false)

    /** Set by AmeliaActivity (onStart/onStop): a screen of ours is visible. */
    @Volatile
    var uiVisible = false

    @Volatile
    private var owned: Owned? = null
    /** The confirmation screen of [owned] (STATUS_PENDING_USER_ACTION). */
    @Volatile
    private var confirmIntent: Intent? = null
    /** Statuses seen for [owned], for the "update_stuck" report. */
    private val statuses = java.util.Collections.synchronizedList(mutableListOf<String>())

    private fun sessions(installer: PackageInstaller, pkg: String): List<SessionView> =
        installer.mySessions.map {
            SessionView(
                id = it.sessionId,
                forUs = it.appPackageName == null || it.appPackageName == pkg,
                active = it.isActive,
                committed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && it.isCommitted,
            )
        }

    private fun abandoned(): Set<Int> =
        MmkvManager.decodeSettingsString(PREF_ABANDONED)?.split(',')?.mapNotNull { it.toIntOrNull() }?.toSet().orEmpty()

    private fun abandon(installer: PackageInstaller, id: Int) {
        // remembered first: abandoning sends STATUS_FAILURE_ABORTED, which is not a refusal
        val list = (abandoned() + id).toList().takeLast(20)
        MmkvManager.encodeSettings(PREF_ABANDONED, list.joinToString(","))
        runCatching { installer.abandonSession(id) }
            .onFailure { LogUtil.w(AppConfig.TAG, "Amelia: cannot abandon install session $id", it) }
    }

    private fun clearOwned(context: Context) {
        owned = null
        confirmIntent = null
        statuses.clear()
        MmkvManager.encodeSettings(PREF_STARTED_AT, "")
        NotificationManagerCompat.from(context).cancel(AmeliaUpdateWorker.UPDATE_NOTIFICATION_ID)
    }

    /**
     * App start, screen open and every few seconds while installing: abandons sessions this
     * process does not lead, gives up a stuck install (report "update_stuck"), and says what
     * the update button shows. Blocking (binder calls), any thread of the app process.
     */
    fun check(context: Context, now: Long = System.currentTimeMillis()): Phase =
        if (starting.get()) Phase.INSTALLING else checkNow(context, now)

    @Synchronized
    private fun checkNow(context: Context, now: Long = System.currentTimeMillis()): Phase {
        val installer = context.packageManager.packageInstaller
        val list = runCatching { sessions(installer, context.packageName) }.getOrElse { return Phase.IDLE }
        val own = owned
        orphans(list, own?.id).forEach {
            LogUtil.i(AppConfig.TAG, "Amelia: abandoning install session $it nobody leads")
            abandon(installer, it)
        }
        val s = settle(phase(own, list, now))
        if (s.abandonOwned) {
            val report = stuckReport(statuses.toList(), list, Build.VERSION.RELEASE.orEmpty(), Build.VERSION.SDK_INT, Build.MANUFACTURER.orEmpty())
            LogUtil.w(AppConfig.TAG, "Amelia: update $report")
            own?.let { abandon(installer, it.id) }
            MmkvManager.encodeSettings(PREF_RESTART_VPN, false)
            if (s.reportStuck) AmeliaCrash.nonFatal("update_stuck", null, report)
        }
        if (s.phase == Phase.IDLE && own != null) clearOwned(context)
        return s.phase
    }

    /** A live session of ours runs (for "no fallback while installing"). */
    fun isBusy(context: Context): Boolean = check(context) != Phase.IDLE

    /**
     * App start: if the version rose since the last install began, that install worked —
     * no "не установлено" state, no update notification, installer state cleared.
     */
    fun resetIfUpdated(context: Context, currentCode: Int) {
        val from = MmkvManager.decodeSettingsString(PREF_FROM_CODE)?.toIntOrNull() ?: 0
        if (!updatedSince(currentCode, from)) return
        clearBlocked()
        clearPlayBlock()
        clearOwned(context)
        MmkvManager.encodeSettings(PREF_FROM_CODE, "")
        MmkvManager.encodeSettings(PREF_APK, "")
        MmkvManager.encodeSettings(PREF_CODE, "")
        AmeliaUpdateWorker.cancelNotification(context)
    }

    sealed interface Start {
        data object Started : Start
        data object AlreadyRunning : Start
        /** The system waits for the person: open this screen (from the activity). */
        data class NeedsConfirm(val intent: Intent) : Start
    }

    /**
     * Blocking (copies the APK into the session). One install at a time: a second call while
     * ours runs creates nothing; while the system waits for confirmation it returns the
     * confirmation screen to open again. Throws on failure; the caller may fall back to
     * ACTION_VIEW unless [isBusy].
     */
    fun install(context: Context, apk: File, vpnRunning: Boolean, versionCode: Int = 0): Start {
        if (!starting.compareAndSet(false, true)) return Start.AlreadyRunning
        try {
            val confirm = confirmIntent
            return when (decideStart(checkNow(context), confirm != null)) {
                StartDecision.ALREADY_RUNNING -> Start.AlreadyRunning
                StartDecision.REOPEN_CONFIRM -> Start.NeedsConfirm(confirm!!)
                StartDecision.NEW_SESSION -> startSession(context, apk, vpnRunning, versionCode)
            }
        } finally {
            starting.set(false)
        }
    }

    private fun startSession(context: Context, apk: File, vpnRunning: Boolean, versionCode: Int): Start {
        val installer = context.packageManager.packageInstaller
        // an AWAITING_CONFIRM session without its screen: start over
        owned?.let { abandon(installer, it.id) }
        clearOwned(context)
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
        // led by this process from now on: the orphan sweep must not touch it
        owned = Owned(sessionId, System.currentTimeMillis(), awaitingConfirm = false)
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
                owned = Owned(sessionId, System.currentTimeMillis(), awaitingConfirm = false)
                session.commit(status.intentSender)
            }
        } catch (e: Exception) {
            abandon(installer, sessionId)
            clearOwned(context)
            MmkvManager.encodeSettings(PREF_RESTART_VPN, false)
            throw e
        }
        return Start.Started
    }

    /**
     * STATUS_PENDING_USER_ACTION: the system wants the person to confirm. A visible app opens
     * the screen; in the background (Android 10+ blocks opening screens from there, silently)
     * a notification opens it. Either way "Установить" opens it again later.
     */
    private fun onConfirmNeeded(context: Context, sessionId: Int, confirm: Intent) {
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // a status for a session this process did not start (it died meanwhile): lead it now
        owned = Owned(sessionId, owned?.takeIf { it.id == sessionId }?.startedAt ?: System.currentTimeMillis(), awaitingConfirm = true)
        confirmIntent = confirm
        when (confirmRoute(uiVisible)) {
            ConfirmRoute.ACTIVITY -> runCatching { context.startActivity(confirm) }
                .onFailure {
                    LogUtil.w(AppConfig.TAG, "Amelia: cannot open the install confirmation, notifying", it)
                    notifyConfirm(context, confirm)
                }
            ConfirmRoute.NOTIFICATION -> notifyConfirm(context, confirm)
        }
    }

    private fun notifyConfirm(context: Context, confirm: Intent) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val channel = AmeliaUpdateWorker.ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 3, confirm,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(context.getString(R.string.amelia_update_confirm_title))
            .setContentText(context.getString(R.string.amelia_update_confirm_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(AmeliaUpdateWorker.UPDATE_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            LogUtil.w(AppConfig.TAG, "Amelia: no permission for the install confirmation notification", e)
        }
    }

    /** Session status: asks for confirmation when the system wants it, falls back on failure. */
    class StatusReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
            val text = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
            if (sessionId in abandoned()) {
                // we abandoned it ourselves (stuck or nobody led it): its late status means nothing
                LogUtil.i(AppConfig.TAG, "Amelia: status $status of abandoned session $sessionId ignored")
                return
            }
            statuses += "$status${if (text.isNotEmpty()) " $text" else ""}".take(160)
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = intent.serializableIntent()
                    if (confirm != null) {
                        AmeliaInstaller.onConfirmNeeded(context, sessionId, confirm)
                    } else {
                        LogUtil.w(AppConfig.TAG, "Amelia: confirmation wanted but no screen given (session $sessionId)")
                    }
                }

                PackageInstaller.STATUS_SUCCESS -> {
                    clearOwned(context)
                    LogUtil.i(AppConfig.TAG, "Amelia: update installed")
                }

                else -> {
                    val message = "install failed, status=$status $text"
                    if (owned?.id == sessionId) clearOwned(context)
                    val failure = classify(status, text, AmeliaUpdater.canInstall(context))
                    if (failure == Failure.DUPLICATE) {
                        // 1.5.1: another install of the package is pending; no screen, no report
                        LogUtil.i(AppConfig.TAG, "Amelia: duplicate install session ignored ($message)")
                        return
                    }
                    MmkvManager.encodeSettings(PREF_RESTART_VPN, false)
                    LogUtil.w(AppConfig.TAG, "Amelia: update $message ($failure)")
                    if (failure == Failure.CANCELLED) {
                        onCancelled(message)
                        return
                    }
                    onSessionFailed(context, message, failure, status, text)
                }
            }
        }

        /**
         * 1.4.0: the session install was refused (Samsung Auto Blocker: BLOCKED,
         * INSTALL_FAILED_VERIFICATION_FAILURE "Auto Blocker", ABORTED without "install unknown apps";
         * 1.7.1: Play Protect has its own screen, see [onPlayProtect]). Right away the
         * classic system installer screen; the app also shows how to let the install through.
         * Reported to /app/crash once per version as "update_blocked". 1.5.1: other failures
         * only get the system installer screen and an "update" report, no help screen.
         */
        /**
         * 1.5.5: "Отмена" in "Обновить приложение?". The card comes back with "Установить" (the
         * session is gone, see check()); no help screen, no system installer, no update_blocked,
         * and the update window does not pop up by itself for a day. A quiet "update_cancel"
         * at most once a day.
         */
        private fun onCancelled(message: String) {
            val now = System.currentTimeMillis()
            MmkvManager.encodeSettings(PREF_CANCELLED_AT, now.toString())
            val last = MmkvManager.decodeSettingsString(PREF_CANCEL_REPORTED_AT)?.toLongOrNull() ?: 0L
            if (!shouldReportCancel(last, now)) return
            MmkvManager.encodeSettings(PREF_CANCEL_REPORTED_AT, now.toString())
            reaction(Failure.CANCELLED).report?.let { AmeliaCrash.nonFatal(it, null, message) }
        }

        /**
         * 1.7.1: Play Protect stopped the update. Remembered for the screen (shown at once, then
         * at most once a day) and for "play_protect" in /app/report; a line in "Журнал".
         */
        private fun onPlayProtect(context: Context, code: Int, status: Int, text: String) {
            val now = System.currentTimeMillis()
            // the version being installed; unknown (0) would never match the offer, so the last answer's
            val version = code.takeIf { it > 0 } ?: AmeliaUpdater.cached()?.versionCode ?: 0
            savePlayBlock(recordPlayBlock(playBlock(), version, status, text, now))
            AmeliaJournal.add(context.getString(R.string.amelia_journal_play_protect), now)
        }

        private fun onSessionFailed(context: Context, message: String, failure: Failure, status: Int, text: String) {
            val code = MmkvManager.decodeSettingsString(PREF_CODE)?.toIntOrNull() ?: 0
            val r = reaction(failure)
            if (r.helpScreen) MmkvManager.encodeSettings(PREF_BLOCKED, code.toString())
            if (r.playProtectScreen) onPlayProtect(context, code, status, text)
            when (r.report) {
                null -> Unit
                "update_blocked" -> if (MmkvManager.decodeSettingsString(PREF_BLOCKED_REPORTED) != code.toString()) {
                    MmkvManager.encodeSettings(PREF_BLOCKED_REPORTED, code.toString())
                    AmeliaCrash.nonFatal(r.report, null, message)
                }
                else -> AmeliaCrash.nonFatal(r.report, null, message)
            }
            // the screen asks again now, not only when the next 3-second check notices
            _failures.value = System.currentTimeMillis()
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
            clearPlayBlock()
            clearOwned(context)
            if (!MmkvManager.decodeSettingsBool(PREF_RESTART_VPN, false)) return
            MmkvManager.encodeSettings(PREF_RESTART_VPN, false)
            if (MmkvManager.getSelectServer().isNullOrEmpty()) return
            LogUtil.i(AppConfig.TAG, "Amelia: restarting VPN after update")
            LauncherManager.startService(context)
        }
    }
}
