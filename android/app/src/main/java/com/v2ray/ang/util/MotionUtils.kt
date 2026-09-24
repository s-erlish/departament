package com.v2ray.ang.util

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.annotation.RequiresApi

/**
 * Small, pure motion helpers shared across the app. No wiring, no side effects
 * beyond the single call each function names.
 *
 * The motion tempo lives in res/values/motion.xml (motion_press_in / _out / _state /
 * _reveal / _stagger) and the easing in res/interpolator (ease_out_quint / _quart /
 * ease_standard). Press feedback is declared in res/anim/press_scale.xml and applied
 * via android:stateListAnimator. These helpers exist so imperative animations honor the
 * same reduced-motion contract and offer consistent haptics.
 */
object MotionUtils {

    /**
     * True when the OS animation duration scale is non-zero. When the user turns
     * animations off (Developer options "Animator duration scale" = Off, or the
     * "Remove animations" accessibility setting), this returns false and callers
     * should jump straight to the end state instead of animating.
     */
    fun animationsEnabled(context: Context): Boolean =
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) != 0f
}

/**
 * Extension form of [MotionUtils.animationsEnabled] for use from a Context receiver.
 * `if (animationsEnabled()) animate() else jumpToEnd()`
 */
fun Context.animationsEnabled(): Boolean =
    Settings.Global.getFloat(
        contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f
    ) != 0f

/**
 * True when motion should be reduced/skipped for this view's context. Convenience
 * inverse of [animationsEnabled] so call sites read naturally:
 * `if (view.reducedMotion()) view.jumpToEnd()`
 */
fun View.reducedMotion(): Boolean = !context.animationsEnabled()

