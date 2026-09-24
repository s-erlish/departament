package com.v2ray.ang.viewmodel

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.R
import com.v2ray.ang.auth.ApiError
import com.v2ray.ang.auth.CODE_INVALID_PASSWORD
import com.v2ray.ang.auth.CODE_PASSWORD_REQUIRED
import com.v2ray.ang.auth.AuthManager
import com.v2ray.ang.auth.AuthManager.LoginState
import com.v2ray.ang.auth.dto.LoginResult
import com.v2ray.ang.auth.dto.UserProfileDto
import com.v2ray.ang.auth.dto.canSetPassword
import com.v2ray.ang.auth.serverCode
import com.v2ray.ang.auth.serverMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The auth state machine (14-auth.md §4), owned here so every UI decision on the sign-in surfaces
 * is a pure function of [state] + [error] + the form buffers.
 *
 * The screen this replaces had no machine at all: the Telegram awaiting block and the 2FA block
 * were independent `visibility` toggles on one scroll and could be on screen at the same time,
 * saying two contradictory things. Here exactly one [AuthUiState] is live, starting any method
 * cancels the one before it ([loginJob]), and there is no state a surface cannot draw.
 *
 * Three invariants from 4.3 are enforced in this file rather than asked of the caller:
 *
 * 1. **At most one method is in flight.** Every entry point cancels [loginJob] first, including the
 *    Telegram poll.
 * 2. **The deep link opens once per token.** [openedDeepLink] lives in [SavedStateHandle], so a
 *    rotation re-renders the awaiting stack without re-launching Telegram.
 * 3. **A failure is rendered, never re-raised.** [error] is a separate, idempotent value rather than
 *    a terminal state: re-collecting it after a rotation redraws the same line (which is correct —
 *    the message must survive turning the phone) while nothing re-fires. It is cleared by starting
 *    a new attempt, by fixing the field it belongs to, or by [consumeError].
 *
 * The `ApiError -> string` mapping (13.1) lives here too, so no Activity has to know what a 410 is,
 * and so both surfaces phrase the same failure identically.
 */
class AuthViewModel(private val saved: SavedStateHandle) : ViewModel() {

    private val authManager = AuthManager()

    /** Every state the sign-in surfaces can be in. Exactly one is live at a time. */
    sealed interface AuthUiState {
        /** Nothing in flight. The gate shows its idle stack; the form is editable. */
        data object Idle : AuthUiState

        /** Creating the Telegram login token. The CTA is loading; there is no deep link yet. */
        data object TelegramStarting : AuthUiState

        /** The deep link exists and the backend is polled every 2s for ~3 min. */
        data class TelegramAwaiting(val deepLink: String) : AuthUiState

        /** An email/password or 2FA request is in flight. The submit CTA is loading. */
        data object Submitting : AuthUiState

        /** The backend accepted the password and wants the TOTP code. */
        data class TwoFactor(val tempToken: String) : AuthUiState

        /**
         * A letter carrying a link is out and the backend is being polled until it is opened.
         * [resending] is true only while a second letter is being requested, so «Отправить снова»
         * can report itself without the screen leaving the waiting state it is reporting from.
         *
         * Two errands reach this state and it is deliberately ONE state: a registration whose
         * address the panel wants proved, and a signed-in account attaching an address. The wait,
         * the ring, the resend and the way out are identical; which errand is being waited on is
         * the screen's own knowledge (it was opened for one of them) and, for the resend, the
         * ViewModel's [verifyErrand].
         */
        data class EmailVerification(
            val email: String,
            val resending: Boolean = false,
        ) : AuthUiState

