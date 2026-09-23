using System.Text.RegularExpressions;
using Avalonia.Animation;
using Avalonia.Data.Converters;
using v2rayN.Desktop.Account.Dto;
using v2rayN.Desktop.Common;
using v2rayN.Desktop.ViewModels;

namespace v2rayN.Desktop.Views;

/// <summary>
/// Экран «Купить подписку»: лекало подэкрана (назад + заголовок + пояснение), состояния
/// скелет/ошибка/пусто/успех, post-checkout hint, карты тарифов с опциями срока, карта чекаута
/// (степпер доп-устройств + «Итого» + «Оплатить») и оверлей-шит «Способ оплаты». Порт Android
/// activity_buy_tariff.xml + item_buy_tariff.xml + item_buy_option.xml + BuyTariffActivity.kt +
/// PaymentMethodSheet.kt. DATA-DRIVEN: всё биндится к <see cref="BuyViewModel"/> (departament-API),
/// никаких зашитых тарифов/цен.
///
/// Самодостаточен: DataContext ставит сам (как AccountView), design-time — пример каталога для
/// превьювера. Навигация назад отдана наружу через <see cref="BackRequested"/>.
///
/// ЗДЕСЬ ЖЕ живут ТРИ производные величины карточки, которых нет в <see cref="BuyViewModel"/>
/// (он — не файл этого экрана): цена за месяц у свёрнутой карточки, «Выгода N ₽» у строки срока
/// и бейдж «Текущий». Все три считаются ИЗ ТЕХ ЖЕ данных каталога и ровно по формулам Android
/// (BuyTariffActivity.monthlyRate / savingOn / isCurrentTariff), чтобы телефон и десктоп показывали
/// одно и то же число. Ничего не зашито: нет опций — нет и цены за месяц.
/// </summary>
public partial class BuyView : UserControl
{
    /// <summary>Средняя длина месяца — знаменатель цены «в месяц» (Android BuyTariffActivity: 30.44).</summary>
    private const double DaysPerMonth = 30.44;

    /// <summary>Число в денежной строке: «2600 ₽» · «135.29 ₽» · «+ 175 ₽» · «С баланса — 10 ₽».</summary>
    private static readonly Regex MoneyNumber = new(@"\d+(\.\d+)?", RegexOptions.CultureInvariant);

    /// <summary>Русский денежный набор: запятая в дробной части, НЕРАЗРЫВНЫЙ пробел в разрядах.</summary>
    private static readonly NumberFormatInfo RuMoney = BuildRuMoney();

    /// <summary>
    /// Id тарифов, которые у аккаунта уже есть — по ним карточка получает бейдж «Текущий».
    /// Свойство ВЬЮ, а не модели: каталог тарифов не знает, чем владеет пользователь, это знает
    /// общий <see cref="AccountViewModel"/> (порт isCurrentTariff, который читает тот же список
    /// подписок). StyledProperty — чтобы бейдж загорелся, когда подписки доедут ПОСЛЕ каталога.
    /// </summary>
    public static readonly StyledProperty<IReadOnlyCollection<string>?> CurrentTariffIdsProperty =
        AvaloniaProperty.Register<BuyView, IReadOnlyCollection<string>?>(nameof(CurrentTariffIds));

    /// <summary>Возникает по back-шеврону тулбара; обработку (закрыть суб-страницу) вешает хост.</summary>
    public event EventHandler? BackRequested;

    private IDisposable? _totalSub;
    private IDisposable? _contentSub;
    private IDisposable? _subsSub;
    private bool _firstTotal = true;

    public BuyView()
    {
        InitializeComponent();
        DataContext = Design.IsDesignMode ? BuyViewModel.CreateDesign() : new BuyViewModel();

        // Esc закрывает шит «Способ оплаты» (клавиатурный путь к «тап-вне»).
        AddHandler(KeyDownEvent, OnPreviewKeyDown, RoutingStrategies.Tunnel);

        BindViewModel();
        // Хост (превью-хук MainWindow) подменяет DataContext ПОСЛЕ конструктора — тогда «Итого»
        // осталось бы пустым, потому что подписка висела бы на прежней модели. Пересобираем.
        DataContextChanged += (_, _) => BindViewModel();
    }

