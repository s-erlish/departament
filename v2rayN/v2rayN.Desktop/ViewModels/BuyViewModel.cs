using System.Globalization;
using Avalonia.VisualTree;
using v2rayN.Desktop.Account;
using v2rayN.Desktop.Account.Dto;

namespace v2rayN.Desktop.ViewModels;

/// <summary>
/// Backs the «Купить подписку» screen. Port of V2rayNG ui/BuyTariffActivity.kt (+ PaymentMethodSheet.kt):
/// the user picks a tariff, a duration/price option and (optionally) extra devices, then taps «Оплатить»
/// to choose a payment method. DATA-DRIVEN: the catalog, prices, durations, devices, traffic and payment
/// methods all come from the departament API via <see cref="AccountRepository"/> — nothing is hardcoded.
///
/// State machine (mutually exclusive, port of renderState): skeleton (loading) / error / empty / content;
/// plus the post-checkout pending hint (re-polling after a browser payment) and the final
/// «Подписка оплачена» success state. The charged amount is always the displayed «Итого»
/// (option price + extra devices), so the total and the charge can never drift apart.
/// </summary>
public class BuyViewModel : MyReactiveObject
{
    /// <summary>Sheet row id of the «С баланса» method (PaymentMethodSheet.ID_BALANCE).</summary>
    public const string BalanceMethodId = "balance";

    /// <summary>Payment statuses that count as webhook-confirmed while re-polling GET /client/payments.</summary>
    private static readonly HashSet<string> PaidStatuses = new(StringComparer.OrdinalIgnoreCase)
    {
        "paid", "success", "succeeded", "completed", "confirmed", "done",
    };

    private const int PollAttempts = 5;
    private const int PollDelayMs = 8000;

    private readonly AccountRepository? _repo;

    // True once the catalog request has completed at least once, so an empty list can be
    // distinguished from "still loading" (otherwise an empty catalog shows a skeleton forever).
    private bool _loaded;
    private ApiError? _catalogError;

    private UserProfileDto? _profile;
    private PublicConfigDto? _publicConfig;

    // Selection state (port of selectedTariff/selectedOption/extraDevices).
    private TariffDto? _selectedTariff;
    private PriceOptionDto? _selectedOption;
    private int _extraDevices;

    private PaymentInitDto? _pendingInit;
    private CancellationTokenSource? _pollCts;

    #region reactive state

    /// <summary>Flattened catalog: every tariff of every group, in API order (group emoji is NOT rendered).</summary>
    [Reactive] public List<BuyTariffItem> Tariffs { get; set; } = new();

    // Mutually exclusive top-level states.
    [Reactive] public bool ShowSkeleton { get; set; }
    [Reactive] public bool ShowError { get; set; }
    [Reactive] public bool ShowEmpty { get; set; }
    [Reactive] public bool ShowContent { get; set; }
    [Reactive] public bool ShowSuccess { get; set; }

    [Reactive] public string ErrorText { get; set; } = string.Empty;
    [Reactive] public string EmptyText { get; set; } = string.Empty;

    /// <summary>Post-checkout hint: «Завершите оплату в браузере» → «Платёж обрабатывается…» while re-polling.</summary>
    [Reactive] public bool ShowPending { get; set; }
    [Reactive] public string PendingText { get; set; } = string.Empty;

    // Checkout card.
    [Reactive] public bool ShowCheckout { get; set; }
    [Reactive] public bool ShowExtraDevices { get; set; }
    [Reactive] public string ExtraCountText { get; set; } = "0";
    [Reactive] public bool HasExtraCost { get; set; }
    [Reactive] public string ExtraCostText { get; set; } = string.Empty;
    [Reactive] public bool CanDevMinus { get; set; }
    [Reactive] public bool CanDevPlus { get; set; }
    [Reactive] public string TotalText { get; set; } = string.Empty;

    /// <summary>True while a purchase request is in flight — the CTA is disabled to prevent double-charge.</summary>
    [Reactive] public bool IsPaying { get; set; }

