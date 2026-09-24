package com.v2ray.ang.ui.adapter

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.v2ray.ang.R
import com.v2ray.ang.auth.dto.DeviceDto
import com.v2ray.ang.databinding.ItemDeviceBinding
import com.v2ray.ang.ui.component.curve
import com.v2ray.ang.ui.component.durationOf
import com.v2ray.ang.ui.component.onSingleClick
import com.v2ray.ang.util.reducedMotion
import java.util.Locale

/**
 * Renders the devices bound to a subscription (by HWID) as rows inside one card.
 *
 * Two rules from handoff README §7 «Устройства» live here rather than in the layout, because both
 * of them are decided per row and not per screen:
 *
 *  - **Своё устройство помечено «Это устройство» акцентом и не удаляется.** [setOwnHwid] carries
 *    this installation's device id; the row that matches it swaps the destructive action for the
 *    accent badge. It is not a disabled «Удалить»: there is no circumstance in which you may unbind
 *    the phone you are reading this on, so the affordance is absent rather than dimmed.
 *  - **«При удалении строка гаснет, подпись меняется на "Отключено от подписки", через 700 мс
 *    уходит из списка».** [release] plays that, and the host waits for it before refetching.
 */
class DeviceAdapter(
    private val onDelete: (DeviceDto) -> Unit,
) : RecyclerView.Adapter<DeviceAdapter.VH>() {

    private val items = mutableListOf<DeviceDto>()

    /**
     * This installation's own HWID, NORMALISED, or null while it is unknown. Null marks nothing
     * rather than guessing: a wrong «Это устройство» would hide the only control that can free a
     * slot.
     */
    private var ownHwid: String? = null

    /**
     * The HWID being released. Its row draws §7's farewell state instead of its meta line, and it
     * survives the rebind a scroll would cause halfway through the 700ms.
     */
    private var releasingHwid: String? = null

    fun submit(list: List<DeviceDto>) {
        items.clear()
        items.addAll(list)
        releasingHwid = null
        notifyDataSetChanged()
    }

    /** Sets which HWID is this device. Rebinds so an already-drawn list picks the badge up. */
    fun setOwnHwid(hwid: String?) {
        val next = normalizeHwid(hwid)
        if (next == ownHwid) return
        ownHwid = next
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val ctx = holder.itemView.context
        val b = holder.binding

        b.tvDeviceName.text = item.deviceModel?.takeIf { it.isNotBlank() }
            ?: item.platform?.takeIf { it.isNotBlank() }
            ?: ctx.getString(R.string.devices_unknown_model)

        // ONE subtitle, «Активно: … · ID: …», as the prototype draws it. It used to be two
        // stacked lines, which made a three-line row out of a two-line design (§11 grabl 9).
        val platform = item.platform?.takeIf { it.isNotBlank() }
        val lastActive = formatIsoDate(item.lastActiveAt)
        val activity = when {
            lastActive.isNotBlank() && platform != null ->
                "$platform · " + ctx.getString(R.string.devices_last_active, lastActive)
            lastActive.isNotBlank() -> ctx.getString(R.string.devices_last_active, lastActive)
            platform != null -> platform
            else -> item.appVersion?.takeIf { it.isNotBlank() }.orEmpty()
        }
        val hwidLine = item.hwid.takeIf { it.isNotBlank() }
            ?.let { ctx.getString(R.string.devices_hwid, it) }
            .orEmpty()
        val meta = listOf(activity, hwidLine).filter { it.isNotBlank() }.joinToString(" · ")

        val releasing = releasingHwid != null && item.hwid == releasingHwid
        b.tvDeviceMeta.text = if (releasing) ctx.getString(R.string.devices_disconnected) else meta
        b.tvDeviceMeta.visibility = if (releasing || meta.isNotBlank()) View.VISIBLE else View.GONE

        // Handoff README §7 draws the devices as rows in ONE card, so the rule between
        // two rows belongs to the row below it — and never above the first, which would
        // draw a line across the card's own top edge.
        b.deviceDivider.visibility = if (position == 0) View.GONE else View.VISIBLE

        val isOwn = ownHwid != null && normalizeHwid(item.hwid) == ownHwid
        b.tvDeviceBadge.visibility = if (isOwn) View.VISIBLE else View.GONE
        // A releasing row keeps neither: its action is already spent, and offering «Удалить» on a
        // row that says «Отключено от подписки» invites a second request for the same slot.
        b.btnDeviceDelete.visibility = if (isOwn || releasing) View.GONE else View.VISIBLE
        // Guarded: two taps are two release requests for the same slot, and the row cannot hide
        // itself fast enough to stop the second — `releasing` only arrives with the next bind.
        b.btnDeviceDelete.onSingleClick { onDelete(item) }

        // A recycled holder can arrive carrying the alpha a previous release left on it.
        holder.itemView.alpha = if (releasing) RELEASED_ALPHA else 1f
    }

    /**
     * Plays §7's release on [device]'s row and calls [onGone] when the 700ms is up.
     *
     * The whole gesture is ONE [AnimatorSet] and not a chain of `postDelayed` calls: dim over
     * `motion_state`, hold for as long as it takes to read the new subtitle, then fade out so the
     * row is gone at exactly `motion_device_release`. Reduced motion snaps — the row is removed at
     * once, with no half-lit intermediate state.
     *
     * If the row is not attached (scrolled off, or the list has already moved on) there is nothing
     * to animate and [onGone] runs immediately, so the caller's refresh can never strand.
     */
    fun release(device: DeviceDto, recycler: RecyclerView, onGone: () -> Unit) {
        val index = items.indexOfFirst { it.hwid == device.hwid }
        if (index < 0 || device.hwid.isBlank()) {
            onGone()
            return
        }
        releasingHwid = device.hwid
        notifyItemChanged(index)

        val row = recycler.findViewHolderForAdapterPosition(index)?.itemView
        if (row == null || row.reducedMotion()) {
            onGone()
            return
        }

        val total = row.durationOf(R.integer.motion_device_release)
        val dimFor = row.durationOf(R.integer.motion_state)
        val leaveFor = row.durationOf(R.integer.motion_state_exit)

        val dim = ObjectAnimator.ofFloat(row, View.ALPHA, 1f, RELEASED_ALPHA).apply {
            duration = dimFor
            interpolator = row.curve(R.interpolator.ease_out_quart)
        }
        val leave = ObjectAnimator.ofFloat(row, View.ALPHA, RELEASED_ALPHA, 0f).apply {
            duration = leaveFor
            // The held moment between the two is what makes «Отключено от подписки» readable.
            startDelay = (total - dimFor - leaveFor).coerceAtLeast(0L)
            interpolator = row.curve(R.interpolator.ease_standard)
        }
        AnimatorSet().apply {
            playSequentially(dim, leave)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // The view returns to the recycler's pool; leaving it transparent would hand
                    // the next device an invisible row.
                    row.alpha = 1f
                    onGone()
                }
            })
            start()
        }
    }

    class VH(val binding: ItemDeviceBinding) : RecyclerView.ViewHolder(binding.root)

    private companion object {
        /** 0.38 — the disabled alpha of the row grammar, which is what a released row now is. */
        const val RELEASED_ALPHA = 0.38f
    }
}

/**
 * One comparable shape for an HWID: lowercase, no dashes, trimmed; blank becomes null.
 *
 * The app sends 32 lowercase hex characters, but «Это устройство» was decided by `==` against
 * whatever the panel echoed back, and Remnawave's device list is a store of strings it did not
 * mint — a proxy that upper-cases the value, or hands it back in the dashed UUID form the id is
 * shaped after, made the badge miss and the row read as somebody else's device. Both sides of the
 * comparison come through here, so the badge answers the question it is asked ("is this row this
 * phone") rather than "did the two spellings match".
 */
private fun normalizeHwid(hwid: String?): String? =
    hwid?.trim()?.replace("-", "")?.lowercase(Locale.US)?.takeIf { it.isNotBlank() }

/** ISO-8601 (or date-only) -> dd.MM.yyyy. Returns "" for blank/unparseable input. */
private fun formatIsoDate(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    val datePart = iso.substringBefore('T')
    val parts = datePart.split('-')
    return if (parts.size == 3) "${parts[2]}.${parts[1]}.${parts[0]}" else datePart
}