    public IReadOnlyCollection<string>? CurrentTariffIds
    {
        get => GetValue(CurrentTariffIdsProperty);
        set => SetValue(CurrentTariffIdsProperty, value);
    }

    private BuyViewModel? Vm => DataContext as BuyViewModel;

    // ── Подписки на модель ──

    private void BindViewModel()
    {
        _totalSub?.Dispose();
        _contentSub?.Dispose();
        _firstTotal = true;

        if (Vm is not { } vm)
        {
            return;
        }

        // «Итого» набрано как ДЕНЬГИ (сумма + приглушённый ₽) и КРОССФЕЙДИТСЯ при пересчёте
        // (степпер устройств / смена срока) — та же грамматика денег, что и hero-баланс.
        _totalSub = vm.WhenAnyValue(v => v.TotalText).Subscribe(SetTotal);
        // Тарифы показались → стаггер входа карт (по одной, +40мс), один раз.
        _contentSub = vm.WhenAnyValue(v => v.ShowContent).Subscribe(OnContentShown);
    }

    /// <summary>
    /// Пока экран на виду — слушаем подписки аккаунта, чтобы бейдж «Текущий» появился и на тех
    /// тарифах, чьи подписки доехали позже каталога. Отписка на уходе: общий AccountViewModel живёт
    /// дольше подэкрана, и незакрытая подписка держала бы вью в памяти.
    /// </summary>
    protected override void OnAttachedToVisualTree(VisualTreeAttachmentEventArgs e)
    {
        base.OnAttachedToVisualTree(e);

        // Образец каталога (превьювер / скриншот-хук): подписок аккаунта нет и быть не может,
        // поэтому «текущим» помечаем первый тариф образца — иначе бейдж не увидеть НИГДЕ, а он
        // в лекале есть. Живого пути это не касается: у настоящей модели IsDesign = false.
        if (Vm is { IsDesign: true } design)
        {
            CurrentTariffIds = design.Tariffs.Take(1).Select(t => t.Tariff.Id).ToList();
            return;
        }

        if (Design.IsDesignMode || AccountViewModel.Shared is not { } account)
        {
            return;
        }
        _subsSub = account.WhenAnyValue(v => v.Subscriptions).Subscribe(SetCurrentTariffs);
    }

    protected override void OnDetachedFromVisualTree(VisualTreeAttachmentEventArgs e)
    {
        _subsSub?.Dispose();
        _subsSub = null;
        base.OnDetachedFromVisualTree(e);
    }

