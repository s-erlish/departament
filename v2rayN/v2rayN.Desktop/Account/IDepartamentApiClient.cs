using v2rayN.Desktop.Account.Dto;

namespace v2rayN.Desktop.Account;

/// <summary>
/// Pluggable client for the Departament backend. Every method throws <see cref="ApiError"/> on failure
/// (including <see cref="ApiError.NotConfiguredError"/> when the base URL is blank). The JWT + HWID
/// headers are injected by the message handler, so no token param. Port of V2rayNG
/// auth/DepartamentApiClient.kt.
/// </summary>
public interface IDepartamentApiClient
{
    // Public
    Task<PublicConfigDto> GetPublicConfig();
    Task<TariffCatalogDto> GetPublicTariffs();
    Task<List<ServerStatusDto>> GetServerStatus();

    // Auth
    Task<TelegramTokenDto> CreateTelegramLoginToken();
    Task<TelegramCheckResult> CheckTelegramLogin(string token);
    Task<LoginResult> Login(string email, string password);
    Task<AuthResult> Login2Fa(string tempToken, string code);
    Task<AuthResult> LoginGoogle(string idToken, string? referralCode = null);
    Task<UserProfileDto> GetMe();

    // Auth — start-page register + passwordless flows
    Task<RegisterResult> Register(string email, string password, string? referralCode = null);
    Task<LoginResult> VerifyEmail(string token);
    Task<MessageResponseDto> RequestMagicLink(string email);
    Task<LoginResult> ConsumeMagicLink(string token, string? referralCode = null);
    Task<MessageResponseDto> RequestPasswordReset(string email);
    Task<MessageResponseDto> ConsumePasswordReset(string token, string newPassword);

    // Auth — app↔site SSO handoff
    Task<AppHandoffDto> CreateAppHandoff();
    Task<AuthResult> ConsumeAppHandoff(string code);

    // Account linking (authed): attach a missing sign-in method to the current account
    Task<LinkTelegramRequestDto> RequestLinkTelegram();
    Task<MessageResponseDto> RequestLinkEmail(string email);
    Task<MessageResponseDto> SetPassword(string newPassword);

    /// <summary>Sets `onboardingCompleted` on the account. Always called straight after a successful
    /// <see cref="SetPassword"/> and never on its own: together they are what «пароль задан» means to
    /// the panel (its set-password gate reads BOTH flags).</summary>
    Task CompleteOnboarding();

    /// <summary>Replace an already-attached address; <paramref name="currentPassword"/> is required
    /// by the panel exactly when the account has one (see <see cref="UserProfileDto.HasPassword"/>).</summary>
    Task<MessageResponseDto> RequestChangeEmail(string newEmail, string? currentPassword);

    Task<UserProfileDto> LinkGoogle(string idToken);

    // Subscription
    Task<PrimarySubscriptionDto> GetPrimarySubscription();
    Task<SubscriptionAllDto> GetSubscriptionAll();
    Task RenameSubscription(string scope, string id, string name);
    Task<byte[]> GetSubscriptionQr(string remnawaveUuid);
    Task<PaymentInitDto> AddDevices(string scope, string id, int extraDevices, string method, string? paymentMethod = null);

    /// <summary>Pure device top-up. Returns {ok,newDeviceLimit,newBalance} for "balance" or a card
    /// checkout {paymentUrl,...} for "platega".</summary>
    Task<AddDevicesResultDto> PurchaseDevices(string scope, string id, int extraDevices, string method, int? paymentMethod = null);

    Task<UpgradeQuoteDto> GetUpgradeQuote(string targetTariffId);
    Task<PaymentInitDto> Upgrade(string targetTariffId, string method, string subscriptionUuid, string? paymentMethod = null);

    // Devices
    Task<DevicesResult> GetDevices(string remnawaveUuid);
    Task DeleteDevice(string hwid, string remnawaveUuid);

    // Payments
    Task<PaymentInitDto> PayPlatega(PaymentRequestDto req);

    /// <summary>Scoped card renewal/purchase of a chosen (root/secondary) subscription → {paymentUrl,...}.</summary>
    Task<PaymentInitDto> PayTariffPlatega(PaymentRequestDto req);

    Task<PaymentResultDto> PayBalance(PaymentRequestDto req);
    Task<PaymentsDto> GetPayments();

    // Promo / trial / referral
    Task<PromoDto> CheckPromo(string code);
    Task ActivatePromo(string code);
    Task ActivateTrial();
    Task SetSecondaryAutoRenew(string id, bool autoRenew);
    Task SetPrimaryAutoRenew(bool autoRenew);
    Task<ReferralStatsDto> GetReferralStats();
}
