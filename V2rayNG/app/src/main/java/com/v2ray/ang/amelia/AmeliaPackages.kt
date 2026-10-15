package com.v2ray.ang.amelia

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build

/** PackageManager calls whose signature changed across the supported Android versions. */
object AmeliaPackages {

    /** Activities answering [intent]; visible thanks to the LAUNCHER <queries> in the manifest. */
    fun launcherActivities(pm: PackageManager, intent: Intent): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            queryIntentActivitiesLegacy(pm, intent)
        }

    /**
     * The int-flags overload is deprecated since API 33, which added ResolveInfoFlags.
     * Remove when minSdk reaches 33.
     */
    @Suppress("DEPRECATION")
    private fun queryIntentActivitiesLegacy(pm: PackageManager, intent: Intent): List<ResolveInfo> =
        pm.queryIntentActivities(intent, 0)
}
