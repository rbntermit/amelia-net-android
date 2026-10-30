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
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 1.8.0 "Поддержка": a chat with support inside the app, for when Telegram does not work.
 * Contract with the server (README, "Контракт report/probe"):
 * - POST /app/support {"key", "text", "about", "app"} → {"ok": true, "id": "W-…", "h": "<24 hex>"};
 *   400 {"err": "empty" | "bad key"}, 429 (more than 6 in 10 min), 500;
 * - POST /app/support/thread {"h"} → {"ok": true, "items": [{"id", "ts", "d": "in" | "out", "x"}]},
 *   up to 30, oldest first; 429 more often than 30 times in 10 min.
 * Reports (/app/report) are in the same thread, and support's answers to them too.
 *
 * The key leaves the phone only in /app/support, when the person taps "Отправить"; reading the
 * thread (the open screen, the background check) sends only [hash].
 */
object AmeliaSupport {

    const val PATH = "/app/support"
    const val THREAD_PATH = "/app/support/thread"
    const val MAX_TEXT = 1500
    const val MAX_ABOUT = 4000
    /** While the screen is open. */
    const val SCREEN_POLL_MS = 30_000L
    /** In the background, only while an answer is awaited ([waiting]). */
    const val BACKGROUND_POLL_MINUTES = 15L
    /** Our last message is older than this: no more background checks. */
    const val WAIT_MS = 7L * 24 * 60 * 60 * 1000

    const val DIR_IN = "in"
    const val DIR_OUT = "out"

    private const val HASH_PREFIX = "amelia-support:"
    private const val HASH_LENGTH = 24
    private const val PREF = "amelia_support"

    /** One message: [d] "in" from the person (and their reports), "out" from support; [ts] Unix seconds. */
    data class Item(val id: String, val ts: Long, val d: String, val x: String) {
        val fromSupport: Boolean get() = d == DIR_OUT
    }

    /**
     * Kept in MMKV: the thread of [h] (another key — another thread), when our last message was
     * sent and not yet seen in a fetched thread ([sentAt], epoch millis, 0: none), the newest answer
     * seen on the screen ([seen]) and the newest one a notification was shown for ([notified]).
     */
    data class Store(
        val h: String = "",
        val items: List<Item> = emptyList(),
        val sentAt: Long = 0L,
        val seen: String = "",
        val notified: String = "",
    )

    sealed interface SendResult {
        data class Sent(val id: String) : SendResult
        data object Empty : SendResult
        data object BadKey : SendResult
        data object TooOften : SendResult
        /** No server answered, or it failed (500, not JSON). */
        data object Failed : SendResult
    }

    // ---- pure logic (unit-tested) ---------------------------------------------------------