        /**
         * **«Письмо отправлено».** A password-reset link is out and NOTHING is being watched for.
         *
         * A separate state from [EmailVerification] because the difference is the whole of it: that
         * one means «письмо ушло, и мы следим, когда по ссылке пройдут», and the ring on screen is
         * that watching. A new password moves neither the login nor the profile, so there is no
         * question to keep asking; the errand ended when the panel answered 200. Folding the two
         * into one state would leave a ring turning over a wait that does not exist, which is the
         * grammar of waiting borrowed for a screen that is not waiting.
         *
         * [resending] is «Отправить снова» working, exactly as it is on [EmailVerification]: the
         * screen has to report the second request without leaving the screen that reports it.
         *
         * **[email] is what the letter was addressed to, and not a statement that anyone got it.**
         * The panel answers the same 200 for an unknown address, so the copy over this state is
         * conditional by contract.
         */
        data class PasswordResetSent(
            val email: String,
            val resending: Boolean = false,
        ) : AuthUiState

        /**
         * The address is attached (or replaced) and the account can still be given a password, so
         * the errand is not over: «Придумайте пароль» is on screen over the same two password
         * fields registration uses. [busy] is the request being in flight, exactly as [resending]
         * is on [EmailVerification] — the step must report itself without leaving itself.
         *
         * **A STEP, NOT A GATE.** The e-mail errand has already succeeded by the time this appears
         * (the caller's RESULT_OK is set on entry), so «Пропустить» and system Back both simply
         * close. It exists because an address without a password is an identifier and not a way in,
         * which is the whole reason the address was attached.
         */
        data class SetPassword(val busy: Boolean = false) : AuthUiState

        /** Session persisted. The UI plays the beat and hands back to the caller. */
        data class Success(val profile: UserProfileDto) : AuthUiState
    }

    /**
     * Which surface a failure belongs to, so the right line (or the right field) carries it.
     *
     * [PASSWORD] is the one that names a FIELD rather than a screen, and it exists because the
     * panel's change-email guard answers two failures whose fix is inside the password box and
     * nowhere else: `PASSWORD_REQUIRED` («введите его») and `INVALID_PASSWORD` («он неверен»).
     * Putting either on the screen-level line would leave the box that has to change unmarked.
     */
    enum class Surface { GATE, MAIL, TWO_FACTOR, PASSWORD }

    /**
     * A failure, already turned into copy. [credentialFlash] marks the one case where the two
     * credential fields also flash their border (12.11) — colour only, never a shake.
     */
    data class AuthError(
        @param:StringRes @get:StringRes val message: Int,
        val surface: Surface,
        val credentialFlash: Boolean = false,
        /**
         * The panel's OWN sentence, when it sent one and the surface is allowed to quote it — the
         * registration path, where a bare 400 covers both «Этот email уже зарегистрирован» and
         * «Некорректные данные» and only the panel knows which. Null everywhere else, and [message]
         * is then the whole answer.
         */
        val text: String? = null,
        /**
         * True for the one refusal that is also a form change: `PASSWORD_REQUIRED` on «Сменить
         * почту». The screen drew no password box because the cached profile said the account had
         * none; the panel says otherwise, and its answer has to be readable — so the box appears
         * with the reason under it rather than the reason appearing under nothing.
         */
        val revealPasswordField: Boolean = false,
    )

    private val _state = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    private val _error = MutableStateFlow<AuthError?>(null)
    val error: StateFlow<AuthError?> = _error.asStateFlow()

    /**
     * True while a 429 cool-down is running. The CTA is disabled for the whole 60s and no countdown
     * is printed: a ticking number would be a second live number on a screen that has none (5.6).
     */
    private val _rateLimited = MutableStateFlow(false)
    val rateLimited: StateFlow<Boolean> = _rateLimited.asStateFlow()

    private var loginJob: Job? = null
    private var cooldownJob: Job? = null

