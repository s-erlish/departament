using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.CompilerServices;
using Avalonia.Automation;
using Avalonia.Platform.Storage;
using departament.Desktop.Common;

namespace departament.Desktop.Views;

/// <summary>
/// «Прокси по приложениям» (split-tunnel) — подэкран настроек по единому лекалу. Real, working:
/// подбирает процессы Windows (по имени) или явный путь к .exe, сохраняет их И вставляет управляемое
/// правило маршрутизации в АКТИВНЫЙ <c>RoutingItem.RuleSet</c> через матчеры <c>process_name</c> /
/// <c>process_path</c>, которые движок уже умеет (SingboxRoutingService.GenRoutingUserRule). Два режима:
///   • «Кроме выбранных» (bypass)  → перечисленные идут НАПРЯМУЮ, остальное остаётся в туннеле;
///   • «Только выбранные» (include) → через прокси идут только перечисленные, остальное напрямую.
/// OFF-модель: изменение настройки НЕ поднимает ядро; вживую применяется, только если оно уже запущено.
///
/// Две вещи, которым научил тот же экран на Android-стороне продукта:
///   1. Строка «Режим» НЕ переключается по кругу. Значение, которое меняется местами по тапу, не
///      показывает набор целиком и заставляет угадывать следующий шаг. Здесь у строки каретка, и она
///      открывает ОБЩЕЕ «окошко у значения» (<see cref="ValuePicker"/>) — второй реализации выбора
///      в приложении не заводится.
///   2. Подпись строки не обрезается на полуслове. Обрезается ЗНАЧЕНИЕ справа (у него свой лимит),
///      а имя программы и имя файла живут в собственной колонке и переносятся/усекаются по своим
///      правилам.
///
/// Готовые наборы «Игры» и «Игровые лаунчеры» (эталонный кадр, screens.md) — <see cref="AppPresets"/>:
/// именованные, обратимые, выключенные по умолчанию. Один тумблер применяет набор и тем же тумблером
/// снимает ровно то, что применил; состав виден в списке ниже отмеченными строками и правится
/// поштучно. Два набора, а не один: задержка и магазин — разные решения, и решение про цены не должно
/// ехать на тумблере, который чинит пинг.
///
/// Список программ — по страницам (<see cref="PageSize"/>), у каждой строки настоящая иконка программы
/// (<see cref="AppIconLoader"/>, только Windows; без неё — буква). Иконки грузятся для видимой страницы.
///
/// Уход со страницы (стрелка «назад») сохраняет и применяет, затем поднимает <see cref="BackRequested"/>.
/// </summary>
public partial class PerAppProxyPage : UserControl, ISubPage
{
    // Маркер на управляемом RulesItem — по нему находим и заменяем СВОИ правила, не трогая пользовательские.
    private const string PerAppMarkerBypass = "__departament_perapp_bypass";
    private const string PerAppMarkerInclude = "__departament_perapp_include";
    private const string PerAppMarkerCatchAll = "__departament_perapp_catchall";

    // Порядок пунктов окошка = порядок этих индексов. 0 — «Кроме выбранных» (bypass).
    private const int ModeExcept = 0;
    private const int ModeOnly = 1;

    // Восемь строк вместе с переключателем страниц помещаются в окно высотой 600 целиком.
    private const int PageSize = 8;

    // Мест под номера в переключателе: «1 … 4 5 6 … 14».
    private const int PageSlotCount = 7;

    // Пропуск в ряду номеров («…»).
    private const int PageGap = -1;

    private readonly Config _config;
    private readonly ObservableCollection<AppItem> _all = new();
    private bool _saved;

    // То, что прошло поиск, и открытая страница в нём (с нуля).
    private List<AppItem> _shown = new();
    private int _page;
    private readonly Button[] _pageSlots = new Button[PageSlotCount];

    // Высота полной страницы, снятая с живой разметки (см. BuildPager).
    private double _fullPageHeight;

    //  Тумблеры наборов сейчас приводятся в согласие с галочками — их собственные события в этот
    //  момент не команда пользователя, а эхо. См. SyncPresetSwitches.
    private bool _syncingPresets;

    public event EventHandler? BackRequested;

