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
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.handler.SubscriptionUpdater
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
        val ctx = applicationContext
        val before = AmeliaUpdater.lastCheck()
        val v = AmeliaUpdater.check()
        if (v == null) {
            // 1.3.1: the server says nothing newer: no stale "вышла версия …" stays behind
            if (AmeliaUpdater.lastCheck() != before) {
                cancelNotification(ctx)
                AmeliaUpdater.deleteDownloads(ctx)
            }
            return Result.success()
        }
        AmeliaUpdater.pruneDownloads(ctx, v)
        // 1.3.0: on Wi-Fi (no data limit) the update is downloaded right away; on mobile data we ask first
        val ready = AmeliaUpdater.readyApk(ctx, v)
            ?: if (AmeliaUpdater.isUnmetered(ctx)) AmeliaUpdater.download(ctx, v) {} else null
        if (AmeliaUpdater.markNotified(v, ready != null)) notify(ctx, v, ready != null)
        return Result.success()
    }

    /**
     * Subscription reminders (AmeliaExpiry). Near or after the end the subscription is fetched
     * again first, so a renewal made in the bot clears the reminder.
     *
     * 1.7.0: this is the only background refresh of the subscription (upstream's worker is off):
     * also when the last download is older than the subscription's own interval
     * ([subscriptionDue], 12 h unless the server says otherwise).
     */
    private fun checkSubscriptionEnd() {
        try {
            val now = System.currentTimeMillis()
            val info = AmeliaManager.getSubInfo()
            val due = AmeliaExpiry.needsRefresh(info, now) ||
                subscriptionDue(AmeliaManager.lastSubscriptionUpdate(), info?.updateIntervalHours, now)
            if (AmeliaManager.hasKey() && due) {
                AmeliaManager.refresh()
                // 1.5.2: new routing lists reach a running VPN (the daemon restarts itself; no-op when off)
                if (AmeliaManager.takeRoutingChanged()) LauncherManager.restartService(applicationContext)
            }
            AmeliaExpiry.check(applicationContext, AmeliaManager.getSubInfo())
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "Amelia: subscription end check failed", e)
        }
    }

    companion object {
        /**
         * 1.7.0, pure: the last download ([lastUpdate], epoch millis, 0: never) is at least the
         * subscription's interval old ([intervalHours] from its headers, the default otherwise).
         */
        fun subscriptionDue(lastUpdate: Long, intervalHours: Int?, now: Long): Boolean {
            val hours = (intervalHours ?: AmeliaConfig.DEFAULT_UPDATE_INTERVAL_HOURS).coerceAtLeast(1)
            return lastUpdate <= 0L || now - lastUpdate >= hours * 3_600_000L
        }

        private const val WORK_NAME = "amelia_update_check"
        private const val CHANNEL_ID = "amelia_updates"
        private const val NOTIFICATION_ID = 7301

        fun schedule(context: Context) {
            // 1.7.0: upstream's subscription task from earlier versions is dropped (this worker refreshes)
            SubscriptionUpdater.cancelOne(context, AmeliaConfig.SUBSCRIPTION_ID)
            val request = PeriodicWorkRequestBuilder<AmeliaUpdateWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(6, TimeUnit.HOURS)
                .build()
            RemoteWorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Removes the update notification (the app shows the current state itself). */
        fun cancelNotification(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)

        /** Opens the app straight into installing the downloaded update. */
        const val EXTRA_INSTALL = "amelia_install_update"

        /** The "Обновления приложения" channel (also used by AmeliaInstaller's confirmation). */
        internal fun ensureChannel(context: Context): String {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.amelia_update_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
                context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            }
            return CHANNEL_ID
        }

        /** Same slot as "готово — установить": one update notification at a time. */
        internal const val UPDATE_NOTIFICATION_ID = NOTIFICATION_ID

        private fun notify(context: Context, v: AmeliaUpdater.AppVersion, ready: Boolean) {
            val nm = NotificationManagerCompat.from(context)
            if (!nm.areNotificationsEnabled()) return
            ensureChannel(context)
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
