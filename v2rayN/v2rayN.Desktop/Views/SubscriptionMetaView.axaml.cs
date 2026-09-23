using System.ComponentModel;
using System.Runtime.InteropServices;
using Avalonia.Animation;
using Avalonia.Animation.Easings;
using Avalonia.Controls.Documents;
using Avalonia.Layout;
using ServiceLib.Helper;
using v2rayN.Desktop.Account;
using v2rayN.Desktop.Common;
using v2rayN.Desktop.ViewModels;

namespace v2rayN.Desktop.Views;

/// <summary>
/// Мета-бар подписки = ЗАГОЛОВОК её группы серверов (под-план 2 / Ф-D4). Владелец потребовал, чтобы
/// карточка подписки сверху бесшовно продолжалась в свои серверы одной секцией — поэтому этот вид
/// живёт КАК header каждой группы внутри <see cref="ServerListView"/> (никакого отдельного
/// «Сервера»-заголовка). Показывает: заголовок/подзаголовок, действия (пинг/обновить/пин/удалить),
/// трафик-пилюлю + expiry, announce, поддержку + Telegram. Шеврон сворачивает серверы ЭТОЙ подписки.
///
/// Data-driven: <see cref="Control.DataContext"/> = <see cref="HomeServerGroup"/> (наследуется из
/// шаблона группы). Реальную <see cref="SubItem"/> резолвим по <c>Subid</c> первого сервера группы
/// (<c>AppManager.GetSubItem</c>) — userinfo (трафик/срок/announce/support/title) заполняет движок из
/// заголовка <c>subscription-userinfo</c>. Ничего не выдумываем: без реальной подписки тело скрыто,
/// виден только заголовок группы (имя + сворачивание). Общий движок (пинг/обновление/добавление)
/// достаём из <see cref="MainWindowViewModel"/> хост-окна. Образцы — ТОЛЬКО в <see cref="Design.IsDesignMode"/>.
/// </summary>
public partial class SubscriptionMetaView : UserControl
{
    //  Кэш-кисти повторяют токены Incy (тема одна, тёмная) — как в ConnectHeroView/MainWindow.
    private static readonly IBrush _accent = new SolidColorBrush(Color.Parse("#4C8DFF"));       // Brush.Accent
    private static readonly IBrush _muted = new SolidColorBrush(Color.Parse("#9BA1AD"));        // Brush.OnSurfaceVariant
    private static readonly IBrush _red = new SolidColorBrush(Color.Parse("#F04452"));          // Brush.Red (destructive)

    //  ДВА порога, и они разные — раньше обе вещи сидели на одном 400, и в компакте (карточка
    //  ~312) пилюля трафика folded в две строки, хотя эталонный кадр компакта показывает одну.
    //  Одна строка пилюли реально не влезает только к ~280; интервал «· 1 ч» в подписи — уже к 400
    //  (замеры по эталонам: компакт 312 — одна строка и БЕЗ интервала, узкий 404 — С интервалом).
    private const double TrafficOneRowMinWidth = 280d;
    private const double SubtitleIntervalMinWidth = 400d;
    private bool _trafficOneRow = true;
    private bool _subtitleWithInterval = true;

    //  Живая ширина вида → раскладка трафик-ряда (одна строка ↔ две). Живёт, пока во дереве.
    private IDisposable? _boundsSub;

    //  Живая ширина трека линии трафика → пиксели заливки из доли.
    private IDisposable? _trackBoundsSub;

    //  Подписка на обновление сведений подписки движком (AppEvents.SubscriptionMetaChanged).
    private IDisposable? _metaSub;

    private HomeServerGroup? _group;
    private string _supportUrl = string.Empty;
    private string _webPageUrl = string.Empty;
    private string _currentSubId = string.Empty;
    private bool _refreshing;
    private bool _pinging;

    //  Last subscription projected onto the meta-bar. Kept so a live language switch can re-render the
    //  imperative fields (expiry / subtitle / traffic units) without re-fetching from the engine.
    private SubItem? _boundSub;

    //  «Облегчённый режим» (reduced-motion): LiteMode ∨ система просит меньше анимаций (Win32 SPI).
    //  Пока true — переходы шеврона/пина СНЯТЫ (угол и цвет прыгают мгновенно). Единственный источник —
    //  MotionState (рантайм-переключение без рестарта), синхронизируемся на входе в визуальное дерево.
    private bool _reducedMotion;

