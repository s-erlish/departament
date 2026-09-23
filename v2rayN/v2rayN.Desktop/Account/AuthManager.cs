using v2rayN.Desktop.Account.Dto;

namespace v2rayN.Desktop.Account;

/// <summary>UI-facing state of a login attempt. Port of V2rayNG auth/AuthManager.LoginState.</summary>
public abstract record LoginState
{
    public sealed record Idle : LoginState;

    /// <summary>Deep link is ready; the UI should open Telegram with it.</summary>
    public sealed record AwaitingTelegram(string DeepLink) : LoginState;

    /// <summary>Polling the backend for confirmation. Carries the deep link so the UI can reopen it.</summary>
    public sealed record Polling(string DeepLink) : LoginState;

    /// <summary>A site email/password or 2FA request is in flight.</summary>
    public sealed record SiteLoading : LoginState;

    /// <summary>
    /// A browser→app SSO handoff code was received (custom-scheme callback or a pasted code) and is being
    /// exchanged for a session via <see cref="AuthManager.ConsumeAppHandoff"/>. The UI shows a focused
    /// «завершаем вход через сайт…» step while the one-time code is redeemed.
    /// </summary>
    public sealed record SiteHandoffLoading : LoginState;

    /// <summary>An email+password registration request is in flight.</summary>
    public sealed record RegisterLoading : LoginState;

    /// <summary>
    /// Registration accepted but the backend requires email verification (no token issued). The UI shows
    /// the «подтвердите почту» state; <see cref="AuthManager.BeginRegister"/> keeps polling login with the
    /// entered password until the user clicks the emailed link and login starts succeeding.
    /// </summary>
    public sealed record AwaitingEmailVerification(string Email) : LoginState;

    /// <summary>A magic sign-in link was emailed. The link is consumed in the browser (no in-app callback,
    /// so nothing to poll — see <see cref="AuthManager.BeginMagicLink"/>).</summary>
    public sealed record MagicLinkSent(string Email) : LoginState;

    /// <summary>A password-reset link was emailed. The reset is completed in the browser; the user then
    /// returns and signs in with the new password.</summary>
    public sealed record PasswordResetSent(string Email) : LoginState;

    /// <summary>Confirmed — session persisted; carries the profile.</summary>
    public sealed record Success(UserProfileDto Profile) : LoginState;

    /// <summary>
    /// The emailed link was opened and the PROFILE now carries the address — the ending of
    /// <see cref="AuthManager.BeginLinkEmail"/> / <see cref="AuthManager.BeginChangeEmail"/>.
    ///
    /// Deliberately NOT <see cref="Success"/>: that one means «a session has just been issued» and its
    /// handlers re-import subscriptions and hand the shell to the sync overlay. Here the session was
    /// already there — the account merely gained an address, and the only things that follow are the
    /// profile landing in the cache and, when it still has no password, the step that gives it one.
    /// </summary>
    public sealed record EmailAttached(UserProfileDto Profile) : LoginState;

    public sealed record Error(ApiError ErrorValue) : LoginState;
}

/// <summary>
/// Orchestrates the auth flows only (Telegram deep-link login, site email/password, TOTP 2FA) plus the
/// two errands that attach an address to a session that already exists (<see cref="BeginLinkEmail"/>,
/// <see cref="BeginChangeEmail"/> — same emailed link, same wait). There is NO refresh/logout here —
/// the JWT is 7-day and non-refreshable; session persistence is delegated to
/// <see cref="AccountSession"/>/<see cref="AuthTokenStore"/>. Port of V2rayNG auth/AuthManager.kt.
/// </summary>
public sealed class AuthManager
{
    private readonly IDepartamentApiClient _api;

    public AuthManager(IDepartamentApiClient? api = null)
    {
        _api = api ?? new DepartamentApiClient();
    }

    public bool IsLoggedIn() => AuthTokenStore.IsLoggedIn();

