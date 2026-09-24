package com.v2ray.ang.ui.component

import android.content.res.ColorStateList
import android.view.View
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import com.google.android.material.chip.Chip
import com.v2ray.ang.R

/**
 * The chip (22-components.md 10, 32-master-plan-android.md 8.4). One component, a closed set of
 * tones, and a rule that is easy to state and easy to break: **a chip is a label, never a control.**
 * A pill you can press is a Tertiary button or a segment, not a chip. The binder therefore takes no
 * click listener at all, and clears interactivity on every bind.
 *
 * Geometry and type come from `@style/Widget.Departament.Chip` and its variants; what a binder is
 * for is the tone CHANGING at runtime - a subscription that goes «Активна» -> «Истекает» ->
 * «Истекла» is one chip wearing three tones, and hand-written code that swaps two colours and
 * forgets the glyph is how the shipped app ended up with four status hues for three states.
 *
 * Two rules the type system enforces here:
 *
 * - **Colour is never the only signal** (00-rules.md 6.3). A status chip must carry the word, so
 *   blank text on a status tone throws. A bare coloured dot next to a label that already says the
 *   state is decoration, and it is deleted rather than bound.
 * - **There is no fourth status hue** (R12). «Отменён» is [Tone.NEUTRAL], because a cancelled
 *   payment is not a warning.
 */
object ChipBinder {

    /**
     * The whole chip vocabulary.
     *
     * - [NEUTRAL] - protocol, transport, «Отменён», «Это устройство», «Пробный период». A trial is
     *   a fact, not a warning.
     * - [TECHNICAL] - neutral colours with the brand face, for Latin technical tokens and figures:
     *   `VLESS`, `Reality`, a port number.
     * - [ACCENT] - the tariff badge, and nothing else. One per subscription card.
     * - [OK] / [WARN] / [ERROR] - the three payment and health states, and no fourth.
     */
    enum class Tone { NEUTRAL, TECHNICAL, ACCENT, OK, WARN, ERROR }

    /**
     * Binds one chip.
     *
     * ```kotlin
     * ChipBinder.bind(binding.chipStatus, "Активна", ChipBinder.Tone.OK, R.drawable.ic_action_done)
     * ```
     *
     * @param chip a `com.google.android.material.chip.Chip` carrying a `Widget.Departament.Chip`
     *   style, or any `TextView` wearing the same shape.
     * @param text the word. Sentence case, never ALL-CAPS, never an ellipsis - a chip measures
     *   `wrap_content` and the text beside it yields, so it never truncates its own label.
     * @param tone which of the six. Status tones require [text] to be non-blank.
     * @param glyph an optional 16dp leading glyph, `0` for none. On a status tone the glyph is the
     *   second channel; it is never the only one.
     */
    fun bind(
        chip: TextView,
        text: CharSequence,
        tone: Tone = Tone.NEUTRAL,
        @DrawableRes glyph: Int = 0,
    ) {
        require(text.isNotBlank() || !tone.isStatus()) {
            "Departament chip: a status chip always carries the word (00-rules.md 6.3). " +
                "Colour on its own is not a signal, and a bare coloured dot is decoration."
        }

        chip.text = text
        chip.setTextAppearance(
            if (tone == Tone.TECHNICAL) {
                R.style.TextAppearance_App_Chip
            } else {
                R.style.TextAppearance_App_Chip_Ui
            }
        )

        val content = ColorStateList.valueOf(chip.contentColor(tone))
        val container = ColorStateList.valueOf(chip.containerColor(tone))
        chip.setTextColor(content)

        when (chip) {
            is Chip -> {
                chip.chipBackgroundColor = container
                // A chip never carries both a fill and a stroke: that is a hole with a rim.
                chip.chipStrokeWidth = 0f
                if (glyph == 0) {
                    chip.chipIcon = null
                    chip.isChipIconVisible = false
                } else {
                    chip.setChipIconResource(glyph)
                    chip.chipIconTint = content
                    chip.isChipIconVisible = true
                }
                chip.isCheckable = false
            }

            else -> {
                chip.backgroundTintList = container
                if (glyph == 0) {
                    chip.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
                } else {
                    chip.setCompoundDrawablesRelativeWithIntrinsicBounds(glyph, 0, 0, 0)
                    TextViewCompat.setCompoundDrawableTintList(chip, content)
                }
            }
        }

        // A chip is a label. It is read out, it is not pressed.
        chip.clearClick()
        chip.isLongClickable = false
        chip.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private fun Tone.isStatus(): Boolean = this == Tone.OK || this == Tone.WARN || this == Tone.ERROR

    private fun View.containerColor(tone: Tone): Int = when (tone) {
        Tone.NEUTRAL, Tone.TECHNICAL ->
            themeColor(com.google.android.material.R.attr.colorSurfaceContainerHighest)

        Tone.ACCENT -> themeColor(com.google.android.material.R.attr.colorPrimaryContainer)
        Tone.OK -> themeColor(com.google.android.material.R.attr.colorTertiaryContainer)
        Tone.WARN -> ContextCompat.getColor(context, R.color.warning_container)
        Tone.ERROR -> themeColor(com.google.android.material.R.attr.colorErrorContainer)
    }

    private fun View.contentColor(tone: Tone): Int = when (tone) {
        Tone.NEUTRAL, Tone.TECHNICAL ->
            themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)

        Tone.ACCENT -> themeColor(com.google.android.material.R.attr.colorOnPrimaryContainer)
        Tone.OK -> ContextCompat.getColor(context, R.color.color_success_text)
        Tone.WARN -> ContextCompat.getColor(context, R.color.color_warning_text)
        Tone.ERROR -> themeColor(R.attr.colorDestructiveText)
    }
}
