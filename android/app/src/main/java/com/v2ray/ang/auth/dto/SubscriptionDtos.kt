package com.v2ray.ang.auth.dto

import com.google.gson.annotations.SerializedName

/**
 * Client subscription endpoints of the Departament backend.
 *
 *  GET   /client/subscription                    -> [PrimarySubscriptionDto]   (the ACTIVE/root sub)
 *  GET   /client/subscription/all                -> [SubscriptionAllDto]       (root + secondaries)
 *  PATCH /client/subscription/{scope}/{id}/name  (body [RenameRequestDto])
 *  GET   /client/subscription/qr?uuid=…          (PNG bytes)
 *  POST  /client/subscription/{scope}/{id}/add-devices (body [AddDevicesRequestDto])
 *  GET   /client/subscriptions/upgrade-quote?targetTariffId=…  -> [UpgradeQuoteDto]
 *  POST  /client/subscriptions/upgrade            (body [UpgradeRequestDto])
 *
 * Field names below were reconciled against the live backend (mirrored from the web cabinet's
 * API client). The `/client/subscription/all` items carry ONLY the fields marked "in /all";
 * the connect payload (subscriptionUrl / remnawaveUuid / raw remnawave record) and the friendly
 * `tariffDisplayName` live on the separate GET /client/subscription summary, so they arrive null
 * from /all. See [PrimarySubscriptionDto].
 */

/** GET /client/subscription/all */
data class SubscriptionAllDto(
    val items: List<SubInfoDto> = emptyList(),
)

/** A single subscription (root or secondary). */
data class SubInfoDto(
    /** "root" | "secondary" — used as the {scope} path segment. (in /all) */
    val type: String = "root",
    /** The subscription id — also the id the auto-renew endpoint expects. (in /all) */
    val id: String = "",
    // NOT present on /all items — only on the GET /client/subscription summary / connect payload.
    // Kept so the device-management / QR / import paths compile; stays blank/null from /all.
    val remnawaveUuid: String = "",
    val subscription: SubResponseWrapper? = null,
    val tariffDisplayName: String? = null,
    // in /all — the user-set label, then the backend default label ("Подписка #N").
    val displayName: String? = null,
    val defaultLabel: String? = null,
    val subscriptionIndex: Int? = null,
    // in /all — tariff + selected price-option this sub renews on (used by renew/upgrade/add-devices).
    val tariffId: String? = null,
    val tariffPriceOptionId: String? = null,
    // in /all — deviceCount = EXTRA devices purchased; totalDevices = total device slots.
    // The live "connected devices" count is NOT in /all (it comes from GET /client/devices -> total).
    val deviceCount: Int = 0,
    val totalDevices: Int = 0,
    // NOT present on /all — always 0 from this endpoint (see note above). Kept for API compat.
    val connectedDevices: Int = 0,
    val autoRenewEnabled: Boolean = false,
    val expireAtIso: String? = null,
    val isTrial: Boolean = false,
    val tariffPrice: Double? = null,
    val tariffCurrency: String? = null,
    val renewalPrice: Double? = null,
) {
    /**
     * Best-effort tariff badge name ("Base" / "Plus") derived from THIS sub's own fields, used as a
     * LAST-RESORT fallback when the tariff catalog can't resolve the sub by [tariffId] or by its
     * renewing price-option. Uses ONLY the authoritative summary [tariffDisplayName], which reflects
     * the CURRENT tariff. The raw remnawave record's `productName` / `subscriptionProductName` are
     * intentionally EXCLUDED: they are fixed at provisioning and go stale after an upgrade (e.g. they
     * still read "Base" after a Base→Plus upgrade), so trusting them would surface a WRONG badge. The
     * generic service label ("departament vpn") is filtered out so the badge only ever shows a real
     * tariff name; a generic-only display name yields null (badge hidden).
     */
    fun tariffBadgeName(): String? =
        tariffDisplayName?.trim()
            ?.takeIf { it.isNotBlank() && !isGenericServiceName(it) }

    private fun isGenericServiceName(name: String): Boolean =
        name.trim().lowercase().let { it == "departament vpn" || it == "departament" }
}

/**
 * GET /client/subscription — the authoritative ACTIVE (root) subscription summary.
 *
 * This is what the web cabinet renders as the primary subscription; it is richer than the root
 * entry inside /all (it carries the raw remnawave record with the connect URL and the friendly
 * tariff name). Wire this up in the API client/repository to render the active subscription
 * reliably even when /all returns no root item.
 */
