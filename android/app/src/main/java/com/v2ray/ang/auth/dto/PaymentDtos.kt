package com.v2ray.ang.auth.dto

import com.google.gson.annotations.SerializedName

/**
 * Payment endpoints of the Departament backend.
 *
 *  POST /client/payments/platega -> [PaymentInitDto]
 *  POST /client/payments/balance -> [PaymentResultDto]
 *  GET  /client/payments         -> [PaymentsDto]
 */

/**
 * Body for POST /client/payments/platega and /client/payments/balance.
 * All fields optional — the caller fills only what a given purchase needs.
 */
data class PaymentRequestDto(
    val amount: Double? = null,
    val currency: String? = null,
    val tariffId: String? = null,
    val tariffPriceOptionId: String? = null,
    val deviceCount: Int? = null,
    val paymentMethod: Int? = null,
    val promoCode: String? = null,
    val subscriptionUuid: String? = null,
)

/** Returned when a payment provider checkout URL is issued (Platega, add-devices, upgrade). */
data class PaymentInitDto(
    val paymentUrl: String = "",
    val paymentId: String = "",
    val orderId: String = "",
)

/**
 * Returned by a balance (wallet) payment.
 *
 * «Settles immediately» is what the endpoint is FOR, not what it promises: the reply is a status,
 * and the status is the answer. Read it with [paymentOutcomeOf] — a 2xx alone says the request was
 * accepted, never that the money moved.
 */
data class PaymentResultDto(
    val status: String = "",
    val orderId: String = "",
)

/** What a raw backend payment status means to this app. @see paymentOutcomeOf */
enum class PaymentOutcome {
    /** The money moved. */
    SETTLED,

    /** Accepted, not yet settled — the provider or the bank still has it. */
    PENDING,

    /** It did not go through. */
    FAILED,

    /** It was called off; nothing happened and nothing will. */
    CANCELED,

    /** A word this build does not know. Callers decide the safe direction for their own case. */
    UNKNOWN,
}

/**
 * THE APP'S ONE READING OF A RAW BACKEND PAYMENT STATUS.
 *
 * It lives here, beside the two DTOs that carry the field ([PaymentDto.status] and
 * [PaymentResultDto.status]), because two readers need it and neither may own it: the history
 * screen renders these words, and the balance purchase decides on them whether it may say
 * «Оплачено». A second `when` over the same spellings is how one screen ends up calling a status
 * settled while the other calls it pending, on the same operation.
 *
 * The spellings are the set `ui/adapter/PaymentsAdapter.kt` accumulated against the live backend;
 * that file now maps this outcome to its label and colour rather than re-deriving it.
 */
fun paymentOutcomeOf(status: String): PaymentOutcome = when (status.trim().lowercase(java.util.Locale.US)) {
    "paid", "success", "succeeded", "completed", "confirmed" -> PaymentOutcome.SETTLED
    "pending", "processing", "new", "created", "waiting", "in_progress" -> PaymentOutcome.PENDING
    "failed", "error", "declined", "rejected" -> PaymentOutcome.FAILED
    "canceled", "cancelled", "expired" -> PaymentOutcome.CANCELED
    else -> PaymentOutcome.UNKNOWN
}

/**
 * GET /client/payments
 *
 * The envelope key carries alternates for the same reason [com.v2ray.ang.auth.dto.DevicesDto]
 * does, and this endpoint had never been given the treatment: a flat `items` read is right only
 * while the backend happens to name it `items`, and every other name yields an EMPTY history with
 * no error to explain it — which is precisely the failure the Devices screen shipped with once.
 * [payments] is what callers read, so the shape stops being their problem.
 */
data class PaymentsDto(
    @SerializedName(value = "items", alternate = ["payments", "data", "results", "orders"])
    val items: List<PaymentDto> = emptyList(),
    // Remnawave-style nesting, the same one DevicesDto already normalizes.
    val response: PaymentsWrapperDto? = null,
) {
    /** The operations regardless of whether the backend returns them flat or nested. */
    fun payments(): List<PaymentDto> = when {
        items.isNotEmpty() -> items
        else -> response?.items.orEmpty()
    }
}

/** Nested shape: `{ response: { total, items: [...] } }`. */
data class PaymentsWrapperDto(
    @SerializedName(value = "items", alternate = ["payments", "data", "results", "orders"])
    val items: List<PaymentDto> = emptyList(),
    val total: Int = 0,
)

/**
 * A single payment/order history entry.
 *
 * ALTERNATES ARE NOT DECORATION HERE. Two of these fields are load-bearing beyond their own line:
 * [createdAt] draws the date AND orders the list (the history is sorted newest-first by an ordinal
 * compare on it), so a name that fails to bind does not show up as a blank column — it silently
 * turns the sort into a no-op and leaves the rows in whatever order the backend happened to send.
 * [status] decides whether a row is shown at all, since the owner asked for «в обработке» and
 * «успешны» only; an unbound status reads as unrecognised, which the filter deliberately lets
 * through, so the screen degrades to showing everything rather than to showing nothing.
 *
 * `snake_case` variants are listed because this backend is not uniformly camelCase — SubscriptionDtos
 * already carries `tariff_id` next to `tariffId` for the same reason.
 */
data class PaymentDto(
    @SerializedName(value = "id", alternate = ["paymentId", "payment_id", "uuid"])
    val id: String = "",
    @SerializedName(value = "orderId", alternate = ["order_id", "orderUuid", "number"])
    val orderId: String = "",
    val amount: Double = 0.0,
    val currency: String = "",
    val status: String = "",
    val provider: String = "",
    @SerializedName(value = "kind", alternate = ["type", "category"])
    val kind: String = "",
    @SerializedName(value = "description", alternate = ["title", "comment"])
    val description: String = "",
    @SerializedName(value = "createdAt", alternate = ["created_at", "date", "paidAt", "paid_at", "updatedAt"])
    val createdAt: String = "",
)