    //  Шеврон сворачивания: явный RotateTransform, угол меняем плавно через собственный переход
    //  (0° раскрыто / −90° свёрнуто). Центр вращения задаётся РОВНО ОДИН раз — через
    //  RenderTransformOrigin="50%,50%" на самом CollapseIcon (ОТНОСИТЕЛЬНЫЙ центр = 10,10 у 20px-глифа).
    //  ВАЖНО «50%,50%», а НЕ «0.5,0.5»: последнее = 0.5 ПИКСЕЛЯ ≈ угол → шеврон облетал бы орбитой.
    //  CenterX/CenterY НАМЕРЕННО оставлены нулевыми, чтобы центр НЕ удвоился (origin 10,10 + center 0 =
    //  10,10 = центр глифа): удвоение унесло бы центр в (20,20) — дальний угол бокса.
    private readonly RotateTransform _chevronRotate = new() { Angle = 0 };

    public SubscriptionMetaView()
    {
        InitializeComponent();

        CollapseIcon.RenderTransform = _chevronRotate;
        //  Наводим переходы (шеврон-угол + пин-цвет) под ТЕКУЩИЙ режим движения. В lite они сняты —
        //  инстанс-переходы шеврона селекторные .lite-рычаги погасить не могут, поэтому гнём их сами.
        ApplyMotionMode();

        if (Design.IsDesignMode)
        {
            ApplyDesignSample();
            return;
        }

        PingButton.Click += OnPingClick;
        RefreshButton.Click += OnRefreshClick;
        SupportButton.Click += OnSupportClick;
        TelegramButton.Click += OnTelegramClick;
        PinButton.Click += OnPinClick;
        DeleteButton.Click += OnDeleteSubClick;

        DataContextChanged += (_, _) => Rebind();
        //  Реактивный lite: подписка на MotionState живёт, пока вид в визуальном дереве (как ConnectHeroView).
        AttachedToVisualTree += OnMetaAttached;
        DetachedFromVisualTree += OnMetaDetached;

        Rebind();
    }

    // ── Реактивный «Облегчённый режим» (runtime, без рестарта) ───────────────────────────
    //  Зеркалит ConnectHeroView: подписываемся на MotionState.Changed, пока во визуальном дереве, и
    //  синхронизируем переходы шеврона/пина на входе (lite могли переключить, пока вид был откреплён).
    private void OnMetaAttached(object? sender, VisualTreeAttachmentEventArgs e)
    {
        MotionState.Changed += OnMotionStateChanged;
        //  Свежие сведения подписки (имя провайдера, трафик, срок) — откуда бы их ни привезли:
        //  добавление ссылки, разовая дозагрузка при запуске, расписание. Смены DataContext при
        //  этом не происходит, поэтому шапка узнаёт об обновлении только отсюда.
        _metaSub = AppEvents.SubscriptionMetaChanged
            .AsObservable()
            .ObserveOn(RxSchedulers.MainThreadScheduler)
            .Subscribe(OnSubscriptionMetaChanged);
        //  Live language switch re-renders the imperative fields (expiry / subtitle / traffic) in place.
        L.Instance.LanguageChanged += OnLanguageChanged;
        //  Reactive theme: rebuild the traffic-fill gradient from the live accent when the theme
        //  variant flips (dark ↔ light), so «бело→синий» becomes the correct per-theme перелив.
        ActualThemeVariantChanged += OnThemeVariantChanged;
        //  Reactive width: the traffic row switches one-line ↔ stacked so the pill and expiry
        //  never collide in compact (~372) yet stay on one line when there is room.
        _boundsSub = this.GetObservable(BoundsProperty).Subscribe(b => ApplyTrafficLayout(b.Width));
        //  Ширина самого трека меняется независимо от ширины вида (паддинги карточки, скролл-бар),
        //  поэтому долю пересчитываем и по его собственным границам.
        _trackBoundsSub = TrafficTrack.GetObservable(BoundsProperty).Subscribe(_ => ApplyTrafficFill());
        ApplyMotionMode();
    }

    private void OnMetaDetached(object? sender, VisualTreeAttachmentEventArgs e)
    {
        MotionState.Changed -= OnMotionStateChanged;
        L.Instance.LanguageChanged -= OnLanguageChanged;
        ActualThemeVariantChanged -= OnThemeVariantChanged;
        _boundsSub?.Dispose();
        _boundsSub = null;
        _trackBoundsSub?.Dispose();
        _trackBoundsSub = null;
        _metaSub?.Dispose();
        _metaSub = null;
        Unhook();
    }

