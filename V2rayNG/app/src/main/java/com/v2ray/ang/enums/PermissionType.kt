package com.v2ray.ang.enums

import android.Manifest
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import com.v2ray.ang.R

/**
 * Permission types used in the app, handling API level differences.
 * Amelia.Net 1.7.0: only notifications are asked for (no QR camera; no "local network" on
 * Android 17, see docs/LOGIC.md).
 */
enum class PermissionType {
    /** Notification permission (Android 13+) */
    POST_NOTIFICATIONS {
        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        override fun getPermission(): String = Manifest.permission.POST_NOTIFICATIONS
    };

    /** Return the actual Android permission string */
    abstract fun getPermission(): String

    /** Return the string resource for the human-readable permission label. */
    @StringRes
    fun getLabelRes(): Int {
        return when (this) {
            POST_NOTIFICATIONS -> R.string.permission_notification
        }
    }
}
