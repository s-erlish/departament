package com.v2ray.ang.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.StrictMode
import androidx.annotation.RequiresApi
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.LOOPBACK
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.contracts.Tun2SocksControl
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.MyContextWrapper
import com.v2ray.ang.util.Utils
import java.lang.ref.SoftReference

@SuppressLint("VpnServicePolicy")
class CoreVpnService : VpnService(), ServiceControl {
    private lateinit var mInterface: ParcelFileDescriptor
    private var isRunning = false
    private var tun2SocksService: Tun2SocksControl? = null

    /**destroy
     * Unfortunately registerDefaultNetworkCallback is going to return our VPN interface: https://android.googlesource.com/platform/frameworks/base/+/dda156ab0c5d66ad82bdcf76cda07cbc0a9c8a2e
     *
     * This makes doing a requestNetwork with REQUEST necessary so that we don't get ALL possible networks that
     * satisfies default network capabilities but only THE default network. Unfortunately we need to have
     * android.permission.CHANGE_NETWORK_STATE to be able to call requestNetwork.
     *
     * Source: https://android.googlesource.com/platform/frameworks/base/+/2df4c7d/services/core/java/com/android/server/ConnectivityService.java#887
     */
    @delegate:RequiresApi(Build.VERSION_CODES.P)
    private val defaultNetworkRequest by lazy {
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
    }

    private val connectivity by lazy { getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager }

    @delegate:RequiresApi(Build.VERSION_CODES.P)
    private val defaultNetworkCallback by lazy {
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                setUnderlyingNetworks(arrayOf(network))
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                // it's a good idea to refresh capabilities
                setUnderlyingNetworks(arrayOf(network))
            }

