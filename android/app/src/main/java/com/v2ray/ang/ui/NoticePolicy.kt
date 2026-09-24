package com.v2ray.ang.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import androidx.annotation.StringRes
import com.google.android.material.snackbar.Snackbar
import com.v2ray.ang.R

/**
 * ============================================================================
 * WHO IS ALLOWED TO INTERRUPT THE USER.
 * ============================================================================
 *
 * The owner, on the whole layer rather than on one message:
 *
 *     «все вот такие уведомления нужно в целом убрать, это же старые от в2рей уведомления,
 *      и по qr коду когда добавляешь там внизу уведомление красное и тд, их убрать надо совсем»
 *
 * WHAT HE IS LOOKING AT. Upstream v2rayNG answers almost every action with a `Toasty` popup — a
 * green tick, a red cross, a system-chrome capsule floating over the app — and the sentence inside
 * it is written for the person who wrote the parser: «Обновлено серверов: 13 (успешно: 1, ошибки:
 * 0, пропущено: 1)», «Ошибка», a raw `e.message`, a `[VLESS] Finland(***12:443)` node summary.
 * Those popups are the single loudest tell that this app is a rebranded fork, and there were
 * FIFTY-ODD of them reachable from screens departament ships.
 *
 * THE DESKTOP SOLVED THIS SHAPE FIRST and its note is the one worth copying
 * (`v2rayN.Desktop/Common/NoticePolicy.cs`): filter the CHANNEL, not the surface. There, the
 * message still reaches the log and the connect screen still reads the failure reason; only the
 * popup is refused. Same here — every call site keeps compiling, every message still reaches
 * `LogUtil`, and this object decides what a human ever sees.
 *
 * THE FOUR RULES, and they are the whole policy:
 *
 *  1. **Success is silent.** The timestamp moved, the list repainted, the editor closed, the
 *     card redrew — that IS the confirmation, and it is a better one than a word that covers the
 *     thing it is confirming. `toastSuccess` reaches no surface at all, in any screen.
 *
 *  2. **A failure the user must act on gets ONE short sentence**, in the product's voice, on the
 *     ONE bottom feedback surface, above the navigation. An invalid QR, an unimportable
 *     clipboard, a подписка that returned nothing, a refresh that could not reach the operator:
 *     silence there would leave a tap with no answer, which is the opposite defect.
 *
 *  3. **Nothing reaches a human unless it is on [ALLOWED].** This is an allow-list and not a
 *     block-list on purpose: the next upstream merge will add `toast(R.string.something)` call
 *     sites, and every one of them is silent here until a person puts the id in this table and
 *     reads the sentence it prints. That is the property the owner asked for — that the layer
 *     cannot come back by itself.
 *
 *  4. **A message with no string id is machine text**, and machine text is never shown. Counters
 *     («…успешно: 1, ошибки: 0…»), node summaries, exception messages and file paths all arrive
 *     through the `CharSequence` door because they are all built by concatenation. Anything the
 *     product genuinely means to say has a resource id and can be listed above. [isMachineText]
 *     is a second net under the same door for the cases that get a formatted id anyway.
 *
 * The IN-FLIGHT SIGNAL is not on this channel and is not filtered by it. «Обновляем данные…» is a
 * state, not a notification — the owner's own G2 rule is that an action in flight must show
 * itself — so it has its own entry point, [progress], on the same bottom surface where he asked
 * for it («это уведомление нужно снизу над панелью навигации»).
 */
object NoticePolicy {

    /**
     * ============================================================================
     * RULE 5, AND IT IS ABOUT THE MOMENT RATHER THAN THE MESSAGE.
     * ============================================================================
     *
     * Nothing speaks while a full-screen flow is on the screen.
     *
     *     «когда залогинился через телеграм, анимация прошла и начала появляться главная, внизу
     *      уведомление появилось "нет подписок для обновления", потом подгрузилось, в общем
     *      как-то багнуло»
     *
     * The sentence he saw is a TRUE one and it is on [ALLOWED] on purpose: signing in fires
     * `refreshSubscriptions()` before the account's подписка has been written, so the refresh
     * really does find nothing to refresh, and «Обновить» with no подписки stored really does need
     * an answer — on the screen where a person pressed «Обновить». It has no business on top of a
     * sign-in the user is still in the middle of: the прогрузка overlay owns the whole window and
     * states its own progress in four steps, and a bar sliding out from under it reports on an
     * errand nobody started and about a condition that resolves itself two seconds later.
     *
     * WHY HERE AND NOT AT THE CALL SITE. The same three lines would otherwise have to be added to
     * `MainActivity.importConfigViaSub`, and then to the next thing the flow triggers, and the one
     * after that — the defect would be fixed for one sentence rather than for the moment. This is
     * the layer that decides who may interrupt the user, and «not now» is the same kind of answer
     * as «not ever». Rule 3's property holds either way: the allow-list is still the only door,
     * this only closes it for a while.
     *
     * A BOOLEAN AND NOT A COUNTER, because a counter that leaks silences the app for the rest of
     * the session. It is raised and lowered by the ONE contract that already brackets every
     * full-screen flow — [com.v2ray.ang.ui.component.HomeHandoff] and `MainActivity`'s
     * `holdHomeEntrance` / `revealHome` — and every path out of a flow, including the cancelled and
     * the failed one, goes through the release half (`GateView.releaseHome`). Setting the same
     * value twice costs nothing, so the two entry points cannot get out of step.
     */
    private var flowInProgress = false

