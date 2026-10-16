package com.v2ray.ang.amelia

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri

/**
 * Work in the background (1.3.0): Samsung, Xiaomi, Huawei and others put apps under battery
 * optimisation to sleep and the VPN goes down with them. After the first successful connection
 * the app asks once to be let off battery optimisation; settings show the state any time.
 * We are not distributed through Google Play, so REQUEST_IGNORE_BATTERY_OPTIMIZATIONS is fine.
 */
object AmeliaBattery {

    const val PREF_CARD_SHOWN = "amelia_battery_card_shown"

    fun isAllowed(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) != false

    /** Samsung has its own "sleeping apps" list on top of Android's battery optimisation. */
    fun isSamsung(): Boolean = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    /**
     * The system dialog "Allow app to always run in the background?". Falls back to the list
     * of battery optimisation settings where the dialog is missing (some vendor builds).
     */
    @SuppressLint("BatteryLife") // the app is a VPN outside Google Play; staying alive is its purpose
    fun requestIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
            .takeIf { it.resolveActivity(context.packageManager) != null }
            ?: Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
}