/**
 * ============================ HAPTICS ============================
 *
 * THE DEFECT THIS SHAPE EXISTS FOR. The owner: «на самсунге нет вибрации, например на моем ксяоми
 * вся вибрация есть, а вот на самсунге вообще вибрации в приложении нет». Same APK, same taps, one
 * OEM buzzes and the other is silent — and the app could not tell, because it was throwing the
 * request over the wall and never looking at what came back.
 *
 * `View.performHapticFeedback(constant)` is not "vibrate". It is a REQUEST to the platform's own
 * touch-feedback pipeline, phrased in a vocabulary the firmware is free not to speak, and it answers
 * with a Boolean. The framework (`PhoneWindowManager.performHapticFeedback`) returns **false**,
 * having played nothing at all, in exactly four cases:
 *
 *  1. the view is not attached (`mAttachInfo == null`) — checked inside [View] before the IPC;
 *  2. the view's own `android:hapticFeedbackEnabled` is false — likewise checked in [View];
 *  3. `Settings.System.HAPTIC_FEEDBACK_ENABLED` is 0 — **system touch feedback is switched off**;
 *  4. the build's `getVibrationEffect(effectId)` switch has no case for the constant we passed and
 *     falls through to `default: return null` — **the OEM does not speak that word**.
 *
 * Case 4 is a real hazard and is handled: `VIRTUAL_KEY` and `CLOCK_TICK` are ancient (API 3 and 21)
 * but they are still only *names*, resolved through an OEM-overlaid config, and `CONFIRM` — the
 * connect hero moment's word — arrived in API 30 against a `minSdk` of 24, so below that it was a
 * guaranteed no-op. Each request now degrades to an older word before giving up.
 *
 * CASE 3 IS THE ONE ON THE OWNER'S GALAXY, and the device settled it rather than a guess:
 * «Системная вибрация → Сенсорный ввод» is **OFF**, while «Интенсивность вибрации → Система» sits at
 * roughly a third — NOT zero — and every other switch on that screen (набор номера, клавиатура
 * Samsung, зарядка, жесты навигации, камера) is ON. And: «с такими настройками не было вибрации, а в
 * incy и happ есть, то есть проблема в коде».
 *
 * THOSE TWO CLIENTS ARE NOT CHEATING AND WE WERE NOT BEING VIRTUOUS. `HAPTIC_FEEDBACK_ENABLED` is
 * the switch for the platform's OWN touch feedback — the pipeline `performHapticFeedback` routes
 * through. It has never bound an app that drives the vibrator itself, and the One UI screen above is
 * the proof of what it actually means: the keypad, the keyboard, the charger and the camera each
 * carry their own switch, and «Сенсорный ввод» is one of them — not a verdict on everything that may
 * ever buzz. Reading it as "this person wants no vibration from any app" was the mistake, and the
 * first version of this file made it twice: it asked the system, was refused for case 3, then
 * re-checked the same key in app space and refused itself. Two gates, one setting, fallback dead.
 *
 * SO THE TWO FEELS PART COMPANY HERE. This is the policy, and it is deliberate:
 *
 *  - [Feel.PRESS] — connect, disconnect, purchase confirm, destructive confirm, and [confirmHaptic].
 *    A state change the user ASKED FOR and wants confirmed, in the same class as a call connecting
 *    or a charger being plugged in: not touch feedback, and not what «Сенсорный ввод» governs. **It
 *    plays with that toggle off.** It is also precisely what Incy and Happ buzz for.
 *  - [Feel.TICK] — tab switch, switch toggle, carousel page. The incidental response to an ordinary
 *    tap, which IS the definition of touch feedback. **It stays gated on the toggle.** A phone whose
 *    owner turned system touch feedback off must not start buzzing under every row because a VPN
 *    client decided its own taps were the exception.
 *
 * WHAT IS STILL HONOURED, for both feels: an off-screen view, a view that opted out with
 * `android:hapticFeedbackEnabled`, a device with no motor, and the «Интенсивность вибрации» slider —
 * which the fallback obeys twice over, by reading it and by tagging the effect `USAGE_TOUCH` so the
 * platform scales it as well. `FLAG_IGNORE_GLOBAL_SETTING` is NOT used and must not be: it is a
 * blunt override of every gate at once, which is why API 33 deprecated it and why new releases
 * ignore it. Nothing here overrides the platform; [Feel.PRESS] simply stops asking a question that
 * was never about it.
 *
 * THE RETURN VALUE IS STILL WHAT KEEPS THIS FROM DOUBLE-BUZZING. Every `return false` in the
 * framework happens *before* `mVibrator.vibrate(...)`, so false is a promise that nothing was
 * played. On a device that already works (the owner's Xiaomi, and any Samsung with «Сенсорный ввод»
 * on) the first request answers true, [playHaptic] returns immediately, and everything below is dead
 * code at runtime. It is reached only after the platform has said, in its own words, that it did
 * nothing at all.
 */

/**
 * Standard "button press" haptic. Use on primary taps/confirmations.
 *
 * WITH «Сенсорный ввод» OFF THIS STILL PLAYS. It confirms a state change the user asked for —
 * connect, disconnect, payment, a destructive yes — which is not what that switch is a switch for.
 * The «Интенсивность вибрации» slider still scales it and a muted motor still silences it.
 *
 * `KEYBOARD_TAP` is the second rung rather than a different feel: both it and `VIRTUAL_KEY` resolve
 * to `EFFECT_CLICK`, but `VIRTUAL_KEY` is historically wired to the navigation-BAR key haptic, which
 * a gesture-navigation One UI build has no keys left to fire.
 */
fun View.pressHaptic() {
    playHaptic(Feel.PRESS, HapticFeedbackConstants.VIRTUAL_KEY, HapticFeedbackConstants.KEYBOARD_TAP)
}

/**
 * Light "tick" haptic. Use for scrubbing, stepping, or incremental selection.
 *
 * WITH «Сенсорный ввод» OFF THIS STAYS SILENT, on purpose. A tick answers a tap and nothing else,
 * which is the exact thing that switch turns off; a client that buzzed under every row anyway would
 * be louder than the system it is running in.
 *
 * `CONTEXT_CLICK` backs `CLOCK_TICK` up because it is the same weight one rung heavier —
 * `EFFECT_TICK` against `EFFECT_TEXTURE_TICK` — so a device that dropped the featherweight one still
 * answers with something that reads as a tick and not as a press.
 */
