package com.v2ray.ang.amelia

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.ui.base.HelperBaseComponentActivity
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * The only screen users see: key entry, or the list of locations with a connect button.
 * Replaces upstream MainActivity as the launcher; config editing, import and export screens
 * are not reachable from here.
 */
class AmeliaActivity : HelperBaseComponentActivity() {

    private val viewModel: AmeliaViewModel by viewModels()

    private val requestVpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) startVpn()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The brand screen is always dark: light status/navigation bar icons in any system theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        lifecycleScope.launch(Dispatchers.IO) {
            AmeliaRouting.apply(applicationContext)
            AmeliaGeoUpdater.schedule(applicationContext)
            AmeliaUpdateWorker.schedule(applicationContext)
            AmeliaProbe.schedule(applicationContext)
        }
        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {}
        if (savedInstanceState == null) handleDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    /** amelia://key/<key> — sent by the bot through an https redirect. */
    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (!data.scheme.equals(AmeliaConfig.DEEP_LINK_SCHEME, ignoreCase = true)) return
        if (!data.host.equals(AmeliaConfig.DEEP_LINK_HOST_KEY, ignoreCase = true)) return
        val key = data.pathSegments.firstOrNull() ?: return
        viewModel.submitKey(key)
    }

    @Composable
    override fun ScreenContent() {
        BackHandler { moveTaskToBack(false) }
        val state by viewModel.state.collectAsStateWithLifecycle()
        AmeliaApp(
            state = state,
            onSubmitKey = viewModel::submitKey,
            onToggle = ::toggle,
            onSelect = viewModel::select,
            onRefresh = { viewModel.refresh() },
            onRecheck = viewModel::recheck,
            onPingAll = viewModel::pingAll,
            onLogout = viewModel::logout,
            onOpenUrl = { Utils.openUri(this, it) },
            onMessageShown = viewModel::messageShown,
            updates = UpdateCallbacks(
                onCheck = { viewModel.checkUpdate(manual = true) },
                onDownload = viewModel::downloadUpdate,
                onInstall = ::installUpdate,
            ),
            settings = SettingsCallbacks(
                onOpen = viewModel::loadSettingsInfo,
                onCheckRoute = viewModel::checkRoute,
                onRefreshSubscription = { viewModel.refresh() },
            ),
            extra = ExtraCallbacks(
                onOpenAbout = viewModel::loadAbout,
                onCopySupport = { viewModel.supportText()?.let { copy(it) } },
                onCopyText = ::copy,
                onStartReport = viewModel::startReport,
                onSendReport = viewModel::sendReport,
                onCloseReport = viewModel::closeReport,
                onBypassRu = viewModel::setBypassRu,
                onOpenApps = viewModel::loadApps,
                onAppBypass = viewModel::setAppBypass,
                onAppsClosed = viewModel::appsClosed,
                onProbe = viewModel::setProbeEnabled,
                onDismissAnnounce = viewModel::dismissAnnounce,
            ),
        )
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }

    /** Puts [text] on the clipboard and says so (support text, or a report the server did not take). */
    private fun copy(text: String) {
        val cm = getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.amelia_clip_label), text))
        viewModel.showMessage(R.string.amelia_about_copied)
    }

    private fun installUpdate(apkPath: String) {
        if (!AmeliaUpdater.canInstall(this)) {
            viewModel.showMessage(R.string.amelia_update_allow_install)
            startActivity(AmeliaUpdater.unknownSourcesSettingsIntent(this))
            return
        }
        val apk = File(apkPath)
        val vpnRunning = viewModel.state.value.isRunning
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Android 12+: installs without the confirmation screen; the app restarts updated
                AmeliaInstaller.install(applicationContext, apk, vpnRunning)
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Amelia: session install failed, falling back to the system installer", e)
                kotlinx.coroutines.withContext(Dispatchers.Main) { installViaSystemScreen(apk) }
            }
        }
    }

    /** Fallback: the classic "Install?" screen of the system package installer. */
    private fun installViaSystemScreen(apk: File) {
        try {
            startActivity(AmeliaUpdater.installIntent(this, apk))
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Amelia: cannot start package installer", e)
            viewModel.showMessage(R.string.amelia_update_failed)
        }
    }

    private fun toggle() {
        val s = viewModel.state.value
        // During a switch the daemon is briefly stopped; the button still means "disconnect".
        if (s.isRunning || s.phase != ConnPhase.OFF) {
            viewModel.onUserDisconnect()
            LauncherManager.stopService(this)
            return
        }
        if (s.updateRequired) {
            viewModel.showMessage(R.string.amelia_update_required_block)
            return
        }
        val prepare = VpnService.prepare(this)
        if (prepare == null) startVpn() else requestVpnPermission.launch(prepare)
    }

    private fun startVpn() {
        if (viewModel.state.value.updateRequired) {
            viewModel.showMessage(R.string.amelia_update_required_block)
            return
        }
        if (viewModel.state.value.selectedGuid.isNullOrEmpty()) {
            viewModel.showMessage(R.string.amelia_err_no_location)
            return
        }
        // Same as upstream MainActivity: Android 17 asks for local network access separately.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            checkAndRequestPermission(PermissionType.ACCESS_LOCAL_NETWORK) {}
        }
        viewModel.onUserConnect()
        LauncherManager.startService(this)
    }
}
