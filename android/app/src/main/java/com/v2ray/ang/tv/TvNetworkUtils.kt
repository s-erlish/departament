package com.v2ray.ang.tv

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Small helpers for the LAN transfer: discovering the device's own Wi-Fi IPv4
 * address and classifying remote peers as LAN-local.
 */
object TvNetworkUtils {

    /**
     * Returns the device's site-local IPv4 address (e.g. 192.168.x.x / 10.x.x.x /
     * 172.16-31.x.x) on an up, non-loopback interface, preferring wlan* interfaces.
     *
     * Returns null when the device has no usable LAN address (e.g. no Wi-Fi).
     */
    fun getLocalIpv4Address(): String? {
        return try {
            val candidates = mutableListOf<Pair<String, String>>() // ifaceName to ip
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback || nif.isVirtual) continue
                val name = nif.name?.lowercase().orEmpty()
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                        candidates.add(name to addr.hostAddress.orEmpty())
                    }
                }
            }
            // Prefer wlan/eth interfaces; fall back to any site-local IPv4.
            candidates.firstOrNull { it.first.startsWith("wlan") }?.second
                ?: candidates.firstOrNull { it.first.startsWith("eth") }?.second
                ?: candidates.firstOrNull()?.second
        } catch (e: Exception) {
            null
        }
    }

    /**
     * True when [address] is a LAN-local peer (site-local, link-local or loopback).
     * Used as a cheap guard so the one-shot listener never services an off-LAN
     * client even if something routed a request to it.
     */
    fun isLanAddress(address: InetAddress?): Boolean {
        if (address == null) return false
        return address.isSiteLocalAddress ||
            address.isLinkLocalAddress ||
            address.isLoopbackAddress
    }
}
