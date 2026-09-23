using System.Reactive.Disposables;
using Avalonia.Animation;
using Avalonia.Animation.Easings;
using Avalonia.LogicalTree;
using Avalonia.VisualTree;
using v2rayN.Desktop.Account;
using v2rayN.Desktop.Base;
using v2rayN.Desktop.Common;
using v2rayN.Desktop.Manager;
using v2rayN.Desktop.ViewModels;

namespace v2rayN.Desktop.Views;

public partial class MainWindow : WindowBase<MainWindowViewModel>
{
    private static Config _config;
    private bool _blCloseByUser = false;

    // Вкладки. «Главная» подключена к реальному движку (HomeViewModel); список серверов живёт
    // в её левой колонке — отдельной вкладки «Сервера» в рейле нет.
    private readonly Control _homeView = new HomeView();
    private readonly Control _settingsView = new SettingsView();
    private readonly AccountView _accountView = new AccountView();
    private readonly Button[] _navButtons;
    private HomeViewModel? _homeViewModel;

    // Компактная (телефонная) «Главная»: свой одностолбцовый экземпляр (широкая и компактная
    // делят ОДИН HomeViewModel, но каждая держит своё дерево «Главной» — см. ViewFor).
    private readonly CompactHomeView _compactHome = new();

    // ==================== ТРИ раскладки (tokens.md «Размеры окна и масштаб») ====================
    //   Wide    — обычная, логические ~1366×768: рейл слева, две колонки (левая 440).
    //   Compact — 900×600: РЕЙЛ ОСТАЁТСЯ на месте, колонки жмутся (левая 340, кольцо 212, скорости сжаты).
    //   Narrow  — 420×860 «как телефон»: навигация уходит ВНИЗ, колонки складываются в ОДИН скролл
    //             (кольцо сверху, список под ним), кольцо 190.
    // Порог узкого — 420 (tokens.md) и сравнение НЕСТРОГОЕ: пресет 420×860 обязан быть узким. Ниже он
    // ещё поднимается до ширины, на которой двум колонкам физически хватает места (TwoColumnMinWidth) —
    // см. комментарий там же. Порог компактной — 1100 (между пресетами 900 и 1366): 1366/1920/2560
    // остаются широкими, 1024-й ноутбук — компактным. Гистерезис 24 на ОБА порога: окно, «припаркованное»
    // на границе, не мигает между раскладками при драге. Сравнения живут в КООРДИНАТАХ КОНТЕНТА
    // (Bounds.Width / _uiScale, см. подписку в ctor).
    private enum LayoutMode
    {
        Wide,
        Compact,
        Narrow,
    }

    private const double NarrowBreakpointWidth = 420.0;
    private const double CompactBreakpointWidth = 1100.0;
    private const double LayoutHysteresis = 24.0;

    // Ширина, УЖЕ которой двухколоночной «Главной» физически не существует: рейл 74 + левая колонка 340
    // + разделитель 1 + кольцо 212 с боковыми отступами 10 = 647. Пакет описывает три пресета (1366 · 900
    // · 420) и порог узкого 420, но промежуток 421…647 не описывает никак, а верстать в нём двумя
    // колонками нечем — правой панели осталось бы несколько пикселей и кольцо вылезло бы на список.
    // Поэтому узкая раскладка забирает и его: на всех ТРЁХ пресетах поведение ровно по пакету, а
    // «сломанной» ширины не существует вовсе. См. ВОПРОСЫ в отчёте.
    private const double RailWidth = 74.0;
    private const double CompactHeroMinWidth = 232.0;
    private const double TwoColumnMinWidth = RailWidth + CompactLeftColumn + 1.0 + CompactHeroMinWidth;

    private LayoutMode _layout = LayoutMode.Compact;  // старт компактный (дефолт окна 900×600); ctor уточняет
    private bool _boundsSeeded;                      // первый живой Bounds-тик — без кроссфейда морфинга

    /// <summary>Узкая раскладка: нижняя навигация + одностолбцовая «Главная» (CompactHomeView).</summary>
    private bool IsNarrow => _layout == LayoutMode.Narrow;

    // Целевые размеры тумблера раскладки (двойной клик по навигации / drag-to-edge) — ровно пресеты
    // пакета, в ЛОГИЧЕСКИХ единицах. Не широкая → широкая 1366×768; широкая → компактная 900×600. Узкая
    // (420×860) достижима ресайзом до минимума окна (MinWidth = 420), поэтому в тумблере её нет. Все цели
    // домножаются на _uiScale и клампятся в WorkingArea.
    private const double WideToggleWidth = 1366.0;
    private const double WideToggleHeight = 768.0;
    private const double CompactToggleWidth = 900.0;
    //  600, а не 860: владелец попросил убрать высоту компактной раскладки примерно в полтора раза
    //  («в 1.4 / 1.5»). На его 2K-мониторе прежние 860 при масштабе 1.25 давали 1075 физических —
    //  окно выходило выше соседних приложений (Happ там 960). Ширина не тронута.
    private const double CompactToggleHeight = 600.0;

    // Доля рабочей области, которую занимает СТАРТОВОЕ окно, если пресет в неё не влезает. Компактный
    // пресет почти квадратный (900×860), а мониторы 16:9 — на 1920×1080 при факторе 1.40 ему нужно 1204
    // пикселя по высоте при 1080 доступных. Без этой доли безрамочное окно вставало бы ровно по краям
    // рабочей области и читалось как сломанный «развёрнутый» режим. 0.92 = видимый зазор со всех сторон.
    private const double StartupWorkAreaFill = 0.92;

    // ==================== Масштаб интерфейса: подбор под монитор × выбор пользователя ====================
    // Фактор применяется к КОРНЮ контента через LayoutTransformControl (uiScaleHost) и складывается из ДВУХ:
    //
    //   • подбор под МОНИТОР (UiScaleState.Auto) — считается на старте из разрешения экрана и СИСТЕМНОГО
    //     масштаба ОС так, чтобы логическая раскладка держалась около 1366×768 (tokens.md): 1366 → 1.00,
    //     1920 → 1.40, 2560 → 1.85. Системный масштаб при этом делится, а не умножается второй раз;
    //   • «Масштаб интерфейса» ПОЛЬЗОВАТЕЛЯ (UiScaleState.Current) — 100/110/125/150% из настроек и
    //     Ctrl +/Ctrl −/Ctrl 0. Только он персистится (UiItem.UiScale) и переживает перезапуск; подбор
    //     под монитор пересчитывается каждый раз заново, потому что монитор может быть другим.
    //
    // _uiScale = UiScaleState.Effective = Auto × Current. Базовые MinWidth/MinHeight (из XAML) и стартовый
    // размер окна масштабируются им же — иначе на высоком факторе контенту не хватает места и он клиппится.
    private double _uiScale = 1.0;
    private double _baseMinWidth;   // == MinWidth в XAML при факторе 1.0 (снимается в ctor до правок мин-размера)
    private double _baseMinHeight;  // == MinHeight в XAML при факторе 1.0

    // Драг-к-краю: когда пользователь тащит компактное окно к верхнему/боковому краю рабочей
    // области — разворачиваем в широкую. Порог в физ. пикселях; _edgeSnapSuspended гасит ложные
    // срабатывания во время программного репозиционирования (клампы/тумблер).
    private const int EdgeSnapThreshold = 6;
    private bool _edgeSnapSuspended;
    private bool _titleDragging;
    private bool _edgeExpandRequested;

    // ==================== Персист ПОЛОЖЕНИЯ окна между запусками ====================
    // Размер окна персистит база (WindowBase.OnClosed → UiItem.WindowSizeItem), а позицию она не хранила
    // вовсе — каждый запуск центрировал окно заново. Модель UiItem лежит в ServiceLib (чужая дорожка),
    // поэтому desktop-слой держит позицию в СВОЁМ маленьком файле guiConfigs/WindowPosition.txt («X,Y»
    // в физ. пикселях). Помним последнюю позицию НОРМАЛЬНОГО состояния (развёрнутое/свёрнутое окно не
    // должно перетирать её), пишем на тех же выходных путях, что и размер (StorageUI/OnClosed), а
    // восстанавливаем ПОСЛЕ base.OnLoaded (тот центрирует) — и только если сохранённая точка попадает
    // на живой экран: отключённый монитор не должен уносить окно за пределы видимого.
    private PixelPoint? _lastNormalPosition;   // живая позиция Normal-окна (пишется на каждом переносе)
    private bool _positionSeeded;              // первичное восстановление сделано → живые переносы можно запоминать
    private AppTab _currentTab = AppTab.Home;   // ОДНО состояние вкладки на обе раскладки
    private bool _isEmpty = true;

    //  Пока НЕ пришёл первый ответ о составе подписок, «пусто» — это «ещё не знаем», а не «нечего
    //  показывать». Гейт в этот момент не имеет права выбирать онбординг: у вошедшего владельца
    //  на холодном старте мелькал экран входа, и лишь потом его перекидывало на Главную.
    private bool _profilesResolved;
    private bool _isSyncing;                     // E3: идёт пост-логин импорт → оверлей синхронизации
    private bool _isLoggedIn;                    // A1: залогинен ли пользователь → пустое состояние ведёт на Главную, а не на онбординг-вход
    private bool _layoutInitialized;             // C6: первый ApplyLayoutMode без кроссфейда морфинга

    // ОДИН экземпляр AccountViewModel на всё приложение: делится между вкладкой «Аккаунт»
    // (AccountView) и суб-страницей «Вход» (LoginView), поэтому состояние входа распространяется
    // на оба (P0-8). Ctor VM безопасен на этапе инициализации полей (без AppManager).
    private readonly AccountViewModel _accountVm =
        Design.IsDesignMode ? AccountViewModel.CreateDesign() : new AccountViewModel();

    // Стек открытых суб-страниц (Buy/Login/Devices/History) в хосте subPageHost. Back снимает
    // верхнюю; когда стек пуст — хост скрыт и снова видна вкладка/онбординг под ним.
    private readonly List<Control> _subStack = new();

    // Моушен-токены оболочки теперь берутся из ЕДИНОГО C#-каталога Common/Motion.cs (Motion.Ease.* /
    // Motion.Dur.*), который зеркалит XAML Ease.*/таблицу длительностей — так XAML и C# не расходятся.
    // Раньше здесь дублировались локальные easings и константы длительностей входа/выхода; их значения
    // были идентичны Motion.Ease.OutQuint/Standard и Motion.Dur.Reveal/Exit — дедуп без смены поведения.
    // Индикатор подключения в рейле красится ТЕМА-токенами через класс .on (Ellipse.ConnDot в разметке).

    // Смена вкладки (P0-2): вход = НАПРАВЛЕННЫЙ горизонтальный слайд translateX ±16→0 + fade-in
    // (Motion.Dur.State 220мс, Motion.Ease.OutQuint) поверх выхода = быстрый fade-out (Motion.Dur.Exit
    // 150мс, Motion.Ease.Standard, короче входа). Направление задаёт дельта nav-индекса (глубже по строке
    // навигации Home▸Settings▸Account → въезд СПРАВА; назад → СЛЕВА) — эхо геометрии навигации вместо
    // «единого рефлекса» одинакового вертикального подъёма на всех вкладках. Дистанция 16px = ЕДИНЫЙ
    // slide-словарь с суб-страницами (AnimateSubPageIn тоже 16px X). ContentSlideFrom — дистанция (px).
    private const double ContentSlideFrom = 16.0;

    // Токены отмены незавершённой анимации на каждый анимируемый узел (перезапуск отменяет предыдущую).
    private CancellationTokenSource? _subPageAnim;
    private CancellationTokenSource? _shellAnim;
    private CancellationTokenSource? _layoutAnim;
    private CancellationTokenSource? _resizeAnim;   // Bug6: плавная анимация размера окна при тумблере раскладки
    private CancellationTokenSource? _contentAnim;  // смена вкладки в едином contentHost (directional slide+fade)
    private Control? _currentShellView;          // текущий видимый оверлей оболочки (для кроссфейда)
    private Control? _currentContentView;        // текущая видимая вкладка в contentHost (keep-alive своп)
    private int _contentZ;                       // ZIndex-счётчик: входящая вкладка всегда поверх уходящей
    private TranslateTransform? _railIndicatorTransform;   // Y-слот путешествующего индикатора рейла (P0-1)
    private bool _railIndicatorSeeded;                     // первый показ индикатора — мгновенно на активном слоте
    private int _navIndex;                                 // индекс текущей вкладки (Home0/Account1/Settings2) → направление слайда (P0-2)
    private readonly HashSet<AppTab> _entrancePlayed = new();  // region-stagger: первая активация вкладки за сессию (P1-1)

    // Bug8: интеракции буфера/скана регистрируются на ВРЕМЯ ЖИЗНИ окна (а не под WhenActivated, что снимало
    // их при деактивации). Держим их здесь и освобождаем один раз в OnClosed — так угловой «+» (MenuFlyout,
    // деактивирующий окно) не теряет обработчик и «добавить из буфера/по QR» не проваливается молча.
    private readonly CompositeDisposable _windowInteractions = new();