    /// <summary>
    /// Telegram login: create a login token, emit <see cref="LoginState.AwaitingTelegram"/> (so the UI
    /// opens the deep link), then poll every ~2s (capped at ~3 min) until the user confirms in Telegram.
    /// On confirmation the session is persisted before <see cref="LoginState.Success"/>.
    ///
    /// The poll rides out transient failures rather than ending on the first one, and re-checks
    /// <paramref name="cancellationToken"/> after each request so a window closed mid-flight cannot be
    /// signed in behind the user's back. Cancelling always ends the flow silently — there is no error to
    /// report when the user is the one who stopped.
    /// </summary>
    public async Task BeginTelegramLogin(Action<LoginState> emit, CancellationToken cancellationToken)
    {
        if (!BackendConfig.IsConfigured())
        {
            emit(new LoginState.Error(new ApiError.NotConfiguredError()));
            return;
        }

        TelegramTokenDto tokenDto;
        try
        {
            tokenDto = await _api.CreateTelegramLoginToken();
        }
        catch (ApiError e)
        {
            emit(new LoginState.Error(e));
            return;
        }
        if (tokenDto.Token.IsNullOrEmpty())
        {
            emit(new LoginState.Error(new ApiError.Parse()));
            return;
        }

        var deepLink = $"https://t.me/{BackendConfig.BotUsername}?start=auth_{tokenDto.Token}";
        emit(new LoginState.AwaitingTelegram(deepLink));
        emit(new LoginState.Polling(deepLink));

        var pollInterval = TimeSpan.FromSeconds(2);
        var deadline = DateTime.UtcNow.AddMinutes(3);
        var transientFailures = 0;

        while (DateTime.UtcNow < deadline)
        {
            try
            {
                await Task.Delay(pollInterval, cancellationToken);
            }
            catch (OperationCanceledException)
            {
                return;
            }

            TelegramCheckResult result;
            try
            {
                result = await _api.CheckTelegramLogin(tokenDto.Token);
                transientFailures = 0;
            }
            catch (ApiError e)
            {
                // A single failed poll is not a failed login. The user is in Telegram right now, and
                // this is exactly when the machine's connectivity is least stable — the browser handing
                // off to the Telegram client, a VPN toggling, a DNS resolver still warming up. Killing
                // the whole flow on the first blip stranded them: they confirmed in Telegram and came
                // back to an error card, with the login token already spent, so the only way forward was
                // to start over. Transient failures are re-tried against the SAME token until the 3-min
                // deadline; only a definitive answer stops the flow (see IsTransient).
                if (!IsTransient(e) || ++transientFailures > MaxTransientPollFailures)
                {
                    emit(new LoginState.Error(e));
                    return;
                }
                continue;
            }

            // The window may have been closed (or another attempt started) while that request was in
            // flight. Cancellation is only observed by the Delay above, so without this check a reply
            // that arrived after the user backed out still persisted a session and pushed Success into
            // a UI that had already moved on — signing them back in right after they signed out.
            if (cancellationToken.IsCancellationRequested)
            {
                return;
            }

            switch (result)
            {
                case TelegramCheckResult.Confirmed confirmed:
                    AccountSession.OnAuthenticated(confirmed.Token, confirmed.Client);
                    emit(new LoginState.Success(confirmed.Client));
                    return;
                case TelegramCheckResult.Expired:
                    emit(new LoginState.Error(new ApiError.GoneError()));
                    return;
                default:
                    // NotYet — keep polling
                    break;
            }
        }

        emit(new LoginState.Error(new ApiError.TimeoutError()));
    }

    /// <summary>
    /// How many polls in a row may fail transiently before the login gives up. Bounded so a backend
    /// that is genuinely down still reports within ~10s instead of leaving the user watching a spinner
    /// for the full three minutes; the counter resets on every poll that gets through.
    /// </summary>
    private const int MaxTransientPollFailures = 5;

