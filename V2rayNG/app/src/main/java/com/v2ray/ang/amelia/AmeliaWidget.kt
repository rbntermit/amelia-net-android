package com.v2ray.ang.amelia

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.receiver.WidgetProvider
import com.v2ray.ang.util.LogUtil

/**
 * 1.5.8: the home-screen widget (2×1, stretches to 4×1): state, location, one big button.
 * The button does what the tile does (AmeliaTile) and starts on the last location, or the best
 * one when it is gone (AmeliaBoot.ensureLocation, as "⚡ Подключить лучший"). An ended
 * subscription turns the button into "Продлить". Redrawn only on the service's own events
 * (started, stopped, switched, health phase) and on a location or subscription change in the
 * app: no periodic polling (updatePeriodMillis = 0).
 *
 * Drawing and the connect state live in the :daemon process (WidgetProvider and
 * AmeliaWidgetTapActivity run there), where CoreServiceManager is the authority.
 *
 * 1.6.0: in "белые списки" mode (daemon phase WHITELIST, the same event that shows the card on
 * the main screen) the widget says "Белые списки · нужен Wi‑Fi" in the warning colour, and the
 * button opens the app instead of touching the VPN. The mode ends: the next phase redraws it.
 */
object AmeliaWidget {

    enum class Status { OFF, CONNECTING, CONNECTED, WHITELIST }

    enum class Button { CONNECT, DISCONNECT, RENEW, OPEN_APP }

    /** What a tap on the button does. */
    enum class Tap { START, STOP, OPEN_APP, RENEW }

    /** "Подключается…" after a tap is dropped after this if the service never answered. */
    const val CONNECTING_MS = 30_000L

    /** From 3 cells wide (dp) the widget has room for "Подписка до …". */
    const val WIDE_DP = 200

    /** AmeliaActivity opens "Продлить" (bot or site) right away. */
    const val EXTRA_RENEW = "amelia_widget_renew"

    data class Model(
        val status: Status,
        /** The location's name as the app shows it (the flag is part of it); null: none yet. */
        val location: String?,
        val button: Button,
        /** "05.11" when the end date is known and still ahead; null otherwise. */
        val until: String?,
        val expired: Boolean,
    )

    // ---- pure logic (unit-tested) ---------------------------------------------------------

    fun status(running: Boolean, phase: String?, connectingSince: Long, now: Long): Status = when {
        running && phase == AmeliaDaemon.Phase.WHITELIST.name -> Status.WHITELIST
        running && (phase == AmeliaDaemon.Phase.CHECKING.name || phase == AmeliaDaemon.Phase.SWITCHING.name) -> Status.CONNECTING
        running -> Status.CONNECTED
        connectingSince > 0 && now - connectingSince in 0 until CONNECTING_MS -> Status.CONNECTING
        else -> Status.OFF
    }

    fun model(status: Status, location: String?, info: AmeliaSubInfo?, now: Long, dayMonth: (Long) -> String): Model {
        val expired = !AmeliaPolicy.subscriptionActive(info, now)
        val until = info?.expire?.takeIf { it > 0 && !expired }?.let(dayMonth)
        val button = when {
            expired -> Button.RENEW
            status == Status.WHITELIST -> Button.OPEN_APP
            status == Status.OFF -> Button.CONNECT
            else -> Button.DISCONNECT
        }
        return Model(status, location?.takeIf { it.isNotBlank() }, button, until, expired)
    }

    /**
     * A tap on the button: "Продлить" when the subscription ended, otherwise exactly the tile's
     * rule (AmeliaTile.onTap): on → off; no VPN permission, no location or a required update →
     * the app; otherwise connect. [busy] is running or connecting: a second tap disconnects.
     * 1.6.0 [whitelist]: "белые списки" — the app (its card explains), the VPN is left alone.
     */
    fun tap(
        expired: Boolean,
        busy: Boolean,
        vpnPermission: Boolean,
        hasLocation: Boolean,
        updateRequired: Boolean,
        whitelist: Boolean = false,
    ): Tap =
        if (expired) {
            Tap.RENEW
        } else if (whitelist) {
            Tap.OPEN_APP
        } else {
            when (AmeliaTile.onTap(busy, vpnPermission, hasLocation, updateRequired)) {
                AmeliaTile.Action.STOP -> Tap.STOP
                AmeliaTile.Action.START -> Tap.START
                AmeliaTile.Action.OPEN_APP -> Tap.OPEN_APP
            }
        }

