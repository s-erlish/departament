package com.v2ray.ang.auth.dto

import com.google.gson.annotations.SerializedName

/**
 * Auth endpoints of the Departament backend (JWT, 7-day, NO refresh).
 *
 *  POST /client/auth/register              -> [RegisterResult] (Success | RequiresVerification)
 *  POST /client/auth/telegram-login-token  -> [TelegramTokenDto]
 *  GET  /client/auth/telegram-login-check  -> 404 NotYet / 200 Confirmed / 410 Expired ([TelegramCheckResult])
 *  POST /client/auth/login                 -> [LoginResult] (Success | Requires2FA)
 *  POST /client/auth/2fa-login             -> [AuthResult]
 *  POST /client/auth/google                -> [AuthResult]
 *  GET  /client/auth/me                    -> [UserProfileDto]
 *  POST /client/auth/password-reset/request-> 200 {message}, the same one either way
 *
 * And three errands of an account that already exists, which is why they sit on the client root
 * rather than under `/client/auth`:
 *
 *  POST /client/link-email-request          -> 200 (a letter is out) / 400 / 500 / 503
 *  POST /client/set-password                -> 200 / 400
 *  POST /client/profile/change-email/request-> 200 (a letter is out) / 400 / 401 / 403 / 404 / 500 / 503
 */

// region request bodies

data class LoginRequestDto(
    val email: String,
    val password: String,
)

/**
 * POST /client/auth/register. The password must be at least 8 characters — the panel refuses a
 * shorter one with 400, so the form gates on the same number rather than letting the server say it.
 * [referralCode] is optional and is sent only when the app has one to pass on.
 */
data class RegisterRequestDto(
    val email: String,
    val password: String,
    val referralCode: String? = null,
)

data class TwoFaLoginRequestDto(
    val tempToken: String,
    val code: String,
)

data class GoogleLoginRequestDto(
    val idToken: String,
    val referralCode: String? = null,
)

/**
 * POST /client/auth/password-reset/request — «я забыл пароль, пришлите ссылку».
 *
 * THE ADDRESS IS THE WHOLE BODY, and unlike [LinkEmailRequestDto] there is no session behind it:
 * this is the request of somebody who cannot get in. Which is also why the panel answers it
 * identically for a known address and an unknown one — a different answer would turn this endpoint
 * into a way of asking «есть ли у вас аккаунт на этот адрес». The copy the app writes afterwards
 * has to keep that promise; see `auth_reset_sent_body`.
 */
data class PasswordResetRequestDto(
    val email: String,
)

/**
 * POST /client/link-email-request — attach an address to the session the request already carries.
 *
 * THE ADDRESS IS THE WHOLE BODY. There is no password here and there must not be one: the account
 * exists, the caller is already authenticated by the Bearer token, and what the panel is being
 * asked for is a letter, not a credential. The password the account will sign in with afterwards
 * is set on the site, behind the link.
 */
data class LinkEmailRequestDto(
    val email: String,
)

/**
 * POST /client/set-password — the password an account gets AFTER an address is attached to it.
 *
 * **Six characters, not eight.** Registration's floor is 8 and this endpoint's is 6; they are
 * different endpoints with different schemas on the panel, and copying the bigger number here would
 * refuse a password the server would have taken. See [com.v2ray.ang.ui.LoginActivity].
 */
data class SetPasswordRequestDto(
    val newPassword: String,
)

/**
 * POST /client/profile/change-email/request — replace the address an account already has.
 *
 * [currentPassword] is sent only when the account HAS one ([UserProfileDto.hasPassword]); the panel
 * requires it then and ignores its absence otherwise, because for an account without a password the
 * live session is already the proof of identity. Getting that wrong is not a cosmetic error: it is
 * the panel's account-takeover guard, and it answers `PASSWORD_REQUIRED` / `INVALID_PASSWORD` —
 * both of which belong on the password FIELD rather than on the screen.
 */
data class ChangeEmailRequestDto(
    val newEmail: String,
    val currentPassword: String? = null,
)

// endregion

// region raw responses (parsed then mapped to the sealed result types)

/** POST /client/auth/telegram-login-token */
data class TelegramTokenDto(
    val token: String = "",
)

/** Raw 200 body of GET /client/auth/telegram-login-check. */
data class TelegramCheckResponseDto(
    val confirmed: Boolean = false,
    val token: String? = null,
    val client: UserProfileDto? = null,
    val justCreated: Boolean = false,
)

/**
 * Raw 201 body of POST /client/auth/register, in EITHER of the two shapes the panel answers with:
 * `{token, client}` when e-mail verification is switched off (a session, immediately), or
 * `{message, requiresVerification}` when it is on and a confirmation letter has been sent.
 */
data class RegisterResponseDto(
    val token: String? = null,
    val client: UserProfileDto? = null,
    val requiresVerification: Boolean = false,
    val message: String? = null,
)

/** Raw body of POST /client/auth/login (either shape). */
data class LoginResponseDto(
    val token: String? = null,
    val client: UserProfileDto? = null,
    val requires2FA: Boolean = false,
    val tempToken: String? = null,
)

// endregion

// region result types consumed by the UI/session layer

/** Outcome of polling GET /client/auth/telegram-login-check. */
sealed interface TelegramCheckResult {
    /** 404 — not confirmed yet, keep polling. */
    object NotYet : TelegramCheckResult

    /** 410 — the login token expired. */
    object Expired : TelegramCheckResult

    /** 200 — the user confirmed in Telegram; session is ready. */
    data class Confirmed(
        val token: String,
        val client: UserProfileDto,
        val justCreated: Boolean,
    ) : TelegramCheckResult
}

