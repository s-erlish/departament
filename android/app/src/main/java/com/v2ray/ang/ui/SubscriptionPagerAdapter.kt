package com.v2ray.ang.ui

import android.animation.ValueAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.v2ray.ang.R
import com.v2ray.ang.auth.AuthTokenStore
import com.v2ray.ang.auth.SubscriptionSyncManager
import com.v2ray.ang.auth.dto.SubInfoDto
import com.v2ray.ang.databinding.ItemSubscriptionCardBinding
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.dto.entities.usedTraffic
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.reducedMotion
import java.util.Locale

/**
 * ONE ПОДПИСКА = ONE PAGE, AND THE PAGE IS THE TRAFFIC RING (handoff README §5.2).
 *
 * This used to bind a whole subscription card — name, badge, expiry, meter, devices row,
 * «Продлить», auto-renew — and §5 lays every one of those out on the tab's own spine instead, once,
 * for whichever page is showing. [AccountFragment.renderSelectedSub] binds them now; what is left
 * here is the one band worth swiping, which is how much traffic THIS подписка has used.
 *
 * The adapter is deliberately dependency-free again: it takes no callbacks and no resolvers,
 * because a ring has nothing to tap and nothing to look up.
 *
 * THE FOUR STATES ARE THE METER'S FOUR STATES, unchanged in substance — the ring is the meter,
 * redrawn:
 *
 *  - under quota  the arc fills to the real fraction, «1,9 ТБ» over «из 5 ТБ», accent;
 *  - OVER quota   the arc is closed and the arc AND the figure go red, caption «Лимит исчерпан».
 *                 An exhausted allowance has to look exhausted; a full accent ring reads as a
 *                 healthy tank;
 *  - unlimited    the arc is closed at the accent, «2,0 ТБ» over «без ограничений». A ring with no
 *                 ceiling would otherwise be a fraction of nothing;
 *  - NO DATA      no figure, caption «Нет данных», empty arc. `/client/subscription/all` carries no
 *                 connect payload, so `raw` is null for every SECONDARY подписка; a stated absence
 *                 is finished, a blank disc is not.
 *
 * Both colours are re-applied on EVERY bind, never only in the branch that needs them: this is a
 * recycled holder, and a page that inherited the previous подписка's red arc would report a limit
 * that is not its own.
 */
class SubscriptionPagerAdapter : RecyclerView.Adapter<SubscriptionPagerAdapter.VH>() {

    private val items = mutableListOf<SubInfoDto>()

