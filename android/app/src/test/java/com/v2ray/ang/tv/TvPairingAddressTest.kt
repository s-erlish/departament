package com.v2ray.ang.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone sends the subscription URL — personal access key included — over plain HTTP to the
 * address in the TV's QR code. Only an address on the local network may receive it.
 */
class TvPairingAddressTest {

    @Test
    fun `home network addresses are accepted`() {
        assertTrue(TvPairingProtocol.isLocalNetworkAddress("192.168.1.42"))
        assertTrue(TvPairingProtocol.isLocalNetworkAddress("10.0.0.7"))
        assertTrue(TvPairingProtocol.isLocalNetworkAddress("172.20.3.4"))
        assertTrue(TvPairingProtocol.isLocalNetworkAddress("169.254.10.1"))
        assertTrue(TvPairingProtocol.isLocalNetworkAddress("fe80::1"))
        assertTrue(TvPairingProtocol.isLocalNetworkAddress("fd12:3456::7"))
    }

    @Test
    fun `internet addresses and names are refused`() {
        assertFalse(TvPairingProtocol.isLocalNetworkAddress("8.8.8.8"))
        assertFalse(TvPairingProtocol.isLocalNetworkAddress("172.32.0.1"))
        assertFalse(TvPairingProtocol.isLocalNetworkAddress("2001:4860:4860::8888"))
        assertFalse(TvPairingProtocol.isLocalNetworkAddress("tv.example.com"))
        assertFalse(TvPairingProtocol.isLocalNetworkAddress("127.0.0.1"))
        assertFalse(TvPairingProtocol.isLocalNetworkAddress(""))
    }
}
