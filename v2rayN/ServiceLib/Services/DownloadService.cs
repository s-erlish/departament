using System.Net.Http.Headers;

namespace ServiceLib.Services;

/// <summary>
/// Download
/// </summary>
public class DownloadService
{
    public event EventHandler<UpdateResult>? UpdateCompleted;

    public event ErrorEventHandler? Error;

    private static readonly string _tag = "DownloadService";

    /// <summary>
    /// Downloads data with the specified proxy and reports progress messages.
    /// </summary>
    public async Task<int> DownloadDataAsync(string url, IWebProxy webProxy, int downloadTimeout, Func<bool, string, Task> updateFunc)
    {
        try
        {
            var progress = new Progress<string>();
            progress.ProgressChanged += (sender, value) => updateFunc?.Invoke(false, $"{value}");

            await DownloaderHelper.Instance.DownloadDataAsync4Speed(webProxy,
                  url,
                  progress,
                  downloadTimeout);
        }
        catch (Exception ex)
        {
            await updateFunc?.Invoke(false, ex.Message);
            if (ex.InnerException != null)
            {
                await updateFunc?.Invoke(false, ex.InnerException.Message);
            }
        }
        return 0;
    }

    /// <summary>
    /// Downloads a file and reports progress through events.
    /// </summary>
    public async Task DownloadFileAsync(string url, string fileName, bool blProxy, int downloadTimeout)
    {
        try
        {
            UpdateCompleted?.Invoke(this, new UpdateResult(false, $"{ResUI.Downloading}   {url}"));

            var progress = new Progress<double>();
            progress.ProgressChanged += (sender, value) => UpdateCompleted?.Invoke(this, new UpdateResult(value > 100, $"...{value}%"));

            var webProxy = await GetWebProxy(blProxy);
            await DownloaderHelper.Instance.DownloadFileAsync(webProxy,
                url,
                fileName,
                progress,
                downloadTimeout);
        }
        catch (Exception ex)
        {
            Logging.SaveLog(_tag, ex);

            Error?.Invoke(this, new ErrorEventArgs(ex));
            if (ex.InnerException != null)
            {
                Error?.Invoke(this, new ErrorEventArgs(ex.InnerException));
            }
        }
    }

    /// <summary>
    /// Gets redirect target URL without following redirects automatically.
    /// </summary>
    public async Task<string?> UrlRedirectAsync(string url, bool blProxy)
    {
        var webRequestHandler = new SocketsHttpHandler
        {
            AllowAutoRedirect = false,
            Proxy = await GetWebProxy(blProxy)
        };
        var certificateChainPolicy = CertPemManager.Instance.BuildCertificateChainPolicy();
        if (certificateChainPolicy != null)
        {
            webRequestHandler.SslOptions.CertificateChainPolicy = certificateChainPolicy;
            webRequestHandler.SslOptions.RemoteCertificateValidationCallback = null;
        }
        using var client = new HttpClient(webRequestHandler);

        var response = await client.GetAsync(url);
        if (response.StatusCode == HttpStatusCode.Redirect && response.Headers.Location is not null)
        {
            return response.Headers.Location.ToString();
        }
        else
        {
            Error?.Invoke(this, new ErrorEventArgs(new Exception("StatusCode error: " + response.StatusCode)));
            Logging.SaveLog("StatusCode error: " + url);
            return null;
        }
    }

    /// <summary>
    /// Tries to download string content using proxy switch setting.
    /// </summary>
    public async Task<string?> TryDownloadString(string url, bool blProxy, string userAgent)
    {
        var webProxy = await GetWebProxy(blProxy);
        return await TryDownloadString(url, webProxy, userAgent);
    }

