using ServiceLib.Handler.SysProxy;
using v2rayN.Desktop.Account.Dto;

namespace v2rayN.Desktop.Account;

/// <summary>Logged-in/out account state, observed by the UI. Port of V2rayNG auth/AccountSession.kt.</summary>
public abstract record AccountState
{
    public sealed record LoggedOut : AccountState;

    public sealed record LoggedIn(UserProfileDto Profile) : AccountState;
}

/// <summary>
/// Single source of truth for the logged-in/out account state. Seeded from <see cref="AuthTokenStore"/>
/// on first access so a returning user is already "logged in". Mutations keep the persisted session
/// and the in-memory <see cref="State"/> consistent and raise <see cref="StateChanged"/>.
/// </summary>
public static class AccountSession
{
    private static readonly object _lock = new();
    private static readonly SubscriptionSyncManager _subs = new();
    private static AccountState _state = Seed();

    /// <summary>Raised (on the caller's thread) whenever the account state changes.</summary>
    public static event Action<AccountState>? StateChanged;

    public static AccountState State
    {
        get
        {
            lock (_lock)
            {
                return _state;
            }
        }
    }

    private static AccountState Seed() =>
        AuthTokenStore.IsLoggedIn()
            ? new AccountState.LoggedIn(AuthTokenStore.GetUser() ?? new UserProfileDto())
            : new AccountState.LoggedOut();

    public static bool IsLoggedIn() => AuthTokenStore.IsLoggedIn();

    #region chip identity (read by the Home account chip)

    /// <summary>The current logged-in profile, or null when logged out. Seeded from the token store.</summary>
    public static UserProfileDto? CurrentProfile => (State as AccountState.LoggedIn)?.Profile;

    /// <summary>
    /// The account's display name for the Home chip: Telegram username as «@handle», else the Telegram
    /// display name, else the email. Blank when logged out. Identical precedence to the Account screen.
    /// </summary>
    public static string DisplayName => DisplayNameFor(CurrentProfile);

    /// <summary>Single uppercase monogram for the chip avatar (first letter of <see cref="DisplayName"/>).</summary>
    public static string AvatarInitial
    {
        get
        {
            var name = DisplayName.Trim().TrimStart('@');
            return name.Length > 0 ? name.Substring(0, 1).ToUpperInvariant() : string.Empty;
        }
    }

    /// <summary>Display-name precedence shared with the Account screen: @username → telegramName → email.</summary>
    public static string DisplayNameFor(UserProfileDto? profile)
    {
        if (profile == null)
        {
            return string.Empty;
        }
        if (profile.TelegramUsername.IsNotEmpty())
        {
            return $"@{profile.TelegramUsername}";
        }
        if (profile.TelegramName.IsNotEmpty())
        {
            return profile.TelegramName!;
        }
        return profile.Email.IsNotEmpty() ? profile.Email : string.Empty;
    }

    #endregion chip identity

    /// <summary>
    /// Persist a freshly issued session and flip to LoggedIn.
    ///
    /// A BLANK jwt is rejected outright. <see cref="IsLoggedIn"/> asks the token store while
    /// <see cref="State"/> answers from memory, so persisting an empty token and flipping the state
    /// anyway left the two permanently disagreeing: the shell rendered the account screen (state says
    /// LoggedIn) while every request went out unauthenticated and every gate that asks
    /// <c>IsLoggedIn()</c> — the cache, the shell's login gate, the sync — read "signed out". The
    /// endpoints that can produce it are the ones whose reply is not pre-validated: 2FA login and the
    /// site→app handoff both deserialize into <see cref="AuthResult"/>, whose Token defaults to "" when
    /// the backend answers 200 without one. Callers already handle <see cref="ApiError"/>.
    /// </summary>
    public static void OnAuthenticated(string jwt, UserProfileDto profile)
    {
        if (jwt.IsNullOrEmpty())
        {
            throw new ApiError.Parse();
        }
        AuthTokenStore.SaveSession(jwt, user: profile);
        SetState(new AccountState.LoggedIn(profile));
    }

    /// <summary>Refresh the cached profile (e.g. after GET /client/auth/me).</summary>
    public static void UpdateProfile(UserProfileDto profile)
    {
        AuthTokenStore.SaveUser(profile);
        if (IsLoggedIn())
        {
            SetState(new AccountState.LoggedIn(profile));
        }
    }

    /// <summary>
    /// Clear session + managed subscriptions and flip to LoggedOut. EXPLICIT USER LOGOUT ONLY — this
    /// is the one path allowed to delete the account's subscriptions and their servers. A dead JWT
    /// takes <see cref="EndSession"/>; a 403, or a 401 on any endpoint other than the identity one,
    /// must not end the session at all.
    /// </summary>
    public static async Task Wipe()
    {
        // Logout must not leave the VPN running against a subscription we are about to delete: stop
        // the core (and clear the system proxy so the user keeps internet) BEFORE removing the subs.
        await StopEngine();
        await _subs.RemoveAllManaged();
        AuthTokenStore.Clear();
        // Drop the in-memory account data with the account itself. AccountCache only evicts lazily, on
        // a READ taken while signed out — so signing straight back in as somebody else (no read in
        // between) handed the new account the previous one's cached data, and the payments entry is
        // keyed globally, not per user. Clearing here closes that window for every exit path.
        AccountCache.InvalidateAll();
        SetState(new AccountState.LoggedOut());
    }

    /// <summary>
    /// Ends the session WITHOUT touching the user's subscriptions — what a dead 7-day JWT gets
    /// (a 401 from the identity endpoint, via <see cref="AccountRepository.RefreshProfile"/>).
    ///
    /// An expired token is not the user asking to give up their subscriptions. Routing that case
    /// through <see cref="Wipe"/> ran <see cref="SubscriptionSyncManager.RemoveAllManaged"/> →
    /// <c>ConfigHandler.DeleteSubItem</c> → <c>RemoveServersViaSubid</c>, which deleted every
    /// account-imported subscription AND every server behind it, permanently, the first time the
    /// Account tab or the Devices screen noticed the token had aged out. Nothing on the machine could
    /// bring them back. The servers keep working (their own credentials are unrelated to the JWT), so
    /// the engine is left running too; the user simply has to sign in again, and because
    /// <see cref="AuthTokenStore.ClearSession"/> keeps the uuid-&gt;guid map that sign-in refreshes the
    /// same subscriptions in place instead of duplicating them.
    /// </summary>
    public static void EndSession()
    {
        AuthTokenStore.ClearSession();
        AccountCache.InvalidateAll();
        SetState(new AccountState.LoggedOut());
    }

    /// <summary>
    /// Disconnects the VPN: stops any running core and force-disables the system proxy. Best-effort —
    /// a failure here must never block the session wipe. Mirrors HomeViewModel.Disconnect so the Home
    /// shield (which polls the core state) flips back to disconnected.
    /// </summary>
    private static async Task StopEngine()
    {
        try
        {
            await CoreManager.Instance.CoreStop();
            await SysProxyHandler.UpdateSysProxy(AppManager.Instance.Config, true);
        }
        catch (Exception ex)
        {
            Logging.SaveLog("AccountSession.StopEngine", ex);
        }
    }

    private static void SetState(AccountState state)
    {
        lock (_lock)
        {
            _state = state;
        }
        StateChanged?.Invoke(state);
    }
}