    private void SetCurrentTariffs(List<SubInfoDto>? subs)
    {
        var ids = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var sub in subs ?? new List<SubInfoDto>())
        {
            var id = sub.TariffId?.Trim();
            if (id.IsNotEmpty())
            {
                ids.Add(id!);
            }
        }
        CurrentTariffIds = ids;
    }

    // ── Производные величины каталога (порт BuyTariffActivity) ──

    /// <summary>
    /// Сроки тарифа. Пустой список опций подменяется одной синтетической из собственных
    /// срока/цены тарифа — ровно как <see cref="BuyTariffItem"/> строит свои строки (optionsOf).
    /// </summary>
    internal static IReadOnlyList<PriceOptionDto> OptionsOf(TariffDto tariff) =>
        tariff.PriceOptions.Count > 0
            ? tariff.PriceOptions
            : new List<PriceOptionDto> { new() { Id = tariff.Id, DurationDays = tariff.DurationDays, Price = tariff.Price } };

    /// <summary>
    /// Цена входа в тариф: стоимость САМОГО КОРОТКОГО срока, ровно как её задали в панели.
    ///
    /// Здесь считалась самая выгодная ставка за месяц по всем срокам — и заголовок карточки врал.
    /// У тарифа с ценами 150 за месяц и 400 за три на карточке стояло «135», потому что 400 делили
    /// на 2,96 месяца; человек видел число, которого нет ни в одном ценнике, и не мог купить за
    /// него. Владелец: «надо ровные цены вернуть».
    ///
    /// Выгода длинных сроков никуда не делась — она подписана у самих сроков внутри карточки
    /// (<see cref="Saving"/>), где рядом стоит и настоящая цена, и срок, за который её платят.
    ///
    /// Тариф без пригодных сроков не показывает НИЧЕГО — лучше пустое место, чем ноль.
    /// </summary>
    internal static PriceOptionDto? EntryOption(TariffDto tariff)
    {
        PriceOptionDto? entry = null;
        foreach (var option in OptionsOf(tariff))
        {
            if (option.DurationDays <= 0 || option.Price <= 0.0)
            {
                continue;
            }
            if (entry is null || option.DurationDays < entry.DurationDays)
            {
                entry = option;
            }
        }
        return entry;
    }

    /// <summary>
    /// Выгода срока против САМОГО КОРОТКОГО срока того же тарифа (порт savingOn): каталог, который
    /// перестал давать скидку, перестаёт её и показывать. Мельче рубля — не выгода, а шум округления.
    /// </summary>
    internal static double? Saving(TariffDto tariff, PriceOptionDto option)
    {
        if (option.DurationDays <= 0 || option.Price <= 0.0)
        {
            return null;
        }

        PriceOptionDto? basis = null;
        foreach (var candidate in OptionsOf(tariff))
        {
            if (candidate.DurationDays <= 0 || candidate.Price <= 0.0)
            {
                continue;
            }
            if (basis is null || candidate.DurationDays < basis.DurationDays)
            {
                basis = candidate;
            }
        }
        if (basis is null || OptionKey(basis) == OptionKey(option))
        {
            return null;
        }

        var expected = basis.Price / basis.DurationDays * option.DurationDays;
        var saving = expected - option.Price;
        return saving >= 1.0 ? saving : null;
    }

    private static string OptionKey(PriceOptionDto option) =>
        option.Id.IsNotEmpty() ? option.Id : $"{option.DurationDays}:{option.Price}";

    // ── Деньги ──

    /// <summary>Сумма в валюте тарифа, набранная по языку интерфейса.</summary>
    internal static string Money(double amount, string currency) =>
        LocalizeMoney(BuyViewModel.FormatMoney(amount, currency));

    /// <summary>
    /// Перенабор денежной строки под язык интерфейса. <see cref="BuyViewModel.FormatMoney"/> печатает
    /// число в инвариантной культуре — «2600 ₽», «135.29 ₽»; по-русски деньги читаются как
    /// «2 600 ₽» и «135,29 ₽» (пакет, раздел «Купить подписку»). Меняется ТОЛЬКО набор: ни само
    /// значение, ни знак валюты converter не трогает, и списывается всегда сумма модели.
    /// </summary>
    internal static string LocalizeMoney(string? text)
    {
        var s = text ?? string.Empty;
        if (s.Length == 0)
        {
            return s;
        }
        return MoneyNumber.Replace(s, Regroup, 1);
    }

    private static string Regroup(Match match)
    {
        var raw = match.Value;
        if (!double.TryParse(raw, NumberStyles.Float, CultureInfo.InvariantCulture, out var value))
        {
            return raw;
        }
        var dot = raw.IndexOf('.');
        var decimals = dot < 0 ? 0 : raw.Length - dot - 1;
        var pattern = decimals > 0 ? "#,##0." + new string('0', decimals) : "#,##0";
        var format = L.Instance.CurrentLang == "en" ? CultureInfo.GetCultureInfo("en-US").NumberFormat : RuMoney;
        return value.ToString(pattern, format);
    }

    private static NumberFormatInfo BuildRuMoney()
    {
        var nfi = (NumberFormatInfo)CultureInfo.GetCultureInfo("ru-RU").NumberFormat.Clone();
        nfi.NumberDecimalSeparator = ",";
        // Неразрывный: разряды одного числа не должны разъезжаться по строкам.
        nfi.NumberGroupSeparator = " ";
        return nfi;
    }

    // ── «Итого»: набор денег + кроссфейд суммы на реальное изменение ──

    private void SetTotal(string? text)
    {
        var (amount, currency) = SplitMoney(LocalizeMoney(text));
        TotalAmount.Text = amount;
        TotalCurrency.Text = currency;

        if (_firstTotal)
        {
            _firstTotal = false;
            return;
        }
        if (MotionState.IsLite)
        {
            return;
        }
        var anim = new Animation
        {
            Duration = Motion.Dur.State,
            Easing = Motion.Ease.Standard,
            FillMode = FillMode.Forward,
            Children =
            {
                new KeyFrame
                {
                    Cue = new Cue(0d),
                    Setters = { new Setter(OpacityProperty, 0.25d), new Setter(TranslateTransform.YProperty, -6d) },
                },
                new KeyFrame
                {
                    Cue = new Cue(1d),
                    Setters = { new Setter(OpacityProperty, 1d), new Setter(TranslateTransform.YProperty, 0d) },
                },
            },
        };
        _ = anim.RunAsync(TotalAmount);
    }

    // «150 ₽» → ("150", "₽"); бланк-валюта («150») → ("150", ""). Разбиваем по ПОСЛЕДНЕМУ ОБЫЧНОМУ
    // пробелу — разряды числа склеены НЕРАЗРЫВНЫМ, поэтому «2 600 ₽» делится там, где нужно.
    private static (string amount, string currency) SplitMoney(string? text)
    {
        var s = text?.Trim() ?? string.Empty;
        if (s.Length == 0)
        {
            return (string.Empty, string.Empty);
        }
        var idx = s.LastIndexOf(' ');
        return idx <= 0 ? (s, string.Empty) : (s[..idx], s[(idx + 1)..]);
    }

    // ── Стаггер входа карт тарифов (одна легитимная list-стаггер-анимация) ──

    private void OnContentShown(bool shown)
    {
        if (!shown || MotionState.IsLite)
        {
            return;
        }
        // Даём ItemsControl разложиться (реализовать контейнеры), затем стаггерим их вход.
        Dispatcher.UIThread.Post(StaggerTariffs, DispatcherPriority.Background);
    }

    private void StaggerTariffs()
    {
        try
        {
            var i = 0;
            foreach (var container in TariffList.GetRealizedContainers())
            {
                // Ограничиваем задержку (карта 6+ не «опаздывает») — весь стаггер ≤ ~240мс.
                var delay = TimeSpan.FromMilliseconds(Math.Min(i, 5) * Motion.Dur.Stagger.TotalMilliseconds);
                RunCardEntrance(container, delay);
                i++;
            }
        }
        catch
        {
            // Контейнеры ещё не реализованы / гонка показа — карты всё равно проявит родительский reveal-fade.
        }
    }

    private static void RunCardEntrance(Control card, TimeSpan delay)
    {
        var anim = new Animation
        {
            Duration = Motion.Dur.Reveal,
            Delay = delay,
            Easing = Motion.Ease.OutQuint,
            FillMode = FillMode.Both,
            Children =
            {
                new KeyFrame
                {
                    Cue = new Cue(0d),
                    Setters = { new Setter(OpacityProperty, 0d), new Setter(TranslateTransform.YProperty, 8d) },
                },
                new KeyFrame
                {
                    Cue = new Cue(1d),
                    Setters = { new Setter(OpacityProperty, 1d), new Setter(TranslateTransform.YProperty, 0d) },
                },
            },
        };
        _ = anim.RunAsync(card);
    }

    private void OnBackClick(object? sender, RoutedEventArgs e)
    {
        BackRequested?.Invoke(this, EventArgs.Empty);
    }

    private void OnScrimTapped(object? sender, TappedEventArgs e)
    {
        Vm?.CloseSheet();
    }

    private void OnPreviewKeyDown(object? sender, KeyEventArgs e)
    {
        if (e.Key == Key.Escape && Vm?.IsSheetOpen == true)
        {
            Vm.CloseSheet();
            e.Handled = true;
        }
    }
}

