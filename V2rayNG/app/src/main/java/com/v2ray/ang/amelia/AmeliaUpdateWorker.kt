package com.v2ray.ang.amelia

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.util.LogUtil
import java.util.concurrent.TimeUnit

/**
 * Background update check every 6 hours (the app also checks on start, see AmeliaViewModel).
 * A new version is cached for the app and announced once with a notification.
 * The same run reminds about the end of the subscription (AmeliaExpiry).
 */
class AmeliaUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        checkSubscriptionEnd()
        val v = AmeliaUpdater.check() ?: return Result.success()
        val ctx = applicationContext
        // 1.3.0: on Wi-Fi (no data limit) the update is downloaded right away; on mobile data we ask first
        val ready = AmeliaUpdater.readyApk(ctx, v)
            ?: if (AmeliaUpdater.isUnmetered(ctx)) AmeliaUpdater.download(ctx, v) {} else null
        if (AmeliaUpdater.markNotified(v, ready != null)) notify(ctx, v, ready != null)
        return Result.success()
    }

    /**
     * Subscription reminders (AmeliaExpiry). Near or after the end the subscription is fetched
     * again first, so a renewal made in the bot clears the reminder.
     */
    private fun checkSubscriptionEnd() {
        try {
            if (AmeliaManager.hasKey() && AmeliaExpiry.needsRefresh(AmeliaManager.getSubInfo(), System.currentTimeMillis())) {
                AmeliaManager.refresh()
            }
            AmeliaExpiry.check(applicationContext, AmeliaManager.getSubInfo())
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "Amelia: subscription end check failed", e)
        }
    }

    companion object {
        private const val WORK_NAME = "amelia_update_check"
        private const val CHANNEL_ID = "amelia_updates"
        private const val NOTIFICATION_ID = 7301

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AmeliaUpdateWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(6, TimeUnit.HOURS)
                .build()
            RemoteWorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Opens the app straight into installing the downloaded update. */
        const val EXTRA_INSTALL = "amelia_install_update"

        private fun notify(context: Context, v: AmeliaUpdater.AppVersion, ready: Boolean) {
            val nm = NotificationManagerCompat.from(context)
            if (!nm.areNotificationsEnabled()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.amelia_update_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
                context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            }
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, AmeliaActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(EXTRA_INSTALL, ready),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val name = v.versionName ?: v.versionCode.toString()
            val title = when {
                ready -> context.getString(R.string.amelia_update_ready, name)
                AmeliaUpdater.isRequired(v) -> context.getString(R.string.amelia_update_required_title, name)
                else -> context.getString(R.string.amelia_update_available, name)
            }
            val text = if (ready) {
                v.notes?.takeIf { it.isNotBlank() } ?: context.getString(R.string.amelia_update_tap)
            } else {
                AmeliaUpdater.downloadSize(v)?.let { context.getString(R.string.amelia_update_tap_size, AmeliaFormat.megabytes(it)) }
                    ?: context.getString(R.string.amelia_update_tap)
            }
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_name)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            try {
                nm.notify(NOTIFICATION_ID, notification)
            } catch (e: SecurityException) {
                LogUtil.w(AppConfig.TAG, "Amelia: no permission for the update notification", e)
            }
        }
    }
}