    // Inline payment diagnostic (port of the «Ошибка оплаты» dialog: raw HTTP code + sanitized detail).
    [Reactive] public bool HasPaymentNotice { get; set; }
    [Reactive] public string PaymentNoticeTitle { get; set; } = string.Empty;
    [Reactive] public bool HasPaymentNoticeBody { get; set; }
    [Reactive] public string PaymentNoticeBody { get; set; } = string.Empty;

    // Payment-method sheet (port of PaymentMethodSheet).
    [Reactive] public bool IsSheetOpen { get; set; }
    [Reactive] public List<BuyPaymentMethodItem> SheetMethods { get; set; } = new();

    #endregion reactive state

    #region commands

    public ReactiveCommand<Unit, Unit> RetryCmd { get; }
    public ReactiveCommand<Unit, Unit> PayCmd { get; }
    public ReactiveCommand<Unit, Unit> DevMinusCmd { get; }
    public ReactiveCommand<Unit, Unit> DevPlusCmd { get; }
    public ReactiveCommand<Unit, Unit> CloseSheetCmd { get; }

    #endregion commands

    /// <summary>Runtime constructor: loads the real catalog/config/profile on activation.</summary>
    public BuyViewModel()
    {
        _repo = new AccountRepository();
        if (AccountSession.IsLoggedIn())
        {
            _profile = AuthTokenStore.GetUser();
        }

        RetryCmd = ReactiveCommand.CreateFromTask(Reload);
        PayCmd = ReactiveCommand.Create(OnPayClicked);
        DevMinusCmd = ReactiveCommand.Create(() => ChangeExtraDevices(-1));
        DevPlusCmd = ReactiveCommand.Create(() => ChangeExtraDevices(+1));
        CloseSheetCmd = ReactiveCommand.Create(CloseSheet);

        RenderState();
        _ = Reload();
    }

    /// <summary>Design-time constructor: sample catalog so the previewer renders. NEVER used at runtime.</summary>
    private BuyViewModel(bool design)
    {
        _repo = null;

        RetryCmd = ReactiveCommand.Create(() => { });
        PayCmd = ReactiveCommand.Create(OnPayClicked);
        DevMinusCmd = ReactiveCommand.Create(() => ChangeExtraDevices(-1));
        DevPlusCmd = ReactiveCommand.Create(() => ChangeExtraDevices(+1));
        CloseSheetCmd = ReactiveCommand.Create(CloseSheet);

        var baseTariff = new TariffDto
        {
            Id = "t-base",
            Name = "Base",
            IncludedDevices = 2,
            TrafficLimitBytes = null,
            MaxExtraDevices = 5,
            PricePerExtraDevice = 35.0,
            Currency = "RUB",
            PriceOptions = new List<PriceOptionDto>
            {
                new() { Id = "o-30", DurationDays = 30, Price = 150.0, SortOrder = 0 },
                new() { Id = "o-60", DurationDays = 60, Price = 300.0, SortOrder = 1 },
                new() { Id = "o-90", DurationDays = 90, Price = 400.0, SortOrder = 2 },
            },
        };
        // Сроки Plus — из пакета (screens.md «Купить подписку»): 30/260, 90/710, 365/2 600. Раньше
        // здесь был один срок, и превью не показывало ни второй строки со скидкой, ни того, как
        // раскрытая карточка выглядит с тремя сроками — то есть ровно тех состояний, ради которых
        // превью и существует.
        var plusTariff = new TariffDto
        {
            Id = "t-plus",
            Name = "Plus",
            IncludedDevices = 3,
            TrafficLimitBytes = null,
            MaxExtraDevices = 5,
            PricePerExtraDevice = 35.0,
            Currency = "RUB",
            PriceOptions = new List<PriceOptionDto>
            {
                new() { Id = "o-p30", DurationDays = 30, Price = 260.0, SortOrder = 0 },
                new() { Id = "o-p90", DurationDays = 90, Price = 710.0, SortOrder = 1 },
                new() { Id = "o-p365", DurationDays = 365, Price = 2600.0, SortOrder = 2 },
            },
        };
        BuildItems(new List<TariffGroupDto>
        {
            new() { Id = "g1", Name = "Тарифы", Tariffs = new List<TariffDto> { baseTariff, plusTariff } },
        });
        _loaded = true;
        RenderState();

        var baseItem = Tariffs[0];
        SelectTariff(baseItem);
        SelectOption(baseItem, baseItem.Options[2]);
        _extraDevices = 5;
        RenderExtraDevices(baseTariff);
        UpdateTotal();

        ShowPending = true;
        PendingText = Common.L.T("Buy_Processing");
        IsDesign = true;
    }