    /**
     * The credentials a registration was started with, held HERE and not inside
     * [AuthUiState.EmailVerification].
     *
     * The address is what «Отправить снова» re-sends to and what the poll signs in with, and both
     * outlive the state that named it: a failed resend puts the machine back into the waiting
     * state, and reading the address out of the state it is being rebuilt from is circular — one
     * error and the screen would be left with a resend button that has nothing to send and a poll
     * with nobody to log in as. A ViewModel field survives every state change and every rotation,
     * which is exactly the lifetime this pair needs.
     *
     * The password is deliberately NOT in [SavedStateHandle]: that Bundle can be written to disk by
     * the platform, and a password does not belong there. Process death therefore ends the wait,
     * which is correct — nothing else on this screen survives it either.
     */
    private var pendingEmail: String? = null
    private var pendingPassword: String? = null

    /** The three errands that end on [AuthUiState.EmailVerification]. @see verifyErrand */
    private enum class VerifyErrand { REGISTER, LINK_EMAIL, CHANGE_EMAIL }

    /**
     * WHICH errand the wait belongs to, held beside the address for the same reason the address
     * itself is held here: «Отправить снова» has to re-send the right letter, and the state it is
     * pressed from is the state being rebuilt. Registration re-posts the registration; a link
     * request re-posts the link request, with no password anywhere near it.
     */
    private var verifyErrand = VerifyErrand.REGISTER

    /**
     * The current password a change-email request was made with, held for «Отправить снова» beside
     * the address and for the same reason: the resend re-posts the whole request, and the panel
     * will refuse it again without the password. Cleared with everything else on the way out, and
     * deliberately never written to [SavedStateHandle] — that Bundle can reach disk.
     */
    private var pendingCurrentPassword: String? = null

    /**
     * The deep link this process has already handed to Telegram. Survives configuration change in
     * [SavedStateHandle] precisely so a rotation does not re-open Telegram behind the user.
     */
    var openedDeepLink: String?
        get() = saved[KEY_DEEP_LINK]
        set(value) {
            saved[KEY_DEEP_LINK] = value
        }

    fun isLoggedIn(): Boolean = authManager.isLoggedIn()

    /**
     * Starts (or restarts) the Telegram deep-link flow: mint a token, hand the link to the UI, then
     * poll until the user confirms. Restarting mints a fresh token and abandons the old one, which
     * is why there is no separate «Начать заново» control anywhere on the gate.
     */
    fun startTelegramLogin() {
        loginJob?.cancel()
        _error.value = null
        _state.value = AuthUiState.TelegramStarting
        loginJob = viewModelScope.launch {
            authManager.beginTelegramLogin().collect { managerState ->
                when (managerState) {
                    is LoginState.AwaitingTelegram ->
                        _state.value = AuthUiState.TelegramAwaiting(managerState.deepLink)

                    // AwaitingTelegram can be conflated away before the UI sees it, so Polling
                    // carries the link too and lands on the same UI state.
                    is LoginState.Polling ->
                        _state.value = AuthUiState.TelegramAwaiting(managerState.deepLink)

                    is LoginState.Success -> _state.value = AuthUiState.Success(managerState.profile)

                    is LoginState.Error -> fail(managerState.error, Surface.GATE)

                    // The manager never emits these on this flow: Idle would be a no-op,
                    // SiteLoading belongs to the password path and the verification wait belongs
                    // to registration.
                    is LoginState.Idle, is LoginState.SiteLoading,
                    is LoginState.AwaitingEmailVerification -> Unit
                }
            }
        }
    }

    /**
     * Cancels the poll and returns to the gate's idle stack. This is what system Back does while
     * awaiting: it never leaves the screen from that state (D-14.E).
     */
    fun cancelTelegramLogin() {
        loginJob?.cancel()
        loginJob = null
        openedDeepLink = null
        _error.value = null
        _state.value = AuthUiState.Idle
    }