    public MainWindow()
    {
        InitializeComponent();

        _config = AppManager.Instance.Config;

        // ==================== Масштаб интерфейса (подбор под монитор × выбор пользователя) ====================
        // Снимаем базовые мин-размеры из XAML (420×560) ДО каких-либо правок, затем засеваем ПОЛЬЗОВАТЕЛЬСКИЙ
        // фактор из конфига (клампится) и подбираем фактор под монитор. Итог (Auto × Current) идёт в корневой
        // ScaleTransform и в мин-размеры окна. Подписка на UiScaleState.Changed держит трансформ, мин-размер и
        // брейкпоинт раскладки в синхроне при изменении масштаба из настроек ИЛИ по горячим клавишам (единый
        // путь применения — OnUiScaleChanged/ApplyUiScale).
        _baseMinWidth = MinWidth;
        _baseMinHeight = MinHeight;
        UiScaleState.Initialize(_config.UiItem.UiScale);
        RefreshAutoScale();       // экран в ctor может быть ещё недоступен — OnLoaded пересчитает
        _uiScale = UiScaleState.Effective;
        UiScaleState.Changed += OnUiScaleChanged;
        ApplyUiScaleToWindow();   // трансформ + мин-размеры на старте (OnLoaded затем впишет окно в экран)

        // Сохранённый размер применяем СРАЗУ, до первого layout: иначе дефолт разметки (900×860) успевает
        // дать первый Bounds-тик ДО того, как WindowBase.OnLoaded восстановит размер, раскладка пересекает
        // брейкпоинт от ЧУЖОЙ ширины (900 → компакт), а гистерезис затем удерживает её и на настоящей
        // (сохранённые 1100…1123 открывались компактными). Экран здесь ещё не нужен: кламп в рабочую
        // область сделает WindowBase.OnLoaded, размер хранится уже в физ. DIP (после UI-zoom).
        try
        {
            if (ConfigHandler.GetWindowSizeItem(_config, GetType().Name) is { } savedSize)
            {
                Width = savedSize.Width;
                Height = savedSize.Height;
            }
        }
        catch { }

        //  Первый запуск: сохранённого размера ещё нет. Стартовый размер (компактный пресет × масштаб под
        //  монитор) ставим ТОЖЕ ДО ПЕРВОГО КАДРА. Раньше его ставил только OnLoaded, а он приходит уже после
        //  первого кадра: окно сперва показывалось в размере из разметки (900×600) с содержимым, уже
        //  увеличенным под монитор, — правая колонка сжималась, щит пропадал, — и через долю секунды
        //  вырастало и перестраивалось на глазах. OnLoaded зовёт то же самое ещё раз и ничего не меняет.
        ApplyStartupSize();

        // Ресайз-грипы безрамочного окна: 8 зон → BeginResizeDrag. Видимость грипов = только Normal-состояние.
        WireResizeGrips();
        this.GetObservable(WindowStateProperty)
            .Subscribe(s => resizeGripHost.IsVisible = s == WindowState.Normal);

        // Скруглённые углы безрамочного окна (см. подробный комментарий у ApplyWindowCorners).
        WireWindowCorners();

        // «Облегчённый режим» (reduced-motion) теперь РЕАКТИВЕН: единый источник — MotionState.
        // MainWindow сеет его из конфига и подписывается на изменения; SettingsViewModel двигает флаг
        // live (без рестарта). ApplyMotionMode вешает/снимает класс .lite (обнуляет press/hover/reveal
        // оболочки через :is(Window).lite + свёртку рейла); смену вкладок keep-alive-хоста глушит/оживляет
        // сам SwapContent по MotionState.IsLite (мгновенный своп vs rise/fade §A.4). Хореографию connect-
        // щита гасит сам ConnectHeroView по тому же MotionState. Итог: тумблер lite мгновенно ГЛУШИТ ВСЁ
        // движение (щит, переходы вкладок, page-rise) и так же мгновенно оживляет обратно.
        MotionState.Initialize(_config.UiItem.LiteMode);
        ApplyMotionMode(MotionState.IsLite);
        MotionState.Changed += OnMotionStateChanged;

        // Плавная смена темы: App.ApplyTheme (обе кнопки настроек — база и монохром) отдаёт свой своп
        // сюда, а мы оборачиваем его в круговую заливку (снимок → своп под снимком → расширяющийся клип).
        // Хук — единственный на приложение (одно окно), живёт всё время работы; в lite сам делает мгновенно.
        App.ThemeTransitionHook = RunThemeTransition;

        // Точка старта заливки = место последнего нажатия (тап по тумблеру темы в настройках). Туннельно и
        // handledEventsToo — ловим даже если кнопка «съест» событие; координата — в системе координат окна.
        AddHandler(PointerPressedEvent, OnAnyPointerPressed, RoutingStrategies.Tunnel, handledEventsToo: true);

        KeyDown += MainWindow_KeyDown;

        // Chrome окна: drag + системные кнопки.
        titleBar.PointerPressed += TitleBar_PointerPressed;
        btnMin.Click += (_, _) => WindowState = WindowState.Minimized;
        btnMax.Click += (_, _) => WindowState = WindowState == WindowState.Maximized ? WindowState.Normal : WindowState.Maximized;
        btnClose.Click += (_, _) => Close();

        // Кнопка внизу рейла сворачивает/разворачивает ЛЕВУЮ навигацию (раньше прятала окно
        // в трей — убрано; в трей ведёт иконка App.axaml и «мин» в заголовке). Тумблит класс
        // .railCollapsed на bodyRoot → стили гонят navItems Width 76↔0 + шеврон ‹↔› (OutQuint,
        // гасится под .lite). Бренд-марка, railStatusDot и сама кнопка остаются видны в слим-полосе.
        btnRailToggle.Click += (_, _) => ToggleRail();

        // Единое состояние вкладки для ОБЕИХ раскладок: рейл (широкая) и нижняя навигация
        // (компактная) пишут в ОДИН ShowTab, который кладёт контент в текущий видимый хост.
        _navButtons = [navHome, navAccount, navSettings];
        navHome.Click += (_, _) => ShowTab(AppTab.Home);
        navSettings.Click += (_, _) => ShowTab(AppTab.Settings);
        navAccount.Click += (_, _) => ShowTab(AppTab.Account);
        bottomNav.TabSelected += (_, tab) => ShowTab(tab);
        _compactHome.AccountRequested += (_, _) => ShowTab(AppTab.Account);

        // Drag-to-edge: тащим компактное окно к краю рабочей области → разворот в широкую.
        PositionChanged += OnPositionChanged;

        // Keep-alive: ВСЕ вкладки — постоянные дети contentHost (широкая/компактная «Главная»,
        // Настройки, Аккаунт). Они всегда в дереве (measured/arranged), скрыты через Opacity/hit-test
        // (НЕ IsVisible — тот бы гнал повторный layout при показе). Смена вкладки = дешёвый композитный
        // Opacity+TranslateY на уже разложенной вью → без detach/reattach и без first-layout под кадром
        // перехода (это и был лаг переключения). ZIndex ставит SwapContent (входящая поверх уходящей).
        //
        // НО В ДЕРЕВО ОНИ ПОПАДАЮТ НЕ ВСЕ СРАЗУ. До первого кадра нужна ровно одна вкладка — та, что
        // пользователь увидит; остальные три только удлиняли первый проход раскладки, который и так
        // самый дорогой за весь запуск. Поэтому здесь их только гасим, а в дерево кладём отложенно,
        // сразу после первого кадра (см. ниже) — к моменту, когда пользователь дотянется до вкладки,
        // они уже разложены, и переключение остаётся тем же дешёвым свопом без first-layout.
        // EnsureAttached страхует любой более ранний показ (INITIAL_TAB, смена раскладки).
        foreach (var v in new Control[] { _homeView, _compactHome, _settingsView, _accountView })
        {
            v.Opacity = 0d;
            v.IsHitTestVisible = false;
        }

        // Первичная раскладка + вотчер ширины окна. Наблюдаем Bounds окна (ширина клиентской
        // области); при пересечении брейкпоинта раскладка меняется РОВНО один раз (гистерезис).
        // Режим на старте берём из ЛОГИЧЕСКОЙ ширины, с которой окно откроется (сохранённый размер или
        // компактный пресет), а не из «широкой по умолчанию»: иначе первый же Bounds-тик перекладывал бы
        // шелл широкая→компактная и первый кадр показывал бы чужую раскладку.
        _layout = ResolveLayoutInitial(StartupLogicalWidth());
        ApplyLayoutMode(_layout);
        // Пороги раскладки живут в КООРДИНАТАХ КОНТЕНТА (после UI-zoom): LayoutTransformControl
        // масштабирует контент на _uiScale, поэтому контент видит ширину Bounds.Width/_uiScale. Делим здесь,
        // чтобы зумнутое окно переключало раскладку осмысленно (при _uiScale=1.0 — прежнее поведение 1:1).
        this.GetObservable(BoundsProperty).Subscribe(b => UpdateLayoutMode(b.Width / _uiScale));

        // Общий AccountViewModel на вкладку «Аккаунт» (в рантайме DataContext ставит MainWindow,
        // не сама вью — тот же экземпляр уедет в LoginView). «Управление»-строки и CTA входа
        // поднимают события — здесь они превращаются в открытие суб-страниц.
        _accountView.DataContext = _accountVm;
        _accountView.BuyRequested += (_, _) => OpenBuy();
        _accountView.DevicesRequested += (_, _) => OpenDevices();
        _accountView.HistoryRequested += (_, _) => OpenHistory();
        _accountView.LoginRequested += (_, _) => OpenLogin();
        _accountView.EmailErrandRequested += (_, errand) => OpenEmailErrand(errand);

        // Вкладка «Аккаунт» ВСЕГДА видна в шелле (как нижняя навигация Android): пользователь
        // с подпиской, но без входа, иначе не доберётся до логина. В logged-out AccountView сам
        // показывает гейт входа («Войдите в аккаунт» + Telegram CTA + «Управление»). Гейтинга нет.
        // DEV screenshot hook: INITIAL_TAB=settings|account opens that tab on launch.
        switch (Environment.GetEnvironmentVariable("INITIAL_TAB"))
        {
            case "settings": ShowTab(AppTab.Settings); break;
            case "account": ShowTab(AppTab.Account); break;
        }

        // DEV screenshot hook: PREVIEW_VIEW=buy|login|devices|history renders that (still
        // un-wired) sub-page with design-time data into the content area for capture. The four
        // link-email|link-email-wait|change-email|set-password values render the «Способы входа»
        // errand page at each of its steps — the wait one with the letter already out, because that
        // state only exists after a real request and otherwise cannot be looked at.
        var previewView = Environment.GetEnvironmentVariable("PREVIEW_VIEW");
        if (previewView is not null)
        {
            Control? preview = previewView switch
            {
                "buy" => new BuyView { DataContext = BuyViewModel.CreateDesign() },
                "account" => new AccountView { DataContext = AccountViewModel.CreateDesign() },
                "account-nopass" => new AccountView { DataContext = AccountViewModel.CreateDesignEmailWithoutPassword() },
                "login" => new LoginView { DataContext = AccountViewModel.CreateDesign() },
                "link-email" => ErrandPreview(EmailErrand.Link),
                "link-email-wait" => ErrandPreview(EmailErrand.Link, waiting: true),
                "change-email" => ErrandPreview(EmailErrand.Change),
                "set-password" => ErrandPreview(EmailErrand.SetPassword),
                "devices" => new DevicesView { DataContext = DevicesViewModel.CreateDesign() },
                "history" => new PaymentHistoryView { DataContext = PaymentHistoryViewModel.CreateDesign() },
                _ => null
            };
            if (preview is not null)
            {
                onboardingView.IsVisible = false;
                bodyRoot.IsVisible = true;
                contentHost.Children.Add(preview);
                SwapContent(preview, animate: false);
            }
        }

        // Питаем «Главную» реальным HomeViewModel, как только доступен корневой ViewModel
        // (App присваивает его сразу после Build, до показа окна — DataContext успевает встать
        // раньше активации HomeView). Здесь же биндим индикатор подключения в рейле.
        this.WhenAnyValue(x => x.ViewModel)
            .Where(vm => vm != null)
            .Take(1)
            .Subscribe(vm =>
            {
                // Bug8: регистрируем интеракции буфера/скана на ВРЕМЯ ЖИЗНИ окна, как только доступен
                // ViewModel (App присваивает его сразу после Build). Раньше они жили под WhenActivated и
                // снимались при деактивации — открытие MenuFlyout углового «+» деактивировало окно,
                // обработчик пропадал, и AddServerViaClipboardAsync бросал UnhandledInteractionException в
                // незамеченную задачу → «ничего не происходит». Теперь обработчик жив всегда.
                RegisterWindowInteractions(vm!);
                SetupHome(vm!);
                //  Перезапуск ради обновления закрыл подключение — вернуть его, как только движок и список
                //  готовы (метка одноразовая; обычный запуск ничего не делает).
                UpdateReconnect.Start(vm!, () => _homeViewModel);
            });

        this.WhenActivated(disposables =>
        {
            // Питаем скрытый StatusBarView (сохраняем интеракции/иконку трея/StatusBarViewModel).
            this.OneWayBind(ViewModel, vm => vm.StatusBarViewModel, v => v.contentStatusBarView.Content).DisposeWith(disposables);

            // Live-смена языка: подсказка свёртки рейла ставится императивно (не {loc:T}), поэтому
            // переустанавливаем её по событию L.LanguageChanged. Отписка при деактивации.
            L.Instance.LanguageChanged += OnLanguageChanged;
            Disposable.Create(() => L.Instance.LanguageChanged -= OnLanguageChanged).DisposeWith(disposables);

            // Bug8: ReadTextFromClipboardInteraction / ScanScreenInteraction более НЕ регистрируются здесь —
            // они живут на время жизни окна (RegisterWindowInteractions), чтобы деактивация окна flyout-«+»
            // не снимала их. Оставшиеся интеракции (выбор файла/картинки, показ-скрытие) привязаны к
            // активной сессии окна и корректно снимаются при деактивации.
            ViewModel.BrowseImageFileInteraction.RegisterHandler(async interaction =>
            {
                var result = await UI.OpenFileDialog(null);
                interaction.SetOutput(result);
            }).DisposeWith(disposables);

            ViewModel.ShowHideWindowInteraction.RegisterHandler(interaction =>
            {
                ShowHideWindow(interaction.Input);
                interaction.SetOutput(Unit.Default);
            }).DisposeWith(disposables);

            AppEvents.SendSnackMsgRequested
              .AsObservable()
              .ObserveOn(RxSchedulers.MainThreadScheduler)
              .Subscribe(async content => await DelegateSnackMsg(content))
              .DisposeWith(disposables);

            AppEvents.AppExitRequested
              .AsObservable()
              .ObserveOn(RxSchedulers.MainThreadScheduler)
              .Subscribe(_ => StorageUI())
              .DisposeWith(disposables);

            AppEvents.ShutdownRequested
              .AsObservable()
              .ObserveOn(RxSchedulers.MainThreadScheduler)
              .Subscribe(Shutdown)
              .DisposeWith(disposables);
        });

        if (Utils.IsWindows() && !Design.IsDesignMode)
        {
            ThreadPool.RegisterWaitForSingleObject(Program.ProgramStarted, OnProgramStarted, null, -1, false);
            HotkeyManager.Instance.Init(_config, OnHotkeyHandler);
        }

        if (_config.UiItem.AutoHideStartup && Utils.IsWindows())
        {
            WindowState = WindowState.Minimized;
        }
    }

    #region Nav & Chrome

    // ==================== Единая смена вкладки (обе раскладки) ====================
    // ОДИН источник истины (_currentTab) и ОДИН общий хост (contentHost). Настройки/Аккаунт —
    // общие экземпляры, которые ВСЕГДА живут в этом ЕДИНОМ хосте, поэтому смена ширины физически
    // не «перецепляет» живой контрол в другой хост (был краш компакт→Настройки→расширение).
    // «Главная» имеет своё дерево на раскладку (компактное одностолбцовое vs широкое двухколоночное);
    // ViewFor выбирает нужное по IsNarrow. Все вкладки — постоянные keep-alive дети ЕДИНОГО хоста;
    // смена = переключение ВИДИМОЙ поверхности (SwapContent), без переноса между родителями и без
    // detach/reattach. Отдельной вкладки «Сервера» нет: серверы — часть «Главной».
    private void ShowTab(AppTab tab, bool animate = true)
    {
        // Направление слайда контента = дельта индекса в строке навигации (Home0▸Account1▸Settings2):
        // +1 глубже (въезд справа), −1 назад (слева). На мгновенном свопе (layout swap / первый показ)
        // направление не нужно (0). _navIndex обновляем ВСЕГДА, чтобы следующий переход считался от
        // фактической вкладки (layout-свопы зовут ShowTab с той же вкладкой → дельта 0, ничего не ломают).
        var toIndex = NavIndex(tab);
        var direction = animate ? toIndex.CompareTo(_navIndex) : 0;
        _navIndex = toIndex;
        _currentTab = tab;

        SetRailActive(tab, animate);
        bottomNav.SetSelected(tab);

        SwapContent(ViewFor(tab), animate, direction);
    }

    /// <summary>Кладёт вкладку в общий хост, если её там ещё нет (отложенный keep-alive, см. ctor).</summary>
    private void EnsureAttached(Control v)
    {
        if (v.Parent is null)
        {
            contentHost.Children.Add(v);
        }
    }

    /// <summary>Досаживает в дерево остальные keep-alive вкладки — после первого кадра, на холостом ходу.</summary>
    private void AttachRemainingTabs()
    {
        foreach (var v in new Control[] { _homeView, _compactHome, _settingsView, _accountView })
        {
            EnsureAttached(v);
        }
    }

    private static int NavIndex(AppTab tab) => tab switch
    {
        //  Порядок пакета: Главная · Аккаунт · Настройки (README, прототип) — от него считается
        //  направление слайда контента.
        AppTab.Account => 1,
        AppTab.Settings => 2,
        _ => 0,
    };

    // Off-screen-guard (P0-3): анимируем оболочку только когда окно реально видно. В трее (Hide → !IsVisible)
    // или свёрнутом (Minimized) состоянии программный ShowTab/индикатор/стаггер снапятся в финал, а не тикают
    // за экраном — закрывает класс «off-screen animation loop».
    private bool IsWindowLive() => IsVisible && WindowState != WindowState.Minimized;

    // ==================== Keep-alive своп вкладок (rise+fade §A.4) ====================
    // Все вкладки постоянно реализованы детьми contentHost; здесь только меняем ВИДИМУЮ поверхность
    // дешёвой композитной анимацией (Opacity + TranslateY) на уже разложенной вью — без detach/reattach
    // и first-layout под кадром перехода. animate:false — мгновенно (первый показ, своп раскладки);
    // под .lite — тоже мгновенно (reduced-motion). Rise+fade идентичен прежнему page-transition.
    private void SwapContent(Control target, bool animate, int direction = 0)
    {
        var previous = _currentContentView;
        if (previous == target)
        {
            target.Opacity = 1d;
            target.IsHitTestVisible = true;
            target.RenderTransform = null;
            return;
        }
        _currentContentView = target;

        EnsureAttached(target);
        target.ZIndex = ++_contentZ;   // входящая ВСЕГДА поверх уходящей → подъём читается корректно

        // Bug7: РОВНО одна интерактивная поверхность. Гасим hit-test на ВСЕХ keep-alive вкладках, кроме
        // target (а не только на previous). Широкая и компактная «Главная» — РАЗНЫЕ экземпляры, которые
        // свопаются при смене ширины; устаревший IsHitTestVisible=true на скрытом инстансе Home перехватывал
        // клики поверх видимого → «мёртвый» выбор сервера/подключение в широкой раскладке. Теперь ровно одна
        // вкладка (target) хит-тестируется, остальные — прозрачны для указателя.
        foreach (var v in new Control[] { _homeView, _compactHome, _settingsView, _accountView })
        {
            v.IsHitTestVisible = ReferenceEquals(v, target);
        }
        target.IsHitTestVisible = true;   // покрывает и PREVIEW_VIEW (target вне keep-alive-набора)

        // Мгновенный своп: первый показ (previous == null), reduced-motion (.lite), своп раскладки или
        // окно вне экрана (P0-3: не крутим переход, которого никто не видит).
        if (!animate || previous is null || MotionState.IsLite || !IsWindowLive())
        {
            _contentAnim?.Cancel();
            target.Opacity = 1d;
            target.RenderTransform = null;
            if (previous != null)
            {
                previous.Opacity = 0d;
                previous.RenderTransform = null;
            }
            return;
        }

        _contentAnim?.Cancel();
        var cts = new CancellationTokenSource();
        _contentAnim = cts;
        AnimateContentSwap(target, previous, direction, cts.Token);
    }

