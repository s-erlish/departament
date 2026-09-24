package com.v2ray.ang.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.v2ray.ang.R
import com.v2ray.ang.databinding.LayoutSubscriptionMetaBarBinding
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.component.expandTouchTarget
import com.v2ray.ang.ui.component.onSingleClick
import com.v2ray.ang.util.reducedMotion

/**
 * Главная's subscription card as a swipeable carousel: one page per подписка.
 *
 * This file was deleted by the wave that replaced the card with a «Подписка» navigation row; the
 * owner overruled that on 2026-07-26 («как выглядела главная по функционалу такая и должна
 * остаться»), so it is back, unchanged in behaviour.
 *
 * Each page reuses the shared [LayoutSubscriptionMetaBarBinding] and is painted by [bindPage]
 * (HomeFragment's `bindMetaBar`), so the single-подписка case looks identical to one static card.
 *
 * **Per-page actions carry the page's own subscription id.** That is the whole reason the carousel
 * exists rather than one card with global buttons: pin, delete, support and Telegram act on the
 * подписка whose card is under the thumb, never on "the first one". The id is handed to [bindPage]
 * for the same reason — the card's NAME is resolved per подписка, and a decoded [SubscriptionItem]
 * is a fresh object every time, so it cannot be matched back to its own id by identity.
 *
 * Only the three genuinely list-wide actions - collapse the list, ping every server, refresh every
 * подписка - are page-independent, and they are declared as such here rather than inferred at the
 * call site.
 *
 * The adapter holds no ViewModel reference: everything it needs arrives as a lambda.
 */