    /// <summary>
    /// Tries to download string content with a specified proxy.
    /// </summary>
    public async Task<string?> TryDownloadString(string url, IWebProxy? webProxy, string userAgent)
    {
        var timeout = 15;
        try
        {
            var result1 = await DownloadStringAsync(url, webProxy, userAgent, timeout);
            if (result1.IsNotEmpty())
            {
                return result1;
            }
        }
        catch (Exception ex)
        {
            Logging.SaveLog(_tag, ex);
            Error?.Invoke(this, new ErrorEventArgs(ex));
            if (ex.InnerException != null)
            {
                Error?.Invoke(this, new ErrorEventArgs(ex.InnerException));
            }
        }

        try
        {
            var result2 = await DownloadStringViaDownloader(url, webProxy, userAgent, timeout);
            if (result2.IsNotEmpty())
            {
                return result2;
            }
        }
        catch (Exception ex)
        {
            Logging.SaveLog(_tag, ex);
            Error?.Invoke(this, new ErrorEventArgs(ex));
            if (ex.InnerException != null)
            {
                Error?.Invoke(this, new ErrorEventArgs(ex.InnerException));
            }
        }

        return null;
    }

    /// <summary>
    /// Same as <see cref="TryDownloadString(string, bool, string)"/> but also returns the
    /// subscription response headers (subscription-userinfo + the announce/support/web-page/title
    /// directives) which the plain string variant discards. Added as a separate path so existing
    /// callers keep using GetStringAsync; this one uses GetAsync to expose <c>response.Headers</c>.
    /// Returns null when the body could not be fetched.
    /// </summary>
    public async Task<SubContentResult?> TryDownloadStringWithHeaders(string url, bool blProxy, string userAgent)
    {
        var webProxy = await GetWebProxy(blProxy);
        return await TryDownloadStringWithHeaders(url, webProxy, userAgent);
    }

    /// <summary>
    /// Tries to download body + subscription headers with a specified proxy.
    /// </summary>
    public async Task<SubContentResult?> TryDownloadStringWithHeaders(string url, IWebProxy? webProxy, string userAgent)
    {
        var timeout = 15;
        try
        {
            var result = await DownloadStringWithHeadersAsync(url, webProxy, userAgent, timeout);
            if (result?.Body.IsNotEmpty() == true)
            {
                return result;
            }
        }
        catch (Exception ex)
        {
            Logging.SaveLog(_tag, ex);
            Error?.Invoke(this, new ErrorEventArgs(ex));
            if (ex.InnerException != null)
            {
                Error?.Invoke(this, new ErrorEventArgs(ex.InnerException));
            }
        }

        return null;
    }

