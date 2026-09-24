package com.v2ray.ang.ui.component

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.v2ray.ang.R

/**
 * The seamless sub-page toolbar - `res/layout/view_toolbar.xml` bound in one call (owner request
 * 0.4.6, 00-rules.md 4.8, 22-components.md 12.1, 32-master-plan-android.md 8.6).
 *
 * ```
 * [ 16 ][ back, 24 glyph ][ 16 ][ Title 16/700 ][ flex ][ action (0-1) ][ 16 ]
 * ```
 *
 * The header is not a bar, it is the top of the page: the same background as the page, no
 * elevation, no divider, no shadow, no scrim, no `liftOnScroll`. That is the layout's business.
 * What this binder owns is the three things a screen gets wrong when it wires a header by hand:
 *
 * - **The back affordance is either present with a name and a working close, or absent with the
 *   title moved to the gutter.** There is no third state, and there is no back button whose
 *   `contentDescription` somebody forgot.
 * - **At most one action.** [bind] takes one, so a second one has nowhere to go; a screen that
 *   genuinely needs two puts them behind an overflow.
 * - **There is no boundary under the header at any scroll position.** The hairline the layout still
 *   carries stays at alpha 0 for the screen's whole life; [attachTo] is the switch that used to
 *   raise it, and it is deliberately a no-op. Read its note before reviving it.
 */
object ToolbarBinder {

    /**
     * Binds a sub-page header: back arrow, title, and at most one action.
     *
     * ```kotlin
     * ToolbarBinder.bind(
     *     root = binding.toolbar.toolbar,
     *     title = getString(R.string.routing_title),
     *     activity = this,
     *     actionIcon = R.drawable.ic_add_24dp,
     *     actionDescription = getString(R.string.routing_add_cd),
     *     onAction = { addRule() },
     * )
     * ```
     *
     * @param root the inflated header, i.e. `@id/toolbar`.
     * @param title the page's title. Ramp role Title 16/700, one line, ellipsised at the end. The
     *   most repeated defect in the app is a Russian title drawn in the Latin wordmark face, which
     *   carries no Cyrillic at all; the layout's `textAppearance` is what fixes it, so do not
     *   override it here.
     * @param activity the host. When it is non-null the back arrow is shown and closes the screen
     *   through [SubPage.close], so the exit transition matches the entrance. Pass null on a TAB
     *   screen, which has no back affordance: the leading gap then takes over and the title still
     *   starts at the 16dp gutter.
     * @param note handoff README §7's optional 13sp explanation under the title - ONE sentence
     *   saying what the screen is for. A note that restates the title is deleted, not written, so
     *   null (the default) hides the slot. `view_toolbar.xml` has no such slot and simply ignores
     *   it; only `view_sub_header.xml` draws it.
     * @param actionIcon the one trailing action's glyph, or `0` for none.
     * @param actionDescription the action's name, stating the ACTION and not the object -
     *   «Добавить сервер», not «Сервер». Required whenever [actionIcon] is set: an icon-only
     *   control with no accessible name is a P1 defect (00-rules.md 14.3).
     * @param onAction what the action does, routed through [onSingleClick].
     */
    fun bind(
        root: View,
        title: CharSequence,
        activity: Activity? = null,
        note: CharSequence? = null,
        @DrawableRes actionIcon: Int = 0,
        actionDescription: CharSequence? = null,
        onAction: ((View) -> Unit)? = null,
    ) {
        require(actionIcon == 0 || (actionDescription != null && onAction != null)) {
            "Departament toolbar: an action needs a glyph, a name and a listener. An icon-only " +
                "control with no accessible name is a P1 (00-rules.md 14.3)."
        }

        val slots = ToolbarSlots.of(root)
        slots.title.text = title

        // §7's «необязательное пояснение». Resolved leniently rather than through RowSlots.slot():
        // the same binder serves view_toolbar.xml, which has no note to find, and a missing
        // optional slot is not a wiring mistake there.
        slots.note?.let { view ->
            view.text = note
            view.visibility = if (note.isNullOrBlank()) View.GONE else View.VISIBLE
        }

        if (activity == null) {
            slots.back.clearClick()
            slots.back.visibility = View.GONE
            // 12 here plus the title's own 4 puts a tab screen's title on the 16dp gutter.
            slots.leadingGap.visibility = View.VISIBLE
        } else {
            slots.back.visibility = View.VISIBLE
            slots.leadingGap.visibility = View.GONE
            slots.back.onSingleClick { SubPage.close(activity) }
        }

        if (actionIcon == 0) {
            slots.action.clearClick()
            slots.action.visibility = View.GONE
        } else {
            slots.action.setIconResource(actionIcon)
            slots.action.contentDescription = actionDescription
            slots.action.visibility = View.VISIBLE
            slots.action.alignInkToGutter(actionIcon)
            onAction?.let { slots.action.onSingleClick(action = it) }
        }
    }