    private async void AnimateContentSwap(Control target, Control previous, int direction, CancellationToken ct)
    {
        target.Opacity = 0d;
        // Направленный вход (translateX ±16→0 + fade-in, State 220 OutQuint) поверх выхода (быстрый
        // fade-out, Exit 150 Standard) — параллельно. Знак X = направление по строке навигации: глубже
        // (direction ≥ 0) → въезд справа (+16); назад (direction < 0) → слева (−16). Уходящая только
        // гаснет (без встречного слайда) — keep-alive стек не клипается, не превращается в тяжёлую карусель.
        var fromX = direction < 0 ? -ContentSlideFrom : ContentSlideFrom;
        var enter = RunTranslateFade(target, TranslateTransform.XProperty, fromX, 0d, 0d, 1d, Motion.Dur.State, Motion.Ease.OutQuint, ct);
        var exit = RunFade(previous, previous.Opacity, 0d, Motion.Dur.Exit, Motion.Ease.Standard, ct);
        // Внутренний region-stagger (Home/Settings) — поверх слайда корня; Account сам себя проигрывает.
        PlayTabEntrance(target);
        try { await Task.WhenAll(enter, exit); }
        catch { }
        if (ct.IsCancellationRequested)
        {
            return;
        }
        target.Opacity = 1d;
        target.RenderTransform = null;
        // Гасим уходящую и чистим transform — только если её снова не выбрали (быстрый обратный своп).
        if (previous != _currentContentView)
        {
            previous.Opacity = 0d;
            previous.RenderTransform = null;
        }
    }

    // Home разный на раскладку (компакт vs широкая); Настройки/Аккаунт — единые экземпляры.
    private Control ViewFor(AppTab tab) => tab switch
    {
        AppTab.Settings => _settingsView,
        AppTab.Account => _accountView,
        _ => IsNarrow ? _compactHome : _homeView,
    };

    // ==================== Layout-aware Home binding (connect pipeline + RAM release) ====================
    // ТОЛЬКО «Главная» ТЕКУЩЕЙ раскладки держит живой HomeViewModel: она и хит-тестируется (SwapContent),
    // и несёт connect-щит/список серверов. «Главная» НЕактивной раскладки отвязывается (DataContext=null),
    // что: (1) освобождает её невиртуализованное дерево строк серверов из памяти — при 80–150 серверах это
    // доминирующая стоимость, и держать ДВЕ копии (широкую+компактную) вечно незачем; (2) страхует от
    // «мёртвой широкой»: активная «Главная» ВСЕГДА получает VM (значит HomeView.BindHero привязывает щит,
    // а строки получают DataContext), а скрытая никогда не перехватывает ввод и не биндит щит.
    // Идемпотентно и layout-верно: зовётся из SetupHome (первичная привязка) и из ApplyLayoutMode (на
    // каждом свопе раскладки). Переактивация раскладки заново ставит DataContext → строки и щит оживают.
    private void BindActiveHome()
    {
        if (_homeViewModel is null)
        {
            return;   // VM ещё не готов (первый ApplyLayoutMode до SetupHome) — привяжет SetupHome позже
        }

        if (IsNarrow)
        {
            if (!ReferenceEquals(_homeView.DataContext, null))
            {
                _homeView.DataContext = null;   // отвязать широкую → освободить её строки
            }
            _compactHome.DataContext = _homeViewModel;
        }
        else
        {
            if (!ReferenceEquals(_compactHome.DataContext, null))
            {
                _compactHome.DataContext = null;   // отвязать компактную → освободить её строки
            }
            _homeView.DataContext = _homeViewModel;
        }
    }

    private void SetRailActive(AppTab tab, bool animate)
    {
        foreach (var b in _navButtons)
        {
            b.Classes.Remove("active");
        }
        var active = tab switch
        {
            AppTab.Settings => navSettings,
            AppTab.Account => navAccount,
            _ => navHome,
        };
        active.Classes.Add("active");

        MoveRailIndicator(tab, animate);
    }

    // ==================== Путешествующий индикатор рейла (P0-1) ====================
    // ОДНА акцентная полоса (railIndicator) физически СКОЛЬЗИТ по Y между слотами (motion.md
    // «Навигация»: полоска одна на всю панель и ПЕРЕЕЗЖАЕТ, а не гаснет и зажигается), вместо трёх
    // независимых пилюль, «мигавших» на месте. Рейл всегда показывает 3 пункта (Home/Settings/Account),
    // поэтому шаг фиксированный: позиция = отступ + индекс × высота кнопки. Геометрия из tokens.md
    // «Рейл» — кнопка 64×72, полоска 3×30, значит слот центрируется как Y = index·72 + (72−30)/2 = 21.
    // Первый показ (не seeded) / lite / off-screen / layout-своп (animate:false) — мгновенно на активном
    // слоте (без скольжения с Y=0); дальше — переезд Motion.Dur.Nav 280мс ease-out-quart. Незавершённое
    // скольжение перебивает сам переход на трансформе (он подхватывает живое Y), отменять нечего;
    // на layout-свопе рейл↔бар пере-садим мгновенно (animate:false из ShowTab), т.к. геометрии разные.
    private const double RailButtonHeight = 72d;   // tokens.md «Рейл»: кнопка 64×72
    private const double RailIndicatorHeight = 30d;   // tokens.md «Рейл»: полоска 3×30

    private static double RailSlotY(AppTab tab)
        => (NavIndex(tab) * RailButtonHeight) + ((RailButtonHeight - RailIndicatorHeight) / 2d);

    private void MoveRailIndicator(AppTab tab, bool animate)
    {
        if (railIndicator is null)
        {
            return;
        }
        _railIndicatorTransform ??= new TranslateTransform();
        if (!ReferenceEquals(railIndicator.RenderTransform, _railIndicatorTransform))
        {
            railIndicator.RenderTransform = _railIndicatorTransform;
        }

        var targetY = RailSlotY(tab);
        //  В узкой раскладке рейл СКРЫТ — тап нижнего бара не должен гонять 220мс скольжение на
        //  невидимой полосе (лишняя работа компоновщика); на свопе в широкий рейл сядет мгновенно.
        var instant = !animate || !_railIndicatorSeeded || MotionState.IsLite || !IsWindowLive() || IsNarrow;
        if (instant)
        {
            ClearIndicatorTransition();
            _railIndicatorTransform.Y = targetY;
            _railIndicatorSeeded = true;
            return;
        }

        //  Переезд делает ПЕРЕХОД на самом трансформе, а не императивный Animation.RunAsync:
        //  запуск отрабатывал (замер: instant=False), но полоска всё равно оказывалась в конечной
        //  точке за один кадр — промежуточных положений не было ни на одном из 16 снятых кадров.
        //  Переходы в этом приложении работают везде, поэтому едем ими: ставим Y, а DoubleTransition
        //  доводит его за Motion.Dur.Nav по OutQuart.
        EnsureIndicatorTransition();
        _railIndicatorTransform.Y = targetY;
    }

    //  Переход живёт на трансформе и ставится один раз. В мгновенной ветке его СНИМАЕМ, иначе
    //  «мгновенно» тоже поехало бы (первый показ, lite, свёрнутое окно, своп раскладки).
    private void EnsureIndicatorTransition()
    {
        if (_railIndicatorTransform is null)
        {
            return;
        }
        if (_railIndicatorTransform.Transitions is null)
        {
            _railIndicatorTransform.Transitions = new Transitions();
        }
        if (_railIndicatorTransform.Transitions.Count == 0)
        {
            _railIndicatorTransform.Transitions.Add(new DoubleTransition
            {
                Property = TranslateTransform.YProperty,
                Duration = Motion.Dur.Nav,
                Easing = Motion.Ease.OutQuart,
            });
        }
    }

    private void ClearIndicatorTransition() => _railIndicatorTransform?.Transitions?.Clear();

    // ==================== Per-tab region stagger (P1-1) ====================
    // На активации вкладки её КРУПНЫЕ регионы (≤3) приезжают со сдвигом 40мс (opacity 0→1 + translateY
    // 6→0, State 220 OutQuint) — «одна кривая, разные голоса» вместо единого рефлекса. Императивно, только
    // при анимируемом свопе (значит уже не lite и на экране — AnimateContentSwap туда не заходит иначе),
    // и ОДИН раз за сессию на вкладку (без повторного ре-fade при каждом возврате). Account ПРОПУСКАЕМ —
    // он сам проигрывает свой group-2 стаггер (IsHitTestVisible false→true); connect-щит (ConnectHeroView)
    // тоже НЕ трогаем — он владеет собственной cold-start сборкой и connect-хореографией (никогда не
    // анимируем дважды). Поэтому Home = чип + список серверов (щит несёт свой вход сам); Settings = первые
    // группы-карточки в порядке чтения.
    private void PlayTabEntrance(Control target)
    {
        if (MotionState.IsLite || !IsWindowLive())
        {
            return;
        }
        if (ReferenceEquals(target, _accountView))
        {
            return;   // Account сам проигрывает вход
        }

        var regions = new List<Control>();
        AppTab tab;
        if (ReferenceEquals(target, _settingsView))
        {
            tab = AppTab.Settings;
            // Первые ≤3 верхнеуровневых ребёнка контент-стека (заголовок/карточка… в порядке чтения).
            var sv = target.GetVisualDescendants().OfType<ScrollViewer>().FirstOrDefault();
            if (sv?.Content is Panel panel)
            {
                foreach (var child in panel.Children.OfType<Control>())
                {
                    regions.Add(child);
                    if (regions.Count >= 3)
                    {
                        break;
                    }
                }
            }
        }
        else
        {
            tab = AppTab.Home;   // широкая или компактная «Главная»
            // ТОЛЬКО список серверов. Щит (ConnectHero) НЕ включаем — у него свой вход; чип аккаунта
            // ТОЖЕ не включаем — HomeAccountChip.MaybeRunEntrance сам проигрывает своё появление при
            // резолве аккаунта, и второй стаггер здесь давал бы двойной вход (а при одновременном
            // логине — два аниматора на одном Opacity/RenderTransform). Стаггерим только список.
            if (FindNamed(target, "ServerList") is { } list)
            {
                regions.Add(list);
            }
        }

        if (regions.Count == 0 || !_entrancePlayed.Add(tab))
        {
            return;   // нечего стаггерить, либо уже играли за эту сессию
        }

        for (var i = 0; i < regions.Count; i++)
        {
            RunRegionReveal(regions[i], i);
        }
    }

    private static Control? FindNamed(Visual root, string name)
        => root.GetVisualDescendants().OfType<Control>().FirstOrDefault(c => c.Name == name);

    private static async void RunRegionReveal(Control region, int index)
    {
        region.Opacity = 0d;
        var anim = new Animation
        {
            Duration = Motion.Dur.State,
            Delay = TimeSpan.FromTicks(Motion.Dur.Stagger.Ticks * index),
            Easing = Motion.Ease.OutQuint,
            FillMode = FillMode.Both,   // держим стартовый кадр (opacity 0) на время задержки региона
            Children =
            {
                new KeyFrame { Cue = new Cue(0d), Setters = { new Setter(Visual.OpacityProperty, 0d), new Setter(TranslateTransform.YProperty, 6d) } },
                new KeyFrame { Cue = new Cue(1d), Setters = { new Setter(Visual.OpacityProperty, 1d), new Setter(TranslateTransform.YProperty, 0d) } },
            },
        };
        try { await anim.RunAsync(region); }
        catch { }
        // Контент виден, даже если стаггер no-op/прерван — гейтить видимость нельзя.
        region.Opacity = 1d;
        region.RenderTransform = null;
    }

    // ==================== Адаптивный своп (ширина окна ↔ раскладка) ====================
    // Три режима, два порога, гистерезис 24 в обе стороны (см. LayoutMode выше).
    private void UpdateLayoutMode(double width)
    {
        if (width <= 0)
        {
            return;
        }

        var mode = ResolveLayout(width);
        if (mode != _layout)
        {
            //  Стартовый кламп размера окна (WindowBase.OnLoaded ужимает 1366×768 в рабочую область
            //  маленького экрана) не должен играть кроссфейд морфинга — это ещё не смена раскладки
            //  пользователем, а первая раскладка как таковая.
            if (!_boundsSeeded)
            {
                _layoutInitialized = false;
            }
            ApplyLayoutMode(mode);
        }
        _boundsSeeded = true;
    }

    // Порог узкого — max(420, «двум колонкам не хватило места») и включительно: пресет 420×860 обязан
    // быть узким. Выход из узкого — только за порог+24. Порог компактного — 1100, выход в широкую — за
    // 1100+24. Пороги читаются в координатах контента, поэтому in-app zoom не «залипает» на чужой раскладке.
    private LayoutMode ResolveLayout(double width)
    {
        var narrowBase = Math.Max(NarrowBreakpointWidth, TwoColumnMinWidth);
        var narrowEdge = IsNarrow ? narrowBase + LayoutHysteresis : narrowBase;
        if (width <= narrowEdge)
        {
            return LayoutMode.Narrow;
        }

        var compactEdge = _layout == LayoutMode.Compact
            ? CompactBreakpointWidth + LayoutHysteresis
            : CompactBreakpointWidth;
        return width < compactEdge ? LayoutMode.Compact : LayoutMode.Wide;
    }

    // Стартовый резолв — ЧИСТЫЕ пороги, без гистерезиса. Гистерезис существует, чтобы окно,
    // «припаркованное» на границе, не мигало при живом драге ОТ уже выбранного режима — а на холодном
    // старте прежнего режима нет, и опора ResolveLayout на дефолт поля _layout (Compact) смещала бы
    // выбор: сохранённое окно 1100…1123 открывалось бы компактным, хотя тем же размером при сжатии из
    // широкой оно оставалось широким. Первый живой Bounds-тик дальше работает уже с гистерезисом.
    private LayoutMode ResolveLayoutInitial(double width)
    {
        if (width <= Math.Max(NarrowBreakpointWidth, TwoColumnMinWidth))
        {
            return LayoutMode.Narrow;
        }
        return width < CompactBreakpointWidth ? LayoutMode.Compact : LayoutMode.Wide;
    }

    // Переклад chrome вокруг ЕДИНОГО contentHost: широкая/компактная = [рейл(Auto) | контент(*)],
    // узкая = [контент(*) / нижняя-нав(Auto)]. Меняем только Grid-раскладку/видимость chrome и Content
    // хоста — сами контролы НЕ переносятся между деревьями (нет двойного родителя → нет краша).
    private void ApplyLayoutMode(LayoutMode mode)
    {
        _layout = mode;
        var compact = IsNarrow;

        if (compact)
        {
            // Одна колонка, две строки: контент над нижней навигацией.
            bodyRoot.ColumnDefinitions = new ColumnDefinitions("*");
            bodyRoot.RowDefinitions = new RowDefinitions("*,Auto");
            Grid.SetColumn(contentArea, 0);
            Grid.SetRow(contentArea, 0);
            Grid.SetColumn(bottomNav, 0);
            Grid.SetRow(bottomNav, 1);
        }
        else
        {
            // Две колонки, одна строка: рейл слева, контент справа.
            bodyRoot.ColumnDefinitions = new ColumnDefinitions("Auto,*");
            bodyRoot.RowDefinitions = new RowDefinitions("*");
            Grid.SetColumn(railHost, 0);
            Grid.SetRow(railHost, 0);
            Grid.SetColumn(contentArea, 1);
            Grid.SetRow(contentArea, 0);
        }

        railHost.IsVisible = !compact;
        bottomNav.IsVisible = compact;

        // Bug5: мягкий градиент-скрим снизу контента ТОЛЬКО в узкой (в широкой/компактной — рейл, нижней
        // навигации нет). Контент «растворяется» в фон под безрамочной нижней навигацией вместо
        // резкого обрыва. Клик сквозной (IsHitTestVisible=False в разметке).
        //  Скрим внизу больше не показываем НИКОГДА. Он красился плоским Brush.Bg с градиентной
        //  маской прозрачности — приём, рассчитанный на плоский фон окна. Фон теперь радиальный
        //  градиент, и плоская заливка перестала совпадать с тем, что под ней: внизу окна во всю
        //  ширину появилась полоса иного тона, зрительно срезавшая последнюю строку списка.
        //  Затухание строк в нижнюю навигацию даёт сама панель своей подложкой, отдельный слой
        //  оболочки для этого не нужен.
        navScrim.IsVisible = false;

        // Классы раскладки НА ОКНЕ — тот же приём, что .lite: любая вью может подстроить метрики
        // селектором «:is(Window).compact …» / «:is(Window).narrow …», не заводя своих брейкпоинтов
        // и не дублируя разметку. ОДНА раскладка на все режимы, отличаются только числа.
        Classes.Set("compact", mode == LayoutMode.Compact);
        Classes.Set("narrow", mode == LayoutMode.Narrow);

        // Метрики «Главной» под раскладку: кольцо 230/212/190 и левая колонка 440/340.
        ApplyHomeMetrics();

        ApplyShellVisibility();

        // ==================== Плавный своп раскладки (без джанка) ====================
        // Смена дерева «Главной» (компактное ↔ широкое) под page-rise «дерётся» с рефлоу сетки —
        // видимый скачок «контент прыгнул и осел». Гасим переход НА ВРЕМЯ свопа контента (мгновенная
        // подмена), затем возвращаем режим-верную анимацию (lite-aware) для последующей навигации.
        //
        // Bug6: раньше мгновенная пересборка дерева (ShowTab) происходила при Opacity=1 — один кадр
        // пересобранного контента ВСПЫХИВАЛ до того, как AnimateLayoutSwap ронял opacity в 0 и проявлял
        // заново (это и был видимый «джерк»). Теперь при анимируемом свопе ПРЯЧЕМ contentArea (Opacity=0)
        // ДО пересборки — она проходит невидимо, затем плавно проявляется. delicate-логику свопа (единый
        // хост, отсутствие reparent, гашение перехода) не трогаем — только порядок гашения opacity.
        var willAnimateSwap = _layoutInitialized && !MotionState.IsLite;
        if (willAnimateSwap)
        {
            contentArea.Opacity = 0d;
        }

        // Своп раскладки крутит ТОЛЬКО кроссфейд contentArea (AnimateLayoutSwap); смену дерева «Главной»
        // делаем мгновенно (animate:false) под уже спрятанным contentArea — без гонки rise/fade вкладки
        // с рефлоу сетки (Bug6). Keep-alive дети остаются в дереве, переносов родителя по-прежнему нет.
        ShowTab(_currentTab, animate: false);

        // Привязываем живой HomeViewModel к «Главной» ТЕКУЩЕЙ раскладки и отвязываем неактивную (RAM +
        // «мёртвая широкая»). Под анимируемым свопом contentArea уже скрыт (Opacity=0 выше), а неактивная
        // «Главная» и так на Opacity=0, поэтому освобождение её строк проходит невидимо, без глитча.
        BindActiveHome();

        // C6: мягкий кроссфейд морфинга раскладки (compact↔wide) поверх мгновенной подмены — маскирует
        // рефлоу дерева. ТОЛЬКО opacity на contentArea (без layout/transform); сквозная подложка-градиент
        // bodyRoot остаётся за ним, поэтому не мигает «белым». Пропускаем первый вызов (старт) и .lite.
        if (willAnimateSwap)
        {
            AnimateLayoutSwap();
        }
        _layoutInitialized = true;
    }