/// <summary>
/// Цена «в месяц» свёрнутой карточки. <c>AsFlag</c> — тот же расчёт, но как «есть ли что показать»:
/// блок цены целиком прячется у тарифа без пригодных сроков, чтобы подпись «в месяц» не осталась
/// висеть без числа.
/// </summary>
public sealed class BuyMonthlyRateConverter : IValueConverter
{
    public bool AsFlag { get; set; }

    /// <summary><c>AsFlag</c> — видимость блока цены; <c>AsPeriod</c> — подпись под ценой (за какой
    /// срок её платят); иначе — сама цена.</summary>
    public bool AsPeriod { get; set; }

    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        var tariff = value as TariffDto;
        var entry = tariff is null ? null : BuyView.EntryOption(tariff);
        if (AsFlag)
        {
            return entry is not null;
        }
        if (entry is null)
        {
            return string.Empty;
        }
        if (!AsPeriod)
        {
            return BuyView.Money(entry.Price, tariff!.Currency);
        }
        //  «в месяц» пишем только когда срок и есть месяц. Иначе подпись называет настоящий срок:
        //  цена за 90 дней с подписью «в месяц» — то же враньё, только другими словами.
        return entry.DurationDays == 30
            ? Common.L.T("Buy_PerMonth")
            : Common.L.F("Common_DaysShort", entry.DurationDays);
    }

    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture) =>
        throw new NotSupportedException();
}

