using Avalonia.Data.Converters;
using v2rayN.Desktop.Common;

namespace v2rayN.Desktop.Views;

/// <summary>
/// «Маршрутизация» — подэкран настроек по единому лекалу (screens.md «Подэкраны»):
/// «Доменная стратегия» (окошко у значения) → «Наборы правил» (тап делает набор активным) →
/// «Обслуживание» (пересоздать встроенные наборы).
///
/// Заменяет легаси Semi-окно <c>RoutingSettingWindow</c>. Логика НЕ дублируется: DataContext — тот
/// же движковый <see cref="RoutingSettingViewModel"/>, что стоял за окном, поэтому изменения пишутся
/// в тот же реальный конфиг (SetDefaultRouting / InitRouting / DomainStrategy → SaveConfig).
///
/// В прототипе на этом экране лежат три строки правил — proxy / direct / block. В ветке правила
/// живут НАБОРАМИ, и отдельного правила отсюда не открыть: его редактор был отдельным окном, а
/// отдельных окон в приложении больше нет. Дорисовывать три фиксированные строки поверх наборов не
/// стали — это соврало бы про устройство экрана (вопрос владельцу).
///
/// OFF-модель: при уходе со страницы перестраиваем маршруты и меню, и применяем вживую только если
/// ядро уже запущено. Стрелка «назад» поднимает <see cref="BackRequested"/>.
/// </summary>
public partial class RoutingSubView : UserControl, ISubPage
{
    // Технические значения Xray-стратегии → дружелюбные подписи (значение хранится «как есть»).
    // Подпись берётся из локали (L.T) — подэкран строится заново при каждом открытии, поэтому
    // язык, выбранный в настройках, применяется к окошку на следующем входе.
    private static readonly (string Value, string LabelKey)[] StrategyOptions =
    [
        ("AsIs", "Routing_DsAsIs"),
        ("IPIfNonMatch", "Routing_DsIpIfNonMatch"),
        ("IPOnDemand", "Routing_DsIpOnDemand"),
    ];

    private readonly Config _config;
    private readonly RoutingSettingViewModel _vm;
    private bool _saved;
    private bool _resetting;

    public event EventHandler? BackRequested;

    public RoutingSubView()
    {
        InitializeComponent();

        _config = AppManager.Instance.Config;
        _vm = new RoutingSettingViewModel();
        DataContext = _vm;

        RefreshRuleSets();

        // Модель наполняет RoutingItems асинхронно (fire-and-forget Init в её конструкторе), и к
        // моменту первого RefreshRuleSets коллекция ещё пуста — снимок без подписки оставлял экран
        // с «Правил пока нет» НАВСЕГДА. Подписка перечитывает список, когда данные реально пришли.
        _vm.RoutingItems.CollectionChanged += (_, _) => Dispatcher.UIThread.Post(RefreshRuleSets);

        // --- Доменная стратегия: общее окошко у значения ---
        StrategyPopup.Options = StrategyOptions.Select(x => L.T(x.LabelKey)).ToList();
        var curIdx = Array.FindIndex(StrategyOptions, x => x.Value == _vm.DomainStrategy);
        StrategyPopup.SelectedIndex = curIdx < 0 ? 0 : curIdx;
        UpdateStrategyValue();

        StrategyPopup.Picked += (_, idx) =>
        {
            if (idx >= 0 && idx < StrategyOptions.Length)
            {
                // Присваивание Reactive-свойству VM само сохраняет конфиг (его подписка на DomainStrategy).
                _vm.DomainStrategy = StrategyOptions[idx].Value;
            }
            UpdateStrategyValue();
        };
        StrategyPopup.GetObservable(ValuePopup.IsOpenProperty).Subscribe(open =>
        {
            SubPageUtil.SetClass(StrategyCaret, "open", open);
            SubPageUtil.SetClass(txtStrategyValue, "open", open);
        });
        RowStrategy.Tapped += (_, _) => StrategyPopup.Toggle();

        RowResetRules.Tapped += async (_, _) => await ResetRulesAsync();
        btnBack.Click += async (_, _) => await BackAsync();
    }

    private void UpdateStrategyValue()
    {
        var i = StrategyPopup.SelectedIndex;
        txtStrategyValue.Text = i >= 0 && i < StrategyOptions.Length ? L.T(StrategyOptions[i].LabelKey) : string.Empty;
    }

    /// <summary>Перечитывает наборы правил из модели. Разделитель рисуется перед каждой строкой,
    /// кроме первой, поэтому строки заворачиваются в обёртку со своим флагом.</summary>
    private void RefreshRuleSets()
    {
        var rows = _vm.RoutingItems.Select((item, i) => new RuleSetRow(item, i > 0)).ToList();
        listRoutings.ItemsSource = rows;

        // Прячем КАРТОЧКУ, а не список внутри неё: пустая карточка в 2 пикселя высотой читается
        // как поломка вёрстки. Вместо неё — пустое состояние.
        var any = rows.Count > 0;
        RulesCard.IsVisible = any;
        RulesEmpty.IsVisible = !any;
    }