    /** What the two text lines say: the state line and, below it, the location (null: its name). */
    data class Lines(val status: Int, val second: Int?)

    fun lines(m: Model): Lines = when (m.status) {
        Status.CONNECTED -> Lines(R.string.amelia_phase_connected, null)
        Status.CONNECTING -> Lines(R.string.amelia_widget_connecting, null)
        Status.OFF -> Lines(R.string.amelia_phase_off, null)
        Status.WHITELIST -> Lines(R.string.amelia_widget_whitelist, R.string.amelia_widget_need_wifi)
    }

    // ---- state of the :daemon process -------------------------------------------------------

    @Volatile
    private var connectingSince = 0L

    @Volatile
    private var phase: String? = null

    /** The last drawn model: health events that change nothing are not redrawn. */
    @Volatile
    private var lastDrawn: Model? = null

    fun markConnecting(context: Context) {
        connectingSince = System.currentTimeMillis()
        draw(context, force = true)
    }

    /** The service answered (started, failed, stopped): no more "Подключается…" from the tap. */
    fun clearConnecting() {
        connectingSince = 0L
    }

    fun onPhase(value: String?) {
        phase = value
    }

    fun isBusy(): Boolean =
        CoreServiceManager.isRunning() || status(false, null, connectingSince, System.currentTimeMillis()) == Status.CONNECTING

    /** Blocking (MMKV, package manager): what a tap on the button does now. */
    fun decideTap(context: Context): Tap = tap(
        expired = !AmeliaPolicy.subscriptionActive(AmeliaManager.getSubInfo(), System.currentTimeMillis()),
        busy = isBusy(),
        vpnPermission = AmeliaBoot.hasVpnPermission(context),
        hasLocation = AmeliaManager.hasKey() && MmkvManager.decodeServerList(AmeliaConfig.SUBSCRIPTION_ID).isNotEmpty(),
        updateRequired = AmeliaUpdater.isRequired(AmeliaUpdater.cached()),
        whitelist = isWhitelist(),
    )

    /** 1.6.0: the VPN is on and the daemon reported "белые списки" (what the widget shows now). */
    fun isWhitelist(): Boolean =
        status(CoreServiceManager.isRunning(), phase, connectingSince, System.currentTimeMillis()) == Status.WHITELIST

    // ---- drawing ------------------------------------------------------------------------------

