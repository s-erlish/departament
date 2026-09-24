package com.v2ray.ang.ui.component

import android.animation.AnimatorInflater
import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import androidx.annotation.AnimatorRes
import com.v2ray.ang.R
import com.v2ray.ang.util.reducedMotion

/**
 * THE ONE PRESS RESPONSE, applied from Kotlin (handoff README §1).
 *
 * Everything about the press is declarative and lives in resources — the scale in `res/anim/`, the
 * background step in the view's own state-list background, the clock and the curves in
 * `res/values/motion.xml` and `res/interpolator/`. A layout that can name its rung in XML needs
 * nothing from this file:
 *
 * ```xml
 * android:background="@drawable/bg_row"
 * android:stateListAnimator="@anim/press_row"
 * ```
 *
 * This file exists for the one thing XML cannot say, and it is README §11's very first grabl:
 * **the text judders on the way back.** A `TextView` re-rasterises its glyphs whenever its
 * effective scale changes and snaps them to the pixel grid when it lands, so the 2% overshoot on
 * `ease_press_out` — the thing that makes the release feel like a spring — is also the thing that
 * makes the label twitch at the end of it. The prototype pins a compositing layer for the duration
 * (`will-change: transform`); [pressFeedback] does the same with a hardware layer, held from the
 * finger going down until the rebound has settled.
 *
 * Prefer it on anything with TEXT in it that scales: a row, a card, a labelled button, a select
 * option. A bare glyph has nothing to re-rasterise and can stay in XML.
 *
 * THE LADDER, and picking the right rung is the whole decision:
 *
 * | Target | Rung | Scale |
 * |---|---|---|
 * | list row, card, sheet row | `R.anim.press_row` | 0.975 |
 * | button, chip, select option | `R.anim.press_button` | 0.965 |
 * | borderless icon | `R.anim.press_icon` | 0.88 |
 * | the connect button | `R.anim.press_connect` | 0.97 |
 *
 * There is no ripple on any of them. §1 forbids it by name, and every Departament button style
 * sets `rippleColor` to transparent so the platform cannot put one back.
 */

/**
 * Attaches the press response: the scale animator for [rung], plus the hardware layer that keeps
 * the label from twitching when the rebound lands.
 *
 * ```kotlin
 * row.pressFeedback(R.anim.press_row)
 * option.pressFeedback(R.anim.press_button)
 * ```
 *
 * The background step is NOT set here — it belongs to the view's background
 * (`@drawable/bg_row`, `@drawable/bg_select_item`, a button's `backgroundTint` ColorStateList), so
 * that a view keeps exactly one description of what it looks like.
 *
 * Under reduced motion the animator is still attached — the platform runs it at 0ms and the view
 * simply snaps to the pressed scale and back — but the layer is not, because there is no
 * transition left for it to smooth and a permanent hardware layer on a scrolling list costs
 * memory for nothing.
 *
 * The touch listener returns false and therefore consumes nothing: `dispatchTouchEvent` offers the
 * event here first, and returning false lets the view's own `onTouchEvent` run exactly as before,
 * so click handling, long press and the debounce guard in [onSingleClick] are untouched. It is
 * also why accessibility is unaffected: a TalkBack activation goes through `performClick` and
 * never through this path at all.
 */
@SuppressLint("ClickableViewAccessibility")
fun View.pressFeedback(@AnimatorRes rung: Int) {
    stateListAnimator = AnimatorInflater.loadStateListAnimator(context, rung)
    if (reducedMotion()) return

    val settle = resources.getInteger(R.integer.motion_press_out).toLong()
    setOnTouchListener { view, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                view.setLayerType(View.LAYER_TYPE_HARDWARE, null)

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                // Held until the rebound has finished, not until the finger lifts: the overshoot
                // happens AFTER the release and is the part that judders.
                view.postDelayed({ view.setLayerType(View.LAYER_TYPE_NONE, null) }, settle)
        }
        false
    }
}