    public PerAppProxyPage()
    {
        InitializeComponent();

        _config = AppManager.Instance.Config;

        btnBack.Click += async (_, _) => await SaveAndBackAsync();
        RowRefresh.Tapped += (_, _) => LoadProcesses();
        RowAddExe.Tapped += async (_, _) => await AddExeAsync();
        BuildPager();
        txtFilter.GetObservable(TextBox.TextProperty).Subscribe(_ => ApplyFilter());

        switchEnabled.IsChecked = _config.UiItem.PerAppProxyEnabled;
        WireRowToggle(RowEnabled, switchEnabled);

        // ── Режим через общий ValuePicker ──
        // Значение, каретка и окошко живут в одном компоненте: он же ведёт поворот каретки и
        // приглушение значения от СОСТОЯНИЯ окошка (оно умеет закрыться само — Esc, клик мимо,
        // уход со страницы), поэтому здесь остаётся только список вариантов и текущий выбор.
        ModePicker.Options = new[] { L.T("PerApp_ModeExcept"), L.T("PerApp_ModeOnly") };
        ModePicker.SelectedIndex = _config.UiItem.PerAppProxyBypass ? ModeExcept : ModeOnly;
        // Тап ВНУТРИ раскрытого окошка — это выбор пункта, а не повторное нажатие на строку.
        // Окошко лежит В ДЕРЕВЕ строки (иначе оно выпадает из in-app зума), поэтому его собственный
        // Click доходит сюда вторым событием: без этой проверки выбор пункта закрывал окошко и тут же
        // открывал его обратно — оно оставалось висеть на экране после выбора.
        RowMode.Tapped += (_, e) =>
        {
            if (!SubPageUtil.OriginatedIn<ValuePopup>(e.Source))
            {
                ModePicker.Toggle();
            }
        };

        // ── Готовые наборы ──
        // Тумблер ЧИТАЕТ ФАКТИЧЕСКИЙ ВЫБОР: набор применён, когда все его программы сейчас отмечены
        // (AppPresets.IsApplied). Поэтому ставится он ПОСЛЕ LoadProcesses — до неё выбора ещё нет.
        txtPresetGames.Text = AppPresets.Games.Title;
        txtPresetGamesHint.Text = AppPresets.Games.Hint;
        txtPresetLaunchers.Text = AppPresets.Launchers.Title;
        txtPresetLaunchersHint.Text = AppPresets.Launchers.Hint;
        switchPresetGames.IsCheckedChanged += (_, _) =>
            TogglePreset(AppPresets.Games, switchPresetGames.IsChecked == true);
        switchPresetLaunchers.IsCheckedChanged += (_, _) =>
            TogglePreset(AppPresets.Launchers, switchPresetLaunchers.IsChecked == true);
        WireRowToggle(RowPresetGames, switchPresetGames);
        WireRowToggle(RowPresetLaunchers, switchPresetLaunchers);

        LoadProcesses();
    }

    /// <summary>Текущий выбор без учёта регистра — то же множество, что уходит в конфиг.</summary>
    private HashSet<string> ChosenSet() => new(
        _all.Where(x => x.IsChecked && x.Identifier.IsNotEmpty()).Select(x => x.Identifier),
        StringComparer.OrdinalIgnoreCase);

    /// <summary>
    /// Приводит тумблеры наборов в согласие с галочками. Зовётся ПОСЛЕ каждого изменения выбора:
    /// загрузка списка, тап по строке, добавленный файл, сам набор.
    ///
    /// Флаг <see cref="_syncingPresets"/> обязателен: присвоение IsChecked поднимает IsCheckedChanged,
    /// а он ведёт в <see cref="TogglePreset"/> — без него отражение состояния снова его меняло бы.
    /// </summary>
    private void SyncPresetSwitches()
    {
        var chosen = ChosenSet();
        _syncingPresets = true;
        try
        {
            switchPresetGames.IsChecked = AppPresets.IsApplied(AppPresets.Games, chosen);
            switchPresetLaunchers.IsChecked = AppPresets.IsApplied(AppPresets.Launchers, chosen);
        }
        finally
        {
            _syncingPresets = false;
        }
    }