fun View.tickHaptic() {
    playHaptic(Feel.TICK, HapticFeedbackConstants.CLOCK_TICK, HapticFeedbackConstants.CONTEXT_CLICK)
}

/**
 * The connect/disconnect confirmation — 32-master-plan-android.md 7.6's PRESS rung, asked for with
 * the platform's dedicated word first so a device that has a distinct "confirm" feel keeps it.
 *
 * `HapticFeedbackConstants.CONFIRM` arrived in API 30. Below that, and on any build that never
 * mapped it, the request is refused and this is [pressHaptic] — which is what 7.6 specifies for
 * connect anyway. Nothing is played twice: the second request is only made because the first
 * reported it played nothing.
 *
 * It therefore inherits [Feel.PRESS]'s policy, which is the whole point of the owner's report: the
 * tunnel coming up is the one buzz he came looking for, and it now survives «Сенсорный ввод» being
 * off exactly as it does in the two clients he compared against.
 */
fun View.confirmHaptic() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    ) {
        return
    }
    pressHaptic()
}

/**
 * The two sanctioned feels, and the explicit effect each one degrades to. Kept private: screens
 * speak `Haptic.PRESS` / `Haptic.TICK` (see `ui/component/SingleClick.kt`) and the three functions
 * above, never this. The set stays closed at two — 32-master-plan-android.md 7.6 — because
 * [confirmHaptic] is the PRESS feel asked for by a better name, not a third thing.
 *
 * @param predefined the platform's own named effect, used from API 29. `createPredefined` already
 *   carries a generic fallback for hardware that lacks the primitive, so this is the better feel
 *   wherever it exists.
 * @param millis the plain motor pulse for API 26–28, and the whole story below 26 where
 *   [VibrationEffect] does not exist yet. 20ms reads as a click, 12ms as a tick.
 * @param isTouchFeedback whether this feel is the thing «Сенсорный ввод»
 *   (`Settings.System.HAPTIC_FEEDBACK_ENABLED`) is a switch FOR. It is a statement about what the
 *   feel means, not a permission flag: a tick answers a tap and is touch feedback by definition; a
 *   press confirms a state change the user asked for and is not. Only the former is gated on it.
 */
private enum class Feel(val predefined: Int, val millis: Long, val isTouchFeedback: Boolean) {
    PRESS(VibrationEffect.EFFECT_CLICK, 20L, isTouchFeedback = false),
    TICK(VibrationEffect.EFFECT_TICK, 12L, isTouchFeedback = true),
}

/**
 * Ask the system, in its own vocabulary, newest word first; fall back only once it has said it did
 * nothing. See the block comment above for why the Boolean is trustworthy and why this cannot buzz
 * twice.
 */
private fun View.playHaptic(feel: Feel, vararg constants: Int) {
    for (constant in constants) {
        if (performHapticFeedback(constant)) return
    }
    if (canVibrate(feel)) context.vibrateOnce(feel)
}

/**
 * Everything the fallback still honours, in the order it is cheapest to check.
 *
 * The first two are the gates [View] itself applies before the request ever reaches the system
 * (cases 1 and 2 up top). They are re-checked rather than assumed, so an off-screen view and a view
 * that opted out in XML get exactly the silence they asked for.
 *
 * The third is «Сенсорный ввод» — and it is asked ONLY about [Feel.TICK]. See the policy in the
 * block comment above: it is the switch for the platform's touch feedback, a tick is touch feedback
 * and a press is not, so a press does not ask a question that was never about it. This is not a
 * bypass bolted on top; it is the one gate whose SCOPE was wrong.
 *
 * The fourth, the intensity slider, applies to both — a person who dragged «Интенсивность вибрации»
 * to zero has said something about the motor itself, not about which app is allowed to reach it.
 * `hasVibrator()` is the fifth and lives in [vibrateOnce], where the handle is.
 *
 * DEPRECATION is suppressed for one constant. `Settings.System.HAPTIC_FEEDBACK_ENABLED` is marked
 * deprecated because a third-party app may no longer WRITE it; reading it is still how the framework
 * itself gates touch feedback, and there is no public replacement — `Vibrator` exposes no intensity
 * query. Retyping the same key as a string literal to silence the marker would hide that, so the
 * typed constant stays and the reason is written down instead.
 */