    /**
     * Registration, in the app and not in a browser. Two answers are both successes: a panel with
     * e-mail verification off signs the user straight in, one with it on sends a letter and the
     * machine moves to [AuthUiState.EmailVerification] while [AuthManager] polls the login.
     *
     * Also the «Отправить снова» action — see [resendVerification]. When it is called from the
     * waiting screen the screen STAYS on it (`resending = true` spins the ring in place) instead of
     * dropping back to the form: the first letter may well arrive while the second is being asked
     * for, and yanking the user off the screen that explains what to do with it would be a step
     * backwards for a tap that meant "keep going".
     */
    fun register(email: String, password: String) {
        loginJob?.cancel()
        _error.value = null
        pendingEmail = email
        pendingPassword = password
        verifyErrand = VerifyErrand.REGISTER
        val resending = _state.value is AuthUiState.EmailVerification
        _state.value = if (resending) {
            AuthUiState.EmailVerification(email, resending = true)
        } else {
            AuthUiState.Submitting
        }
        loginJob = viewModelScope.launch {
            authManager.beginRegister(email, password).collect { managerState ->
                when (managerState) {
                    is LoginState.AwaitingEmailVerification ->
                        _state.value = AuthUiState.EmailVerification(managerState.email)

                    is LoginState.Success -> _state.value = AuthUiState.Success(managerState.profile)

                    is LoginState.Error -> fail(managerState.error, Surface.MAIL, quoteBackend = true)

                    // Not emitted on this flow: the Telegram and password states belong to the
                    // other two entry points.
                    is LoginState.Idle, is LoginState.SiteLoading,
                    is LoginState.AwaitingTelegram, is LoginState.Polling -> Unit
                }
            }
        }
    }

    /**
     * **Привязка почты к аккаунту, в который уже вошли.** The errand behind the «Почта» row in
     * «Способы входа»: an account created from Telegram has no address, and this is how it gets
     * one. No password is taken and none is sent — the panel is being asked for a letter, and the
     * Bearer token on the request already says whose account it is about.
     *
     * The shape is the registration's, and on purpose: one request, then the same wait. It is also
     * the «Отправить снова» action for that wait — when it is called from the waiting screen the
     * screen STAYS on it (`resending = true` keeps the ring turning) rather than dropping back to
     * the form, for the same reason [register] does.
     */
    fun requestEmailLink(email: String) {
        loginJob?.cancel()
        _error.value = null
        pendingEmail = email
        // Nothing to remember and nothing to clear later: this errand never takes a password.
        pendingPassword = null
        verifyErrand = VerifyErrand.LINK_EMAIL
        val resending = _state.value is AuthUiState.EmailVerification
        _state.value = if (resending) {
            AuthUiState.EmailVerification(email, resending = true)
        } else {
            AuthUiState.Submitting
        }
        loginJob = viewModelScope.launch {
            authManager.beginLinkEmail(email).collect { managerState ->
                when (managerState) {
                    is LoginState.AwaitingEmailVerification ->
                        _state.value = AuthUiState.EmailVerification(managerState.email)

                    is LoginState.Success -> settleEmailErrand(managerState.profile)

                    is LoginState.Error ->
                        fail(managerState.error, Surface.MAIL, quoteBackend = true, linkEmail = true)

                    // Not emitted on this flow: the Telegram and password states belong to the
                    // other entry points.
                    is LoginState.Idle, is LoginState.SiteLoading,
                    is LoginState.AwaitingTelegram, is LoginState.Polling -> Unit
                }
            }
        }
    }

    /**
     * **Смена почты у аккаунта, у которого адрес уже есть.** Same request/wait/poll shape as
     * [requestEmailLink]; what differs is the guard in front of it ([currentPassword], required by
     * the panel of any account that has one) and the sharper question the wait asks afterwards —
     * the address has to become [newEmail], not merely exist.
     *
     * Also the «Отправить снова» action for that wait, through the same [resendVerification].
     */
    fun requestEmailChange(newEmail: String, currentPassword: String?) {
        loginJob?.cancel()
        _error.value = null
        pendingEmail = newEmail
        pendingPassword = null
        pendingCurrentPassword = currentPassword
        verifyErrand = VerifyErrand.CHANGE_EMAIL
        val resending = _state.value is AuthUiState.EmailVerification
        _state.value = if (resending) {
            AuthUiState.EmailVerification(newEmail, resending = true)
        } else {
            AuthUiState.Submitting
        }
        loginJob = viewModelScope.launch {
            authManager.beginChangeEmail(newEmail, currentPassword).collect { managerState ->
                when (managerState) {
                    is LoginState.AwaitingEmailVerification ->
                        _state.value = AuthUiState.EmailVerification(managerState.email)

                    is LoginState.Success -> settleEmailErrand(managerState.profile)

                    is LoginState.Error -> failEmailChange(managerState.error)

                    is LoginState.Idle, is LoginState.SiteLoading,
                    is LoginState.AwaitingTelegram, is LoginState.Polling -> Unit
                }
            }
        }
    }