    /// <summary>Тап по строке переключает её тумблер — кроме случая, когда тапнули сам тумблер
    /// (он уже переключился, и второе переключение вернуло бы всё назад).</summary>
    private static void WireRowToggle(Border row, ToggleSwitch sw) =>
        row.Tapped += (_, e) =>
        {
            if (!SubPageUtil.OriginatedIn<ToggleSwitch>(e.Source))
            {
                sw.IsChecked = !(sw.IsChecked ?? false);
            }
        };

    /// <summary>
    /// Включение/выключение набора — ТОЛЬКО в памяти страницы. Ни одной записи в конфиг и ни одного
    /// перезапуска ядра здесь нет: решает общий путь выхода (<see cref="SaveAndBackAsync"/>). Включить
    /// набор и тут же выключить — ноль записей.
    ///
    /// Добавляется только то, чего в выборе ещё НЕТ, и запоминается именно добавленное
    /// (<see cref="AppPresets.Apply"/>); выключение возвращает ровно его. Процесс, отмеченный
    /// человеком до применения набора, набору не принадлежит и остаётся отмеченным.
    ///
    /// В конце тумблеры приводятся в согласие с получившимся выбором: их состояние — это и есть
    /// «все программы набора отмечены», а не отдельная память о нажатии.
    /// </summary>
    private void TogglePreset(AppPreset preset, bool on)
    {
        if (_syncingPresets)
        {
            return;
        }
        EnsureRows(preset);
        if (on)
        {
            var added = new HashSet<string>(AppPresets.Apply(preset, ChosenSet()), StringComparer.OrdinalIgnoreCase);
            foreach (var item in _all.Where(x => added.Contains(x.Identifier)))
            {
                item.IsChecked = true;
            }
        }
        else
        {
            var owned = new HashSet<string>(AppPresets.Release(preset), StringComparer.OrdinalIgnoreCase);
            foreach (var item in _all.Where(x => owned.Contains(x.Identifier)))
            {
                item.IsChecked = false;
            }
        }
        // Набор трогает сразу десятки строк, и без пересортировки его действие видно только по
        // счётчику: два десятка игр, которых на этой машине не запущено, легли бы в конец списка.
        // Пересортировка здесь и НИГДЕ БОЛЬШЕ — после ручной галочки строка уехала бы из-под курсора.
        SortByChosen();
        ApplyFilter();
        SyncPresetSwitches();
    }

    /// <summary>Отмеченные — наверх, дальше по алфавиту. Тот же порядок, что задаёт LoadProcesses.</summary>
    private void SortByChosen()
    {
        var ordered = _all
            .OrderByDescending(x => x.IsChecked)
            .ThenBy(x => x.Display, StringComparer.OrdinalIgnoreCase)
            .ToList();
        _all.Clear();
        foreach (var item in ordered)
        {
            _all.Add(item);
        }
    }

    /// <summary>
    /// Гарантирует строку в списке для каждого процесса набора. Игры при настройке НЕ ЗАПУЩЕНЫ — а
    /// список собирается из живых процессов, — поэтому без этого набор «применялся» бы к пустоте и не
    /// показывал бы ничего. Строки добавляются невыбранными; выбор ставит вызывающий. Ровно это и
    /// делает набор набором, а не скрытым списком: его состав виден в списке ниже и снимается поштучно.
    /// </summary>
    private void EnsureRows(AppPreset preset)
    {
        var known = new HashSet<string>(
            _all.Where(x => x.Identifier.IsNotEmpty()).Select(x => x.Identifier),
            StringComparer.OrdinalIgnoreCase);
        foreach (var process in preset.Processes)
        {
            if (known.Add(process))
            {
                _all.Add(new AppItem { Identifier = process, Display = process });
            }
        }
    }

