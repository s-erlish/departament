using System.Text.Json;

namespace v2rayN.Desktop.Account;

/// <summary>
/// Sealed hierarchy of failures the API layer can surface. Callers pattern-match on these and never
/// see raw exceptions. Messages must never contain tokens or subscription URLs. Ported 1:1 from
/// V2rayNG auth/ApiError.kt.
/// </summary>
public abstract class ApiError : Exception
{
    protected ApiError(string message, Exception? cause = null) : base(message, cause)
    {
    }

    /// <summary>Backend not configured (blank base URL) — login should not be offered.</summary>
    public sealed class NotConfiguredError : ApiError
    {
        public NotConfiguredError() : base("Backend not configured")
        {
        }
    }

    /// <summary>Network/IO failure (no connectivity, DNS, TLS).</summary>
    public sealed class NetworkError : ApiError
    {
        public NetworkError(Exception? cause = null) : base("Network error", cause)
        {
        }
    }

    /// <summary>A request (or the login flow) exceeded its time budget.</summary>
    public sealed class TimeoutError : ApiError
    {
        public TimeoutError() : base("Request timed out")
        {
        }
    }

    /// <summary>
    /// 401 — session invalid or expired. Detail carries a sanitized snippet of the response body
    /// (payment diagnostics), never a token/URL.
    /// </summary>
    public sealed class Unauthorized : ApiError
    {
        public string? Detail { get; }

        /// <summary>
        /// True when the 401 actually came from the departament backend, false when it came from
        /// something else standing in the way.
        ///
        /// A 401 is the ONE answer allowed to end a session, so it matters who said it. A captive
        /// portal — hotel, airport, office wifi, the exact networks a VPN user meets — answers every
        /// request with 401 and an HTML login page, and the app read that as «твой токен мёртв» and
        /// signed the user out of an account whose token was perfectly fine. Provenance is judged by
        /// the response's content type: our API answers JSON (or nothing), a portal answers a page.
        /// Defaults to true so a call site that cannot judge behaves exactly as before.
        /// </summary>
        public bool FromApi { get; }

        public Unauthorized(string? detail = null, bool fromApi = true) : base("Unauthorized")
        {
            Detail = detail;
            FromApi = fromApi;
        }
    }

    /// <summary>404 — resource not found (also the "keep polling" signal for telegram-login-check).</summary>
    public sealed class NotFoundError : ApiError
    {
        public NotFoundError() : base("Not found")
        {
        }
    }

    /// <summary>410 — resource gone / login token expired.</summary>
    public sealed class GoneError : ApiError
    {
        public GoneError() : base("Gone")
        {
        }
    }

    /// <summary>429 — too many requests.</summary>
    public sealed class RateLimited : ApiError
    {
        public RateLimited() : base("Rate limited")
        {
        }
    }

    /// <summary>502/503 — backend temporarily unavailable.</summary>
    public sealed class ServiceUnavailable : ApiError
    {
        public ServiceUnavailable() : base("Service unavailable")
        {
        }
    }

    /// <summary>
    /// Any other unexpected non-2xx status. Detail carries a sanitized snippet of the response body
    /// (payment diagnostics), never a token/URL.
    /// </summary>
    public sealed class Server : ApiError
    {
        public int Code { get; }
        public string? Detail { get; }

        public Server(int code, string? detail = null) : base($"Server error ({code})")
        {
            Code = code;
            Detail = detail;
        }
    }

    /// <summary>Response body could not be parsed into the expected shape.</summary>
    public sealed class Parse : ApiError
    {
        public Parse(Exception? cause = null) : base("Failed to parse response", cause)
        {
        }
    }
}

/// <summary>
/// Reads the panel's OWN words out of a refusal.
///
/// Several endpoints answer one status code with several different meanings — <c>/link-email-request</c>
/// returns 400 for «Почта уже привязана», «Некорректный email» and «Эта почта уже используется другим
/// аккаунтом» alike — and nothing in the code tells them apart. The body does, and by the time it
/// reaches here it has already been trimmed and stripped of anything token-shaped by the client's
/// <c>SanitizeBody</c>, so quoting it is safe. Lives beside <see cref="ApiError"/> rather than in one
/// screen's code-behind because both the account tab and the sign-in page now quote the same refusals.
///
/// Only for the paths where the panel writes a human sentence. «Неверный email или пароль» on an
/// ordinary sign-in stays OUR wording: it names the fix, and the panel's does not.
/// </summary>
public static class ApiErrorText
{
    /// <summary>
    /// The <c>message</c> the panel sent, or null when there is nothing quotable (no body, not JSON —
    /// a captive portal's HTML page, say — or no message field). Callers fall back to their own string.
    /// </summary>
    public static string? ServerMessageOf(ApiError error) => FieldOf(error, "message");

    /// <summary>
    /// The machine-readable <c>code</c> the panel sent beside the message (<c>PASSWORD_REQUIRED</c>,
    /// <c>INVALID_PASSWORD</c>, …), or null. Status alone cannot separate «введите пароль» from
    /// «пароль неверный» once both are shown as one red line — this is what lets the screen point at
    /// the field that is actually wrong.
    /// </summary>
    public static string? ServerCodeOf(ApiError error) => FieldOf(error, "code");

    private static string? FieldOf(ApiError error, string field)
    {
        var detail = error switch
        {
            ApiError.Server server => server.Detail,
            ApiError.Unauthorized unauthorized => unauthorized.Detail,
            _ => null,
        };
        if (detail.IsNullOrEmpty())
        {
            return null;
        }
        try
        {
            using var doc = JsonDocument.Parse(detail!);
            if (doc.RootElement.ValueKind == JsonValueKind.Object
                && doc.RootElement.TryGetProperty(field, out var value)
                && value.ValueKind == JsonValueKind.String)
            {
                return value.GetString().NullIfEmpty();
            }
        }
        catch (JsonException)
        {
            // Not JSON — normal enough: the body may be a proxy's HTML page. The caller's own string fits.
        }
        return null;
    }
}
