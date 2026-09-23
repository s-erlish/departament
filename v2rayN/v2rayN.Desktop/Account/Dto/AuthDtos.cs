using System.Text.Json.Serialization;

namespace v2rayN.Desktop.Account.Dto;

// Auth endpoints of the Departament backend (JWT, 7-day, NO refresh).
//
//  POST /client/auth/telegram-login-token  -> TelegramTokenDto
//  GET  /client/auth/telegram-login-check  -> 404 NotYet / 200 Confirmed / 410 Expired (TelegramCheckResult)
//  POST /client/auth/login                 -> LoginResult (Success | Requires2FA)
//  POST /client/auth/2fa-login             -> AuthResult
//  POST /client/auth/google                -> AuthResult
//  GET  /client/auth/me                    -> UserProfileDto
//
// Ported 1:1 from V2rayNG auth/dto/AuthDtos.kt. Gson @SerializedName(alternate=[...]) is preserved
// via extra set-only properties that funnel each alternate spelling into the canonical field.

#region request bodies

public sealed class LoginRequestDto
{
    public string Email { get; set; } = "";
    public string Password { get; set; } = "";

    public LoginRequestDto()
    {
    }

    public LoginRequestDto(string email, string password)
    {
        Email = email;
        Password = password;
    }
}

public sealed class TwoFaLoginRequestDto
{
    public string TempToken { get; set; } = "";
    public string Code { get; set; } = "";

    public TwoFaLoginRequestDto()
    {
    }

    public TwoFaLoginRequestDto(string tempToken, string code)
    {
        TempToken = tempToken;
        Code = code;
    }
}

public sealed class GoogleLoginRequestDto
{
    public string IdToken { get; set; } = "";
    public string? ReferralCode { get; set; }

    public GoogleLoginRequestDto()
    {
    }

    public GoogleLoginRequestDto(string idToken, string? referralCode = null)
    {
        IdToken = idToken;
        ReferralCode = referralCode;
    }
}

/// <summary>POST /client/auth/register — email+password sign-up (referralCode optional).</summary>
public sealed class RegisterRequestDto
{
    public string Email { get; set; } = "";
    public string Password { get; set; } = "";
    public string? ReferralCode { get; set; }

    public RegisterRequestDto()
    {
    }

    public RegisterRequestDto(string email, string password, string? referralCode = null)
    {
        Email = email;
        Password = password;
        ReferralCode = referralCode;
    }
}

/// <summary>POST /client/auth/verify-email — confirm the emailed registration token.</summary>
public sealed class TokenRequestDto
{
    public string Token { get; set; } = "";

    public TokenRequestDto()
    {
    }

    public TokenRequestDto(string token) => Token = token;
}

/// <summary>POST /client/auth/magic-link/request and /client/password-reset/request — {email}.</summary>
public sealed class EmailRequestDto
{
    public string Email { get; set; } = "";

    public EmailRequestDto()
    {
    }

    public EmailRequestDto(string email) => Email = email;
}

/// <summary>POST /client/auth/magic-link/consume — exchange the emailed token for a session.</summary>
public sealed class MagicLinkConsumeRequestDto
{
    public string Token { get; set; } = "";
    public string? ReferralCode { get; set; }

    public MagicLinkConsumeRequestDto()
    {
    }

    public MagicLinkConsumeRequestDto(string token, string? referralCode = null)
    {
        Token = token;
        ReferralCode = referralCode;
    }
}

/// <summary>POST /client/auth/password-reset/consume — {token,newPassword}.</summary>
public sealed class PasswordResetConsumeRequestDto
{
    public string Token { get; set; } = "";
    public string NewPassword { get; set; } = "";

    public PasswordResetConsumeRequestDto()
    {
    }

    public PasswordResetConsumeRequestDto(string token, string newPassword)
    {
        Token = token;
        NewPassword = newPassword;
    }
}

/// <summary>POST /client/auth/app-handoff/consume — {code} (app receives a code minted on the site).</summary>
public sealed class CodeRequestDto
{
    public string Code { get; set; } = "";

    public CodeRequestDto()
    {
    }