    private void LoadProcesses()
    {
        var selected = new HashSet<string>(
            _config.UiItem.PerAppProxyList ?? new List<string>(),
            StringComparer.OrdinalIgnoreCase);

        var items = new Dictionary<string, AppItem>(StringComparer.OrdinalIgnoreCase);

        // Сначала добавленные вручную / ранее выбранные — так путь переживает то, что программа
        // сейчас не запущена и в списке процессов её нет.
        foreach (var id in selected)
        {
            items[id] = new AppItem
            {
                Identifier = id,
                Display = IsPathLike(id) ? Path.GetFileName(id) : id,
                IsChecked = true,
            };
        }

        try
        {
            foreach (var p in Process.GetProcesses())
            {
                try
                {
                    var name = p.ProcessName;
                    if (name.IsNullOrEmpty())
                    {
                        continue;
                    }
                    if (items.TryGetValue(name, out var known))
                    {
                        // Выбранная раньше и сейчас запущенная: строка уже есть, но без пути — а по
                        // нему берётся иконка.
                        known.Path ??= AppIconLoader.ProcessPath(p);
                        continue;
                    }
                    items[name] = new AppItem
                    {
                        Identifier = name,
                        Display = name,
                        Path = AppIconLoader.ProcessPath(p),
                        IsChecked = selected.Contains(name),
                    };
                }
                catch { }
                finally { p.Dispose(); }
            }
        }
        catch { }

        _all.Clear();
        foreach (var it in items.Values.OrderByDescending(x => x.IsChecked).ThenBy(x => x.Display, StringComparer.OrdinalIgnoreCase))
        {
            _all.Add(it);
        }
        ApplyFilter();
        SyncPresetSwitches();
    }

    /// <summary>Новый поиск или новый состав списка — снова с первой страницы.</summary>
    private void ApplyFilter()
    {
        var q = txtFilter.Text?.Trim();
        _shown = q.IsNullOrEmpty()
            ? _all.ToList()
            : _all.Where(x => (x.Display?.Contains(q!, StringComparison.OrdinalIgnoreCase) ?? false)
                           || (x.Identifier?.Contains(q!, StringComparison.OrdinalIgnoreCase) ?? false))
                  .ToList();
        _page = 0;
        ShowPage();
    }

    private int PageCount => Math.Max(1, (_shown.Count + PageSize - 1) / PageSize);

    private void ShowPage()
    {
        _page = Math.Clamp(_page, 0, PageCount - 1);
        var rows = _shown.Skip(_page * PageSize).Take(PageSize).ToList();

        // Разделитель рисует сама строка, поэтому у ПЕРВОЙ его быть не должно — иначе под шапкой
        // карточки появляется лишняя линия.
        for (var i = 0; i < rows.Count; i++)
        {
            rows[i].ShowDivider = i > 0;
        }

        listApps.ItemsSource = rows;
        // Неполная последняя страница держит высоту полной: иначе карточка становилась короче,
        // переключатель уезжал вверх из-под курсора, а окно ещё и прокручивалось.
        listApps.MinHeight = PageCount > 1 ? _fullPageHeight : 0;
        AppsCard.IsVisible = rows.Count > 0;
        AppsEmpty.IsVisible = rows.Count == 0;
        txtProgramsLabel.Text = $"{L.T("PerApp_Programs")} · {L.F("PerApp_Chosen", _all.Count(x => x.IsChecked))}";
        UpdatePager();
        LoadIcons(rows);
    }

    private void GoToPage(int page)
    {
        if (page == _page || page < 0 || page >= PageCount)
        {
            return;
        }
        _page = page;
        ShowPage();
    }

    /// <summary>Семь мест под номера создаются один раз и дальше только переподписываются: пересоздание
    /// на каждом шаге сбрасывало бы фокус клавиатуры с нажатой кнопки.</summary>
    private void BuildPager()
    {
        // Полная страница бывает всегда, когда страниц больше одной, — первая. Её высоту и держит
        // последняя. Снимается с разметки, а не считается: строка выше своего минимума (плитка 40 и
        // поля), и число в коде разошлось бы с ней при первой правке стиля.
        listApps.SizeChanged += (_, e) =>
        {
            if (listApps.ItemsSource is List<AppItem> { Count: PageSize })
            {
                _fullPageHeight = e.NewSize.Height;
            }
        };
        btnPagePrev.Click += (_, _) => GoToPage(_page - 1);
        btnPageNext.Click += (_, _) => GoToPage(_page + 1);
        for (var i = 0; i < PageSlotCount; i++)
        {
            var slot = new Button { Content = new TextBlock() };
            slot.Classes.Add("SubPage");
            slot.Click += (sender, _) =>
            {
                if ((sender as Button)?.Tag is int page)
                {
                    GoToPage(page);
                }
            };
            _pageSlots[i] = slot;
            PagerSlots.Children.Add(slot);
        }
    }