/// <summary>
/// «Выгода N ₽» у строки срока: считается по опции и её ТАРИФУ (первое значение — опция, второе —
/// карточка тарифа, из которой берутся соседние сроки). <c>AsFlag</c> отдаёт видимость подписи.
/// </summary>
public sealed class BuySavingConverter : IMultiValueConverter
{
    public bool AsFlag { get; set; }

    public object Convert(IList<object?> values, Type targetType, object? parameter, CultureInfo culture)
    {
        var option = values.Count > 0 ? values[0] as PriceOptionDto : null;
        var owner = values.Count > 1 ? values[1] as BuyTariffItem : null;
        var saving = option is null || owner is null ? null : BuyView.Saving(owner.Tariff, option);
        if (AsFlag)
        {
            return saving is not null;
        }
        return saving is null
            ? string.Empty
            : L.F("Buy_Saving", BuyView.Money(saving.Value, owner!.Tariff.Currency));
    }
}

/// <summary>
/// Бейдж «Текущий»: id тарифа (первое значение) против набора id, которые аккаунт уже оплатил
/// (второе). Порт isCurrentTariff — факт о ВЛАДЕНИИ, поэтому и приходит он не из каталога.
/// </summary>
public sealed class BuyCurrentTariffConverter : IMultiValueConverter
{
    public object Convert(IList<object?> values, Type targetType, object? parameter, CultureInfo culture)
    {
        var id = (values.Count > 0 ? values[0] as string : null)?.Trim();
        var owned = values.Count > 1 ? values[1] as IReadOnlyCollection<string> : null;
        if (id.IsNullOrEmpty() || owned is null || owned.Count == 0)
        {
            return false;
        }
        return owned.Contains(id!, StringComparer.OrdinalIgnoreCase);
    }
}

/// <summary>Денежная строка модели, перенабранная под язык интерфейса («2600 ₽» → «2 600 ₽»).</summary>
public sealed class BuyMoneyConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture) =>
        BuyView.LocalizeMoney(value as string);

    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture) =>
        throw new NotSupportedException();
}
