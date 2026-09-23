using System.Net.Http;
using System.Text.Json;
using v2rayN.Desktop.Account.Dto;
using Endpoints = v2rayN.Desktop.Account.BackendConfig.Endpoints;

namespace v2rayN.Desktop.Account;

/// <summary>
/// HttpClient + System.Text.Json implementation of <see cref="IDepartamentApiClient"/>. Port of
/// V2rayNG auth/DepartamentApiClientImpl.kt (OkHttp + Gson).
///
/// A single <see cref="AuthMessageHandler"/> attaches Accept, User-Agent, the Bearer JWT (from
/// <see cref="AuthTokenStore"/>) and the HWID/device headers. Every failure maps to an
/// <see cref="ApiError"/>; tokens and subscription URLs are never logged. All calls throw
/// <see cref="ApiError.NotConfiguredError"/> when the base URL is blank.
/// </summary>
public sealed class DepartamentApiClient : IDepartamentApiClient
{
    private const string HeaderHwid = "X-HWID";
    private const string HeaderDeviceOs = "x-device-os";
    private const string HeaderVerOs = "x-ver-os";
    private const string HeaderDeviceModel = "x-device-model";

    private static readonly HttpClient _http = CreateClient();

    private static HttpClient CreateClient()
    {
        // SocketsHttpHandler with a BOUNDED pooled-connection lifetime. The default is infinite: a
        // connection opened at launch is reused for the whole process life and its DNS answer is never
        // re-resolved. For a VPN client that is the wrong default twice over — the machine's routing
        // changes under the app every time the tunnel comes up or down, and the backend's address is
        // exactly the kind that gets re-pointed when an IP is blocked. Recycling every two minutes
        // makes the next request re-resolve and re-connect, instead of pinning the process to an
        // address that stopped working. Two minutes is long enough that a burst of calls (the account
        // load fires six at once) still shares its connections.
        var handler = new SocketsHttpHandler
        {
            PooledConnectionLifetime = TimeSpan.FromMinutes(2),

            // АККАУНТ НЕ ХОДИТ ЧЕРЕЗ СИСТЕМНЫЙ ПРОКСИ. По умолчанию хендлер берёт прокси операционной
            // системы — а этот самый прокси приложение САМО и переписывает на свой локальный порт в
            // режимах «Изменить системный прокси» и PAC. То есть весь аккаунтный трафик (/me, подписка,
            // тарифы, платежи, вход) уходил в собственное ядро и умирал вместе с ним: ядро не поднято —
            // «Аккаунт» не грузится, ядро упало — вход по Telegram не завершается. Клиент подписок
            // (DownloadService.CreateSubscriptionClient) системный прокси не берёт НИКОГДА — либо явно
            // заданный локальный SOCKS, либо напрямую. Приводим к одному: напрямую.
            UseProxy = false,
        };

        // …и та же политика цепочки сертификатов, что у клиента подписок. Настройка «поставщик
        // корневых сертификатов» (system / Chrome / Mozilla) действовала на загрузку подписки, но не
        // на API: с не-системным набором корней подписка качалась, а /me отвечал ошибкой TLS. Читается
        // ОДИН раз на процесс — это ровно тот срок, на который значение и живёт: менять его в этой
        // сборке негде, оно приходит из конфига при старте. Для «system» политика null — стоковая
        // проверка TLS, как и было.
        var certificateChainPolicy = CertPemManager.Instance.BuildCertificateChainPolicy();
        if (certificateChainPolicy != null)
        {
            handler.SslOptions.CertificateChainPolicy = certificateChainPolicy;
            handler.SslOptions.RemoteCertificateValidationCallback = null;
        }

        var client = new HttpClient(new AuthMessageHandler(handler))
        {
            Timeout = TimeSpan.FromSeconds(25),
        };
        return client;
    }

    /// <summary>Injects Accept / User-Agent / Bearer JWT / HWID+device headers on every request.</summary>
    private sealed class AuthMessageHandler : DelegatingHandler
    {
        public AuthMessageHandler(HttpMessageHandler inner) : base(inner)
        {
        }

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            request.Headers.TryAddWithoutValidation("Accept", "application/json");
            request.Headers.TryAddWithoutValidation("User-Agent", BackendConfig.SubscriptionUserAgent);