    // ==================== Метрики «Главной» под раскладку (900×860) ====================
    // Компактная раскладка отличается от широкой ровно двумя числами: кадр кольца 230 → 212
    // (tokens.md «Кольцо подключения») и левая колонка 440 → 340 (tokens.md «Главная»). Рейл при этом
    // ОСТАЁТСЯ на месте — складывается в один скролл только узкая (420), у неё своё дерево.
    //
    // Кольцо ставится штатным API: ConnectHeroView.SetHeroSize задуман так, что пресет назначает
    // «раскладка-хозяин» — то есть эта оболочка, единственная, кто знает ширину окна.
    //
    // Ширина колонки живёт в ColumnDefinition (Views/HomeView.axaml: ColumnDefinitions="440,1,*"),
    // а ColumnDefinition — не контрол, стилем его не задать; поэтому ставим отсюда и ПО ФОРМЕ: три
    // колонки, первая абсолютная. Форма не совпала — метод молча ничего не делает, сломать чужую
    // разметку он не может. Исходные значения снимаются с самой разметки (не хардкод), чтобы возврат
    // в широкую восстанавливал ровно то, что там написано. Постоянное место этих чисел — сам HomeView.
    private const double CompactLeftColumn = 340.0;

    private double? _homeLeftColumnWidth;   // исходная ширина левой колонки из разметки (440)
    private double? _homeLeftColumnMin;     // исходный MinWidth левой колонки из разметки (380)

    private void ApplyHomeMetrics()
    {
        var compact = _layout == LayoutMode.Compact;

        //  Узкая «Главная» — отдельное дерево (CompactHomeView), кольцо 190 она ставит себе сама.
        _homeView.FindControl<ConnectHeroView>("ConnectHero")?
            .SetHeroSize(compact ? ConnectHeroView.HeroSize.Compact : ConnectHeroView.HeroSize.Normal);

        if (IsNarrow)
        {
            return;   // широкое дерево не показано — его колонки трогать незачем
        }

        var columns = _homeView.GetLogicalDescendants()
            .OfType<Grid>()
            .FirstOrDefault(g => g.ColumnDefinitions.Count == 3 && g.ColumnDefinitions[0].Width.IsAbsolute);
        if (columns is null)
        {
            return;
        }

        var left = columns.ColumnDefinitions[0];
        _homeLeftColumnWidth ??= left.Width.Value;
        var target = compact ? CompactLeftColumn : _homeLeftColumnWidth.Value;
        if (Math.Abs(left.Width.Value - target) > 0.5)
        {
            left.Width = new GridLength(target, GridUnitType.Pixel);
        }

        //  MinWidth содержимого колонки перебил бы 340, поэтому опускаем его вместе с колонкой.
        if (columns.Children.FirstOrDefault(c => Grid.GetColumn(c) == 0) is Control content)
        {
            _homeLeftColumnMin ??= content.MinWidth;
            content.MinWidth = compact
                ? Math.Min(_homeLeftColumnMin.Value, CompactLeftColumn)
                : _homeLeftColumnMin.Value;
        }
    }

    private async void AnimateLayoutSwap()
    {
        _layoutAnim?.Cancel();
        var cts = new CancellationTokenSource();
        _layoutAnim = cts;

        contentArea.Opacity = 0d;
        // P1-2: длительность = Motion.Dur.Shell 200мс (было off-scale 130). Совпадает с анимацией
        // размера окна (AnimateWindowSize 200 OutQuint) → при тумблере/drag-to-edge контент
        // до-материализуется РОВНО на том кадре, где окно перестаёт расти = один плавный морфинг,
        // а не два рассинхронных события. Семантически Shell = «оболочка перекладывается» — это оно.
        try { await RunFade(contentArea, 0d, 1d, Motion.Dur.Shell, Motion.Ease.Standard, cts.Token); }
        catch { }
        if (cts.IsCancellationRequested)
        {
            return;
        }
        contentArea.Opacity = 1d;
    }

    // ==================== Реактивный «Облегчённый режим» (lite) ====================
    // Единственная точка применения reduced-motion к ОБОЛОЧКЕ. Класс .lite обнуляет press/hover/
    // reveal-переходы и свёртку рейла (стили :is(Window).lite), а page-transition единого contentHost
    // переключается на null (мгновенный своп) под lite / на rise-fade иначе. Вызывается на старте и на
    // КАЖДОМ рантайм-переключении MotionState — переход lite происходит без перезапуска приложения.
    private void OnMotionStateChanged(object? sender, bool lite)
    {
        if (Dispatcher.UIThread.CheckAccess())
        {
            ApplyMotionMode(lite);
        }
        else
        {
            Dispatcher.UIThread.Post(() => ApplyMotionMode(lite));
        }
    }

    private void ApplyMotionMode(bool lite)
    {
        if (lite)
        {
            if (!Classes.Contains("lite"))
            {
                Classes.Add("lite");
            }
        }
        else
        {
            Classes.Remove("lite");
        }
        // Смену вкладок «оживляет»/глушит сам SwapContent по MotionState.IsLite (rise+fade §A.4 vs
        // мгновенный своп) — отдельный page-transition единому contentHost больше не нужен (keep-alive).
    }

    // Онбординг/суб-страницы — mode-agnostic оверлеи. Пусто (нет подписок) → только онбординг на всю
    // ширину. Есть подписки → виден единый bodyRoot (chrome раскладывает ApplyLayoutMode).
    private void ApplyShellVisibility()
    {
        // Не трогаем видимость в режиме превью суб-экрана (DEV screenshot hook).
        if (Environment.GetEnvironmentVariable("PREVIEW_VIEW") is not null)
        {
            return;
        }

        // Пока открыт ПРОЗРАЧНЫЙ подэкран, шелл под ним погашен (ApplySubPageShellGate) и трогать его
        // нельзя: этот метод дёргают наблюдатели VM (пришли серверы, сменился IsLoggedIn, поднялся
        // IsImportingAccount), и без гейта, например, приход серверов во время открытого «Входа» зажёг
        // бы «Главную» ПРЯМО ПОД полупрозрачной страницей входа. Отложенный кадр не теряется: на
        // закрытии последнего подэкрана ApplySubPageShellGate зовёт этот метод заново, и он посчитает
        // видимость по АКТУАЛЬНОМУ состоянию.
        if (_subStack.Count > 0)
        {
            return;
        }

        // 3-way gate (E3): SYNCING > EMPTY > CONTENT. Оверлей синхронизации перекрывает и пустой
        // онбординг, и половинчатую «Главную». Поднимает его ОДИН сигнал — _isSyncing
        // (IsImportingAccount), пост-логин импорт: между закрытием «Входа» и приходом серверов НЕ
        // мелькает пустой онбординг. Снимается он только ПОСЛЕ завершения импорта, к тому моменту
        // _isEmpty уже false (сервера пришли) → кадр уходит прямо в заполненный bodyRoot.
        //
        // A1: онбординг-гейт (с CTA входа) осмыслен ТОЛЬКО для вышедшего из аккаунта пользователя. Если
        // пользователь ВОШЁЛ, но подписок/серверов нет (пустой аккаунт), НЕ показываем ему снова экран
        // входа — ведём в оболочку «Главной» (там пустое состояние героя + вкладка «Аккаунт» с «Купить
        // подписку»), а не на онбординг-вход. Онбординг остаётся первым кадром только для logged-out.
        //  Ждём первого ответа о составе: до него «пусто» означает «ещё не читали базу». Раньше гейт
        //  верил этому «пусто» и на холодном старте показывал вошедшему владельцу экран входа, а через
        //  мгновение перекидывал на Главную — владелец: «было стартовое окно, где входы, а потом уже
        //  перекинуло». Держим кадр пустым: фон окна уже нарисован, мелькать нечему.
        if (!_profilesResolved && !_isSyncing)
        {
            return;
        }

        //  Восстановление сессии при запуске (IsStartupLoading) оверлей НЕ поднимает: он рисует шаги
        //  входа, а при запуске никто не входит — владелец видел «Открываем Telegram» на перезапуске
        //  уже вошедшим. Роль того признака — не дать мелькнуть гейту входа — выполняет
        //  _profilesResolved выше: до первого ответа о составе кадр держится пустым.
        Control target = _isSyncing
            ? accountSyncView
            : (_isEmpty && !_isLoggedIn) ? onboardingView : bodyRoot;
        CrossfadeShellTo(target);
    }

    // C4/E3: тихий кроссфейд (200мс Ease.Standard, opacity-only) между тремя оверлеями оболочки
    // (accountSyncView / onboardingView / bodyRoot). Первый показ и .lite — мгновенно (без анимации
    // на старте, чтобы окно не «проявлялось» при запуске).
    private void CrossfadeShellTo(Control target)
    {
        var previous = _currentShellView;
        if (previous == target)
        {
            target.IsVisible = true;
            target.Opacity = 1;
            return;
        }
        _currentShellView = target;

        if (MotionState.IsLite || previous is null)
        {
            accountSyncView.IsVisible = target == accountSyncView;
            onboardingView.IsVisible = target == onboardingView;
            bodyRoot.IsVisible = target == bodyRoot;
            target.Opacity = 1;
            return;
        }

        // Мгновенно прячем третий оверлей (ни target, ни previous) — страхует от прерванного кроссфейда,
        // чтобы никогда не остались видны сразу три поверхности.
        foreach (var v in new Control[] { accountSyncView, onboardingView, bodyRoot })
        {
            if (v != target && v != previous)
            {
                v.IsVisible = false;
                v.Opacity = 1;
            }
        }

        _shellAnim?.Cancel();
        var cts = new CancellationTokenSource();
        _shellAnim = cts;

        target.Opacity = 0;
        target.IsVisible = true;
        FadeShellIn(target, cts.Token);
        FadeShellOutThenHide(previous, cts.Token);
    }

    private async void FadeShellIn(Control c, CancellationToken ct)
    {
        try { await RunFade(c, 0d, 1d, TimeSpan.FromMilliseconds(200), Motion.Ease.Standard, ct); }
        catch { }
        if (!ct.IsCancellationRequested)
        {
            c.Opacity = 1;
        }
    }

    private async void FadeShellOutThenHide(Control c, CancellationToken ct)
    {
        try { await RunFade(c, c.Opacity, 0d, TimeSpan.FromMilliseconds(200), Motion.Ease.Standard, ct); }
        catch { }
        if (ct.IsCancellationRequested)
        {
            return;
        }
        // Прячем ушедший оверлей ТОЛЬКО если он не стал снова целевым (быстрый обратный своп).
        if (c != _currentShellView)
        {
            c.IsVisible = false;
            c.Opacity = 1;
        }
    }

    // Свёртка/разворот левого нав-рейла. Всё движение (navItems Width/Opacity, поворот шеврона)
    // живёт в стилях по классу .railCollapsed на bodyRoot; здесь только тумблим класс и правим
    // подсказку. Столбец рейла Auto → контент сам занимает освободившееся место, ничего не клипая.
    // Кнопка и индикатор остаются в слим-полосе, так что развернуть можно всегда (не «застрять»).
    private bool _railCollapsed;

    private void ToggleRail()
    {
        _railCollapsed = !_railCollapsed;
        if (_railCollapsed)
        {
            bodyRoot.Classes.Add("railCollapsed");
        }
        else
        {
            bodyRoot.Classes.Remove("railCollapsed");
        }
        ApplyRailToggleTip();
    }

    // Подсказка кнопки свёртки рейла ставится императивно (ToggleRail перебивает {loc:T}-биндинг из
    // разметки), поэтому её нужно переустанавливать и при live-смене языка — см. OnLanguageChanged.
    private void ApplyRailToggleTip()
        => ToolTip.SetTip(btnRailToggle, _railCollapsed ? L.T("Nav_ExpandPanel") : L.T("Nav_CollapsePanel"));

    private void OnLanguageChanged(object? sender, EventArgs e) => ApplyRailToggleTip();

    // Bug8: интеракции буфера обмена и скана экрана. Раньше они регистрировались под WhenActivated и
    // снимались при деактивации окна. Угловой «+» открывает MenuFlyout, который может деактивировать
    // окно; к моменту вызова ReadTextFromClipboardInteraction.Handle обработчика уже не было → бросок
    // UnhandledInteractionException в незамеченную fire-and-forget задачу (_ = vm.AddViaClipboard()) →
    // тихая неудача добавления. Регистрируем на ВРЕМЯ ЖИЗНИ окна (_windowInteractions, освобождается в
    // OnClosed) — обработчик доступен из любого места независимо от активации.
    private void RegisterWindowInteractions(MainWindowViewModel vm)
    {
        vm.ReadTextFromClipboardInteraction.RegisterHandler(async interaction =>
        {
            var result = await AvaUtils.GetClipboardData(this);
            interaction.SetOutput(result);
        }).DisposeWith(_windowInteractions);

        vm.ScanScreenInteraction.RegisterHandler(async interaction =>
        {
            ShowHideWindow(false);
            await Task.Delay(200);
            var result = QRCodeAvaloniaUtils.CaptureScreen();
            ShowHideWindow(true);
            interaction.SetOutput(result);
        }).DisposeWith(_windowInteractions);
    }