    /// <summary>
    /// True for failures that say nothing about the login itself — the request never reached a verdict.
    /// Everything else (unauthorized, gone/expired token, unparseable reply, backend not configured) is
    /// an answer, and answers stop the flow.
    /// </summary>
    private static bool IsTransient(ApiError e) => e switch
    {
        ApiError.NetworkError => true,
        ApiError.TimeoutError => true,
        ApiError.RateLimited => true,
        ApiError.ServiceUnavailable => true,
        ApiError.Server srv => srv.Code >= 500,
        _ => false,
    };

    /// <summary>
    /// Site login with email/password. On <see cref="LoginResult.Success"/> the session is persisted;
    /// on <see cref="LoginResult.Requires2Fa"/> the caller must follow up with <see cref="Submit2Fa"/>.
    /// Throws <see cref="ApiError"/> on failure.
    /// </summary>
    public async Task<LoginResult> LoginSite(string email, string password)
    {
        if (!BackendConfig.IsConfigured())
        {
            throw new ApiError.NotConfiguredError();
        }
        var result = await _api.Login(email, password);
        if (result is LoginResult.Success success)
        {
            AccountSession.OnAuthenticated(success.Token, success.Client);
        }
        return result;
    }

    /// <summary>
    /// Redeems a one-time app-handoff code minted by the site's <c>/app-login</c> page (a logged-in web
    /// user who returned to the app via the <c>departamentvpn://auth?code=…</c> scheme, or a manually
    /// pasted code) for a session. Persists the session and returns the profile — the SAME terminal path
    /// as an email or Telegram login. Throws <see cref="ApiError"/> on failure (e.g. an expired code).
    /// </summary>
    public async Task<UserProfileDto> ConsumeAppHandoff(string code)
    {
        if (!BackendConfig.IsConfigured())
        {
            throw new ApiError.NotConfiguredError();
        }
        var auth = await _api.ConsumeAppHandoff(code);
        // AuthResult deserializes straight from the body, so a 200 that carries no token yields a blank
        // one. Handing that to OnAuthenticated used to flip the UI to "signed in" over a session store
        // that stayed empty. Treat it as what it is: a reply we could not use.
        if (auth.Token.IsNullOrEmpty())
        {
            throw new ApiError.Parse();
        }
        AccountSession.OnAuthenticated(auth.Token, auth.Client);
        return auth.Client;
    }

    /// <summary>Completes a 2FA login; persists the session and returns the profile. Throws ApiError.</summary>
    public async Task<UserProfileDto> Submit2Fa(string tempToken, string code)
    {
        if (!BackendConfig.IsConfigured())
        {
            throw new ApiError.NotConfiguredError();
        }
        var auth = await _api.Login2Fa(tempToken, code);
        // Same guard as ConsumeAppHandoff: a 200 without a token is not a session.
        if (auth.Token.IsNullOrEmpty())
        {
            throw new ApiError.Parse();
        }
        AccountSession.OnAuthenticated(auth.Token, auth.Client);
        return auth.Client;
    }

    /// <summary>
    /// Email+password registration (mirrors <see cref="BeginTelegramLogin"/>'s emit/poll shape). Two
    /// backend outcomes:
    ///  • verification OFF → a session is issued immediately → persist + <see cref="LoginState.Success"/>
    ///    (the exact path email login uses today);
    ///  • verification ON  → <see cref="LoginState.AwaitingEmailVerification"/>, then poll login with the
    ///    entered password every ~4s (bounded ~10 min, cancellable) — the moment the user clicks the
    ///    emailed link the account gains a password and login starts succeeding → persist + Success.
    /// Errors surface as <see cref="LoginState.Error"/>. Also reused verbatim as the «отправить снова»
    /// action (a fresh call re-sends the verification email and restarts the poll).
    /// </summary>
    public async Task BeginRegister(string email, string password, Action<LoginState> emit, CancellationToken cancellationToken)
    {
        if (!BackendConfig.IsConfigured())
        {
            emit(new LoginState.Error(new ApiError.NotConfiguredError()));
            return;
        }

        RegisterResult result;
        try
        {
            result = await _api.Register(email, password);
        }
        catch (ApiError e)
        {
            emit(new LoginState.Error(e));
            return;
        }

        switch (result)
        {
            case RegisterResult.Success success:
                AccountSession.OnAuthenticated(success.Token, success.Client);
                emit(new LoginState.Success(await MarkOnboardingCompleted(success.Client)));
                return;
            case RegisterResult.RequiresVerification:
                emit(new LoginState.AwaitingEmailVerification(email));
                await PollUntilVerified(email, password, emit, cancellationToken);
                return;
        }
    }