    public CodeRequestDto(string code) => Code = code;
}

/// <summary>POST /client/set-password — {newPassword} (set the first password on a passwordless account).</summary>
public sealed class SetPasswordRequestDto
{
    public string NewPassword { get; set; } = "";

    public SetPasswordRequestDto()
    {
    }

    public SetPasswordRequestDto(string newPassword) => NewPassword = newPassword;
}

/// <summary>
/// POST /client/profile/change-email/request — replace the address already attached to this account.
///
/// <see cref="CurrentPassword"/> is OPTIONAL in the wire schema and mandatory in fact whenever the
/// account has a password: the panel answers 400 code PASSWORD_REQUIRED when it is missing and 401
/// code INVALID_PASSWORD when it is wrong. Null is omitted on write (ApiJson drops nulls), so a
/// passwordless account sends the address alone rather than an empty string the schema would accept
/// and the comparison would then fail on.
/// </summary>
public sealed class ChangeEmailRequestDto
{
    public string NewEmail { get; set; } = "";
    public string? CurrentPassword { get; set; }

    public ChangeEmailRequestDto()
    {
    }

    public ChangeEmailRequestDto(string newEmail, string? currentPassword = null)
    {
        NewEmail = newEmail;
        CurrentPassword = currentPassword;
    }
}

/// <summary>POST /client/link-google — {idToken} (attach Google to the current account).</summary>
public sealed class LinkGoogleRequestDto
{
    public string IdToken { get; set; } = "";

    public LinkGoogleRequestDto()
    {
    }

    public LinkGoogleRequestDto(string idToken) => IdToken = idToken;
}

#endregion request bodies

#region raw responses (parsed then mapped to the result types)

/// <summary>POST /client/auth/telegram-login-token</summary>
public sealed class TelegramTokenDto
{
    public string Token { get; set; } = "";
}

/// <summary>Raw 200 body of GET /client/auth/telegram-login-check.</summary>
public sealed class TelegramCheckResponseDto
{
    public bool Confirmed { get; set; }
    public string? Token { get; set; }
    public UserProfileDto? Client { get; set; }
    public bool JustCreated { get; set; }
}

/// <summary>Raw body of POST /client/auth/login (either shape). Also reused for the auth responses of
/// POST /client/auth/verify-email and /client/auth/magic-link/consume (both go through the backend's
/// buildAuthResponse, so they can return {token,client} OR {requires2FA,tempToken}).</summary>
public sealed class LoginResponseDto
{
    public string? Token { get; set; }
    public UserProfileDto? Client { get; set; }

    [JsonPropertyName("requires2FA")]
    public bool Requires2Fa { get; set; }

    public string? TempToken { get; set; }
}

/// <summary>
/// Raw body of POST /client/auth/register. Two shapes: verification-off returns {token,client};
/// verification-on returns {message,requiresVerification:true} with NO token.
/// </summary>
public sealed class RegisterResponseDto
{
    public string? Token { get; set; }
    public UserProfileDto? Client { get; set; }
    public bool RequiresVerification { get; set; }
    public string? Message { get; set; }
}

/// <summary>
/// A message-only response (magic-link/request, password-reset/request, password-reset/consume,
/// link-email-request, set-password). Anti-enumeration endpoints return the same message either way.
/// </summary>
public sealed class MessageResponseDto
{
    public string? Message { get; set; }
    public int? ExpiresInMinutes { get; set; }
}

/// <summary>POST /client/link-telegram-request → {code, expiresAt, botUsername}. The user sends
/// `/link &lt;code&gt;` to the bot; poll /me until telegramLinked flips true.</summary>
public sealed class LinkTelegramRequestDto
{
    public string Code { get; set; } = "";
    public string? ExpiresAt { get; set; }
    public string? BotUsername { get; set; }
}

/// <summary>POST /client/auth/app-handoff → {code, expiresAt}. Open the site with this code to land
/// already signed-in.</summary>
public sealed class AppHandoffDto
{
    public string Code { get; set; } = "";
    public string? ExpiresAt { get; set; }
}

#endregion raw responses

#region result types consumed by the UI/session layer

