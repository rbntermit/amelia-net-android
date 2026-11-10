package com.v2ray.ang.amelia

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 1.5.8: the widget's button. An invisible activity (in :daemon, like the tile) rather than a
 * broadcast: it may open the app (no VPN permission, "Продлить") and start the VPN service as
 * a foreground app would. Decides at tap time (AmeliaWidget.decideTap) and closes itself.
 */
class AmeliaWidgetTapActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocaleManager.localizedContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            finish()
            return
        }
        lifecycleScope.launch {
            try {
                act()
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Amelia widget: tap failed", e)
            } finally {
                finish()
            }
        }
    }

    private suspend fun act() {
        val app = applicationContext
        val tap = withContext(Dispatchers.IO) { AmeliaWidget.decideTap(app) }
        LogUtil.i(AppConfig.TAG, "Amelia widget: tap → $tap")
        when (tap) {
            AmeliaWidget.Tap.STOP -> {
                AmeliaWidget.clearConnecting()
                LauncherManager.stopService(this)
            }
            AmeliaWidget.Tap.START -> {
                // the last location, or the best one when a subscription refresh removed it
                val guid = withContext(Dispatchers.IO) { AmeliaBoot.ensureLocation(app) }
                if (guid == null) {
                    openApp(renew = false)
                    return
                }
                withContext(Dispatchers.IO) { AmeliaWidget.markConnecting(app) }
                if (!LauncherManager.startServiceFromToggle(this)) {
                    AmeliaWidget.clearConnecting()
                    withContext(Dispatchers.IO) { AmeliaWidget.draw(app, running = false, force = true) }
                }
            }
            AmeliaWidget.Tap.OPEN_APP -> openApp(renew = false)
            AmeliaWidget.Tap.RENEW -> openApp(renew = true)
        }
    }

    /** The app; with [renew] it opens "Продлить" (bot or site, as everywhere) right away. */
    private fun openApp(renew: Boolean) {
        val intent = Intent(this, AmeliaActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (renew) intent.putExtra(AmeliaWidget.EXTRA_RENEW, true)
        startActivity(intent)
    }
}
