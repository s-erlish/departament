package com.v2ray.ang.enums

/**
 * User-selectable connection-test methods.
 *
 * - [TCP_CONNECT] / [HTTP_URL] / [ICMP] are direct (phone → node, no tunnel): cheap, but they
 *   only tell you the node is reachable, not that the proxy works.
 * - [PROXIED_REAL_DELAY] measures latency through a temporary core built from the node's config;
 *   it is the only method that validates the tunnel end-to-end and is the default.
 */
enum class PingMethod(val prefValue: String) {
    TCP_CONNECT("tcp"),
    HTTP_URL("http"),
    ICMP("icmp"),
    PROXIED_REAL_DELAY("real");

    companion object {
        fun fromPref(v: String?) = entries.firstOrNull { it.prefValue == v } ?: PROXIED_REAL_DELAY
    }
}