    public static BuyViewModel CreateDesign() => new(true);

    /// <summary>
    /// Модель собрана образцом каталога (превьювер / скриншот-хук), а не departament-API.
    /// Нужна ВЬЮ: бейдж «Текущий» приходит не из каталога, а из общего <c>AccountViewModel</c>,
    /// которого в превью нет вовсе — без этого признака бейдж в превью не показать, и проверить
    /// его глазами было бы негде. На живом пути флаг остаётся false и ни на что не влияет.
    /// </summary>
    public bool IsDesign { get; private set; }

    #region load / state machine

    /// <summary>(Re)fetches catalog + payment config + profile, showing the skeleton until the first result.</summary>
    private async Task Reload()
    {
        if (_repo == null)
        {
            return;
        }

        StopPolling();
        RunOnUi(() =>
        {
            _loaded = false;
            _catalogError = null;
            ShowSuccess = false;
            ShowPending = false;
            ShowCheckout = false;
            ClearPaymentNotice();
            _selectedTariff = null;
            _selectedOption = null;
            _extraDevices = 0;
            RenderState();
        });

        // Payment methods + balance load alongside the catalog (mirrors reload(): loadPublicConfig/refreshProfile).
        _ = LoadPublicConfig();
        _ = RefreshProfile();

        var result = await _repo.LoadCatalog();
        RunOnUi(() =>
        {
            result
                .OnSuccess(c => BuildItems(c.Items))
                .OnFailure(e => _catalogError = e);
            _loaded = true;
            RenderState();
        });
    }

    private async Task LoadPublicConfig()
    {
        if (_repo == null)
        {
            return;
        }
        var result = await _repo.LoadPublicConfig();
        RunOnUi(() => result.OnSuccess(c => _publicConfig = c));
    }

    private async Task RefreshProfile()
    {
        if (_repo == null || !AccountSession.IsLoggedIn())
        {
            return;
        }
        var result = await _repo.RefreshProfile();
        RunOnUi(() => result.OnSuccess(p => _profile = p));
    }

    /// <summary>Flattens the group catalog into selectable tariff items (the group emoji is never rendered).</summary>
    private void BuildItems(List<TariffGroupDto> groups)
    {
        var items = new List<BuyTariffItem>();
        foreach (var group in groups)
        {
            foreach (var tariff in group.Tariffs)
            {
                items.Add(new BuyTariffItem(this, tariff));
            }
        }
        Tariffs = items;
    }

    /// <summary>
    /// Single source of truth for the top-level state (port of renderState). An empty catalog is only
    /// «Тарифы недоступны» once <see cref="_loaded"/> is set — before that it still reads as loading.
    /// </summary>
    private void RenderState()
    {
        var hasAny = Tariffs.Count > 0;
        if (ShowSuccess)
        {
            ShowContent = false;
            ShowSkeleton = false;
            ShowError = false;
            ShowEmpty = false;
            return;
        }

        ShowContent = hasAny;
        ShowError = !hasAny && _catalogError != null;
        ShowEmpty = !hasAny && _catalogError == null && _loaded;
        ShowSkeleton = !hasAny && _catalogError == null && !_loaded;

        ErrorText = ShowError ? Common.L.T("Buy_ErrLoadPlans") : string.Empty;
        EmptyText = ShowEmpty ? Common.L.T("Buy_NoPlans") : string.Empty;
    }

    #endregion load / state machine