    // Создаёт HomeViewModel поверх реального движка (ProfilesViewModel + StatusBarViewModel из
    // MainWindowViewModel) и отдаёт его «Главной». Индикатор рейла следует за IsConnected.
    private void SetupHome(MainWindowViewModel vm)
    {
        _homeViewModel = new HomeViewModel(vm);
        // ОДИН HomeViewModel питает ОБЕ раскладки (широкую и компактную «Главную»), поэтому
        // connect-состояние, выбранный сервер, скорости и таймер одинаковы при любой ширине.
        // НО живой VM держит ТОЛЬКО активная по текущей раскладке «Главная» (BindActiveHome):
        // неактивная раскладка отвязывается, чтобы её (невиртуализованные) строки серверов
        // освобождались из памяти и она никогда не перехватывала ввод. Онбординг — лёгкий (без
        // списка серверов), поэтому его DataContext держим постоянно.
        onboardingView.DataContext = _homeViewModel;
        BindActiveHome();

        //  Передача кадра с экрана прогрузки на Главную (motion.md «Сборка главной после потока»).
        //  Экран прогрузки поднимает это событие СИНХРОННО, в том же обороте UI-потока, в котором
        //  снимает себя, — чтобы между «подписка добавлена» и живой Главной не было пустого кадра.
        //  Раньше событие никто не слушал: слой просто растворялся, и Главная проявлялась штатным
        //  кроссфейдом шелла — без обещанного пакетом стаггера. Теперь снимаем слой со стека и
        //  проигрываем тот же вход регионов, что и при обычной смене вкладки.
        //  ВАЖНО: на FlowRequested не подписываемся намеренно — начальный экран поднимает слой САМ
        //  (RaiseFlowOverlay), и вторая подписка открыла бы его дважды.
        accountSyncView.ShellHandoffRequested += (_, _) =>
        {
            if (_subStack.LastOrDefault() is AccountSyncView)
            {
                PopSubPage();
            }
            ShowTab(AppTab.Home);
            PlayTabEntrance(IsNarrow ? _compactHome : _homeView);
        };

        // Индикатор рейла: серый в покое, синий при подключении И в процессе подключения (P1-3).
        // Цвет ведёт класс .on (C5): BrushTransition OnSurfaceVariant↔Accent из тема-токенов.
        _homeViewModel.WhenAnyValue(x => x.IsConnected, x => x.IsConnecting, (connected, connecting) => connected || connecting)
            .ObserveOn(RxSchedulers.MainThreadScheduler)
            .Subscribe(active =>
            {
                if (active)
                {
                    if (!railStatusDot.Classes.Contains("on"))
                    {
                        railStatusDot.Classes.Add("on");
                    }
                }
                else
                {
                    railStatusDot.Classes.Remove("on");
                }
            });

        // E3: пока идёт пост-логин импорт (AccountViewModel.IsImportingAccount) — показываем оверлей
        // синхронизации, а НЕ пустой онбординг. Флаг взводится в тот же UI-тик, что и IsLoggedIn (до
        // первого await), поэтому оверлей уже стоит в момент закрытия LoginView — пустой кадр не мелькает.
        _accountVm.WhenAnyValue(x => x.IsImportingAccount)
            .ObserveOn(RxSchedulers.MainThreadScheduler)
            .Subscribe(syncing =>
            {
                _isSyncing = syncing;
                ApplyShellVisibility();
            });

        // Bug4: холодный старт с уже сохранённой сессией. IsStartupLoading взводится СИНХРОННО в ctor
        // AccountViewModel (до присвоения IsLoggedIn, только при наличии persisted-сессии) и снимается в
        // finally StartupLoad — после того как импорт аккаунта + подписки + refresh «Главной» завершились.
        // Пока он true — держим оверлей загрузки вместо logged-out онбординга, чтобы у вернувшегося
        // пользователя не мелькал гейт входа. Отличается от IsImportingAccount (пост-логин импорт).
        _accountVm.WhenAnyValue(x => x.IsStartupLoading)
            .ObserveOn(RxSchedulers.MainThreadScheduler)
            .Subscribe(_ => ApplyShellVisibility());

        // A1: вошёл/вышел из аккаунта → пере-оцениваем гейт (logged-in + пусто ведёт на Главную, а не
        // на онбординг-вход). Подписка стоит РАНЬШЕ состава: планировщик на UI-потоке отдаёт текущее
        // значение сразу при подписке, и раньше гейт впервые считался с _isLoggedIn = false даже у
        // вошедшего владельца — вставал экран входа, а через мгновение уходил растворением.
        _accountVm.WhenAnyValue(x => x.IsLoggedIn)
            .ObserveOn(RxSchedulers.MainThreadScheduler)
            .Subscribe(loggedIn =>
            {
                _isLoggedIn = loggedIn;
                ApplyShellVisibility();
            });

        // Пустой старт (нет подписок): показываем ТОЛЬКО онбординг на всю ширину под chrome — оба
        // дерева скрыты. После добавления подписки (IsEmpty=false) — дерево по текущей раскладке.
        //  Состав считается известным только по IsResolved (база прочитана), а не по первой эмиссии
        //  IsEmpty: та приходит сразу при подписке со значением по умолчанию «пусто», и гейт по ней
        //  показывал онбординг или пустую «Главную», пока база не отдавала список.
        _homeViewModel.WhenAnyValue(x => x.IsEmpty, x => x.IsResolved)
            .ObserveOn(RxSchedulers.MainThreadScheduler)
            .Subscribe(state =>
            {
                _isEmpty = state.Item1;
                if (state.Item2)
                {
                    OnProfilesResolved();
                }
                ApplyShellVisibility();
            });

        //  Страховка: если база по какой-то причине не ответила, окно не остаётся пустым навсегда.
        //  Срок с запасом на медленный холодный старт (антивирус, спящий диск): решение по неполным
        //  данным показало бы экран входа человеку, у которого серверы есть, — ровно то, что чиним.
        DispatcherTimer.RunOnce(() =>
        {
            if (!_profilesResolved)
            {
                OnProfilesResolved();
                ApplyShellVisibility();
            }
        }, TimeSpan.FromSeconds(8));
    }

    /// <summary>Состав серверов известен: гейт может выбирать экран, а соседние вкладки — садиться в дерево.</summary>
    private void OnProfilesResolved()
    {
        _profilesResolved = true;
        MaybeAttachRemainingTabs();
    }

    //  Соседние keep-alive вкладки садятся в дерево ПОСЛЕ того, как окно показано И список серверов
    //  на месте. Раньше они садились сразу после первого кадра и их раскладка шла вперёд чтения базы:
    //  на слабой машине «Главная» со списком появлялась на секунду позже, чем могла.
    private bool _windowLoaded;
    private bool _remainingTabsScheduled;

    private void MaybeAttachRemainingTabs()
    {
        if (!_windowLoaded || !_profilesResolved || _remainingTabsScheduled)
        {
            return;
        }
        _remainingTabsScheduled = true;
        Dispatcher.UIThread.Post(AttachRemainingTabs, DispatcherPriority.Background);
    }

    /// <summary>
    /// Browser→app SSO callback (departamentvpn://auth?code=…): brings the window forward so the sign-in
    /// completion is visible, ensures the login sub-page is up (so the «завершаем вход через сайт…» step +
    /// success beat render), and redeems the one-time handoff code on the shared <see cref="AccountViewModel"/>
    /// — the SAME terminal path as an email/Telegram login. A duplicate/stale callback after we're already
    /// signed in is ignored.
    /// </summary>
    public void HandleAuthCallback(string code)
    {
        ShowHideWindow(true);
        Activate();
        if (_accountVm.IsLoggedIn)
        {
            return;
        }
        if (_subStack.LastOrDefault() is not LoginView)
        {
            OpenLogin();
        }
        _ = _accountVm.CompleteAppHandoff(code);
    }

    #region Sub-page host (Buy / Login / Devices / History)

    // ==================== Гейт шелла под прозрачными подэкранами ====================
    // README «Хром окна»: «Подэкраны и оверлей прогрузки ПРОЗРАЧНЫЕ — фон окна непрерывный. Экран под
    // ними НЕ рендерится, иначе просвечивает содержимое».
    //
    // subPageHost прозрачен (Background=Transparent в разметке) и сам шелл собой не перекрывает — гасим
    // шелл явно. Пока стек подэкранов не пуст, все три поверхности шелла скрыты, и подэкран лежит на
    // непрерывном фоне окна (windowShell). Как только стек пустеет, видимость возвращает штатный
    // 3-way-гейт ApplyShellVisibility (SYNCING > EMPTY > CONTENT) — состояние не дублируется здесь и не
    // может разойтись с ним.
    //
    // МОМЕНТ переключения выбран так, чтобы под полупрозрачным слоем НИКОГДА не было видно живого шелла:
    //   • push — гасим СРАЗУ (до входной анимации): страница въезжает на пустой фон окна;
    //   • pop  — возвращаем СРАЗУ (до выходной анимации): уходящая страница растворяется, открывая
    //     уже стоящий на месте шелл, без провала в пустоту и без вспышки в конце.
    private void ApplySubPageShellGate()
    {
        if (_subStack.Count > 0)
        {
            accountSyncView.IsVisible = false;
            onboardingView.IsVisible = false;
            //  Подэкран занимает ВСЁ окно: навигации на нём нет. Ненадолго было иначе — эталонные
            //  кадры B_*/E_* показывают рейл на подэкране, и я вернул его по ним, — но владелец
            //  отменил: «навигации быть не должно, когда входишь во вкладку». Выход только стрелкой
            //  «назад». Гасим тело целиком, иначе оно просвечивает сквозь прозрачный подэкран.
            bodyRoot.IsVisible = false;
            contentArea.IsVisible = true;
            return;
        }
        contentArea.IsVisible = true;
        ApplyShellVisibility();
    }

    // Кладёт суб-страницу поверх контента/онбординга и показывает хост с направленным slide+fade (C2).
    private void PushSubPage(Control view)
    {
        _subStack.Add(view);
        subPageHost.Content = view;
        subPageHost.IsVisible = true;
        ApplySubPageShellGate();   // гасим шелл ДО входной анимации — см. комментарий выше
        AnimateSubPageIn();
    }

    // Снимает верхнюю суб-страницу: анимирует уход текущей (translateX 0→16 + fade-out), затем
    // показывает предыдущую из стека (тем же slide+fade) либо прячет хост целиком (тогда снова виден
    // шелл-контент или онбординг, смотря по IsEmpty/IsSyncing).
    private void PopSubPage()
    {
        if (_subStack.Count > 0)
        {
            _subStack.RemoveAt(_subStack.Count - 1);
        }
        var next = _subStack.Count > 0 ? _subStack[^1] : null;
        ApplySubPageShellGate();   // шелл возвращается ДО выходной анимации — см. комментарий выше
        AnimateSubPageOut(next);
    }

    // ==================== Направленный slide+fade суб-страниц (C2) ====================
    // Push (вперёд, вглубь) = входящая translateX 16→0 + opacity 0→1, 300мс Ease.OutQuint.
    // Pop (назад)          = уходящая translateX 0→16 + opacity 1→0, 200мс Ease.Standard (выход
    // быстрее входа). ТОЛЬКО translate+opacity (никаких scale/rotate — страница не «улетает» из угла).
    // Под .lite — мгновенно (как contentHost). subPageHost ПРОЗРАЧЕН (README «Хром окна»: подэкраны
    // прозрачные, фон окна непрерывный), а шелл под стеком подэкранов гасит ApplySubPageShellGate —
    // поэтому сквозь уходящую страницу не видно живую «Главную», а на её месте проступает ровный фон
    // окна, и лишь по завершении гейт возвращает шелл/онбординг.
    private async void AnimateSubPageIn()
    {
        if (MotionState.IsLite)
        {
            subPageHost.Opacity = 1;
            subPageHost.RenderTransform = null;
            return;
        }
        _subPageAnim?.Cancel();
        var cts = new CancellationTokenSource();
        _subPageAnim = cts;

        subPageHost.Opacity = 0;
        try { await RunTranslateFade(subPageHost, TranslateTransform.XProperty, 16d, 0d, 0d, 1d, TimeSpan.FromMilliseconds(300), Motion.Ease.OutQuint, cts.Token); }
        catch { }
        if (cts.IsCancellationRequested)
        {
            return;
        }
        subPageHost.Opacity = 1;
        subPageHost.RenderTransform = null;
    }

    private async void AnimateSubPageOut(Control? next)
    {
        if (MotionState.IsLite)
        {
            ApplySubPageResult(next);
            return;
        }
        _subPageAnim?.Cancel();
        var cts = new CancellationTokenSource();
        _subPageAnim = cts;

        try { await RunTranslateFade(subPageHost, TranslateTransform.XProperty, 0d, 16d, 1d, 0d, TimeSpan.FromMilliseconds(200), Motion.Ease.Standard, cts.Token); }
        catch { }
        if (cts.IsCancellationRequested)
        {
            return;
        }
        ApplySubPageResult(next);
        if (next != null)
        {
            AnimateSubPageIn();   // предыдущая страница въезжает тем же slide+fade
        }
    }

    private void ApplySubPageResult(Control? next)
    {
        if (next != null)
        {
            subPageHost.Content = next;
        }
        else
        {
            subPageHost.Content = null;
            subPageHost.IsVisible = false;
            subPageHost.Opacity = 1;
            subPageHost.RenderTransform = null;
        }
    }

    /// <summary>DEV screenshot hook: одна ступень поручения «Способов входа» на дизайнерских данных.</summary>
    private static LoginView ErrandPreview(EmailErrand errand, bool waiting = false)
    {
        var vm = AccountViewModel.CreateDesign();
        vm.LoginEmail = "user@example.com";
        if (waiting)
        {
            vm.CurrentLoginState = new LoginState.AwaitingEmailVerification("user@example.com");
        }
        return new LoginView(errand) { DataContext = vm };
    }

    // «Купить подписку»: BuyView со своим BuyViewModel (грузит каталог в ctor).
    public void OpenBuy()
    {
        var view = new BuyView();
        view.BackRequested += (_, _) => PopSubPage();
        PushSubPage(view);
    }

    // «Устройства»: DevicesView со своим DevicesViewModel (uuid активной подписки резолвит сам
    // из вошедшего профиля; список грузится в ctor).
    public void OpenDevices()
    {
        var view = new DevicesView();
        view.BackRequested += (_, _) => PopSubPage();
        PushSubPage(view);
    }

    // «История платежей»: PaymentHistoryView; пустой CTA «Купить подписку» ведёт на Buy поверх.
    public void OpenHistory()
    {
        var view = new PaymentHistoryView();
        view.BackRequested += (_, _) => PopSubPage();
        view.BuyRequested += (_, _) => OpenBuy();
        PushSubPage(view);
    }

    // «Вход»: LoginView на ОБЩЕМ AccountViewModel — состояние входа видит и вкладка «Аккаунт».
    // BackRequested поднимается и по кнопке «назад», и по успешному входу (закрывает суб-страницу).
    public void OpenLogin()
    {
        var view = new LoginView { DataContext = _accountVm };
        view.BackRequested += (_, _) =>
        {
            // A10: настоящее закрытие «Входа» (кнопка «назад» / бэк) — отменяем опрос Telegram-логина,
            // иначе он тикает до ~3 мин на снятой странице и поздний Confirmed/Error ещё дёргает UI.
            // При УСПЕХЕ (IsLoggedIn) НЕ отменяем: опрос уже завершился, а дальше кадр ведёт оверлей
            // синхронизации (IsImportingAccount) — CancelLogin тут был бы лишним.
            if (!_accountVm.IsLoggedIn)
            {
                _accountVm.CancelLogin();
            }
            PopSubPage();
        };
        PushSubPage(view);
    }

    // «Способы входа» → «Почта»: та же суб-страница «Входа», открытая как ПОРУЧЕНИЕ существующего
    // аккаунта (привязать адрес · сменить адрес · задать первый пароль). Поручение объявляется VM ДО
    // создания вью — форму открывает чистой, — и передаётся в конструктор, а не свойством после него:
    // подписка на IsLoggedIn внутри вью срабатывает мгновенно и на вошедшем аккаунте закрыла бы
    // страницу сама. Возврат по «назад» останавливает и запрос, и опрос профиля за ним.
    public void OpenEmailErrand(EmailErrand errand)
    {
        _accountVm.BeginEmailErrand(errand);
        var view = new LoginView(errand) { DataContext = _accountVm };
        view.BackRequested += (_, _) =>
        {
            _accountVm.CancelEmailErrand();
            PopSubPage();
        };
        PushSubPage(view);
    }

    // Онбординг «Войти через Telegram»: НЕ показываем промежуточный выбор метода — сразу стартуем
    // Telegram-авторизацию на ОБЩЕМ AccountViewModel (открывает Telegram deep link) и открываем
    // LoginView, который переходит в состояние ожидания подтверждения по CurrentLoginState.
    public void OpenLoginTelegram()
    {
        OpenLogin();
        _accountVm.LoginTelegramCmd.Execute().Subscribe();
    }

    // Общий вход для суб-страниц НАСТРОЕК (DNS, Маршрутизация, Прокси по приложениям, Провайдеры,
    // Файлы ресурсов, Пинг, О приложении, Резервное копирование, Схемы URL). Раньше это были
    // отдельные OS-окна — теперь любая реализующая <see cref="ISubPage"/> вью кладётся на ТОТ ЖЕ стек
    // «назад», что и Buy/Login/Devices/History. Никаких отдельных окон в приложении быть не должно.
    public void OpenSubPage(Control view)
    {
        if (view is ISubPage sub)
        {
            sub.BackRequested += (_, _) => PopSubPage();
        }
        PushSubPage(view);
    }

    /// <summary>«Проверить обновление» из уведомления в строке окна. Экран, уже лежащий сверху стека,
    /// второй раз не кладётся: иначе «назад» пришлось бы нажимать дважды ради одного и того же экрана.</summary>
    public void OpenUpdatePage()
    {
        if (_subStack.Count > 0 && _subStack[^1] is CheckUpdateView)
        {
            return;
        }
        OpenSubPage(new CheckUpdateView());
    }

    #endregion Sub-page host

