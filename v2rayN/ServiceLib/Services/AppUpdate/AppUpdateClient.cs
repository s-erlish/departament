using System.Net.Http.Headers;

namespace ServiceLib.Services.AppUpdate;

/// <summary>
/// Почему проверка, загрузка или установка остановилась — значением, а не текстом исключения. Экран
/// обязан сказать причину своими словами и поставить рядом следующий шаг; сырое сообщение исключения
/// пользователю не показывается никогда, оно уходит в журнал.
/// </summary>
public enum AppUpdateFailure
{
    /// <summary>Сети нет: интерфейсы не подняты или имя сервера не разрешается.</summary>
    Offline,

    /// <summary>Сервер обновлений не ответил: таймаут, обрыв, ошибка TLS.</summary>
    Unreachable,

    /// <summary>Сервер ответил, но не лентой: 5xx, неожиданный код, неразбираемый ответ.</summary>
    ServerError,

    /// <summary>GitHub временно ограничил запросы с этого адреса (60 в час без входа).</summary>
    RateLimited,

    /// <summary>Самообновление в этой сборке не поддерживается: пакет выпускается только для Windows x64.</summary>
    UnsupportedPlatform,

    /// <summary>
    /// Более новая версия вышла, но без пакета для этой системы. Как и «выпусков нет», это ответ ленты,
    /// а не поломка: предлагать нечего, ждать следующую сборку.
    /// </summary>
    NoPackage,

    /// <summary>Загрузка оборвалась или файл не записался (нет места, нет прав).</summary>
    DownloadFailed,

    /// <summary>Загрузку перенаправили не на хранилище выпусков GitHub — она остановлена.</summary>
    ForeignRedirect,

    /// <summary>Пакет не сошёлся с контрольной суммой — удалён.</summary>
    ChecksumMismatch,

    /// <summary>Файл выпуска не похож на пакет departament (или битая сумма) — удалён.</summary>
    BadPackage,

    /// <summary>Рядом с приложением нет установщика (AmazTool) или он не запустился.</summary>
    InstallerMissing,

    /// <summary>Установщик отработал, но запущена прежняя версия: замена не прошла и откатилась.</summary>
    InstallFailed,
}

public sealed class AppUpdateException(AppUpdateFailure reason, string detail) : Exception(detail)
{
    public AppUpdateFailure Reason { get; } = reason;
}

/// <summary>Ответ ленты: тело или «выпусков нет» (GitHub отвечает 404 на /latest пустого репозитория).</summary>
public sealed record AppUpdateFeedResponse(string? Body, bool NoRelease);

/// <summary>
/// Сеть обновления: лента и загрузка файлов выпуска, и ничего больше. Ни одного адреса этот класс не
/// принимает снаружи: лента и загрузка собираются из <see cref="AppUpdateChannel"/>, а каждое
/// перенаправление проверяется им же до того, как по нему пойти.
///
/// <para><b>Маршрут.</b> Сначала напрямую (с системным прокси, если он задан: когда VPN подключён в режиме
/// прокси, это и есть туннель), и только если ответа не было вовсе — через локальный SOCKS приложения,
/// если он слушает. Так же делает departament для Android: GitHub может быть недоступен напрямую. Когда
/// сервер ответил кодом, повтор через прокси ответа не изменит, поэтому второго запроса нет.</para>
/// </summary>
public sealed class AppUpdateClient
{
    private static readonly TimeSpan FeedTimeout = TimeSpan.FromSeconds(20);

    // Загрузка идёт столько, сколько идёт, но без единого байта 30 с — это обрыв, а не медленная сеть.
    private static readonly TimeSpan StallTimeout = TimeSpan.FromSeconds(30);

    private const int MaxRedirects = 5;

    private readonly AppUpdateChannel _channel;
    private readonly string _userAgent;

    // Маршрут, на котором ответила лента: загрузка идёт тем же путём.
    private bool _viaLocalProxy;

    public AppUpdateClient(AppUpdateChannel channel, string userAgent)
    {
        _channel = channel;
        _userAgent = userAgent;
    }

    public async Task<AppUpdateFeedResponse> GetFeedAsync(bool includePreRelease, CancellationToken token)
    {
        var url = _channel.FeedUrl(includePreRelease);
        if (!_channel.IsFeedUrl(url))
        {
            throw new AppUpdateException(AppUpdateFailure.ServerError, $"refused feed url {url}");
        }

        _viaLocalProxy = false;
        try
        {
            return await GetFeedOnceAsync(url, proxy: null, token);
        }
        catch (Exception ex) when (IsNoAnswer(ex, token))
        {
            var local = await LocalProxyAsync();
            if (local is null)
            {
                throw NoAnswer(ex);
            }
            Logging.SaveLog($"AppUpdate: feed unreachable directly ({ex.Message}), retrying through the local proxy");
            _viaLocalProxy = true;
            try
            {
                return await GetFeedOnceAsync(url, local, token);
            }
            catch (Exception again) when (IsNoAnswer(again, token))
            {
                throw NoAnswer(again);
            }
        }
    }