    #region selection (port of selectTariff/selectOption/changeExtraDevices)

    public void SelectTariff(BuyTariffItem item)
    {
        // ПОВТОРНОЕ нажатие по раскрытому тарифу — сворачивает его (screens.md «Купить подписку»:
        // «Открыт всегда один тариф; повторное нажатие сворачивает»). Раньше метод на этом месте
        // молча выходил: каретка обещала «нажми ещё раз — закроется», а карточка не закрывалась,
        // и вернуть экран в исходный вид (оба тарифа свёрнуты) было нечем.
        if (item.IsSelected)
        {
            CollapseTariffs();
            return;
        }

        _selectedTariff = item.Tariff;
        _selectedOption = null;
        _extraDevices = 0;
        ClearPaymentNotice();

        foreach (var t in Tariffs)
        {
            t.IsSelected = ReferenceEquals(t, item);
            foreach (var o in t.Options)
            {
                o.IsSelected = false;
            }
        }

        // A tariff with a single option → preselect it for a shorter flow.
        if (item.Options.Count == 1)
        {
            SelectOption(item, item.Options[0]);
        }
        else
        {
            ShowCheckout = false;
        }
    }

    /// <summary>
    /// Сворачивает всё: ни один тариф не раскрыт, срок не выбран, чекаут и кнопка оплаты уходят
    /// (кнопка НЕСЁТ сумму — без выбранного срока суммы нет). Диагностика прошлой оплаты снимается
    /// вместе с выбором: она относилась именно к нему.
    /// </summary>
    private void CollapseTariffs()
    {
        _selectedTariff = null;
        _selectedOption = null;
        _extraDevices = 0;
        ClearPaymentNotice();

        foreach (var t in Tariffs)
        {
            t.IsSelected = false;
            foreach (var o in t.Options)
            {
                o.IsSelected = false;
            }
        }

        ShowCheckout = false;
        ShowExtraDevices = false;
    }

    public void SelectOption(BuyTariffItem item, BuyOptionItem option)
    {
        if (!item.IsSelected)
        {
            SelectTariff(item);
        }

        _selectedTariff = item.Tariff;
        _selectedOption = option.Option;
        foreach (var o in item.Options)
        {
            o.IsSelected = ReferenceEquals(o, option);
        }

        SetupExtraDevices(item.Tariff);
        ShowCheckout = true;
        UpdateTotal();
    }

    private void SetupExtraDevices(TariffDto tariff)
    {
        var max = Math.Max(0, tariff.MaxExtraDevices);
        ShowExtraDevices = max > 0;
        _extraDevices = Math.Clamp(_extraDevices, 0, max);
        RenderExtraDevices(tariff);
    }

    private void ChangeExtraDevices(int delta)
    {
        var tariff = _selectedTariff;
        if (tariff == null)
        {
            return;
        }
        var max = Math.Max(0, tariff.MaxExtraDevices);
        _extraDevices = Math.Clamp(_extraDevices + delta, 0, max);
        RenderExtraDevices(tariff);
        UpdateTotal();
    }

    private void RenderExtraDevices(TariffDto tariff)
    {
        ExtraCountText = _extraDevices.ToString(CultureInfo.InvariantCulture);

        // Make the stepper bounds visible: disable (0.38 opacity) the button that can't move further.
        var max = Math.Max(0, tariff.MaxExtraDevices);
        CanDevMinus = _extraDevices > 0;
        CanDevPlus = _extraDevices < max;

        var cost = _extraDevices * tariff.PricePerExtraDevice;
        HasExtraCost = _extraDevices > 0 && cost > 0.0;
        ExtraCostText = HasExtraCost ? $"+ {FormatMoney(cost, tariff.Currency)}" : string.Empty;
    }

    /// <summary>
    /// The single source of truth for the price: option price + extra devices. This exact value is both
    /// shown as «Итого» and sent as the charged amount, so they can never drift apart.
    /// </summary>
    private double CurrentTotal(TariffDto tariff, PriceOptionDto option) =>
        option.Price + _extraDevices * tariff.PricePerExtraDevice;