class HomeMetaPagerAdapter(
    private val bindPage: (LayoutSubscriptionMetaBarBinding, String, SubscriptionItem?) -> Unit,
    private val onToggleList: () -> Unit,
    private val onPingAll: () -> Unit,
    private val onRefreshAll: () -> Unit,
    private val onTogglePin: (String) -> Unit,
    private val onDeleteSub: (String) -> Unit,
    private val onOpenSupport: (String) -> Unit,
    private val onOpenTelegram: (String) -> Unit,
    private val collapsed: () -> Boolean,
) : RecyclerView.Adapter<HomeMetaPagerAdapter.VH>() {

    private val subIds = mutableListOf<String>()

    /** Replaces the pages with [ids] and repaints. */
    @SuppressLint("NotifyDataSetChanged")
    fun submit(ids: List<String>) {
        subIds.clear()
        subIds.addAll(ids)
        notifyDataSetChanged()
    }

    /**
     * Repaints every page in place, for a change that alters what a card SAYS rather than which
     * cards exist — the account answering with its own nicknames, and the chevron flipping when the
     * server list collapses.
     *
     * `notifyItemRangeChanged`, NEVER `notifyDataSetChanged`. This is a ViewPager2, i.e. a
     * RecyclerView, and `notifyDataSetChanged` declares a STRUCTURAL change: the layout manager
     * discards its state and re-lays out from the start, while ViewPager2's own current-item
     * bookkeeping still believes it is on page N. The carousel visibly slides back to the first
     * card — «если скрывать раскрывать сам бар с подпиской улетает влево», because collapsing the
     * list calls straight through to here. A content-only notification rebinds the same holders in
     * place and leaves the scroll offset alone, which is exactly what a repaint means.
     *
     * AND IT CARRIES A PAYLOAD, which is the rest of that fix. A change notification with no
     * payload lets the ItemAnimator run: `SimpleItemAnimator.canReuseUpdatedViewHolder` returns
     * false for an empty payload list, RecyclerView then takes a scrap copy of the page and
     * DefaultItemAnimator cross-fades the two while translating the incoming one from its old
     * bounds to its new ones. On a page that is the full width of the viewport, that is a card
     * blinking and sliding sideways — «моргает тулбар с подпиской» and «двигается слева вправо и
     * там заезжает за экран». A non-empty payload makes the holder reusable, so the same view is
     * simply re-bound where it stands. HomeFragment also strips the ItemAnimator outright; both are
     * here because either one alone leaves a way back in.
     */
    fun repaint() {
        if (subIds.isEmpty()) return
        notifyItemRangeChanged(0, subIds.size, PAYLOAD_REPAINT)
    }

    override fun getItemCount(): Int = subIds.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = LayoutSubscriptionMetaBarBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        // A PAGE MUST BE match_parent ON BOTH AXES, AND THIS IS NOT A STYLE PREFERENCE.
        //
        // ViewPager2 installs an OnChildAttachStateChangeListener that reads the page's
        // LayoutParams the instant it is attached and throws
        // `IllegalStateException: Pages must fill the whole ViewPager2 (use match_parent)`
        // unless BOTH width and height are MATCH_PARENT (androidx.viewpager2 1.1.0,
        // ViewPager2$4.onChildViewAttachedToWindow).
        //
        // `layout_subscription_meta_bar.xml` is a wrap_content card — it has to be, because
        // `HomeFragment.measureHomeMetaHeight` measures every подписка's card and fixes the pager
        // to the tallest — so the height that arrives here is WRAP_CONTENT and has to be
        // overridden. Setting only the width (which is what this did) crashed the app the first
        // time a page was attached, i.e. the moment the user added his first подписка: with no
        // подписка the carousel has zero items and never attaches one, so the app started
        // perfectly and died on the add, and on every launch after it, since the подписка was
        // already stored by then.
        //
        // The page filling the pager is also what the carousel wants visually: the pager's height
        // is the tallest card's, so every card is that height and peeking neighbours line up.
        //
        // The ViewPager2 owns the horizontal gutters (padding) and the inter-page gap, so the
        // card's own margins go with the same call — a match_parent page plus the gutters would
        // overflow the viewport.
        binding.root.layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        // The action discs draw at 36dp because that is what the design draws, and they are TAPPED
        // at 48dp because that is the floor nothing in the product goes under (the design rules,
        // PORT-DELTA П-29). The neighbouring @id/btn_collapse is a real 48dp view and needed
        // nothing; these keep their drawn size and grow only the hit rect.
        //
        // @id/btn_pin IS NOT IN THIS LIST ANY MORE, and leaving it here would have been the whole
        // cost of hiding it. expandTouchTarget reads `getHitRect`, and a GONE child of a
        // LinearLayout is never laid out, so its rect is (0,0,0,0) — the grow would then register a
        // 48x48 delegate at the ROW'S OWN ORIGIN, which is where the collapse chevron is drawn, and
        // a CompositeTouchDelegate offers the event to it first. The card's disclosure would have
        // toggled a pin. A hidden control claims no touch area at all.
        //
        // HERE AND NOT IN onBindViewHolder: the delegate is registered on the PARENT, so a call
        // per bind would stack a fresh one on the row every time a page was recycled.
        listOf(
            binding.btnPing,
            binding.btnRefresh,
            binding.btnTelegram,
        ).forEach { it.expandTouchTarget() }
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val subId = subIds[position]
        val meta = holder.binding
        // Bound even when the подписка has just left the store: [bindPage] hides an empty card, and
        // the actions below are re-pointed at THIS position's id either way. Returning early here
        // instead would leave a recycled holder wired to the id it last showed — so the long press
        // that deletes a подписка could act on a different one from the card under the thumb.
        bindPage(meta, subId, MmkvManager.decodeSubscription(subId))
        turnCaret(meta.btnCollapse, if (collapsed()) COLLAPSED_DEGREES else 0f)
        // onSingleClick, not setOnClickListener (PORT-DELTA П-31, and SingleClick.kt's own rule
        // that every raw hit under ui/ is a defect). These six are the card's action row and four of
        // them have a duplicable effect: two subscription refreshes, two ping sweeps, two browser
        // tabs for «Поддержка», two Telegram launches. The 500ms stamp lives on the view, so a
        // recycled page carries its own guard and no per-holder state is needed.
        meta.btnCollapse.onSingleClick { onToggleList() }
        meta.btnPing.onSingleClick { onPingAll() }
        meta.btnRefresh.onSingleClick { onRefreshAll() }
        meta.btnPin.onSingleClick { onTogglePin(subId) }
        meta.btnSupport.onSingleClick { onOpenSupport(subId) }
        meta.btnTelegram.onSingleClick { onOpenTelegram(subId) }
        // Deleting a подписка, from the card that shows it. A long press is a hidden affordance and
        // the card's own layout has no free slot for a visible control, so HomeFragment says out
        // loud that it is here; a visible trailing action is filed with that layout's owner.
        meta.root.setOnLongClickListener { onDeleteSub(subId); true }
    }

    /**
     * The card's caret, and BOTH halves of the turn.
     *
     * The prototype states it on the glyph itself — `transform:rotate({{ srv.rot }})` with
     * `transition:transform 300ms cubic-bezier(.25,1,.5,1)`, i.e. @integer/motion_caret on
     * @interpolator/ease_out_quart, 0° open and −90° closed — and it is the only thing on the card
     * that says which way the list under it is about to go. It used to be assigned, so it snapped
     * while the list it points at travelled 340ms.
     *
     * It TURNS only when the value really changed and the view has been laid out at least once: a
     * page bound for the first time, or recycled onto another подписка, arrives already pointing
     * the right way and must not spin on arrival. Reduced motion assigns, like every other motion
     * in the product.
     */
    private fun turnCaret(caret: ImageView, target: Float) {
        if (caret.rotation == target) return
        if (!caret.isLaidOut || caret.reducedMotion()) {
            caret.animate().cancel()
            caret.rotation = target
            return
        }
        caret.animate().cancel()
        caret.animate()
            .rotation(target)
            .setDuration(caret.resources.getInteger(R.integer.motion_caret).toLong())
            .setInterpolator(AnimationUtils.loadInterpolator(caret.context, R.interpolator.ease_out_quart))
            .start()
    }

    class VH(val binding: LayoutSubscriptionMetaBarBinding) : RecyclerView.ViewHolder(binding.root)

    companion object {
        /** §4: the caret points down when the list is open and left when it is closed. */
        private const val COLLAPSED_DEGREES = -90f

        /**
         * Marks a repaint as content-only. Its VALUE is never read — what matters is that the
         * payload list is not empty, because that is the flag `canReuseUpdatedViewHolder` keys off
         * to skip the change animation entirely. See [repaint].
         */
        private const val PAYLOAD_REPAINT = "repaint"
    }
}
