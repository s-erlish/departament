package com.v2ray.ang.ui.component

import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DimenRes
import androidx.annotation.DrawableRes
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.widget.ImageViewCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.v2ray.ang.R

/**
 * The universal row - `res/layout/view_row.xml` bound in one call (22-components.md 8,
 * 32-master-plan-android.md 8.1).
 *
 * This is the single most repeated object in the product, and the reason this package exists: the
 * same structure is hand-inlined over a hundred times in the shipped app and the copies have
 * drifted 2 to 4dp apart. Geometry belongs to the layout and to
 * `@style/Widget.Departament.Row`:
 *
 * ```
 * [ 16 gutter ][ 40 tile r12, 22 glyph ][ 12 ][ text column, weight 1 ][ 12 ][ ONE trailing ][ 16 ]
 * ```
 *
 * That closing 16 is a line and not a margin: it is where the text column stops and where the
 * card's content edge is, so a trailing GLYPH is measured to its last painted pixel and not to its
 * box ([alignInkToGutter]). A trailing OBJECT with a boundary of its own - a switch, a checkbox, a
 * button with a label - is measured to that boundary, like every other object in the product.
 *
 * This binder owns the row's CONTENT and its STATE, and it enforces the three things geometry
 * cannot:
 *
 * 1. **Exactly one trailing element.** [Trailing] is a closed set and the binder hides every
 *    affordance it was not asked for, on every bind. Two trailing elements are not "discouraged"
 *    here, they are unreachable. The same reset is what makes recycling safe: a row that was a
 *    switch last frame cannot keep its switch when it is rebound as a chevron.
 * 2. **The row's job is never split in two.** When the trailing element is itself the action
 *    ([Trailing.IconAction], [Trailing.ActionButton]) the row is not a target, per 22-components
 *    8.4 - two targets doing different things is the `layout_subscription_meta_bar` mistake.
 *    Passing `onClick` alongside one of those throws rather than shipping the defect.
 * 3. **State is announced, not only drawn.** A toggle row reports itself to TalkBack as checkable
 *    and checked, a selected row as selected (00-rules.md 14.9).
 *
 * The rest follows the spec: the whole row is the touch target, it GROWS with a two-line subtitle
 * rather than clipping, press feedback is the background step in `@drawable/bg_row` and never a
 * scale (R5), text is styled by applying a ramp role and never by an inline size or face, and every
 * icon-only control carries a name.
 */
object RowBinder {

    /**
     * The closed tile system (D-5): three colours and no fourth. Purple / orange / yellow / green
     * encode a category system this product does not have.
     *
     * At most three coloured tiles on one screen, and in practice a screen has one or none - 56 of
     * 65 tiles in the shipped app are blue, which is the defect this closes.
     *
     * [DESTRUCTIVE] is for a genuinely destructive CATEGORY, not for a destructive row: a
     * destructive row keeps a [NEUTRAL] tile and carries its red in the title
     * ([RowTone.DESTRUCTIVE]), because a red tile plus red text is the same signal twice and turns
     * the row into an alarm (22-components 8.6).
     */
    enum class TileRole { NEUTRAL, ACCENT, DESTRUCTIVE }

    /**
     * The title's tone. The SIZE never changes with it - handoff §6 gives every row title
     * 14sp/400 (`TextAppearance.App.Body`) and the tone only moves its colour.
     *
     * [ACCENT] is §7's action row: «Проверить обновление», «Копировать», «Очистить»,
     * «Сканировать код телевизора», «Добавить правило». The prototype draws those as a normal
     * row whose NAME is accent-coloured, with no chevron and no button - the accent is the
     * affordance. At most one such group per screen, or the screen turns blue.
     */
    enum class RowTone { DEFAULT, DESTRUCTIVE, ACCENT }

    /**
     * Where a row sits in a section card that does NOT clip its children - see [edge].
     *
     * [MIDDLE] is the default and the only one a clipping card ever needs, because there the card's
     * own 20dp corner does the trimming.
     */
    enum class Edge { MIDDLE, TOP, BOTTOM, ONLY }

