using v2rayN.Desktop.Account.Dto;

namespace v2rayN.Desktop.Account;

/// <summary>
/// Wrapper over <see cref="IDepartamentApiClient"/> that returns <see cref="ApiResult{T}"/> instead of
/// throwing, maps every failure to an <see cref="ApiError"/>, and performs the higher-level account
/// operations the UI needs. Port of V2rayNG auth/AccountRepository.kt.
///
/// The local session is ENDED via <see cref="AccountSession.EndSession"/> ONLY when the identity
/// endpoint (<see cref="IDepartamentApiClient.GetMe"/>, via <see cref="RefreshProfile"/>) returns 401 —
/// the single reliable "the 7-day JWT is dead" signal. That path deliberately deletes nothing: only an
/// explicit user logout runs <see cref="AccountSession.Wipe"/>. A 401/403 on any OTHER endpoint
/// surfaces as a plain error and never touches the session.
/// </summary>
public sealed class AccountRepository
{
    private readonly IDepartamentApiClient _api;
    private readonly SubscriptionSyncManager _subs;

    public AccountRepository(IDepartamentApiClient? api = null, SubscriptionSyncManager? subs = null)
    {
        _api = api ?? new DepartamentApiClient();
        _subs = subs ?? new SubscriptionSyncManager(_api);
    }

    /// <summary>
    /// Runs an authenticated API call and normalises failures to <see cref="ApiResult{T}"/>.
    /// Deliberately does NOT wipe the session on <see cref="ApiError.Unauthorized"/>: only
    /// <see cref="RefreshProfile"/> (the identity endpoint) is allowed to wipe.
    /// </summary>
    private static async Task<ApiResult<T>> Guard<T>(Func<Task<T>> block)
    {
        try
        {
            return ApiResult<T>.Success(await block());
        }
        catch (ApiError e)
        {
            return ApiResult<T>.Failure(e);
        }
        catch (Exception e)
        {
            return ApiResult<T>.Failure(new ApiError.NetworkError(e));
        }
    }

    // Public catalog / status
    public Task<ApiResult<PublicConfigDto>> LoadPublicConfig() => Guard(() => _api.GetPublicConfig());

    public Task<ApiResult<TariffCatalogDto>> LoadCatalog() => Guard(() => _api.GetPublicTariffs());

    public Task<ApiResult<List<ServerStatusDto>>> LoadServerStatus() => Guard(() => _api.GetServerStatus());

    // Profile
    //
    // GetMe() is the authoritative identity check: it is the ONLY endpoint whose 401 reliably means the
    // JWT is dead. A 401 here — and only here — ENDS the local session so an expired 7-day token
    // self-heals into a logged-out state. Every other failure leaves the session intact.
    //
    // EndSession, NOT Wipe. Wipe is the explicit-logout teardown: it stops the engine and runs
    // RemoveAllManaged, which deletes every account-imported subscription and — through
    // DeleteSubItem -> RemoveServersViaSubid — every server behind it, with nothing on the machine able
    // to restore them. Calling it from here handed that teardown to a token that had merely aged past
    // its seven days: the user opened the Account tab (StartupLoad -> RefreshProfile) or the Devices
    // screen, the 401 came back, and their whole server list vanished. An expired token is not a
    // request to give up the subscriptions, so the session ends and the subscriptions stay.
    public async Task<ApiResult<UserProfileDto>> RefreshProfile()
    {
        try
        {
            var profile = await _api.GetMe();
            AccountSession.UpdateProfile(profile);
            return ApiResult<UserProfileDto>.Success(profile);
        }
        catch (ApiError.Unauthorized e)
        {
            // Ending the session is the single most disruptive thing this app does to itself, so the
            // 401 has to be OURS. A captive portal answers every request with 401 and an HTML page —
            // hotel, airport and office wifi, i.e. precisely the networks a VPN user is on — and taking
            // that as «твой токен мёртв» signed the user out of an account whose token was fine. The
            // owner's rule is that a session ends when the user ends it; a middlebox is not the user.
            //
            // What this CANNOT fix: the token is a 7-day JWT with no refresh endpoint, so once the
            // backend itself rejects it the app has nothing left to present. Making a session outlive
            // seven days needs a rolling or refreshable token from the panel — a server-side change.
            if (e.FromApi)
            {
                AccountSession.EndSession();
            }
            return ApiResult<UserProfileDto>.Failure(e);
        }
        catch (ApiError e)
        {
            return ApiResult<UserProfileDto>.Failure(e);
        }
        catch (Exception e)
        {
            return ApiResult<UserProfileDto>.Failure(new ApiError.NetworkError(e));
        }
    }

    // Subscriptions
    /// <summary>The authoritative ACTIVE (root) subscription — /all often returns no root item.</summary>
    public Task<ApiResult<PrimarySubscriptionDto>> LoadPrimarySubscription() => Guard(() => _api.GetPrimarySubscription());

    public Task<ApiResult<SubscriptionAllDto>> LoadSubscriptions() => Guard(() => _api.GetSubscriptionAll());

    /// <summary>
    /// Fetches the account's subscriptions and imports them into the local plumbing; returns local
    /// guids. The GET + URL resolution lives in <see cref="SubscriptionSyncManager.ImportAll"/> because
    /// the real connect URL comes from the PRIMARY summary, not the /all items.
    /// </summary>
    public Task<ApiResult<List<string>>> AutoImportSubscriptions() => Guard(() => _subs.ImportAll());

    public Task<ApiResult<bool>> RenameSubscription(string scope, string id, string name) =>
        Guard(async () => { await _api.RenameSubscription(scope, id, name); return true; });

    public Task<ApiResult<byte[]>> GetQr(string remnawaveUuid) => Guard(() => _api.GetSubscriptionQr(remnawaveUuid));