    /// <summary>
    /// Polls login with the just-registered credentials until the emailed verification is clicked (login
    /// flips from unauthorized → success), the deadline passes, or the flow is cancelled. A failed attempt
    /// (still unverified / transient) is swallowed — we simply keep waiting. On timeout we stop quietly and
    /// leave the pending screen up (the user can «отправить снова» or return); no error is invented.
    /// </summary>
    private async Task PollUntilVerified(string email, string password, Action<LoginState> emit, CancellationToken cancellationToken)
    {
        var pollInterval = TimeSpan.FromSeconds(4);
        var deadline = DateTime.UtcNow.AddMinutes(10);

        while (DateTime.UtcNow < deadline)
        {
            try
            {
                await Task.Delay(pollInterval, cancellationToken);
            }
            catch (OperationCanceledException)
            {
                return;
            }

            LoginResult result;
            try
            {
                result = await _api.Login(email, password);
            }
            catch (ApiError)
            {
                // Not verified yet (401/403) or a transient blip — keep polling.
                continue;
            }

            if (cancellationToken.IsCancellationRequested)
            {
                return;
            }

            if (result is LoginResult.Success success)
            {
                AccountSession.OnAuthenticated(success.Token, success.Client);
                //  Тот же вход, что и у обычной почты, но добрался сюда ТОЛЬКО зарегистрировавшийся:
                //  этот цикл живёт лишь внутри BeginRegister. Значит, отметку о первом входе ставим
                //  здесь же — см. MarkOnboardingCompleted.
                emit(new LoginState.Success(await MarkOnboardingCompleted(success.Client)));
                return;
            }
            // A freshly registered account cannot have TOTP, so Requires2Fa is not expected here; ignore
            // it and keep waiting rather than stranding the user on a code prompt they cannot satisfy.
        }
    }

    /// <summary>
    /// <b>Отмечает первый вход завершённым у аккаунта, который ТОЛЬКО ЧТО зарегистрировался.</b>
    ///
    /// Оба пути регистрации панели — <c>/client/auth/register</c> при выключенной проверке почты и
    /// <c>/client/auth/verify-email</c> по ссылке из письма — создают клиента с
    /// <c>onboardingCompleted: false</c>, хотя пароль у него уже есть. А set-password панель
    /// отклоняет только при <c>passwordHash &amp;&amp; onboardingCompleted</c>: без этой отметки
    /// <see cref="UserProfileExtensions.CanSetPassword"/> для такого аккаунта остаётся истинным
    /// НАВСЕГДА, и панель до скончания века принимает повторную установку пароля.
    ///
    /// Правила те же, что после <c>set-password</c>: обе неудачи проглатываются (сессия уже выдана,
    /// назвать неудачу довеска неудачей входа значило бы соврать), профиль перечитывается ПОСЛЕ
    /// отметки, чтобы кэш и возвращаемый профиль несли свежий флаг. Запрос идёт через <c>_api</c>, а
    /// не через хранилище: 401 на пути опознания завершает сессию, и умерший в этот момент токен не
    /// должен выкидывать человека из только что созданного аккаунта.
    /// </summary>
    /// <returns>Свежий профиль, если его удалось перечитать, иначе тот, что пришёл с сессией.</returns>
    private async Task<UserProfileDto> MarkOnboardingCompleted(UserProfileDto profile)
    {
        try
        {
            await _api.CompleteOnboarding();
        }
        catch (Exception ex)
        {
            Logging.SaveLog("AuthManager.CompleteOnboarding", ex);
        }

        try
        {
            var fresh = await _api.GetMe();
            AccountSession.UpdateProfile(fresh);
            return fresh;
        }
        catch (Exception ex)
        {
            Logging.SaveLog("AuthManager.CompleteOnboarding", ex);
            return profile;
        }
    }