    /**
     * Rounds a row to its section card's corner, for the cards that host a [SelectPopup].
     *
     * README §6 and §11 grabl 4: a card whose row can open the popup must carry `clipChildren` and
     * `clipToPadding` false, or the popup is sliced off at the card's bottom edge. With the clip
     * gone the card also stops trimming its first and last row to its own radius, so those two rows
     * carry the corner themselves — 19dp, one pixel tighter than the card's 20 because the card
     * paints its hairline stroke inside the radius.
     *
     * Padding is restored by hand: `setBackgroundResource` re-derives padding from the new drawable
     * and would otherwise drop the `Widget.Departament.Row` insets that put the tile on the 16dp
     * gutter — the same trap [bindTile] guards against.
     */
    fun edge(root: View, edge: Edge) {
        val background = when (edge) {
            Edge.MIDDLE -> R.drawable.bg_row
            Edge.TOP -> R.drawable.bg_row_top
            Edge.BOTTOM -> R.drawable.bg_row_bottom
            Edge.ONLY -> R.drawable.bg_row_only
        }
        val left = root.paddingLeft
        val top = root.paddingTop
        val right = root.paddingRight
        val bottom = root.paddingBottom
        root.setBackgroundResource(background)
        root.setPadding(left, top, right, bottom)
    }

    /**
     * The trailing affordance and the promise it makes to the user (32-master-plan-android.md 8.1).
     * Exactly one per row; the type is what enforces that.
     */
    sealed interface Trailing {

        /**
         * No trailing affordance at all, which is two archetypes depending on `onClick`:
         *
         * - with no listener it is **Row.Fact** - a read-only row, not clickable, no ripple, no
         *   press feedback, not focusable;
         * - with one it is **Row.Destructive** («Удалить сервер», «Выйти»), where the red title in
         *   [RowTone.DESTRUCTIVE] is itself the affordance and a chevron would be noise. Per
         *   00-rules.md 7.5 the default is act plus an undo snackbar, not a confirm dialog; when
         *   the action really is irreversible, use [Chevron] instead, because it opens a screen.
         */
        data object None : Trailing

        /** Tapping pushes a screen. The 20dp `ic_chevron_right` in `?attr/colorOnSurfaceVariant`. */
        data object Chevron : Trailing

        /**
         * Tapping expands content inline, right here: the same chevron rotated 0 to 90.
         * [bind] sets the resting angle, [animateExpand] performs the transition.
         */
        data class Expand(val expanded: Boolean) : Trailing

        /**
         * Tapping changes the value in place - no screen, no dialog. This is the cycle affordance;
         * pass `ic_unfold_more` once the icon owner lands it, which is why the layout ships this
         * slot with no `src` to be wrong.
         *
         * The glyph is normally decorative - the row's name and its value carry the meaning - so
         * [contentDescription] is normally null and the glyph is then hidden from TalkBack.
         */
        data class Glyph(
            @param:DrawableRes val icon: Int,
            val contentDescription: CharSequence? = null,
        ) : Trailing

        /**
         * A boolean applied immediately. The ROW owns the switch: tapping anywhere on the row
         * toggles it, the switch is not separately focusable, and the state is announced as a
         * checkable node rather than only drawn. Fires `tickHaptic()` per 00-rules.md 8.10.
         */
        data class Toggle(
            val checked: Boolean,
            val onCheckedChange: (Boolean) -> Unit,
        ) : Trailing

        /**
         * MEMBERSHIP of a set, which is not the same promise as [Toggle] (handoff §7, «список
         * приложений с чекбоксами»). A switch says «this is on now»; a box says «this one is in
         * the set some other control acts on». «Прокси по приложениям» marks 128 apps, and a
         * column of switches down its right edge claims to be turning 128 things on one at a time.
         *
         * The ROW still owns the tap and the box is neither clickable nor focusable, exactly as
         * with [Toggle], so nothing about the touch target or the announcement changes.
         */
        data class Checkbox(
            val checked: Boolean,
            val onCheckedChange: (Boolean) -> Unit,
        ) : Trailing

        /**
         * This item is the current selection (22-components 18). The check slot is ALWAYS reserved
         * and only its alpha changes, so selecting an item never reflows the list. This is the one
         * sanctioned pairing: a marker row may also carry a `value` - the server row's ping figure
         * beside its check.
         */
        data class Marker(val selected: Boolean) : Trailing