    /** The first 24 hex characters of sha256("amelia-support:" + key): the thread's name without the key. */
    fun hash(key: String): String =
        MessageDigest.getInstance("SHA-256").digest((HASH_PREFIX + key).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(HASH_LENGTH)

    /** What is sent of the person's text: trimmed, at most [MAX_TEXT] characters. */
    fun cut(text: String): String = text.trim().take(MAX_TEXT)

    fun sendRequest(key: String, text: String, about: String, app: String): String = JsonObject().apply {
        addProperty("key", key)
        addProperty("text", cut(text))
        addProperty("about", about.take(MAX_ABOUT))
        addProperty("app", app)
    }.toString()

    fun threadRequest(h: String): String = JsonObject().apply { addProperty("h", h) }.toString()

    private class SendWire(val ok: Boolean? = null, val id: String? = null, val err: String? = null)

    private class ItemWire(val id: String? = null, val ts: Long? = null, val d: String? = null, val x: String? = null)

    private class ThreadWire(val ok: Boolean? = null, val items: List<ItemWire?>? = null)

    private fun <T> parse(body: String?, cls: Class<T>): T? =
        // a proxy or captive portal may answer with HTML: that is "no answer", not an error to log
        body?.let { runCatching { Gson().fromJson(it, cls) }.getOrNull() }

    /** The answer of /app/support with its HTTP [code]; null [code]: no server answered. */
    fun parseSend(code: Int?, body: String?): SendResult = when (code) {
        null -> SendResult.Failed
        429 -> SendResult.TooOften
        400 -> when (parse(body, SendWire::class.java)?.err) {
            "empty" -> SendResult.Empty
            "bad key" -> SendResult.BadKey
            else -> SendResult.Failed
        }
        in 200..299 -> parse(body, SendWire::class.java)
            ?.takeIf { it.ok == true }?.id?.takeIf { it.isNotBlank() }
            ?.let { SendResult.Sent(it) } ?: SendResult.Failed
        else -> SendResult.Failed
    }

    /**
     * The thread from /app/support/thread, oldest first; null when it is not one (HTML, garbage,
     * "ok" not true). Messages without an id or with an unknown direction are left out.
     */
    fun parseThread(body: String?): List<Item>? {
        val w = parse(body, ThreadWire::class.java)?.takeIf { it.ok == true } ?: return null
        return items(w.items)
    }

    private fun items(wire: List<ItemWire?>?): List<Item> = wire.orEmpty().mapNotNull { i ->
        val id = i?.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val d = i.d?.takeIf { it == DIR_IN || it == DIR_OUT } ?: return@mapNotNull null
        Item(id, (i.ts ?: 0L).coerceAtLeast(0L), d, i.x.orEmpty())
    }

    /** [s] when it is the thread of [h], an empty one otherwise (the key was changed). */
    fun forHash(s: Store, h: String): Store = if (s.h == h) s else Store(h = h)

    /**
     * A thread fetched at [fetchedAt] replaces the kept one; it already has every message sent
     * before that, so [Store.sentAt] is no longer needed. A message sent while the fetch was on its
     * way stays at the end until a later fetch brings it.
     */
    fun withThread(s: Store, items: List<Item>, fetchedAt: Long): Store {
        val sentSince = if (s.sentAt > fetchedAt) {
            s.items.filter { it.d == DIR_IN && it.ts >= fetchedAt / 1000 && items.none { f -> f.id == it.id } }
        } else {
            emptyList()
        }
        return s.copy(items = items + sentSince, sentAt = if (s.sentAt in 1..fetchedAt) 0L else s.sentAt)
    }

    /** Our message [id] sent at [now]: shown right away, until the next fetch brings it from the server. */
    fun withSent(s: Store, id: String, text: String, now: Long): Store =
        s.copy(items = s.items + Item(id, now / 1000, DIR_IN, cut(text)), sentAt = now)

    /**
     * An answer is awaited: the last message is ours (or one sent after the last fetch, e.g. a
     * report) and it is less than [WAIT_MS] old. Only then the background check runs.
     */
    fun waiting(s: Store, now: Long): Boolean {
        if (s.sentAt > 0 && now - s.sentAt < WAIT_MS) return true
        val last = s.items.lastOrNull() ?: return false
        return last.d == DIR_IN && now - last.ts * 1000 < WAIT_MS
    }

    fun latestAnswer(items: List<Item>): Item? = items.lastOrNull { it.fromSupport }

    /** The dot on "💬 Поддержка": support's newest answer has not been seen on the screen. */
    fun unread(s: Store): Boolean = latestAnswer(s.items)?.let { it.id != s.seen } ?: false

    /**
     * The answer to notify about: the thread ends with support's answer (it came after our last
     * message) that was neither seen on the screen nor notified already. One notification per answer.
     */
    fun newAnswer(s: Store): Item? =
        s.items.lastOrNull()?.takeIf { it.fromSupport && it.id != s.seen && it.id != s.notified }

    /** The screen showed the thread: its newest answer is read. */
    fun seen(s: Store): Store = latestAnswer(s.items)?.let { s.copy(seen = it.id) } ?: s

    /**
     * "about" for support: what "Скопировать для поддержки" gives, the location, whether the VPN
     * is on and the journal. Never the key. Over [MAX_ABOUT]: the oldest journal lines go first.
     */
    fun about(supportText: String, location: String?, vpn: Boolean, journal: List<String>): String {
        fun build(lines: List<String>) = buildString {
            appendLine(supportText.trim())
            location?.let { appendLine("Локация: $it") }
            appendLine("VPN включён: ${if (vpn) "да" else "нет"}")
            if (lines.isNotEmpty()) {
                appendLine()
                appendLine("Журнал:")
                lines.forEach { appendLine(it) }
            }
        }.trimEnd()
        var lines = journal
        var text = build(lines)
        while (text.length > MAX_ABOUT && lines.isNotEmpty()) {
            lines = lines.drop(1)
            text = build(lines)
        }
        return text.take(MAX_ABOUT)
    }

    /** "14:05" today, "29.10 14:05" another day; [ts] Unix seconds. */
    fun time(ts: Long, now: Long): String {
        val at = Calendar.getInstance().apply { timeInMillis = ts * 1000 }
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val sameDay = at.get(Calendar.YEAR) == today.get(Calendar.YEAR) && at.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
        return SimpleDateFormat(if (sameDay) "HH:mm" else "dd.MM HH:mm", Locale.US).format(Date(ts * 1000))
    }

    // ---- storage --------------------------------------------------------------------------

    private class StoreWire(
        val h: String? = null,
        val items: List<ItemWire?>? = null,
        val sentAt: Long? = null,
        val seen: String? = null,
        val notified: String? = null,
    )

    fun load(): Store {
        val w = MmkvManager.decodeSettingsString(PREF)?.takeIf { it.isNotBlank() }
            ?.let { JsonUtil.fromJsonSafe(it, StoreWire::class.java) } ?: return Store()
        return Store(
            h = w.h.orEmpty(),
            items = items(w.items),
            sentAt = w.sentAt ?: 0L,
            seen = w.seen.orEmpty(),
            notified = w.notified.orEmpty(),
        )
    }

    /** Read-modify-write under the settings lock shared by every process (the app, the worker). */
    fun update(block: (Store) -> Store): Store = MmkvManager.withSettingsLock {
        block(load()).also { MmkvManager.encodeSettings(PREF, JsonUtil.toJson(it)) }
    }

    /** The kept thread of [key]; empty for another key. */
    fun current(key: String): Store = forHash(load(), hash(key))

    /** Blocking (may load the core once for its version): [about] of this phone right now. */
    fun aboutFor(context: Context, location: String?, vpn: Boolean): String = about(
        AmeliaAbout.supportText(context, AmeliaAbout.load(context)),
        location,
        vpn,
        AmeliaReport.journalLines(AmeliaJournal.list(), AmeliaFormat::dateTime),
    )

    /** Logout: the thread of the old key is not kept. */
    fun clear(context: Context) {
        MmkvManager.encodeSettings(PREF, "")
        cancel(context)
        cancelNotification(context)
    }

    // ---- network --------------------------------------------------------------------------

    /** 400 and 429 are the same at every address: no need to ask the next one. */
    private fun final(code: Int) = code == 400 || code == 429

    /**
     * Blocking: [text] with [about] to support, from a tap on "Отправить". On success the message is
     * kept in the thread and the background check for the answer starts.
     */
    fun send(context: Context, key: String, text: String, about: String): SendResult {
        val answer = AmeliaNetwork.postAnswer(context, PATH, ::final) {
            sendRequest(key, text, about, BuildConfig.VERSION_NAME)
        }
        val result = parseSend(answer?.code, answer?.body)
        if (result is SendResult.Sent) {
            val h = hash(key)
            update { withSent(forHash(it, h), result.id, text, System.currentTimeMillis()) }
            schedule(context)
        }
        return result
    }

    /** "Не работает — сообщить" went through: the report is in the thread, its answer is awaited. */
    fun reportSent(context: Context, key: String) {
        val h = hash(key)
        update { forHash(it, h).copy(sentAt = System.currentTimeMillis()) }
        schedule(context)
    }

    /** Blocking: the thread of [key] from the server (only [hash] is sent), kept; null: no answer. */
    fun refresh(context: Context, key: String): Store? {
        val h = hash(key)
        val started = System.currentTimeMillis()
        val answer = AmeliaNetwork.postAnswer(context, THREAD_PATH, ::final) { threadRequest(h) } ?: return null
        if (answer.code !in 200..299) {
            LogUtil.w(AppConfig.TAG, "Amelia support: thread HTTP ${answer.code} (${answer.path})")
            return null
        }
        val items = parseThread(answer.body) ?: return null
        return update { withThread(forHash(it, h), items, started) }
    }

    /** The screen showed the thread: the dot goes, and the notification about it too. */
    fun markSeen(context: Context, key: String): Store {
        val h = hash(key)
        return update { seen(forHash(it, h)) }.also { cancelNotification(context) }
    }

    // ---- background check -----------------------------------------------------------------

    private const val WORK_NAME = "amelia_support_wait"
    private const val CHANNEL_ID = "amelia_support"
    private const val NOTIFICATION_ID = 7306

    /** Opens the app on "Поддержка" (the notification about an answer). */
    const val EXTRA_OPEN = "amelia_open_support"

    /**
     * Every 15 minutes (WorkManager's shortest period, no alarms of our own) with a network and a
     * battery that is not low. Android puts it off further in Doze and battery saving, as any
     * background work; the battery setting of the app (AmeliaBattery) is not asked for this.
     */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<Worker>(BACKGROUND_POLL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .setInitialDelay(BACKGROUND_POLL_MINUTES, TimeUnit.MINUTES)
            .build()
        RemoteWorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        RemoteWorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /** App start: the check runs only while an answer is awaited. */
    fun syncSchedule(context: Context) {
        val key = AmeliaManager.getKey()
        if (key != null && waiting(current(key), System.currentTimeMillis())) schedule(context) else cancel(context)
    }

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val ctx = applicationContext
            try {
                val key = AmeliaManager.getKey()
                if (key == null || !waiting(current(key), System.currentTimeMillis())) {
                    cancel(ctx)
                    return Result.success()
                }
                refresh(ctx, key)
                var answer: Item? = null
                val s = update { st ->
                    val cur = forHash(st, hash(key))
                    answer = newAnswer(cur)
                    answer?.let { cur.copy(notified = it.id) } ?: cur
                }
                answer?.let { notify(ctx, it) }
                if (!waiting(s, System.currentTimeMillis())) cancel(ctx)
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "Amelia support: background check failed", e)
            }
            return Result.success()
        }
    }

    fun cancelNotification(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)

    private fun notify(context: Context, answer: Item) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.amelia_support_channel), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val open = PendingIntent.getActivity(
            context, NOTIFICATION_ID,
            Intent(context, AmeliaActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_OPEN, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(context.getString(R.string.amelia_support_notification))
            .setContentText(answer.x)
            .setStyle(NotificationCompat.BigTextStyle().bigText(answer.x))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            LogUtil.w(AppConfig.TAG, "Amelia support: no permission for the notification", e)
        }
    }
}
