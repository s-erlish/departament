package com.v2ray.ang.ui.adapter

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.v2ray.ang.R
import com.v2ray.ang.auth.dto.PaymentDto
import com.v2ray.ang.auth.dto.PaymentOutcome
import com.v2ray.ang.auth.dto.paymentOutcomeOf
import com.v2ray.ang.databinding.ItemPaymentBinding
import java.util.Locale

/**
 * Renders the account payment/order history as rows inside ONE card (handoff README §7).
 *
 * THE STATUS IS PART OF THE SENTENCE. The prototype writes «03.08.2026 · Оплачено» on the row's
 * second line and dims the AMOUNT of anything that has not settled; this used to be a coloured chip
 * in a second right-hand column, which made every row three lines tall and gave a read-only ledger
 * four accent colours. Nothing about the status is lost:
 *
 *  - all four states are still NAMED, which is what the user reads;
 *  - a FAILED one is still coloured, because a payment that did not go through is the only thing on
 *    this screen worth interrupting for, and destructive red is the app's word for it;
 *  - anything not yet settled still dims its sum, which is the design's own signal that the figure
 *    is provisional.
 *
 * SINCE THE OWNER NARROWED THE LEDGER to «в обработке» and «успешны» (see [paymentsForHistory]),
 * the destructive branch below no longer fires from the history screen — its two statuses are
 * filtered out before they reach a row. It stays, and so does every status in [statusStyle],
 * because that table is the app's ONE reading of a raw backend status: the payment-confirmation
 * poll and any screen that lists a single operation share it, and a renderer that could not draw a
 * failure would be a table with a hole in it. The meta line does NOT become redundant: with paid
 * and pending both on screen, «03.08.2026 · Оплачено» versus «03.08.2026 · В обработке» is the
 * only thing that tells them apart.
 */
class PaymentsAdapter : RecyclerView.Adapter<PaymentsAdapter.VH>() {

    private val items = mutableListOf<PaymentDto>()

    fun submit(list: List<PaymentDto>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(ItemPaymentBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val ctx = holder.itemView.context
        val b = holder.binding

        b.tvPaymentDesc.text = item.description.ifBlank { item.kind.ifBlank { item.orderId } }

        // «03.08.2026 · Оплачено» — one muted line, and either half may be missing without
        // leaving a stray separator behind.
        val state = statusStyle(item.status)
        val statusText = if (state.labelRes != 0) ctx.getString(state.labelRes) else item.status
        b.tvPaymentDate.text = listOf(formatIsoDate(item.createdAt), statusText)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        b.tvPaymentDate.setTextColor(
            if (state.failed) {
                resolveThemeColor(ctx, R.attr.colorDestructiveText)
            } else {
                resolveThemeColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant)
            },
        )

        b.tvPaymentAmount.text = formatMoney(item.amount, item.currency)
        // An unsettled sum is provisional, and the design says so by dimming it rather than by
        // adding a fifth colour to a ledger.
        b.tvPaymentAmount.setTextColor(
            resolveThemeColor(
                ctx,
                if (state.settled) {
                    com.google.android.material.R.attr.colorOnSurface
                } else {
                    com.google.android.material.R.attr.colorOnSurfaceVariant
                },
            ),
        )

        // A card of rows needs a rule between them and the card cannot draw one; never above
        // the first row, which would cut a line across the card's own top edge.
        b.paymentDivider.visibility = if (position == 0) View.GONE else View.VISIBLE
    }

    class VH(val binding: ItemPaymentBinding) : RecyclerView.ViewHolder(binding.root)
}

