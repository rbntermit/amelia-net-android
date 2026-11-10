package com.v2ray.ang.amelia

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import androidx.work.WorkManager
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil

/**
 * 1.9.1: what a removed feature left on the phone, cleared once at start (AngApplication, any process).
 *
 * "Пауза за роутером Amelia" (1.9.0) is gone: a person behind our router decides about the VPN
 * themselves. Its settings are removed: the answer to the offer, the pause itself, the network the VPN
 * was held in and the pending offer. A pause that was on at the update ends with them; nothing of the
 * start (button, tile, widget, boot, Always-on) ever read them, so the VPN starts the usual way. Its
 * network watch is dropped too: the PendingIntent network callback and the WorkManager job.
 */
object AmeliaLegacy {

    private const val PAUSE_SETTING = "amelia_router_pause"
    private const val PAUSE_PAUSED = "amelia_router_paused"
    private const val PAUSE_HOLD = "amelia_router_hold"
    private const val PAUSE_OFFER = "amelia_router_offer"

    /** Keys of the 1.9.0 pause. */
    val ROUTER_PAUSE_KEYS = listOf(PAUSE_SETTING, PAUSE_PAUSED, PAUSE_HOLD, PAUSE_OFFER)

    /** The 1.9.0 job that watched for mobile data while paused. */
    private const val PAUSE_WORK = "amelia_router_resume"

    /** The 1.9.0 receiver of the network callback (the class is gone; the name is what the system kept). */
    private const val PAUSE_RECEIVER = "com.v2ray.ang.amelia.AmeliaRouterPause\$NetReceiver"

    /** The settings as this cleanup sees them (MMKV, a map in unit tests). */
    interface Store {
        fun contains(key: String): Boolean
        fun remove(keys: Collection<String>)
    }

    /**
     * Pure apart from [store] and [stopWatch]: removes what is left of the pause. The watch existed only
     * while paused, so it is stopped only then. @return true when there was something to clear.
     */
    fun clearRouterPause(store: Store, stopWatch: () -> Unit): Boolean {
        val present = ROUTER_PAUSE_KEYS.filter { store.contains(it) }
        if (present.isEmpty()) return false
        if (PAUSE_PAUSED in present) stopWatch()
        store.remove(present)
        return true
    }

    /** Blocking, short; after the first run only four key lookups. */
    fun cleanUp(context: Context) {
        val app = context.applicationContext
        try {
            val cleared = MmkvManager.withSettingsLock {
                clearRouterPause(
                    object : Store {
                        override fun contains(key: String) = MmkvManager.containsSettings(key)
                        override fun remove(keys: Collection<String>) = MmkvManager.removeSettings(keys)
                    },
                ) { stopRouterWatch(app) }
            }
            if (cleared) LogUtil.i(AppConfig.TAG, "Amelia: settings of the removed router pause cleared")
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "Amelia: cannot clear the router pause settings", e)
        }
    }

    private fun stopRouterWatch(context: Context) {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(PAUSE_WORK) }
            .onFailure { LogUtil.w(AppConfig.TAG, "Amelia: cannot cancel the router pause job", it) }
        runCatching {
            // the same PendingIntent as 1.9.0 registered (explicit component, mutable on Android 12+), if the system still has it
            val flags = PendingIntent.FLAG_NO_CREATE or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(context, 0, Intent().setClassName(context, PAUSE_RECEIVER), flags) ?: return@runCatching
            context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(pi)
            pi.cancel()
        }.onFailure { LogUtil.w(AppConfig.TAG, "Amelia: cannot drop the router pause network watch", it) }
    }
}