    private void UpdatePager()
    {
        var pages = PageCount;
        Pager.IsVisible = pages > 1;
        if (pages <= 1)
        {
            return;
        }
        btnPagePrev.IsEnabled = _page > 0;
        btnPageNext.IsEnabled = _page < pages - 1;

        var slots = PageSlots(_page, pages);
        for (var i = 0; i < PageSlotCount; i++)
        {
            var button = _pageSlots[i];
            if (i >= slots.Count)
            {
                button.IsVisible = false;
                continue;
            }
            var page = slots[i];
            var gap = page == PageGap;
            button.IsVisible = true;
            button.Tag = gap ? null : page;
            ((TextBlock)button.Content!).Text = gap ? "…" : (page + 1).ToString(CultureInfo.InvariantCulture);
            // Пропуск — не кнопка: не нажимается и не берёт фокус.
            button.IsHitTestVisible = !gap;
            button.Focusable = !gap;
            button.Classes.Set("current", page == _page);
            AutomationProperties.SetName(button, gap ? string.Empty : L.F("PerApp_PageN", page + 1));
        }
    }

    /// <summary>
    /// Номера страниц в ряду: все, если их не больше семи; иначе первая, последняя, текущая с соседями
    /// и «…» на месте пропуска. Мест всегда семь — у первой и последних страниц соседей добирается
    /// с одной стороны.
    /// </summary>
    private static List<int> PageSlots(int page, int pages)
    {
        if (pages <= PageSlotCount)
        {
            return Enumerable.Range(0, pages).ToList();
        }
        if (page <= 3)
        {
            return [0, 1, 2, 3, 4, PageGap, pages - 1];
        }
        if (page >= pages - 4)
        {
            return [0, PageGap, pages - 5, pages - 4, pages - 3, pages - 2, pages - 1];
        }
        return [0, PageGap, page - 1, page, page + 1, PageGap, pages - 1];
    }

    /// <summary>
    /// Иконки только для видимой страницы и каждой строке один раз. Размер — в пикселях экрана
    /// (24 точки × масштаб), поэтому до появления страницы в окне масштаб неизвестен и загрузка
    /// ждёт <see cref="OnAttachedToVisualTree"/>.
    /// </summary>
    private void LoadIcons(IEnumerable<AppItem> rows)
    {
        if (!AppIconLoader.IsSupported || TopLevel.GetTopLevel(this) is not { } top)
        {
            return;
        }
        var size = (int)Math.Round(24 * top.RenderScaling);
        foreach (var row in rows)
        {
            if (row.IconRequested || row.IconPath.IsNullOrEmpty())
            {
                continue;
            }
            row.IconRequested = true;
            var load = AppIconLoader.LoadAsync(row.IconPath!, size);
            // Уже разобранная иконка ставится сразу, без кадра с буквой: иначе при возврате на
            // страницу плитки мигали бы.
            if (load.IsCompletedSuccessfully)
            {
                row.Icon = load.Result;
            }
            else
            {
                _ = SetIconAsync(row, load);
            }
        }
    }

    private static async Task SetIconAsync(AppItem row, Task<Bitmap?> load)
    {
        // Продолжение возвращается в поток интерфейса: загрузку просили из него.
        row.Icon = await load;
    }

    protected override void OnAttachedToVisualTree(VisualTreeAttachmentEventArgs e)
    {
        base.OnAttachedToVisualTree(e);
        LoadIcons(_shown.Skip(_page * PageSize).Take(PageSize));
    }

    private void OnAppRowTapped(object? sender, TappedEventArgs e)
    {
        if ((sender as Control)?.DataContext is not AppItem item)
        {
            return;
        }
        item.IsChecked = !item.IsChecked;
        //  Снятая руками галочка гасит тумблер набора, поставленная — может его зажечь: он и есть
        //  «все программы набора отмечены».
        SyncPresetSwitches();
        txtProgramsLabel.Text = $"{L.T("PerApp_Programs")} · {L.F("PerApp_Chosen", _all.Count(x => x.IsChecked))}";
    }