    /**
     * Where an e-mail errand actually ends. The address is on the account either way; the question
     * left is whether this account can still sign in with it, and an account with no password
     * cannot. The panel's own gate is mirrored so the step is offered only where it would be
     * accepted — see `UserProfileDto.canSetPassword`.
     */
    private fun settleEmailErrand(profile: UserProfileDto) {
        _state.value = if (profile.canSetPassword()) {
            AuthUiState.SetPassword()
        } else {
            AuthUiState.Success(profile)
        }
    }

    /**
     * Opens on «Придумайте пароль» with no letter in front of it: the account already HAS an
     * address and simply has no password, so e-mail sign-in does not work and the «Почта» row says
     * so. The errand is the second half of an attachment somebody skipped, reached on its own.
     *
     * A plain state assignment rather than a request: there is nothing to ask the panel yet. What
     * follows is [setPassword], the same call the step makes on either of the other two routes.
     */
    fun beginSetPassword() {
        loginJob?.cancel()
        _error.value = null
        _state.value = AuthUiState.SetPassword()
    }

    /**
     * «Придумайте пароль», and the reason the address was attached at all. Minimum six characters
     * — the panel's floor for THIS endpoint, held by the form so a 400 never has to say it.
     *
     * A failure returns to the step rather than to [AuthUiState.Idle]: the fields are still filled
     * in, «Пропустить» is still the way out, and the e-mail errand behind it is still a success.
     */
    fun setPassword(newPassword: String) {
        loginJob?.cancel()
        _error.value = null
        _state.value = AuthUiState.SetPassword(busy = true)
        loginJob = viewModelScope.launch {
            try {
                authManager.setPassword(newPassword)
                _state.value = AuthUiState.Success(
                    authManager.currentProfile() ?: UserProfileDto()
                )
            } catch (e: ApiError) {
                fail(e, Surface.MAIL, quoteBackend = true, linkEmail = true)
            }
        }
    }

    /** The profile this process is signed in with, or null. Read-only. */
    fun currentProfile(): UserProfileDto? = authManager.currentProfile()

    /** «Отправить снова» on the waiting screen: the same letter, to the same address. */
    fun resendVerification() {
        val email = pendingEmail ?: return
        when (verifyErrand) {
            VerifyErrand.REGISTER -> pendingPassword?.let { register(email, it) }
            VerifyErrand.LINK_EMAIL -> requestEmailLink(email)
            // The panel guards every change request, including the second one, so the password
            // that got the first letter sent has to travel with this one too.
            VerifyErrand.CHANGE_EMAIL -> requestEmailChange(email, pendingCurrentPassword)
        }
    }

