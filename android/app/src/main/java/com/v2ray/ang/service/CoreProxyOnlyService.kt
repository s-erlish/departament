package com.v2ray.ang.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.v2ray.ang.AppConfig
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.MyContextWrapper
import java.lang.ref.SoftReference

class CoreProxyOnlyService : Service(), ServiceControl {
    /**
     * Initializes the service.
     */
    override fun onCreate() {
        super.onCreate()
        LogUtil.i(AppConfig.TAG, "StartCore-Proxy: Service created")
        CoreServiceManager.serviceControl = SoftReference(this)
    }

    /**
     * Handles the start command for the service.
     * @param intent The intent.
     * @param flags The flags.
     * @param startId The start ID.
     * @return The start mode.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LogUtil.i(AppConfig.TAG, "StartCore-Proxy: Service command received")

        // A STICKY RESTART OF A PAUSED SERVICE IS NOT A REQUEST TO CONNECT — see the same guard,
        // and the same reasoning, in `CoreVpnService.onStartCommand`. «Только прокси» carries the
        // identical row in the shade, so it carries the identical pause.
        if (intent == null && CoreServiceManager.isPaused()) {
            LogUtil.i(AppConfig.TAG, "StartCore-Proxy: Restarted while paused; the core stays down")
            val paused = MmkvManager.getSelectServer()?.let { MmkvManager.decodeServerConfig(it) }
            if (!NotificationManager.showNotification(paused)) {
                LogUtil.e(AppConfig.TAG, "StartCore-Proxy: Failed to enter foreground while paused; stopping")
                stopSelf()
                return START_NOT_STICKY
            }
            CoreServiceManager.ensureCommandReceiver()
            return START_STICKY
        }

        // Every other start command is a connect, «Возобновить» in the shade included.
        CoreServiceManager.clearPaused()

        // A DUPLICATE START IS NOT A FAILED ONE, and telling them apart is what keeps a live
        // connection alive. `startCoreLoop` answers false to both — "a core is already running" and
        // "the core would not start" — and the branch below treats false as failure and stops the
        // service, which tears down the very core that was already serving traffic. The shell can
        // issue such a start honestly: `MainViewModel.isRunning` is published as false on every
        // Activity start and only corrected when the daemon answers the handshake, so a connect
        // pressed in that window arrives on a running core. Answer it with the truth — the tunnel
        // is up — and change nothing.
        if (CoreServiceManager.isRunning()) {
            LogUtil.w(AppConfig.TAG, "StartCore-Proxy: A core is already running; keeping it")
            MessageUtil.sendMsg2UI(this, AppConfig.MSG_STATE_RUNNING, "")
            return START_STICKY
        }

        // Honour the result. A genuine refusal must not leave a half-started service behind: it
        // used to be ignored, and a failed start then left the PREVIOUS server's core alive while
        // the UI reported the newly selected one — a silent lie about where the traffic is going.
        if (!CoreServiceManager.startCoreLoop(null)) {
            LogUtil.e(AppConfig.TAG, "StartCore-Proxy: Failed to start core loop")
            // stopSelf() alone — onDestroy() performs the single stopCoreLoop() teardown.
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    /**
     * Destroys the service.
     */
    override fun onDestroy() {
        super.onDestroy()
        CoreServiceManager.stopCoreLoop()
    }

    /**
     * Gets the service instance.
     * @return The service instance.
     */
    override fun getService(): Service {
        return this
    }

    /**
     * Starts the service.
     */
    override fun startService() {
        // do nothing
    }

    /**
     * Stops the service.
     */
    override fun stopService() {
        stopSelf()
    }

    /**
     * Пауза: the core loop comes down, the service and its row in the shade stay.
     *
     * There is no tun interface here to close — the proxy mode never establishes one — so the
     * whole pause is the core stop, and `stopSelf()` is exactly what must NOT happen: `onDestroy`
     * would run `stopCoreLoop()` and take the notification with it, which is the way back.
     */
    override fun pauseService() {
        CoreServiceManager.stopCoreLoop(keepAlive = true)
    }

    /**
     * Protects the VPN socket.
     * @param socket The socket to protect.
     * @return True if the socket is protected, false otherwise.
     */
    override fun vpnProtect(socket: Int): Boolean {
        return true
    }

    /**
     * Binds the service.
     * @param intent The intent.
     * @return The binder.
     */
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    /**
     * Attaches the base context to the service.
     * @param newBase The new base context.
     */
    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let {
            MyContextWrapper.wrap(newBase, SettingsManager.getLocale())
        }
        super.attachBaseContext(context)
    }
}