    private void TitleBar_PointerPressed(object? sender, PointerPressedEventArgs e)
    {
        //  Двойной щелчок по заголовку — на весь экран и обратно, как у любого окна Windows. Сама система
        //  этого не делает: заголовок нарисован нами, для Windows это клиентская область, и каждый щелчок
        //  лишь запускал перетаскивание. Двойной щелчок по левой навигации и нижней панели окно больше не
        //  трогает: он случайно разворачивал его на весь экран.
        if (e.ClickCount == 2 && e.GetCurrentPoint(this).Properties.IsLeftButtonPressed)
        {
            e.Handled = true;
            WindowState = WindowState == WindowState.Maximized ? WindowState.Normal : WindowState.Maximized;
            return;
        }
        if (e.GetCurrentPoint(this).Properties.IsLeftButtonPressed)
        {
            // Арм drag-to-edge только на реальный перенос заголовка. На Windows BeginMoveDrag
            // блокирует до конца перетаскивания (PositionChanged летят реентрантно и лишь помечают
            // _edgeExpandRequested), поэтому фактический разворот делаем ПОСЛЕ выхода из move-loop —
            // не воюем с нативным циклом перемещения за позицию окна.
            _titleDragging = true;
            _edgeExpandRequested = false;
            BeginMoveDrag(e);
            _titleDragging = false;
            if (_edgeExpandRequested)
            {
                _edgeExpandRequested = false;
                // Масштабируем цель на _uiScale: раскладка живёт в координатах контента (Bounds/_uiScale).
                ResizeClamped(WideToggleWidth * _uiScale, WideToggleHeight * _uiScale);
            }
        }
    }

    // ==================== Bug6: плавная анимация размера окна (тумблер компакт⇄широкая) ====================
    // Раньше тумблер жёстко «щёлкал» размер (ResizeClamped мгновенно), из-за чего разворот/сворачивание
    // ощущались рывком. Теперь размер плавно интерполируется (OutQuint ~200мс), удерживая ТЕКУЩИЙ центр
    // окна зафиксированным (тот же center-anchor + кламп в WorkingArea, что у ResizeClamped, но центр
    // берётся ОДИН раз в начале — без дрейфа). По ходу анимации Bounds-вотчер один раз пересекает
    // брейкпоинт и меняет раскладку (с невидимой пересборкой контента, Bug6 выше) — контент морфится
    // синхронно с ростом окна. Под .lite (или без экрана) — мгновенно, как прежде. Перезапуск отменяет
    // предыдущую анимацию; _edgeSnapSuspended гасит drag-to-edge на всё время прогона.
    private async void AnimateWindowSize(double targetWidth, double targetHeight)
    {
        if (WindowState != WindowState.Normal)
        {
            WindowState = WindowState.Normal;
        }

        var screen = Screens.ScreenFromWindow(this) ?? Screens.Primary;
        if (MotionState.IsLite || screen is null)
        {
            ResizeClamped(targetWidth, targetHeight);
            return;
        }

        _resizeAnim?.Cancel();
        var cts = new CancellationTokenSource();
        _resizeAnim = cts;

        var scaling = screen.Scaling > 0 ? screen.Scaling : 1.0;
        // Центр фиксируем ОДНОКРАТНО на старте (физ. пиксели) → окно растёт/сжимается «на месте».
        var centerX = Position.X + (Width * scaling / 2);
        var centerY = Position.Y + (Height * scaling / 2);
        var startWidth = Width;
        var startHeight = Height;

        _edgeSnapSuspended = true;
        try
        {
            var startTicks = Environment.TickCount64;
            const double durationMs = 200d;
            while (true)
            {
                var t = Math.Min(1d, (Environment.TickCount64 - startTicks) / durationMs);
                var eased = Motion.Ease.OutQuint.Ease(t);
                var w = startWidth + ((targetWidth - startWidth) * eased);
                var h = startHeight + ((targetHeight - startHeight) * eased);
                ApplySizeCentered(w, h, centerX, centerY, screen, scaling);
                if (t >= 1d)
                {
                    break;
                }
                await Task.Delay(16, cts.Token);
            }
        }
        catch (OperationCanceledException) { }
        catch { }
        finally
        {
            // Снимаем подавление edge-snap только если нас не сменила новая анимация (та уже взвела его).
            if (_resizeAnim == cts)
            {
                _edgeSnapSuspended = false;
            }
        }
    }

    // ==================== Drag-to-edge: разворот маленького окна у края экрана ====================
    // Тащим НЕширокое окно так, что его верх/левый/правый край касается края рабочей области →
    // разворачиваем в широкую. Только из компактной/узкой и только при реальном drag заголовка (не
    // дёргает при программных клампах). После разворота раскладка = Wide — повторно не срабатывает.
    private void OnPositionChanged(object? sender, PixelPointEventArgs e)
    {
        // Персист позиции: запоминаем КАЖДУЮ живую позицию Normal-состояния (перенос пользователем и
        // программные клампы дают одинаково честную конечную точку), но только после первичного
        // восстановления — стартовые центрирования base.OnLoaded не должны перетирать сохранённое.
        if (_positionSeeded && WindowState == WindowState.Normal)
        {
            _lastNormalPosition = e.Point;
        }

        if (_edgeSnapSuspended || !_titleDragging || _layout == LayoutMode.Wide || WindowState != WindowState.Normal)
        {
            return;
        }

        var screen = Screens.ScreenFromWindow(this) ?? Screens.Primary;
        if (screen is null)
        {
            return;
        }
        var scaling = screen.Scaling > 0 ? screen.Scaling : 1.0;
        var wa = screen.WorkingArea;
        var p = Position;
        var physW = (int)(Width * scaling);

        var hitTop = p.Y <= wa.Y + EdgeSnapThreshold;
        var hitLeft = p.X <= wa.X + EdgeSnapThreshold;
        var hitRight = p.X + physW >= wa.X + wa.Width - EdgeSnapThreshold;
        if (hitTop || hitLeft || hitRight)
        {
            // Помечаем разворот; сам ResizeClamped выполнит TitleBar_PointerPressed после move-loop.
            _edgeExpandRequested = true;
        }
    }

    // Ставит размер и КЛАМПИТ его + позицию в WorkingArea текущего экрана: окно (и кастомный
    // заголовок) всегда целиком на экране — верх никогда не уходит за границу (y ≥ wa.Y).
    // Растёт/сжимается НА МЕСТЕ: держим ТЕКУЩИЙ центр окна фиксированным, а не «телепортируем» рамку
    // в центр экрана (владелец: при разворачивании окно «улетало в угол, потом расширялось»). Позицию
    // ставим ДО размера, обе в одном синхронном проходе (до следующего кадра) → без промежуточной
    // отрисовки крупной рамки в старом углу.
    private void ResizeClamped(double width, double height)
    {
        _edgeSnapSuspended = true;
        try
        {
            var screen = Screens.ScreenFromWindow(this) ?? Screens.Primary;
            if (screen is null)
            {
                Width = width;
                Height = height;
                return;
            }
            var scaling = screen.Scaling > 0 ? screen.Scaling : 1.0;

            // Якорь = текущий геометрический центр окна (физ. пиксели).
            var centerX = Position.X + (Width * scaling / 2);
            var centerY = Position.Y + (Height * scaling / 2);

            ApplySizeCentered(width, height, centerX, centerY, screen, scaling);
        }
        catch { }
        finally
        {
            _edgeSnapSuspended = false;
        }
    }

    // Ставит размер и КЛАМПИТ его + позицию в WorkingArea вокруг ЗАДАННОГО центра (физ. пиксели): окно
    // всегда целиком на экране (верх никогда не за границей). Центр передаётся явно, поэтому годится и
    // для одиночного ResizeClamped (центр = текущий), и для покадровой анимации AnimateWindowSize
    // (центр фиксирован на старте → без дрейфа). Позицию ставим ДО размера — без промежуточного кадра.
    private void ApplySizeCentered(double width, double height, double centerX, double centerY, Screen screen, double scaling)
    {
        var wa = screen.WorkingArea;
        var maxW = wa.Width / scaling;
        var maxH = wa.Height / scaling;

        var w = Math.Clamp(width, Math.Min(MinWidth, maxW), maxW);
        var h = Math.Clamp(height, Math.Min(MinHeight, maxH), maxH);

        var physW = w * scaling;
        var physH = h * scaling;

        var x = centerX - (physW / 2);
        var y = centerY - (physH / 2);
        x = Math.Max(wa.X, Math.Min(x, wa.X + wa.Width - physW));
        y = Math.Max(wa.Y, Math.Min(y, wa.Y + wa.Height - physH));

        Position = new PixelPoint((int)x, (int)y);
        Width = w;
        Height = h;
    }

    // ==================== Ресайз безрамочного окна (грипы → BeginResizeDrag) ====================
    // 8 прозрачных грипов (XAML resizeGripHost) на PointerPressed зовут кроссплатформенный
    // Window.BeginResizeDrag(WindowEdge, e). Он сосуществует с остальным chrome без конфликтов:
    //   • title-drag: North-грип лежит ПОВЕРХ верхних 6px title-bar → хит-тест выбирает грип (верхний
    //     z-order), нажатие ниже 6px уходит в titleBar → перенос. Разные зоны, не спорят.
    //   • edge-snap (OnPositionChanged): гейтится _titleDragging, который ресайз НЕ взводит → не мигает.
    //   • auto-swap по порогам (420 / 1100): ресайз меняет Bounds → вотчер живьём переключает раскладку.
    //   • max-кнопка: грипы видимы только в Normal (WindowStateProperty-вотчер), BeginResize тоже гейтит.
    private void WireResizeGrips()
    {
        gripNW.PointerPressed += (_, e) => BeginResize(WindowEdge.NorthWest, e);
        gripN.PointerPressed += (_, e) => BeginResize(WindowEdge.North, e);
        gripNE.PointerPressed += (_, e) => BeginResize(WindowEdge.NorthEast, e);
        gripW.PointerPressed += (_, e) => BeginResize(WindowEdge.West, e);
        gripE.PointerPressed += (_, e) => BeginResize(WindowEdge.East, e);
        gripSW.PointerPressed += (_, e) => BeginResize(WindowEdge.SouthWest, e);
        gripS.PointerPressed += (_, e) => BeginResize(WindowEdge.South, e);
        gripSE.PointerPressed += (_, e) => BeginResize(WindowEdge.SouthEast, e);
    }

    // Стартует нативный ресайз за указанный край/угол. Только ЛКМ и только в Normal (в maximized/minimized
    // ресайз бессмыслен). Handled — чтобы нажатие не «протекло» дальше. BeginResizeDrag кроссплатформенный
    // (на Windows блокирует до конца тяги, на X11/Wayland отдаёт WM) и сам уважает MinWidth/MinHeight —
    // потолка по ширине нет, поэтому окно тянется вплоть до края экрана.
    private void BeginResize(WindowEdge edge, PointerPressedEventArgs e)
    {
        if (WindowState != WindowState.Normal || !e.GetCurrentPoint(this).Properties.IsLeftButtonPressed)
        {
            return;
        }
        e.Handled = true;
        BeginResizeDrag(edge, e);
    }

    // ==================== Скруглённые углы безрамочного окна (README «Хром окна»: 14) ====================
    // Углы рисует НЕ ОС, а корневой Border windowShell (XAML, ClipToBounds): само окно прозрачно
    // (Background=Transparent + TransparencyLevelHint=Transparent), поэтому за скруглением — дыра, сквозь
    // которую видно то, что лежит под окном. Отсюда два случая, когда скругление ОБЯЗАНО исчезнуть, иначе
    // в углах будут клинья:
    //
    //   1) РАЗВЁРНУТОЕ окно (Maximized/FullScreen). Развёрнутое окно занимает всю рабочую область и
    //      прижато к краям экрана; скруглённые углы оставили бы в них вырезы с рабочим столом (а на
    //      части WM — с чёрным). Нативные окна в максимуме тоже прямоугольные. Радиус → 0.
    //
    //   2) НЕТ НАСТОЯЩЕЙ ПРОЗРАЧНОСТИ. Windows (DWM) даёт её всегда, а вот X11 — ТОЛЬКО при живом
    //      композиторе: Avalonia.X11.TransparencyHelper поддерживает Transparent ⇔ IsCompositionEnabled,
    //      иначе уровень падает в None. Без композитора вырез за скруглением нечем показать — его зальёт
    //      TransparencyBackgroundFallback (в XAML задан Brush.Bg; дефолт Avalonia — БЕЛЫЙ, что дало бы
    //      светлые клинья в тёмной теме). Радиус → 0: честный прямоугольник лучше клиньев.
    //      Композитор на X11 можно запустить/убить на ходу, и Avalonia шлёт об этом уведомление
    //      (CompositionChanged/WindowManagerChanged → TransparencyLevelChanged), поэтому подписка ЖИВАЯ,
    //      а не разовая проверка на старте.
    //
    // Windows-специфика: на Win11 DWM самостоятельно скругляет углы верхнеуровневых окон. Поверх нашего
    // собственного скругления это дало бы двойной радиус (срез по чужой кривой). Просим DWM не трогать
    // углы (DWMWA_WINDOW_CORNER_PREFERENCE = DoNotRound через Win32Properties) — форму задаём только мы.
    // На X11/macOS атрибут игнорируется, вызов безвреден.
    private const double WindowCornerRadius = 14.0;

    private void WireWindowCorners()
    {
        try
        {
            Win32Properties.SetWindowCornerPreference(this, Win32Properties.WindowCornerPreference.DoNotRound);
        }
        catch { }

        this.GetObservable(WindowStateProperty).Subscribe(_ => ApplyWindowCorners());
        this.GetObservable(ActualTransparencyLevelProperty).Subscribe(_ => ApplyWindowCorners());
        ApplyWindowCorners();
    }

    private void ApplyWindowCorners()
    {
        var squared = WindowState is WindowState.Maximized or WindowState.FullScreen
                      || ActualTransparencyLevel == WindowTransparencyLevel.None;
        windowShell.CornerRadius = new CornerRadius(squared ? 0d : WindowCornerRadius);
    }

    #region In-app UI-масштаб (zoom)

    // Реакция на смену фактора извне (настройки/горячие клавиши). Единый путь применения: трансформ +
    // мин-размеры + пере-кламп окна + пере-оценка брейкпоинта. Персист делает ИНИЦИАТОР (SetUiScale/настройки),
    // здесь только применение, поэтому смена из настроек и из клавиш дают идентичный результат.
    private void OnUiScaleChanged(object? sender, double scale)
    {
        if (Dispatcher.UIThread.CheckAccess())
        {
            ApplyUiScale(scale);
        }
        else
        {
            Dispatcher.UIThread.Post(() => ApplyUiScale(scale));
        }
    }

    private void ApplyUiScale(double scale)
    {
        // Аргумент — ПОЛЬЗОВАТЕЛЬСКИЙ фактор (его уже принял UiScaleState); в окно идёт итоговый,
        // с подбором под монитор.
        _ = scale;
        _uiScale = UiScaleState.Effective;
        ApplyUiScaleToWindow();

        // Окно могло стать меньше нового мин-размера (zoom вырос) — переклампим текущий размер в экран
        // (вырастет до мин, останется по центру, не уедет за край). ResizeClamped уже уважает MinWidth/Height.
        if (WindowState == WindowState.Normal)
        {
            ResizeClamped(Width, Height);
        }

        // Пороги раскладки — в координатах контента (Bounds/_uiScale). На старте Bounds=0 → no-op.
        if (Bounds.Width > 0)
        {
            UpdateLayoutMode(Bounds.Width / _uiScale);
        }
    }

    // Применяет ТЕКУЩИЙ _uiScale к корневому ScaleTransform и к мин-размерам окна. Мин-размер контента
    // растёт с zoom (контенту нужно _base*scale физ. DIP, иначе клип); клампим под рабочую область, чтобы
    // MinWidth/Height НИКОГДА не превысили экран (иначе Avalonia распёрла бы окно за его пределы). НЕ
    // персистит и не трогает layout-режим сам по себе.
    private void ApplyUiScaleToWindow()
    {
        // Ставим/обновляем масштаб корня через сам LayoutTransformControl (у ScaleTransform внутри
        // property-элемента компилятор XAML не генерирует поле — обращаемся к хосту).
        if (uiScaleHost is not null)
        {
            uiScaleHost.LayoutTransform = new ScaleTransform(_uiScale, _uiScale);
        }

        // По умолчанию (в т.ч. если экран ещё не доступен — вызов из ctor до реализации окна) — просто
        // base*scale без клампа; фактический размер окна на старте всё равно клампит WindowBase.OnLoaded, а
        // MainWindow.OnLoaded ниже повторно зовёт этот метод уже с доступным экраном.
        var minW = _baseMinWidth * _uiScale;
        var minH = _baseMinHeight * _uiScale;
        try
        {
            var screen = Screens.ScreenFromWindow(this) ?? Screens.Primary;
            if (screen is not null)
            {
                var scaling = screen.Scaling > 0 ? screen.Scaling : 1.0;
                // Клампим мин под рабочую область: MinWidth/Height НИКОГДА не должны превысить экран, иначе
                // Avalonia распёрла бы окно за его пределы (высокий zoom на маленьком дисплее).
                minW = Math.Min(minW, screen.WorkingArea.Width / scaling);
                minH = Math.Min(minH, screen.WorkingArea.Height / scaling);
            }
        }
        catch { }
        MinWidth = minW;
        MinHeight = minH;
    }