    /// <summary>
    /// <b>Attaching an email to the account that is already signed in.</b>
    ///
    /// The errand a Telegram-only account arrives with: it has no address, so «Способы входа» has
    /// nothing to report on its email row and the panel has no second way to let that person back in.
    /// <c>POST /client/link-email-request</c> carries the address ALONE — the Bearer token already says
    /// which account is asking, and no password is set here (that is <see cref="SetPassword"/>'s job,
    /// and until it runs the address is an identifier the user cannot sign in with).
    ///
    /// Shaped like <see cref="BeginRegister"/> and for the same reason: the errand does not end with the
    /// request. A 200 only means a letter is out, and the wait that follows is the user's, not the
    /// network's — so the state moves to <see cref="LoginState.AwaitingEmailVerification"/> and the flow
    /// sits on <see cref="PollUntilProfile"/> until the link is opened. Also serves as the «отправить
    /// снова» action: a fresh call re-sends the letter and restarts the poll.
    /// </summary>
    public async Task BeginLinkEmail(string email, Action<LoginState> emit, CancellationToken cancellationToken)
    {
        if (!BackendConfig.IsConfigured())
        {
            emit(new LoginState.Error(new ApiError.NotConfiguredError()));
            return;
        }
        try
        {
            await _api.RequestLinkEmail(email);
        }
        catch (ApiError e)
        {
            emit(new LoginState.Error(e));
            return;
        }
        emit(new LoginState.AwaitingEmailVerification(email));

        // The address the letter went to is deliberately NOT compared against the one that comes back:
        // the panel owns that value and hands it back normalised (trimmed, lower-cased), so an equality
        // test would wait forever for a link that was opened correctly. An account that HAS an address
        // where it had none is the whole signal.
        await PollUntilProfile(profile => profile.Email.IsNotEmpty(), emit, cancellationToken);
    }

    /// <summary>
    /// <b>Replacing the address already attached to this account.</b>
    ///
    /// A different endpoint from <see cref="BeginLinkEmail"/> and a different guard: the panel refuses
    /// <c>/link-email-request</c> outright once an address exists («Почта уже привязана»), and asks
    /// <c>/profile/change-email/request</c> for the CURRENT PASSWORD whenever the account has one —
    /// otherwise anyone holding a borrowed session could move the account to their own address. The
    /// two refusals that guard says (400 <c>PASSWORD_REQUIRED</c>, 401 <c>INVALID_PASSWORD</c>) reach
    /// the caller as-is; separating them is the screen's job.
    ///
    /// The confirmation is the same letter with the same link, so the wait is the same wait — but what
    /// it watches for differs. The profile already carries an address, so «non-blank» would be true on
    /// the very first poll; what changes when the link is opened is the address ITSELF, and that is
    /// what <paramref name="previousEmail"/> is compared against. Compared against the OLD one, never
    /// the new one, for the same normalisation reason as <see cref="BeginLinkEmail"/>.
    /// </summary>
    public async Task BeginChangeEmail(
        string newEmail,
        string? currentPassword,
        string previousEmail,
        Action<LoginState> emit,
        CancellationToken cancellationToken)
    {
        if (!BackendConfig.IsConfigured())
        {
            emit(new LoginState.Error(new ApiError.NotConfiguredError()));
            return;
        }
        try
        {
            await _api.RequestChangeEmail(newEmail, currentPassword);
        }
        catch (ApiError e)
        {
            emit(new LoginState.Error(e));
            return;
        }
        emit(new LoginState.AwaitingEmailVerification(newEmail));
        await PollUntilProfile(
            profile => profile.Email.IsNotEmpty()
                && !string.Equals(profile.Email.Trim(), previousEmail.Trim(), StringComparison.OrdinalIgnoreCase),
            emit,
            cancellationToken);
    }

