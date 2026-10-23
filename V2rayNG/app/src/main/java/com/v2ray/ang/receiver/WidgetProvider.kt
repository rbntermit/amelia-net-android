package com.v2ray.ang.receiver

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.v2ray.ang.AppConfig
import com.v2ray.ang.amelia.AmeliaConfig
import com.v2ray.ang.amelia.AmeliaDaemon
import com.v2ray.ang.amelia.AmeliaWidget
import com.v2ray.ang.extension.serializable
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Amelia.Net 1.5.8: the home-screen widget (AmeliaWidget draws it). The class name is upstream's,
 * so widgets placed by earlier versions get the new look on the next update. Runs in :daemon.
 * Redraws on the service's state broadcasts only (updatePeriodMillis is 0).
 */
class WidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        async { AmeliaWidget.draw(context, ids = appWidgetIds) }
    }

    /** Stretched or shrunk (below Android 12 the layout is chosen by width here). */
    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        async { AmeliaWidget.draw(context, ids = intArrayOf(appWidgetId)) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (AppConfig.BROADCAST_ACTION_ACTIVITY != intent.action) return
        when (intent.getIntExtra("key", 0)) {
            AppConfig.MSG_STATE_RUNNING, AppConfig.MSG_STATE_START_SUCCESS -> {
                AmeliaWidget.clearConnecting()
                async { AmeliaWidget.draw(context, running = true) }
            }
            AppConfig.MSG_STATE_NOT_RUNNING, AppConfig.MSG_STATE_START_FAILURE, AppConfig.MSG_STATE_STOP_SUCCESS -> {
                AmeliaWidget.clearConnecting()
                AmeliaWidget.onPhase(null)
                async { AmeliaWidget.draw(context, running = false) }
            }
            AmeliaConfig.MSG_HEALTH -> {
                // checking / switching → "Подключается…"; redrawn only when the picture changes
                AmeliaWidget.onPhase(intent.serializable<AmeliaDaemon.Health>("content")?.phase)
                async { AmeliaWidget.draw(context) }
            }
        }
    }

    /** Drawing reads MMKV: off the main thread, while the broadcast is kept alive (goAsync). */
    private fun async(block: () -> Unit) {
        val pending = goAsync()
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Amelia widget: redraw failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /**
         * Owns widget redraws in :daemon for the process lifetime (a receiver has no lifecycle
         * of its own); each job ends its goAsync() result (drawing takes milliseconds).
         */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