    // ==================== Подбор масштаба под монитор ====================
    // Читаем экран, на котором ОТКРЫВАЕТСЯ окно (или основной, пока окна ещё нет), и отдаём его пиксельный
    // размер + СИСТЕМНЫЙ масштаб в UiScaleState.ResolveAuto. Там системный масштаб делится, а не умножается
    // повторно, поэтому 2560×1440 даёт 1.85 при 100% ОС и 1.25 при 150% ОС (итог по пикселям тот же).
    // Возвращает true, если фактор изменился — вызывающий решает, что переприменять.
    private bool RefreshAutoScale()
    {
        try
        {
            var screen = Screens.ScreenFromWindow(this) ?? Screens.Primary;
            if (screen is null)
            {
                return false;
            }
            var bounds = screen.Bounds;
            if (bounds.Width <= 0 || bounds.Height <= 0)
            {
                return false;
            }
            return UiScaleState.SetAuto(UiScaleState.ResolveAuto(bounds.Width, bounds.Height, screen.Scaling));
        }
        catch
        {
            return false;
        }
    }

    // Логическая ширина, с которой окно откроется: сохранённый физический размер, переведённый в координаты
    // контента, либо компактный пресет. Нужна ДО первого layout, чтобы засеять раскладку правильным режимом.
    private double StartupLogicalWidth()
    {
        try
        {
            var saved = ConfigHandler.GetWindowSizeItem(_config, GetType().Name);
            if (saved is not null && saved.Width > 0 && _uiScale > 0)
            {
                return saved.Width / _uiScale;
            }
        }
        catch { }
        return CompactToggleWidth;
    }

    // Стартовый размер окна = КОМПАКТНЫЙ пресет 900×860 в логических единицах, домноженный на итоговый
    // фактор. Ставим его ДО WindowBase.OnLoaded: тот берёт сохранённый размер, а при его отсутствии —
    // текущие Width/Height, и уже сам центрирует и вписывает окно в рабочую область. Высоту дополнительно
    // ограничиваем долей рабочей области: компактный пресет почти квадратный и на 16:9 в неё не влезает,
    // а безрамочное окно впритык к краям читается как сломанный «развёрнутый» режим.
    private void ApplyStartupSize()
    {
        try
        {
            if (ConfigHandler.GetWindowSizeItem(_config, GetType().Name) is not null)
            {
                return;   // сохранённый размер важнее пресета — его восстановит WindowBase.OnLoaded
            }

            var width = CompactToggleWidth * _uiScale;
            var height = CompactToggleHeight * _uiScale;

            var screen = Screens.ScreenFromWindow(this) ?? Screens.Primary;
            if (screen is not null)
            {
                var scaling = screen.Scaling > 0 ? screen.Scaling : 1.0;
                width = Math.Min(width, screen.WorkingArea.Width / scaling * StartupWorkAreaFill);
                height = Math.Min(height, screen.WorkingArea.Height / scaling * StartupWorkAreaFill);
            }

            Width = width;
            Height = height;
        }
        catch { }
    }

    // ==================== Персист положения окна (см. поля _lastNormalPosition/_positionSeeded) ====================

    private static string WindowPositionPath => Utils.GetConfigPath("WindowPosition.txt");

    // Восстановление позиции ПОСЛЕ base.OnLoaded (который центрирует): первый вызов читает файл, повторные
    // (если Loaded придёт ещё раз) — последнюю живую позицию. Сохранённая точка применяется, только если
    // лежит на живом экране, и клампится в его рабочую область целиком — заголовок всегда достижим.
    private void RestoreWindowPosition()
    {
        var saved = _positionSeeded ? _lastNormalPosition : LoadWindowPosition();
        _positionSeeded = true;
        if (saved is not PixelPoint pos)
        {
            return;
        }
        try
        {
            var screen = Screens.ScreenFromPoint(pos) ?? Screens.All.FirstOrDefault(s => s.Bounds.Contains(pos));
            if (screen is null)
            {
                return;   // точка ни на одном экране (монитор отключили) — остаёмся по центру
            }
            var scaling = screen.Scaling > 0 ? screen.Scaling : 1.0;
            var wa = screen.WorkingArea;
            var physW = Width * scaling;
            var physH = Height * scaling;
            var x = Math.Max(wa.X, Math.Min(pos.X, wa.X + wa.Width - physW));
            var y = Math.Max(wa.Y, Math.Min(pos.Y, wa.Y + wa.Height - physH));
            Position = new PixelPoint((int)x, (int)y);
            _lastNormalPosition = Position;
        }
        catch { }
    }

    private static PixelPoint? LoadWindowPosition()
    {
        try
        {
            var path = WindowPositionPath;
            if (!File.Exists(path))
            {
                return null;
            }
            var parts = File.ReadAllText(path).Trim().Split(',');
            if (parts.Length == 2 && int.TryParse(parts[0], out var x) && int.TryParse(parts[1], out var y))
            {
                return new PixelPoint(x, y);
            }
        }
        catch { }
        return null;
    }

    // Пишется на тех же выходных путях, что и размер (StorageUI на выходе приложения + OnClosed).
    // Позиция уже хранится как «последняя Normal», поэтому состояние окна здесь проверять не нужно.
    private void SaveWindowPosition()
    {
        if (_lastNormalPosition is not PixelPoint p)
        {
            return;
        }
        try
        {
            File.WriteAllText(WindowPositionPath, $"{p.X},{p.Y}");
        }
        catch { }
    }

    // Горячие клавиши: сдвиг на шаг (Ctrl +/Ctrl −) и установка точного значения (Ctrl 0 = сброс).
    // Двигаем ПОЛЬЗОВАТЕЛЬСКИЙ фактор (то же, что строка настроек), а не итоговый: иначе первый же Ctrl+
    // на 4K-мониторе схлопнул бы подбор под монитор в пользовательский диапазон 0.8…2.0.
    private void NudgeUiScale(double delta) => SetUiScale(UiScaleState.Current + delta);

    private void SetUiScale(double scale)
    {
        var clamped = UiScaleState.Clamp(scale);
        if (Math.Abs(clamped - UiScaleState.Current) < 0.0001)
        {
            return;   // уже на границе — незачем писать конфиг/дёргать применение
        }
        PersistUiScale(clamped);
        UiScaleState.Set(clamped);   // → OnUiScaleChanged применит трансформ/мин/раскладку (+ строку настроек)
    }

    private void PersistUiScale(double scale)
    {
        _config.UiItem.UiScale = scale;
        _ = ConfigHandler.SaveConfig(_config);
    }

    #endregion In-app UI-масштаб (zoom)

    #endregion Nav & Chrome

    #region Theme transition (круговая заливка смены темы)

    // ==================== Плавная смена темы: круговая «заливка» новой темы ====================
    // App.ApplyTheme (обе кнопки настроек: смена базы Тёмная↔Светлая и монохромный оверлей) вместо
    // мгновенного свопа зовёт этот хук. Техника: снимок ТЕКУЩЕЙ темы (RenderTargetBitmap) кладём поверх
    // окна → applySwap перекрашивает живые контролы ПОД снимком (без вспышки, один синхронный тик) →
    // снимок «вытекает» расширяющимся круговым клипом из точки нажатия по тумблеру, открывая новую тему.
    // OutQuint ~520мс, одноразово, покадрово (16мс). В lite/reduced-motion — мгновенный своп без снимка.
    // Bitmap освобождается по завершении/отмене (FinishThemeTransition) — утечки нет; оверлей hit-test-
    // прозрачен и скрыт в покое, поэтому UI полностью интерактивен во время и после перехода.
    private CancellationTokenSource? _themeAnim;
    private RenderTargetBitmap? _themeSnapshot;
    private Point? _lastPointerInWindow;
    private static readonly TimeSpan ThemeRevealDuration = TimeSpan.FromMilliseconds(520);

    // Точку старта заливки берём относительно chromeRoot (а НЕ окна): именно chromeRoot снимается в bitmap
    // и его Bounds задают w/h заливки. Под UI-zoom окно и chromeRoot в РАЗНЫХ координатах (контент масштабирован
    // LayoutTransformControl); координаты chromeRoot совпадают с w/h при любом факторе. При _uiScale=1.0 —
    // тождественно прежнему GetPosition(this) (chromeRoot в начале координат окна).
    private void OnAnyPointerPressed(object? sender, PointerPressedEventArgs e)
        => _lastPointerInWindow = e.GetPosition(chromeRoot);

    // Хук из App.ApplyTheme. applySwap = мгновенная перекраска (RequestedThemeVariant + моно-оверлей).
    private void RunThemeTransition(Action applySwap)
    {
        if (!Dispatcher.UIThread.CheckAccess())
        {
            Dispatcher.UIThread.Post(() => RunThemeTransition(applySwap));
            return;
        }

        // Escape-hatch владельца: в lite/reduced-motion, пока окно скрыто/не разложено — мгновенно, без снимка.
        var w = chromeRoot.Bounds.Width;
        var h = chromeRoot.Bounds.Height;
        if (MotionState.IsLite || !IsVisible || w < 1 || h < 1)
        {
            applySwap();
            return;
        }

        // Снимаем любой незавершённый предыдущий переход (быстрый повторный тумблер) и освобождаем его bitmap.
        CancelThemeTransition();

        RenderTargetBitmap? snapshot = null;
        try
        {
            var scaling = RenderScaling > 0 ? RenderScaling : 1.0;
            var px = new PixelSize(
                Math.Max(1, (int)Math.Ceiling(w * scaling)),
                Math.Max(1, (int)Math.Ceiling(h * scaling)));
            snapshot = new RenderTargetBitmap(px, new Vector(96 * scaling, 96 * scaling));
            snapshot.Render(chromeRoot);
        }
        catch
        {
            // Редкий сбой рендера → безопасный откат в мгновенную смену (снимок не оставляем).
            snapshot?.Dispose();
            applySwap();
            return;
        }

        _themeSnapshot = snapshot;
        themeTransitionImage.Source = snapshot;
        // Подложка снимка = СТАРЫЙ фон окна (Brush.HomeGradient до свопа). Снимок chromeRoot прозрачен
        // везде, где виден фон окна: сам фон рисует windowBackdrop, а не chromeRoot. Без подложки сквозь
        // снимок до прихода круга просвечивала бы новая тема. Кисть берётся сейчас, до свопа, поэтому
        // остаётся старой, а границы у оверлея те же, что у chromeRoot и окна, — градиент совпадает.
        themeTransitionOverlay.Background = ResolveThemeBrush("Brush.HomeGradient");
        themeTransitionOverlay.Clip = null;
        themeTransitionOverlay.Opacity = 1d;
        themeTransitionOverlay.IsVisible = true;

        // Новая тема применяется СЕЙЧАС — под непрозрачным снимком. Живые контролы перекрашиваются скрыто,
        // без вспышки: кадр ещё не скомпонован, всё синхронно на UI-потоке (снимок сверху = полный, без клипа).
        applySwap();

        var cts = new CancellationTokenSource();
        _themeAnim = cts;
        AnimateThemeReveal(w, h, cts);
    }

    private async void AnimateThemeReveal(double w, double h, CancellationTokenSource cts)
    {
        var ct = cts.Token;
        var origin = ResolveThemeOrigin(w, h);
        var maxRadius = MaxCornerDistance(origin, w, h);
        var rect = new RectangleGeometry(new Rect(0, 0, w, h));

        var startTicks = Environment.TickCount64;
        var durationMs = ThemeRevealDuration.TotalMilliseconds;
        try
        {
            while (true)
            {
                var t = Math.Min(1d, (Environment.TickCount64 - startTicks) / durationMs);
                var radius = Motion.Ease.OutQuint.Ease(t) * maxRadius;
                // Клип = прямоугольник окна МИНУС растущий круг: снимок виден только СНАРУЖИ круга, внутри
                // проступает уже перекрашенная новая тема. Круг растёт из точки нажатия → «заливка» новой темы.
                var hole = new EllipseGeometry { Center = origin, RadiusX = radius, RadiusY = radius };
                themeTransitionOverlay.Clip = new CombinedGeometry(GeometryCombineMode.Exclude, rect, hole);
                if (t >= 1d)
                {
                    break;
                }
                await Task.Delay(16, ct);
            }
        }
        catch (OperationCanceledException) { return; }
        catch { }

        // Финализируем только если нас не сменил/не отменил новый переход.
        if (ct.IsCancellationRequested || _themeAnim != cts)
        {
            return;
        }
        FinishThemeTransition();
    }

    // Старт заливки = последнее нажатие в окне (тап по тумблеру темы), если оно в пределах окна; иначе центр.
    private Point ResolveThemeOrigin(double w, double h)
    {
        if (_lastPointerInWindow is { } p && p.X >= 0 && p.Y >= 0 && p.X <= w && p.Y <= h)
        {
            return p;
        }
        return new Point(w / 2, h / 2);
    }

    // Радиус, чтобы круг накрыл самый дальний угол окна от точки старта (полная заливка).
    private static double MaxCornerDistance(Point o, double w, double h)
    {
        var dx = Math.Max(o.X, w - o.X);
        var dy = Math.Max(o.Y, h - o.Y);
        return Math.Sqrt((dx * dx) + (dy * dy));
    }

    // Тема-кисть по ключу в ТЕКУЩЕМ варианте (совпадает с паттерном ConnectHeroView/SubscriptionMetaView).
    private IBrush? ResolveThemeBrush(string key)
        => this.TryFindResource(key, ActualThemeVariant, out var v) && v is IBrush b ? b : null;

    // Снятие оверлея + освобождение снимка (нет утечки RenderTargetBitmap). Идемпотентно.
    private void FinishThemeTransition()
    {
        _themeAnim = null;
        themeTransitionOverlay.IsVisible = false;
        themeTransitionOverlay.Clip = null;
        themeTransitionOverlay.Background = null;
        themeTransitionImage.Source = null;
        _themeSnapshot?.Dispose();
        _themeSnapshot = null;
    }

    private void CancelThemeTransition()
    {
        _themeAnim?.Cancel();
        FinishThemeTransition();
    }

    #endregion Theme transition

    #region Event

    private void OnProgramStarted(object state, bool timeout)
    {
        Dispatcher.UIThread.Post(() =>
                ShowHideWindow(true),
            DispatcherPriority.Default);
    }

    // ==================== Нижняя пилюля-тост ОТКЛЮЧЕНА; фидбэк уходит в панель сообщений (Bug8) ====================
    // Владелец: НИКАКИХ всплывающих нижних тостов (snackHost) — ни на подключении/отключении, ни на
    // добавлении/обновлении подписки. Пилюля снизу по-прежнему НЕ показывается (snackHost остаётся
    // скрытым). Но раньше это был ПОЛНЫЙ no-op: весь фидбэк добавления (пустой буфер, неверные данные,
    // дубликат, успех) шёл через NoticeManager.Enqueue → это событие и молча ПРОПАДАЛ → «добавляю
    // подписку — ничего не происходит, без объяснений». Теперь вместо тоста маршрутизируем текст в
    // ИНЛАЙН-панель сообщений (NoticeManager.SendMessage → SendMsgViewRequested → MsgViewModel-лог) —
    // не плавающий тост, а лог-поверхность (owner-aligned): исход добавления больше не теряется.
    // (SubscriptionImportLogHandler в MainWindowViewModel уже пишет прогресс скачивания в ту же панель.)
    private Task DelegateSnackMsg(string content)
    {
        NoticeManager.Instance.SendMessage(content);
        return Task.CompletedTask;
    }

    // ==================== Общие аниматоры оболочки (transform+opacity, §A) ====================
    // Все переходы MainWindow строятся из этих двух примитивов: чистый fade и translate+fade (две
    // параллельные анимации на одном визуале — opacity и translate идут разными аниматорами Avalonia,
    // ровно как в SwapContent-переходе вкладок). FillMode.Forward держит конечный кадр до сброса.
    private static Task RunFade(Visual target, double fromO, double toO, TimeSpan duration, Easing easing, CancellationToken ct)
    {
        var fade = new Animation
        {
            Duration = duration,
            Easing = easing,
            FillMode = FillMode.Forward,
            Children =
            {
                new KeyFrame { Cue = new Cue(0d), Setters = { new Setter(Visual.OpacityProperty, fromO) } },
                new KeyFrame { Cue = new Cue(1d), Setters = { new Setter(Visual.OpacityProperty, toO) } },
            },
        };
        return fade.RunAsync(target, ct);
    }

