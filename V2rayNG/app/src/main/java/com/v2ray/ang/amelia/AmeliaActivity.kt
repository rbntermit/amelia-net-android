package com.v2ray.ang.amelia

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
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
        lifecycleScope.launch(Dispatchers.IO) {
            AmeliaRouting.apply(applicationContext)
            AmeliaGeoUpdater.schedule(applicationContext)
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
            onLogout = viewModel::logout,
            onOpenUrl = { Utils.openUri(this, it) },
            onMessageShown = viewModel::messageShown,
            updates = UpdateCallbacks(
                onCheck = { viewModel.checkUpdate(manual = true) },
                onDownload = viewModel::downloadUpdate,
                onInstall = ::installUpdate,
            ),
        )
    }

    private fun installUpdate(apkPath: String) {
        if (!AmeliaUpdater.canInstall(this)) {
            viewModel.showMessage(R.string.amelia_update_allow_install)
            startActivity(AmeliaUpdater.unknownSourcesSettingsIntent(this))
            return
        }
        try {
            startActivity(AmeliaUpdater.installIntent(this, File(apkPath)))
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Amelia: cannot start package installer", e)
            viewModel.showMessage(R.string.amelia_update_failed)
        }
    }

    private fun toggle() {
        if (viewModel.state.value.isRunning) {
            LauncherManager.stopService(this)
            return
        }
        val prepare = VpnService.prepare(this)
        if (prepare == null) startVpn() else requestVpnPermission.launch(prepare)
    }

    private fun startVpn() {
        if (viewModel.state.value.selectedGuid.isNullOrEmpty()) {
            viewModel.showMessage(R.string.amelia_err_no_location)
            return
        }
        // Same as upstream MainActivity: Android 17 asks for local network access separately.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            checkAndRequestPermission(PermissionType.ACCESS_LOCAL_NETWORK) {}
        }
        LauncherManager.startService(this)
    }
}