    /// <summary>
    /// Движок перезаписал сведения ЭТОЙ подписки — перечитываем запись и перерисовываем шапку на
    /// месте. Чужие подписки пропускаем; до первого связывания (_currentSubId пуст) реагировать
    /// не на что — тогда шапку заполнит обычный Rebind.
    /// </summary>
    private async void OnSubscriptionMetaChanged(string? subId)
    {
        if (_currentSubId.IsNullOrEmpty() || subId.IsNullOrEmpty() || subId != _currentSubId)
        {
            return;
        }

        try
        {
            var fresh = await AppManager.Instance.GetSubItem(_currentSubId);
            if (fresh is not null && fresh.Id == _currentSubId)
            {
                BindSub(fresh);
            }
        }
        catch (Exception ex)
        {
            Logging.SaveLog("SubscriptionMetaView.MetaChanged", ex);
        }
    }

    //  Тема сменилась → пересобрать градиент заливки из НОВОГО Brush.Accent (тёмная/светлая/mono).
    private void OnThemeVariantChanged(object? sender, EventArgs e)
    {
        if (_boundSub is not null)
        {
            TrafficFill.Background = BuildTrafficBrush();
        }
    }

    //  Раскладка трафик-ряда по фактической ширине: широкий → срок справа на строке значения;
    //  компакт → срок на своей строке под линией. Место зарезервировано сеткой (Auto,Auto),
    //  поэтому переключение не двигает соседей рывком.
    //
    //  Здесь же пересчитывается ширина заливки: линия тянется во всю ширину карточки, значит
    //  доля должна пересчитываться при КАЖДОМ изменении ширины, а не один раз при привязке.
    private void ApplyTrafficLayout(double width)
    {
        if (width <= 0)
        {
            return;
        }

        ApplyTrafficFill();

        var oneRow = width >= TrafficOneRowMinWidth;
        var withInterval = width >= SubtitleIntervalMinWidth;
        if (oneRow == _trafficOneRow && withInterval == _subtitleWithInterval && ExpiryText.IsInitialized)
        {
            return;
        }
        _trafficOneRow = oneRow;
        _subtitleWithInterval = withInterval;

        //  Интервал в подписи живёт на СВОЁМ пороге (400), не на пороге пилюли: компактная карточка
        //  держит пилюлю в одну строку, но интервал в подпись уже не помещается.
        ApplySubtitle(null);

        if (oneRow)
        {
            Grid.SetRow(ExpiryText, 0);
            Grid.SetColumn(ExpiryText, 2);
            ExpiryText.HorizontalAlignment = HorizontalAlignment.Right;
            ExpiryText.TextAlignment = TextAlignment.Right;
            ExpiryText.Margin = new Thickness(8, 0, 0, 0);
        }
        else
        {
            //  Компакт: срок уезжает под линию (в TrafficRow, ряд 2 — второй ряд сетки).
            Grid.SetRow(ExpiryText, 1);
            Grid.SetColumn(ExpiryText, 0);
            ExpiryText.HorizontalAlignment = HorizontalAlignment.Right;
            ExpiryText.TextAlignment = TextAlignment.Right;
            ExpiryText.Margin = new Thickness(0, 6, 0, 0);
        }
    }

    //  Заливка линии трафика = доля × живая ширина трека. Доля хранится отдельно (а не как
    //  готовая ширина), потому что трек резиновый: та же подписка на 440 и на 340 обязана дать
    //  одинаковую ДОЛЮ, а не одинаковые пиксели.
    private double _trafficFraction;

    private void ApplyTrafficFill()
    {
        var track = TrafficTrack.Bounds.Width;
        if (track <= 0)
        {
            return;
        }

        TrafficFill.Width = Math.Clamp(_trafficFraction, 0d, 1d) * track;
    }

    //  Re-project the current subscription so the localized imperative fields follow the new language.
    private void OnLanguageChanged(object? sender, EventArgs e)
    {
        if (_boundSub is not null)
        {
            BindSub(_boundSub);
        }
    }

    private void OnMotionStateChanged(object? sender, bool lite) => ApplyMotionMode();

