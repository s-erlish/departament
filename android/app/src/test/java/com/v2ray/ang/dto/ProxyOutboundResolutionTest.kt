package com.v2ray.ang.dto

import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * [V2rayConfig.getProxyOutbound] must name the outbound that actually carries traffic.
 *
 * Operator templates ship several proxy outbounds and choose one with a routing rule, so reading the
 * first proxy-protocol entry can name a decoy. Everything downstream then reads the wrong server: the
 * row shows the decoy's protocol and transport, the TCP ping probes a host that is not the server and
 * never answers — so the profile looks unpingable — and the delay test promotes the decoy and measures
 * it after stripping routing.
 *
 * The two configs below are the reduced but faithful shapes of a template that worked and one that did
 * not, which is what put this defect on the list.
 */
class ProxyOutboundResolutionTest {

    private val gson = GsonBuilder().create()

    private fun parse(json: String): V2rayConfig =
        gson.fromJson(json, V2rayConfig::class.java)

    /** Traffic goes to the tag the routing rule names, not to the first proxy outbound in the array. */
    @Test
    fun routingRuleWinsOverTheFirstProxyOutbound() {
        val config = parse(
            """
            {
              "routing": {
                "domainStrategy": "IPIfNonMatch",
                "rules": [
                  { "type": "field", "protocol": ["bittorrent"], "outboundTag": "block" },
                  { "type": "field", "ip": ["geoip:private"], "outboundTag": "direct" },
                  { "type": "field", "domain": ["geosite:private"], "outboundTag": "direct" },
                  { "type": "field", "network": "tcp,udp", "outboundTag": "proxy-rum1lk2" }
                ]
              },
              "outbounds": [
                { "tag": "proxy", "protocol": "vless",
                  "settings": { "vnext": [ { "address": "web.max.ru", "port": 443,
                    "users": [ { "id": "fd9151b1-da58-493e-b830-e2037d7b66e6", "encryption": "none" } ] } ] },
                  "streamSettings": { "network": "tcp", "security": "reality" } },
                { "tag": "proxy-rum1lk2", "protocol": "vless",
                  "settings": { "vnext": [ { "address": "185.91.54.248", "port": 44444,
                    "users": [ { "id": "4f69ad29-f49a-4032-b9bb-888663ff8296", "encryption": "none" } ] } ] },
                  "streamSettings": { "network": "grpc", "security": "reality" } },
                { "tag": "direct", "protocol": "freedom" },
                { "tag": "block", "protocol": "blackhole" }
              ]
            }
            """.trimIndent()
        )

        val outbound = config.getProxyOutbound()
        assertNotNull("a routed proxy outbound must be resolved", outbound)
        assertEquals("proxy-rum1lk2", outbound!!.tag)
        assertEquals("185.91.54.248", outbound.getServerAddress())
        assertEquals(44444, outbound.getServerPort())
    }

    /** The ordinary single-proxy template keeps resolving to its one outbound. */
    @Test
    fun singleProxyOutboundIsUnaffected() {
        val config = parse(
            """
            {
              "routing": {
                "domainStrategy": "IPIfNonMatch",
                "rules": [
                  { "type": "field", "ip": ["geoip:private"], "outboundTag": "direct" },
                  { "type": "field", "network": "tcp,udp", "outboundTag": "proxy" }
                ]
              },
              "outbounds": [
                { "tag": "proxy", "protocol": "vless",
                  "settings": { "vnext": [ { "address": "nl.departament.site", "port": 443,
                    "users": [ { "id": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", "encryption": "none" } ] } ] },
                  "streamSettings": { "network": "tcp", "security": "reality" } },
                { "tag": "direct", "protocol": "freedom" },
                { "tag": "direct-fragment", "protocol": "freedom" },
                { "tag": "block", "protocol": "blackhole" }
              ]
            }
            """.trimIndent()
        )

        val outbound = config.getProxyOutbound()
        assertNotNull(outbound)
        assertEquals("proxy", outbound!!.tag)
        assertEquals("nl.departament.site", outbound.getServerAddress())
    }