    private async Task AddExeAsync()
    {
        var top = TopLevel.GetTopLevel(this);
        if (top is null)
        {
            return;
        }
        var files = await top.StorageProvider.OpenFilePickerAsync(new FilePickerOpenOptions
        {
            AllowMultiple = false,
            FileTypeFilter = Utils.IsWindows()
                ? new[] { new FilePickerFileType(L.T("PerApp_ProgramFileType")) { Patterns = new[] { "*.exe" } } }
                : null,
        });
        var path = files?.FirstOrDefault()?.TryGetLocalPath();
        if (path.IsNullOrEmpty())
        {
            return;
        }
        if (_all.Any(x => string.Equals(x.Identifier, path, StringComparison.OrdinalIgnoreCase)))
        {
            return;
        }
        _all.Insert(0, new AppItem { Identifier = path!, Display = Path.GetFileName(path), IsChecked = true });
        ApplyFilter();
        SyncPresetSwitches();
    }

    private async Task SaveAndBackAsync()
    {
        if (_saved)
        {
            return;
        }
        _saved = true;

        var enabled = switchEnabled.IsChecked == true;
        var bypass = ModePicker.SelectedIndex != ModeOnly;
        var chosen = _all.Where(x => x.IsChecked && x.Identifier.IsNotEmpty())
                         .Select(x => x.Identifier!)
                         .Distinct(StringComparer.OrdinalIgnoreCase)
                         .ToList();

        // Владение наборами публикуется ЗДЕСЬ, вместе со списком, и на обеих дорогах отсюда: это
        // две половины одного факта, и разъехаться им нельзя.
        AppPresets.Commit();

        //  НИЧЕГО НЕ ИЗМЕНИЛОСЬ — НИЧЕГО И НЕ ДЕЛАЕМ. Перезагрузка ядра — это разрыв и повторное
        //  поднятие туннеля: заглянуть в настройку не должно стоить соединения.
        var oldEnabled = _config.UiItem.PerAppProxyEnabled;
        var oldBypass = _config.UiItem.PerAppProxyBypass;
        var oldSet = new HashSet<string>(_config.UiItem.PerAppProxyList ?? new List<string>(), StringComparer.OrdinalIgnoreCase);
        var sameApps = oldSet.SetEquals(chosen);
        if (enabled == oldEnabled && bypass == oldBypass && sameApps)
        {
            BackRequested?.Invoke(this, EventArgs.Empty);
            return;
        }

        _config.UiItem.PerAppProxyEnabled = enabled;
        _config.UiItem.PerAppProxyBypass = bypass;
        _config.UiItem.PerAppProxyList = chosen;
        await ConfigHandler.SaveConfig(_config);

        //  Маршрут меняется, только если правила «по приложениям» действуют до или после: функция
        //  включена И хотя бы одна программа выбрана. Включили тумблер, но ничего не выбрали; сменили
        //  режим или список при выключенной функции — правил нет ни до, ни после, настройка просто
        //  сохраняется, а VPN не трогается. Раньше любое такое изменение перезапускало подключение.
        var wasActive = oldEnabled && oldSet.Count > 0;
        var isActive = enabled && chosen.Count > 0;
        var routingChanged = wasActive != isActive || (isActive && (bypass != oldBypass || !sameApps));
        if (!routingChanged)
        {
            BackRequested?.Invoke(this, EventArgs.Empty);
            return;
        }

        await ApplyToRoutingAsync(isActive, bypass, chosen);

        if (IsCoreRunning())
        {
            StatusBarViewModel.Instance.ReloadRequested.Publish();
        }
        BackRequested?.Invoke(this, EventArgs.Empty);
    }