/// <summary>Outcome of polling GET /client/auth/telegram-login-check.</summary>
public abstract record TelegramCheckResult
{
    /// <summary>404 — not confirmed yet, keep polling.</summary>
    public sealed record NotYet : TelegramCheckResult;

    /// <summary>410 — the login token expired.</summary>
    public sealed record Expired : TelegramCheckResult;

    /// <summary>200 — the user confirmed in Telegram; session is ready.</summary>
    public sealed record Confirmed(string Token, UserProfileDto Client, bool JustCreated) : TelegramCheckResult;
}

/// <summary>Outcome of POST /client/auth/login.</summary>
public abstract record LoginResult
{
    /// <summary>Password accepted, session issued.</summary>
    public sealed record Success(string Token, UserProfileDto Client) : LoginResult;

    /// <summary>Password accepted but a TOTP code is required; call 2fa-login with TempToken.</summary>
    public sealed record Requires2Fa(string TempToken) : LoginResult;
}

/// <summary>Outcome of POST /client/auth/register.</summary>
public abstract record RegisterResult
{
    /// <summary>Verification disabled server-side — a session was issued immediately.</summary>
    public sealed record Success(string Token, UserProfileDto Client) : RegisterResult;

    /// <summary>Verification required — a confirmation email was sent; no token yet.</summary>
    public sealed record RequiresVerification(string? Message) : RegisterResult;
}

/// <summary>A successful authentication carrying the JWT and profile.</summary>
public sealed class AuthResult
{
    public string Token { get; set; } = "";
    public UserProfileDto Client { get; set; } = new();
}

#endregion result types

/// <summary>The authenticated user's profile (GET /client/auth/me and embedded in auth responses).</summary>
public sealed class UserProfileDto
{
    public string Id { get; set; } = "";
    public string Email { get; set; } = "";
    public double Balance { get; set; }

    // The backend exposes the profile currency as `preferredCurrency`; accept `currency` too so
    // either spelling maps. Stays blank when absent (then defaults to the ruble sign).
    [JsonPropertyName("currency")]
    public string Currency { get; set; } = "";

    [JsonPropertyName("preferredCurrency")]
    public string PreferredCurrency
    {
        set
        {
            if (Currency.IsNullOrEmpty() && value.IsNotEmpty())
            {
                Currency = value;
            }
        }
    }

    public bool TelegramLinked { get; set; }

    // Account-linking state the backend's /client/auth/me already returns (see toClientShape):
    // googleLinked = Boolean(googleId), appleLinked = Boolean(appleId), hasPassword = has passwordHash.
    // Drive the "Привязки" (linking) rows on the Account tab.
    public bool GoogleLinked { get; set; }
    public bool AppleLinked { get; set; }

    /// <summary>
    /// <b>Can this account sign in with a password at all?</b> The panel computes it as
    /// `Boolean(passwordHash)`. Decides two things: whether «Привязать почту» ends with a
    /// «Придумайте пароль» step, and whether «Сменить почту» has to ask for the current password
    /// before it sends anything. Defaults to false — the safe end of both.
    /// </summary>
    public bool HasPassword { get; set; }

    /// <summary>
    /// False while the account still carries the dummy password an e-mail registration leaves behind.
    /// The panel's set-password refuses only when `passwordHash &amp;&amp; onboardingCompleted`, so BOTH
    /// flags decide whether the step is offered — see <see cref="UserProfileExtensions.CanSetPassword"/>.
    /// Defaults to <c>true</c> so a backend that omits the key reads as «первый вход завершён», which
    /// with <see cref="HasPassword"/> false still offers the step and with it true correctly does not.
    /// </summary>
    public bool OnboardingCompleted { get; set; } = true;

    public long? TelegramId { get; set; }
    public string? TelegramUsername { get; set; }

    // Telegram display / first name, when the backend exposes it. Preferred for the primary account
    // line; the key name varies across backends so accept the common spellings.
    [JsonPropertyName("telegramName")]
    public string? TelegramName { get; set; }

    [JsonPropertyName("telegramFirstName")]
    public string? TelegramNameAlt1 { set => SetTelegramName(value); }