    private void UpdateTotal()
    {
        var tariff = _selectedTariff;
        var option = _selectedOption;
        if (tariff == null || option == null)
        {
            return;
        }
        TotalText = FormatMoney(CurrentTotal(tariff, option), tariff.Currency);
    }

    #endregion selection

    #region checkout (port of onPayClicked/onMethodPicked/openCheckout/startPaymentPolling)

    private void OnPayClicked()
    {
        ClearPaymentNotice();
        if (_selectedTariff == null || _selectedOption == null)
        {
            ShowNotice(Common.L.T("Buy_ChoosePeriod"));
            return;
        }

        var methods = _publicConfig?.PlategaMethods ?? new List<PlategaMethodDto>();
        if (methods.Count == 0)
        {
            ShowNotice(Common.L.T("Buy_NoPaymentMethods"));
            return;
        }

        // Balance row first (green differentiator), then the Platega methods (СБП, карта…).
        var rows = new List<BuyPaymentMethodItem>();
        var profile = _profile;
        if (profile != null)
        {
            var balanceLabel = FormatMoney(profile.Balance, profile.Currency);
            rows.Add(new BuyPaymentMethodItem(this, BalanceMethodId, Common.L.F("Buy_FromBalance", balanceLabel), isBalance: true, isSbp: false));
        }
        foreach (var m in methods)
        {
            var isSbp = m.Id.Contains("sbp", StringComparison.OrdinalIgnoreCase) ||
                        m.Label.Contains("СБП", StringComparison.OrdinalIgnoreCase);
            rows.Add(new BuyPaymentMethodItem(this, m.Id, m.Label, isBalance: false, isSbp: isSbp));
        }

        SheetMethods = rows;
        IsSheetOpen = true;
    }

    public void CloseSheet()
    {
        IsSheetOpen = false;
    }

    public async Task PickMethod(BuyPaymentMethodItem method)
    {
        IsSheetOpen = false;
        var tariff = _selectedTariff;
        var option = _selectedOption;
        if (_repo == null || tariff == null || option == null || IsPaying)
        {
            return;
        }

        ClearPaymentNotice();
        IsPaying = true;

        // Charge exactly the displayed «Итого» (option price + extra devices), never the bare option price.
        var req = new PaymentRequestDto
        {
            TariffId = tariff.Id,
            TariffPriceOptionId = option.Id,
            DeviceCount = _extraDevices > 0 ? _extraDevices : null,
            Amount = CurrentTotal(tariff, option),
            Currency = tariff.Currency.IsNotEmpty() ? tariff.Currency : "RUB",
        };

        if (method.IsBalance)
        {
            var result = await _repo.PayWithBalance(req);
            RunOnUi(() =>
            {
                IsPaying = false;
                result
                    .OnSuccess(dto =>
                    {
                        // Read the reply, don't take the 2xx as a receipt: this endpoint answers 200
                        // both for a debited wallet and for a refusal (insufficient funds). «Подписка
                        // оплачена» over a refusal is the one message this screen must never show.
                        switch (dto.Settlement())
                        {
                            case BalanceSettlement.Settled:
                                SetSuccess();
                                break;
                            case BalanceSettlement.Rejected:
                                ShowNotice(Common.L.T("Buy_PaymentError"));
                                break;
                            default:
                                // Accepted but not settled in-request: keep the checkout open and let
                                // the ordinary payment poll decide, exactly as the card path does.
                                _pendingInit = null;
                                PendingText = Common.L.T("Buy_Processing");
                                ShowPending = true;
                                break;
                        }
                    })
                    .OnFailure(ShowPaymentError);
            });
        }
        else
        {
            req.PaymentMethod = int.TryParse(method.Id, out var methodCode) ? methodCode : null;
            var result = await _repo.Buy(req);
            RunOnUi(() =>
            {
                IsPaying = false;
                result
                    .OnSuccess(OpenCheckout)
                    .OnFailure(ShowPaymentError);
            });
        }
    }

