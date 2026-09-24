package com.v2ray.ang.auth.dto

/**
 * Public (unauthenticated) endpoints of the Departament backend.
 *
 *  GET /public/config        -> [PublicConfigDto]
 *  GET /public/tariffs       -> [TariffCatalogDto]
 *  GET /public/server-status -> List<[ServerStatusDto]>
 *
 * All fields default to safe/empty values so a partial or missing payload never crashes Gson.
 */

/** GET /public/config */
data class PublicConfigDto(
    val telegramBotUsername: String = "",
    val publicAppUrl: String = "",
    val siteUrl: String = "",
    val plategaMethods: List<PlategaMethodDto> = emptyList(),
    val trialEnabled: Boolean = false,
    val defaultReferralPercent: Double = 0.0,
)

/** A selectable Platega payment method. */
data class PlategaMethodDto(
    val id: String = "",
    val label: String = "",
)

/** GET /public/tariffs */
data class TariffCatalogDto(
    val items: List<TariffGroupDto> = emptyList(),
)

/** A named group (category) of tariffs. */
data class TariffGroupDto(
    val id: String = "",
    val name: String = "",
    val emoji: String = "",
    val tariffs: List<TariffDto> = emptyList(),
)

/** A single tariff/plan. `trafficLimitBytes == null` means unlimited traffic. */
data class TariffDto(
    val id: String = "",
    val name: String = "",
    val durationDays: Int = 0,
    val trafficLimitBytes: Long? = null,
    val includedDevices: Int = 0,
    val pricePerExtraDevice: Double = 0.0,
    val maxExtraDevices: Int = 0,
    val price: Double = 0.0,
    val currency: String = "",
    val priceOptions: List<PriceOptionDto> = emptyList(),
) {
    fun isUnlimitedTraffic(): Boolean = trafficLimitBytes == null
}

/** A duration/price option for a tariff. */
data class PriceOptionDto(
    val id: String = "",
    val durationDays: Int = 0,
    val price: Double = 0.0,
    val sortOrder: Int = 0,
)

/** One entry of GET /public/server-status. */
data class ServerStatusDto(
    val countryCode: String = "",
    val online: Boolean = false,
)