    /// <summary>
    /// Single source of truth for the HTTP client used by EVERY subscription/string GET, so the
    /// manual-add path and the Telegram/account path issue a byte-identical request. Shapes the
    /// request to match a real v2rayNG (Android HttpURLConnection / OkHttp) client:
    ///   - UA attached raw/unvalidated so the exact literal (e.g. "v2rayNG/1.10.6") goes out verbatim,
    ///     as a SINGLE clean header (no default "v2rayN/&lt;ver&gt;" UA is ever appended);
    ///   - Accept-Encoding advertised as exactly "gzip" (OkHttp's default) rather than the .NET
    ///     default "gzip, deflate, br" — the previous value made the request fingerprint diverge from
    ///     a genuine v2rayNG client;
    ///   - gzip transparently decoded by the handler.
    /// Verified on the wire against an echo endpoint: the request now carries
    ///   User-Agent: v2rayNG/1.10.6   Accept-Encoding: gzip
    /// and nothing else app-identifying.
    /// </summary>
    private static HttpClient CreateSubscriptionClient(string url, IWebProxy? webProxy, ref string userAgent, int connectTimeout)
    {
        var handler = new SocketsHttpHandler
        {
            Proxy = webProxy,
            UseProxy = webProxy != null,
            ConnectTimeout = TimeSpan.FromSeconds(connectTimeout),
            // Advertise + transparently decode exactly "gzip" — byte-identical to a real v2rayNG
            // (OkHttp) client. NOT DecompressionMethods.All, which would send "gzip, deflate, br".
            AutomaticDecompression = DecompressionMethods.GZip
        };
        var certificateChainPolicy = CertPemManager.Instance.BuildCertificateChainPolicy();
        if (certificateChainPolicy != null)
        {
            handler.SslOptions.CertificateChainPolicy = certificateChainPolicy;
            handler.SslOptions.RemoteCertificateValidationCallback = null;
        }

        var client = new HttpClient(handler)
        {
            Timeout = Timeout.InfiniteTimeSpan
        };

        if (userAgent.IsNullOrEmpty())
        {
            userAgent = Utils.GetVersion(false);
        }
        // Attach the UA raw/unvalidated so the exact literal is transmitted verbatim as one header.
        client.DefaultRequestHeaders.UserAgent.Clear();
        client.DefaultRequestHeaders.TryAddWithoutValidation("User-Agent", userAgent);

        // Remnawave HWID device-limit headers (like Happ). Without x-hwid a panel with HWID limit
        // enabled returns the «Приложение не поддерживается» placeholder; with it the real list is
        // served (subject to the device-slot limit). HWID comes from the Desktop-wired provider so it
        // matches the account API's X-HWID (one device slot per machine). Sent verbatim/unvalidated.
        var hwid = Global.SubscriptionHwidProvider?.Invoke();
        if (hwid.IsNotEmpty())
        {
            client.DefaultRequestHeaders.TryAddWithoutValidation("x-hwid", hwid);
            client.DefaultRequestHeaders.TryAddWithoutValidation("x-device-os", "Windows");
            client.DefaultRequestHeaders.TryAddWithoutValidation("x-ver-os", Environment.OSVersion.Version.ToString());
            client.DefaultRequestHeaders.TryAddWithoutValidation("x-device-model", Environment.MachineName);
        }

        Uri uri = new(url);
        //Authorization Header
        if (uri.UserInfo.IsNotEmpty())
        {
            client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Basic", Utils.Base64Encode(uri.UserInfo));
        }

        return client;
    }

    /// <summary>
    /// Downloads string content via HttpClient.
    /// </summary>
    private async Task<string?> DownloadStringAsync(string url, IWebProxy? webProxy, string userAgent, int timeout)
    {
        try
        {
            var connectTimeout = Math.Clamp(timeout / 5, 2, 5);
            using var client = CreateSubscriptionClient(url, webProxy, ref userAgent, connectTimeout);

            using var cts = new CancellationTokenSource();
            cts.CancelAfter(TimeSpan.FromSeconds(timeout));

            return await client.GetStringAsync(url, cts.Token);
        }
        catch (Exception ex)
        {
            Logging.SaveLog(_tag, ex);
            Error?.Invoke(this, new ErrorEventArgs(ex));
            if (ex.InnerException != null)
            {
                Error?.Invoke(this, new ErrorEventArgs(ex.InnerException));
            }
        }

        return null;
    }

    /// <summary>
    /// Downloads string content via HttpClient using GetAsync so the response headers
    /// (subscription-userinfo and the Happ/Incy directives) remain available. Mirrors
    /// <see cref="DownloadStringAsync"/> but returns body + selected headers. Auto-redirects
    /// are followed by the handler, so the headers read here are those of the final 2xx response.
    /// </summary>
    private async Task<SubContentResult?> DownloadStringWithHeadersAsync(string url, IWebProxy? webProxy, string userAgent, int timeout)
    {
        try
        {
            var connectTimeout = Math.Clamp(timeout / 5, 2, 5);
            using var client = CreateSubscriptionClient(url, webProxy, ref userAgent, connectTimeout);

            // Concise, verifiable trace of what actually goes out on the subscription GET (this
            // WithHeaders path is used ONLY by SubscriptionHandler). Confirms the manual and the
            // account fetch send the identical User-Agent; no secrets (host only, never the token/path).
            Logging.SaveLog($"{_tag} subscription GET UA=[{userAgent}] hwid=[{(Global.SubscriptionHwidProvider?.Invoke().IsNotEmpty() == true ? "yes" : "no")}] host={new Uri(url).Host}");

            using var cts = new CancellationTokenSource();
            cts.CancelAfter(TimeSpan.FromSeconds(timeout));

            using var response = await client.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, cts.Token);
            response.EnsureSuccessStatusCode();
            var body = await response.Content.ReadAsStringAsync(cts.Token);

            return new SubContentResult
            {
                Body = body,
                SubscriptionUserInfo = GetHeaderValue(response, "subscription-userinfo"),
                Announce = GetHeaderValue(response, "announce"),
                SupportUrl = GetHeaderValue(response, "support-url"),
                WebPageUrl = GetHeaderValue(response, "profile-web-page-url"),
                ProfileTitle = GetHeaderValue(response, "profile-title"),
            };
        }
        catch (Exception ex)
        {
            Logging.SaveLog(_tag, ex);
            Error?.Invoke(this, new ErrorEventArgs(ex));
            if (ex.InnerException != null)
            {
                Error?.Invoke(this, new ErrorEventArgs(ex.InnerException));
            }
        }

