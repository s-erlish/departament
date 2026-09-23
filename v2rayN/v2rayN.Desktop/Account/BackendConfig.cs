namespace v2rayN.Desktop.Account;

/// <summary>
/// Central configuration for the Departament VPN backend + Telegram bot. Ported from V2rayNG
/// auth/BackendConfig.kt (values baked from build.gradle.kts buildConfigField defaults, since the
/// desktop build has no BuildConfig).
///
/// Base URL is `https://web.departament.site/api`; every path in <see cref="Endpoints"/> is relative
/// to it. JWT auth uses `Authorization: Bearer &lt;token&gt;` (7-day token, NO refresh endpoint).
/// </summary>
public static class BackendConfig
{
    /// <summary>Backend base URL (no trailing slash).</summary>
    public static string BaseUrl => "https://web.departament.site/api".TrimEnd('/');

    /// <summary>Telegram bot username without the leading '@'.</summary>
    public static string BotUsername => "departamentvpnbot";

    /// <summary>User-Agent used for API + subscription requests (negotiates the response format).</summary>
    public static string SubscriptionUserAgent => "DepartamentVPN/1.0";

    /// <summary>True only when a backend base URL has been provided.</summary>
    public static bool IsConfigured() => BaseUrl.IsNotEmpty();

    /// <summary>Relative paths (appended to <see cref="BaseUrl"/>). Parameterized paths use helpers.</summary>
    public static class Endpoints
    {
        // Public
        public const string PublicConfig = "/public/config";
        public const string PublicTariffs = "/public/tariffs";
        public const string ServerStatus = "/public/server-status";

        // Auth
        public const string TelegramLoginToken = "/client/auth/telegram-login-token";
        public const string TelegramLoginCheck = "/client/auth/telegram-login-check";
        public const string Login = "/client/auth/login";
        public const string TwoFaLogin = "/client/auth/2fa-login";
        public const string GoogleLogin = "/client/auth/google";
        public const string Me = "/client/auth/me";

        // Auth — start-page (email/password) register + passwordless flows
        public const string Register = "/client/auth/register";
        public const string VerifyEmail = "/client/auth/verify-email";
        public const string MagicLinkRequest = "/client/auth/magic-link/request";
        public const string MagicLinkConsume = "/client/auth/magic-link/consume";
        public const string PasswordResetRequest = "/client/auth/password-reset/request";
        public const string PasswordResetConsume = "/client/auth/password-reset/consume";

        // Auth — app↔site SSO handoff (issue code while authed, consume it publicly)
        public const string AppHandoff = "/client/auth/app-handoff";
        public const string AppHandoffConsume = "/client/auth/app-handoff/consume";

        // Account linking (all authed; attach a missing sign-in method to the current account)
        public const string LinkTelegramRequest = "/client/link-telegram-request";

        /// <summary>
        /// Attach an address to the session already in flight: {email} in, a letter carrying a LINK
        /// out. Deliberately NOT under /client/auth — the panel puts it on the client root, because it
        /// is an errand of an account that exists rather than a way of getting one.
        ///
        /// There is no confirmation endpoint here on purpose. The link in the letter opens the SITE,
        /// and the site calls /client/auth/verify-link-email with the token in it; the app never sees
        /// that token and never posts it. What the app watches instead is <see cref="Me"/>, which
        /// starts answering with a non-blank `email` the moment the link is opened.
        /// </summary>
        public const string LinkEmailRequest = "/client/link-email-request";

        /// <summary>
        /// The FIRST password of an account that has none — {newPassword}, minimum SIX characters
        /// (the registration endpoint's eight belongs to registration; this one is the panel's
        /// setPasswordSchema). Needed because <see cref="LinkEmailRequest"/> writes an address and
        /// nothing else: without this the linked address is an identifier the user cannot sign in with.
        /// </summary>
        public const string SetPassword = "/client/set-password";

        /// <summary>
        /// Marks the account's first sign-in as finished — no body of its own (the panel parses JSON
        /// on this route, so an empty object is sent, not an empty entity). Always the second half of
        /// <see cref="SetPassword"/> and never called alone: the panel refuses set-password only when
        /// `passwordHash && onboardingCompleted`, so without this flag the step stays open on an
        /// account that already has a password — walkable twice, and disagreeing with the site.
        /// </summary>
        public const string CompleteOnboarding = "/client/complete-onboarding";

        /// <summary>
        /// Replace an address that is already attached — {newEmail, currentPassword?}. Confirmed by the
        /// same emailed link as <see cref="LinkEmailRequest"/>, so the app watches <see cref="Me"/> for
        /// the address to CHANGE. The password is the panel's account-takeover guard and is required
        /// exactly when the account has one (`hasPassword`): 400 code PASSWORD_REQUIRED when it is
        /// missing, 401 code INVALID_PASSWORD when it is wrong.
        /// </summary>
        public const string ChangeEmailRequest = "/client/profile/change-email/request";

        public const string LinkGoogle = "/client/link-google";

        // Subscription
        /// <summary>The authoritative ACTIVE (root) subscription summary — richer than the /all root item.</summary>
        public const string Subscription = "/client/subscription";
        public const string SubscriptionAll = "/client/subscription/all";
        public const string SubscriptionQr = "/client/subscription/qr";
        public const string UpgradeQuote = "/client/subscriptions/upgrade-quote";
        public const string Upgrade = "/client/subscriptions/upgrade";

        public static string RenameSubscription(string scope, string id) => $"/client/subscription/{scope}/{id}/name";

        public static string AddDevices(string scope, string id) => $"/client/subscription/{scope}/{id}/add-devices";

        // Devices
        public const string Devices = "/client/devices";
        public const string DeleteDevice = "/client/devices/delete";

        // Payments
        public const string PayPlatega = "/client/payments/platega";
        public const string PayBalance = "/client/payments/balance";
        public const string Payments = "/client/payments";

        /// <summary>Scoped card (Platega) purchase/renewal of a chosen (root or secondary) subscription.</summary>
        public const string PayTariffPlatega = "/client/payments/tariff/platega";

        // Promo / trial / referral
        public const string PromoCheck = "/client/promo-code/check";
        public const string PromoActivate = "/client/promo-code/activate";
        public const string Trial = "/client/trial";
        public const string ReferralStats = "/client/referral-stats";

        /// <summary>PATCH auto-renew of a secondary subscription — body {enabled}.</summary>
        public static string SecondaryAutoRenew(string id) => $"/client/secondary-subscriptions/{id}/auto-renew";

        /// <summary>
        /// PATCH auto-renew of the ACTIVE (root/primary) subscription — no id in the path, body
        /// {enabled}. NOTE: the real route is `/client/auto-renew` (bug #29 — the former
        /// `/client/subscription/auto-renew` 404s).
        /// </summary>
        public const string PrimaryAutoRenew = "/client/auto-renew";
    }
}
