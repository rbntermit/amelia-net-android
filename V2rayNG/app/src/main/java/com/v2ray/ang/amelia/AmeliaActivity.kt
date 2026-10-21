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
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
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
            // 1.5.1: the update went through: no "не установлено" left over from it
            AmeliaInstaller.resetIfUpdated(applicationContext, BuildConfig.VERSION_CODE)
            AmeliaRouting.apply(applicationContext)
            AmeliaGeoUpdater.schedule(applicationContext)
            AmeliaUpdateWorker.schedule(applicationContext)
            AmeliaProbe.schedule(applicationContext)
        }
        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {}
        if (savedInstanceState == null) {
            handleDeepLink(intent)
            handleInstallRequest(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
        handleInstallRequest(intent)
    }

    /** From the notification "Обновление … готово — установить": one tap installs. */
    private fun handleInstallRequest(intent: Intent?) {
        if (intent?.getBooleanExtra(AmeliaUpdateWorker.EXTRA_INSTALL, false) != true) return
        intent.removeExtra(AmeliaUpdateWorker.EXTRA_INSTALL)
        viewModel.installWhenReady(::installUpdate)
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
                onAppsPreset = viewModel::applyPreset,
                onTelegramProxy = ::openTelegramProxy,
                onOpenApps = viewModel::loadApps,
                onAppBypass = viewModel::setAppBypass,
                onAppsClosed = viewModel::appsClosed,
                onProbe = viewModel::setProbeEnabled,
                onDismissAnnounce = viewModel::dismissAnnounce,
                onConnectBest = { viewModel.connectBest(::toggle) },
                onMeasureSpeed = viewModel::measureSpeed,
                onAllowBackground = ::openBatterySettings,
                onBatteryCardDone = viewModel::batteryCardDone,
                onCrashReports = viewModel::setCrashEnabled,
                onAutoMode = viewModel::setAutoMode,
                onOpenJournal = viewModel::loadJournal,
                onCopyJournal = { copy(viewModel.journalText()) },
                onInstallPermissionOpen = {
                    runCatching { startActivity(AmeliaUpdater.unknownSourcesSettingsIntent(this)) }
                        .onFailure { LogUtil.e(AppConfig.TAG, "Amelia: cannot open the install permission", it) }
                },
                onInstallPermissionDismiss = viewModel::installPermissionDismissed,
                onInstallRetry = {
                    viewModel.state.value.updateApkPath?.let { path ->
                        // 1.5.1: never a second install next to a running one
                        if (viewModel.beginInstall()) {
                            lifecycleScope.launch(Dispatchers.IO) {
                                val busy = AmeliaInstaller.isBusy(applicationContext)
                                kotlinx.coroutines.withContext(Dispatchers.Main) {
                                    if (busy) {
                                        viewModel.installRunning(alreadyRunning = true)
                                    } else {
                                        viewModel.installNotStarted()
                                        installViaSystemScreen(File(path))
                                    }
                                }
                            }
                        }
                    } ?: viewModel.downloadUpdate()
                },
                onInstallHelpDone = viewModel::installHelpDone,
                onOpenSecurity = ::openSecuritySettings,
                onDownloadInBrowser = { viewModel.manualDownloadUrl()?.let { Utils.openUri(this, it) } },
                onGetInTelegram = { Utils.openUri(this, AmeliaConfig.botUrl(AmeliaConfig.BotScreen.APP)) },
            ),
        )
    }

    /**
     * Where the phone's install protection is switched off: Samsung Auto Blocker
     * (One UI 6+), otherwise the general security settings.
     */
    private fun openSecuritySettings() {
        val intents = listOf(
            Intent().setClassName("com.android.settings", "com.android.settings.Settings\$AutoBlockerSettingsActivity"),
            Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS),
            Intent(android.provider.Settings.ACTION_SETTINGS),
        )
        for (i in intents) {
            if (runCatching { startActivity(i) }.isSuccess) return
        }
    }

    /**
     * "📨 Прокси для Telegram" (1.5.0): the bot sends the person's own proxy link. In Telegram when
     * it is installed (the https link given to it directly, so no browser or chooser comes up);
     * otherwise the link opens in the browser and we say to install Telegram.
     */
    private fun openTelegramProxy() {
        val url = AmeliaConfig.botUrl(AmeliaConfig.BotScreen.TGPROXY)
        val tg = AmeliaPackages.telegram(packageManager)
        if (tg != null) {
            val opened = runCatching { startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).setPackage(tg)) }
                .recoverCatching {
                    // a Telegram fork without t.me links: its own scheme
                    startActivity(
                        Intent(Intent.ACTION_VIEW, "tg://resolve?domain=${AmeliaConfig.BOT_USERNAME.removePrefix("@")}&start=${AmeliaConfig.BotScreen.TGPROXY.payload}".toUri())
                            .setPackage(tg),
                    )
                }
            if (opened.isSuccess) return
            opened.exceptionOrNull()?.let { LogUtil.w(AppConfig.TAG, "Amelia: Telegram did not open the proxy link", it) }
        }
        Utils.openUri(this, url)
        viewModel.showMessage(R.string.amelia_tgproxy_install)
    }

    /** "Разрешить работу в фоне": the system asks to let the app off battery optimisation. */
    private fun openBatterySettings() {
        try {
            startActivity(AmeliaBattery.requestIntent(this))
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Amelia: cannot open the battery settings", e)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
        // back from "allow installing apps": continue the install the person started
        viewModel.pendingInstall(AmeliaUpdater.canInstall(this))?.let { installUpdate(it) }
    }

    /** Puts [text] on the clipboard and says so (support text, or a report the server did not take). */
    private fun copy(text: String) {
        val cm = getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.amelia_clip_label), text))
        viewModel.showMessage(R.string.amelia_about_copied)
    }

    private fun installUpdate(apkPath: String) {
        // 1.4.0: the permission first, with an explanation; installing continues on return (onResume)
        if (!AmeliaUpdater.canInstall(this)) {
            viewModel.askInstallPermission(apkPath)
            return
        }
        // 1.5.1: the button, the update dialog, the notification and the return from the
        // permission screen all come here; only the first one starts an install
        if (!viewModel.beginInstall()) return
        val apk = File(apkPath)
        val vpnRunning = viewModel.state.value.isRunning
        val expected = viewModel.state.value.update?.versionCode ?: 0
        lifecycleScope.launch(Dispatchers.IO) {
            // ours, signed with our key, the promised version: otherwise say so instead of a system error
            val check = AmeliaApkCheck.check(applicationContext, apk, expected)
            when (check.verdict) {
                AmeliaApkCheck.Verdict.BAD_SIGNATURE -> {
                    apk.delete()
                    viewModel.installNotStarted()
                    viewModel.apkRejected(getString(R.string.amelia_install_bad_signature))
                    return@launch
                }
                AmeliaApkCheck.Verdict.BAD_VERSION -> {
                    apk.delete()
                    viewModel.installNotStarted()
                    viewModel.apkRejected(getString(R.string.amelia_install_bad_version, check.versionName ?: "?"))
                    return@launch
                }
                else -> Unit // unreadable: the system installer will tell
            }
            try {
                // Android 12+: installs without the confirmation screen; the app restarts updated
                val start = AmeliaInstaller.install(applicationContext, apk, vpnRunning, expected)
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    viewModel.installRunning(alreadyRunning = start == AmeliaInstaller.Start.ALREADY_RUNNING)
                }
            } catch (e: Exception) {
                val busy = AmeliaInstaller.isBusy(applicationContext)
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    if (busy) {
                        // another session is on its way: wait for it, no second install
                        LogUtil.w(AppConfig.TAG, "Amelia: session install failed while another one runs", e)
                        viewModel.installRunning(alreadyRunning = true)
                    } else {
                        LogUtil.e(AppConfig.TAG, "Amelia: session install failed, falling back to the system installer", e)
                        AmeliaCrash.nonFatal("update", e)
                        viewModel.installNotStarted()
                        installViaSystemScreen(apk)
                    }
                }
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