/**
 * Outcome of POST /client/auth/register.
 *
 * The two cases are a property of the PANEL, not of the request: the same body registers a session
 * outright on an installation with verification disabled and only sends a letter on one with it
 * enabled, so the app has to be able to finish either way.
 */
sealed interface RegisterResult {
    /** Verification is off on this panel — the account exists and the session is already issued. */
    data class Success(val token: String, val client: UserProfileDto) : RegisterResult

    /**
     * Verification is on: a letter is on its way and there is no token yet. [message] is the
     * panel's own sentence about it, kept for the log — the waiting screen writes its own copy,
     * which names the address the letter went to.
     */
    data class RequiresVerification(val message: String?) : RegisterResult
}

/** Outcome of POST /client/auth/login. */
sealed interface LoginResult {
    /** Password accepted, session issued. */
    data class Success(val token: String, val client: UserProfileDto) : LoginResult

    /** Password accepted but a TOTP code is required; call 2fa-login with [tempToken]. */
    data class Requires2FA(val tempToken: String) : LoginResult
}

/** A successful authentication carrying the JWT and profile. */
data class AuthResult(
    val token: String = "",
    val client: UserProfileDto = UserProfileDto(),
)

/** The authenticated user's profile (GET /client/auth/me and embedded in auth responses). */
data class UserProfileDto(
    val id: String = "",
    val email: String = "",
    val balance: Double = 0.0,
    // The backend exposes the profile currency as `preferredCurrency`; accept `currency` too so
    // either spelling maps. Stays blank when absent (currencySymbol then defaults to ₽ for RUB).
    @SerializedName(value = "currency", alternate = ["preferredCurrency"])
    val currency: String = "",
    val telegramLinked: Boolean = false,
    val telegramId: Long? = null,
    val telegramUsername: String? = null,
    // Telegram display / first name, when the backend exposes it. Preferred for the primary
    // account line; the key name varies across backends so accept the common spellings. Stays
    // null (so the @username/email fallback is used unchanged) when absent.
    @SerializedName(
        value = "telegramName",
        alternate = ["telegramFirstName", "firstName", "first_name", "name", "displayName", "tgName"],
    )
    val telegramName: String? = null,
    val referralCode: String = "",
    val remnawaveUuid: String = "",
    val trialUsed: Boolean = false,
    val autoRenewEnabled: Boolean = false,
    val totpEnabled: Boolean = false,
    /**
     * **Can this account sign in with a password at all?** The panel computes it as
     * `Boolean(passwordHash)` and sends it on every profile, and it is the fact that decides two
     * screens: whether «Привязать почту» ends with a «Придумайте пароль» step, and whether
     * «Сменить почту» has to ask for the current password before it will send anything.
     *
     * Defaults to false, which is the safe end of both: a profile that somehow arrives without the
     * field offers the password step (the panel refuses with a sentence if it is not needed) and
     * omits the current-password box (the panel answers `PASSWORD_REQUIRED` if it was).
     */
    val hasPassword: Boolean = false,
    /**
     * False while the account still carries the dummy password an e-mail registration leaves
     * behind. The panel's `set-password` refuses only when `passwordHash && onboardingCompleted`,
     * so BOTH fields decide whether the step is offered — see `UserProfileDto.canSetPassword`.
     *
     * Defaults to true so an older backend that omits it is read as "onboarding is done", which
     * with `hasPassword` false still offers the step and with it true correctly does not.
     */
    val onboardingCompleted: Boolean = true,
    // Telegram profile photo, if the backend exposes one. Key name varies across backends,
    // so accept the common spellings; stays null (monogram fallback) when absent.
    @SerializedName(
        value = "avatarUrl",
        alternate = ["photoUrl", "photo_url", "telegramPhotoUrl", "tgPhotoUrl", "telegramAvatarUrl", "avatar", "photo"],
    )
    val avatarUrl: String? = null,
)

/**
 * **Would `POST /client/set-password` be accepted for this account?**
 *
 * Mirrors the panel's own gate — it refuses with «Пароль уже установлен» exactly when the account
 * has a real password AND has finished onboarding — so the step is offered when it can work and is
 * silently skipped when it cannot. Mirroring rather than always asking is the difference between a
 * flow that ends and a flow that ends with a refusal for something the user never requested.
 */
fun UserProfileDto.canSetPassword(): Boolean = !hasPassword || !onboardingCompleted

/**
 * **Can this account actually sign in with its e-mail?** Both halves, never either: the panel's
 * login looks up the account by address and then verifies a password hash, so an attached address
 * with no password behind it is an identifier and not a way in.
 *
 * Deliberately NOT [canSetPassword]. That one mirrors the panel's set-password gate and is true for
 * an account whose password is real but whose onboarding is unfinished — an account that signs in
 * perfectly well. The «Почта» row asks this question, the password step asks that one, and the
 * single case where they disagree is exactly the one that would put «Нужен пароль для входа» under
 * an address that has never needed anything.
 */
fun UserProfileDto.emailSignInWorks(): Boolean = email.isNotBlank() && hasPassword

/**
 * **Has the address the app is waiting on actually arrived?** The one decision the confirmation
 * poll makes, kept here as a function of the profile rather than inline in the loop, because both
 * of its halves are easy to get wrong in a way that never raises anything — it just waits forever.
 *
 * @param expected null when the account had NO address: any address is the answer, because the
 * link is the only thing that could have put one there. The new address when one is being
 * REPLACED: «есть ли адрес» is already true then and would end the wait on its first round,
 * before the user has opened anything.
 *
 * Compared case-insensitively and against a trimmed value, because the panel lower-cases what it
 * is given: a user who typed `A@B.RU` would otherwise never be answered about their own address.
 */
fun UserProfileDto.emailArrived(expected: String?): Boolean =
    if (expected == null) email.isNotBlank() else email.equals(expected.trim(), ignoreCase = true)
