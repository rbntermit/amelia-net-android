package com.v2ray.ang.amelia

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import java.io.File

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

    fun blockedCode(): Int = MmkvManager.decodeSettingsString(PREF_BLOCKED)?.toIntOrNull() ?: 0

    fun clearBlocked() = MmkvManager.encodeSettings(PREF_BLOCKED, "")

    /** Blocking (copies the APK into the session). Throws on failure; the caller falls back to ACTION_VIEW. */
    fun install(context: Context, apk: File, vpnRunning: Boolean, versionCode: Int = 0) {
        MmkvManager.encodeSettings(PREF_APK, apk.absolutePath)
        MmkvManager.encodeSettings(PREF_CODE, versionCode.toString())
        val installer = context.packageManager.packageInstaller
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
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            MmkvManager.encodeSettings(PREF_RESTART_VPN, false)
            throw e
        }
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

                PackageInstaller.STATUS_SUCCESS -> LogUtil.i(AppConfig.TAG, "Amelia: update installed")

                else -> {
                    MmkvManager.encodeSettings(PREF_RESTART_VPN, false)
                    val message = "install failed, status=$status " + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                    LogUtil.w(AppConfig.TAG, "Amelia: update $message")
                    onSessionFailed(context, message)
                }
            }
        }

        /**
         * 1.4.0: the session install was refused (Samsung Auto Blocker, Play Protect: BLOCKED,
         * ABORTED with INSTALL_FAILED_VERIFICATION_FAILURE …). Right away the classic system
         * installer screen; the app also shows how to let the install through. Reported to
         * /app/crash once per version as "update_blocked".
         */
        private fun onSessionFailed(context: Context, message: String) {
            val code = MmkvManager.decodeSettingsString(PREF_CODE)?.toIntOrNull() ?: 0
            MmkvManager.encodeSettings(PREF_BLOCKED, code.toString())
            if (MmkvManager.decodeSettingsString(PREF_BLOCKED_REPORTED) != code.toString()) {
                MmkvManager.encodeSettings(PREF_BLOCKED_REPORTED, code.toString())
                AmeliaCrash.nonFatal("update_blocked", null, message)
            }
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
            if (!MmkvManager.decodeSettingsBool(PREF_RESTART_VPN, false)) return
            MmkvManager.encodeSettings(PREF_RESTART_VPN, false)
            if (MmkvManager.getSelectServer().isNullOrEmpty()) return
            LogUtil.i(AppConfig.TAG, "Amelia: restarting VPN after update")
            LauncherManager.startService(context)
        }
    }
}