    //  Ставим/снимаем ИНСТАНС-переходы. В lite: Transitions = null → угол шеврона и цвет пина меняются
    //  МГНОВЕННО (snap), уважая reduced-motion-контракт. Вне lite: 220мс Ease.Standard шеврону,
    //  ~200мс Ease.Standard пину (как тон nav). Угол/кривая/длительности прежние; центр — origin 50%,50%.
    private void ApplyMotionMode()
    {
        _reducedMotion = MotionState.IsLite || !SystemAnimationsEnabled();

        _chevronRotate.Transitions = _reducedMotion ? null : new Transitions
        {
            new DoubleTransition
            {
                Property = RotateTransform.AngleProperty,
                Duration = TimeSpan.FromMilliseconds(220),
                Easing = new SplineEasing(0.2, 0, 0, 1), // Ease.Standard
            },
        };

        //  Пин-глиф (OnSurfaceVariant ↔ Accent) плавно перетекает как прочие статус-поверхности; в lite — мгновенно.
        PinIcon.Transitions = _reducedMotion ? null : new Transitions
        {
            new BrushTransition
            {
                Property = TextElement.ForegroundProperty,
                Duration = TimeSpan.FromMilliseconds(200),
                Easing = new SplineEasing(0.2, 0, 0, 1), // Ease.Standard
            },
        };
    }

    // ── Reduced-motion: Win32 SPI_GETCLIENTAREAANIMATION («Показывать анимации в Windows») ──
    private const uint SPI_GETCLIENTAREAANIMATION = 0x1042;

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SystemParametersInfo(uint uiAction, uint uiParam, ref bool pvParam, uint fWinIni);

    private static bool SystemAnimationsEnabled()
    {
        //  Нет прямого сигнала вне Windows → считаем движение включённым.
        if (!OperatingSystem.IsWindows())
        {
            return true;
        }

        try
        {
            var enabled = true;
            return !SystemParametersInfo(SPI_GETCLIENTAREAANIMATION, 0, ref enabled, 0) || enabled;
        }
        catch
        {
            return true;
        }
    }

    // ── Bind the owning group ────────────────────────────────────────────────

    private void Unhook()
    {
        if (_group is not null)
        {
            _group.PropertyChanged -= OnGroupPropertyChanged;
        }
    }

    private void Rebind()
    {
        if (Design.IsDesignMode)
        {
            return;
        }

        Unhook();
        _group = DataContext as HomeServerGroup;

        if (_group is null)
        {
            MetaCard.IsVisible = false;
            return;
        }

        _group.PropertyChanged += OnGroupPropertyChanged;
        MetaCard.IsVisible = true;
        SyncCollapsed();

        // Header baseline from the group itself (always available, even for a manual no-sub group).
        _currentSubId = string.Empty;
        _boundSub = null;
        TitleText.Text = _group.Name;
        SubtitleText.Text = string.Empty;
        SubtitleText.IsVisible = false;
        MetaBody.IsVisible = false;
        RefreshButton.IsVisible = false;
        PinButton.IsVisible = false;
        DeleteButton.IsVisible = false;

        //  ПЕРВЫЙ КАДР КАРТОЧКИ — СРАЗУ ПОЛНЫЙ. Запись подписки раньше приезжала только из базы, а
        //  ответ базы возвращается на UI-поток уже после первой раскладки списка: «Главная» вставала
        //  с одной строкой заголовка, а через кадр карточка вырастала на трафик, срок и кнопки и
        //  сталкивала все серверы вниз почти на 50 px. Так было на каждом холодном старте и при
        //  каждой смене раскладки. Подписка уже есть в памяти — берём её синхронно, а чтение базы
        //  ниже только освежает цифры: раскладка при этом не меняется.
        var subid = SubIdOfGroup();
        if (subid.IsNotEmpty() && KnownSub(subid!) is { } known)
        {
            BindSub(known);
        }

        // Resolve the real subscription (with userinfo) behind this group, if any.
        _ = ResolveAndBindSub();
    }

    private string? SubIdOfGroup() =>
        _group?.Servers.FirstOrDefault(s => s is not null && s.Subid.IsNotEmpty())?.Subid;

    //  Последняя запись, которую карточка показывала для каждой подписки. Свежее снимка движка:
    //  сюда попадает и то, что привезло обновление подписки (OnSubscriptionMetaChanged).
    private static readonly Dictionary<string, SubItem> _lastBound = new(StringComparer.Ordinal);

    /// <summary>
    /// Запись подписки без обращения к базе: последняя показанная, иначе список подписок, который
    /// движок держит в памяти с самого запуска (ProfilesViewModel.SubItems). Null — не знаем, и
    /// тогда карточку заполнит чтение базы.
    /// </summary>
    private static SubItem? KnownSub(string subid)
    {
        if (_lastBound.TryGetValue(subid, out var last))
        {
            return last;
        }
        return (Application.Current?.ApplicationLifetime as IClassicDesktopStyleApplicationLifetime)?.MainWindow?.DataContext is MainWindowViewModel main
            ? main.ProfilesViewModel?.SubItems.FirstOrDefault(s => s.Id == subid)
            : null;
    }

