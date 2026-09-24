package com.v2ray.ang.service

import android.content.Context
import android.os.ParcelFileDescriptor
import com.v2ray.ang.AppConfig
import com.v2ray.ang.contracts.Tun2SocksControl
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.LogUtil
import java.io.File

/**
 * Manages the tun2socks process that handles VPN traffic
 */
/**
 * `isRunningProvider` and `restartCallback` USED TO BE CONSTRUCTOR PARAMETERS HERE and neither was
 * ever read. Upstream v2rayNG ran tun2socks as a child PROCESS and needed both: a watchdog thread
 * asked "is the service still meant to be up" and, if the process had died on its own, called back
 * to start it again. This fork drives hev-socks5-tunnel through JNI in-process, so there is no
 * process to outlive us and nothing to restart — the two lambdas were captured, stored and never
 * consulted, one of them holding `CoreVpnService::runTun2socks`.
 */
class TProxyService(
    private val context: Context,
    private val vpnInterface: ParcelFileDescriptor,
) : Tun2SocksControl {
    companion object {
        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyStartService(configPath: String, fd: Int)

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyStopService()

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyGetStats(): LongArray?

        /**
         * Whether `libhev-socks5-tunnel.so` actually loaded, decided once per process.
         *
         * THE LOAD USED TO BE A BARE `System.loadLibrary` IN A COMPANION `init {}` BLOCK, AND THAT
         * SHAPE TURNS ONE MISSING FILE INTO A SILENT PROCESS DEATH.
         *
         * A failed `System.loadLibrary` throws `UnsatisfiedLinkError`, and a throw out of a static
         * initialiser reaches the caller as `ExceptionInInitializerError`. Both are `Error`, not
         * `Exception` — so `CoreVpnService.onStartCommand`'s catch, which exists for the express
         * purpose of keeping the `:RunSoLibV2RayDaemon` process alive through a failed start
         * (`CoreVpnService.kt:128`), did not catch it and the process died. Nothing was broadcast,
         * so the UI could not even report a failure and sat on «Подключение…» until its watchdog
         * expired. From outside that is indistinguishable from «крашится при запуске ВПН».
         *
         * The blast radius is also invisible until connect: this class is touched for the first
         * time by `CoreVpnService.runTun2socks()`, so the app launches, imports, browses and lists
         * servers perfectly and only dies on the one tap that matters. And it is invisible to a
         * local build — the type-check stub has no native code and no test here ever loads it.
         *
         * Caught as `Throwable`, because every way this can fail is an `Error`: a missing ABI
         * split, a stripped or mis-packaged .so, a 16 KB page-size refusal, an unsatisfied symbol.
         * None of them is a reason to take the process down, because the core can carry the tunnel
         * itself — that is exactly what `PREF_USE_HEV_TUNNEL = false` already does, every day, on
         * the same code path. So this value is not a dead guard: `SettingsManager.isUsingHevTun()`
         * consults it, which routes the whole start down that existing xray-tun path — the core
         * gets a `tun` inbound (`CoreConfigManager.needTun`), the tun fd is handed to the core
         * instead of being zeroed (`CoreServiceManager.doStartCoreLoop`), and `runTun2socks()`
         * never constructs this class.
         */
        @JvmStatic
        val isAvailable: Boolean by lazy {
            try {
                System.loadLibrary("hev-socks5-tunnel")
                true
            } catch (t: Throwable) {
                LogUtil.e(
                    AppConfig.TAG,
                    "TProxyService: libhev-socks5-tunnel could not be loaded; " +
                        "falling back to the core's own tun handling",
                    t,
                )
                false
            }
        }
    }

    /**
     * Starts the tun2socks process with the appropriate parameters.
     */
    override fun startTun2Socks() {
        val configContent = buildConfig()
        val configFile = File(context.filesDir, "hev-socks5-tunnel.yaml").apply {
            writeText(configContent)
        }
        LogUtil.d(AppConfig.TAG, "HevSocks5Tunnel Config content:\n$configContent")

        try {
            TProxyStartService(configFile.absolutePath, vpnInterface.fd)
        } catch (t: Throwable) {
            // Throwable, not Exception: an unresolved JNI symbol arrives as UnsatisfiedLinkError,
            // and letting an Error out of here kills :RunSoLibV2RayDaemon with nothing broadcast.
            LogUtil.e(AppConfig.TAG, "HevSocks5Tunnel failed to start", t)
        }
    }

    private fun buildConfig(): String {
        val socksPort = SettingsManager.getSocksPort()
        val vpnConfig = SettingsManager.getCurrentVpnInterfaceAddressConfig()
        // The socks inbound (CoreConfigManager.configureInbounds) is always "noauth", so this
        // loopback bridge never sends credentials — enabling SOCKS5 auth or hotspot sharing can
        // therefore never desync the two sides and stall the phone's VPN.
        return buildString {
            appendLine("tunnel:")
            appendLine("  mtu: ${SettingsManager.getVpnMtu()}")
            appendLine("  ipv4: ${vpnConfig.ipv4Client}")

            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_IPV6_ENABLED)) {
                appendLine("  ipv6: '${vpnConfig.ipv6Client}'")
            }

            appendLine("socks5:")
            appendLine("  port: ${socksPort}")
            appendLine("  address: ${AppConfig.LOOPBACK}")
            appendLine("  udp: 'udp'")

            // Read-write timeout settings
            val timeoutSetting = MmkvManager.decodeSettingsString(AppConfig.PREF_HEV_TUNNEL_RW_TIMEOUT) ?: AppConfig.HEVTUN_RW_TIMEOUT
            val parts = timeoutSetting.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            val tcpTimeout = parts.getOrNull(0)?.toIntOrNull() ?: 300
            val udpTimeout = parts.getOrNull(1)?.toIntOrNull() ?: 60

            appendLine("misc:")
            appendLine("  tcp-read-write-timeout: ${tcpTimeout * 1000}")
            appendLine("  udp-read-write-timeout: ${udpTimeout * 1000}")
            appendLine("  log-level: ${MmkvManager.decodeSettingsString(AppConfig.PREF_HEV_TUNNEL_LOGLEVEL) ?: "warn"}")
        }
    }

    /**
     * Stops the tun2socks process
     */
    override fun stopTun2Socks() {
        try {
            LogUtil.i(AppConfig.TAG, "TProxyStopService...")
            TProxyStopService()
        } catch (t: Throwable) {
            LogUtil.e(AppConfig.TAG, "Failed to stop hev-socks5-tunnel", t)
        }
    }
}