    init {
        // The page IS the подписка, and [identityOf] is the подписка's name in every other store on
        // this screen, so the recycler can be told which page is which. Without stable ids a
        // structural change hands ViewPager2 a set of pages it has to treat as all-new.
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = identityOf(items[position]).hashCode().toLong()

    /**
     * Publishes [list].
     *
     * A REFRESH THAT BRINGS THE SAME ПОДПИСКИ REBINDS, IT DOES NOT REBUILD. The tab loads twice by
     * design — the cache answers first, the network a moment later — and both answers came through
     * `notifyDataSetChanged()`, which tells ViewPager2 that every page it is holding is void: pages
     * are re-created, the pager re-lays-out, re-snaps and re-dispatches its page-selected callback,
     * with the ring in the middle of it. That is the horizontal shudder the owner sees while the
     * tab pulls its data in, and §5's rule for this object rules it out: «кольцо стоит на месте с
     * первого кадра, меняется только заливка».
     *
     * So the identities are compared first. Same подписки in the same order — the overwhelmingly
     * common case, since a refresh changes BYTES and not membership — and the pages stay exactly
     * where they are while [onBindViewHolder] writes the new figures into them, which is the sweep
     * the design asks for. Only a genuine change of membership goes through the structural notify,
     * and stable ids keep the surviving pages in place even then.
     */
    fun submit(list: List<SubInfoDto>) {
        val sameSet = items.size == list.size &&
            items.indices.all { identityOf(items[it]) == identityOf(list[it]) }
        items.clear()
        items.addAll(list)
        if (sameSet) notifyItemRangeChanged(0, items.size) else notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemSubscriptionCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return VH(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    /** A page that scrolls out mid-sweep must not keep an animator pointed at a recycled view. */
    override fun onViewRecycled(holder: VH) {
        super.onViewRecycled(holder)
        holder.cancelSweep()
    }

    inner class VH(private val binding: ItemSubscriptionCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private var sweep: ValueAnimator? = null

        /**
         * Identity of the подписка this holder last drew. §5's «переход 500 мс» is about a figure
         * that CHANGED — a refresh landing new bytes for the подписка already on screen — and not
         * about a holder being pointed at a different подписка by the recycler. The second case
         * lands instantly, because a sweep there would animate a value that was never true.
         */
        private var boundKey: String? = null

        fun bind(sub: SubInfoDto) {
            val key = identityOf(sub)
            val sameSub = key == boundKey
            boundKey = key
            bindTrafficRing(sub, animate = sameSub)
        }

        fun cancelSweep() {
            sweep?.cancel()
            sweep = null
        }

        private fun bindTrafficRing(sub: SubInfoDto, animate: Boolean) {
            val ctx = binding.root.context
            val raw = sub.subscription?.raw()

            // THE SAME FIGURES THE METER READ, and for the same reason: the backend's own record
            // first, the `subscription-userinfo` header persisted on the local [SubscriptionItem]
            // as the fallback. `/client/subscription/all` carries no connect payload, so without
            // the fallback every SECONDARY подписка reports «Нет данных» here while Главная — which
            // meters from the header — shows real bytes for the same подписка.
            val local = localSubscription(sub)
            val used: Long = raw?.trafficUsed
                ?: raw?.userTraffic?.usedTrafficBytes
                ?: local?.usedTraffic
                ?: 0L
            val limit: Long? = raw?.trafficLimitBytes
                ?: local?.totalTraffic?.takeIf { it > 0L }

            val accent = MaterialColors.getColor(binding.ringTraffic, androidx.appcompat.R.attr.colorPrimary)
            val onSurface = MaterialColors.getColor(
                binding.tvRingValue, com.google.android.material.R.attr.colorOnSurface
            )
            val danger = MaterialColors.getColor(binding.tvRingValue, R.attr.colorDestructiveText)

            if (used <= 0L && limit == null) {
                binding.tvRingValue.visibility = View.GONE
                binding.tvRingCaption.setText(R.string.account_meter_traffic_none)
                binding.ringTraffic.setIndicatorColor(accent)
                setSweep(0, animate)
                return
            }

            binding.tvRingValue.visibility = View.VISIBLE
            binding.tvRingValue.text = formatBytes(used)

            if (limit == null || limit <= 0L) {
                binding.tvRingValue.setTextColor(onSurface)
                binding.tvRingCaption.setText(R.string.account_ring_unlimited)
                binding.ringTraffic.setIndicatorColor(accent)
                setSweep(RING_MAX, animate)
                return
            }

            val exhausted = used >= limit
            binding.ringTraffic.setIndicatorColor(if (exhausted) danger else accent)
            binding.tvRingValue.setTextColor(if (exhausted) danger else onSurface)
            binding.tvRingCaption.text = if (exhausted) {
                ctx.getString(R.string.account_meter_traffic_over)
            } else {
                ctx.getString(R.string.account_ring_of, formatBytes(limit))
            }
            val fraction = (used.toDouble() / limit.toDouble()).coerceIn(0.0, 1.0)
            setSweep(Math.round(fraction * RING_MAX).toInt(), animate)
        }

        /**
         * Moves the arc to [target].
         *
         * §5 asks for a 500ms transition on §8's ease-out-quart, and CircularProgressIndicator's own
         * `setProgressCompat(_, true)` runs on the library's clock instead — so the sweep is driven
         * frame by frame here and the indicator is only ever asked for instant values. Reduced
         * motion (and a first paint) land on the number with no travel at all.
         */
        private fun setSweep(target: Int, animate: Boolean) {
            cancelSweep()
            val ring = binding.ringTraffic
            val from = ring.progress
            if (!animate || from == target || ring.reducedMotion()) {
                ring.setProgressCompat(target, false)
                return
            }
            sweep = ValueAnimator.ofInt(from, target).apply {
                duration = RING_SWEEP_MS
                interpolator = AnimationUtils.loadInterpolator(ring.context, R.interpolator.ease_out_quart)
                addUpdateListener { ring.setProgressCompat(it.animatedValue as Int, false) }
                start()
            }
        }

        /**
         * The LOCAL подписка this account record was imported as, or null when it has not been
         * imported (or the map cannot be read). Keyed exactly the way
         * `SubscriptionSyncManager.identityOf` writes the map, because a mismatch here is silently a
         * null rather than a wrong page.
         */
        private fun localSubscription(sub: SubInfoDto): SubscriptionItem? {
            val identity = identityOf(sub)
            if (identity.isBlank()) return null
            return runCatching {
                AuthTokenStore.getManagedGuids()[identity]
                    ?.takeIf { it.isNotBlank() }
                    ?.let { MmkvManager.decodeSubscription(it) }
            }.getOrNull()
        }
    }

    private companion object {
        /**
         * The indicator's `android:max`, mirrored from item_subscription_card.xml. 1000 and not 100
         * so a percent-and-a-bit of a terabyte plan still moves the arc: at max=100 every change
         * under 1% quantised to no movement at all, which is a sweep that never runs.
         */
        const val RING_MAX = 1000

        /** §5 «переход 500 мс». Not in motion.xml — see the report's token request. */
        const val RING_SWEEP_MS = 500L
    }
}

/**
 * The key a подписка is stored under, root by its constant and anything else by its remnawave uuid
 * falling back to its id — the same rule `SubscriptionSyncManager.identityOf` writes the managed-guid
 * map with, so a lookup either hits the right local record or misses cleanly.
 */
private fun identityOf(sub: SubInfoDto): String =
    if (sub.type.equals(SubscriptionSyncManager.TYPE_ROOT, ignoreCase = true)) {
        SubscriptionSyncManager.TYPE_ROOT
    } else {
        sub.remnawaveUuid.ifBlank { sub.id }
    }

/**
 * Bytes as the product writes them: Russian units, one decimal past kilobytes. A file-private copy
 * for the same reason [identityOf] is one — this adapter depends on no screen.
 */
private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 Б"
    val units = arrayOf("Б", "КБ", "МБ", "ГБ", "ТБ")
    var value = bytes.toDouble()
    var idx = 0
    while (value >= 1024.0 && idx < units.size - 1) {
        value /= 1024.0
        idx++
    }
    val formatted = if (idx == 0) value.toLong().toString()
    else String.format(Locale.US, "%.1f", value).replace('.', ',')
    return "$formatted ${units[idx]}"
}