        /**
         * An icon button performing the action in place; the row itself is inert.
         * [contentDescription] is not optional, and it names the ACTION rather than the object -
         * «Скопировать код», not «Код».
         *
         * It DRAWS at `@dimen/row_action_size` 36 and is TAPPED at 48 ([restoreTouchTarget]): a
         * 48dp box cannot put a narrow glyph's ink on the row's gutter without hanging its press
         * plate outside the card, and it made every row carrying one 8dp taller than its
         * neighbours.
         */
        data class IconAction(
            @param:DrawableRes val icon: Int,
            val contentDescription: CharSequence,
            val onClick: (View) -> Unit,
        ) : Trailing

        /**
         * A Tertiary button inside the row - the only place in the product a Tertiary lives in a
         * row (22-components 8.4): «Привязать Telegram» -> «Привязать». The row itself is inert.
         */
        data class ActionButton(
            val label: CharSequence,
            val onClick: (View) -> Unit,
        ) : Trailing
    }

    /**
     * Binds one `view_row.xml`.
     *
     * ```kotlin
     * RowBinder.bind(
     *     root = binding.rowDns.row,
     *     title = getString(R.string.settings_dns),
     *     glyph = R.drawable.ic_dns,
     *     value = "Cloudflare",
     *     trailing = RowBinder.Trailing.Chevron,
     *     onClick = { SubPage.open(this, dnsIntent) },
     * )
     * ```
     *
     * @param root the inflated row, i.e. `@id/row`.
     * @param title the row's name. Ramp role Title 16/700, max 2 lines, sentence case, never caps.
     * @param subtitle six words on what the row DOES, or null. A subtitle that restates its title is
     *   deleted, not written; it says what is on when it is on, never a negation.
     * @param glyph the 22dp tile glyph. `0` hides the tile, which is what a PLAIN (untiled) group
     *   is - the row then starts at the 16dp gutter and the tile's 12dp margin goes with it. A
     *   group is tiled only when its rows carry glyphs that DIFFER; identical tiles down the left
     *   edge is the generated-settings tell.
     * @param tileRole one of the three tile colours. Neutral unless the row is genuinely categorical.
     * @param tone the title's ramp role; [RowTone.DESTRUCTIVE] for «Удалить сервер», «Выйти».
     * @param value the row's current value - «DNS · Cloudflare». A settings list the user can audit
     *   by scrolling once, without opening anything, is the single best idea in the reference app.
     * @param valueIsNumeric true for a quantity, which puts the value on the Numeric role: tabular
     *   figures, so a live number does not reflow on every tick.
     * @param trailing the one affordance. Everything else is hidden.
     * @param enabled false draws the whole row at 0.38 and takes it out of the touch order (R6).
     * @param haptic normally [Haptic.NONE] - a row tap does not vibrate. A switch does, and the
     *   binder supplies that itself.
     * @param onClick what tapping the row does, routed through [onSingleClick] so a double tap
     *   cannot fire it twice. MUST be null when the trailing element is itself the action
     *   ([Trailing.IconAction], [Trailing.ActionButton]); null with [Trailing.None] is what makes a
     *   row a read-only fact.
     */
    fun bind(
        root: View,
        title: CharSequence,
        subtitle: CharSequence? = null,
        @DrawableRes glyph: Int = 0,
        tileRole: TileRole = TileRole.NEUTRAL,
        tone: RowTone = RowTone.DEFAULT,
        value: CharSequence? = null,
        valueIsNumeric: Boolean = false,
        trailing: Trailing = Trailing.Chevron,
        enabled: Boolean = true,
        haptic: Haptic = Haptic.NONE,
        onClick: ((View) -> Unit)? = null,
    ) {
        require(onClick == null || !trailing.trailingOwnsAction()) {
            "Departament row: this trailing element owns the action, so the row itself must not " +
                "be a target (22-components 8.4). Pass onClick = null."
        }
        require(trailing !is Trailing.Toggle || value == null) {
            "Departament row: a toggle row is a title, an optional subtitle and the switch " +
                "(22-components 8.5). A value beside a switch is two trailing elements."
        }
        require(trailing !is Trailing.Checkbox || value == null) {
            "Departament row: a checkbox row is a title, an optional subtitle and the box. " +
                "A value beside it is two trailing elements."
        }

        val slots = RowSlots.of(root)
        bindTile(slots, glyph, tileRole)
        bindText(slots, title, subtitle, tone)
        bindValue(slots, value, valueIsNumeric)
        resetTrailing(slots)
        bindTrailing(slots, trailing)
        bindInteraction(slots, trailing, enabled, haptic, onClick)
    }