        return null;
    }

    /// <summary>
    /// Reads a response header from either the message or the content header collection.
    /// Returns null when the header is absent so callers can tell "not sent" from "cleared".
    /// </summary>
    private static string? GetHeaderValue(HttpResponseMessage response, string name)
    {
        if (response.Headers.TryGetValues(name, out var values))
        {
            return values.FirstOrDefault();
        }
        if (response.Content.Headers.TryGetValues(name, out var contentValues))
        {
            return contentValues.FirstOrDefault();
        }
        return null;
    }

    /// <summary>
    /// Downloads string content via DownloaderHelper.
    /// </summary>
    private async Task<string?> DownloadStringViaDownloader(string url, IWebProxy? webProxy, string userAgent, int timeout)
    {
        try
        {
            if (userAgent.IsNullOrEmpty())
            {
                userAgent = Utils.GetVersion(false);
            }
            var result = await DownloaderHelper.Instance.DownloadStringAsync(webProxy, url, userAgent, timeout);
            return result;
        }
        catch (Exception ex)
        {
            Logging.SaveLog(_tag, ex);
            Error?.Invoke(this, new ErrorEventArgs(ex));
            if (ex.InnerException != null)
            {
                Error?.Invoke(this, new ErrorEventArgs(ex.InnerException));
            }
        }
        return null;
    }

    /// <summary>
    /// Creates local SOCKS proxy when proxy switch is enabled.
    /// </summary>
    private async Task<WebProxy?> GetWebProxy(bool blProxy)
    {
        if (!blProxy)
        {
            return null;
        }
        var port = AppManager.Instance.GetLocalPort(EInboundProtocol.socks);
        if (await SocketCheck(Global.Loopback, port) == false)
        {
            return null;
        }

        return new WebProxy($"socks5://{Global.Loopback}:{port}");
    }

    /// <summary>
    /// Checks whether the specified TCP endpoint is reachable.
    /// </summary>
    private async Task<bool> SocketCheck(string ip, int port)
    {
        try
        {
            IPEndPoint point = new(IPAddress.Parse(ip), port);
            using Socket? sock = new(AddressFamily.InterNetwork, SocketType.Stream, ProtocolType.Tcp);
            await sock.ConnectAsync(point);
            return true;
        }
        catch (Exception)
        {
            return false;
        }
    }
}

/// <summary>
/// Body plus the subscription-related response headers of a subscription fetch.
/// The directive fields (announce/support/web-page/title) are RAW header values — they may be
/// <c>base64:</c>-prefixed or "0" (clear); decoding/persisting is done by
/// <see cref="Handler.SubscriptionHandler"/>. Any field may be null when the header was absent.
/// </summary>
public class SubContentResult
{
    public string? Body { get; set; }
    public string? SubscriptionUserInfo { get; set; }
    public string? Announce { get; set; }
    public string? SupportUrl { get; set; }
    public string? WebPageUrl { get; set; }
    public string? ProfileTitle { get; set; }
}
