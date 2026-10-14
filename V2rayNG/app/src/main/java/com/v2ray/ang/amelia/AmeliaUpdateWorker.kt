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
 */
class AmeliaUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val v = AmeliaUpdater.check() ?: return Result.success()
        if (AmeliaUpdater.markNotified(v)) notify(applicationContext, v)
        return Result.success()
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

        private fun notify(context: Context, v: AmeliaUpdater.AppVersion) {
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
                Intent(context, AmeliaActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val title = context.getString(
                if (AmeliaUpdater.isRequired(v)) R.string.amelia_update_required_title else R.string.amelia_update_available,
                v.versionName ?: v.versionCode.toString(),
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_name)
                .setContentTitle(title)
                .setContentText(v.notes?.takeIf { it.isNotBlank() } ?: context.getString(R.string.amelia_update_tap))
                .setStyle(NotificationCompat.BigTextStyle().bigText(v.notes))
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
