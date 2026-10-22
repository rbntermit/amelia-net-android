package com.v2ray.ang.amelia

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import java.util.Calendar
import java.util.TimeZone

/**
 * Subscription end date (Subscription-Userinfo `expire`, unix seconds; 0 = no end date):
 * reminders 3 days and 1 day before and on the last day, each once per end date. A renewal
 * moves the end date, which clears the reminder; the background check refreshes the
 * subscription while the end is near, so it notices the renewal without the app being opened.
 */
object AmeliaExpiry {

    /** Ordered by urgency. */
    enum class Stage { DAYS_3, DAY_1, LAST_DAY }

    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val PREF_NOTIFIED = "amelia_expiry_notified"
    private const val CHANNEL_ID = "amelia_subscription"
    private const val NOTIFICATION_ID = 7302

    // ---- pure logic (unit-tested) ---------------------------------------------------------

    /** Which reminder applies now; null when the end is more than 3 days away or there is none. */
    fun stage(expireSec: Long?, now: Long, zone: TimeZone = TimeZone.getDefault()): Stage? {
        val expire = expireSec?.takeIf { it > 0 } ?: return null
        val end = expire * 1000
        val left = end - now
        return when {
            left <= 0 || sameDay(end, now, zone) -> Stage.LAST_DAY
            left <= DAY_MS -> Stage.DAY_1
            left <= 3 * DAY_MS -> Stage.DAYS_3
            else -> null
        }
    }

    private fun sameDay(a: Long, b: Long, zone: TimeZone): Boolean {
        val ca = Calendar.getInstance(zone).apply { timeInMillis = a }
        val cb = Calendar.getInstance(zone).apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }

    /**
     * Should [stage] be announced, given what was announced already ("<expire>:<stage ordinal>")?
     * Only something more urgent than before for the same end date; a missed earlier reminder
     * is not sent after a later one.
     */
    fun shouldNotify(stage: Stage?, expireSec: Long?, notified: String?): Boolean {
        if (stage == null || expireSec == null) return false
        val parts = notified?.split(':')
        val sameExpire = parts?.getOrNull(0)?.toLongOrNull() == expireSec
        val done = if (sameExpire) parts?.getOrNull(1)?.toIntOrNull() ?: -1 else -1
        return stage.ordinal > done
    }

    /** What one check does: the reminder to show now (null: none) and the new "announced" mark. */
    data class Decision(val notify: Stage?, val mark: String?)

    /** One check at [now] with the stored mark [notified]: what to show and what to store. */
    fun decide(expireSec: Long?, now: Long, notified: String?, zone: TimeZone = TimeZone.getDefault()): Decision {
        val stage = stage(expireSec, now, zone)
        return if (shouldNotify(stage, expireSec, notified)) Decision(stage, "$expireSec:${stage!!.ordinal}")
        else Decision(null, notified)
    }

    /** The end is near or passed: worth asking the server whether it was renewed. */
    fun needsRefresh(info: AmeliaSubInfo?, now: Long): Boolean = stage(info?.expire, now) != null

    // ---- notifications --------------------------------------------------------------------

    /** Shows the reminder that is due, or removes it after a renewal. Any process. */
    fun check(context: Context, info: AmeliaSubInfo?, now: Long = System.currentTimeMillis()) {
        val stage = stage(info?.expire, now)
        val nm = NotificationManagerCompat.from(context)
        if (stage == null) {
            nm.cancel(NOTIFICATION_ID)
            return
        }
        val expire = info?.expire ?: return
        val d = decide(expire, now, MmkvManager.decodeSettingsString(PREF_NOTIFIED))
        val due = d.notify ?: return
        MmkvManager.encodeSettings(PREF_NOTIFIED, d.mark.orEmpty())
        // no POST_NOTIFICATIONS on Android 13+ (or notifications off): not asked for, just not sent
        notify(context, due, expire, now)
    }

    private fun notify(context: Context, stage: Stage, expireSec: Long, now: Long) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, context.getString(R.string.amelia_expiry_channel), NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        val title = context.getString(
            when {
                stage == Stage.LAST_DAY && expireSec * 1000 <= now -> R.string.amelia_expiry_ended
                stage == Stage.LAST_DAY -> R.string.amelia_expiry_today
                stage == Stage.DAY_1 -> R.string.amelia_expiry_1_day
                else -> R.string.amelia_expiry_3_days
            },
        )
        val open = PendingIntent.getActivity(
            context, 1,
            Intent(context, AmeliaActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val pay = PendingIntent.getActivity(
            context, 2,
            // 1.5.7: the bot with Telegram, the site without (main address: a notice link is fixed)
            Intent(
                Intent.ACTION_VIEW,
                AmeliaSite.renewUrl(
                    AmeliaPackages.telegram(context.packageManager) != null, AmeliaSite.BASES[0], AmeliaManager.getKey(),
                ).toUri(),
            )
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.amelia_expiry_text, AmeliaFormat.date(expireSec)))
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.amelia_renew), pay)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            LogUtil.w(AppConfig.TAG, "Amelia: no permission for the subscription reminder", e)
        }
    }
}
