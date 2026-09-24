package com.v2ray.ang.dto.entities

data class SubscriptionItem(
    var remarks: String = "",
    var url: String = "",
    var enabled: Boolean = true,
    val addedTime: Long = System.currentTimeMillis(),
    var lastUpdated: Long = -1,
    var autoUpdate: Boolean = true,
    var updateInterval: Long = 60, // in minutes, default to 1 hour
    var prevProfile: String? = null,
    var nextProfile: String? = null,
    var filter: String? = null,
    var allowInsecureUrl: Boolean = false,
    var userAgent: String? = null,

    // --- subscription-userinfo metadata (bytes / epoch-seconds) ---
    var uploadUsed: Long = 0,       // bytes, from header `upload`
    var downloadUsed: Long = 0,     // bytes, from header `download`
    var totalTraffic: Long = 0,     // bytes, from header `total`; 0 == unlimited
    var expire: Long = 0,           // epoch SECONDS, from header `expire`; 0 == no expiry
    var userInfoUpdated: Long = 0,  // epoch millis, when metadata was last captured

    // --- Happ-style subscription directives ---
    var pinned: Boolean = false,    // pinned subscriptions sort first and become the default tab
    var announce: String = "",      // banner text from the `announce` header/#directive
    var supportUrl: String = "",    // from `support-url` (e.g. a Telegram link)
    var webPageUrl: String = "",    // from `profile-web-page-url`
    var profileTitle: String = "",  // display title from the `profile-title` header

    // --- managed/hidden template state ---
    // Set from the `profile-hidden`/`hidden` response header or an in-body `#profile-hidden:` directive.
    // Locked subscriptions store their raw config obfuscated/encrypted and stamp locked=true on every
    // imported profile, which gates share/QR/show-config/edit/export in the UI and redacts the sub URL.
    var locked: Boolean = false,
)

// --- derived helpers (kept out of the data class so it stays a plain JSON POJO) ---
val SubscriptionItem.usedTraffic: Long get() = uploadUsed + downloadUsed
val SubscriptionItem.isUnlimited: Boolean get() = totalTraffic <= 0L
val SubscriptionItem.hasExpiry: Boolean get() = expire > 0L
val SubscriptionItem.isExpired: Boolean get() = expire in 1 until (System.currentTimeMillis() / 1000)

/** 0f..1f, only meaningful when !isUnlimited */
val SubscriptionItem.trafficFraction: Float
    get() = if (isUnlimited) 0f else (usedTraffic.toFloat() / totalTraffic).coerceIn(0f, 1f)

/** true when the header carried at least one meaningful field */
val SubscriptionItem.hasUserInfo: Boolean get() = usedTraffic > 0 || totalTraffic > 0 || expire > 0

