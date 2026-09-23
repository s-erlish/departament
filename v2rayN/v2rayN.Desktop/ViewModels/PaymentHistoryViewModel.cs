using v2rayN.Desktop.Account;
using v2rayN.Desktop.Account.Dto;

namespace v2rayN.Desktop.ViewModels;

/// <summary>
/// One preformatted row of the payment-history list. Pure presentation record: every string is
/// derived once from <see cref="PaymentDto"/> by <see cref="PaymentHistoryViewModel"/> (ported 1:1
/// from V2rayNG ui/adapter/PaymentsAdapter.kt), so the view binds text verbatim and never formats.
/// Exactly one of the four status flags is true for a mapped status; all four false = the unmapped
/// fallback (raw status text on the neutral chip).
/// </summary>
public sealed record PaymentRow
{
    /// <summary>Row title: Description, else Kind, else OrderId (PaymentsAdapter fallback chain).</summary>
    public string Description { get; init; } = string.Empty;

    /// <summary>Payment date as dd.MM.yyyy, or blank when CreatedAt is missing/unparseable.</summary>
    public string Date { get; init; } = string.Empty;

    /// <summary>Amount with currency sign, e.g. «199 ₽» / «216.50 ₽» (tnum in the view).</summary>
    public string Amount { get; init; } = string.Empty;

    /// <summary>Russian label for mapped statuses, the raw status for unmapped, blank = no status.</summary>
    public string StatusLabel { get; init; } = string.Empty;

    public bool IsPaid { get; init; }
    public bool IsPending { get; init; }
    public bool IsFailed { get; init; }
    public bool IsCanceled { get; init; }

    /// <summary>
    /// The whole caption as ONE string — «09.08.2026 · Оплачено». Date and status used to be two
    /// TextBlocks side by side, and side by side they drifted: the boxes align by their tops, so the
    /// moment the two differed by a font (the date carried the numeric face, the status did not) or
    /// by a margin, the status sat visibly off the date's line. One string cannot drift from itself.
    /// The separator lives inside the join, so a payment without a date never shows a leading «·».
    /// </summary>
    public string Sub =>
        Date.Length > 0 && StatusLabel.Length > 0 ? Date + " · " + StatusLabel
        : Date.Length > 0 ? Date
        : StatusLabel;

    public bool HasSub => Sub.Length > 0;
}

/// <summary>
/// Backs the «История платежей» sub-screen. Port of V2rayNG PaymentHistoryActivity.kt +
/// PaymentsAdapter.kt on the AccountViewModel conventions (ReactiveUI, ApiResult, RunOnUi):
/// cache-first (fresh &lt; 1h list renders instantly and skips the network), otherwise one load via
/// <see cref="AccountRepository.GetPayments"/>; rows are preformatted here and sorted newest first.
/// DATA-DRIVEN: nothing is invented — the list stays empty until the real API returns.
/// The screen is always in exactly one of four states: loading / list / empty / error.
/// </summary>
public class PaymentHistoryViewModel : MyReactiveObject
{
    private readonly AccountRepository _repo;

    // True once the FIRST result (cache hit or network response) has landed. Gates the skeleton so
    // a genuinely empty history resolves to the empty state instead of pulsing forever.
    private bool _loaded;

    #region reactive state

    /// <summary>Preformatted rows, newest first.</summary>
    [Reactive] public List<PaymentRow> Payments { get; set; } = new();

    [Reactive] public bool IsLoading { get; set; }
    [Reactive] public ApiError? Error { get; set; }

    /// <summary>Russian message for the error state (network vs generic, as Android messageFor).</summary>
    [Reactive] public string ErrorText { get; set; } = string.Empty;

    // The four mutually-exclusive screen states (skeleton / list / empty / error).
    [Reactive] public bool ShowLoading { get; set; }
    [Reactive] public bool ShowList { get; set; }
    [Reactive] public bool ShowEmpty { get; set; }
    [Reactive] public bool ShowError { get; set; }

    #endregion reactive state

    /// <summary>Loads (or reloads) the history. Bound to the error-state «Повторить» button.</summary>
    public ReactiveCommand<Unit, Unit> LoadCmd { get; }