    /// <summary>Ответа не было вовсе: соединение не установилось или истекло время. Отмена пользователем — не сюда.</summary>
    private static bool IsNoAnswer(Exception ex, CancellationToken token) =>
        ex is HttpRequestException { StatusCode: null } || (ex is OperationCanceledException && !token.IsCancellationRequested);

    private static AppUpdateException NoAnswer(Exception ex) =>
        ex is HttpRequestException http ? Classify(http) : new AppUpdateException(AppUpdateFailure.Unreachable, "feed timed out");

    private async Task<AppUpdateFeedResponse> GetFeedOnceAsync(Uri url, IWebProxy? proxy, CancellationToken token)
    {
        using var client = CreateClient(proxy);
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(token);
        timeout.CancelAfter(FeedTimeout);

        using var request = new HttpRequestMessage(HttpMethod.Get, url);
        request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/vnd.github+json"));
        request.Headers.TryAddWithoutValidation("X-GitHub-Api-Version", "2022-11-28");
        using var response = await client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, timeout.Token);

        switch ((int)response.StatusCode)
        {
            case 200:
                return new AppUpdateFeedResponse(await response.Content.ReadAsStringAsync(timeout.Token), false);

            // «Выпусков ещё нет» — ответ, а не сбой (см. AppUpdateVerdict.NoRelease).
            case 404:
                return new AppUpdateFeedResponse(null, true);

            case 403 or 429 when IsRateLimited(response):
                throw new AppUpdateException(AppUpdateFailure.RateLimited, $"feed rate limited ({(int)response.StatusCode})");

            default:
                throw new AppUpdateException(AppUpdateFailure.ServerError, $"feed answered {(int)response.StatusCode}");
        }
    }

    /// <summary>
    /// Скачивает файл выпуска <paramref name="asset"/> тега <paramref name="tag"/> в <paramref name="destination"/>.
    /// Идёт только по перенаправлениям, которые пропускает канал, и не пишет больше <paramref name="maxBytes"/>.
    /// </summary>
    /// <param name="expectedSize">Размер по ленте; если назван, файл другой длины считается оборванным.</param>
    /// <param name="progress">Принято / всего байт (всего = 0, если сервер длину не назвал).</param>
    public async Task DownloadAsync(string tag, string asset, string destination, long expectedSize, long maxBytes,
        IProgress<(long Received, long Total)>? progress, CancellationToken token)
    {
        var url = _channel.DownloadUrl(tag, asset);
        if (!_channel.IsDownloadUrl(url, tag, asset))
        {
            throw new AppUpdateException(AppUpdateFailure.ForeignRedirect, $"refused download url {url}");
        }

        var proxy = _viaLocalProxy ? await LocalProxyAsync() : null;
        using var client = CreateClient(proxy);
        using var stall = CancellationTokenSource.CreateLinkedTokenSource(token);
        stall.CancelAfter(StallTimeout);

        try
        {
            for (var hop = 0; ; hop++)
            {
                using var response = await client.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, stall.Token);
                if (IsRedirect(response.StatusCode))
                {
                    var location = response.Headers.Location;
                    var next = location is null ? null : location.IsAbsoluteUri ? location : new Uri(url, location);
                    if (hop >= MaxRedirects || !_channel.IsAllowedRedirect(next))
                    {
                        // В журнал — только хост: в адресе хранилища GitHub лежит подписанный токен доступа.
                        throw new AppUpdateException(AppUpdateFailure.ForeignRedirect,
                            $"refused redirect of {asset} to {next?.Scheme}://{next?.Authority}");
                    }
                    url = next!;
                    continue;
                }
                if (!response.IsSuccessStatusCode)
                {
                    throw new AppUpdateException(AppUpdateFailure.DownloadFailed,
                        $"{asset} answered {(int)response.StatusCode} at {url.Authority}");
                }

                var total = response.Content.Headers.ContentLength ?? expectedSize;
                if (total > maxBytes || (expectedSize > 0 && total > 0 && total != expectedSize))
                {
                    throw new AppUpdateException(AppUpdateFailure.DownloadFailed,
                        $"{asset}: server length {total}, expected {expectedSize}, limit {maxBytes}");
                }

                await using var body = await response.Content.ReadAsStreamAsync(stall.Token);
                await using var file = new FileStream(destination, FileMode.Create, FileAccess.Write, FileShare.None, 1 << 16, useAsync: true);
                var buffer = new byte[1 << 16];
                long received = 0;
                progress?.Report((0, Math.Max(0, total)));
                while (true)
                {
                    var read = await body.ReadAsync(buffer, stall.Token);
                    if (read == 0)
                    {
                        break;
                    }
                    received += read;
                    if (received > maxBytes || (expectedSize > 0 && received > expectedSize))
                    {
                        throw new AppUpdateException(AppUpdateFailure.DownloadFailed, $"{asset}: more data than announced");
                    }
                    await file.WriteAsync(buffer.AsMemory(0, read), stall.Token);
                    stall.CancelAfter(StallTimeout);
                    progress?.Report((received, Math.Max(0, total)));
                }
                if (expectedSize > 0 && received != expectedSize)
                {
                    throw new AppUpdateException(AppUpdateFailure.DownloadFailed, $"{asset}: got {received} of {expectedSize} bytes");
                }
                return;
            }
        }
        catch (OperationCanceledException) when (!token.IsCancellationRequested)
        {
            throw new AppUpdateException(AppUpdateFailure.DownloadFailed, $"{asset}: no data for {StallTimeout.TotalSeconds:0} s");
        }
        catch (HttpRequestException ex)
        {
            var classified = Classify(ex);
            throw classified.Reason == AppUpdateFailure.Offline
                ? classified
                : new AppUpdateException(AppUpdateFailure.DownloadFailed, $"{asset}: {ex.HttpRequestError}: {ex.Message}");
        }
        catch (IOException ex)
        {
            throw new AppUpdateException(AppUpdateFailure.DownloadFailed, $"{asset}: {ex.Message}");
        }
    }

    private HttpClient CreateClient(IWebProxy? proxy)
    {
        var handler = new SocketsHttpHandler
        {
            // Перенаправления проходим сами: каждое проверяется до того, как по нему пойти.
            AllowAutoRedirect = false,
            ConnectTimeout = TimeSpan.FromSeconds(10),
            AutomaticDecompression = DecompressionMethods.GZip,
        };
        if (proxy is not null)
        {
            handler.Proxy = proxy;
            handler.UseProxy = true;
        }
        // Тот же набор корневых сертификатов, что у остальных запросов приложения (системный или Mozilla).
        var chainPolicy = CertPemManager.Instance.BuildCertificateChainPolicy();
        if (chainPolicy is not null)
        {
            handler.SslOptions.CertificateChainPolicy = chainPolicy;
        }
        var client = new HttpClient(handler) { Timeout = Timeout.InfiniteTimeSpan };
        // GitHub API без User-Agent отвечает 403.
        client.DefaultRequestHeaders.TryAddWithoutValidation("User-Agent", _userAgent);
        return client;
    }

    /// <summary>Локальный SOCKS приложения, если он сейчас слушает; иначе null.</summary>
    private static async Task<IWebProxy?> LocalProxyAsync()
    {
        try
        {
            var port = AppManager.Instance.GetLocalPort(EInboundProtocol.socks);
            using var socket = new Socket(AddressFamily.InterNetwork, SocketType.Stream, ProtocolType.Tcp);
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(1));
            await socket.ConnectAsync(IPAddress.Loopback, port, cts.Token);
            return new WebProxy($"socks5://{Global.Loopback}:{port}");
        }
        catch
        {
            return null;
        }
    }

    private static bool IsRedirect(HttpStatusCode code) =>
        code is HttpStatusCode.MovedPermanently or HttpStatusCode.Found or HttpStatusCode.SeeOther
            or HttpStatusCode.TemporaryRedirect or HttpStatusCode.PermanentRedirect;

    private static bool IsRateLimited(HttpResponseMessage response) =>
        (int)response.StatusCode == 429
        || response.Headers.RetryAfter is not null
        || (response.Headers.TryGetValues("X-RateLimit-Remaining", out var left) && left.FirstOrDefault() == "0");

    /// <summary>Нет ответа вовсе: «нет сети», если сеть правда лежит, иначе «сервер не ответил».</summary>
    private static AppUpdateException Classify(HttpRequestException ex)
    {
        var offline = !NetworkInterface.GetIsNetworkAvailable()
            || ex.HttpRequestError == HttpRequestError.NameResolutionError
            || ex.InnerException is SocketException
            {
                SocketErrorCode: SocketError.NetworkDown or SocketError.NetworkUnreachable or SocketError.HostNotFound
                    or SocketError.TryAgain or SocketError.NoData
            };
        return new AppUpdateException(offline ? AppUpdateFailure.Offline : AppUpdateFailure.Unreachable,
            $"{ex.HttpRequestError}: {ex.Message}");
    }
}