    /**
     * Puts the action's INK on the 16dp gutter, which its box cannot do on its own.
     *
     * THE LINE IS THE GUTTER AND NOT THE BACK ARROW, which is what the round before this one got
     * wrong — the same mistake [RowBinder.alignInkToGutter] made one component down, and it is
     * corrected here for the same reason. 16 is where the page title starts, where every card's
     * content edge is, where each of the row's five trailing glyphs now stops, and the ONE gutter
     * the design rules give the product. Levelling the action with a control instead of with that line
     * closed the gap the owner could see and left the whole slot adrift of it: «вот нашел что
     * плюсик сверху он левее, надо правее опять сделать».
     *
     * ONE NUMBER CANNOT SERVE BOTH GLYPHS. The slot holds the two extremes of the icon set — a
     * 24-unit viewport filled x 5…19 by «+» and x 10…14 by «⋮» — so a margin big enough for the
     * overflow shoves the plus 5dp past the gutter. The correction belongs to the GLYPH and is
     * applied here, at the one place that knows which glyph is going in:
     *
     * ```
     *                       ink of 24 units   air behind it   ink from box end   margin
     *   ic_add_24dp              5 … 19            5dp           6 + 5 = 11        +5
     *   ic_more_vert_24dp       10 … 14           10dp           6 + 10 = 16        0
     * ```
     *
     * THE MARGIN IS POSITIVE NOW, and that is the whole difference between this round and the two
     * before it. `MaterialButton`'s `iconGravity` is `textStart`, not `start`: the library CENTRES
     * the icon and then shifts it by half the difference between the two paddings, so the 18/12
     * padding pair the slot used to carry moved the glyph 3dp off its own press plate instead of
     * placing it. The glyph is carried by the slot's inset split now
     * (`@dimen/toolbar_plate_inset_lead`, 16/4 of a 20dp total in a box that is an exact 48), which
     * leaves it 18dp from the box's trailing edge — 6dp behind the glyph box — and moves the plate
     * with it. What is left for this function is the glyph's own air, and because that is at most
     * 10 the box never has to leave the screen: the target is a full 48dp with no `TouchDelegate`,
     * and the press disc keeps 4dp of clearance from the window. The arithmetic is on the two
     * dimensions in `values/dimens.xml`.
     *
     * A glyph this function does not know about is reset to NO pull rather than left alone. It is
     * the honest default — it draws the slot exactly where the layout puts it — and it is now load
     * bearing: neither header carries a resting margin any more, so "leave it" would mean
     * inheriting whichever pull the previous bind happened to write.
     */
    private fun MaterialButton.alignInkToGutter(@DrawableRes icon: Int) {
        val nudge = when (icon) {
            R.drawable.ic_more_vert_24dp -> R.dimen.toolbar_action_nudge_overflow
            R.drawable.ic_add_24dp -> R.dimen.toolbar_action_nudge_wide
            else -> 0
        }
        val params = layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val value = if (nudge == 0) 0 else resources.getDimensionPixelSize(nudge)
        if (params.marginEnd == value) return
        params.marginEnd = value
        layoutParams = params
    }

    /**
     * NO-OP, DELIBERATELY, AND THE CALLS ARE LEFT IN PLACE.
     *
     * This used to fade the hairline in once the content had scrolled off the top — the one thing
     * about the header that was allowed to react to scroll (32-master-plan-android.md 7.4). The
     * owner saw it on the device and rejected it: «появляется какая-то полоска под текстом журнал,
     * так и в других вкладках наблюдается если вниз пролистать».
     *
     * He is right that it is not the design's. §7's лекало is back control → title → optional note →
     * cards, and neither the specification nor the prototype draws a boundary under the title at any
     * scroll position. The line was ours, inherited from a Material habit, and it reads as a stray
     * rule because the first thing under it — a search field, a card — already has an edge of its own.
     *
     * Kept as an empty function rather than deleted, and the hairline view kept at alpha 0 rather
     * than removed, because 24 screens call this and `ToolbarSlots` resolves the id. Making the
     * boundary come back is one line here, in one place, if it is ever wanted again.
     */
    @Suppress("UNUSED_PARAMETER")
    fun attachTo(root: View, scroller: RecyclerView) = Unit

    /** [attachTo], for a screen whose content is a `NestedScrollView` rather than a list. */
    @Suppress("UNUSED_PARAMETER")
    fun attachTo(root: View, scroller: NestedScrollView) = Unit
}

/** The toolbar's child views, resolved once from `view_toolbar.xml`. */
class ToolbarSlots private constructor(
    val root: View,
    val back: MaterialButton,
    val leadingGap: View,
    val title: TextView,
    val note: TextView?,
    val action: MaterialButton,
    val hairline: View,
) {

    companion object {

        private const val LAYOUT = "res/layout/view_toolbar.xml"

        fun of(root: View): ToolbarSlots = ToolbarSlots(
            root = root,
            back = root.slot(R.id.toolbar_back, LAYOUT, "toolbar_back"),
            leadingGap = root.slot(R.id.toolbar_leading_gap, LAYOUT, "toolbar_leading_gap"),
            title = root.slot(R.id.toolbar_title, LAYOUT, "toolbar_title"),
            // The ONE optional slot, and the only one resolved with findViewById: it exists in
            // view_sub_header.xml and not in view_toolbar.xml, and both are bound through here.
            note = root.findViewById(R.id.toolbar_note),
            action = root.slot(R.id.toolbar_action, LAYOUT, "toolbar_action"),
            hairline = root.slot(R.id.toolbar_hairline, LAYOUT, "toolbar_hairline"),
        )
    }
}