    /// <summary>
    /// Runtime constructor. Mirrors PaymentHistoryActivity.onCreate: serve fresh (&lt; 1h) cached
    /// payments instantly and skip the initial network load — a cached-but-empty list is a genuine
    /// empty state, not a reason to spin. No cache → skeleton + one real load.
    /// </summary>
    public PaymentHistoryViewModel()
    {
        _repo = new AccountRepository();
        LoadCmd = ReactiveCommand.CreateFromTask(Load);

        var cached = AccountCache.GetPayments();
        if (cached != null)
        {
            _loaded = true;
            Payments = MapRows(cached);
            Recompute();
        }
        else
        {
            Recompute();
            _ = Load();
        }
    }

    /// <summary>
    /// Design-time constructor. The five rows are the package's own live examples for this screen
    /// (screens.md «История платежей»: Пополнение баланса ×2 · Тариф Base · Пополнение баланса ×2,
    /// суммы 1 / 1 / 10 / 10 / 10 ₽), so the previewer and the screenshot harness show exactly what
    /// the reference frame shows. Dates are deliberately out of order in the literal — the newest-
    /// first sort has to earn the order on every preview, not be handed it.
    /// </summary>
    private PaymentHistoryViewModel(bool design)
    {
        _repo = null!;
        LoadCmd = ReactiveCommand.Create(() => { });
        _loaded = true;
        Payments = MapRows(new List<PaymentDto>
        {
            new() { Description = Common.L.T("History_SamplePlan"), Amount = 10, Currency = "RUB", Status = "paid", CreatedAt = "2026-05-05T11:20:00Z" },
            new() { Description = Common.L.T("History_SampleTopUp"), Amount = 10, Currency = "RUB", Status = "paid", CreatedAt = "2026-04-23T10:00:00Z" },
            new() { Description = Common.L.T("History_SampleTopUp"), Amount = 1, Currency = "RUB", Status = "paid", CreatedAt = "2026-08-09T12:00:00Z" },
            new() { Description = Common.L.T("History_SampleTopUp"), Amount = 10, Currency = "RUB", Status = "paid", CreatedAt = "2026-04-23T09:00:00Z" },
            new() { Description = Common.L.T("History_SampleTopUp"), Amount = 1, Currency = "RUB", Status = "paid", CreatedAt = "2026-08-03T09:00:00Z" },
        });
        Recompute();
    }

    public static PaymentHistoryViewModel CreateDesign() => new(true);

    #region load

    /// <summary>
    /// One full load: GET /client/payments → preformatted rows + warmed cache. Port of
    /// AccountViewModel.loadPayments + the activity's render/error split: a failure surfaces the
    /// error state only when there is nothing to show, otherwise the existing rows stay.
    /// </summary>
    private async Task Load()
    {
        RunOnUi(() =>
        {
            IsLoading = true;
            Error = null;
            Recompute();
        });

        var result = await _repo.GetPayments();
        RunOnUi(() =>
        {
            result
                .OnSuccess(p =>
                {
                    Payments = MapRows(p.Items);
                    // Warm the process-wide cache so the Account tab (separate viewmodel) and a
                    // re-entered history render instantly. Port of AccountCache.putPayments.
                    AccountCache.PutPayments(p.Items);
                })
                .OnFailure(e => Error = e);
            _loaded = true;
            IsLoading = false;
            Recompute();
        });
    }

    #endregion load

    #region derive display state

    /// <summary>Recomputes the error text + the mutually-exclusive screen state.</summary>
    private void Recompute()
    {
        ErrorText = Error != null ? MessageFor(Error) : string.Empty;

        var hasRows = Payments.Count > 0;
        var coldLoading = IsLoading || !_loaded;
        ShowList = hasRows;
        ShowError = !hasRows && Error != null;
        ShowLoading = !hasRows && Error == null && coldLoading;
        ShowEmpty = !hasRows && Error == null && !coldLoading;
    }