    /**
     * The expand transition for a [Trailing.Expand] row: the chevron rotates 0 to 90 over
     * `motion_state` 220ms on `ease_standard`. A rotation between two resting angles is a state
     * change, not a reveal, which is why it is 220 and not 300. Reduced motion snaps.
     *
     * Call it from the row's own click listener after flipping your expanded flag; [bind] only sets
     * the resting angle, so scrolling a list never replays the animation.
     */
    fun animateExpand(root: View, expanded: Boolean) {
        val chevron = RowSlots.of(root).chevron
        val target = if (expanded) EXPANDED_DEGREES else 0f
        chevron.motion(snap = { chevron.rotation = target }) {
            chevron.animate()
                .rotation(target)
                .setDuration(chevron.durationOf(R.integer.motion_state))
                .setInterpolator(chevron.curve(R.interpolator.ease_standard))
                .start()
        }
    }

    /**
     * Puts the trailing glyph's INK on the row's 16dp end gutter, which its box cannot do on its
     * own.
     *
     * THE LINE IS THE GUTTER AND NOT A CONTROL, which is what the first two rounds of this got
     * wrong. The slot holds one of several objects and they are not the same KIND of object:
     * `row_chevron`, `row_trailing_glyph` and `row_marker` are 18dp `ImageView`s whose box already
     * ends on the row's `paddingEnd`, while `row_icon_action` is a `MaterialButton` whose 22dp icon
     * is centred in a larger box. Levelling them with each other closed the difference the owner
     * could see — «дальше баги по кнопкам 1-3 скрины, они улетели куда-то влево» — and left the
     * whole family 6dp inside the line the text column, the card's content edge and the screen all
     * end on. He came back: «надо их правее также поставить правильно».
     *
     * ONE NUMBER CANNOT SERVE THEM, which is the trap [ToolbarBinder.alignInkToGutter] hit first.
     * Every glyph in this set is a 24-unit viewport and they fill it by very different amounts, so
     * the correction belongs to the GLYPH and is applied here, at the one place that knows which
     * glyph is going in:
     *
     * ```
     *                       ink of 24 units    drawn at    empty air    pull
     *   ic_chevron_right        7.2 … 16         18dp        6.00      -6.00
     *   ic_arrow_drop_down        7 … 17         18dp        5.25      -5.25
     *   ic_action_done            3 … 21         18dp        2.25      -2.25
     *   ic_copy                   2 … 21         22dp        2.75      -9.75   (+7 box)
     *   ic_more_vert_24dp        10 … 14         22dp        9.17     -16.00   (+7 box)
     * ```
     *
     * The numbers and their arithmetic are on the dimensions in `values/tokens_row_action.xml`. The
     * two slots whose drawable `view_row.xml` declares itself carry their pull in that file instead,
     * three lines under the `src` it was measured from; these two are the ones a CALLER fills.
     *
     * A glyph this function does not know about is reset to no pull rather than left alone — these
     * rows are recycled by five adapters, and a stale margin from the previous holder's icon would
     * outlive the icon that earned it. No pull is also the only honest default: it draws the slot
     * exactly where it drew before any of this existed.
     */
    private fun MaterialButton.alignInkToGutter(@DrawableRes icon: Int) = pullInk(
        when (icon) {
            R.drawable.ic_more_vert_24dp -> R.dimen.row_action_nudge_overflow
            R.drawable.ic_copy -> R.dimen.row_action_nudge_wide
            else -> 0
        }
    )

    /** [alignInkToGutter] for the 18dp slot, i.e. the same glyphs at ×0.75 instead of ×0.9167. */
    private fun ImageView.alignInkToGutter(@DrawableRes icon: Int) = pullInk(
        when (icon) {
            R.drawable.ic_chevron_right -> R.dimen.row_glyph_nudge_chevron
            R.drawable.ic_arrow_drop_down -> R.dimen.row_glyph_nudge_caret
            R.drawable.ic_action_done -> R.dimen.row_glyph_nudge_check
            else -> 0
        }
    )

