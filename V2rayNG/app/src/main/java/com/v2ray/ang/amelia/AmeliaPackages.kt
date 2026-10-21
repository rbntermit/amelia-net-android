package com.v2ray.ang.amelia

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import androidx.core.net.toUri

/** PackageManager calls whose signature changed across the supported Android versions. */
object AmeliaPackages {

    /** Activities answering [intent]; visible thanks to the <queries> in the manifest (LAUNCHER, tg://). */
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

    /** Official Telegram builds first: forks may not open bot links the same way. */
    private val TELEGRAM_PREFERRED = listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.messenger.beta")

    /**
     * Package of an installed Telegram app (anything that opens tg:// links; visible thanks to the
     * tg <queries> in the manifest), or null.
     */
    fun telegram(pm: PackageManager): String? {
        val intent = Intent(Intent.ACTION_VIEW, "tg://resolve?domain=telegram".toUri())
        val pkgs = try {
            launcherActivities(pm, intent).map { it.activityInfo.packageName }.distinct()
        } catch (e: Exception) {
            emptyList()
        }
        return pickTelegram(pkgs)
    }

    /** Pure: the preferred Telegram among [installed] handlers. */
    fun pickTelegram(installed: List<String>): String? =
        TELEGRAM_PREFERRED.firstOrNull { it in installed } ?: installed.firstOrNull()
}