    /**
     * Redraws every placed widget. [running]: the state an event just reported (null: ask
     * CoreServiceManager). Blocking (MMKV); call it off the main thread, in :daemon.
     */
    fun draw(context: Context, running: Boolean? = null, force: Boolean = false, ids: IntArray? = null) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val all = ids ?: manager.getAppWidgetIds(ComponentName(context, WidgetProvider::class.java))
        if (all.isEmpty()) return
        val now = System.currentTimeMillis()
        val isRunning = running ?: CoreServiceManager.isRunning()
        val location = if (isRunning) {
            CoreServiceManager.getRunningServerName()
        } else {
            MmkvManager.getSelectServer()?.let { MmkvManager.decodeServerConfig(it)?.remarks }
        }
        val m = model(
            status(isRunning, phase.takeIf { isRunning }, connectingSince, now),
            location?.takeIf { AmeliaManager.hasKey() },
            AmeliaManager.getSubInfo(),
            now,
            AmeliaFormat::dayMonth,
        )
        if (!force && ids == null && m == lastDrawn) return
        lastDrawn = m
        for (id in all) {
            try {
                manager.updateAppWidget(id, views(context, manager, id, m))
            } catch (e: RuntimeException) {
                LogUtil.e(AppConfig.TAG, "Amelia widget: cannot update widget $id", e)
            }
        }
    }

    private fun views(context: Context, manager: AppWidgetManager, id: Int, m: Model): RemoteViews =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+: the launcher picks the layout for the current size itself
            RemoteViews(
                mapOf(
                    SizeF(100f, 40f) to build(context, m, wide = false),
                    SizeF(WIDE_DP.toFloat(), 40f) to build(context, m, wide = true),
                ),
            )
        } else {
            val width = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
            build(context, m, wide = width >= WIDE_DP)
        }

    private fun build(context: Context, m: Model, wide: Boolean): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.widget_amelia)
        val lines = lines(m)
        v.setTextViewText(R.id.widget_status, context.getString(lines.status))
        when (m.status) {
            Status.CONNECTED -> v.setTextColor(R.id.widget_status, context.getColor(R.color.amelia_widget_green))
            // 1.6.0: "белые списки" is a warning, never green
            Status.CONNECTING, Status.WHITELIST -> v.setTextColor(R.id.widget_status, context.getColor(R.color.amelia_widget_amber))
            Status.OFF -> Unit // the layout's muted colour (day/night)
        }
        v.setTextViewText(
            R.id.widget_location,
            lines.second?.let { context.getString(it) } ?: m.location ?: context.getString(R.string.app_name),
        )

        val sub = when {
            !wide -> null
            m.expired -> context.getString(R.string.amelia_sub_ended)
            m.until != null -> context.getString(R.string.amelia_widget_sub_until, m.until)
            else -> null
        }
        v.setViewVisibility(R.id.widget_sub, if (sub != null) View.VISIBLE else View.GONE)
        sub?.let { v.setTextViewText(R.id.widget_sub, it) }

        val renew = m.button == Button.RENEW
        v.setViewVisibility(R.id.widget_power, if (renew) View.GONE else View.VISIBLE)
        v.setViewVisibility(R.id.widget_renew, if (renew) View.VISIBLE else View.GONE)
        if (!renew) {
            v.setInt(
                R.id.widget_power, "setBackgroundResource",
                when (m.status) {
                    Status.CONNECTED -> R.drawable.amelia_widget_power_on
                    Status.CONNECTING, Status.WHITELIST -> R.drawable.amelia_widget_power_connecting
                    Status.OFF -> R.drawable.amelia_widget_power_off
                },
            )
            v.setImageViewResource(
                R.id.widget_power_icon,
                if (m.status == Status.WHITELIST) R.drawable.ic_amelia_warning else R.drawable.ic_amelia_power,
            )
            v.setContentDescription(
                R.id.widget_power,
                context.getString(
                    when (m.button) {
                        Button.CONNECT -> R.string.amelia_connect
                        Button.OPEN_APP -> R.string.amelia_widget_open_app
                        else -> R.string.amelia_disconnect
                    },
                ),
            )
        }

        // the button decides at tap time (permission, subscription and state may have changed since)
        val tap = PendingIntent.getActivity(
            context, 0,
            Intent(context, AmeliaWidgetTapActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        v.setOnClickPendingIntent(R.id.widget_power, tap)
        v.setOnClickPendingIntent(R.id.widget_renew, tap)
        // the rest of the widget opens the app
        v.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))
        return v
    }

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, AmeliaActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * Any process: asks the widget (in :daemon) to redraw, e.g. after a location or subscription
     * change in the app. Nothing is sent when no widget is placed.
     */
    fun requestUpdate(context: Context) {
        try {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, WidgetProvider::class.java))
            if (ids.isEmpty()) return
            context.sendBroadcast(
                Intent(context, WidgetProvider::class.java)
                    .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
            )
        } catch (e: RuntimeException) {
            LogUtil.w(AppConfig.TAG, "Amelia widget: update request failed", e)
        }
    }
}
