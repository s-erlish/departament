package com.v2ray.ang.ui.component

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.v2ray.ang.R

/**
 * The selection indicator (22-components.md 18) - the most-touched selection surface in the
 * product, and the one 00-rules.md 7.1 is strictest about: **two axes minimum, never tint alone,
 * and no geometry shift.**
 *
 * Applied to anything the user picks from a set that already exists: server rows, tariff cards,
 * price options, payment-method rows, select options, subscription cards. [RowBinder] already
 * carries this treatment for a row through `Trailing.Marker`; this binder is for the objects that
 * are not rows.
 *
 * | Axis | Unselected | Selected |
 * |---|---|---|
 * | Fill | `colorSurface` | 12% accent (`@color/accent_fill_12`) |
 * | Border | 1dp `?attr/colorOutlineVariant` | 1dp `?attr/colorPrimary` - **same width** |
 * | Title weight | 500 | **700** |
 * | Trailing check | slot reserved, alpha 0 | 20dp check in `colorPrimary`, alpha 1 |
 *
 * Four axes, **zero layout change**. The fill and the border are drawn by the state-list drawables
 * behind `isActivated` (`@drawable/bg_selectable_item`, `@style/Widget.Departament.Card.Selectable`),
 * which is why this binder sets the flag rather than painting colours: a binder that painted them
 * would be a fifth place the selected blue is defined. The check slot stays VISIBLE and changes
 * only its alpha, so nothing reflows. Radius never changes. Stroke width never changes. The shipped
 * price option moves radius 14 to 20 and stroke 1 to 1.5 on selection, which is why it visibly
 * jumps, and that is precisely what this replaces.
 *
 * Not permitted, and not reachable through this API: a coloured left edge, a scale change on
 * selection, a shadow, or a second badge repeating what the check already says.
 */
object SelectionBinder {

    /**
     * Applies the selection treatment to one selectable object.
     *
     * ```kotlin
     * SelectionBinder.apply(
     *     view = holder.binding.root,
     *     selected = item.uuid == selectedUuid,
     *     marker = holder.binding.check,
     *     title = holder.binding.title,
     *     animated = true,
     * )
     * ```
     *
     * @param view the selectable object. Its background must be a state-list that reacts to
     *   `state_activated`; that drawable owns the fill and the border.
     * @param selected the state.
     * @param marker the always-reserved check slot. Its alpha carries the axis, never its
     *   visibility - hiding it would reflow the row on every selection change.
     * @param title the object's title, for the weight axis: 700 selected, 500 not. Both are ramp
     *   roles; the weight is never set inline.
     * @param animated true when the user just changed the selection, so the check crossfades over
     *   `motion_state` 220ms on `ease_standard`. False on first bind and on rebind, so a list that
     *   scrolls does not replay the transition. Reduced motion snaps either way.
     */
    fun apply(
        view: View,
        selected: Boolean,
        marker: ImageView? = null,
        title: TextView? = null,
        animated: Boolean = false,
    ) {
        view.isActivated = selected

        title?.setTextAppearance(
            if (selected) R.style.TextAppearance_App_Title else R.style.TextAppearance_App_Title_Medium
        )

        marker?.let {
            it.visibility = View.VISIBLE
            // The check repeats what the announced selected state already says.
            it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            val target = if (selected) 1f else 0f
            if (!animated) {
                it.alpha = target
            } else {
                it.motion(snap = { it.alpha = target }) {
                    it.animate()
                        .alpha(target)
                        .setDuration(it.durationOf(R.integer.motion_state))
                        .setInterpolator(it.curve(R.interpolator.ease_standard))
                        .start()
                }
            }
        }

        announce(view, selected)
    }

    /**
     * 00-rules.md 14.9 and 22-components 18: a selected item is EXPOSED to TalkBack
     * (`AccessibilityNodeInfo.isSelected`), not only drawn. Colour is never the only signal, and a
     * check glyph a screen reader cannot see is the same failure one step further along.
     */
    private fun announce(view: View, selected: Boolean) {
        ViewCompat.setAccessibilityDelegate(view, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(
                host: View,
                info: AccessibilityNodeInfoCompat,
            ) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.isSelected = selected
            }
        })
    }
}