    /// <summary>Opens the provider checkout URL in the external browser, then re-polls for the webhook result.</summary>
    private void OpenCheckout(PaymentInitDto init)
    {
        var url = init.PaymentUrl;
        if (url.IsNullOrEmpty())
        {
            ShowNotice(Common.L.T("Common_CouldntOpenPayment"));
            return;
        }

        // Checked, not assumed: ProcUtils.ProcessStart swallows its failures, so the catch this
        // replaces could never fire — a machine with no browser handler was told «завершите оплату
        // в браузере» and then polled for forty seconds for a payment nobody could make.
        if (!ProcUtils.TryProcessStart(url))
        {
            ShowNotice(Common.L.T("Common_CouldntOpenPayment"));
            return;
        }

        _pendingInit = init;
        PendingText = Common.L.T("Common_CompletePaymentInBrowser");
        ShowPending = true;
        StartPolling();
    }

    /// <summary>
    /// The backend only confirms PAID via webhook, so the browser returning proves nothing: re-poll
    /// profile + payment history a few times over ~40s while the pending hint is up. A confirmed order
    /// flips to «Подписка оплачена»; otherwise the hint quietly retires (port of startPaymentPolling).
    /// </summary>
    private void StartPolling()
    {
        StopPolling();
        var cts = new CancellationTokenSource();
        _pollCts = cts;
        _ = PollAsync(cts.Token);
    }

    private void StopPolling()
    {
        _pollCts?.Cancel();
        _pollCts = null;
    }

    private async Task PollAsync(CancellationToken ct)
    {
        if (_repo == null)
        {
            return;
        }
        try
        {
            for (var i = 0; i < PollAttempts; i++)
            {
                await Task.Delay(PollDelayMs, ct);
                RunOnUi(() => PendingText = Common.L.T("Buy_Processing"));

                _ = RefreshProfile();
                var payments = await _repo.GetPayments();
                if (ct.IsCancellationRequested)
                {
                    return;
                }
                if (IsPendingPaymentConfirmed(payments.GetOrNull()))
                {
                    RunOnUi(SetSuccess);
                    return;
                }
            }
            RunOnUi(() => ShowPending = false);
        }
        catch (OperationCanceledException)
        {
            // Superseded by a reload or a newer purchase.
        }
    }

    private bool IsPendingPaymentConfirmed(PaymentsDto? payments)
    {
        var init = _pendingInit;
        if (payments == null || init == null)
        {
            return false;
        }
        return payments.Items.Any(p =>
            PaidStatuses.Contains(p.Status.Trim()) &&
            ((init.OrderId.IsNotEmpty() && p.OrderId == init.OrderId) ||
             (init.PaymentId.IsNotEmpty() && p.Id == init.PaymentId)));
    }

    private void SetSuccess()
    {
        StopPolling();
        _pendingInit = null;
        ShowPending = false;
        IsSheetOpen = false;
        ShowSuccess = true;
        RenderState();

        // A confirmed purchase must behave exactly like a fresh login: import the just-bought
        // subscription's servers and refresh Home + Account. Buy owns a SEPARATE VM from the shared
        // AccountViewModel, so this reruns the login-path steps (import → RefreshServers) itself and
        // routes the Account-tab reload through the shared VM instance (public RetryCmd).
        _ = RefreshAfterPurchase();
    }

