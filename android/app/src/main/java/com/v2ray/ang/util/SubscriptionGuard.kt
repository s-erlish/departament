package com.v2ray.ang.util

import android.net.Uri

object SubscriptionGuard {

    private const val REQUIRED_LABEL = "departament"

    fun isAllowed(rawUrl: String): Boolean {
        val uri = try {
            Uri.parse(rawUrl.trim())
        } catch (e: Exception) {
            return false
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return false

        val host = uri.host?.lowercase() ?: return false
        return host.split(".").any { it == REQUIRED_LABEL }
    }
}
