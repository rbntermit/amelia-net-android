package com.v2ray.ang.amelia

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.provider.Settings
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil

/**
 * 1.5.4: "Включать VPN при запуске телефона" (off by default) and the location the VPN starts
 * on when Android starts it by itself (boot, Always-on VPN). Upstream's BootReceiver and its
 * "start on boot" setting (never shown in this app) are replaced by this.
 */
object AmeliaBoot {

    const val PREF_ENABLED = "amelia_start_on_boot"
    private const val PREF_LAST_BOOT = "amelia_boot_handled"

    /** Boot broadcasts we act on; QUICKBOOT_POWERON: some HTC/Xiaomi/Huawei firmwares on fast reboot. */
    val BOOT_ACTIONS = setOf(
        Intent.ACTION_BOOT_COMPLETED,
        "android.intent.action.QUICKBOOT_POWERON",
        "com.htc.intent.action.QUICKBOOT_POWERON",
    )

    // ---- pure logic (unit-tested) ---------------------------------------------------------

    enum class Decision {
        /** Setting off, no key or locations, already handled this boot: nothing. */
        NOTHING,
        /** Android has not let the app make a VPN yet: nothing now, a line in the app. */
        NEEDS_PERMISSION,
        /** The subscription ended: no connecting at boot. */
        EXPIRED,
        /** A newer version is required (minVersionCode): the connect button refuses too. */
        UPDATE_REQUIRED,
        START,
    }

    fun decide(
        enabled: Boolean,
        hasLocations: Boolean,
        vpnPermission: Boolean,
        subscriptionActive: Boolean,
        updateRequired: Boolean,
        alreadyHandled: Boolean,
    ): Decision = when {
        !enabled || !hasLocations || alreadyHandled -> Decision.NOTHING
        !vpnPermission -> Decision.NEEDS_PERMISSION
        !subscriptionActive -> Decision.EXPIRED
        updateRequired -> Decision.UPDATE_REQUIRED
        else -> Decision.START
    }

    /**
     * The location to start on when Android starts the VPN without the app (boot, Always-on):
     * the last one if it still exists (a subscription refresh can remove it), otherwise the best
     * one, as "⚡ Подключить лучший".
     */
    fun chooseLocation(selected: String?, existing: Collection<String>, best: String?): String? =
        selected?.takeIf { it in existing } ?: best?.takeIf { it in existing } ?: existing.firstOrNull()

    /** "Чтобы VPN включался сам, один раз нажмите «Подключить»" on the main screen. */
    fun showPermissionHint(enabled: Boolean, vpnPermission: Boolean): Boolean = enabled && !vpnPermission

    // ---- Android ----------------------------------------------------------------------------

    fun isEnabled(): Boolean = MmkvManager.decodeSettingsBool(PREF_ENABLED, false)

    fun setEnabled(on: Boolean) = MmkvManager.encodeSettings(PREF_ENABLED, on)

    fun hasVpnPermission(context: Context): Boolean = runCatching { VpnService.prepare(context) == null }.getOrDefault(false)

    /** Android's boot counter; 0 where it cannot be read (then every boot broadcast counts). */
    private fun bootCount(context: Context): Int =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(0)

    /**
     * Selects the location for a start without the app (it may be gone after a subscription
     * refresh). Blocking (disk). @return the location's guid, or null when there is none.
     */
    fun ensureLocation(context: Context): String? {
        val existing = MmkvManager.decodeServerList(AmeliaConfig.SUBSCRIPTION_ID)
        val selected = MmkvManager.getSelectServer()
        if (selected != null && selected in existing) return selected
        val best = runCatching { AmeliaQueue.current(context).firstOrNull() }.getOrNull()
        val guid = chooseLocation(selected, existing, best) ?: return null
        LogUtil.i(AppConfig.TAG, "Amelia: no usable selected location, starting on the best one")
        MmkvManager.setSelectServer(guid)
        AmeliaManual.markAutomatic()
        return guid
    }

    /** BOOT_COMPLETED (and QUICKBOOT): start the VPN when the person asked for it. Blocking. */
    fun onBoot(context: Context, action: String) {
        if (action !in BOOT_ACTIONS) return
        val count = bootCount(context)
        val handled = count > 0 && MmkvManager.decodeSettingsString(PREF_LAST_BOOT) == count.toString()
        val decision = decide(
            enabled = isEnabled(),
            hasLocations = AmeliaManager.hasKey() && MmkvManager.decodeServerList(AmeliaConfig.SUBSCRIPTION_ID).isNotEmpty(),
            vpnPermission = hasVpnPermission(context),
            subscriptionActive = AmeliaPolicy.subscriptionActive(AmeliaManager.getSubInfo(), System.currentTimeMillis()),
            updateRequired = AmeliaUpdater.isRequired(AmeliaUpdater.cached()),
            alreadyHandled = handled,
        )
        LogUtil.i(AppConfig.TAG, "Amelia: boot ($action, #$count): $decision")
        if (decision == Decision.NOTHING) return
        if (count > 0) MmkvManager.encodeSettings(PREF_LAST_BOOT, count.toString())
        if (decision != Decision.START) return
        if (ensureLocation(context) == null) return
        AmeliaJournal.add("Телефон включился — подключаю VPN")
        // BOOT_COMPLETED lets a just-booted app start a foreground service (also on Android 14+;
        // our VPN service type is specialUse, not on Android 15's boot-start block list).
        // LauncherManager logs a refusal; it is also reported, so it never fails silently.
        if (!LauncherManager.startServiceFromToggle(context)) {
            LogUtil.e(AppConfig.TAG, "Amelia: VPN start at boot was refused")
            AmeliaCrash.nonFatal("boot_start", null, "VPN start at boot refused ($action)")
        }
    }
}

/** 1.5.4: what a tap on the quick settings tile does. */
object AmeliaTile {

    enum class Action { START, STOP, OPEN_APP }

    /**
     * The VPN is on: off. Otherwise start like the connect button, unless only the app can
     * help: no VPN permission yet (Android asks in the app), no location, a required update.
     */
    fun onTap(running: Boolean, vpnPermission: Boolean, hasLocation: Boolean, updateRequired: Boolean): Action = when {
        running -> Action.STOP
        !vpnPermission || !hasLocation || updateRequired -> Action.OPEN_APP
        else -> Action.START
    }

    /**
     * 1.7.0: the state line under the location's name while the VPN is on (the daemon's phase,
     * the same MSG_HEALTH the widget uses). Null: nothing known yet, the name alone.
     */
    fun stateText(phase: String?): Int? = when (phase) {
        AmeliaDaemon.Phase.CONNECTED.name -> R.string.amelia_phase_connected
        AmeliaDaemon.Phase.CHECKING.name, AmeliaDaemon.Phase.SWITCHING.name -> R.string.amelia_widget_connecting
        AmeliaDaemon.Phase.ALL_FAILED.name, AmeliaDaemon.Phase.MANUAL_FAILED.name -> R.string.amelia_widget_not_working
        AmeliaDaemon.Phase.NO_INTERNET.name -> R.string.amelia_widget_no_internet
        AmeliaDaemon.Phase.WHITELIST.name -> R.string.amelia_widget_whitelist
        else -> null
    }

    /** 1.7.0: below Android 10 a tile has no second line: a problem then replaces the name. */
    fun isProblem(phase: String?): Boolean = phase in setOf(
        AmeliaDaemon.Phase.ALL_FAILED.name, AmeliaDaemon.Phase.MANUAL_FAILED.name,
        AmeliaDaemon.Phase.NO_INTERNET.name, AmeliaDaemon.Phase.WHITELIST.name,
    )
}