    /// <summary>
    /// Watches for the emailed link being opened by asking the one question whose answer changes when
    /// it is: the PROFILE. Registration polls the login instead (the account it is waiting on does not
    /// exist yet); these errands are run BY a session, so <c>/client/auth/me</c> is what moves.
    ///
    /// Two things here are load-bearing.
    ///
    /// First, <b>every failure is swallowed</b>, exactly as in <see cref="PollUntilVerified"/>: an
    /// unread letter is not news, and neither is a network blip. On timeout the flow simply stops and
    /// leaves the waiting screen up — «отправить снова» and «назад» are both still there, and inventing
    /// an error for a letter that may be read a minute later would be a lie.
    ///
    /// Second, the poll asks <see cref="_api"/> DIRECTLY rather than going through
    /// <see cref="AccountRepository.RefreshProfile"/>: a 401 on the identity endpoint ENDS THE SESSION
    /// there, and a seven-day token that died while a letter was in flight must not sign the user out
    /// from inside a background poll they cannot see. They would come back from the letter to a
    /// logged-out app and no idea why.
    ///
    /// The profile is written to <see cref="AccountSession"/> before the state is emitted, so the row
    /// that sent the user here reports the new address from cache rather than from a lucky reload.
    /// </summary>
    private async Task PollUntilProfile(
        Func<UserProfileDto, bool> attached,
        Action<LoginState> emit,
        CancellationToken cancellationToken)
    {
        var pollInterval = TimeSpan.FromSeconds(4);
        var deadline = DateTime.UtcNow.AddMinutes(10);

        while (DateTime.UtcNow < deadline)
        {
            try
            {
                await Task.Delay(pollInterval, cancellationToken);
            }
            catch (OperationCanceledException)
            {
                return;
            }

            UserProfileDto profile;
            try
            {
                profile = await _api.GetMe();
            }
            catch (ApiError)
            {
                continue; // Not opened yet, or a blip. Neither is news.
            }

            if (cancellationToken.IsCancellationRequested)
            {
                return;
            }

            if (attached(profile))
            {
                AccountSession.UpdateProfile(profile);
                emit(new LoginState.EmailAttached(profile));
                return;
            }
        }
    }

    /// <summary>
    /// Requests a passwordless magic sign-in link for <paramref name="email"/> and emits
    /// <see cref="LoginState.MagicLinkSent"/>. Deliberately does NOT poll: the link is consumed in the
    /// system browser and there is no in-app return callback (a URL-scheme / loopback receiver would live
    /// in App startup, which this integration does not own), so a spinner here could never resolve. The
    /// backend reply is anti-enumeration (same message whether or not the address exists), so the copy is
    /// framed conditionally.
    /// </summary>
    public async Task BeginMagicLink(string email, Action<LoginState> emit)
    {
        if (!BackendConfig.IsConfigured())
        {
            emit(new LoginState.Error(new ApiError.NotConfiguredError()));
            return;
        }
        try
        {
            await _api.RequestMagicLink(email);
        }
        catch (ApiError e)
        {
            emit(new LoginState.Error(e));
            return;
        }
        emit(new LoginState.MagicLinkSent(email));
    }

    /// <summary>
    /// Requests a password-reset link for <paramref name="email"/> and emits
    /// <see cref="LoginState.PasswordResetSent"/>. The reset itself is completed in the browser; the user
    /// then returns and signs in with the new password (no in-app poll — same rationale as the magic link).
    /// </summary>
    public async Task BeginPasswordReset(string email, Action<LoginState> emit)
    {
        if (!BackendConfig.IsConfigured())
        {
            emit(new LoginState.Error(new ApiError.NotConfiguredError()));
            return;
        }
        try
        {
            await _api.RequestPasswordReset(email);
        }
        catch (ApiError e)
        {
            emit(new LoginState.Error(e));
            return;
        }
        emit(new LoginState.PasswordResetSent(email));
    }
}