    /// <summary>Тап по строке набора → сделать его активным по умолчанию (движковый SetDefaultRouting).</summary>
    private async void OnRoutingRowTapped(object? sender, TappedEventArgs e)
    {
        //  Обработчик события ⇒ async void: исключение отсюда ловить НЕКОМУ, оно валит процесс.
        //  А внутри — запись в базу профилей (SetDefaultRouting) и перезагрузка ядра, то есть
        //  и файлы, и процессы. Ловим: набор не переключился — это строка списка, не крах.
        try
        {
            if (sender is Border { DataContext: RuleSetRow row } && !row.Item.IsActive)
            {
                _vm.SelectedSource = row.Item;
                await _vm.RoutingAdvancedSetDefault();
                RefreshRuleSets();
            }
        }
        catch (Exception ex)
        {
            Logging.SaveLog("RoutingSubView.RowTapped", ex);
        }
    }

    /// <summary>Пока идёт пересоздание, действие теряет акцент и не откликается: акцентный текст
    /// читается как «нажми», а нажимать в этот момент нечего.</summary>
    private async Task ResetRulesAsync()
    {
        if (_resetting)
        {
            return;
        }
        _resetting = true;
        SetResetBusy(true);
        try
        {
            // «Пересоздать» означает пересоздать, а не добавить: движковая команда импорта
            // (InitRouting с blImportAdvancedRules=true) всегда ДОПИСЫВАЕТ встроенные наборы, и
            // каждый тап удваивал список. Поэтому сначала убираем прежние встроенные («V4-» —
            // версия-префикс из ConfigHandler.InitBuiltinRouting), не трогая наборы пользователя,
            // и лишь затем просим движок создать их заново (он же назначит набор по умолчанию).
            var items = await AppManager.Instance.RoutingItems();
            foreach (var item in items.Where(t => t.Remarks.StartsWith("V4-")))
            {
                await ConfigHandler.RemoveRoutingItem(item);
            }
            await ConfigHandler.InitRouting(_config);
            await _vm.RefreshRoutingItems();
            _vm.IsModified = true;
        }
        finally
        {
            _resetting = false;
            SetResetBusy(false);
            RefreshRuleSets();
        }
    }

    private void SetResetBusy(bool busy)
    {
        txtResetRules.Classes.Set("accent", !busy);
        RowResetRules.Classes.Set("tap", !busy);
        txtResetState.Text = busy ? L.T("Routing_Resetting") : string.Empty;
    }

    private async Task BackAsync()
    {
        if (_saved)
        {
            return;
        }
        _saved = true;

        // Как в прежнем OpenRoutingAsync: перестраиваем встроенные маршруты и меню статуса, применяем
        // вживую только если ядро уже запущено.
        if (_vm.IsModified)
        {
            await ConfigHandler.InitBuiltinRouting(_config);
            await StatusBarViewModel.Instance.RefreshRoutingsMenu();
            if (IsCoreRunning())
            {
                StatusBarViewModel.Instance.ReloadRequested.Publish();
            }
        }

        BackRequested?.Invoke(this, EventArgs.Empty);
    }

    private static bool IsCoreRunning() =>
        AppManager.Instance.IsRunningCore(ECoreType.Xray) || AppManager.Instance.IsRunningCore(ECoreType.sing_box);

    /// <summary>Обёртка строки списка: сам набор плюс флаг разделителя (он рисуется перед каждой
    /// строкой, кроме первой).</summary>
    public sealed class RuleSetRow
    {
        public RuleSetRow(RoutingItemModel item, bool showDivider)
        {
            Item = item;
            ShowDivider = showDivider;
        }

        public RoutingItemModel Item { get; }

        public bool ShowDivider { get; }
    }
}

/// <summary>Число правил с верным склонением («1 правило», «4 правила», «8 правил» / «1 rule») через
/// L.Plural. Нужен, потому что StringFormat в XAML статичен и не переключается при смене языка.</summary>
public sealed class RuleCountConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        var n = value is int i ? i : 0;
        return L.Plural("Routing_RulesPlural", n);
    }

    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture)
        => throw new NotSupportedException();
}

/// <summary>
/// Имя набора правил для показа. Три встроенных набора движок заводит под китайскими именами
/// апстрима (ConfigHandler.InitBuiltinRouting): «V4-绕过大陆(Whitelist)», «V4-黑名单(Blacklist)»,
/// «V4-全局(Global)». Переводим ТОЛЬКО при показе и только эти три точных имени: в базе имя служит
/// ещё и опознавательным знаком («Стандартные правила» находит встроенные наборы по префиксу «V4-»),
/// а набор пользователя, как бы он ни назывался, показывается как есть.
/// </summary>
public sealed class RoutingNameConverter : IValueConverter
{
    private static readonly Dictionary<string, string> Builtins = new(StringComparer.Ordinal)
    {
        ["V4-绕过大陆(Whitelist)"] = "Routing_PresetBasic",
        ["V4-黑名单(Blacklist)"] = "Routing_PresetBlocked",
        ["V4-全局(Global)"] = "Routing_PresetGlobal",
    };

    public static string Display(string? remarks) =>
        remarks is not null && Builtins.TryGetValue(remarks, out var key) ? L.T(key) : remarks ?? string.Empty;

    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
        => Display(value as string);

    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture)
        => throw new NotSupportedException();
}