    /**
     * **«Восстановить пароль».** One request, then a static «Письмо отправлено» — there is no wait
     * after it and no ring, because nothing on the account changes until the link is opened on the
     * site. See [AuthManager.requestPasswordReset].
     *
     * Also the «Отправить снова» action for that screen, through [resendPasswordReset], and it
     * behaves like every other resend here: the screen STAYS put and the busy state goes on the
     * button, rather than dropping the user back onto the form they have already filled in.
     */
    fun requestPasswordReset(email: String) {
        loginJob?.cancel()
        _error.value = null
        pendingEmail = email
        // No credential is taken by this errand and none may be left lying around from another one.
        pendingPassword = null
        pendingCurrentPassword = null
        val resending = _state.value is AuthUiState.PasswordResetSent
        _state.value = if (resending) {
            AuthUiState.PasswordResetSent(email, resending = true)
        } else {
            AuthUiState.Submitting
        }
        loginJob = viewModelScope.launch {
            try {
                authManager.requestPasswordReset(email)
                _state.value = AuthUiState.PasswordResetSent(email)
            } catch (e: ApiError) {
                fail(e, Surface.MAIL, quoteBackend = true, passwordReset = true)
            }
        }
    }

    /** «Отправить снова» on «Письмо отправлено»: the same letter, to the same address. */
    fun resendPasswordReset() {
        val email = pendingEmail ?: return
        requestPasswordReset(email)
    }

    /**
     * «Вернуться ко входу»: abandons the wait and the poll with it, and forgets the credentials it
     * was holding. The account still exists and the letter is still valid — nothing here undoes the
     * registration, it only stops watching for it.
     */
    fun leaveVerification() {
        loginJob?.cancel()
        loginJob = null
        pendingEmail = null
        pendingPassword = null
        pendingCurrentPassword = null
        verifyErrand = VerifyErrand.REGISTER
        _error.value = null
        _state.value = AuthUiState.Idle
    }

    /** Email + password. A `Requires2FA` answer moves to [AuthUiState.TwoFactor], not to an error. */
    fun loginSite(email: String, password: String) {
        loginJob?.cancel()
        _error.value = null
        _state.value = AuthUiState.Submitting
        loginJob = viewModelScope.launch {
            try {
                when (val result = authManager.loginSite(email, password)) {
                    is LoginResult.Success -> _state.value = AuthUiState.Success(result.client)
                    is LoginResult.Requires2FA -> {
                        saved[KEY_TEMP_TOKEN] = result.tempToken
                        _state.value = AuthUiState.TwoFactor(result.tempToken)
                    }
                }
            } catch (e: ApiError) {
                fail(e, Surface.MAIL)
            }
        }
    }

    /**
     * Completes a 2FA login with the token held from [loginSite]. The token lives in
     * [SavedStateHandle] so rotating the phone mid-step does not throw the user back to the form.
     */
    fun submit2fa(code: String) {
        val tempToken: String = saved[KEY_TEMP_TOKEN] ?: return
        loginJob?.cancel()
        _error.value = null
        _state.value = AuthUiState.Submitting
        loginJob = viewModelScope.launch {
            try {
                val profile = authManager.submit2fa(tempToken, code)
                saved.remove<String>(KEY_TEMP_TOKEN)
                _state.value = AuthUiState.Success(profile)
            } catch (e: ApiError) {
                fail(e, Surface.TWO_FACTOR)
            }
        }
    }

    /** «Отмена» and system Back on the 2FA step: drop the token and go back to the form. */
    fun cancelTwoFactor() {
        loginJob?.cancel()
        loginJob = null
        saved.remove<String>(KEY_TEMP_TOKEN)
        _error.value = null
        _state.value = AuthUiState.Idle
    }

    /**
     * Abandons whatever is in flight and returns the machine to [AuthUiState.Idle] **without**
     * raising a failure: the user navigated away, and a navigation is not an error. Used when the
     * form is left while a submit is running — the answer to a request nobody is waiting for must
     * not arrive on a surface that has moved on.
     */
    fun cancelPending() {
        loginJob?.cancel()
        loginJob = null
        openedDeepLink = null
        saved.remove<String>(KEY_TEMP_TOKEN)
        pendingEmail = null
        pendingPassword = null
        pendingCurrentPassword = null
        verifyErrand = VerifyErrand.REGISTER
        _error.value = null
        _state.value = AuthUiState.Idle
    }