            var token = AuthTokenStore.GetToken();
            if (token.IsNotEmpty())
            {
                request.Headers.TryAddWithoutValidation("Authorization", $"Bearer {token}");
            }

            // Stable per-install HWID + OS/device so the panel keeps ONE device entry per machine.
            // The OS is REPORTED, not assumed: this build also ships for Linux and macOS, and the
            // hardcoded "windows" is what the panel stores and what the «Устройства» screen then reads
            // back — so a Mac listed itself as a Windows box in the user's own device list.
            request.Headers.TryAddWithoutValidation(HeaderHwid, AuthTokenStore.DeviceId());
            request.Headers.TryAddWithoutValidation(HeaderDeviceOs, CurrentDeviceOs());
            request.Headers.TryAddWithoutValidation(HeaderVerOs, Environment.OSVersion.Version.ToString());
            request.Headers.TryAddWithoutValidation(HeaderDeviceModel, Environment.MachineName);

            return await base.SendAsync(request, cancellationToken);
        }

        /// <summary>The OS this build is actually running on, in the panel's lowercase spelling.</summary>
        private static string CurrentDeviceOs() =>
            OperatingSystem.IsWindows() ? "windows"
            : OperatingSystem.IsMacOS() ? "macos"
            : OperatingSystem.IsLinux() ? "linux"
            : "desktop";
    }

    #region public

    public Task<PublicConfigDto> GetPublicConfig() => GetJson<PublicConfigDto>(Endpoints.PublicConfig);

    public Task<TariffCatalogDto> GetPublicTariffs() => GetJson<TariffCatalogDto>(Endpoints.PublicTariffs);

    public Task<List<ServerStatusDto>> GetServerStatus() => GetJson<List<ServerStatusDto>>(Endpoints.ServerStatus);

    #endregion public

    #region auth

    public Task<TelegramTokenDto> CreateTelegramLoginToken() =>
        PostJson<TelegramTokenDto>(Endpoints.TelegramLoginToken, "{}");

    public async Task<TelegramCheckResult> CheckTelegramLogin(string token)
    {
        EnsureConfigured();
        var url = $"{UrlOf(Endpoints.TelegramLoginCheck)}?token={Uri.EscapeDataString(token)}";
        var resp = await Execute(new HttpRequestMessage(HttpMethod.Get, url));
        using (resp)
        {
            var code = (int)resp.StatusCode;
            if (code == 404)
            {
                return new TelegramCheckResult.NotYet();
            }
            if (code == 410)
            {
                return new TelegramCheckResult.Expired();
            }
            if (code is >= 200 and <= 299)
            {
                var raw = Parse<TelegramCheckResponseDto>(await resp.Content.ReadAsStringAsync());
                var jwt = raw.Token;
                var client = raw.Client;
                if (raw.Confirmed && jwt.IsNotEmpty() && client != null)
                {
                    return new TelegramCheckResult.Confirmed(jwt!, client, raw.JustCreated);
                }
                return new TelegramCheckResult.NotYet();
            }
            throw MapError(code);
        }
    }

    public async Task<LoginResult> Login(string email, string password)
    {
        var raw = await PostJson<LoginResponseDto>(Endpoints.Login, Serialize(new LoginRequestDto(email, password)));
        return MapLoginResponse(raw);
    }

    public Task<AuthResult> Login2Fa(string tempToken, string code) =>
        PostJson<AuthResult>(Endpoints.TwoFaLogin, Serialize(new TwoFaLoginRequestDto(tempToken, code)));

    public Task<AuthResult> LoginGoogle(string idToken, string? referralCode = null) =>
        PostJson<AuthResult>(Endpoints.GoogleLogin, Serialize(new GoogleLoginRequestDto(idToken, referralCode)));

    public Task<UserProfileDto> GetMe() => GetJson<UserProfileDto>(Endpoints.Me);

    public async Task<RegisterResult> Register(string email, string password, string? referralCode = null)
    {
        var raw = await PostJson<RegisterResponseDto>(Endpoints.Register, Serialize(new RegisterRequestDto(email, password, referralCode)));
        if (raw.Token.IsNotEmpty() && raw.Client != null)
        {
            return new RegisterResult.Success(raw.Token!, raw.Client);
        }
        // No token → the backend sent a verification email (requiresVerification).
        return new RegisterResult.RequiresVerification(raw.Message);
    }

    public async Task<LoginResult> VerifyEmail(string token)
    {
        var raw = await PostJson<LoginResponseDto>(Endpoints.VerifyEmail, Serialize(new TokenRequestDto(token)));
        return MapLoginResponse(raw);
    }

    public Task<MessageResponseDto> RequestMagicLink(string email) =>
        PostJson<MessageResponseDto>(Endpoints.MagicLinkRequest, Serialize(new EmailRequestDto(email)));

    public async Task<LoginResult> ConsumeMagicLink(string token, string? referralCode = null)
    {
        var raw = await PostJson<LoginResponseDto>(Endpoints.MagicLinkConsume, Serialize(new MagicLinkConsumeRequestDto(token, referralCode)));
        return MapLoginResponse(raw);
    }

    public Task<MessageResponseDto> RequestPasswordReset(string email) =>
        PostJson<MessageResponseDto>(Endpoints.PasswordResetRequest, Serialize(new EmailRequestDto(email)));

    public Task<MessageResponseDto> ConsumePasswordReset(string token, string newPassword) =>
        PostJson<MessageResponseDto>(Endpoints.PasswordResetConsume, Serialize(new PasswordResetConsumeRequestDto(token, newPassword)));

    public Task<AppHandoffDto> CreateAppHandoff() =>
        PostJson<AppHandoffDto>(Endpoints.AppHandoff, "{}");

    public Task<AuthResult> ConsumeAppHandoff(string code) =>
        PostJson<AuthResult>(Endpoints.AppHandoffConsume, Serialize(new CodeRequestDto(code)));

    /// <summary>Maps a login/verify/magic-link auth body (either {token,client} or {requires2FA,tempToken}).</summary>
    private static LoginResult MapLoginResponse(LoginResponseDto raw)
    {
        var tempToken = raw.TempToken;
        var token = raw.Token;
        var client = raw.Client;
        if (raw.Requires2Fa && tempToken.IsNotEmpty())
        {
            return new LoginResult.Requires2Fa(tempToken!);
        }
        if (token.IsNotEmpty() && client != null)
        {
            return new LoginResult.Success(token!, client);
        }
        throw new ApiError.Parse();
    }

    #endregion auth

    #region account linking

    public Task<LinkTelegramRequestDto> RequestLinkTelegram() =>
        PostJson<LinkTelegramRequestDto>(Endpoints.LinkTelegramRequest, "{}");

    public Task<MessageResponseDto> RequestLinkEmail(string email) =>
        PostJson<MessageResponseDto>(Endpoints.LinkEmailRequest, Serialize(new EmailRequestDto(email)));

    public Task<MessageResponseDto> SetPassword(string newPassword) =>
        PostJson<MessageResponseDto>(Endpoints.SetPassword, Serialize(new SetPasswordRequestDto(newPassword)));

    public async Task CompleteOnboarding()
    {
        EnsureConfigured();
        // «{}», не пустое тело: панель разбирает JSON на этом маршруте, и отсутствующая сущность —
        // не то же самое, что пустой объект. Ответ не читаем — единственное, что этот вызов делает,
        // это ставит флаг.
        var req = new HttpRequestMessage(HttpMethod.Post, UrlOf(Endpoints.CompleteOnboarding))
        {
            Content = JsonBody("{}"),
        };
        await ExecuteVoid(req);
    }

    public Task<MessageResponseDto> RequestChangeEmail(string newEmail, string? currentPassword) =>
        PostJson<MessageResponseDto>(
            Endpoints.ChangeEmailRequest,
            Serialize(new ChangeEmailRequestDto(newEmail, currentPassword.NullIfEmpty())));

    public Task<UserProfileDto> LinkGoogle(string idToken) =>
        PostJson<UserProfileDto>(Endpoints.LinkGoogle, Serialize(new LinkGoogleRequestDto(idToken)));

    #endregion account linking

    #region subscription

    //  ОДИН ОТВЕТ НА ДВОИХ. При запуске и при возвращении окна после простоя импорт подписок аккаунта
    //  (SubscriptionSyncManager) и загрузка вкладки «Аккаунт» спрашивают эти два адреса подряд, с
    //  разницей в доли секунды и из РАЗНЫХ экземпляров клиента: каждый раз по два одинаковых запроса к
    //  панели. Теперь второй, пришедший, пока первый ещё идёт или только что ответил, получает тот же
    //  ответ. Окно свежести короткое: опросы после оплаты ходят реже и видят новые данные.
    private static readonly SharedGet<PrimarySubscriptionDto> _primaryShared = new();
    private static readonly SharedGet<SubscriptionAllDto> _allShared = new();

    public Task<PrimarySubscriptionDto> GetPrimarySubscription() =>
        _primaryShared.Get(() => GetJson<PrimarySubscriptionDto>(Endpoints.Subscription));

    public Task<SubscriptionAllDto> GetSubscriptionAll() =>
        _allShared.Get(() => GetJson<SubscriptionAllDto>(Endpoints.SubscriptionAll));

    /// <summary>
    /// Общий ответ одного GET: запрос в полёте или ответивший не раньше <see cref="Fresh"/> назад
    /// отдаётся всем, кто спросил. Ключ — токен сессии: ответ одного аккаунта не достаётся другому.
    /// Ошибка не запоминается: следующий спросивший идёт в сеть заново.
    /// </summary>
    private sealed class SharedGet<T>
    {
        private static readonly TimeSpan Fresh = TimeSpan.FromSeconds(3);
        private readonly object _lock = new();
        private Task<T>? _task;
        private string? _token;
        private DateTime _doneAt;

        public Task<T> Get(Func<Task<T>> fetch)
        {
            var token = AuthTokenStore.GetToken();
            lock (_lock)
            {
                if (_task is { } task && _token == token)
                {
                    if (!task.IsCompleted)
                    {
                        return task;
                    }
                    if (task.IsCompletedSuccessfully && DateTime.UtcNow - _doneAt < Fresh)
                    {
                        return task;
                    }
                }
                _token = token;
                _task = Run(fetch);
                return _task;
            }
        }

        private async Task<T> Run(Func<Task<T>> fetch)
        {
            var result = await fetch();
            lock (_lock)
            {
                _doneAt = DateTime.UtcNow;
            }
            return result;
        }
    }

    public async Task RenameSubscription(string scope, string id, string name)
    {
        EnsureConfigured();
        var req = new HttpRequestMessage(HttpMethod.Patch, UrlOf(Endpoints.RenameSubscription(scope, id)))
        {
            Content = JsonBody(Serialize(new RenameRequestDto(name))),
        };
        await ExecuteVoid(req);
    }

    public async Task<byte[]> GetSubscriptionQr(string remnawaveUuid)
    {
        EnsureConfigured();
        var url = $"{UrlOf(Endpoints.SubscriptionQr)}?uuid={Uri.EscapeDataString(remnawaveUuid)}";
        var resp = await Execute(new HttpRequestMessage(HttpMethod.Get, url));
        using (resp)
        {
            if (!resp.IsSuccessStatusCode)
            {
                throw MapError((int)resp.StatusCode);
            }
            return await resp.Content.ReadAsByteArrayAsync();
        }
    }

    public Task<PaymentInitDto> AddDevices(string scope, string id, int extraDevices, string method, string? paymentMethod = null) =>
        PostJson<PaymentInitDto>(Endpoints.AddDevices(scope, id), Serialize(new AddDevicesRequestDto(extraDevices, method, paymentMethod)));

    public Task<AddDevicesResultDto> PurchaseDevices(string scope, string id, int extraDevices, string method, int? paymentMethod = null) =>
        PostJson<AddDevicesResultDto>(Endpoints.AddDevices(scope, id), Serialize(new AddDevicesPurchaseRequestDto(extraDevices, method, paymentMethod)));

    public async Task<UpgradeQuoteDto> GetUpgradeQuote(string targetTariffId)
    {
        EnsureConfigured();
        var url = $"{UrlOf(Endpoints.UpgradeQuote)}?targetTariffId={Uri.EscapeDataString(targetTariffId)}";
        return await Call<UpgradeQuoteDto>(new HttpRequestMessage(HttpMethod.Get, url));
    }

    public Task<PaymentInitDto> Upgrade(string targetTariffId, string method, string subscriptionUuid, string? paymentMethod = null) =>
        PostJson<PaymentInitDto>(Endpoints.Upgrade, Serialize(new UpgradeRequestDto(targetTariffId, method, paymentMethod, subscriptionUuid)));

    #endregion subscription

    #region devices

    public async Task<DevicesResult> GetDevices(string remnawaveUuid)
    {
        EnsureConfigured();
        var url = $"{UrlOf(Endpoints.Devices)}?uuid={Uri.EscapeDataString(remnawaveUuid)}";
        var resp = await Execute(new HttpRequestMessage(HttpMethod.Get, url));
        using (resp)
        {
            var body = await resp.Content.ReadAsStringAsync();
            if (!resp.IsSuccessStatusCode)
            {
                throw MapError((int)resp.StatusCode, SanitizeBody(body));
            }
            var devices = Parse<DevicesDto>(body).Devices();
            return new DevicesResult(devices, (int)resp.StatusCode, SanitizeBody(body) ?? "");
        }
    }

    public async Task DeleteDevice(string hwid, string remnawaveUuid)
    {
        EnsureConfigured();
        var req = new HttpRequestMessage(HttpMethod.Post, UrlOf(Endpoints.DeleteDevice))
        {
            Content = JsonBody(Serialize(new DeleteDeviceRequestDto(hwid, remnawaveUuid))),
        };
        await ExecuteVoid(req);
    }

    #endregion devices

    #region payments

    public Task<PaymentInitDto> PayPlatega(PaymentRequestDto req) =>
        PostJson<PaymentInitDto>(Endpoints.PayPlatega, Serialize(req));

    public Task<PaymentInitDto> PayTariffPlatega(PaymentRequestDto req) =>
        PostJson<PaymentInitDto>(Endpoints.PayTariffPlatega, Serialize(req));

    public Task<PaymentResultDto> PayBalance(PaymentRequestDto req) =>
        PostJson<PaymentResultDto>(Endpoints.PayBalance, Serialize(req));

    public Task<PaymentsDto> GetPayments() => GetJson<PaymentsDto>(Endpoints.Payments);

    #endregion payments

    #region promo / trial / referral

    public Task<PromoDto> CheckPromo(string code) =>
        PostJson<PromoDto>(Endpoints.PromoCheck, Serialize(new PromoRequestDto(code)));

    public async Task ActivatePromo(string code)
    {
        EnsureConfigured();
        var req = new HttpRequestMessage(HttpMethod.Post, UrlOf(Endpoints.PromoActivate))
        {
            Content = JsonBody(Serialize(new PromoRequestDto(code))),
        };
        await ExecuteVoid(req);
    }

    public async Task ActivateTrial()
    {
        EnsureConfigured();
        var req = new HttpRequestMessage(HttpMethod.Post, UrlOf(Endpoints.Trial))
        {
            Content = JsonBody("{}"),
        };
        await ExecuteVoid(req);
    }

    public async Task SetSecondaryAutoRenew(string id, bool autoRenew)
    {
        EnsureConfigured();
        var req = new HttpRequestMessage(HttpMethod.Patch, UrlOf(Endpoints.SecondaryAutoRenew(id)))
        {
            Content = JsonBody(Serialize(new AutoRenewRequestDto(autoRenew))),
        };
        await ExecuteVoid(req);
    }

    public async Task SetPrimaryAutoRenew(bool autoRenew)
    {
        EnsureConfigured();
        var req = new HttpRequestMessage(HttpMethod.Patch, UrlOf(Endpoints.PrimaryAutoRenew))
        {
            Content = JsonBody(Serialize(new AutoRenewRequestDto(autoRenew))),
        };
        await ExecuteVoid(req);
    }

    public Task<ReferralStatsDto> GetReferralStats() => GetJson<ReferralStatsDto>(Endpoints.ReferralStats);

    #endregion promo / trial / referral

    #region internals

    private static void EnsureConfigured()
    {
        if (!BackendConfig.IsConfigured())
        {
            throw new ApiError.NotConfiguredError();
        }
    }

    private static string UrlOf(string path) => BackendConfig.BaseUrl + path;

    private static HttpContent JsonBody(string json) => new StringContent(json, Encoding.UTF8, "application/json");

    private static string Serialize<T>(T value) => JsonSerializer.Serialize(value, ApiJson.Options);

    private async Task<T> GetJson<T>(string path)
    {
        EnsureConfigured();
        return await Call<T>(new HttpRequestMessage(HttpMethod.Get, UrlOf(path)));
    }

    private async Task<T> PostJson<T>(string path, string json)
    {
        EnsureConfigured();
        var req = new HttpRequestMessage(HttpMethod.Post, UrlOf(path)) { Content = JsonBody(json) };
        return await Call<T>(req);
    }

    private async Task<T> Call<T>(HttpRequestMessage request)
    {
        var resp = await Execute(request);
        using (resp)
        {
            var body = await resp.Content.ReadAsStringAsync();
            if (!resp.IsSuccessStatusCode)
            {
                throw MapError((int)resp.StatusCode, SanitizeBody(body), LooksLikeApiResponse(resp));
            }
            return Parse<T>(body);
        }
    }

    /// <summary>
    /// Whether this response actually came from our API rather than from something intercepting the
    /// request. Our backend answers JSON (or an empty body); a captive portal answers an HTML login
    /// page. Only the 401 mapping reads this — see <see cref="ApiError.Unauthorized.FromApi"/> — but
    /// judging it here is the only place that still HAS the response to judge.
    /// </summary>
    private static bool LooksLikeApiResponse(HttpResponseMessage resp)
    {
        var mediaType = resp.Content.Headers.ContentType?.MediaType;
        return mediaType.IsNullOrEmpty() || mediaType!.Contains("json", StringComparison.OrdinalIgnoreCase);
    }

    private async Task ExecuteVoid(HttpRequestMessage request)
    {
        var resp = await Execute(request);
        using (resp)
        {
            if (!resp.IsSuccessStatusCode)
            {
                throw MapError((int)resp.StatusCode);
            }
        }
    }

    /// <summary>Executes the call, mapping transport failures to <see cref="ApiError"/>.</summary>
    private static async Task<HttpResponseMessage> Execute(HttpRequestMessage request)
    {
        try
        {
            return await _http.SendAsync(request);
        }
        catch (TaskCanceledException)
        {
            throw new ApiError.TimeoutError();
        }
        catch (OperationCanceledException)
        {
            throw new ApiError.TimeoutError();
        }
        catch (HttpRequestException e)
        {
            throw new ApiError.NetworkError(e);
        }
    }

    private static ApiError MapError(int code, string? detail = null, bool fromApi = true) => code switch
    {
        // Only 401 means "authentication failed / token expired". 403 (Forbidden) is a permission
        // outcome on a valid session and must NOT be treated as Unauthorized (else callers wipe a
        // live session). `fromApi` says whether the 401 is even ours to believe.
        401 => new ApiError.Unauthorized(detail, fromApi),
        403 => new ApiError.Server(403, detail),
        404 => new ApiError.NotFoundError(),
        410 => new ApiError.GoneError(),
        429 => new ApiError.RateLimited(),
        502 or 503 => new ApiError.ServiceUnavailable(),
        _ => new ApiError.Server(code, detail),
    };

    /// <summary>
    /// Reduces an error body to a short, screenshot-safe snippet: drops any line mentioning a
    /// token/authorization header or an http(s) URL, then caps at 300 chars. Null when nothing remains.
    /// </summary>
    private static string? SanitizeBody(string body)
    {
        if (body.IsNullOrEmpty())
        {
            return null;
        }
        var cleaned = string.Join("\n", body.Split('\n').Where(line =>
        {
            var l = line.ToLowerInvariant();
            return !(l.Contains("token") || l.Contains("authorization") || l.Contains("http://") || l.Contains("https://"));
        })).Trim();
        if (cleaned.IsNullOrEmpty())
        {
            return null;
        }
        return cleaned.Length > 300 ? cleaned.Substring(0, 300) : cleaned;
    }

    private static T Parse<T>(string body)
    {
        try
        {
            var value = JsonSerializer.Deserialize<T>(body, ApiJson.Options);
            if (value is null)
            {
                throw new ApiError.Parse();
            }
            return value;
        }
        catch (JsonException e)
        {
            throw new ApiError.Parse(e);
        }
    }

    #endregion internals
}
