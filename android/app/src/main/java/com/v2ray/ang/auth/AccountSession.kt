package com.v2ray.ang.auth

import com.v2ray.ang.auth.dto.UserProfileDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for the logged-in/out account state, observed by the UI.
 *
 * Seeded from [AuthTokenStore] on first access so a returning user is already "logged in".
 * Mutations here keep the persisted session and the in-memory [state] consistent.
 */
object AccountSession {

    sealed interface AccountState {
        object LoggedOut : AccountState
        data class LoggedIn(val profile: UserProfileDto) : AccountState
    }

    /**
     * False until the store has given ONE real answer about the session this process.
     *
     * The seed below runs on first touch of this object and its result is the state for the rest
     * of the process. [AuthTokenStore] is explicitly allowed to answer "not right now" — a Keystore
     * that is briefly unavailable makes it unreadable and it retries on a two-second timer — and a
     * seed taken inside that window used to be indistinguishable from a signed-out device: the
     * shell drew the sign-in gate, the Аккаунт tab drew the signed-out block, and the token, the
     * cached profile and the подписки were all on disk the whole time, unreachable until the user
     * killed the app and opened it again. That is the «вылетает аккаунт» that heals on a restart.
     *
     * So the seed no longer gets to be final when it was a guess: the first real answer replaces
     * it. Only that first one — after it, LoggedOut means [endSession] or [wipe] said so, and
     * re-reading the store there would fight them.
     */
    @Volatile
    private var sessionKnown = false

    private val _state = MutableStateFlow(seed())
    val state: StateFlow<AccountState> = _state.asStateFlow()

    private val subs = SubscriptionSyncManager()

    private fun seed(): AccountState {
        val known = AuthTokenStore.isLoggedInOrUnknown()
        sessionKnown = known != null
        return if (known == true) {
            AccountState.LoggedIn(AuthTokenStore.getUser() ?: UserProfileDto())
        } else {
            AccountState.LoggedOut
        }
    }

    /**
     * Whether there is a session, from the store — with the seed's guess corrected the first time
     * the store can actually answer. @see sessionKnown
     *
     * While the store still cannot answer, this reports what the process already believes rather
     * than "signed out". A gate that flips to «Войти» because the Keystore was busy for two
     * seconds is the same defect as the seed's, one screen further on.
     */
    fun isLoggedIn(): Boolean {
        val known = AuthTokenStore.isLoggedInOrUnknown() ?: return _state.value is AccountState.LoggedIn
        if (!sessionKnown) {
            sessionKnown = true
            _state.value = if (known) {
                AccountState.LoggedIn(AuthTokenStore.getUser() ?: UserProfileDto())
            } else {
                AccountState.LoggedOut
            }
        }
        return known
    }

    /**
     * Persist a freshly issued session and flip to LoggedIn.
     *
     * A BLANK jwt is refused. [isLoggedIn] asks the token store while [state] answers from memory,
     * so writing an empty token and flipping the state anyway left the two disagreeing for the rest
     * of the process: the shell drew the signed-in Аккаунт tab (state says LoggedIn) while every
     * request went out unauthenticated and every gate that asks `isLoggedIn()` — the loads, the
     * cache, the Главная gate — read "signed out". The reply that can produce it is the one nothing
     * pre-validates: [com.v2ray.ang.auth.dto.AuthResult] (2FA and Google login) defaults `token` to
     * "" when the backend answers 200 without one. Callers already handle [ApiError], so this is
     * reported the same way a malformed body is.
     */
    fun onAuthenticated(jwt: String, profile: UserProfileDto) {
        if (jwt.isBlank()) throw ApiError.Parse()
        AuthTokenStore.saveSession(jwt, user = profile)
        sessionKnown = true
        tokenDeathPending = false
        _state.value = AccountState.LoggedIn(profile)
    }

    /**
     * Set when the SERVER ended the session and cleared when the user did, so the screen that comes
     * back can tell the two apart. @see consumeTokenDeathNotice
     */
    @Volatile
    private var tokenDeathPending = false

    /**
     * **Did the session end on its own?** True exactly once after a token death, false after an
     * explicit sign-out and after every read.
     *
     * The seven-day JWT cannot be refreshed — this backend issues no refresh endpoint and the app
     * keeps no credentials to sign in again with — so the token WILL die, and until now it did so
     * in complete silence: the tab blanked, the signed-out block came back with its reason line
     * explicitly cleared, and the user was left to work out that they had been signed out at all,
     * let alone why. «Вылетает аккаунт» is partly this: not the sign-out, the absence of a sentence
     * about it.
     *
     * One-shot, because it describes a transition and not a state: the next thing the user does on
     * that screen is start a new sign-in, and a reason still standing under it would be describing
     * the previous attempt.
     */
    fun consumeTokenDeathNotice(): Boolean {
        if (!tokenDeathPending) return false
        tokenDeathPending = false
        return true
    }

    /** Refresh the cached profile (e.g. after GET /client/auth/me). */
    fun updateProfile(profile: UserProfileDto) {
        AuthTokenStore.saveUser(profile)
        if (isLoggedIn()) _state.value = AccountState.LoggedIn(profile)
    }

    /**
     * The token is dead: end the session and leave everything else alone.
     *
     * Called when the identity endpoint (getMe) answers 401 — and only there; a 403, or a 401 from
     * any other endpoint, is a per-action failure and must not touch the session at all.
     *
     * This deliberately does NOT remove the imported subscriptions. A 7-day JWT expiring is the
     * most ordinary event in this app's life, and it says nothing about whether the user still has
     * a подписка: the subscription URLs are Remnawave URLs with their own credentials, they keep
     * refreshing, and the серверы keep working while the user is signed out. Wiping them here is
     * how one visit to the Аккаунт tab used to delete every сервер on the device — including the
     * selected one — for a token that simply timed out.
     *
     * Signing back in re-imports through the kept uuid->guid map, so the same провайдеры are
     * updated in place rather than duplicated. Detaching the account's subscriptions is what
     * [wipe] is for, and only an explicit sign-out asks for that.
     */
    fun endSession() {
        sessionKnown = true
        // The user did not ask for this, so the screen that comes back has to say why. @see
        // consumeTokenDeathNotice
        tokenDeathPending = true
        AuthTokenStore.clearSession()
        // Eagerly, not on the next read: [AccountCache] only evicts when something asks it for a
        // value while signed out, and signing straight back in performs no such read. The payments
        // entry is keyed globally rather than per user, so the next account would have inherited
        // the previous one's history.
        AccountCache.invalidateAll()
        _state.value = AccountState.LoggedOut
    }

    /**
     * Clear session + managed subscriptions and flip to LoggedOut. **Explicit user logout only.**
     *
     * Removing the subscriptions is the point here: the user asked to detach this device from the
     * account, so the account's провайдеры go with it. Everything the user added by hand stays.
     * An expired token takes [endSession] instead.
     *
     * Throws when the token could not actually be erased — the one outcome a sign-out must never
     * paper over. The store is unopenable in that case, so nothing was removed either (the
     * managed-guid map read back empty) and the session is untouched and the action retryable,
     * which is exactly what the caller's failure branch promises the user.
     */
    suspend fun wipe() {
        sessionKnown = true
        // An explicit sign-out explains itself; nothing is owed to the screen afterwards.
        tokenDeathPending = false
        subs.removeAllManaged()
        check(AuthTokenStore.clear()) { "auth store unavailable: the session was not cleared" }
        AccountCache.invalidateAll()
        _state.value = AccountState.LoggedOut
    }
}