    [JsonPropertyName("firstName")]
    public string? TelegramNameAlt2 { set => SetTelegramName(value); }

    [JsonPropertyName("first_name")]
    public string? TelegramNameAlt3 { set => SetTelegramName(value); }

    [JsonPropertyName("name")]
    public string? TelegramNameAlt4 { set => SetTelegramName(value); }

    [JsonPropertyName("displayName")]
    public string? TelegramNameAlt5 { set => SetTelegramName(value); }

    [JsonPropertyName("tgName")]
    public string? TelegramNameAlt6 { set => SetTelegramName(value); }

    public string ReferralCode { get; set; } = "";
    public string RemnawaveUuid { get; set; } = "";
    public bool TrialUsed { get; set; }
    public bool AutoRenewEnabled { get; set; }
    public bool TotpEnabled { get; set; }

    // Telegram profile photo, if the backend exposes one. Key name varies across backends.
    [JsonPropertyName("avatarUrl")]
    public string? AvatarUrl { get; set; }

    [JsonPropertyName("photoUrl")]
    public string? AvatarUrlAlt1 { set => SetAvatarUrl(value); }

    [JsonPropertyName("photo_url")]
    public string? AvatarUrlAlt2 { set => SetAvatarUrl(value); }

    [JsonPropertyName("telegramPhotoUrl")]
    public string? AvatarUrlAlt3 { set => SetAvatarUrl(value); }

    [JsonPropertyName("tgPhotoUrl")]
    public string? AvatarUrlAlt4 { set => SetAvatarUrl(value); }

    [JsonPropertyName("telegramAvatarUrl")]
    public string? AvatarUrlAlt5 { set => SetAvatarUrl(value); }

    [JsonPropertyName("avatar")]
    public string? AvatarUrlAlt6 { set => SetAvatarUrl(value); }

    [JsonPropertyName("photo")]
    public string? AvatarUrlAlt7 { set => SetAvatarUrl(value); }

    private void SetTelegramName(string? value)
    {
        if (TelegramName.IsNullOrEmpty() && value.IsNotEmpty())
        {
            TelegramName = value;
        }
    }

    private void SetAvatarUrl(string? value)
    {
        if (AvatarUrl.IsNullOrEmpty() && value.IsNotEmpty())
        {
            AvatarUrl = value;
        }
    }
}

/// <summary>
/// Две РАЗНЫЕ вопроса о пароле, заданные один раз здесь, чтобы экраны их не путали.
/// </summary>
public static class UserProfileExtensions
{
    /// <summary>
    /// <b>Примет ли панель POST /client/set-password для этого аккаунта?</b> Повторяет её собственный
    /// запрет — «Пароль уже установлен» она отвечает ровно тогда, когда пароль настоящий И первый
    /// вход завершён, — поэтому шаг предлагается там, где он сработает, и молча пропускается там,
    /// где нет. Повторить запрет, а не спрашивать всегда, — это разница между поручением, которое
    /// заканчивается, и поручением, которое заканчивается отказом на то, чего никто не просил.
    /// </summary>
    public static bool CanSetPassword(this UserProfileDto profile)
        => !profile.HasPassword || !profile.OnboardingCompleted;

    /// <summary>
    /// <b>Может ли этот аккаунт на самом деле войти по своей почте?</b> Обе половины, а не любая:
    /// вход на панели ищет аккаунт по адресу и затем сверяет хэш пароля, поэтому привязанный адрес
    /// без пароля за ним — идентификатор, а не способ войти.
    /// <para/>
    /// Намеренно НЕ <see cref="CanSetPassword"/>. Тот повторяет запрет set-password и истинен для
    /// аккаунта с настоящим паролем и незавершённым первым входом — аккаунта, который входит
    /// прекрасно. Строка «Почта» спрашивает это, шаг пароля — то, и единственный случай, где они
    /// расходятся, — ровно тот, где под адресом, которому ничего не нужно, появилось бы «Нужен
    /// пароль для входа».
    /// </summary>
    public static bool EmailSignInWorks(this UserProfileDto profile)
        => profile.Email.IsNotEmpty() && profile.HasPassword;
}
