package com.v2ray.ang.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Network
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.StrictMode
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.LOOPBACK
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.amelia.AmeliaCrash
import com.v2ray.ang.amelia.AmeliaLocalProxy
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.contracts.Tun2SocksControl
import com.v2ray.ang.core.CoreLifecycleRules
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.lang.ref.SoftReference
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("VpnServicePolicy")
class CoreVpnService : VpnService(), ServiceControl {
    /** The VPN interface; set by the setup job, closed by the stop (both under [resourceLock]). */
    @Volatile
    private var mInterface: ParcelFileDescriptor? = null
    @Volatile
    private var isRunning = false
    private var tun2SocksService: Tun2SocksControl? = null
    private val isStartingLock = AtomicBoolean(false)

    /**
     * Amelia.Net 1.7.0: the VPN setup (interface, tun2socks, config build with DNS, core start)
     * runs here, off the main thread: a slow DNS answer no longer freezes the service (ANR).
     * Cancelled in onDestroy.
     */
    private val setupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var setupJob: Job? = null

    /** 1.7.0: a stop was asked; the setup job checks it between its steps. */
    @Volatile
    private var stopRequested = false

    /** 1.7.0: the interface and tun2socks are created and released under this lock (setup vs stop). */
    private val resourceLock = Any()

    override fun onCreate() {
        super.onCreate()
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service created")
        val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)
        CoreServiceManager.serviceControl = SoftReference(this)
    }

    override fun onRevoke() {
        LogUtil.w(AppConfig.TAG, "StartCore-VPN: Permission revoked")
        stopAllService()
    }