    /** A full-screen flow has taken the window. Called by the hand-off, not by a screen. */
    fun enterFlow() {
        flowInProgress = true
    }

    /** The window is the app's again. Idempotent; safe when no flow was running. */
    fun leaveFlow() {
        flowInProgress = false
    }

    /** What a message is FOR. The policy's first question, before it looks at the text. */
    enum class Kind {
        /** Something worked. Never shown — rule 1. */
        SUCCESS,

        /** A refusal, a dead end, or a condition the user has to resolve. */
        FAILURE,

        /** Neither — a note about what just happened, or did not. */
        INFO,
    }

    /**
     * Every string this product is allowed to say out loud, and nothing else is.
     *
     * Each one is a sentence a person wrote for a person, each one answers an action the user
     * took, and each one names something the screen behind it cannot show on its own. Adding an
     * id here is a design decision: read the string first, and if it reports a count, names a
     * field, or quotes a parser, it does not belong on a screen at all.
     */
    private val ALLOWED: Set<Int> = setOf(
        // The add paths — QR, clipboard, file, deep link. The one the owner named.
        R.string.notice_add_failed,
        R.string.import_sub_empty,
        R.string.import_sub_duplicate,
        R.string.import_sub_foreign,
        // Refreshing a подписка. Success is the new timestamp on the card; only failure speaks.
        R.string.notice_refresh_failed,
        // …and the third outcome, which used to be filed as the second: a refresh with NOTHING to
        // refresh — no подписки stored, or every one of them disabled. Not a failure (no request
        // went out) and not a success, so rule 1 does not silence it, and without it «Обновить»
        // is a dead button in exactly the state that most needs explaining. Both surfaces that
        // refresh everything say it: `MainActivity.importConfigViaSub`, `SubSettingActivity.updateAll`.
        R.string.subs_update_none,
        // Итог «Обновить все». Успех молчит (правило 1); эти три — единственные исходы, у которых
        // есть что сказать. До них экран отвечал на неудачу тем же, чем на успех: ничем.
        R.string.subs_update_failed,
        R.string.subs_update_partial,
        R.string.subs_update_skipped,
        // The clipboard: the one outcome with no visible result of any kind.
        R.string.notice_copied,
        R.string.notice_copy_failed,
        // Выбранная картинка не содержит QR-кода. Выбор файла закрывается, экран остаётся — и без
        // этой строки не было ни ошибки, ни объяснения, почему ничего не произошло.
        R.string.toast_decoding_failed,
        // Dead ends the app cannot resolve for the user.
        R.string.toast_require_file_manager,
        R.string.toast_permission_denied,
        R.string.app_tile_first_use,
        R.string.toast_warning_pref_proxysharing_short,
        // Backup and restore: the file either exists afterwards or it does not.
        R.string.backup_failed,
        R.string.backup_restore_failed,
        R.string.backup_cloud_failed,
        // The account tab. Copying to the clipboard is the one action in the product with NO
        // visible result at all — nothing on the screen changes — so it is the one confirmation
        // that survives rule 1.
        R.string.account_referral_copied,
        R.string.account_sub_autorenew_failed,
        // The account's failure family, behind `AccountFragment.messageFor`. It answers a WARM
        // failure only — a refresh that broke while the tab already had data on it (a cold load
        // has the error hero card instead and is handled before this) — so the screen shows
        // yesterday's figures and, without these, said nothing about it. Each one is a sentence
        // with a next step in it; the fragment's own comment has claimed they speak since it was
        // written.
        R.string.account_error_service_unavailable,
        R.string.account_error_network,
        R.string.account_error_unauthorized,
        R.string.account_error_rate_limited,
        R.string.account_error_timeout,
        R.string.account_error_generic,
        // The three account actions that end in a dead end with nothing to show for it: a device
        // that is still in the list, an avatar that did not change, a checkout page that would not
        // open. The last one is the twin of `buy_no_browser`, which was listed while the identical
        // sentence on this screen was not.
        R.string.account_avatar_error,
        R.string.account_checkout_no_browser,
        R.string.account_top_up_invalid,
        // Платёж с баланса, который провайдер ещё проводит. Экран НЕ закрывается (это и есть
        // замысел — дать увидеть, как он дойдёт), поэтому без этой строки нажатие «Оплатить»
        // выглядело как нажатие, на которое ничего не ответили.
        R.string.account_pay_pending,
        R.string.devices_error_delete,
        // The buy flow — departament's own screens, and each of these blocks a tap.
        R.string.buy_select_option_first,
        R.string.buy_no_methods,
        R.string.buy_no_browser,
        R.string.buy_checkout_return,
        // Editors and settings: a refusal with a reason.
        R.string.srv_config_invalid,
        // Отказы формы «сервер вручную». До них на их месте стояли ПОДПИСИ ПОЛЕЙ, и ни одна не
        // была разрешена — то есть «Сохранить» на неполной форме не отвечало ничем.
        R.string.srv_name_required,
        R.string.srv_address_required,
        R.string.srv_port_required,
        R.string.srv_id_required,
        R.string.srv_password_required,
        R.string.srv_tls_required,
        R.string.srv_json_invalid,
        // Экран, который не может открыться: тип сервера без формы и нечитаемая запись. Оба
        // закрывают экран, и закрыться молча - это ровно тот пустой чёрный экран, которым
        // «сервер вручную» отвечал на неизвестный тип до этого.
        R.string.srv_editor_unavailable,
        R.string.srv_config_unreadable,
        R.string.editor_url_invalid,
        R.string.editor_failed,
        R.string.asset_name_duplicate,
        R.string.asset_copy_failed,
        R.string.asset_download_failed,
        R.string.srv_delete_selected,
        R.string.srv_chain_no_servers,
        R.string.srv_chain_too_few,
        // Два оставшихся отказа при сохранении цепочки. Обе строки были с подстановкой и не
        // показывались вовсе, так что «Сохранить» с пустым шагом не отвечало ничем.
        R.string.srv_chain_step_empty,
        R.string.srv_chain_invalid_members,
        R.string.template_locked_toast,
        R.string.toast_action_not_allowed,
        // Routing sets and deep links: an import that could not be read, an export with nothing in
        // it, a scheme that would not parse. Each one answers a control the user pressed and each
        // one leaves the screen exactly as it was, so silence here is a dead button.
        R.string.routing_import_failed,
        R.string.routing_export_empty,
        R.string.scheme_import_failed,
        R.string.log_share_failed,
        R.string.settings_always_on_hint,
        R.string.settings_always_on_unavailable,
        R.string.settings_sub_auto_update_empty,
        // Sending a подписка to the TV.
        R.string.tv_send_no_subs,
        R.string.tv_send_pick_title,
        R.string.tv_send_scanning_invalid,
    )