            override fun onLost(network: Network) {
                setUnderlyingNetworks(null)
            }
        }
    }

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

    override fun onDestroy() {
        super.onDestroy()
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service destroyed")

        // Ensure VPN interface is properly closed when the service is destroyed without
        // going through stopAllService() (e.g. when killed unexpectedly). isRunning is
        // set to false at the start of stopAllService(), so this guard prevents a double-close.
        if (isRunning) {
            try {
                if (::mInterface.isInitialized) {
                    mInterface.close()
                    LogUtil.i(AppConfig.TAG, "StartCore-VPN: VPN interface closed in onDestroy")
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to close interface in onDestroy", e)
            }
        }

        NotificationManager.cancelNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service command received")
        try {
            // A STICKY RESTART OF A PAUSED SERVICE IS NOT A REQUEST TO CONNECT.
            //
            // `START_STICKY` is what keeps a real session alive across a kill, and it hands back a
            // `null` intent — which, in a fresh process, looks exactly like the restart of a
            // tunnel that WAS running and is supposed to come back up. A pause that the system
            // killed would therefore have turned the VPN back on by itself, with nobody asking.
            // The flag is on disk for precisely this (see CoreServiceManager.isPaused): re-post
            // the paused row from the selected server, re-arm its «Остановить», and stay down.
            if (intent == null && CoreServiceManager.isPaused()) {
                LogUtil.i(AppConfig.TAG, "StartCore-VPN: Restarted while paused; the tunnel stays down")
                val paused = MmkvManager.getSelectServer()?.let { MmkvManager.decodeServerConfig(it) }
                if (!NotificationManager.showNotification(paused)) {
                    LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to enter foreground while paused; stopping")
                    stopAllService()
                    return START_NOT_STICKY
                }
                CoreServiceManager.ensureCommandReceiver()
                return START_STICKY
            }

            // EVERY OTHER START COMMAND IS A CONNECT, «Возобновить» in the shade included — it is
            // an Intent to this class and nothing else (@see AppConfig.ACTION_RESUME_SERVICE), so
            // it needs no branch of its own here; the ordinary start path IS the way back. The
            // pause ends before the row is re-drawn below, so the shade says «Подключение…» from
            // the first frame of the reconnect rather than still offering to resume it.
            CoreServiceManager.clearPaused()

            // Promote to foreground first (mandatory within ~5s). showNotification is hardened to
            // never throw and returns false if the system refused the foreground promotion.
            if (!NotificationManager.showNotification(null)) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to enter foreground; aborting start")
                reportStartFailure("Foreground service could not start")
                stopAllService()
                return START_NOT_STICKY
            }
            // A START THAT ARRIVES ON A LIVE TUNNEL IS A DUPLICATE, NOT A NEW SESSION — and until
            // this guard it was the fastest way to lose a working connection.
            //
            // The shell decides whether to start or stop from `MainViewModel.isRunning`, and that
            // value is OPTIMISTIC: `startListenBroadcast()` publishes `false` on every Activity
            // start, before the daemon has answered the registration handshake. Press the connect
            // object in that window — the app has just been opened, on a phone where the tunnel
            // has been up since yesterday — and a start is issued against a core that is already
            // running. What followed was: `configureVpnService()` closed the live interface and
            // established a new one, `startCoreLoop` then refused because the core was up, and the
            // refusal was handled by `stopAllService()`. The tunnel went down and the screen said
            // «Не удалось подключиться».
            //
            // Nothing here has anything to do: the tunnel the caller wants IS the tunnel that is
            // running. So say so, and leave it alone. Checked after the foreground promotion (which
            // now re-posts the live notification rather than blanking it) because the promotion is
            // what the 5-second `startForegroundService` deadline demands, whatever comes next.
            if (CoreServiceManager.isRunning()) {
                LogUtil.w(AppConfig.TAG, "StartCore-VPN: A core is already running; keeping the live tunnel")
                MessageUtil.sendMsg2UI(this, AppConfig.MSG_STATE_RUNNING, "")
                return START_STICKY
            }

            // THE SETUP'S ANSWER IS HONOURED, and both halves of that matter.
            //
            // It used to be ignored, so a setup that bailed still fell into startService() — and
            // that guard is `::mInterface.isInitialized`, which stays true for the rest of the
            // process once a session has ever been established. So the second connect after a
            // revoked VPN permission handed the core the PREVIOUS session's file descriptor, which
            // stopAllService had already closed.
            //
            // The other half is that a refusal announced nothing. `prepare() != null` — the system
            // permission has been withdrawn — called stopSelf() and stopped, with no state
            // broadcast at all, so the screen sat on «Подключение…» until the 20-second watchdog
            // gave up on it. A start that cannot happen says so now, in the same breath as every
            // other failed start.
            if (!setupVpnService()) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: Setup failed; not starting the core")
                return START_NOT_STICKY
            }
            startService()
        } catch (e: Exception) {
            // Any uncaught failure here would kill the :RunSoLibV2RayDaemon process, leaving the
            // UI stuck on "Подключение…" with no state broadcast. Convert it into a reported
            // failure + clean stop so the UI can recover to idle.
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Unexpected failure during start", e)
            reportStartFailure(e.message?.takeUnless { it.isBlank() } ?: e.javaClass.simpleName)
            stopAllService()
        }
        return START_STICKY
    }

    /**
     * Notifies the UI that starting the VPN failed so it can reset from the "connecting" state.
     */
    private fun reportStartFailure(message: String) {
        try {
            MessageUtil.sendMsg2UI(this, AppConfig.MSG_STATE_START_FAILURE, message)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to report start failure", e)
        }
    }

    override fun getService(): Service {
        return this
    }

    override fun startService() {
        if (!::mInterface.isInitialized) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Interface not initialized")
            return
        }
        if (!CoreServiceManager.startCoreLoop(mInterface)) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to start core loop")
            stopAllService()
            return
        }
    }

    override fun stopService() {
        stopAllService(true)
    }

    /**
     * Пауза: the same teardown as [stopService] with the two lines that would burn the bridge
     * left out — `stopSelf()` and the cancelled notification.
     *
     * The tun interface IS closed. Leaving it up with nothing reading it would not be a pause,
     * it would be an outage: every route still points into the tunnel and no core is emptying
     * it, so the phone loses the internet rather than the VPN. Closing it hands routing back to
     * the system, which is what «выключить впн» means to the person who pressed the button.
     *
     * THE VPN PERMISSION IS NOT ASKED FOR AGAIN ON THE WAY BACK. `VpnService.prepare()` returns
     * null once the user has consented to this app, and consent belongs to the package, not to
     * the interface — closing the tunnel does not withdraw it, and this service does not even
     * stop, so nothing here can. [setupVpnService] runs its `prepare()` on resume exactly as it
     * does on a cold connect and gets null back, then establishes a fresh interface. (The one
     * case where it does not is the one where the user revoked the permission in Settings
     * meanwhile, and that already reports a start failure instead of a silent dead end.)
     */
    override fun pauseService() {
        stopAllService(isForced = true, keepAlive = true)
    }

    override fun vpnProtect(socket: Int): Boolean {
        return protect(socket)
    }

    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let {
            MyContextWrapper.wrap(newBase, SettingsManager.getLocale())
        }
        super.attachBaseContext(context)
    }

    /**
     * Sets up the VPN service.
     * Prepares the VPN and configures it if preparation is successful.
     *
     * @return true when the tunnel interface is up and the core may be started.
     */
    private fun setupVpnService(): Boolean {
        val prepare = prepare(this)
        if (prepare != null) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Permission not granted")
            // The user revoked the VPN permission (or another app took it). Nothing here can ask
            // for it back — only an Activity can — so the honest thing is to report the start as
            // failed and let the screen offer the retry, rather than leaving it negotiating.
            reportStartFailure(getString(R.string.toast_permission_denied))
            stopSelf()
            return false
        }

        if (configureVpnService() != true) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Configuration failed")
            stopSelf()
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
            if (::mInterface.isInitialized) {
                mInterface.close()
            }
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
            stopAllService()
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
        SettingsManager.getVpnDnsServers().forEach {
            if (Utils.isPureIpAddress(it)) {
                builder.addDnsServer(it)
            }
        }
    }

    /**
     * Configures platform-specific VPN features for different Android versions.
     *
     * @param builder The VPN Builder to configure
     */
    private fun configurePlatformFeatures(builder: Builder) {
        // Android P (API 28) and above: Configure network callbacks
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                connectivity.requestNetwork(defaultNetworkRequest, defaultNetworkCallback)
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to request network", e)
            }
        }

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

        // AN UNINSTALLED PACKAGE IS NOT AN ERROR, and logging it as one is why «Журнал» filled with
        // red on a perfectly healthy connect.
        //
        // The selection is a list of package NAMES, and it long outlives the apps: uninstalling an
        // app does not prune it, the «Подобрать автоматически» list is a remote file describing apps
        // this phone may never have had, and the «Российские приложения» набор deliberately names
        // every bank and operator app in the country so that whichever ones the user installs later
        // are already covered. Every absent one threw here, and every throw wrote an ERROR line with
        // a stack trace — on EVERY connect, one per package. Nothing was wrong, and the log said
        // dozens of things were.
        //
        // So the skip is counted, not reported, and one INFO line states the outcome: a real
        // diagnostic («37 из 40 применены») instead of 3 alarms, and still enough to notice a
        // selection that has gone entirely stale.
        var applied = 0
        var missing = 0
        apps.forEach {
            try {
                if (bypassApps) {
                    // In bypass mode, disallow the selected apps
                    builder.addDisallowedApplication(it)
                } else {
                    // In proxy mode, only allow the selected apps
                    builder.addAllowedApplication(it)
                }
                applied++
            } catch (e: PackageManager.NameNotFoundException) {
                missing++
            }
        }
        LogUtil.i(
            AppConfig.TAG,
            "StartCore-VPN: per-app rules applied to $applied of ${apps.size} packages" +
                if (missing > 0) " ($missing not installed)" else ""
        )
    }

    /**
     * Runs the tun2socks process.
     * Starts the tun2socks process with the appropriate parameters.
     */
    private fun runTun2socks() {
        // isUsingHevTun() is the user's preference AND TProxyService.isAvailable — see its kdoc.
        // When the native bridge cannot load, this is false, nothing here touches TProxyService,
        // and the core owns the tunnel instead (CoreConfigManager.needTun / the tun fd handed to
        // startLoop). Do not "simplify" this back to reading the preference alone: that is what
        // turned a missing .so into an ExceptionInInitializerError out of a static initialiser,
        // past onStartCommand's catch(Exception), and killed :RunSoLibV2RayDaemon on connect.
        if (SettingsManager.isUsingHevTun()) {
            tun2SocksService = TProxyService(
                context = applicationContext,
                vpnInterface = mInterface,
            )
        } else {
            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_USE_HEV_TUNNEL, true)) {
                LogUtil.w(
                    AppConfig.TAG,
                    "StartCore-VPN: hev-socks5-tunnel is unavailable in this build; " +
                        "the core will carry the tunnel itself"
                )
            }
            tun2SocksService = null
        }

        tun2SocksService?.startTun2Socks()
    }

    /**
     * @param isForced also close the tun interface (and, unless paused, stop the service itself).
     * @param keepAlive **пауза**: everything comes down except this service and its row in the
     *   shade. The ONLY two differences from a stop are on this page — `stopCoreLoop` keeps the
     *   notification instead of cancelling it, and `stopSelf()` is not called. Written as a
     *   parameter rather than a parallel method on purpose: a copy of this teardown would drift
     *   from it the first time either half changed.
     */
    private fun stopAllService(isForced: Boolean = true, keepAlive: Boolean = false) {
        isRunning = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                connectivity.unregisterNetworkCallback(defaultNetworkCallback)
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "StartCore-VPN: Failed to unregister callback", e)
            }
        }

        tun2SocksService?.stopTun2Socks()
        tun2SocksService = null

        CoreServiceManager.stopCoreLoop(keepAlive)

        if (isForced) {
            //stopSelf has to be called ahead of mInterface.close(). otherwise v2ray core cannot be stooped
            //It's strage but true.
            //This can be verified by putting stopself() behind and call stopLoop and startLoop
            //in a row for several times. You will find that later created v2ray core report port in use
            //which means the first v2ray core somehow failed to stop and release the port.
            //
            // A PAUSE KEEPS THE SERVICE, so it is the one path that does not stop itself. The
            // ordering the comment above protects is preserved: the core stop was asked for a few
            // lines up and the interface is still closed after it, with the same delay.
            if (!keepAlive) stopSelf()

            // Add a small delay to allow the async core stop operation to complete
            // before closing the VPN interface, preventing a race condition that can
            // leave the VPN icon in the status bar after stopping the service.
            try {
                Thread.sleep(100)
            } catch (e: InterruptedException) {
                LogUtil.w(AppConfig.TAG, "StartCore-VPN: Sleep interrupted", e)
            }

            try {
                if (::mInterface.isInitialized) {
                    mInterface.close()
                    LogUtil.i(AppConfig.TAG, "StartCore-VPN: VPN interface closed")
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to close interface", e)
            }
        }
    }
}