//    override fun onLowMemory() {
//        stopV2Ray()
//        super.onLowMemory()
//    }

    override fun onDestroy() {
        super.onDestroy()
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service destroyed")

        // 1.7.0: no setup outlives the service
        stopRequested = true
        setupScope.cancel()

        // Ensure VPN interface is properly closed when the service is destroyed without
        // going through stopAllService() (e.g. when killed unexpectedly). isRunning is
        // set to false at the start of stopAllService(), so this guard prevents a double-close.
        if (isRunning) {
            synchronized(resourceLock) { closeInterface("onDestroy") }
        }

        unlockStart()
        NotificationManager.cancelNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationManager.ensureForeground()
        // Always-on VPN restarts from OS deliver intent.action == SERVICE_INTERFACE or null intent;
        // they take the same path (a killed process starts with a free lock).
        val isSystemVpnStart = intent == null || intent.action == SERVICE_INTERFACE
        // 1.7.0: a second start while the core runs keeps it (one core, one interface); a start
        // while another one sets up leaves that one to decide. The lock is freed after every
        // setup, so after a successful start the next command is answered sticky as well.
        val running = CoreServiceManager.isRunning() && !stopRequested && !CoreServiceManager.isStopping()
        when (CoreLifecycleRules.startCommand(running, startLockFree = !running && tryLockStart())) {
            CoreLifecycleRules.StartCommand.ALREADY_RUNNING -> {
                LogUtil.i(AppConfig.TAG, "StartCore-VPN: start command while running, kept, systemVpnStart=$isSystemVpnStart")
                return START_STICKY
            }
            CoreLifecycleRules.StartCommand.IN_PROGRESS -> {
                LogUtil.w(AppConfig.TAG, "StartCore-VPN: Start already in progress")
                return START_STICKY
            }
            CoreLifecycleRules.StartCommand.START -> Unit
        }
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service command received, systemVpnStart=$isSystemVpnStart")
        stopRequested = false
        CoreServiceManager.beginStart()
        // Sticky right away: the setup runs on [setupScope]; if it fails, the service stops itself
        // (stopSelf), and Android does not restart a service that stopped itself.
        setupJob = setupScope.launch { setupAndStart() }
        return START_STICKY
    }

    /** 1.7.0: the whole start, off the main thread; the start lock is freed at the end either way. */
    private fun setupAndStart() {
        try {
            // the local proxy's port before tun2socks is configured with it
            AmeliaLocalProxy.ensurePortFree()
            val ready = synchronized(resourceLock) { !stopRequested && setupVpnService() }
            if (!ready) {
                if (!stopRequested) {
                    LogUtil.e(AppConfig.TAG, "StartCore-VPN: setup failed, stopping the service")
                    stopAllService()
                }
                return
            }
            startService()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: setup failed", e)
            AmeliaCrash.nonFatal("vpn_service", e)
            if (!stopRequested) stopAllService()
        } finally {
            unlockStart()
        }
    }

    override fun getService(): Service {
        return this
    }

    override fun startService() {
        val vpnInterface = mInterface
        if (vpnInterface == null) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Interface not initialized")
            return
        }
        if (!CoreServiceManager.startCoreLoop(vpnInterface)) {
            // 1.7.0: a stop asked meanwhile already tears everything down
            if (stopRequested) return
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to start core loop")
            stopAllService()
            return
        }
        // 1.7.0: upstream's root LAN sharing is removed with the root mode
    }

    override fun stopService() {
        stopAllService(true)
    }

    override fun vpnProtect(socket: Int): Boolean {
        return protect(socket)
    }

    override fun setUnderlyingNetworks(networks: Array<Network>?): Boolean {
        return super<VpnService>.setUnderlyingNetworks(networks)
    }

    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let(AppLocaleManager::localizedContext)
        super.attachBaseContext(context)
    }

    /**
     * Sets up the VPN service.
     * Prepares the VPN and configures it if preparation is successful.
     */
    private fun setupVpnService(): Boolean {
        val prepare = prepare(this)
        if (prepare != null) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Permission not granted")
            return false
        }

        if (configureVpnService() != true) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Configuration failed")
            return false
        }

        runTun2socks()
        return true
    }

    /**
     * Configures the VPN service.
     * @return True if the VPN service was configured successfully, false otherwise.
     */
    private fun configureVpnService(): Boolean {
        val builder = Builder()

        // Configure network settings (addresses, routing and DNS)
        configureNetworkSettings(builder)

        // Configure app-specific settings (session name and per-app proxy)
        configurePerAppProxy(builder)

        // Close the old interface since the parameters have been changed
        try {
            mInterface?.close()
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "Failed to close old interface", e)
        }

        // Configure platform-specific features
        configurePlatformFeatures(builder)

        // Create a new interface using the builder and save the parameters
        try {
            mInterface = builder.establish()!!
            isRunning = true
            return true
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to establish VPN interface", e)
            // Amelia.Net: crash report (non-fatal), sent with the next app start
            AmeliaCrash.nonFatal("vpn_service", e)
            // 1.7.0: the caller (setupAndStart) stops the service
        }
        return false
    }

    /**
     * Configures the basic network settings for the VPN.
     * This includes IP addresses, routing rules, and DNS servers.
     *
     * @param builder The VPN Builder to configure
     */
    private fun configureNetworkSettings(builder: Builder) {
        val vpnConfig = SettingsManager.getCurrentVpnInterfaceAddressConfig()
        val bypassLan = SettingsManager.routingRulesetsBypassLan()

        // Configure IPv4 settings
        builder.setMtu(SettingsManager.getVpnMtu())
        builder.addAddress(vpnConfig.ipv4Client, 30)

        // Configure routing rules
        if (bypassLan) {
            AppConfig.ROUTED_IP_LIST.forEach {
                val addr = it.split('/')
                builder.addRoute(addr[0], addr[1].toInt())
            }
        } else {
            builder.addRoute("0.0.0.0", 0)
        }

        // Configure IPv6 if enabled
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_IPV6_ENABLED) == true) {
            builder.addAddress(vpnConfig.ipv6Client, 126)
            if (bypassLan) {
                builder.addRoute("2000::", 3) // Currently only 1/8 of total IPv6 is in use
                builder.addRoute("fc00::", 18) // Xray-core default FakeIPv6 Pool
            } else {
                builder.addRoute("::", 0)
            }
        }

        // Configure DNS servers
        //if (MmkvManager.decodeSettingsBool(AppConfig.PREF_LOCAL_DNS_ENABLED) == true) {
        //  builder.addDnsServer(PRIVATE_VLAN4_ROUTER)
        //} else {
        SettingsManager.getVpnDnsServers().forEach {
            if (Utils.isPureIpAddress(it)) {
                builder.addDnsServer(it)
            }
        }

        //builder.setSession(V2RayServiceManager.getRunningServerName())
    }

    /**
     * Configures platform-specific VPN features for different Android versions.
     *
     * @param builder The VPN Builder to configure
     */
    private fun configurePlatformFeatures(builder: Builder) {
        // Android Q (API 29) and above: Configure metering and HTTP proxy
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_APPEND_HTTP_PROXY)) {
                builder.setHttpProxy(ProxyInfo.buildDirectProxy(LOOPBACK, SettingsManager.getHttpPort()))
            }
        }
    }

    /**
     * Configures per-app proxy rules for the VPN builder.
     *
     * - If per-app proxy is not enabled, disallow the VPN service's own package.
     * - If no apps are selected, disallow the VPN service's own package.
     * - If bypass mode is enabled, disallow all selected apps (including self).
     * - If proxy mode is enabled, only allow the selected apps (excluding self).
     *
     * @param builder The VPN Builder to configure.
     */
    private fun configurePerAppProxy(builder: Builder) {
        val selfPackageName = BuildConfig.APPLICATION_ID

        // If per-app proxy is not enabled, disallow the VPN service's own package and return
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY) == false) {
            builder.addDisallowedApplication(selfPackageName)
            return
        }

        // If no apps are selected, disallow the VPN service's own package and return
        val apps = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET)
        if (apps.isNullOrEmpty()) {
            builder.addDisallowedApplication(selfPackageName)
            return
        }

        val bypassApps = MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS)
        // Handle the VPN service's own package according to the mode
        if (bypassApps) apps.add(selfPackageName) else apps.remove(selfPackageName)

        apps.forEach {
            try {
                if (bypassApps) {
                    // In bypass mode, disallow the selected apps
                    builder.addDisallowedApplication(it)
                } else {
                    // In proxy mode, only allow the selected apps
                    builder.addAllowedApplication(it)
                }
            } catch (e: PackageManager.NameNotFoundException) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to configure app", e)
            }
        }
    }

    /**
     * Runs the tun2socks process.
     * Starts the tun2socks process with the appropriate parameters.
     */
    private fun runTun2socks() {
        val vpnInterface = mInterface ?: return
        if (SettingsManager.isUsingHevTun()) {
            tun2SocksService = TProxyService(
                context = applicationContext,
                vpnInterface = vpnInterface,
                isRunningProvider = { isRunning },
                restartCallback = { runTun2socks() }
            )
        } else {
            tun2SocksService = null
        }

        tun2SocksService?.startTun2Socks()
    }

    private fun stopAllService(isForced: Boolean = true) {
//        val configName = defaultDPreference.getPrefString(PREF_CURR_CONFIG_GUID, "")
//        val emptyInfo = VpnNetworkInfo()
//        val info = loadVpnNetworkInfo(configName, emptyInfo)!! + (lastNetworkInfo ?: emptyInfo)
//        saveVpnNetworkInfo(configName, info)
        // 1.7.0: mark the stop first: a setup in flight starts nothing more (it checks the mark
        // between steps, and the core manager's lock and "stopping" mark stop a core start)
        stopRequested = true
        isRunning = false

        synchronized(resourceLock) {
            tun2SocksService?.stopTun2Socks()
            tun2SocksService = null

            CoreServiceManager.stopCoreLoop()

            if (isForced) {
                //stopSelf has to be called ahead of mInterface.close(). otherwise v2ray core cannot be stooped
                //It's strage but true.
                //This can be verified by putting stopself() behind and call stopLoop and startLoop
                //in a row for several times. You will find that later created v2ray core report port in use
                //which means the first v2ray core somehow failed to stop and release the port.
                stopSelf()

                // Add a small delay to allow the async core stop operation to complete
                // before closing the VPN interface, preventing a race condition that can
                // leave the VPN icon in the status bar after stopping the service.
                try {
                    Thread.sleep(100)
                } catch (e: InterruptedException) {
                    LogUtil.w(AppConfig.TAG, "StartCore-VPN: Sleep interrupted", e)
                }

                closeInterface("stop")
            }
        }
        unlockStart()
    }

    /** Closes the VPN interface once; later calls do nothing. Call under [resourceLock]. */
    private fun closeInterface(phase: String) {
        val vpnInterface = mInterface ?: return
        mInterface = null
        try {
            vpnInterface.close()
            LogUtil.i(AppConfig.TAG, "StartCore-VPN: VPN interface closed ($phase)")
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to close interface ($phase)", e)
        }
    }

    fun tryLockStart(): Boolean {
        LogUtil.w(AppConfig.TAG, "StartCore-VPN: tryLockStart: ${isStartingLock.get()}")
        return isStartingLock.compareAndSet(false, true)
    }

    fun unlockStart() {
        isStartingLock.set(false)
        LogUtil.w(AppConfig.TAG, "StartCore-VPN: unlockStart")
    }
}