    /**
     * RULE 5 ON ITS OWN, for a surface that is not on this channel and is asked none of the other
     * questions.
     *
     * A full-screen flow owns the window and states its own progress; anything that slides out from
     * under it reports on an errand nobody started. Two surfaces need exactly this question and
     * nothing else — the in-flight bar ([allowsProgress]) and Главная's transient status bar
     * (`HomeFragment.showStatus`, which the post-sign-in import raises «Подписка привязана» on,
     * mid-flow, over the overlay that is already saying so in four steps).
     *
     * It is the one predicate and not a second copy of the flag, so a screen cannot end up asking a
     * different question from the one the policy is answering.
     */
    fun allowsNow(): Boolean = !flowInProgress

    /** Rule 1 + rule 3 + rule 5, in one question. */
    fun allows(kind: Kind, @StringRes id: Int): Boolean =
        allowsNow() && kind != Kind.SUCCESS && id != 0 && ALLOWED.contains(id)

    /**
     * Rule 5 alone, for the IN-FLIGHT signal, which is not on the policy channel and is not asked
     * any of the other questions.
     *
     * «Обновляем данные…» is a state and normally must show itself (G2). Under a flow overlay it
     * cannot: the overlay is the in-flight signal — a turning arc, a four-step caption and a
     * progress bar — and a second one underneath it reports the same work twice from behind an
     * opaque surface.
     */
    fun allowsProgress(): Boolean = allowsNow()

    /**
     * A node summary in upstream's debug format — `[VLESS] Finland(***12:443)`, `[Custom] [Xray]`.
     * Not a sentence: an internal caption, and it must not appear anywhere a person can read it.
     * Recognised by SHAPE, so it does not depend on the language of the parts around it. Ported
     * from `NoticePolicy.IsNodeSummary`.
     */
    fun isNodeSummary(text: CharSequence?): Boolean {
        val s = text?.trim() ?: return false
        if (s.isEmpty() || s[0] != '[') return false
        val close = s.indexOf(']')
        return close > 1 && s[1].isLetter()
    }