    /**
     * Writes one pull, or clears it when [nudge] is 0.
     *
     * A negative end margin on the row's LAST visible child does not move that child directly —
     * `LinearLayout` places a child from what came before it. It shortens `mTotalLength` during
     * measure, which hands the same dp to the weight-1 text column, which then pushes everything
     * after it along by exactly that much. The slot ends up in the row's own end padding, where
     * there is nothing to collide with.
     *
     * AND NOTHING CLIPS — WHICH IS NOW TRUE. `clipToPadding` defaults to true, so until
     * `view_row.xml` turned it off the row's 16dp `paddingEnd` was also a clip line, and the press
     * disc of a slot deliberately parked past it lost everything beyond 16: rendered and measured,
     * 12dp of a 28dp disc, cut flat on the side the finger is on. «выделил зажав на три точки …
     * обрезается». The pull is not the thing that was wrong; the clip was.
     */
    private fun View.pullInk(@DimenRes nudge: Int) {
        val params = layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val value = if (nudge == 0) 0 else resources.getDimensionPixelSize(nudge)
        if (params.marginEnd == value) return
        params.marginEnd = value
        layoutParams = params
    }

    /**
     * Gives [Trailing.IconAction] back the 48dp it stopped DRAWING (00-rules.md 14.2).
     *
     * `view_row.xml` lays that button out at `@dimen/row_action_size` 36 so its ink can reach the
     * gutter without the box leaving the row; the touch area does not shrink with it. This is the
     * `layout_subscription_meta_bar` pattern verbatim — 36dp discs tapped at 48 — and it is
     * [expandTouchTarget] and its `CompositeTouchDelegate` doing the work, not a third copy of them.
     *
     * THE ASK IS 60 AND NOT 48, and the extra 12 is not slack. [expandTouchTarget] grows a hit rect
     * symmetrically about the control, and the row's own edge then trims whatever falls outside it:
     * once «⋮»'s ink is on the gutter its box is flush with that edge, so its centre is 18dp from
     * it and a 48dp square centred there would need 6dp the row does not have. Asking for 60 grows
     * the rect 12dp on each side, the row keeps the 12 on the inside, and 36 + 12 is the 48 the
     * floor wants. The copy glyph, whose box stops 6.25dp short of the edge, ends up with 54 —
     * harmless, because a row showing this control is inert by construction (22-components 8.4) and
     * there is nothing in that strip for the delegate to take a tap away from.
     *
     * ONCE PER VIEW, NOT PER BIND: the delegate is registered on the PARENT and the composite that
     * holds several only ever grows, so [R.id.tag_row_action_touch] stamps the view with the glyph
     * its rect was measured for. A row recycled between the two glyphs earns a second rect, 6.25dp
     * over from the first; both point at this same button, so the older one can only widen the
     * target, never mis-aim it.
     */
    private fun MaterialButton.restoreTouchTarget(@DrawableRes icon: Int) {
        if (getTag(R.id.tag_row_action_touch) == icon) return
        setTag(R.id.tag_row_action_touch, icon)
        expandTouchTarget(TOUCH_TARGET_ASK_DP)
    }

    /**
     * True when the trailing control is the action, which is the case 22-components 8.4 settles:
     * the row then stops being a target, because two targets doing different things is a defect and
     * not a convenience. [Trailing.None] is deliberately absent - a row with no trailing is inert
     * only when it is also given no listener.
     */
    private fun Trailing.trailingOwnsAction(): Boolean =
        this is Trailing.IconAction || this is Trailing.ActionButton

    private fun bindTile(slots: RowSlots, @DrawableRes glyph: Int, role: TileRole) {
        val tile = slots.tile
        if (glyph == 0) {
            tile.visibility = View.GONE
            return
        }
        tile.visibility = View.VISIBLE

        val background = when (role) {
            TileRole.NEUTRAL -> R.drawable.bg_tile_neutral
            TileRole.ACCENT -> R.drawable.bg_tile_accent
            TileRole.DESTRUCTIVE -> R.drawable.bg_tile_destructive
        }
        // setBackgroundResource re-derives padding from the drawable, which would drop the padding
        // that centres the 22dp glyph in the 40dp tile. Keep exactly what the style declared.
        val left = tile.paddingLeft
        val top = tile.paddingTop
        val right = tile.paddingRight
        val bottom = tile.paddingBottom
        tile.setBackgroundResource(background)
        tile.setPadding(left, top, right, bottom)

        // THE NEUTRAL FILL COMES FROM THE THEME, NOT FROM THE DRAWABLE. bg_tile_neutral paints
        // @color/icon_tile_neutral, and a ThemeOverlay can only override ATTRIBUTES — so the mono
        // theme could not touch it and every «grey» tile stayed faintly blue there. The tint is a
        // no-op in the other two themes: icon_tile_neutral is #E3EAF4 / #20242B and
        // colorSurfaceContainerHighest is the same two values. Accent and destructive tiles keep
        // their own drawables, which are already attribute-driven.
        ViewCompat.setBackgroundTintList(
            tile,
            if (role == TileRole.NEUTRAL) {
                ColorStateList.valueOf(tile.themeColor(com.google.android.material.R.attr.colorSurfaceContainerHighest))
            } else {
                null
            },
        )

        tile.setImageResource(glyph)
        ImageViewCompat.setImageTintList(tile, ColorStateList.valueOf(tile.tintFor(role)))
    }

