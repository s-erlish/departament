package com.v2ray.ang.util

import android.net.Uri
import com.v2ray.ang.handler.MmkvManager

/**
 * Decides whether the app currently holds a genuine "departament" subscription — one of the
 * owner's own VPN subscription links.
 *
 * Account and payment features are gated on this: for any FOREIGN subscription that a user might
 * paste (someone else's, not from this VPN) the Account tab and the payment/buy entry points must
 * NOT appear, because payment cannot work for foreign servers. Only the owner's own subscription
 * links unlock those features.
 */
object SubscriptionOrigin {

    /**
     * True iff [url] is one of the owner's departament subscription links: it parses, its host
     * (lowercased) contains the word "departament", and its path contains "/sub" (i.e. it is a
     * subscription URL such as https://sub.departament.site/sub/<token> or https://x.departament.y/sub).
     * Any blank or malformed URL yields false (defensive: never throws).
     */
    fun isDepartamentSubscriptionUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val host: String?
        val path: String?
        try {
            val uri = Uri.parse(url.trim())
            host = uri.host
            path = uri.path
        } catch (e: Exception) {
            return false
        }
        if (host.isNullOrBlank()) return false
        if (!host.lowercase().contains("departament")) return false
        val p = path ?: return false
        return p.contains("/sub")
    }

    /**
     * True iff ANY subscription currently stored in the app is a departament subscription URL.
     * Enumerates the persisted subscriptions from [MmkvManager]; any failure yields false.
     */
    fun hasDepartamentSubscription(): Boolean {
        return try {
            MmkvManager.decodeSubscriptions().any { isDepartamentSubscriptionUrl(it.subscription.url) }
        } catch (e: Exception) {
            false
        }
    }
}