    // Purchase / renew / upgrade / devices
    public Task<ApiResult<PaymentInitDto>> Buy(PaymentRequestDto req) => Guard(() => _api.PayPlatega(req));

    public Task<ApiResult<PaymentResultDto>> PayWithBalance(PaymentRequestDto req) => Guard(() => _api.PayBalance(req));

    /// <summary>Renew is a Platega purchase of the given tariff/price-option for an existing subscription.</summary>
    public Task<ApiResult<PaymentInitDto>> Renew(PaymentRequestDto req) => Guard(() => _api.PayPlatega(req));

    /// <summary>Scoped card renewal of a chosen (root/secondary) subscription — POST /payments/tariff/platega.</summary>
    public Task<ApiResult<PaymentInitDto>> RenewTariffCard(PaymentRequestDto req) => Guard(() => _api.PayTariffPlatega(req));

    public Task<ApiResult<UpgradeQuoteDto>> UpgradeQuote(string targetTariffId) => Guard(() => _api.GetUpgradeQuote(targetTariffId));

    public Task<ApiResult<PaymentInitDto>> Upgrade(string targetTariffId, string method, string subscriptionUuid, string? paymentMethod = null) =>
        Guard(() => _api.Upgrade(targetTariffId, method, subscriptionUuid, paymentMethod));

    public Task<ApiResult<PaymentInitDto>> AddDevices(string scope, string id, int extraDevices, string method, string? paymentMethod = null) =>
        Guard(() => _api.AddDevices(scope, id, extraDevices, method, paymentMethod));

    /// <summary>
    /// Pure device top-up (POST /client/subscription/{scope}/{id}/add-devices) returning the dual-shape
    /// <see cref="AddDevicesResultDto"/>: a "balance" top-up settles immediately ({ok,newDeviceLimit,
    /// newBalance}); a "platega" top-up returns a card checkout ({paymentUrl,…}) — poll GET /client/payments.
    /// </summary>
    public Task<ApiResult<AddDevicesResultDto>> PurchaseDevices(string scope, string id, int extraDevices, string method, int? paymentMethod = null) =>
        Guard(() => _api.PurchaseDevices(scope, id, extraDevices, method, paymentMethod));

    public Task<ApiResult<DevicesResult>> GetDevices(string remnawaveUuid) => Guard(() => _api.GetDevices(remnawaveUuid));

    public Task<ApiResult<bool>> DeleteDevice(string hwid, string remnawaveUuid) =>
        Guard(async () => { await _api.DeleteDevice(hwid, remnawaveUuid); return true; });

    // Payments history
    public Task<ApiResult<PaymentsDto>> GetPayments() => Guard(() => _api.GetPayments());

    // Promo / trial / auto-renew / referral
    public Task<ApiResult<PromoDto>> CheckPromo(string code) => Guard(() => _api.CheckPromo(code));

    public Task<ApiResult<bool>> ActivatePromo(string code) =>
        Guard(async () => { await _api.ActivatePromo(code); return true; });

    public Task<ApiResult<bool>> ActivateTrial() =>
        Guard(async () => { await _api.ActivateTrial(); return true; });

    public Task<ApiResult<bool>> ToggleAutoRenew(string id, bool autoRenew) =>
        Guard(async () => { await _api.SetSecondaryAutoRenew(id, autoRenew); return true; });

    /// <summary>Auto-renew of the active (root/primary) subscription — targets the id-less endpoint.</summary>
    public Task<ApiResult<bool>> TogglePrimaryAutoRenew(bool autoRenew) =>
        Guard(async () => { await _api.SetPrimaryAutoRenew(autoRenew); return true; });

    public Task<ApiResult<ReferralStatsDto>> GetReferralStats() => Guard(() => _api.GetReferralStats());

    // Account linking (attach a missing sign-in method to the current account)
    public Task<ApiResult<LinkTelegramRequestDto>> RequestLinkTelegram() => Guard(() => _api.RequestLinkTelegram());

    public Task<ApiResult<MessageResponseDto>> RequestLinkEmail(string email) => Guard(() => _api.RequestLinkEmail(email));

    /// <summary>
    /// The FIRST password of an account that has none. Goes through <see cref="Guard"/> like every
    /// other errand — its 401 is a dead token, not a verdict on the identity endpoint, so it must not
    /// end the session (only <see cref="RefreshProfile"/> may do that).
    /// </summary>
    public Task<ApiResult<MessageResponseDto>> SetPassword(string newPassword) => Guard(() => _api.SetPassword(newPassword));

    /// <summary>
    /// The other half of <see cref="SetPassword"/>: marks the first sign-in finished so the panel
    /// stops accepting set-password on an account that now has a password. Guarded like the rest —
    /// its failure is never the errand's failure, the password is saved by the time it runs.
    /// </summary>
    public Task<ApiResult<bool>> CompleteOnboarding() =>
        Guard(async () => { await _api.CompleteOnboarding(); return true; });

    /// <summary>
    /// Replace an already-attached address. Note the 401 this can return is NOT a dead session: the
    /// panel answers 401 code INVALID_PASSWORD for a wrong current password. Routing it through
    /// <see cref="Guard"/> keeps that where it belongs — a refusal for the caller to show, never a
    /// reason to sign anybody out.
    /// </summary>
    public Task<ApiResult<MessageResponseDto>> RequestChangeEmail(string newEmail, string? currentPassword) =>
        Guard(() => _api.RequestChangeEmail(newEmail, currentPassword));

    /// <summary>App↔site SSO handoff — mint a code, open the site's tg-login page already signed in.</summary>
    public Task<ApiResult<AppHandoffDto>> CreateAppHandoff() => Guard(() => _api.CreateAppHandoff());
}