    // Same reason as the fill above: @color/icon_glyph_neutral is #54607A / #9BA1AD, which is
    // exactly colorOnSurfaceVariant in both themes — but as a colour resource the mono overlay
    // could not reach it, so mono glyphs stayed blue-grey. Attribute, same pixels.
    private fun View.tintFor(role: TileRole): Int = when (role) {
        TileRole.NEUTRAL -> themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        TileRole.ACCENT -> themeColor(androidx.appcompat.R.attr.colorPrimary)
        TileRole.DESTRUCTIVE -> themeColor(androidx.appcompat.R.attr.colorError)
    }

    private fun bindText(
        slots: RowSlots,
        title: CharSequence,
        subtitle: CharSequence?,
        tone: RowTone,
    ) {
        // ONE SIZE FOR EVERY ROW TITLE — handoff §6: «заголовок 14sp/400». The tone moves the
        // colour and nothing else, so a red «Удалить» and an accent «Копировать» sit on the same
        // baseline grid as the row above them. The colour is an ATTRIBUTE rather than a second
        // TextAppearance because the mono overlay redefines attributes and cannot reach a style.
        slots.title.text = title
        slots.title.setTextAppearance(R.style.TextAppearance_App_Body)
        slots.title.setTextColor(
            when (tone) {
                RowTone.DEFAULT -> slots.title.themeColor(com.google.android.material.R.attr.colorOnSurface)
                RowTone.DESTRUCTIVE -> slots.title.themeColor(R.attr.colorDestructiveText)
                RowTone.ACCENT -> slots.title.themeColor(androidx.appcompat.R.attr.colorPrimary)
            }
        )
        slots.subtitle.text = subtitle ?: ""
        slots.subtitle.visibility = if (subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun bindValue(slots: RowSlots, value: CharSequence?, numeric: Boolean) {
        if (value.isNullOrEmpty()) {
            slots.value.text = ""
            slots.value.visibility = View.GONE
            return
        }
        slots.value.text = value
        slots.value.visibility = View.VISIBLE
        slots.value.setTextAppearance(
            if (numeric) R.style.TextAppearance_App_Numeric else R.style.TextAppearance_App_Subtitle
        )
    }

    /** Every affordance off, every listener detached. This is what makes "exactly one" true. */
    private fun resetTrailing(slots: RowSlots) {
        slots.chevron.visibility = View.GONE
        slots.trailingGlyph.visibility = View.GONE
        slots.marker.visibility = View.GONE
        slots.toggle.setOnCheckedChangeListener(null)
        slots.toggle.visibility = View.GONE
        slots.checkBox.visibility = View.GONE
        slots.iconAction.clearClick()
        slots.iconAction.visibility = View.GONE
        slots.actionButton.clearClick()
        slots.actionButton.visibility = View.GONE
    }

    private fun bindTrailing(slots: RowSlots, trailing: Trailing) {
        when (trailing) {
            is Trailing.None -> Unit

            is Trailing.Chevron -> {
                slots.chevron.rotation = 0f
                slots.chevron.visibility = View.VISIBLE
            }

            is Trailing.Expand -> {
                slots.chevron.rotation = if (trailing.expanded) EXPANDED_DEGREES else 0f
                slots.chevron.visibility = View.VISIBLE
            }

            is Trailing.Glyph -> with(slots.trailingGlyph) {
                setImageResource(trailing.icon)
                alignInkToGutter(trailing.icon)
                contentDescription = trailing.contentDescription
                importantForAccessibility = if (trailing.contentDescription == null) {
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO
                } else {
                    View.IMPORTANT_FOR_ACCESSIBILITY_YES
                }
                visibility = View.VISIBLE
            }

            is Trailing.Toggle -> with(slots.toggle) {
                visibility = View.VISIBLE
                // THE LISTENER IS DETACHED BEFORE THE STATE IS WRITTEN. `bind` runs again on every
                // rebind — a resume, a value that changed elsewhere, a recycled holder — and
                // assigning `isChecked` while the PREVIOUS bind's listener is still attached fires
                // that closure, which belongs to the previous call's `trailing` and so writes the
                // previous item's target with this item's value.
                setOnCheckedChangeListener(null)
                // ...and the position is restored, not played: this is a value being read back, and
                // an animated state change queued before the first draw parks and then spills into
                // it — the «переключатели дёргаются» defect. `restoreChecked`'s own guard leaves an
                // animation the user just started with their finger alone.
                restoreChecked(trailing.checked)
                setOnCheckedChangeListener { _, checked -> trailing.onCheckedChange(checked) }
            }

            is Trailing.Checkbox -> with(slots.checkBox) {
                visibility = View.VISIBLE
                // The fill and the tick both hang off this one state, so a recycled row cannot
                // keep the previous item's tick. Jump to it: an animated state change queued
                // before the first draw parks and then spills out into it (the switch defect).
                isActivated = trailing.checked
                jumpDrawablesToCurrentState()
            }

            is Trailing.Marker -> {
                // The slot stays VISIBLE and moves its alpha, so selection changes nothing about
                // the layout - no reflow, no geometry shift (22-components 18.1). The title does
                // NOT change weight with it: §6 gives every row title one size and one weight,
                // and the marker plus the activated background are already two channels.
                slots.marker.visibility = View.VISIBLE
                slots.marker.alpha = if (trailing.selected) 1f else 0f
            }

            is Trailing.IconAction -> with(slots.iconAction) {
                visibility = View.VISIBLE
                setIconResource(trailing.icon)
                contentDescription = trailing.contentDescription
                alignInkToGutter(trailing.icon)
                restoreTouchTarget(trailing.icon)
                onSingleClick(action = trailing.onClick)
            }

            is Trailing.ActionButton -> with(slots.actionButton) {
                visibility = View.VISIBLE
                text = trailing.label
                onSingleClick(action = trailing.onClick)
            }
        }
    }

    private fun bindInteraction(
        slots: RowSlots,
        trailing: Trailing,
        enabled: Boolean,
        haptic: Haptic,
        onClick: ((View) -> Unit)?,
    ) {
        val root = slots.root
        root.isEnabled = enabled
        // isEnabled does NOT cascade to a ViewGroup's children, so a disabled row whose action
        // lives in the trailing slot would stay tappable. Disable the three interactive slots by
        // hand; the other trailing elements are glyphs and have nothing to disable.
        slots.toggle.isEnabled = enabled
        slots.checkBox.isEnabled = enabled
        slots.iconAction.isEnabled = enabled
        slots.actionButton.isEnabled = enabled
        // R6: disabled is 0.38 on the WHOLE control, on both platforms.
        root.alpha = if (enabled) 1f else DISABLED_ALPHA
        root.isActivated = trailing is Trailing.Marker && trailing.selected
        // ...and it lands instantly, like every other state written before a first draw. `bg_row`
        // and its three edge variants are selectors with enterFadeDuration/exitFadeDuration, so the
        // activated fill CROSS-FADES whenever it is written — including on a recycled row, where the
        // holder arrives carrying the previous item's selection and dissolves it into this one's
        // while the list scrolls. Five adapters bind rows through here.
        //
        // The press keeps its fade: those durations are motion_press_in / motion_press_out, and a
        // press happens on a laid-out, drawn row long after this call. Same rule as the switch two
        // slots down (@see restoreChecked) and the checkbox's own jump above.
        root.jumpDrawablesToCurrentState()

        when {
            // An inert row keeps its background: `bg_row` only draws a pressed or focused state,
            // and neither is reachable once the row stops being clickable. Clearing the background
            // instead would leave a recycled row permanently flat when it is next bound clickable.
            !enabled || trailing.trailingOwnsAction() -> root.clearClick()

            trailing is Trailing.Toggle -> root.onSingleClick(Haptic.TICK) {
                slots.toggle.isChecked = !slots.toggle.isChecked
            }

            trailing is Trailing.Checkbox -> root.onSingleClick(Haptic.TICK) {
                val next = !slots.checkBox.isActivated
                slots.checkBox.isActivated = next
                trailing.onCheckedChange(next)
            }

            onClick != null -> {
                root.onSingleClick(haptic, onClick)
                root.isFocusable = true
            }

            else -> root.clearClick()
        }

        applySemantics(
            root = root,
            checkable = trailing is Trailing.Toggle || trailing is Trailing.Checkbox,
            checked = (trailing is Trailing.Toggle && trailing.checked) ||
                (trailing is Trailing.Checkbox && trailing.checked),
            selected = trailing is Trailing.Marker && trailing.selected,
            checkboxClass = trailing is Trailing.Checkbox,
        )
    }

    /**
     * 00-rules.md 14.9: selected, disabled, expanded, loading and error are EXPOSED to TalkBack,
     * not only drawn. A delegate is installed on every bind, including the neutral case, so a
     * recycled row cannot keep the previous item's announcement.
     */
    private fun applySemantics(
        root: View,
        checkable: Boolean,
        checked: Boolean,
        selected: Boolean,
        checkboxClass: Boolean = false,
    ) {
        ViewCompat.setAccessibilityDelegate(root, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(
                host: View,
                info: AccessibilityNodeInfoCompat,
            ) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.isCheckable = checkable
                // setChecked(Boolean) is deprecated in favour of an int-based tri-state
                // (unchecked / checked / partially checked), but that overload is NOT in the
                // androidx.core resolved by this build - the class in the resolved artifact
                // exposes setChecked and no CHECKED_STATE_* constants, so there is nothing to
                // migrate to yet. A toggle row genuinely has to announce its checked state or
                // TalkBack reads it as a plain button, so this is suppressed rather than dropped.
                // Revisit when androidx.core ships the overload.
                @Suppress("DEPRECATION")
                info.isChecked = checked
                info.isSelected = selected
                if (checkable) {
                    info.className = if (checkboxClass) CHECKBOX_CLASS else SWITCH_CLASS
                }
            }
        })
    }