    /**
     * A tally rather than a sentence: «Обновлено серверов: 13 (успешно: 1, ошибки: 0, пропущено:
     * 1)», «Импортировано серверов: 4». The user did not ask how many records moved; the list
     * repainting says everything a count says, and says it about the thing he is looking at.
     *
     * Recognised as: two or more separate runs of digits, with a colon or a bracket between them.
     * A single figure inside an otherwise human sentence is not a counter and survives.
     */
    fun isCounterReport(text: CharSequence?): Boolean {
        val s = text ?: return false
        var groups = 0
        var separator = false
        var index = 0
        while (index < s.length) {
            val c = s[index]
            when {
                c.isDigit() -> {
                    groups++
                    while (index < s.length && s[index].isDigit()) index++
                    continue
                }

                groups > 0 && (c == ':' || c == '(' || c == ',' || c == ';') -> separator = true
            }
            index++
        }
        return groups >= 2 && separator
    }

    /**
     * Text produced by a machine for a machine: an exception, a stack frame, a file path, a JSON
     * body, a bare token with no words in it. `CoreServiceManager` toasts `e.message` twice, and
     * that is the shape it produces.
     */
    fun isMachineText(text: CharSequence?): Boolean {
        val s = text?.trim() ?: return true
        if (s.isEmpty()) return true
        if (s[0] == '{' || s[0] == '[') return true
        if (s.contains("Exception") || s.contains("java.") || s.contains("kotlin.")) return true
        // No space anywhere means it is not a sentence in any language this app ships.
        return !s.any { it.isWhitespace() }
    }

    /** Rule 4: the last gate the text itself has to pass, whatever id carried it. */
    fun allowsText(text: CharSequence?): Boolean =
        !isNodeSummary(text) && !isCounterReport(text) && !isMachineText(text)
}

/**
 * THE ONE FEEDBACK SURFACE. A themed Snackbar at the bottom of whatever screen is in front,
 * anchored above the navigation bar when the shell is showing one, so a message never covers the
 * thing it is about and never appears in the middle of the screen the way a Toast does.
 *
 * `@style/Widget.Departament.Snackbar` already dresses it: the product's radius, the product's
 * type, the inverse surface. There is no green variant and no red variant — an outcome is a
 * sentence, and the sentence carries its own weather.
 */
object Notice {

    /** 3s, the no-action duration (22-components.md 14). Long enough to read one sentence. */
    private const val DURATION_MS = 3000

    private var progressBar: Snackbar? = null

    /**
     * Says one sentence, if [NoticePolicy] lets it. Everything else in the app funnels here.
     *
     * @param id the string that will be shown — a resource id, never a built string, because the
     *   id is the only thing the policy can recognise (rule 3).
     */
    fun say(context: Context, @StringRes id: Int, kind: NoticePolicy.Kind) {
        if (!NoticePolicy.allows(kind, id)) return
        val activity = context.activity() ?: return
        val text = runCatching { activity.getString(id) }.getOrNull() ?: return
        if (!NoticePolicy.allowsText(text)) return
        show(activity, text, DURATION_MS)
    }

    /**
     * The IN-FLIGHT SIGNAL, and it is deliberately not policy-gated: an action the user started
     * and that has not finished must show itself, at the bottom, until it does. Indefinite, and
     * cleared by [clearProgress] — never by a timer, because a spinner that gives up while the
     * work continues is a lie.
     */
    fun progress(context: Context, text: CharSequence) {
        // …with the ONE exception rule 5 names: a full-screen flow is already showing this work.
        if (!NoticePolicy.allowsProgress()) return
        val activity = context.activity() ?: return
        val current = progressBar
        if (current != null && current.isShown) return
        progressBar = show(activity, text, Snackbar.LENGTH_INDEFINITE)
    }

    /** Takes the in-flight signal down. Safe to call when nothing is up. */
    fun clearProgress() {
        progressBar?.dismiss()
        progressBar = null
    }

    private fun show(activity: Activity, text: CharSequence, duration: Int): Snackbar? {
        val root = activity.findViewById<View>(android.R.id.content) ?: return null
        val bar = Snackbar.make(root, text, Snackbar.LENGTH_LONG)
        bar.duration = duration
        // Above the navigation, which is where he asked for it. Anchoring to a hidden view would
        // park the bar off the bottom of the screen, so the bar is only anchored when the shell is
        // actually showing one.
        val nav = activity.findViewById<View>(R.id.bottom_nav)
        if (nav != null && nav.isShown) bar.setAnchorView(nav)
        bar.show()
        return bar
    }

    private fun Context.activity(): Activity? {
        var context: Context? = this
        while (context is ContextWrapper) {
            if (context is Activity) return context
            context = context.baseContext
        }
        return null
    }
}
