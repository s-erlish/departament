package com.v2ray.ang.ui.component

import android.animation.Animator
import android.util.TypedValue
import android.view.View
import android.view.animation.AnimationUtils
import android.view.animation.Interpolator
import android.widget.CompoundButton
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.IntegerRes
import androidx.annotation.InterpolatorRes
import androidx.core.content.ContextCompat
import com.v2ray.ang.util.reducedMotion
import java.util.WeakHashMap

/**
 * Plumbing shared by every binder in this package: theme-attribute colours, the shared curves and
 * duration tokens, the reduced-motion gate, and per-view animator bookkeeping.
 *
 * Nothing here is a component. It exists so the binders below never hard-code a duration, never
 * name a curve of their own, and never animate without checking whether the user wants motion.
 *
 * Everything in this file is `internal` — the package's own plumbing, not part of the surface a
 * screen binds against — with ONE exception, [restoreChecked], which is a rule every screen with a
 * switch has to obey and therefore has to be able to reach. A screen agent calls [RowBinder],
 * [ToolbarBinder], [EmptyStateBinder], [ChipBinder], [SkeletonBinder], [SelectionBinder], [SubPage],
 * [onSingleClick] and [restoreChecked], and nothing else.
 */

/**
 * WRITE A STORED SWITCH POSITION WITHOUT PLAYING IT. The owner, about the settings tab: «переключатели
 * как будто дёргаются, когда захожу в настройки… включаются очень быстро».
 *
 * Every switch inflates in its DEFAULT position — off — and is only then handed the stored value, so
 * a switch that is on gets drawn off first and morphed on in front of the user. `setChecked` alone
 * does not prevent it, and that is the part that is easy to get wrong: `SwitchCompat.setChecked`
 * guards exactly ONE animation, the thumb's slide along the track, behind `isLaidOut()`. What it does
 * not guard is the drawable state change it also performs — `mtrl_switch_thumb` is an
 * `<animated-selector>` whose unchecked→checked `<transition>` is an `<animated-vector>` morphing the
 * thumb's path over ~250 ms. An AnimatedVectorDrawable asked to start before its view has ever been
 * drawn does not lose the animation: it PARKS it and flushes it on the first `draw()`. So the morph
 * is not merely surviving the bind, it is timed to begin on the screen's very first frame.
 *
 * [View.jumpDrawablesToCurrentState] is the answer to exactly that: the thumb and the track jump to
 * the end of whatever transition is in flight and the position animator ends. The state is already
 * correct; only the theatre around it is dropped.
 *
 * THE `isChecked == value` GUARD IS WHAT KEEPS THIS HONEST IN THE OTHER DIRECTION, and it is why
 * this is a helper rather than two lines copied around. A bind pass runs again on resume, after
 * every picker, and after the user's own tap; without the guard the next pass would cut off the
 * animation the user had just started with their own finger. With it, a bind that changes nothing
 * touches nothing, and only a value the user did not just set is snapped into place.
 *
 * This lives here, once, because the same defect was fixed on the settings tab alone and left
 * standing on every other screen that binds a switch — the shape that already cost this project
 * three rounds with the mono-theme tile.
 */
fun CompoundButton.restoreChecked(value: Boolean) {
    if (isChecked == value) return
    isChecked = value
    jumpDrawablesToCurrentState()
}

/**
 * Resolves a colour the theme carries as an attribute.
 *
 * Colours come from `?attr/...`, never from a raw hex and never from `@color/md_theme_*` directly:
 * the first two are what break the light and the mono themes (00-rules.md 6,
 * 32-master-plan-android.md 2.2). Returns 0 when the attribute is not mapped in the current theme,
 * which is a theme bug and shows up immediately as a black glyph.
 */
@ColorInt
internal fun View.themeColor(@AttrRes attr: Int): Int {
    val value = TypedValue()
    if (!context.theme.resolveAttribute(attr, value, true)) return 0
    return if (value.resourceId != 0) ContextCompat.getColor(context, value.resourceId) else value.data
}

/**
 * One of the four curves in `res/interpolator/` - `ease_out_quart` for press, `ease_out_quint` for
 * reveal and settle, `ease_standard` for two-way crossfade, `ease_out_expo` for the single auth to
 * home hand-off. No binder declares a curve of its own (00-rules.md 8.3).
 */
internal fun View.curve(@InterpolatorRes interpolator: Int): Interpolator =
    AnimationUtils.loadInterpolator(context, interpolator)

/** A duration token from `res/values/motion.xml`, in ms. No binder hard-codes one (00-rules.md 3.7). */
internal fun View.durationOf(@IntegerRes token: Int): Long = resources.getInteger(token).toLong()

/**
 * The reduced-motion contract in one call (00-rules.md 8.8, 32-master-plan-android.md 7.5).
 *
 * Runs [animate] when the OS animation scale is non-zero and [snap] - the END STATE, never a
 * halfway house - when it is zero. Every imperative animation in this package goes through here; an
 * animation that does not is a P1 accessibility defect.
 */
internal inline fun View.motion(snap: () -> Unit, animate: () -> Unit) {
    if (reducedMotion()) snap() else animate()
}

/**
 * Per-view animator bookkeeping, so a rebound view cannot end up with two animators fighting over
 * the same property - the classic RecyclerView flicker.
 *
 * A [WeakHashMap] rather than `setTag`, because a tag key must be a declared id resource and
 * `res/values/ids.xml` belongs to another wave; the one id it does declare, `tag_last_click`, is
 * spoken for by the double-press guard.
 */
internal object RunningAnimators {

    private val running = WeakHashMap<View, Animator>()

    fun set(view: View, animator: Animator) {
        cancel(view)
        running[view] = animator
        animator.start()
    }

    fun cancel(view: View) {
        running.remove(view)?.cancel()
    }
}

/**
 * The loud failure for a missing slot. Every binder here is bound to one layout file; a null child
 * means the caller passed the wrong root, which is a programming error and is reported at the first
 * bind rather than silently drawing a component with a hole in it.
 */
internal fun <T : View> View.slot(id: Int, layout: String, name: String): T {
    val view = findViewById<T>(id)
    return view ?: error(
        "Departament component: \"$name\" is missing. The view passed to this binder is not a " +
            "$layout - pass the root of that layout (the <include>'s own view), not its parent."
    )
}