    /// <summary>
    /// Post-purchase sync (mirror of the login path AccountViewModel.AutoImportAndRefreshHome + LoadAll):
    /// 1) auto-import the account subscriptions so the new servers land in the DB;
    /// 2) reload the engine server list on the running MainWindow (RefreshServers only re-reads the DB —
    ///    it NEVER starts the core, so this can't silently connect);
    /// 3) run the shared AccountViewModel's public full reload so its balance/subscription reflect the buy.
    /// A transient import failure is swallowed here (the success state still stands); the shared refresh
    /// re-reports any real API error on the Account tab.
    /// </summary>
    private async Task RefreshAfterPurchase()
    {
        if (_repo == null)
        {
            return;
        }

        try
        {
            await _repo.AutoImportSubscriptions();
        }
        catch (Exception ex)
        {
            Logging.SaveLog("BuyAutoImport", ex);
        }

        RunOnUi(() =>
        {
            try
            {
                if (Application.Current?.ApplicationLifetime is not IClassicDesktopStyleApplicationLifetime desktop
                    || desktop.MainWindow is not { } window)
                {
                    return;
                }

                // Home: repopulate ProfilesViewModel.ProfileItems so the imported servers appear and the
                // empty/onboarding state flips off (same call the login path uses via IViewFor).
                if (window is IViewFor<MainWindowViewModel> { ViewModel: { } main })
                {
                    _ = main.ProfilesViewModel.RefreshServers();
                }

                // Account tab: the ONE shared AccountViewModel is the DataContext of the account view in
                // the running window; run its public full reload (RetryCmd → LoadAll) so profile balance
                // and the subscription list reflect the purchase without editing AccountViewModel.
                var accountVm = (window as Visual)?
                    .GetVisualDescendants()
                    .OfType<Control>()
                    .Select(c => c.DataContext)
                    .OfType<AccountViewModel>()
                    .FirstOrDefault();
                accountVm?.RetryCmd.Execute().Subscribe();
            }
            catch (Exception ex)
            {
                Logging.SaveLog("BuyRefreshAfterPurchase", ex);
            }
        });
    }

    #endregion checkout

    #region payment diagnostics (port of showPaymentErrorDialog)

    private void ShowNotice(string title)
    {
        PaymentNoticeTitle = title;
        PaymentNoticeBody = string.Empty;
        HasPaymentNoticeBody = false;
        HasPaymentNotice = true;
    }

    /// <summary>Inline «Ошибка оплаты» diagnostic with the raw HTTP code + sanitized backend detail.</summary>
    private void ShowPaymentError(ApiError error)
    {
        var code = error switch
        {
            ApiError.Unauthorized => "401/403",
            ApiError.Server server => server.Code.ToString(CultureInfo.InvariantCulture),
            ApiError.RateLimited => "429",
            ApiError.ServiceUnavailable => "502/503",
            ApiError.NotFoundError => "404",
            ApiError.GoneError => "410",
            ApiError.TimeoutError => "timeout",
            ApiError.NetworkError => "network",
            ApiError.Parse => "parse",
            ApiError.NotConfiguredError => "not-configured",
            // Отказ, у которого нет ни кода, ни имени: диагностической строки просто НЕ БУДЕТ.
            // Раньше здесь стоял прочерк, и пользователь получал «HTTP —» — слово «HTTP» рядом с
            // символом, который не значит ничего. Заголовок «Ошибка оплаты» и так на месте.
            _ => null,
        };
        var detail = error switch
        {
            ApiError.Unauthorized unauthorized => unauthorized.Detail,
            ApiError.Server server => server.Detail,
            _ => null,
        };

        PaymentNoticeTitle = Common.L.T("Buy_PaymentError");
        var head = code.IsNullOrEmpty() ? null : $"HTTP {code}";
        PaymentNoticeBody = string.Join("\n", new[] { head, detail }.Where(line => line.IsNotEmpty()));
        HasPaymentNoticeBody = PaymentNoticeBody.IsNotEmpty();
        HasPaymentNotice = true;
    }

    private void ClearPaymentNotice()
    {
        HasPaymentNotice = false;
        HasPaymentNoticeBody = false;
        PaymentNoticeTitle = string.Empty;
        PaymentNoticeBody = string.Empty;
    }

    #endregion payment diagnostics

    #region formatting helpers (ported 1:1 from BuyTariffActivity)

    /// <summary>Whole amounts render without decimals; a blank currency renders as a bare number.</summary>
    internal static string FormatMoney(double amount, string currency) => v2rayN.Desktop.Common.Money.WithCurrency(amount, currency);

