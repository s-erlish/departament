package com.v2ray.ang.auth

import com.v2ray.ang.auth.dto.AuthResult
import com.v2ray.ang.auth.dto.LoginResult
import com.v2ray.ang.auth.dto.RegisterResult
import com.v2ray.ang.auth.dto.TelegramCheckResult
import com.v2ray.ang.auth.dto.UserProfileDto
import com.v2ray.ang.auth.dto.emailArrived
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * Orchestrates the auth flows only (Telegram deep-link login, e-mail registration, e-mail/password
 * sign-in, TOTP 2FA, and the e-mail errands of a session that already exists: attaching an
 * address, replacing it, and giving the account the password that makes either one a way IN).
 * There is NO refresh/logout here — the JWT is 7-day and non-refreshable; session persistence is
 * delegated to [AccountSession]/[AuthTokenStore], subscription import to [AccountRepository].
 */
class AuthManager(
    private val api: DepartamentApiClient = DepartamentApiClientImpl(),
) {

    /** UI-facing state of a login attempt (Telegram deep-link, site email/password, or 2FA). */
    sealed interface LoginState {
        object Idle : LoginState

        /** Deep link is ready; the UI should open Telegram via ACTION_VIEW with it. */
        data class AwaitingTelegram(val deepLink: String) : LoginState

        /** Polling the backend for confirmation. Carries the deep link so the UI can reopen it. */
        data class Polling(val deepLink: String) : LoginState

        /**
         * A site email/password or 2FA request is in flight. The UI shows an inline busy indicator
         * on the site/2FA button; unlike [Polling] it never opens Telegram or the awaiting card.
         */
        object SiteLoading : LoginState

        /**
         * A letter carrying a LINK (not a code — there is nothing for the user to type back) has
         * been sent to [email], and the flow that emitted this keeps watching for it to be opened.
         *
         * TWO ERRANDS END UP HERE, and the wait is the same wait either way. [beginRegister] gets
         * it when the panel wants a new account's address proved, and polls the LOGIN until it
         * answers 200; [beginLinkEmail] gets it when a signed-in user attaches an address, and
         * polls the PROFILE until it carries one. The question differs because the fact each is
         * waiting for differs; the moment does not, so neither does the screen.
         */
        data class AwaitingEmailVerification(val email: String) : LoginState

        /** Confirmed — session persisted; carries the profile. */
        data class Success(val profile: UserProfileDto) : LoginState

        data class Error(val error: ApiError) : LoginState
    }

    fun isLoggedIn(): Boolean = AuthTokenStore.isLoggedIn()

    /**
     * Telegram login as a cold [Flow]: create a login token, emit [LoginState.AwaitingTelegram]
     * (so the UI opens the deep link), then poll every ~2s (capped at ~3 min) until the user
     * confirms in Telegram. On confirmation the session is persisted before [LoginState.Success].
     */
    fun beginTelegramLogin(): Flow<LoginState> = flow {
        if (!BackendConfig.isConfigured()) {
            emit(LoginState.Error(ApiError.NotConfigured))
            return@flow
        }

        val tokenDto = try {
            api.createTelegramLoginToken()
        } catch (e: ApiError) {
            emit(LoginState.Error(e))
            return@flow
        }
        if (tokenDto.token.isBlank()) {
            emit(LoginState.Error(ApiError.Parse()))
            return@flow
        }

        val deepLink = "https://t.me/${BackendConfig.botUsername}?start=auth_${tokenDto.token}"
        emit(LoginState.AwaitingTelegram(deepLink))
        emit(LoginState.Polling(deepLink))

        val pollIntervalMs = 2_000L
        val timeoutMs = 3 * 60 * 1_000L
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            delay(pollIntervalMs)
            val result = try {
                api.checkTelegramLogin(tokenDto.token)
            } catch (e: ApiError) {
                emit(LoginState.Error(e))
                return@flow
            }
            when (result) {
                is TelegramCheckResult.Confirmed -> {
                    // Persisting can now REFUSE the reply (a blank jwt is not a session), and this
                    // flow is collected straight into `viewModelScope.launch` / `lifecycleScope`
                    // with no catch around it — an exception thrown here would not be a failed
                    // login, it would be a crash on the sign-in screen. It becomes the same
                    // rendered error every other failure on this flow becomes.
                    try {
                        AccountSession.onAuthenticated(result.token, result.client)
                    } catch (e: ApiError) {
                        emit(LoginState.Error(e))
                        return@flow
                    }
                    emit(LoginState.Success(result.client))
                    return@flow
                }
                TelegramCheckResult.Expired -> {
                    emit(LoginState.Error(ApiError.Gone))
                    return@flow
                }
                TelegramCheckResult.NotYet -> Unit // keep polling
            }
        }
        emit(LoginState.Error(ApiError.Timeout))
    }

    /**
     * Registration as a cold [Flow], and it has to be a flow rather than a suspend call for the same
     * reason the Telegram login does: the errand does not end with the request. A panel with e-mail
     * verification switched OFF answers with a session and this is over in one emission; a panel
     * with it switched ON answers with a letter, and the flow then sits on
     * [LoginState.AwaitingEmailVerification] until the user opens the link in it.
     *
     * The letter carries a LINK and not a code, which is why nothing on the waiting screen asks the
     * user to type anything: the app cannot receive the browser's confirmation, so it watches for
     * the SIDE EFFECT of it instead — see [pollUntilVerified].
     */
    fun beginRegister(email: String, password: String): Flow<LoginState> = flow {
        if (!BackendConfig.isConfigured()) {
            emit(LoginState.Error(ApiError.NotConfigured))
            return@flow
        }

        val result = try {
            api.register(email, password)
        } catch (e: ApiError) {
            emit(LoginState.Error(e))
            return@flow
        }

        when (result) {
            is RegisterResult.Success -> {
                // Persisting can refuse the reply (a blank jwt is not a session); that is a failed
                // registration, not a crash on the sign-in screen. Same guard as the Telegram flow.
                try {
                    AccountSession.onAuthenticated(result.token, result.client)
                } catch (e: ApiError) {
                    emit(LoginState.Error(e))
                    return@flow
                }
                // Fired BEFORE the success is reported, and not awaited at all: see
                // [settleRegistration]. Once Success is out the screen plays its beat and closes,
                // and the collector goes with it — an emit is the last place to put anything that
                // still has to happen.
                settleRegistration()
                emit(LoginState.Success(result.client))
            }

            is RegisterResult.RequiresVerification -> {
                emit(LoginState.AwaitingEmailVerification(email))
                pollUntilVerified(email, password)
            }
        }
    }

    /**
     * Watches for the emailed link being opened, by asking the ONE question whose answer changes
     * when it is: log in with the credentials just registered. While the address is unproved the
     * panel refuses with 401; the moment the link is opened the same request answers 200 with a
     * token, and the app signs itself in without the user coming back to type anything.
     *
     * **Every failure here is swallowed on purpose.** A 401 is the expected answer for as long as
     * the letter is unread, and a network blip is not the user's mistake — reporting either would
     * put an error on a screen whose only correct instruction is «откройте ссылку». The deadline is
     * reached in the same silence: the waiting screen stays up with «Отправить снова» on it, which
     * is the real next move, rather than being replaced by an invented failure.
     */
    private suspend fun FlowCollector<LoginState>.pollUntilVerified(
        email: String,
        password: String,
    ) {
        val deadline = System.currentTimeMillis() + VERIFY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(VERIFY_POLL_INTERVAL_MS)
            val result = try {
                api.login(email, password)
            } catch (e: ApiError) {
                continue // Still unverified, or a blip. Neither is news.
            }
            if (result is LoginResult.Success) {
                try {
                    AccountSession.onAuthenticated(result.token, result.client)
                } catch (e: ApiError) {
                    emit(LoginState.Error(e))
                    return
                }
                settleRegistration()
                emit(LoginState.Success(result.client))
                return
            }
            // An account registered a minute ago cannot have TOTP on it, so Requires2FA is not a
            // real answer here; keep waiting rather than stranding the user on a code prompt they
            // have no way to satisfy.
        }
    }

    /**
     * **Регистрация оставляет `onboardingCompleted` ЛОЖНЫМ, хотя пароль у аккаунта уже есть.**
     *
     * Проверено по исходникам панели, и по обоим путям сразу: и `POST /client/auth/register` при
     * выключенной проверке почты, и `POST /client/auth/verify-email` создают клиента с этим флагом
     * в `false`. А `set-password` панель отклоняет ровно при `passwordHash && onboardingCompleted`,
     * и то же условие зеркалит [com.v2ray.ang.auth.dto.canSetPassword] — так что без этой отметки
     * у только что зарегистрировавшегося «можно задать пароль» остаётся истинным НАВСЕГДА. Панель
     * примет повторную установку пароля, «Способы входа» будут звать на шаг, который аккаунту не
     * нужен, и то, что приложение думает об аккаунте, разойдётся с тем, что показывает сайт.
     *
     * **И ЧЕЛОВЕК ЖДАТЬ ЭТОГО НЕ ДОЛЖЕН.** Оба запроса стояли между ответом панели и `Success`,
     * то есть между «регистрация состоялась» и «экран закрылся»: на таймаутах это до тридцати пяти
     * секунд, за которые не происходит ничего, что ему нужно. Сессия поднята
     * [AccountSession.onAuthenticated] строкой выше — регистрация УЖЕ состоялась, — а отметка о
     * первом входе и свежий профиль это служебная доводка. Поэтому здесь ничего не возвращается и
     * ничего не ожидается: `Success` уходит с тем профилем, который приехал, а перечитанный
     * разошлётся сам через [AccountSession.updateProfile] — «Способы входа», Главная и «Устройства»
     * читают эту сессию потоком и обновятся, даже если человек уже ушёл на Главную.
     *
     * **Область корутины не принадлежит экрану**, и это здесь главное: доводка переживает и
     * закрытие экрана, и смерть ViewModel вместе с её `viewModelScope`. Отсюда [settleScope] —
     * один на процесс, с [SupervisorJob], чтобы упавшая доводка не забирала с собой следующую.
     *
     * Порядок внутри обязателен: сначала флаг, потом перечитать профиль, чтобы кэш нёс то, что
     * этот вызов только что поставил.
     *
     * **Неудачи по-прежнему проглатываются, но больше не бесследно.** Аккаунт создан, войти по
     * нему можно, и рассказывать про неудавшийся довесок человеку нечего; а вот аккаунт с
     * незакрытым первым входом всплывёт потом — на «Способах входа», зовущих на ненужный шаг, — и
     * без строки в журнале искать причину будет негде.
     *
     * Вызывается ТОЛЬКО там, где регистрация закончилась сессией, и только после
     * [AccountSession.onAuthenticated]: без сохранённого токена оба запроса ушли бы без
     * `Authorization` и вернулись бы 401.
     */
    private fun settleRegistration() {
        settleScope.launch {
            runCatching { api.completeOnboarding() }.onFailure {
                LogUtil.w(
                    AppConfig.TAG,
                    "Registration: onboarding was not marked complete, the account stays eligible " +
                        "for a password step it does not need",
                    it,
                )
            }
            val refreshed = runCatching { api.getMe() }.getOrElse {
                LogUtil.w(AppConfig.TAG, "Registration: the profile was not re-read", it)
                return@launch
            }
            runCatching { AccountSession.updateProfile(refreshed) }
        }
    }

    /**
     * **«Восстановить пароль»: одна просьба, и на этом наша часть кончается.**
     *
     * A suspend call and not a [Flow], deliberately, because nothing follows the request. The other
     * two letters this app sends start a WAIT — the app watches the login or the profile until the
     * link is opened — and there is nothing of the sort to watch here: a new password changes
     * neither, so a poll would ask a question whose answer never moves. Wearing the flow's shape
     * would put a turning ring over an errand that finished the moment the panel answered.
     *
     * **The 200 is deliberately uninformative.** The panel answers the same way for an address it
     * knows and one it does not, so that nobody can use this to enumerate customers, and the screen
     * that follows has to keep that promise in its wording. See [DepartamentApiClient.requestPasswordReset].
     *
     * The link in the letter opens the SITE, which calls `password-reset/consume` with the token
     * inside it. This app never sees that token, so it never makes that call.
     */
    suspend fun requestPasswordReset(email: String) {
        if (!BackendConfig.isConfigured()) throw ApiError.NotConfigured
        api.requestPasswordReset(email)
    }

    /**
     * **Attaching an e-mail to the account that is already signed in.**
     *
     * The errand a Telegram-only account arrives with: it has no address, so «Способы входа» has
     * nothing to report on its «Почта» row and the panel has no second way to let that person back
     * in. `POST /client/link-email-request` carries the address alone — the Bearer token already
     * says which account is asking, and no password is set here.
     *
     * A flow rather than a suspend call for the same reason registration is one: the errand does
     * not end with the request. The 200 only means a letter is out, and the wait that follows is
     * the user's, not the network's — so the state moves to [LoginState.AwaitingEmailVerification]
     * and this flow sits on [pollUntilLinked] until the link is opened.
     */
    fun beginLinkEmail(email: String): Flow<LoginState> = flow {
        if (!BackendConfig.isConfigured()) {
            emit(LoginState.Error(ApiError.NotConfigured))
            return@flow
        }

        try {
            api.linkEmailRequest(email)
        } catch (e: ApiError) {
            emit(LoginState.Error(e))
            return@flow
        }

        emit(LoginState.AwaitingEmailVerification(email))
        pollUntilEmail(expected = null)
    }

    /**
     * **Replacing the address an account already has.**
     *
     * The same letter, the same link, the same `verify-link-email` behind it — and a different
     * endpoint, because the panel guards this one: an account WITH a password must prove it before
     * anything is sent ([currentPassword]), which is its account-takeover check. Getting that guard
     * wrong is reported as `PASSWORD_REQUIRED` / `INVALID_PASSWORD` beside the sentence, and both
     * belong on the password field rather than on the screen — see `ApiError.serverCode`.
     *
     * **The wait afterwards asks a sharper question than [beginLinkEmail]'s.** «Есть ли адрес?» is
     * already true here and would resolve instantly against the OLD one, ending the wait before the
     * user has opened anything. So the poll waits for the address to BECOME [newEmail].
     */
    fun beginChangeEmail(newEmail: String, currentPassword: String?): Flow<LoginState> = flow {
        if (!BackendConfig.isConfigured()) {
            emit(LoginState.Error(ApiError.NotConfigured))
            return@flow
        }

        try {
            api.changeEmailRequest(newEmail, currentPassword)
        } catch (e: ApiError) {
            emit(LoginState.Error(e))
            return@flow
        }

        emit(LoginState.AwaitingEmailVerification(newEmail))
        pollUntilEmail(expected = newEmail)
    }

    /**
     * Gives the account a password, so the address it just gained is a way IN rather than a label.
     *
     * The profile is re-read afterwards and written back to [AccountSession] — `hasPassword` has
     * just changed on the server, and it is the fact that decides whether the NEXT «Сменить почту»
     * asks for the current password. A failure of that re-read is swallowed: the password is set
     * either way, and reporting a stale cache as a failed errand would be a lie about what happened.
     */
    suspend fun setPassword(newPassword: String) {
        if (!BackendConfig.isConfigured()) throw ApiError.NotConfigured
        api.setPassword(newPassword)
        // THE FLAG IS THE OTHER HALF OF THE ERRAND. `set-password` is refused only when
        // `passwordHash && onboardingCompleted`, so without this the endpoint stays open on an
        // account that now has a password: the step could be walked a second time, and what this
        // app believes about the account would part company with what the site shows.
        //
        // Swallowed on failure, both of them, and in this order. The password is SAVED by the time
        // either runs; reporting a failed follow-up as a failed errand would tell the user their
        // password did not take when it did. The profile is re-read last so it carries the flag
        // this call just set — `hasPassword` and `onboardingCompleted` are what the «Почта» row and
        // the next «Сменить почту» read.
        runCatching { api.completeOnboarding() }
        runCatching { AccountSession.updateProfile(api.getMe()) }
    }

    /** The profile this process is signed in with, or null. Read-only; see [AccountSession]. */
    fun currentProfile(): UserProfileDto? =
        (AccountSession.state.value as? AccountSession.AccountState.LoggedIn)?.profile

    /**
     * Watches for the emailed link being opened, and — as in [pollUntilVerified] — it does so by
     * asking the one question whose answer changes when it is. Here that question is not the login
     * (this user is already signed in) but the PROFILE: `/client/auth/me` is what the site's
     * `verify-link-email` writes the address into.
     *
     * @param expected null when the account had NO address (any address is the answer), and the new
     * address when it is being REPLACED — there the old one is already non-blank, so «есть ли
     * адрес» would answer itself on the first round and end the wait before anything happened.
     *
     * **Every failure here is swallowed, exactly as in [pollUntilVerified]**: an unread letter is
     * not news, and neither is a network blip. Note this is also why the poll asks [api] directly
     * rather than going through `AccountRepository` — a 401 on the identity endpoint ENDS THE
     * SESSION there, and a token that died while a letter was in flight must not sign the user out
     * from inside a background poll they cannot see.
     *
     * The profile is written back to [AccountSession] before the success is emitted, so the row
     * that sent the user here reports the new address from cache rather than from a lucky reload.
     */
    private suspend fun FlowCollector<LoginState>.pollUntilEmail(expected: String?) {
        val deadline = System.currentTimeMillis() + VERIFY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(VERIFY_POLL_INTERVAL_MS)
            val profile = try {
                api.getMe()
            } catch (e: ApiError) {
                continue // Still unconfirmed, or a blip. Neither is news.
            }
            if (profile.emailArrived(expected)) {
                AccountSession.updateProfile(profile)
                emit(LoginState.Success(profile))
                return
            }
        }
    }

    /**
     * Site login with email/password. On [LoginResult.Success] the session is persisted; on
     * [LoginResult.Requires2FA] the caller must follow up with [submit2fa] using the tempToken.
     * Throws [ApiError] on failure.
     */
    suspend fun loginSite(email: String, password: String): LoginResult {
        if (!BackendConfig.isConfigured()) throw ApiError.NotConfigured
        val result = api.login(email, password)
        if (result is LoginResult.Success) {
            AccountSession.onAuthenticated(result.token, result.client)
        }
        return result
    }

    /** Completes a 2FA login; persists the session and returns the profile. Throws [ApiError]. */
    suspend fun submit2fa(tempToken: String, code: String): UserProfileDto {
        if (!BackendConfig.isConfigured()) throw ApiError.NotConfigured
        val auth: AuthResult = api.login2fa(tempToken, code)
        AccountSession.onAuthenticated(auth.token, auth.client)
        return auth.client
    }

    companion object {
        /**
         * **Где живёт доводка, которую никто не ждёт.** Один скоуп на процесс, а не поле
         * экземпляра и тем более не `viewModelScope`: [settleRegistration] запускается ровно в тот
         * момент, когда экран регистрации начинает закрываться, и всё, что привязано к экрану,
         * будет отменено у неё под ногами.
         *
         * [SupervisorJob], потому что задачи здесь независимы: упавшая доводка одной регистрации
         * не должна закрывать скоуп для следующей. [Dispatchers.IO], потому что это два сетевых
         * запроса и ничего больше.
         *
         * Он намеренно НИКОГДА не отменяется: отменить его значило бы бросить ровно ту работу,
         * ради которой он и заведён. Задач в нём в худшем случае столько, сколько регистраций за
         * жизнь процесса, и каждая ограничена таймаутами клиента.
         */
        val settleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Досылка одной только отметки о первом входе, без перечитывания профиля.
         *
         * Доводка после регистрации идёт вне ожидания, поэтому процесс может умереть между «сессия
         * поднята» и «отметка ушла» — и досылать её станет некому. Симптом тихий и живучий:
         * «Способы входа» зовут задать пароль тому, у кого он есть.
         *
         * Зовётся оттуда, где профиль УЖЕ прочитан и по нему видно, что отметка не дошла: пара
         * «пароль есть, первый вход не закрыт» иначе не появляется. Перечитывать профиль здесь
         * незачем — тот, кто позвал, только что это сделал.
         *
         * То же молчание, что у доводки: человеку рассказывать нечего, аккаунт работает, а причина
         * остаётся в журнале.
         */
        fun settleOnboarding(api: DepartamentApiClient) {
            settleScope.launch {
                runCatching { api.completeOnboarding() }.onFailure {
                    LogUtil.w(
                        AppConfig.TAG,
                        "Onboarding was not marked complete on retry, the account stays eligible " +
                            "for a password step it does not need",
                        it,
                    )
                }
            }
        }

        /** 4s between rounds: the user is reading a letter, not watching a spinner tick. */
        const val VERIFY_POLL_INTERVAL_MS = 4_000L

        /** 10 minutes. Long enough to find the letter in a spam folder; not an unbounded poll. */
        const val VERIFY_TIMEOUT_MS = 10 * 60 * 1_000L
    }
}