    private static Task RunTranslateFade(Visual target, AvaloniaProperty axis, double fromT, double toT, double fromO, double toO, TimeSpan duration, Easing easing, CancellationToken ct)
    {
        var fade = new Animation
        {
            Duration = duration,
            Easing = easing,
            FillMode = FillMode.Forward,
            Children =
            {
                new KeyFrame { Cue = new Cue(0d), Setters = { new Setter(Visual.OpacityProperty, fromO) } },
                new KeyFrame { Cue = new Cue(1d), Setters = { new Setter(Visual.OpacityProperty, toO) } },
            },
        };
        var slide = new Animation
        {
            Duration = duration,
            Easing = easing,
            FillMode = FillMode.Forward,
            Children =
            {
                new KeyFrame { Cue = new Cue(0d), Setters = { new Setter(axis, fromT) } },
                new KeyFrame { Cue = new Cue(1d), Setters = { new Setter(axis, toT) } },
            },
        };
        return Task.WhenAll(fade.RunAsync(target, ct), slide.RunAsync(target, ct));
    }

    private void OnHotkeyHandler(EGlobalHotkey e)
    {
        switch (e)
        {
            case EGlobalHotkey.ShowForm:
                Dispatcher.UIThread.Post(() => ShowHideWindow(null));
                break;

            case EGlobalHotkey.SystemProxyClear:
            case EGlobalHotkey.SystemProxySet:
            case EGlobalHotkey.SystemProxyUnchanged:
            case EGlobalHotkey.SystemProxyPac:
                AppEvents.SysProxyChangeRequested.Publish((ESysProxyType)((int)e - 1));
                break;
        }
    }

    protected override async void OnClosing(WindowClosingEventArgs e)
    {
        if (_blCloseByUser)
        {
            return;
        }

        Logging.SaveLog("OnClosing -> " + e.CloseReason.ToString());

        switch (e.CloseReason)
        {
            case WindowCloseReason.OwnerWindowClosing or WindowCloseReason.WindowClosing:
                e.Cancel = true;
                ShowHideWindow(false);
                break;

            case WindowCloseReason.ApplicationShutdown or WindowCloseReason.OSShutdown:
                //  Переопределённый обработчик ⇒ async void: исключение отсюда ловить НЕКОМУ.
                //  А AppExitAsync останавливает ядро, снимает системный прокси и пишет конфиг —
                //  трогает и процессы, и сеть, и файлы. Падение здесь означало бы, что завершение
                //  сеанса ОС выглядит как крах приложения. Ловим и всё равно закрываемся: выход
                //  уже начат и отменить его нельзя.
                try
                {
                    await AppManager.Instance.AppExitAsync(false);
                }
                catch (Exception ex)
                {
                    Logging.SaveLog("MainWindow.OnClosing", ex);
                }
                break;
        }

        base.OnClosing(e);
    }

    // Bug8: освобождаем интеракции, зарегистрированные на время жизни окна (буфер/скан). Окно реально
    // закрывается только при завершении приложения (обычное закрытие уходит в трей через OnClosing),
    // поэтому одноразового освобождения здесь достаточно.
    protected override void OnClosed(EventArgs e)
    {
        _windowInteractions.Dispose();
        // If the window closes mid theme-transition, cancel the reveal loop's token and dispose the
        // RenderTargetBitmap snapshot (else it's left to GC and the async-void loop keeps poking a
        // closing window). No-op when no transition is in flight.
        CancelThemeTransition();
        // Позиция окна — на закрытии (base.OnClosed рядом персистит размер в UiItem.WindowSizeItem).
        SaveWindowPosition();
        base.OnClosed(e);
    }

    private async void MainWindow_KeyDown(object? sender, KeyEventArgs e)
    {
        // Ctrl (или ⌘) присутствует — но НЕ обязательно один: Ctrl «+» на большинстве раскладок = Ctrl+Shift+=,
        // поэтому масштаб проверяем по НАЛИЧИЮ Control/Meta (допуская Shift), а V/S/F5 — по прежней точной маске.
        var zoomMod = e.KeyModifiers.HasFlag(KeyModifiers.Control) || e.KeyModifiers.HasFlag(KeyModifiers.Meta);
        if (zoomMod)
        {
            switch (e.Key)
            {
                // Увеличить: Ctrl «=» / Ctrl «+» (OemPlus) и «+» цифрового блока (Add).
                case Key.OemPlus:
                case Key.Add:
                    NudgeUiScale(UiScaleState.Step);
                    e.Handled = true;
                    return;

                // Уменьшить: Ctrl «−» (OemMinus) и «−» цифрового блока (Subtract).
                case Key.OemMinus:
                case Key.Subtract:
                    NudgeUiScale(-UiScaleState.Step);
                    e.Handled = true;
                    return;

                // Сброс к 100%: Ctrl 0 (верхний ряд / цифровой блок).
                case Key.D0:
                case Key.NumPad0:
                    SetUiScale(UiScaleState.Default);
                    e.Handled = true;
                    return;
            }
        }

        //  Обработчик клавиатуры ⇒ async void: исключение отсюда ловить НЕКОМУ, оно валит процесс.
        //  А за Ctrl V стоит буфер обмена (на X11 чтение может не ответить и бросить), за Ctrl S —
        //  снимок экрана и разбор QR. Ловим: горячая клавиша не сработала — это не повод падать.
        try
        {
            if (e.KeyModifiers is KeyModifiers.Control or KeyModifiers.Meta)
            {
                switch (e.Key)
                {
                    case Key.V:
                        await AddServerViaClipboardAsync();
                        break;

                    case Key.S:
                        await ScanScreenTaskAsync();
                        break;
                }
            }
            else
            {
                if (e.Key == Key.F5)
                {
                    UpdateReconnect.NoteUserAction();
                    ViewModel?.Reload();
                }
            }
        }
        catch (Exception ex)
        {
            Logging.SaveLog("MainWindow.KeyDown", ex);
        }
    }

    public async Task AddServerViaClipboardAsync()
    {
        var clipboardData = await AvaUtils.GetClipboardData(this);
        if (clipboardData.IsNotEmpty() && ViewModel != null)
        {
            await ViewModel.AddServerViaClipboardAsync(clipboardData);
        }
    }

    public async Task ScanScreenTaskAsync()
    {
        ShowHideWindow(false);

        await Task.Delay(200);

        var bytes = QRCodeAvaloniaUtils.CaptureScreen();
        if (bytes != null && ViewModel != null)
        {
            await ViewModel.ScanScreenResult(bytes);
        }

        ShowHideWindow(true);
    }

    private void Shutdown(bool obj)
    {
        if (!_blCloseByUser)
        {
            _blCloseByUser = obj;
        }
        StorageUI();
        if (Application.Current?.ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop)
        {
            HotkeyManager.Instance.Dispose();
            desktop.Shutdown();
        }
    }

    #endregion Event

    #region UI

    //  Геометрия, с которой окно ушло в трей. Windows при повторном показе иногда отдаёт окно
    //  развёрнутым — владелец: «скрываю в трей и открываю из трея, а он в полный экран».
    //  Поэтому не полагаемся на систему: запоминаем размер, положение и состояние сами и
    //  возвращаем их при показе. Запоминаем ТОЛЬКО обычное состояние — развёрнутое/свёрнутое
    //  восстанавливать нечего.
    private (PixelPoint Pos, double W, double H)? _trayGeometry;

    public void ShowHideWindow(bool? blShow)
    {
        // ОКНО САМО ЗНАЕТ, ВИДНО ЛИ ЕГО. Переключатель (двойной клик по трею, горячая клавиша)
        // раньше восстанавливал этот факт из флага AppManager.ShowInTaskbar, добавляя к нему через
        // XOR «свёрнуто ли окно» — потому что на Linux ветка «сворачивать, а не прятать» уходила из
        // метода, НЕ обновив флаг, и XOR эту дыру компенсировал.
        //
        // Компенсация ломалась ровно там, где обе половины говорят «не видно»: окно свернули, а
        // потом спрятали в трей — флаг false, состояние Minimized, XOR даёт «прятать», и
        // переключатель из трея прятал уже спрятанное окно. Двойной клик по значку не делал ничего,
        // и вернуть окно можно было только пунктом меню «Показать».
        //
        // Два вопроса к самому окну отвечают на это без флагов и на всех системах одинаково.
        var bl = blShow ?? (!IsVisible || WindowState == WindowState.Minimized);
        if (bl)
        {
            //  Геометрию возвращаем ДО показа, пока окно ещё скрыто. Раньше она ставилась после Show():
            //  окно сперва проявлялось в том размере, какой дала система (иногда развёрнутым), раскладка
            //  успевала перестроиться под него, а потом ещё раз под прежний размер. Две полные раскладки
            //  подряд на глазах — это и было «открывается с лагами» при возвращении из трея.
            //  Сначала размер, потом положение (иначе система переставит окно под новый размер).
            var g = _trayGeometry;
            if (g is { } before)
            {
                Width = before.W;
                Height = before.H;
                Position = before.Pos;
            }
            Show();
            if (WindowState != WindowState.Normal)
            {
                WindowState = WindowState.Normal;
            }
            //  Если система всё же вмешалась при показе — поправляем, но только то, что разошлось.
            if (g is { } after)
            {
                if (Width != after.W || Height != after.H)
                {
                    Width = after.W;
                    Height = after.H;
                }
                if (Position != after.Pos)
                {
                    Position = after.Pos;
                }
                _trayGeometry = null;
            }
            Activate();
            Focus();
        }
        else
        {
            //  Окно уходит в трей — значок должен уже быть там. На старте «сразу в трей» первого кадра
            //  нет, и значок появляется именно здесь.
            App.ShowTrayIcon();

            if (WindowState == WindowState.Normal)
            {
                _trayGeometry = (Position, Width, Height);
            }

            if (Utils.IsLinux() && _config.UiItem.Hide2TrayWhenClose == false)
            {
                // «Сворачивать, а не прятать» — но флаг всё равно публикуется (ниже). Он значит
                // «интерфейс поднят», а не «есть строка в панели задач»: по нему простаивающие циклы
                // решают, есть ли кому смотреть на их работу. Ранний выход отсюда оставлял флаг
                // прежним, и они продолжали крутиться над свёрнутым окном.
                WindowState = WindowState.Minimized;
            }
            else
            {
                foreach (var ownedWindow in OwnedWindows)
                {
                    ownedWindow.Close();
                }
                Hide();
            }
        }

        AppManager.Instance.ShowInTaskbar = bl;
    }

    //  Значок в трее — вместе с первым кадром окна, не раньше (App.ShowTrayIcon). Кадр ловим через
    //  RequestAnimationFrame, а сам значок ставим сразу после него, на холостом ходу: регистрация значка
    //  в системе не должна задерживать сам кадр.
    private bool _trayIconRequested;

    protected override void OnOpened(EventArgs e)
    {
        base.OnOpened(e);
        App.DevMark("window.opened");
        if (_trayIconRequested)
        {
            return;
        }
        _trayIconRequested = true;
        RequestAnimationFrame(_ =>
        {
            App.DevMark("window.frame");
            Dispatcher.UIThread.Post(() =>
            {
                App.ShowTrayIcon();
                // Автопроверка обновлений — тоже после первого кадра, и ещё через 20–30 с (UpdateNotice).
                UpdateNotice.StartAutoCheck();
            }, DispatcherPriority.Background);
        });
        // Страховка, как у значка в трее (App.SetupTray): окно, спрятанное в трей до первого кадра,
        // кадра не рисует. Повторный вызов ничего не делает.
        DispatcherTimer.RunOnce(UpdateNotice.StartAutoCheck, TimeSpan.FromSeconds(10));
    }

    protected override void OnLoaded(object? sender, RoutedEventArgs e)
    {
        // Порядок здесь важен и обратен прежнему:
        //   1. экран уже доступен (в ctor Screens мог быть ещё не готов) — подбираем масштаб под МОНИТОР,
        //      на котором окно реально открылось, и переприменяем трансформ + мин-размеры;
        //   2. ставим стартовый размер = компактный пресет × фактор (если сохранённого размера нет);
        //   3. только теперь base.OnLoaded — он берёт сохранённый размер ИЛИ текущие Width/Height, клампит
        //      их в рабочую область и центрирует окно.
        // Если звать base первым, он клампил бы ещё не отмасштабированные 900×860 и подбор под монитор
        // на размер окна не влиял бы вовсе.
        if (RefreshAutoScale())
        {
            _uiScale = UiScaleState.Effective;
        }
        ApplyUiScaleToWindow();
        ApplyStartupSize();
        base.OnLoaded(sender, e);

        // base.OnLoaded центрирует — возвращаем окно на сохранённое место (если оно на живом экране).
        RestoreWindowPosition();

        // Раскладка следует за живой шириной: после клампа окна в экран логическая ширина могла измениться.
        if (Bounds.Width > 0)
        {
            UpdateLayoutMode(Bounds.Width / _uiScale);
        }

        // Остальные keep-alive вкладки — В ДЕРЕВО ПОСЛЕ ПЕРВОГО КАДРА. Background — самый низкий из
        // рабочих приоритетов диспетчера: очередь сначала доводит до конца раскладку и отрисовку того,
        // что пользователь увидит, и только потом досаживает соседние вкладки. Тем самым первый проход
        // раскладки меряет ОДНУ вкладку вместо четырёх, а keep-alive (смена вкладки без first-layout)
        // остаётся в силе — к первому же тапу по навигации все четыре уже разложены.
        //  Когда именно — решает MaybeAttachRemainingTabs: после показа окна И после чтения списка.
        _windowLoaded = true;
        MaybeAttachRemainingTabs();

        // DEV probe hooks (скриншот-обвязка, как INITIAL_TAB/PREVIEW_VIEW): DP_SETPOS=x,y ставит позицию
        // окна, как будто его перенёс пользователь; DP_EXIT_AFTER_MS=N штатно завершает приложение через
        // N мс (полный путь выхода с персистом размера и позиции) — живая проверка сохранения между
        // запусками в среде без оконного менеджера.
        if (Environment.GetEnvironmentVariable("DP_SETPOS") is { } setPos)
        {
            var parts = setPos.Split(',');
            if (parts.Length == 2 && int.TryParse(parts[0], out var px) && int.TryParse(parts[1], out var py))
            {
                Position = new PixelPoint(px, py);
            }
        }
        if (int.TryParse(Environment.GetEnvironmentVariable("DP_EXIT_AFTER_MS"), out var exitMs) && exitMs > 0)
        {
            Dispatcher.UIThread.Post(async () =>
            {
                await Task.Delay(exitMs);
                await AppManager.Instance.AppExitAsync(false);
                AppManager.Instance.Shutdown(true);
            });
        }
        //  DP_TRAY_CYCLE=A:B — через A мс окно уходит в трей тем же путём, что и пункт меню, ещё через
        //  B мс возвращается. Живая проверка возврата из трея там, где трея нет (Xvfb).
        if (Environment.GetEnvironmentVariable("DP_TRAY_CYCLE") is { } trayCycle
            && trayCycle.Split(':') is [var hideAt, var hiddenFor]
            && int.TryParse(hideAt, out var hideAtMs) && int.TryParse(hiddenFor, out var hiddenForMs))
        {
            Dispatcher.UIThread.Post(async () =>
            {
                await Task.Delay(hideAtMs);
                ShowHideWindow(false);
                await Task.Delay(hiddenForMs);
                ShowHideWindow(true);
            });
        }
        //  DP_CONNECT_AFTER_MS=N — через N мс щит «Подключить» нажимается тем же путём, что и тапом
        //  (HomeViewModel.ConnectToggle): стенд Windows проверяет системный прокси и TUN без рук. Ждёт,
        //  пока прочитан список серверов, иначе подключать нечего. Вехи connect.tap и core.up/core.down
        //  уходят в DP_TIMELINE.
        if (int.TryParse(Environment.GetEnvironmentVariable("DP_CONNECT_AFTER_MS"), out var connectAfterMs) && connectAfterMs >= 0)
        {
            AppEvents.CoreRunningStateChanged
                .AsObservable()
                .Subscribe(up => App.DevMark(up ? "core.up" : "core.down"));
            Dispatcher.UIThread.Post(async () =>
            {
                await Task.Delay(connectAfterMs);
                for (var i = 0; i < 120 && _homeViewModel is not { HasServers: true }; i++)
                {
                    await Task.Delay(250);
                }
                if (_homeViewModel is { IsConnected: false, IsConnecting: false } home)
                {
                    App.DevMark("connect.tap");
                    home.ConnectToggle();
                }
            });
        }

        if (_config.UiItem.AutoHideStartup)
        {
            ShowHideWindow(false);
        }
    }

    private void StorageUI()
    {
        // Позиция — независимо от текущего состояния: хранится последняя НОРМАЛЬНАЯ (развёрнутое окно
        // на следующем запуске откроется обычным — на прежнем месте).
        SaveWindowPosition();

        // Размер сохраняем ТОЛЬКО в обычном состоянии: развёрнутое/свёрнутое окно
        // не должно перетекать в персист (иначе следующий запуск открывается «на весь экран»).
        if (WindowState != WindowState.Normal)
        {
            return;
        }
        ConfigHandler.SaveWindowSizeItem(_config, GetType().Name, Width, Height);
    }

    #endregion UI
}