    /// <summary>Переписывает ТОЛЬКО наши управляемые правила; пользовательские не трогаются.</summary>
    private async Task ApplyToRoutingAsync(bool active, bool bypass, List<string> apps)
    {
        var routing = await ConfigHandler.GetDefaultRouting(_config);
        if (routing is null)
        {
            return;
        }

        var rules = JsonUtils.Deserialize<List<RulesItem>>(routing.RuleSet) ?? new List<RulesItem>();
        rules.RemoveAll(r => r.Remarks is PerAppMarkerBypass or PerAppMarkerInclude or PerAppMarkerCatchAll);

        if (active)
        {
            if (bypass)
            {
                // Перечисленные — НАПРЯМУЮ (мимо туннеля). Остальное живёт по существующим правилам.
                rules.Insert(0, new RulesItem
                {
                    Id = Utils.GetGuid(false),
                    Remarks = PerAppMarkerBypass,
                    OutboundTag = Global.DirectTag,
                    Process = apps,
                    Enabled = true,
                });
            }
            else
            {
                // Через прокси идут только перечисленные; всё остальное — напрямую (catch-all в конце).
                rules.Insert(0, new RulesItem
                {
                    Id = Utils.GetGuid(false),
                    Remarks = PerAppMarkerInclude,
                    OutboundTag = Global.ProxyTag,
                    Process = apps,
                    Enabled = true,
                });
                rules.Add(new RulesItem
                {
                    Id = Utils.GetGuid(false),
                    Remarks = PerAppMarkerCatchAll,
                    OutboundTag = Global.DirectTag,
                    Network = "tcp,udp",
                    Enabled = true,
                });
            }
        }

        routing.RuleSet = JsonUtils.Serialize(rules, false);
        routing.RuleNum = rules.Count;
        await ConfigHandler.SaveRoutingItem(_config, routing);
    }

    private static bool IsPathLike(string s) => s.Contains('/') || s.Contains('\\');

    private static bool IsCoreRunning() =>
        AppManager.Instance.IsRunningCore(ECoreType.Xray) || AppManager.Instance.IsRunningCore(ECoreType.sing_box);

    /// <summary>Строка списка программ. Уведомляет об изменениях, потому что галочку и разделитель
    /// строки ведёт разметка через <c>Classes.on</c> / <c>IsVisible</c>, а не код-behind по имени.</summary>
    public sealed class AppItem : INotifyPropertyChanged
    {
        private bool _isChecked;
        private bool _showDivider;

        private Bitmap? _icon;

        public string Identifier { get; set; } = string.Empty;
        public string? Display { get; set; }
        public string? Path { get; set; }

        /// <summary>Файл, из которого берётся иконка: .exe, добавленный вручную, или путь запущенного
        /// процесса. У невыбранной и незапущенной программы (строки наборов) его нет — там буква.</summary>
        public string? IconPath => IsPathLike(Identifier) ? Identifier : Path;

        /// <summary>Загрузка уже просилась — чтобы листание туда-обратно не просило снова.</summary>
        public bool IconRequested { get; set; }

        /// <summary>Настоящая иконка программы. Пока её нет, в плитке буква.</summary>
        public Bitmap? Icon
        {
            get => _icon;
            set
            {
                if (ReferenceEquals(_icon, value))
                {
                    return;
                }
                _icon = value;
                PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(Icon)));
                PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(HasIcon)));
            }
        }

        public bool HasIcon => _icon is not null;

        /// <summary>
        /// Вторая строка — ТОЛЬКО когда ей есть что добавить. У запущенной программы имя процесса и
        /// есть идентификатор, и строка повторяла заголовок слово в слово: «bash» над «bash»,
        /// «python3» над «python3» — на Linux так выглядел почти весь список. Смысл у неё появляется
        /// у программы, добавленной файлом: там заголовок — имя файла, а идентификатор — полный путь.
        /// </summary>
        public string? Detail =>
            string.Equals(Identifier, Display, StringComparison.Ordinal) ? null : Identifier;

        /// <summary>Есть ли вторая строка. Пустая занимала бы высоту строки текста.</summary>
        public bool HasDetail => Detail.IsNotEmpty();

        /// <summary>Инициал для плитки. Пустое имя даёт «?», а не пустой квадрат.</summary>
        public string Letter =>
            Display.IsNullOrEmpty() ? "?" : Display!.Trim()[..1].ToUpperInvariant();

        public bool IsChecked
        {
            get => _isChecked;
            set => Set(ref _isChecked, value);
        }

        public bool ShowDivider
        {
            get => _showDivider;
            set => Set(ref _showDivider, value);
        }

        public event PropertyChangedEventHandler? PropertyChanged;

        private void Set(ref bool field, bool value, [CallerMemberName] string? name = null)
        {
            if (field == value)
            {
                return;
            }
            field = value;
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
        }
    }
}
