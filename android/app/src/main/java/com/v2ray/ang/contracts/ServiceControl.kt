package com.v2ray.ang.contracts

import android.app.Service

interface ServiceControl {
    /**
     * Gets the service instance.
     * @return The service instance.
     */
    fun getService(): Service

    /**
     * Starts the service.
     */
    fun startService()

    /**
     * Stops the service.
     */
    fun stopService()

    /**
     * Пауза: brings the tunnel down and leaves the service — and its row in the shade — standing.
     *
     * The difference from [stopService] is only what survives: the foreground notification is
     * re-worded instead of cancelled, so the way back is still in the shade. Everything that
     * costs anything comes down exactly as it does on a stop — core loop, tun interface, the
     * network callback, tun2socks, the speed meter.
     */
    fun pauseService()

    /**
     * Protects the VPN socket.
     * @param socket The socket to protect.
     * @return True if the socket is protected, false otherwise.
     */
    fun vpnProtect(socket: Int): Boolean
}