    /**
     * A rule narrowed to a destination describes a special case, not the default path, so it must not
     * decide which server the profile is. Here a domain-scoped rule points at a second outbound while
     * ordinary traffic still goes to the first.
     */
    @Test
    fun narrowedRulesDoNotDecideTheServer() {
        val config = parse(
            """
            {
              "routing": {
                "domainStrategy": "IPIfNonMatch",
                "rules": [
                  { "type": "field", "domain": ["geosite:category-ads"], "outboundTag": "block" },
                  { "type": "field", "domain": ["example.com"], "outboundTag": "proxy-special" },
                  { "type": "field", "network": "tcp,udp", "outboundTag": "proxy-main" }
                ]
              },
              "outbounds": [
                { "tag": "proxy-special", "protocol": "vless",
                  "settings": { "vnext": [ { "address": "special.example", "port": 443,
                    "users": [ { "id": "11111111-2222-3333-4444-555555555555", "encryption": "none" } ] } ] } },
                { "tag": "proxy-main", "protocol": "vless",
                  "settings": { "vnext": [ { "address": "main.example", "port": 8443,
                    "users": [ { "id": "66666666-7777-8888-9999-000000000000", "encryption": "none" } ] } ] } },
                { "tag": "block", "protocol": "blackhole" }
              ]
            }
            """.trimIndent()
        )

        assertEquals("proxy-main", config.getProxyOutbound()!!.tag)
    }

    /**
     * A catch-all sending everything to freedom is a bypass or a kill-switch, not this profile's
     * server. Resolution keeps looking rather than reporting that the server is "freedom".
     */
    @Test
    fun aCatchAllToFreedomIsNotTheServer() {
        val config = parse(
            """
            {
              "routing": {
                "domainStrategy": "AsIs",
                "rules": [
                  { "type": "field", "network": "tcp,udp", "outboundTag": "direct" }
                ]
              },
              "outbounds": [
                { "tag": "direct", "protocol": "freedom" },
                { "tag": "proxy", "protocol": "vless",
                  "settings": { "vnext": [ { "address": "fallback.example", "port": 443,
                    "users": [ { "id": "aaaaaaaa-0000-0000-0000-000000000000", "encryption": "none" } ] } ] } }
                ]
            }
            """.trimIndent()
        )

        assertEquals("proxy", config.getProxyOutbound()!!.tag)
    }

    /** A balancer names its members by tag prefix; any proxy member represents the profile. */
    @Test
    fun aBalancerResolvesThroughItsSelector() {
        val config = parse(
            """
            {
              "routing": {
                "domainStrategy": "IPIfNonMatch",
                "rules": [
                  { "type": "field", "network": "tcp,udp", "balancerTag": "balancer-eu" }
                ],
                "balancers": [
                  { "tag": "balancer-eu", "selector": ["node-eu"] }
                ]
              },
              "outbounds": [
                { "tag": "direct", "protocol": "freedom" },
                { "tag": "node-eu-1", "protocol": "vless",
                  "settings": { "vnext": [ { "address": "eu1.example", "port": 443,
                    "users": [ { "id": "aaaaaaaa-1111-1111-1111-111111111111", "encryption": "none" } ] } ] } }
              ]
            }
            """.trimIndent()
        )

        assertEquals("node-eu-1", config.getProxyOutbound()!!.tag)
    }

    /** With no routing section at all, the first proxy outbound is still the answer. */
    @Test
    fun noRoutingFallsBackToTheFirstProxyOutbound() {
        val config = parse(
            """
            {
              "outbounds": [
                { "tag": "proxy", "protocol": "vmess",
                  "settings": { "vnext": [ { "address": "plain.example", "port": 12345,
                    "users": [ { "id": "cccccccc-dddd-eeee-ffff-000000000000" } ] } ] } },
                { "tag": "direct", "protocol": "freedom" }
              ]
            }
            """.trimIndent()
        )

        assertEquals("proxy", config.getProxyOutbound()!!.tag)
    }
}