data class PrimarySubscriptionDto(
    val subscription: SubResponseWrapper? = null,
    val tariffDisplayName: String? = null,
    // The active subscription's tariff id, when the summary exposes it. Lets the badge resolve the
    // tariff name from the catalog EXACTLY (id → TariffDto.name), independent of the possibly-absent
    // /all root entry and the stale remnawave product label. Key spelling varies across backends, so
    // accept the common ones; stays null (fall back to the display name) when absent.
    @SerializedName(value = "tariffId", alternate = ["tariff_id", "tariffUuid", "tariffID"])
    val tariffId: String? = null,
    val autoRenewNextChargeAmount: Double? = null,
    val autoRenewNextChargeAt: String? = null,
    val autoRenewCurrency: String? = null,
    val message: String? = null,
) {
    /** The raw remnawave record for the active subscription, if any. */
    fun raw(): RawSubDto? = subscription?.raw()

    /**
     * The tariff id for the active subscription: the summary's own [tariffId] when present, else the
     * one the raw remnawave record carries. Blank/null when neither exposes it. Callers match this
     * against the tariff catalog to render the real tariff badge.
     */
    fun activeTariffId(): String? =
        tariffId?.takeIf { it.isNotBlank() } ?: raw()?.tariffId?.takeIf { it.isNotBlank() }

    /**
     * True when this payload actually carries an active subscription. When the account has none
     * the backend returns an empty `subscription` and only a [message], so we key off the raw
     * record having any real content (connect URL / expiry / status) or a tariff name.
     */
    fun hasActiveSubscription(): Boolean {
        val r = raw()
        val rawHasContent = r != null &&
            (r.subscriptionUrl.isNotBlank() || !r.expireAt.isNullOrBlank() || !r.status.isNullOrBlank())
        return rawHasContent || !tariffDisplayName.isNullOrBlank()
    }
}

/**
 * Wrapper around the Remnawave subscription payload. The backend nests the raw record under
 * `response`, or occasionally under `data.response` — mirror the web client's tolerance so both
 * shapes resolve. [raw] returns whichever is present.
 */
data class SubResponseWrapper(
    val response: RawSubDto? = null,
    val data: SubDataWrapper? = null,
) {
    fun raw(): RawSubDto? = response ?: data?.response
}

data class SubDataWrapper(
    val response: RawSubDto? = null,
)

/** The raw Remnawave subscription record. */
data class RawSubDto(
    val subscriptionUrl: String = "",
    val hwidDeviceLimit: Int = 0,
    val trafficLimitBytes: Long? = null,
    // Some payloads carry the used traffic flat as `trafficUsed` instead of userTraffic.usedTrafficBytes.
    val trafficUsed: Long? = null,
    val userTraffic: UserTrafficDto = UserTrafficDto(),
    val expireAt: String? = null,
    val status: String? = null,
    // Friendly names the backend sometimes attaches to the raw record.
    val productName: String? = null,
    val subscriptionProductName: String? = null,
    // The tariff id, when the backend attaches it to the raw record. Preferred over the product name
    // for the badge: it resolves the catalog exactly, whereas productName is fixed at provisioning.
    @SerializedName(value = "tariffId", alternate = ["tariff_id", "tariffUuid", "tariffID"])
    val tariffId: String? = null,
) {
    /** trafficLimitBytes == null means an unlimited traffic plan. */
    fun isUnlimitedTraffic(): Boolean = trafficLimitBytes == null

    /** hwidDeviceLimit <= 0 means an unlimited device plan. */
    fun isUnlimitedDevices(): Boolean = hwidDeviceLimit <= 0
}

data class UserTrafficDto(
    val usedTrafficBytes: Long = 0L,
)

// region request bodies

data class RenameRequestDto(
    val name: String,
)

data class AddDevicesRequestDto(
    val extraDevices: Int,
    val method: String,
    val paymentMethod: String? = null,
)

data class UpgradeRequestDto(
    val targetTariffId: String,
    val method: String,
    val paymentMethod: String? = null,
    val subscriptionUuid: String,
)

data class AutoRenewRequestDto(
    val autoRenew: Boolean,
)

// endregion
