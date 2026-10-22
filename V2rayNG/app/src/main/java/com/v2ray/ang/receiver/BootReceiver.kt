package com.v2ray.ang.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.v2ray.ang.AppConfig
import com.v2ray.ang.amelia.AmeliaBoot
import com.v2ray.ang.util.LogUtil
import kotlin.concurrent.thread

/**
 * Amelia.Net 1.5.4: after the phone starts, the VPN comes up when the person turned on
 * "Включать VPN при запуске телефона" (AmeliaBoot decides). Upstream's start on
 * MY_PACKAGE_REPLACED is gone: after an update AmeliaInstaller brings the VPN back only if it
 * was on.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return
        if (context == null) return
        LogUtil.i(AppConfig.TAG, "BootReceiver received: $action")
        if (action !in AmeliaBoot.BOOT_ACTIONS) return
        val pending = goAsync()
        val app = context.applicationContext
        // disk reads off the main thread; the boot exemption for starting the service still holds
        thread(name = "amelia-boot") {
            try {
                AmeliaBoot.onBoot(app, action)
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "BootReceiver: start on boot failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