    /// <summary>
    /// The reason, in the user's words — it IS the headline of the error state, so it has to say
    /// something the generic line does not. Same mapping as the sibling «Устройства» screen: an
    /// expired session used to surface here as «Не удалось загрузить историю платежей», which sends
    /// the reader looking for a network problem that is not there instead of to the sign-in they need.
    /// </summary>
    private static string MessageFor(ApiError error) => error switch
    {
        ApiError.NetworkError => Common.L.T("Common_NetworkError"),
        ApiError.TimeoutError => Common.L.T("Common_Timeout"),
        ApiError.ServiceUnavailable => Common.L.T("Common_ServiceUnavailable"),
        ApiError.Unauthorized => Common.L.T("Common_SignInRequired"),
        ApiError.RateLimited => Common.L.T("Common_TooManyRequests"),
        _ => Common.L.T("History_ErrLoad"),
    };

    #endregion derive display state

    #region row mapping (ported 1:1 from PaymentsAdapter.kt)

    /// <summary>Newest first (ISO-8601 CreatedAt sorts chronologically as a plain ordinal string).</summary>
    private static List<PaymentRow> MapRows(IEnumerable<PaymentDto> items) =>
        items
            .OrderByDescending(p => p.CreatedAt, StringComparer.Ordinal)
            .Select(ToRow)
            .ToList();

    private static PaymentRow ToRow(PaymentDto p)
    {
        var (label, kind) = StatusStyle(p.Status);
        return new PaymentRow
        {
            Description = FirstNonBlank(p.Description, p.Kind, p.OrderId),
            Date = FormatIsoDate(p.CreatedAt),
            Amount = FormatMoney(p.Amount, p.Currency),
            StatusLabel = label,
            IsPaid = kind == StatusKind.Paid,
            IsPending = kind == StatusKind.Pending,
            IsFailed = kind == StatusKind.Failed,
            IsCanceled = kind == StatusKind.Canceled,
        };
    }

    private enum StatusKind
    {
        Neutral,
        Paid,
        Pending,
        Failed,
        Canceled,
    }

    /// <summary>
    /// Maps a raw payment status to its Russian label + chip colour kind. The value set is exactly
    /// PaymentsAdapter.statusStyle; anything unmapped keeps its raw text on the neutral chip.
    /// </summary>
    private static (string Label, StatusKind Kind) StatusStyle(string status) =>
        status.Trim().ToLowerInvariant() switch
        {
            "paid" or "success" or "succeeded" or "completed" or "confirmed" =>
                (Common.L.T("History_StatusPaid"), StatusKind.Paid),

            "pending" or "processing" or "new" or "created" or "waiting" or "in_progress" =>
                (Common.L.T("History_StatusProcessing"), StatusKind.Pending),

            "failed" or "error" or "declined" or "rejected" =>
                (Common.L.T("History_StatusFailed"), StatusKind.Failed),

            "canceled" or "cancelled" or "expired" =>
                (Common.L.T("History_StatusCanceled"), StatusKind.Canceled),

            _ => (status.Trim(), StatusKind.Neutral),
        };

    #endregion row mapping

    #region formatting helpers (same conventions as AccountViewModel)

    // RUB-only product (owner directive): RUB/blank/USD/unknown all render as the ruble sign; only
    // genuinely distinct currencies keep their own symbol. Same mapping as AccountViewModel so the
    // history never shows a different sign than the balance for the same backend money.
    private static string FormatMoney(double amount, string currency) => v2rayN.Desktop.Common.Money.WithCurrency(amount, currency);

    private static string CurrencySymbol(string currency) => currency.Trim().ToUpperInvariant() switch
    {
        "EUR" => "€",
        "KZT" => "₸",
        "UAH" => "₴",
        _ => "₽",
    };

    private static string FormatIsoDate(string? iso)
    {
        if (iso.IsNullOrEmpty())
        {
            return string.Empty;
        }
        var datePart = iso!.Split('T')[0];
        var parts = datePart.Split('-');
        return parts.Length == 3 ? $"{parts[2]}.{parts[1]}.{parts[0]}" : datePart;
    }

    private static string FirstNonBlank(params string?[] values)
    {
        foreach (var v in values)
        {
            if (v.IsNotEmpty())
            {
                return v!;
            }
        }
        return string.Empty;
    }

    private static void RunOnUi(Action action)
    {
        if (Dispatcher.UIThread.CheckAccess())
        {
            action();
        }
        else
        {
            Dispatcher.UIThread.Post(action);
        }
    }

    #endregion formatting helpers
}