    /// <summary>Maps an ISO currency code to a trailing symbol: RUB→₽, USD→$, EUR→€, KZT→₸, UAH→₴.</summary>
    private static string CurrencySymbol(string currency) => currency.Trim().ToUpperInvariant() switch
    {
        "RUB" => "₽",
        "USD" => "$",
        "EUR" => "€",
        "KZT" => "₸",
        "UAH" => "₴",
        _ => currency,
    };

    //  Объём тарифа тем же счётом, что трафик на «Главной» и «Аккаунте» (ByteSize.Bytes): своя копия
    //  форматтера писала ТОЧКУ посреди русского текста — «100.0 ГБ».
    internal static string FormatBytes(long bytes) => Common.ByteSize.Bytes(bytes);

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

/// <summary>
/// One selectable tariff card: name + «Устройства: N · Трафик: ∞|X ГБ» summary + duration/price options
/// (revealed when selected). Values are derived from the API <see cref="TariffDto"/> — never hardcoded.
/// </summary>
public class BuyTariffItem : ReactiveObject
{
    public TariffDto Tariff { get; }
    public string Name { get; }
    public string Info { get; }
    public List<BuyOptionItem> Options { get; }
    public ReactiveCommand<Unit, Unit> SelectCmd { get; }

    [Reactive] public bool IsSelected { get; set; }

    public BuyTariffItem(BuyViewModel owner, TariffDto tariff)
    {
        Tariff = tariff;
        Name = tariff.Name;

        var traffic = tariff.IsUnlimitedTraffic() || (tariff.TrafficLimitBytes ?? 0L) <= 0L
            ? "∞"
            : BuyViewModel.FormatBytes(tariff.TrafficLimitBytes ?? 0L);
        Info = Common.L.F("Buy_DevicesTraffic", tariff.IncludedDevices, traffic);

        // Options sorted by the API sort order; a tariff without options falls back to its own
        // duration/price as a single synthetic option (port of optionsOf).
        var options = tariff.PriceOptions.OrderBy(o => o.SortOrder).ToList();
        if (options.Count == 0)
        {
            options = new List<PriceOptionDto>
            {
                new() { Id = tariff.Id, DurationDays = tariff.DurationDays, Price = tariff.Price },
            };
        }
        Options = options.Select(o => new BuyOptionItem(owner, this, o, tariff.Currency)).ToList();

        SelectCmd = ReactiveCommand.Create(() => owner.SelectTariff(this));
    }
}

/// <summary>One duration/price row inside a tariff card: «30 дн.» left, «150 ₽» right.</summary>
public class BuyOptionItem : ReactiveObject
{
    public PriceOptionDto Option { get; }
    public string DurationText { get; }
    public string PriceText { get; }
    public ReactiveCommand<Unit, Unit> SelectCmd { get; }

    [Reactive] public bool IsSelected { get; set; }

    public BuyOptionItem(BuyViewModel owner, BuyTariffItem parent, PriceOptionDto option, string currency)
    {
        Option = option;
        DurationText = Common.L.F("Common_DaysShort", option.DurationDays);
        PriceText = BuyViewModel.FormatMoney(option.Price, currency);
        SelectCmd = ReactiveCommand.Create(() => owner.SelectOption(parent, this));
    }
}

/// <summary>
/// One row of the «Способ оплаты» sheet: the optional «С баланса» row (green tile) first, then the
/// Platega methods from PublicConfigDto (СБП/карта — blue tile). Port of PaymentMethodSheet rows.
/// </summary>
public class BuyPaymentMethodItem
{
    public string Id { get; }
    public string Label { get; }
    public bool IsBalance { get; }
    public bool IsSbp { get; }
    public bool IsCard { get; }
    public ReactiveCommand<Unit, Unit> PickCmd { get; }

    public BuyPaymentMethodItem(BuyViewModel owner, string id, string label, bool isBalance, bool isSbp)
    {
        Id = id;
        Label = label;
        IsBalance = isBalance;
        IsSbp = !isBalance && isSbp;
        IsCard = !isBalance && !isSbp;
        PickCmd = ReactiveCommand.CreateFromTask(() => owner.PickMethod(this));
    }
}