    private const val DISABLED_ALPHA = 0.38f
    private const val EXPANDED_DEGREES = 90f

    /**
     * What [restoreTouchTarget] asks [expandTouchTarget] for: 36dp of drawn button, 12dp of grown
     * rect on the side the row's edge does not take, and the 48dp floor is met. It is a dp count
     * and not a `@dimen` because that is the unit the helper takes.
     */
    private const val TOUCH_TARGET_ASK_DP = 60
    private const val SWITCH_CLASS = "android.widget.Switch"
    private const val CHECKBOX_CLASS = "android.widget.CheckBox"
}

/**
 * The row's child views, resolved once from `view_row.xml`.
 *
 * Nothing here is nullable: every slot is declared by that one layout, so a null would mean the
 * caller passed the wrong root, and [slot] says so instead of drawing a row with a hole in it.
 */
class RowSlots private constructor(
    val root: View,
    val tile: ImageView,
    val title: TextView,
    val subtitle: TextView,
    val value: TextView,
    val chevron: ImageView,
    val marker: ImageView,
    val trailingGlyph: ImageView,
    val toggle: MaterialSwitch,
    val checkBox: ImageView,
    val iconAction: MaterialButton,
    val actionButton: MaterialButton,
) {

    companion object {

        private const val LAYOUT = "res/layout/view_row.xml"

        /** Resolves every slot of an inflated `view_row.xml`. */
        fun of(root: View): RowSlots = RowSlots(
            root = root,
            tile = root.slot(R.id.row_tile, LAYOUT, "row_tile"),
            title = root.slot(R.id.row_title, LAYOUT, "row_title"),
            subtitle = root.slot(R.id.row_subtitle, LAYOUT, "row_subtitle"),
            value = root.slot(R.id.row_value, LAYOUT, "row_value"),
            chevron = root.slot(R.id.row_chevron, LAYOUT, "row_chevron"),
            marker = root.slot(R.id.row_marker, LAYOUT, "row_marker"),
            trailingGlyph = root.slot(R.id.row_trailing_glyph, LAYOUT, "row_trailing_glyph"),
            toggle = root.slot(R.id.row_switch, LAYOUT, "row_switch"),
            checkBox = root.slot(R.id.row_check_box, LAYOUT, "row_check_box"),
            iconAction = root.slot(R.id.row_icon_action, LAYOUT, "row_icon_action"),
            actionButton = root.slot(R.id.row_action, LAYOUT, "row_action"),
        )
    }
}