    private void OnGroupPropertyChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName == nameof(HomeServerGroup.IsExpanded))
        {
            SyncCollapsed();
        }
    }

    // Reflect the group's collapsed state on the chevron (−90° collapsed). Rotates in place about
    // its own centre (CollapseIcon.RenderTransformOrigin=50%,50%); the transition animates the angle.
    private void SyncCollapsed()
    {
        var collapsed = _group is { IsExpanded: false };
        _chevronRotate.Angle = collapsed ? -90 : 0;
    }

    // The group's Subid → real SubItem. Data-driven: null when the group has no real subscription.
    private async Task ResolveAndBindSub()
    {
        var subid = SubIdOfGroup();
        if (subid.IsNullOrEmpty())
        {
            return;
        }

        SubItem? sub = null;
        try
        {
            sub = await AppManager.Instance.GetSubItem(subid);
        }
        catch (Exception ex)
        {
            Logging.SaveLog("SubscriptionMetaView.Resolve", ex);
        }

        // The group may have been recycled onto another subscription while we awaited.
        if (_group is null || subid != SubIdOfGroup())
        {
            return;
        }
        if (sub is not null)
        {
            BindSub(sub);
        }
    }

    /// <summary>Project a real subscription (with userinfo) onto the meta-bar fields.</summary>
    private void BindSub(SubItem? sub)
    {
        if (sub is null || sub.Id.IsNullOrEmpty())
        {
            return;
        }
        _currentSubId = sub.Id;
        _boundSub = sub;
        _lastBound[sub.Id] = sub;

        // Title: profile-title -> remarks -> group name (never fabricated). Заглушка («import sub»
        // и родня) на экран не проходит: она не называет подписку, а выглядит как её название —
        // именно это и висело в шапке карточки до первого ручного обновления.
        var title = SubscriptionSyncManager.DisplayName(sub.ProfileTitle)
            ?? SubscriptionSyncManager.DisplayName(sub.Remarks)
            ?? string.Empty;
        TitleText.Text = title.IsNotEmpty() ? title : (_group?.Name ?? string.Empty);

        // Subtitle: last-update time + auto-update interval (интервал уходит на узкой карточке).
        ApplySubtitle(sub);

        // Body + subscription-only actions become available.
        MetaBody.IsVisible = true;
        RefreshButton.IsVisible = true;
        PinButton.IsVisible = true;
        // Delete targets THIS real subscription (SubItem + its servers) — hidden for a manual no-sub group.
        DeleteButton.IsVisible = true;

        // Traffic pill: used = upload + download; total <= 0 => unlimited ("∞").
        var used = sub.UploadUsed + sub.DownloadUsed;
        var total = sub.TotalTraffic;
        var unlimited = total <= 0;
        TrafficText.Text = unlimited
            ? $"{ByteSize.Bytes(used)} / ∞"
            : $"{ByteSize.Bytes(used)} / {ByteSize.Bytes(total)}";
        // Доля заливки; безлимит (∞) ⇒ пустой трек, как в референсе. Пиксели считает
        // ApplyTrafficFill по живой ширине трека — линия резиновая.
        _trafficFraction = unlimited ? 0d : Math.Clamp((double)used / total, 0d, 1d);
        ApplyTrafficFill();
        // Полированный градиент светлое→акцент (тема-зависимый, mono-безопасный) из живого акцента.
        TrafficFill.Background = BuildTrafficBrush();

        // Expiry: "∞" / "до dd.MM.yyyy" / "Просрочено" (red) — localized.
        ApplyExpiry(sub.Expire);

        // Announce banner (collapse when the provider sent none).
        var announce = sub.Announce ?? string.Empty;
        AnnounceText.Text = announce;
        AnnounceText.IsVisible = announce.IsNotEmpty();

        // Pin state tint (Accent when pinned, OnSurfaceVariant otherwise).
        PinIcon.Foreground = sub.Pinned ? _accent : _muted;

        // Support / Telegram: shown only when the provider sent the matching URL.
        _supportUrl = sub.SupportUrl ?? string.Empty;
        _webPageUrl = sub.WebPageUrl ?? string.Empty;
        SupportButton.IsVisible = _supportUrl.IsNotEmpty();
        TelegramButton.IsVisible = _webPageUrl.IsNotEmpty();
        ActionRow.IsVisible = _supportUrl.IsNotEmpty() || _webPageUrl.IsNotEmpty();
    }

    private void ApplyExpiry(long expire)
    {
        if (expire <= 0)
        {
            ExpiryText.Text = "∞";
            ExpiryText.Foreground = _muted;
            return;
        }

        // `expire` is epoch SECONDS (Remnawave/Happ), matching the header semantics.
        if (expire < DateTimeOffset.Now.ToUnixTimeSeconds())
        {
            ExpiryText.Text = L.T("Sub_Expired");
            ExpiryText.Foreground = _red;
            return;
        }

        var date = DateTimeOffset.FromUnixTimeSeconds(expire).LocalDateTime;
        ExpiryText.Text = L.F("Sub_Until", date);
        ExpiryText.Foreground = _muted;
    }

    // ── Трафик-заливка: полированный градиент светлое→акцент, тема-зависимый ──────────────
    //  Владелец: заливка пилюли должна переливаться «бело→синий». Делаем это на-бренд и
    //  безопасно во всех 3 темах: конечная точка = ЖИВОЙ Brush.Accent (mono подменяет его на
    //  серо-белый ⇒ синева уходит сама), стартовая = тот же акцент, осветлённый к белому.
    //  На светлой теме трек бледный, поэтому near-white старт «растворился бы» — уводим старт
    //  ближе к акценту (толькочастичное осветление); на тёмной уводим почти в белый. Трек
    //  (незаполненное) остаётся тихой поверхностью SurfaceVariant из стиля Border.TrafficPill.
    private IBrush BuildTrafficBrush()
    {
        var accent = (ResolveBrush("Brush.Accent", _accent) as ISolidColorBrush)?.Color ?? Color.Parse("#4C8DFF");
        var toWhite = ActualThemeVariant == ThemeVariant.Light ? 0.45 : 0.82;
        var start = Blend(accent, Colors.White, toWhite);
        return new LinearGradientBrush
        {
            StartPoint = new RelativePoint(0d, 0.5d, RelativeUnit.Relative),
            EndPoint = new RelativePoint(1d, 0.5d, RelativeUnit.Relative),
            GradientStops =
            {
                new GradientStop(start, 0d),
                new GradientStop(accent, 1d),
            },
        };
    }

    //  Линейное смешение цветов по каналам (t: 0 = a, 1 = b). Альфа держим непрозрачной.
    private static Color Blend(Color a, Color b, double t)
    {
        byte Mix(byte x, byte y) => (byte)Math.Round(x + (y - x) * t);
        return Color.FromArgb(0xFF, Mix(a.R, b.R), Mix(a.G, b.G), Mix(a.B, b.B));
    }

    //  Кисть текущей темы (подхватывает светлую И mono-оверлей), с тихим Incy-фолбэком —
    //  зеркалит ConnectHeroView.ResolveBrush, чтобы заливка не падала из-за отсутствия ключа.
    private IBrush ResolveBrush(string key, IBrush fallback)
    {
        try
        {
            return this.TryFindResource(key, ActualThemeVariant, out var v) && v is IBrush b ? b : fallback;
        }
        catch
        {
            return fallback;
        }
    }

    /// <summary>
    /// Подпись карточки: «16.08.2026 20:25 · 1 ч» (screens.md «Главная»). Интервал автообновления
    /// идёт ГОЛЫМ, без слова «Автообновление —»: подпись живёт в одну строку рядом с четырьмя
    /// иконками действий, и на живом окне 1366 длинная форма («18.08.2026 01:01 · Автообновление —
    /// 1 м…») не помещалась и обрезалась многоточием. Точка после даты уже говорит, что дальше —
    /// её период, а полное объяснение живёт в подэкране автообновления.
    /// <paramref name="withInterval"/> = false — компактная карточка (screens.md: «в компактном
    /// режиме без “· 1 ч”»): там та же строка не влезает даже короткой.
    /// </summary>
    private static string FormatSubtitle(SubItem sub, bool withInterval)
    {
        var parts = new List<string>();
        if (sub.UpdateTime > 0)
        {
            var dt = DateTimeOffset.FromUnixTimeSeconds(sub.UpdateTime).LocalDateTime;
            parts.Add(dt.ToString("dd.MM.yyyy HH:mm"));
        }
        if (withInterval && sub.AutoUpdateInterval > 0)
        {
            parts.Add(FormatInterval(sub.AutoUpdateInterval));
        }
        return string.Join(" · ", parts);
    }

    /// <summary>Пере-собирает подпись под текущую ширину карточки. Дёргается и при привязке
    /// подписки, и при смене ширины — в обоих случаях источник один.</summary>
    private void ApplySubtitle(SubItem? sub)
    {
        sub ??= _boundSub;
        if (sub is null)
        {
            return;
        }

        var subtitle = FormatSubtitle(sub, withInterval: _subtitleWithInterval);
        SubtitleText.Text = subtitle;
        SubtitleText.IsVisible = subtitle.IsNotEmpty();
    }

    private static string FormatInterval(int minutes)
    {
        return minutes % 60 == 0 ? L.F("Common_HoursShort", minutes / 60) : L.F("Common_MinutesShort", minutes);
    }

    // ── Engine access (shared VM reached through the host window) ─────────────

    private MainWindowViewModel? MainVm => TopLevel.GetTopLevel(this)?.DataContext as MainWindowViewModel;

    private ProfilesViewModel? Profiles => MainVm?.ProfilesViewModel;

    // ── Actions ─────────────────────────────────────────────────────────────

    // Collapse chevron: fold / unfold THIS subscription's server rows (group.IsExpanded).
    private void OnCollapseClick(object? sender, RoutedEventArgs e)
    {
        if (_group is not null)
        {
            _group.IsExpanded = !_group.IsExpanded;
        }
    }

    //  Пинг (motion.md «Пинг и обновление подписки»): иконка сменяется вращающимся кругом В ТОМ ЖЕ
    //  слоте, в строках серверов вместо значений — такие же круги (их ставит сам движок, выписывая
    //  в DelayVal нечисловой плейсхолдер, см. DelayTestingConverter), а по завершении всплывает
    //  тост «Задержка обновлена».
    //
    //  Замер идёт по ВСЕМ показанным серверам одной командой движка, поэтому «завершение» ловим по
    //  завершению самой команды — никаких таймеров на 1400 мс: у живого замера время своё.
    private void OnPingClick(object? sender, RoutedEventArgs e)
    {
        if (_pinging)
        {
            return;
        }

        var profiles = Profiles;
        if (profiles is null)
        {
            return;
        }

        _pinging = true;
        PingIcon.IsVisible = false;
        PingSpinner.IsVisible = true;
        PingSpinner.Classes.Add("spinning");

        void Done(bool ok)
        {
            PingSpinner.Classes.Remove("spinning");
            PingSpinner.IsVisible = false;
            PingIcon.IsVisible = true;
            _pinging = false;
            if (ok)
            {
                HomeToast.Show(L.T("Sub_ToastPinged"));
            }
        }

        profiles.FastRealPingCmd.Execute().Subscribe(
            _ => { },
            _ => Dispatcher.UIThread.Post(() => Done(false)),
            () => Dispatcher.UIThread.Post(() => Done(true)));
    }

    private async void OnRefreshClick(object? sender, RoutedEventArgs e)
    {
        if (_refreshing)
        {
            return;
        }

        var subId = _currentSubId;
        if (subId.IsNullOrEmpty())
        {
            return;
        }

        _refreshing = true;
        // In-place busy indicator: swap the refresh glyph for a spinner in the SAME 40px slot so the
        // action row never grows / shifts (no progress-bar animation). Nothing around it moves.
        RefreshIcon.IsVisible = false;
        RefreshSpinner.IsVisible = true;
        RefreshSpinner.Classes.Add("spinning");
        try
        {
            // Refresh THIS subscription only, via the real MainWindowViewModel. Re-downloads the sub;
            // the engine now also persists its userinfo/directives.
            var mainVm = MainVm;
            if (mainVm is not null)
            {
                await mainVm.UpdateSubscriptionProcess(subId, false);
            }
        }
        catch (Exception ex)
        {
            Logging.SaveLog("SubscriptionMetaView.Refresh", ex);
        }
        finally
        {
            RefreshSpinner.Classes.Remove("spinning");
            RefreshSpinner.IsVisible = false;
            RefreshIcon.IsVisible = true;
            _refreshing = false;
        }

        // Re-read the freshly persisted row so the pill/expiry/announce update immediately.
        try
        {
            var fresh = await AppManager.Instance.GetSubItem(subId);
            if (fresh is not null && fresh.Id == _currentSubId)
            {
                BindSub(fresh);

                //  Подтверждение обновления с реальным числом серверов подписки (motion.md:
                //  «Подписка обновлена · N серверов»). Число берём из живой группы, не из выдумки.
                var count = _group?.Servers.Count ?? 0;
                HomeToast.Show(L.F("Sub_ToastRefreshed", L.Plural("Common_ServersPlural", count)));
            }
        }
        catch (Exception ex)
        {
            Logging.SaveLog("SubscriptionMetaView.Rebind", ex);
        }
    }

    // Pin toggle: flip SubItem.Pinned and persist it (pinned subs sort first / become default tab).
    private async void OnPinClick(object? sender, RoutedEventArgs e)
    {
        var subId = _currentSubId;
        if (subId.IsNullOrEmpty())
        {
            return;
        }
        try
        {
            var sub = await AppManager.Instance.GetSubItem(subId);
            if (sub is null)
            {
                return;
            }
            sub.Pinned = !sub.Pinned;
            await SQLiteHelper.Instance.UpdateAsync(sub);
            // Optimistic tint update (still the same sub in view).
            if (_currentSubId == subId)
            {
                PinIcon.Foreground = sub.Pinned ? _accent : _muted;
            }
        }
        catch (Exception ex)
        {
            Logging.SaveLog("SubscriptionMetaView.Pin", ex);
        }
    }

    private void OnSupportClick(object? sender, RoutedEventArgs e)
    {
        if (_supportUrl.IsNotEmpty())
        {
            ProcUtils.ProcessStart(_supportUrl);
        }
    }

    private void OnTelegramClick(object? sender, RoutedEventArgs e)
    {
        if (_webPageUrl.IsNotEmpty())
        {
            ProcUtils.ProcessStart(_webPageUrl);
        }
    }

    // Delete THIS subscription: confirm, then drop its SubItem + all of its servers via the engine
    // (ConfigHandler.DeleteSubItem = SQLite delete of the SubItem + RemoveServersViaSubid). Replaces the
    // old «+» (adding moved to the app's top-right corner). After deletion we refresh the shared list VM,
    // which rebuilds the grouped ServerGroups — so this whole section disappears.
    private async void OnDeleteSubClick(object? sender, RoutedEventArgs e)
    {
        var subId = _currentSubId;
        if (subId.IsNullOrEmpty())
        {
            return;
        }

        try
        {
            // Confirm in the interface's voice («Удалить подписку?»), same yes/no affordance as row-delete.
            //  Подтверждение ВНУТРИ try. Диалогу нужно окно-владелец, и WindowDialog.TryGetOwnerWindow
            //  БРОСАЕТ InvalidOperationException, когда видимых окон нет и MainWindow не найден
            //  (окно ушло в трей / закрывается). Раньше этот вызов стоял снаружи try, а метод —
            //  обработчик события, то есть async void: ловить исключение было НЕКОМУ, и оно уносило
            //  весь процесс. Теперь неудача подтверждения = подписка не удалена, и только.
            if (await UI.ShowYesNo(L.T("Sub_DeleteConfirm")) != ButtonResult.Yes)
            {
                return;
            }

            await ConfigHandler.DeleteSubItem(AppManager.Instance.Config, subId);
            // Rebuild the real ProfileItems → HomeViewModel.ServerGroups reprojects → this section is gone.
            var profiles = Profiles;
            if (profiles is not null)
            {
                await profiles.RefreshServers();
            }
        }
        catch (Exception ex)
        {
            Logging.SaveLog("SubscriptionMetaView.DeleteSub", ex);
        }
    }

    // ── Design-time only ────────────────────────────────────────────────────

    /// <summary>Representative sample for the Avalonia previewer. Never runs at runtime.</summary>
    private void ApplyDesignSample()
    {
        MetaCard.IsVisible = true;
        RefreshButton.IsVisible = true;
        PinButton.IsVisible = true;
        DeleteButton.IsVisible = true;
        TitleText.Text = "erlish";
        SubtitleText.Text = "10.07.2026 17:17 · Автообновление — 1 ч.";
        SubtitleText.IsVisible = true;
        MetaBody.IsVisible = true;
        //  Ограниченный образец, чтобы превьюер показал сам градиент-заливку и не-налезающую дату.
        TrafficText.Text = "1,7 ТБ / 3 ТБ";
        _trafficFraction = 0.57d;
        ApplyTrafficFill();
        TrafficFill.Background = BuildTrafficBrush();
        ExpiryText.Text = "до 24.07.2026";
        ExpiryText.Foreground = _muted;
        AnnounceText.Text = "Без рекламы на YouTube: Hybrid, Russia\nЕсли не работает, обновите подписку\n@departamentvpn";
        AnnounceText.IsVisible = true;
        PinIcon.Foreground = _muted;
        ActionRow.IsVisible = true;
        SupportButton.IsVisible = true;
        TelegramButton.IsVisible = true;
    }
}