@Suppress("DEPRECATION")
private fun View.canVibrate(feel: Feel): Boolean {
    if (!isAttachedToWindow || !isHapticFeedbackEnabled) return false
    val resolver = context.contentResolver
    if (feel.isTouchFeedback &&
        Settings.System.getInt(resolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0
    ) {
        return false
    }
    // `haptic_feedback_intensity` is the slider Android 12 added; it is `@SystemApi` on
    // `Settings.System`, so the key is spelled out below. A build without it returns the default and
    // is allowed — absence of the setting is not a refusal.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    return Settings.System.getInt(resolver, HAPTIC_FEEDBACK_INTENSITY, INTENSITY_DEFAULT) != INTENSITY_OFF
}

/**
 * One pulse, tagged as touch feedback so the platform keeps the last word on how strong it is.
 *
 * `USAGE_TOUCH` matters: an untagged vibration is treated as a generic alarm-ish one and ignores the
 * user's touch-feedback intensity, which would make the fallback louder than the haptics it is
 * standing in for. Below API 33 the same usage is expressed through `AudioAttributes`
 * (`USAGE_ASSISTANCE_SONIFICATION` is what `VibrationAttributes` maps to `USAGE_TOUCH`).
 *
 * From API 31 the vibrator is reached through [VibratorManager] — on a multi-actuator device
 * `getSystemService(Vibrator::class.java)` and `getDefaultVibrator()` are not guaranteed to be the
 * same object, and the latter is the one the platform itself drives touch feedback with.
 *
 * THE ONE ASSUMPTION LEFT IN THIS FILE, and the first place to look if a Galaxy is still silent:
 * `USAGE_TOUCH` is scaled by `VibrationSettings` from the INTENSITY setting, and in AOSP
 * `HAPTIC_FEEDBACK_ENABLED` is read by `PhoneWindowManager` alone and never folds into it. If some
 * OEM wires the toggle into the usage's intensity as well, the platform would drop this pulse for
 * the same reason the request was refused and [Feel.PRESS] would still not play. The owner's own
 * screen is the evidence against that — «жесты навигации» buzz on that phone, off the same slider,
 * with «Сенсорный ввод» off — but it is evidence, not a guarantee. The fix if it ever bites is to
 * choose a different usage here, NOT to reach for `FLAG_IGNORE_GLOBAL_SETTING`.
 *
 * DEPRECATION is suppressed for exactly two calls, each the only overload that exists below the API
 * that replaced it: `vibrate(VibrationEffect, AudioAttributes)` under 33 and `vibrate(long)` under 26.
 */
@Suppress("DEPRECATION")
private fun Context.vibrateOnce(feel: Feel) {
    val vibrator = touchVibrator() ?: return
    if (!vibrator.hasVibrator()) return
    when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> vibrator.vibrate(
            feel.effect(),
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH)
        )

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> vibrator.vibrate(
            feel.effect(),
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )

        else -> vibrator.vibrate(feel.millis)
    }
}

private fun Context.touchVibrator(): Vibrator? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        getSystemService(Vibrator::class.java)
    }

@RequiresApi(Build.VERSION_CODES.O)
private fun Feel.effect(): VibrationEffect =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        VibrationEffect.createPredefined(predefined)
    } else {
        VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE)
    }

/** `Settings.System.HAPTIC_FEEDBACK_INTENSITY` — `@SystemApi`, so the key is named here. */
private const val HAPTIC_FEEDBACK_INTENSITY = "haptic_feedback_intensity"

/** `Vibrator.VIBRATION_INTENSITY_OFF` / `_MEDIUM`, likewise not public. */
private const val INTENSITY_OFF = 0
private const val INTENSITY_DEFAULT = 2