/**
 * What a raw backend status means to this row: the word to print, whether the money is actually
 * gone (a settled sum is stated at full strength), whether it went wrong (the one case that still
 * earns a colour) and whether the operation belongs on this screen at all.
 *
 * The WORDS themselves are no longer read here: [paymentOutcomeOf], beside the DTOs that carry the
 * field, is the app's one reading of a raw status, and this table maps its answer to a label and a
 * colour. The balance purchase reads the same function to decide whether it may say «Оплачено», so
 * one operation cannot be settled on one screen and pending on another.
 *
 * [labelRes] 0 means the backend sent something this build does not know; the raw value is printed
 * rather than swallowed, and it is treated as unsettled, which is the safe direction — a sum shown
 * at full strength is a claim that it was charged.
 *
 * [listed] IS THE OWNER'S RULING, NOT A BUG FIX: «оставить только те что в обработке и те, что
 * успешны». A failed, declined, cancelled or expired attempt is a thing that did not happen, and
 * the ledger is a record of what did. NOTHING IS DELETED to achieve it — every status this build
 * ever knew is still mapped here, still carries its word and its colour, and the API layer and
 * [PaymentDto] are untouched; the payment-confirmation poll still reads the same field and still
 * recognises the same paid-set. Only the SCREEN narrows.
 *
 * An UNKNOWN status stays listed on purpose. The owner's complaint is that too few operations are
 * shown, and an allow-list of five spellings would hide anything the backend renames — the safe
 * direction here is to hide only what is explicitly named as not-happening.
 */
private data class PaymentState(
    val labelRes: Int,
    val settled: Boolean,
    val failed: Boolean,
    val listed: Boolean,
)

private fun statusStyle(status: String): PaymentState = when (paymentOutcomeOf(status)) {
    PaymentOutcome.SETTLED ->
        PaymentState(R.string.account_status_paid, settled = true, failed = false, listed = true)

    PaymentOutcome.PENDING ->
        PaymentState(R.string.account_status_pending, settled = false, failed = false, listed = true)

    PaymentOutcome.FAILED ->
        PaymentState(R.string.account_status_failed, settled = false, failed = true, listed = false)

    PaymentOutcome.CANCELED ->
        PaymentState(R.string.account_status_canceled, settled = false, failed = false, listed = false)

    PaymentOutcome.UNKNOWN -> PaymentState(0, settled = false, failed = false, listed = true)
}

/**
 * The history screen's own view of the account's operations: only the ones that are settled or
 * still being settled, newest first.
 *
 * It lives beside [statusStyle] so there is ONE status table in the app rather than a filter that
 * knows a different set of spellings from the renderer. Sorting is here for the same reason the
 * desktop sorts: `createdAt` is an ISO-8601 instant, so an ordinal string compare IS a chronological
 * compare, and the backend does not promise an order.
 */
fun paymentsForHistory(all: List<PaymentDto>): List<PaymentDto> =
    all.filter { statusStyle(it.status).listed }.sortedByDescending { it.createdAt }

/**
 * The fraction is separated by a COMMA — the interface is Russian, and the account tab's ring next
 * door already writes «2,0 ТБ». The number is still composed under [Locale.US] so the grouping and
 * the digit shapes cannot follow a phone set to Farsi or Bengali; only the decimal mark is swapped.
 */
private fun formatMoney(amount: Double, currency: String): String {
    val n = if (amount % 1.0 == 0.0) amount.toLong().toString()
    else String.format(Locale.US, "%.2f", amount).replace('.', ',')
    if (currency.isBlank()) return n
    val symbol = when (currency.uppercase(Locale.US)) {
        "RUB" -> "₽"
        "USD" -> "$"
        "EUR" -> "€"
        "KZT" -> "₸"
        "UAH" -> "₴"
        else -> currency
    }
    return "$n $symbol"
}

private fun formatIsoDate(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    val datePart = iso.substringBefore('T')
    val parts = datePart.split('-')
    return if (parts.size == 3) "${parts[2]}.${parts[1]}.${parts[0]}" else datePart
}

private fun resolveThemeColor(context: android.content.Context, attr: Int): Int {
    val tv = TypedValue()
    context.theme.resolveAttribute(attr, tv, true)
    return tv.data
}