    /** Raises a failure the UI produced itself, e.g. no Telegram and no browser to fall back to. */
    fun failLocally(@StringRes message: Int, surface: Surface) {
        loginJob?.cancel()
        loginJob = null
        openedDeepLink = null
        _state.value = AuthUiState.Idle
        _error.value = AuthError(message, surface)
    }

    /** Clears the error line: the user has started a new attempt or fixed the field it named. */
    fun consumeError() {
        _error.value = null
    }

    /**
     * Turns a failure into copy and puts the machine back where the surface can act again. The 2FA
     * step keeps its own state, so the user lands back on the six cells rather than on the password
     * field they already got right.
     */
    private fun fail(
        cause: ApiError,
        surface: Surface,
        quoteBackend: Boolean = false,
        linkEmail: Boolean = false,
        passwordReset: Boolean = false,
        @StringRes fallback: Int? = null,
    ) {
        val awaitingTelegram = _state.value is AuthUiState.TelegramAwaiting
        val verifying = _state.value is AuthUiState.EmailVerification
        val resetSent = _state.value is AuthUiState.PasswordResetSent
        val onPasswordStep = _state.value is AuthUiState.SetPassword
        _state.value = when {
            surface == Surface.TWO_FACTOR -> {
                val token: String? = saved[KEY_TEMP_TOKEN]
                if (token != null) AuthUiState.TwoFactor(token) else AuthUiState.Idle
            }

            // A failed «Отправить снова» is not a failed WAIT: the letter that is already out is
            // still valid and the poll still has something to find, so the screen goes back to
            // waiting with the reason printed on it. The address comes from the field that outlives
            // the state, never from the state being replaced.
            verifying -> pendingEmail?.let { AuthUiState.EmailVerification(it) } ?: AuthUiState.Idle

            // Same reasoning for the reset's «Отправить снова»: the first letter, if there was one,
            // is still on its way, so the screen that says so stays up with the reason printed on
            // it rather than collapsing back to a form the user has already filled in.
            resetSent -> pendingEmail?.let { AuthUiState.PasswordResetSent(it) } ?: AuthUiState.Idle

            // A refused password is not a failed e-mail errand — that one has already succeeded and
            // the caller's result is already set. The step stays up with the reason on it, its
            // fields still filled and «Пропустить» still the way out; dropping to Idle would put a
            // sign-in form under somebody who is signed in.
            onPasswordStep -> AuthUiState.SetPassword()

            else -> AuthUiState.Idle
        }
        if (cause is ApiError.RateLimited) startCooldown()
        _error.value = AuthError(
            // `fallback` is this app's own words for a refusal whose MEANING the panel named in a
            // code: if the sentence beside it ever goes missing, «Введите текущий пароль» is still
            // a better answer than whatever a status code maps to.
            message = fallback ?: messageFor(cause, surface, awaitingTelegram, linkEmail, passwordReset),
            surface = surface,
            // 12.11: an Unauthorized on the password step flashes both credential borders. On the
            // 2FA step the cells carry that signal instead, so the flash is not doubled.
            credentialFlash = surface == Surface.MAIL && cause is ApiError.Unauthorized && !quoteBackend,
            // The one failure that CHANGES the form rather than only annotating it: the account has
            // a password the form did not know about, so the box has to appear before its own error
            // can point at anything.
            revealPasswordField = cause.serverCode() == CODE_PASSWORD_REQUIRED,
            text = if (quoteBackend) cause.serverMessage() else null,
        )
    }

