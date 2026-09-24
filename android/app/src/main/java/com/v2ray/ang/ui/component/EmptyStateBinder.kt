package com.v2ray.ang.ui.component

import android.view.View
import android.view.ViewStub
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.LayoutRes
import com.google.android.material.button.MaterialButton
import com.v2ray.ang.R

/**
 * The empty state - `res/layout/view_empty_state.xml` bound in one call (22-components.md 15,
 * 32-master-plan-android.md 8.8). One grammar, replacing the three the app ships today: a card on
 * Account, a tile block on Devices, a `drawableTop` on a TextView on Payment history.
 *
 * ```
 * [ 64 tile r20, neutral, 32dp glyph ]
 *                 16
 *   Title   Headline 24/700, centred, max 2 lines
 *                 8
 *   Line    Subtitle 13/400, centred, max 2 lines
 *                 24
 *   Action  ONE button, or none
 * ```
 *
 * What the binder enforces, because these are the three things that go wrong:
 *
 * - **The tile is neutral.** An empty state is not the screen's primary action surface and does not
 *   spend the accent budget on decoration, so the tile colour is not a parameter.
 * - **One action, or none.** Two buttons in an empty state is the current Account defect. The
 *   action lives in a `ViewStub`, and a stub inflates once, so the second button has nowhere to go.
 * - **The cause is passed in, never written into the layout.** The error variant is this same
 *   silhouette with the alert glyph, the MAPPED cause and a Tertiary «Повторить»; the hard-wired
 *   «Что-то пошло не так» in `activity_account.xml` is exactly what this replaces.
 *
 * Copy follows 00-rules.md 9.5 - title says what is not here, one line says why or what it gives
 * you, one action - and belongs to the screen, so this binder takes `CharSequence` and owns none.
 */
object EmptyStateBinder {

    /**
     * Which button the action is. It is chosen ONCE, at the first bind that shows an action,
     * because a `ViewStub` inflates once.
     *
     * - [PRIMARY] - the action genuinely is the screen's job: «Купить», «Добавить провайдера».
     * - [SECONDARY] - anything else. This is the default, and it is the common case.
     * - [TERTIARY] - the error variant's «Повторить».
     */
    enum class Emphasis { PRIMARY, SECONDARY, TERTIARY }

    /**
     * Binds an empty or an error state and makes the block visible.
     *
     * ```kotlin
     * EmptyStateBinder.bind(
     *     root = binding.emptyState.emptyState,
     *     glyph = R.drawable.ic_globe_24dp,
     *     title = getString(R.string.servers_empty_title),
     *     line = getString(R.string.servers_empty_line),
     *     actionLabel = getString(R.string.servers_empty_action),
     *     emphasis = EmptyStateBinder.Emphasis.PRIMARY,
     *     onAction = { SubPage.open(this, providerIntent) },
     * )
     * ```
     *
     * @param root the inflated block, i.e. `@id/empty_state`.
     * @param glyph the 32dp neutral glyph. Decorative: the title is the accessible name.
     * @param title what is not here. Headline by default; pass `compact = true` inside a card or a
     *   list section, where the Title role is the right step of the ramp.
     * @param line why it is not here, or what having it would give the user. One line, about 60
     *   characters, or null when the title already says everything.
     * @param actionLabel the one action, or null for a state with nothing to do («Платежей пока
     *   нет»). Never two.
     * @param emphasis which button tier, honoured at the first bind that shows an action.
     * @param compact true when the state sits inside a card or a list rather than owning the screen.
     * @param onAction what the action does, routed through [onSingleClick]. Required whenever
     *   [actionLabel] is set, and forbidden when it is not.
     */
    fun bind(
        root: View,
        @DrawableRes glyph: Int,
        title: CharSequence,
        line: CharSequence? = null,
        actionLabel: CharSequence? = null,
        emphasis: Emphasis = Emphasis.SECONDARY,
        compact: Boolean = false,
        onAction: ((View) -> Unit)? = null,
    ) {
        require((actionLabel == null) == (onAction == null)) {
            "Departament empty state: an action needs both a label and a listener. A button that " +
                "does nothing is worse than no button (00-rules.md 9.5)."
        }

        val slots = EmptyStateSlots.of(root)

        slots.glyph.setImageResource(glyph)

        slots.title.text = title
        slots.title.setTextAppearance(
            if (compact) R.style.TextAppearance_App_Title else R.style.TextAppearance_App_Headline
        )

        slots.line.text = line ?: ""
        slots.line.visibility = if (line.isNullOrEmpty()) View.GONE else View.VISIBLE

        bindAction(root, actionLabel, emphasis, onAction)

        root.visibility = View.VISIBLE
    }

    /** Hides the whole block. Use it when the content arrives; never hide the parts one by one. */
    fun hide(root: View) {
        EmptyStateSlots.action(root)?.clearClick()
        root.visibility = View.GONE
    }

    private fun bindAction(
        root: View,
        label: CharSequence?,
        emphasis: Emphasis,
        onAction: ((View) -> Unit)?,
    ) {
        if (label == null || onAction == null) {
            // Nothing to do here: leave the stub uninflated so the state costs no views at all.
            EmptyStateSlots.action(root)?.let {
                it.clearClick()
                it.visibility = View.GONE
            }
            return
        }

        val button = EmptyStateSlots.action(root) ?: inflateAction(root, emphasis) ?: return
        button.text = label
        button.visibility = View.VISIBLE
        button.onSingleClick(action = onAction)
    }

    private fun inflateAction(root: View, emphasis: Emphasis): MaterialButton? {
        val stub = root.findViewById<ViewStub>(R.id.empty_action_stub) ?: return null
        stub.layoutResource = layoutFor(emphasis)
        stub.inflate()
        return EmptyStateSlots.action(root)
    }

    @LayoutRes
    private fun layoutFor(emphasis: Emphasis): Int = when (emphasis) {
        Emphasis.PRIMARY -> R.layout.view_action_primary
        Emphasis.SECONDARY -> R.layout.view_action_secondary
        Emphasis.TERTIARY -> R.layout.view_action_tertiary
    }
}

/**
 * The empty state's child views. The action is deliberately absent from this holder: it lives
 * behind a `ViewStub` and does not exist until a state that has something to do asks for it.
 */
class EmptyStateSlots private constructor(
    val root: View,
    val glyph: ImageView,
    val title: TextView,
    val line: TextView,
) {

    companion object {

        private const val LAYOUT = "res/layout/view_empty_state.xml"

        fun of(root: View): EmptyStateSlots = EmptyStateSlots(
            root = root,
            glyph = root.slot(R.id.empty_glyph, LAYOUT, "empty_glyph"),
            title = root.slot(R.id.empty_title, LAYOUT, "empty_title"),
            line = root.slot(R.id.empty_line, LAYOUT, "empty_line"),
        )

        /** The action button, or null while its `ViewStub` is still uninflated. */
        fun action(root: View): MaterialButton? = root.findViewById(R.id.empty_action)
    }
}
