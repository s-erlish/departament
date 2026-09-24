package com.v2ray.ang.auth.dto

import com.google.gson.annotations.SerializedName

/**
 * Remaining client endpoints: upgrade quote, devices, promo codes, referral stats.
 *
 *  GET  /client/subscriptions/upgrade-quote?targetTariffId=… -> [UpgradeQuoteDto]
 *  GET  /client/devices?uuid=…                               -> [DevicesDto]
 *  POST /client/devices/delete                                (body [DeleteDeviceRequestDto])
 *  POST /client/promo-code/check                              -> [PromoDto]
 *  POST /client/promo-code/activate                           (body [PromoRequestDto])
 *  GET  /client/referral-stats                                -> [ReferralStatsDto]
 */

/** GET /client/subscriptions/upgrade-quote */
data class UpgradeQuoteDto(
    val amount: Double = 0.0,
    val effectiveDays: Int = 0,
    val currency: String = "",
)

/**
 * GET /client/devices — tolerant to the different HWID-list shapes the backend / Remnawave
 * proxy may return. Historically we only read a flat `items` array, which yielded an empty
 * list (and a blank Devices screen) whenever the backend actually nested the devices under
 * `devices` or a Remnawave-style `response.devices`. [devices] normalizes all of them.
 */
data class DevicesDto(
    @SerializedName(value = "items", alternate = ["devices", "hwidDevices"])
    val items: List<DeviceDto> = emptyList(),
    // Remnawave HWID endpoint shape: { response: { total, devices: [...] } }
    val response: DevicesWrapperDto? = null,
) {
    /** The device list regardless of whether the backend returns it flat or nested. */
    fun devices(): List<DeviceDto> = when {
        items.isNotEmpty() -> items
        else -> response?.devices.orEmpty()
    }
}

/** Remnawave-style nested wrapper: { response: { total, devices: [...] } }. */
data class DevicesWrapperDto(
    @SerializedName(value = "devices", alternate = ["items", "hwidDevices"])
    val devices: List<DeviceDto> = emptyList(),
    val total: Int = 0,
)

/**
 * A device bound to a subscription (HWID). Field names carry Gson [SerializedName] alternates
 * because the backend/Remnawave may label the same value differently (model vs deviceModel,
 * updatedAt vs lastActiveAt, etc.).
 */
data class DeviceDto(
    val hwid: String = "",
    val platform: String? = null,
    @SerializedName(value = "deviceModel", alternate = ["model", "deviceName", "device"])
    val deviceModel: String? = null,
    @SerializedName(value = "appVersion", alternate = ["osVersion", "userAgent"])
    val appVersion: String? = null,
    @SerializedName(value = "lastActiveAt", alternate = ["updatedAt", "lastSeen", "createdAt"])
    val lastActiveAt: String? = null,
)

/** Parsed devices plus the raw HTTP status/body, so the UI can surface a diagnostic. */
data class DevicesResult(
    val devices: List<DeviceDto> = emptyList(),
    val httpCode: Int = 0,
    val rawBody: String = "",
)

data class DeleteDeviceRequestDto(
    val hwid: String,
    val uuid: String,
)

// region promo codes

data class PromoRequestDto(
    val code: String,
)

/** POST /client/promo-code/check */
data class PromoDto(
    val type: String = "",
    val discountPercent: Double? = null,
    val durationDays: Int? = null,
)

// endregion

/** GET /client/referral-stats */
data class ReferralStatsDto(
    val referralCode: String = "",
    val referralPercent: Double = 0.0,
    val totalReferrals: Int = 0,
    val totalEarned: Double = 0.0,
    val currency: String = "",
)