    /**
     * The change-email refusal, routed by the panel's own `code` rather than by its status.
     *
     * Two of the answers are about the password BOX and nothing else: 400 `PASSWORD_REQUIRED` means
     * the account has a password this request did not carry (the profile said otherwise, so the
     * form has to grow the field), and 401 `INVALID_PASSWORD` means it carried the wrong one. A
     * bare 401 on a signed-in errand otherwise reads as «сессия истекла», which would send the user
     * to sign in again over a password they simply mistyped — so the code is consulted first.
     *
     * Everything else is the screen's: «Эта почта уже используется другим аккаунтом», «Аккаунт
     * заблокирован», «Отправка писем не настроена». All of them quoted from the panel.
     */
    private fun failEmailChange(cause: ApiError) {
        when (cause.serverCode()) {
            CODE_PASSWORD_REQUIRED ->
                fail(cause, Surface.PASSWORD, quoteBackend = true, fallback = R.string.auth_password_required)

            CODE_INVALID_PASSWORD ->
                fail(cause, Surface.PASSWORD, quoteBackend = true, fallback = R.string.auth_password_wrong)

            else -> fail(cause, Surface.MAIL, quoteBackend = true, linkEmail = true)
        }
    }

    private fun startCooldown() {
        cooldownJob?.cancel()
        _rateLimited.value = true
        cooldownJob = viewModelScope.launch {
            delay(RATE_LIMIT_COOLDOWN_MS)
            _rateLimited.value = false
        }
    }

    companion object {
        /**
         * 13.1, in one place. Every message names the cause AND the fix; none carries an HTTP code,
         * a URL or a response body — the raw reason goes to the log, not to the customer.
         *
         * Public and on the companion because the Telegram flow that runs from the START SCREEN
         * (README §3, `ui/component/GateView`) reports the same failures on a surface this
         * ViewModel does not own: its overlay lifts and the reason lands in the gate's caption. Two
         * copies of this `when` would let the same 410 read two different ways in one product.
         */
        @StringRes
        fun messageFor(
            cause: ApiError,
            surface: Surface = Surface.GATE,
            awaitingTelegram: Boolean = false,
            linkEmail: Boolean = false,
            passwordReset: Boolean = false,
        ): Int = when {
            // A wrong TOTP code is not "wrong password": it names the authenticator app, because
            // that is where the user has to look.
            surface == Surface.TWO_FACTOR && cause is ApiError.Unauthorized -> R.string.auth_2fa_wrong
            // Attaching an address is done BY a session rather than to obtain one, so a 401 here is
            // the seven-day token dying mid-errand. «Неверная почта или пароль» would name a
            // password nobody typed and send the user back to check a field that is not on screen.
            linkEmail && cause is ApiError.Unauthorized -> R.string.auth_err_session_expired
            // «Восстановить пароль» takes NO session and NO password, so neither of the two
            // readings above fits: «неверная почта или пароль» names a field that is not on the
            // form and that the user is here precisely because they do not have, and «сессия
            // истекла» names a session nobody opened. The panel's own sentence still wins over
            // this — see the `quoteBackend` half of [fail] — so this is what is left when it sent
            // none, and it says the one true thing: something went wrong, try again.
            passwordReset && cause is ApiError.Unauthorized -> R.string.auth_err_generic
            cause is ApiError.Unauthorized -> R.string.auth_err_credentials
            cause is ApiError.Gone -> R.string.auth_err_gone
            cause is ApiError.RateLimited -> R.string.auth_err_rate_limited
            cause is ApiError.ServiceUnavailable -> R.string.auth_err_unavailable
            cause is ApiError.Network -> R.string.auth_err_network
            // The manager reports the 180s poll deadline as Timeout as well, so the phrasing is
            // chosen by WHERE we were: waiting on a human reads differently from waiting on a
            // server.
            cause is ApiError.Timeout && awaitingTelegram -> R.string.auth_err_tg_timeout
            cause is ApiError.Timeout -> R.string.auth_err_timeout
            cause is ApiError.NotConfigured -> R.string.auth_err_not_configured
            else -> R.string.auth_err_generic
        }

        /** 5.6: 429 disables the submit for a minute; the countdown is deliberately not printed. */
        private const val RATE_LIMIT_COOLDOWN_MS = 60_000L
        private const val KEY_DEEP_LINK = "auth_opened_deep_link"
        private const val KEY_TEMP_TOKEN = "auth_temp_token"
    }
}
