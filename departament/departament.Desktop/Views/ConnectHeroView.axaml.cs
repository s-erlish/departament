using System.Runtime.InteropServices;
using Avalonia.Animation;
using Avalonia.Animation.Easings;
using departament.Desktop.Common;

namespace departament.Desktop.Views;

/// <summary>
/// Connect-щит (герой) + стата-строка — правая панель «Главной». Перенос 1:1 из Android
/// (activity_main.xml hero + MainActivity applyRunningState/applyConnectedState/applyIdleState).
///
/// Разметка чисто визуальная; три состояния подключения (idle/connecting/connected) и
/// переключение «есть подписка ↔ нет подписки» задаёт публичный API ниже. Будущий HomeViewModel
/// вызывает эти методы/подписывается на события — трогать XAML не нужно.
///
/// Хореография движения (§1.4 DESIGN_PLAN):
///   • press диска — scale 0.975 (DiscPressScale, motion.md «Кнопка подключения»): quart-in 90мс /
///                    quint-out 160мс, императивно через ScaleTransform;
///   • connecting — дуга крутится + glow «дышит» (850мс), tint щита серый→синий;
///   • connected  — crossfade контур→залив + tint→СИНИЙ (220мс), glow-reveal (300мс) и ОДИН сонар
///                  (1.0→1.6 + fade, 600мс);
///   • disconnect — реверс на ~75% темпа (state 165мс / reveal 225мс);
///   • cold-start — «сборка» героя (scale 0.9→1 + fade, 400мс) ОДИН раз за процесс.
/// Reduced-motion (Win32 SPI_GETCLIENTAREAANIMATION) → каждая ветка прыгает в конечный вид.
/// Кривые/длительности — токены Ease.*/Dur.* из GlobalResources.axaml.
/// </summary>
public partial class ConnectHeroView : UserControl
{
    /// <summary>Визуальное состояние connect-щита.</summary>
    public enum ConnectVisualState
    {
        Idle,
        Connecting,
        Connected,

        //  Неудачное подключение (§A4): щит тонируется Brush.Red + подпись «Не удалось подключиться»
        //  + подсказка-ретрай. Отличим от штатного отключения (то падает в Idle) — иначе провал
        //  «молча» выглядел как обычный disconnect. Само-сбрасывается в Idle при следующей попытке
        //  подключения (HomeViewModel гонит Connecting → ветка Error затирается).
        Error,
    }

    //  Fallbacks (Incy dark defaults) — used ONLY when a theme-resource lookup fails. The LIVE
    //  values below come from theme tokens so the shield + status text stay readable on the light
    //  theme AND under the «Чёрно-белая» (mono) overlay, not just on dark. Hardcoding these was the
    //  bug: on light/mono the disc collapsed to a light/grey surface while the grey shield + near-
    //  white status text washed out (black-on-black / white-on-white).
    private static readonly IBrush ShieldGrayFallback = new SolidColorBrush(Color.Parse("#9BA1AD"));
    private static readonly IBrush AccentFallback = new SolidColorBrush(Color.Parse("#4C8DFF"));
    private static readonly IBrush OnSurfaceFallback = new SolidColorBrush(Color.Parse("#F2F4F8"));
    private static readonly IBrush ErrorFallback = new SolidColorBrush(Color.Parse("#F04452"));

    //  Resolve a theme brush for the CURRENT theme variant (so it picks up the light theme AND the
    //  merged mono overlay), never throwing out of the connect path — any failure silently returns
    //  the cached Incy fallback so the hero can never end up half-built / blank.
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

    //  Connected shield/status accent → mono maps Brush.Accent to grey/white (contrast kept).
    private IBrush AccentBrush => ResolveBrush("Brush.Accent", AccentFallback);

    //  Idle shield glyph → Brush.ShieldOff (tokens.md «Щит отключён»: #3A4A66 тёмная / #AEB8C8
    //  светлая / #4A4E57 чёрно-белая) — свой токен именно под отключённый щит, всегда контрастен
    //  диску Brush.ConnectDisc во всех трёх темах.
    private IBrush ShieldIdleBrush => ResolveBrush("Brush.ShieldOff", ShieldGrayFallback);

    //  Приглушённый контур/текст пилюли статуса, пока не подключено.
    private IBrush MutedBrush => ResolveBrush("Brush.OnSurfaceVariant", ShieldGrayFallback);

    //  Контур пилюли статуса в покое (tokens.md «Контур кнопки»).
    private IBrush OutlineBrush => ResolveBrush("Brush.ButtonOutline", ShieldGrayFallback);

    //  Кольца/комета НЕ следуют за акцентом (владелец: плёнка из #1F6FF5 мутит на тёмной) — они
    //  живут на своих токенах Brush.Ring.*, которые mono-оверлей уводит в белый. Отсюда же комета
    //  берёт свой RGB, поэтому «синий в тёмной и светлой, белый в чёрно-белой» получается сам.
    private IBrush RingDimBrush => ResolveBrush("Brush.Ring.Outer", AccentFallback);

    private IBrush RingLiveBrush => ResolveBrush("Brush.Ring.Inner", AccentFallback);

    //  Idle status text → Brush.OnSurface (theme ink), readable on light/mono, not fixed near-white.
    private IBrush OnSurfaceBrush => ResolveBrush("Brush.OnSurface", OnSurfaceFallback);

    //  Error shield/status tint → Brush.Red (dark #F04452 / light #C42B32; mono keeps a readable
    //  warning red). Destructive/warning is the ONLY non-blue accent allowed (app design rules).
    private IBrush ErrorBrush => ResolveBrush("Brush.Red", ErrorFallback);

    //  Кривые зеркалят токены GlobalResources Ease.* 1:1 (ease_out_quart/_quint/_standard) —
    //  для императивных частей (press, перекл. длительностей). Декларативный XAML берёт токены.
    private static readonly Easing EaseOutQuart = new SplineEasing(0.25, 1, 0.5, 1);
    private static readonly Easing EaseOutQuint = new SplineEasing(0.22, 1, 0.36, 1);
    private static readonly Easing EaseStandard = new SplineEasing(0.2, 0, 0, 1);

    //  Cold-start «сборка» героя проигрывается ОДИН раз за процесс (Android shield_assemble).
    private static bool _assembled;

    private bool _pressing;
    private ConnectVisualState _visualState = ConnectVisualState.Idle;

    //  Visibility pause (§C3 / idle B5): while the window is minimized OR hidden-to-tray the hero is
    //  off-screen, yet the connecting arc / glow-breathe / shield-breathe keep ticking the compositor
    //  clock. We strip those infinite loops when the window goes invisible and re-attach them on
    //  restore (if still connecting/connected) — pure start/stop, no curve/duration change. Guarded so
    //  a state change ARRIVING while hidden (e.g. connect completes in tray) never starts a loop
    //  off-screen; the loop is (re)attached only on the restore re-apply.
    private bool _animationsPaused;

    //  Layout-deactivation pause (RAM/CPU): in the keep-alive shell BOTH the wide and compact hero
    //  are permanent children of contentHost; only the active-layout hero holds the live VM. The
    //  INACTIVE hero sits at Opacity=0 — which does NOT stop Style animations (only IsVisible=false
    //  would) — so without this its ambient breathe/sonar + glow/arc/shield loops would tick the
    //  compositor forever off-screen. HomeHeroPresenter.Deactivate() (on unbind) sets this and strips
    //  the loops; Activate() (on rebind) clears it and the presenter's re-apply re-attaches them.
    //  Folded with _animationsPaused into MotionSuppressed so every loop gate honours BOTH reasons.
    private bool _deactivated;

    //  True when NO infinite loop may run right now — window hidden/minimized OR this layout is the
    //  inactive one. Every loop-attach site checks this so a suppressed hero never drives the clock.
    private bool MotionSuppressed => _animationsPaused || _deactivated;

    private Window? _heroWindow;
    private IDisposable? _winStateSub;
    private IDisposable? _winVisibleSub;
    private IDisposable? _winBoundsSub;

    //  Last hasServer passed to SetConnectState — remembered so a runtime lite toggle can re-apply
    //  the CURRENT visual state (jump to its end-look) without the presenter re-driving it.
    private bool _hasServer = true;

    //  Онбординг «нет подписки» активен (ShowEmptyState(true)): герой снят со сцены, поэтому ambient-
    //  петля не должна крутить компоновщик под скрытым слоем. SetAmbient уважает этот флаг.
    private bool _empty;

    /// <summary>
    /// Ступень прогиба кнопки подключения — ОДНО число на весь экран (motion.md «Отклик на нажатие»:
    /// «Кнопка подключения — 0.975»). Раньше значение жило в ДВУХ местах и в двух разных вариантах:
    /// императивные 0.94 здесь и сеттер <c>Border.ConnectDisc.pressed → scale(0.94)</c> в
    /// GlobalStyles. Сеттер был МЁРТВЫМ — класс «pressed» диску не навешивает никто (диск ведёт
    /// press сам, собственным self-centering ScaleTransform), поэтому правка стиля не давала
    /// эффекта и разъезд двух чисел было нечем заметить. Теперь глубина живёт ЗДЕСЬ и только здесь.
    /// Мёртвый сеттер в GlobalStyles.axaml — не мой файл, вынесен в отчёт владельцу.
    /// </summary>
    private const double DiscPressScale = 0.975;

    /// <summary>
    /// Глиф щита проседает на ступень ГЛУБЖЕ диска — «вдав» читается объёмом, а не одним размером.
    /// Держим ту же разницу, что была при 0.94/0.97 (полступени), но считаем ОТ ступени диска, а не
    /// вторым магическим числом: сменится ступень — parallax поедет за ней сам.
    /// </summary>
    private const double GlyphPressScale = DiscPressScale - ((1.0 - DiscPressScale) / 2.0);

    //  Press-scale диска: собственный ScaleTransform, чей ScaleX/Y анимируем через его же
    //  переходы — длительность+кривую перекл. по направлению (quart-in 90 / quint-out 160).
    //  ЭТОТ ЖЕ ScaleTransform ведёт connect-bloom (1.0→1.04→1.0) и error-contract (1.0→0.98→1.0):
    //  оба — оседания через те же переходы (симметрично прочитанные обе ноги, БЕЗ overshoot/bounce).
    private readonly ScaleTransform _discScale = new(1, 1);
    private readonly DoubleTransition _discScaleX = new() { Property = ScaleTransform.ScaleXProperty, Duration = TimeSpan.FromMilliseconds(160) };
    private readonly DoubleTransition _discScaleY = new() { Property = ScaleTransform.ScaleYProperty, Duration = TimeSpan.FromMilliseconds(160) };

    //  Глиф-щит parallax-dip на press: вложенный self-centering ScaleTransform на Viewbox — глиф
    //  «вдавливается» в диск на полступени глубже самого диска (см. GlyphPressScale, оно считается
    //  от DiscPressScale). Тайминг зеркалит press-scale (90/160).
    private readonly ScaleTransform _glyphScale = new(1, 1);
    private readonly DoubleTransition _glyphScaleX = new() { Property = ScaleTransform.ScaleXProperty, Duration = TimeSpan.FromMilliseconds(160) };
    private readonly DoubleTransition _glyphScaleY = new() { Property = ScaleTransform.ScaleYProperty, Duration = TimeSpan.FromMilliseconds(160) };

    //  Press-скрим диска (0→~0.12): чёрная «лунка» под глифом на press. Тайминг зеркалит press-scale.
    private readonly DoubleTransition _scrimOpacity = new() { Property = Visual.OpacityProperty, Duration = TimeSpan.FromMilliseconds(160) };

    //  Opacity-переход дуги: 200мс OutQuint на wind-up (fade-in из покоя) / 220мс Standard на dissolve
    //  (растворение в glow на connect-confirm). Длительность/кривую перекл. код-behind по сайту.
    private readonly DoubleTransition _arcOpacity = new() { Property = Visual.OpacityProperty, Duration = TimeSpan.FromMilliseconds(220) };

    //  Hover-переходы (P0-1): поверхность диска (Brush) + накладное кольцо (Opacity) на наведении.
    //  120мс OutQuart в движении, 0мс в lite (мгновенный swap, без «моушена» — только цвет/яркость).
    private const double HoverMs = 120;
    private readonly BrushTransition _discSurface = new() { Property = Border.BackgroundProperty, Duration = TimeSpan.FromMilliseconds(HoverMs) };
    private readonly DoubleTransition _ringHoverOpacity = new() { Property = Visual.OpacityProperty, Duration = TimeSpan.FromMilliseconds(HoverMs) };

    //  Retry-hint fade (Error): 220мс Standard в движении, 0мс в lite (мгновенно виден).
    private readonly DoubleTransition _retryHintOpacity = new() { Property = Visual.OpacityProperty, Duration = TimeSpan.FromMilliseconds(220) };

    //  Указатель над диском — держим флаг, чтобы hover-exit был идемпотентен (PointerExited приходит
    //  и как отмена press, и как выход hover) и чтобы не «залипнуть» в hover-виде.
    private bool _hovering;

    //  Переходы crossfade/tint/glow из XAML — длительность (и кривую glow) перекл. на реверсе (75%).
    private DoubleTransition? _outlineOpacity;
    private BrushTransition? _outlineFill;
    private DoubleTransition? _filledOpacity;
    private DoubleTransition? _glowOpacity;

    /// <summary>Тап по диску: переключить подключение (connect/disconnect).</summary>
    public event EventHandler? ConnectToggleRequested;

    /// <summary>Онбординг «нет подписки»: добавить по QR-коду (пункт меню «плюса»).</summary>
    public event EventHandler? AddByQrRequested;

    /// <summary>Онбординг «нет подписки»: уйти в «Аккаунт» — оттуда покупают подписку, после
    /// чего серверы приезжают сами. Хост решает, как открыть вкладку (как у чипа аккаунта).</summary>
    public event EventHandler? AccountRequested;

    /// <summary>Онбординг «нет подписки»: добавить из буфера обмена.</summary>
    public event EventHandler? AddFromClipboardRequested;

    /// <summary>Если true — движение подавлено, состояния прыгают к конечному виду.</summary>
    public bool ReducedMotion { get; set; }

    #region Геометрия кольца — равные зазоры на всех размерах

    /// <summary>Пресет размера кольца подключения (tokens.md «Кольцо подключения»).</summary>
    public enum HeroSize
    {
        /// <summary>Обычный: кадр 230, диск 166, щит 80.</summary>
        Normal,

        /// <summary>Компактный (900×860): кадр 212, диск 152, щит 72.</summary>
        Compact,

        /// <summary>Узкий (420×860, «как телефон»): кадр 190, диск 138, щит 64.</summary>
        Narrow,
    }

    //  ЕДИНСТВЕННАЯ таблица размеров героя. Ни одного диаметра в разметке — иначе кольца пришлось бы
    //  править в двух местах, и на нестандартном пресете зазоры «поехали» бы (ровно та приёмка, что
    //  этот экран проваливал раньше).
    private static (double frame, double disc, double shield) Preset(HeroSize size) => size switch
    {
        HeroSize.Compact => (212, 152, 72),
        HeroSize.Narrow => (190, 138, 64),
        _ => (230, 166, 80),
    };

    //  Номинальная ширина окна каждого пресета — та же таблица tokens.md «Размеры окна и масштаб»
    //  (1366 · Компактное 900 · Узкое 420). Выбор идёт по БЛИЖАЙШЕЙ номинальной ширине, поэтому
    //  порогов как отдельных чисел не существует: они падают из этой же таблицы (границы —
    //  середины между соседями, 1133 и 660). Сменится таблица — сменятся и пороги.
    private static readonly (HeroSize size, double nominalWindowWidth)[] SizeLadder =
    [
        (HeroSize.Narrow, 420),
        (HeroSize.Compact, 900),
        (HeroSize.Normal, 1366),
    ];

    private HeroSize _heroSize = HeroSize.Normal;

    /// <summary>Половина диска — центр press-scale (ставится вместе с геометрией).</summary>
    private double _discHalf = 83;

    /// <summary>
    /// Задаёт пресет размера кольца. Всё остальное — кадр, три кольца, комета, сонар, ambient, диск
    /// и щит — пересчитывается отсюда. Публично, чтобы раскладка-хозяин могла навязать пресет; в
    /// обычной жизни его выбирает сам герой (<see cref="SyncHeroSizeToWindow"/>).
    /// </summary>
    public void SetHeroSize(HeroSize size)
    {
        if (_heroSize == size)
        {
            return;
        }

        _heroSize = size;
        ApplyHeroGeometry();
    }

    /// <summary>
    /// Выбирает пресет по ширине окна САМ. Раньше <see cref="SetHeroSize"/> не звал никто, и
    /// компактный с узким пресетом были мёртвой таблицей: герой рисовал кадр 230 и в окне 1366, и
    /// в телефонном 372 — кольца упирались в края.
    ///
    /// Меряем ширину в КООРДИНАТАХ КОНТЕНТА (<c>Bounds.Width / UiScaleState.Current</c>) — ровно
    /// так же, как MainWindow считает свой брейкпоинт раскладки: корень окна зумится
    /// LayoutTransform-ом, поэтому физическая ширина при zoom 1.4 врёт в полтора раза. Отсюда же
    /// «на 1920 и 2560 пропорции совпадают с 1366×768, только крупнее»: там растёт масштаб, а
    /// логическая ширина остаётся ~1371/1384 — тот же пресет 230.
    ///
    /// Пресет — БЛИЖАЙШИЙ по номинальной ширине из <see cref="SizeLadder"/>, а не результат
    /// отдельной лесенки порогов: два списка чисел неизбежно разъехались бы.
    /// </summary>
    private void SyncHeroSizeToWindow()
    {
        if (_heroWindow is null)
        {
            return;
        }

        var scale = UiScaleState.Current;
        var width = _heroWindow.Bounds.Width / (scale > 0 ? scale : 1.0);
        if (width <= 0)
        {
            return; // окно ещё не разложено — придёт следующая эмиссия Bounds
        }

        var best = SizeLadder[0];
        foreach (var candidate in SizeLadder)
        {
            if (Math.Abs(width - candidate.nominalWindowWidth) < Math.Abs(width - best.nominalWindowWidth))
            {
                best = candidate;
            }
        }

        SetHeroSize(best.size);
    }

    /// <summary>
    /// ЗАКОН РАВНЫХ ЗАЗОРОВ. Радиусы четырёх окружностей — внешнее кольцо, среднее, активное и кромка
    /// диска — образуют арифметическую прогрессию с шагом <c>g = (кадр − диск) / 6</c> (три зазора на
    /// радиус, отсюда шестёрка). Отступы получаются 0 · g · 2g · 3g, то есть ровно табличные 0 · 11 · 21
    /// · 32 при кадре 230 и диске 166, 0 · 10 · 20 · 30 при 212/152, 0 · 8.7 · 17.3 · 26 при 190/138.
    /// Зазоры равны НЕ потому, что подобраны, а потому, что вычислены — сменить пресет и «развалить»
    /// их невозможно.
    ///
    /// Толщины обводок (1.5 / 1.5 / 2.5) от размера НЕ зависят: волосяная линия одинакова на всех
    /// пресетах, иначе на узком она бы истончилась до невидимости.
    /// </summary>
    private void ApplyHeroGeometry()
    {
        var (frame, disc, shield) = Preset(_heroSize);
        var g = (frame - disc) / 6.0;

        HeroFrame.Width = HeroFrame.Height = frame;

        //  Стата-строка дышит вместе с героем: в широкой раскладке интервалы 28 (эталон 1366 даёт
        //  ~195 на строку), в компактной и узкой — 20 (эталон компакта ~179). Раньше 20 стояло
        //  всюду, и широкая не отличалась от компактной.
        StatsRow.Spacing = _heroSize == HeroSize.Normal ? 28 : 20;

        //  Кольца: отступ 0 / g / 2g → диаметры frame / frame−2g / frame−4g.
        SetCircle(RingOuter, frame);
        SetCircle(RingHoverGlow, frame);
        SetCircle(RingMid, frame - (2 * g));
        SetCircle(RingActive, frame - (4 * g));

        //  Комета лежит РОВНО на активном кольце: тот же диаметр, та же толщина, тот же центр.
        SetCircle(Comet, frame - (4 * g));
        Comet.StrokeThickness = RingActive.StrokeThickness;
        if (Comet.RenderTransform is RotateTransform rot)
        {
            //  Дублируем центр вращения явными координатами (origin 0.5,0.5 + center = половина
            //  элемента): оба указывают в геометрический центр, поэтому орбита невозможна.
            var half = (frame - (4 * g)) / 2.0;
            rot.CenterX = half;
            rot.CenterY = half;
        }

        //  Сонар расходится ОТ активного кольца — значит стартует на нём же.
        SetCircle(SonarPulse, frame - (4 * g));

        //  Ambient: волна стартует от активного кольца, дышащее кольцо — чуть внутри внешнего.
        SetCircle(AmbientSonar, frame - (4 * g));
        SetCircle(AmbientRing, frame - g);

        //  Glow-halo заполняет кадр целиком (радиальная кисть сама сходит в ноль по краю).
        SetCircle(GlowHalo, frame - g);

        //  Диск и щит.
        ConnectDisc.Width = ConnectDisc.Height = disc;
        ConnectDisc.CornerRadius = new CornerRadius(disc / 2);
        _discHalf = disc / 2.0;
        ShieldViewbox.Width = ShieldViewbox.Height = shield;

        //  Самоцентрирующие TransformGroup диска и глифа держат ПОЛОВИНУ своего размера — после
        //  смены пресета их надо пересобрать, иначе масштаб нажатия уехал бы из центра.
        RebuildPressTransforms(shield);
    }

    private static void SetCircle(Avalonia.Layout.Layoutable el, double d)
    {
        el.Width = d;
        el.Height = d;
    }

    /// <summary>
    /// Самоцентрирующие press-трансформы диска и глифа. RenderTransformOrigin в этой сборке НЕ
    /// применяется к анимируемым render-transform, поэтому центр задаём явным сдвигом
    /// (−половина → scale → +половина); голый ScaleTransform иначе давил бы от левого-верхнего угла.
    /// Половины зависят от пресета, поэтому группы пересобираются вместе с геометрией.
    /// </summary>
    private void RebuildPressTransforms(double shield)
    {
        ConnectDisc.RenderTransform = new TransformGroup
        {
            Children =
            {
                new TranslateTransform { X = -_discHalf, Y = -_discHalf },
                _discScale,
                new TranslateTransform { X = _discHalf, Y = _discHalf },
            },
        };

        var glyphHalf = shield / 2.0;
        ShieldViewbox.RenderTransform = new TransformGroup
        {
            Children =
            {
                new TranslateTransform { X = -glyphHalf, Y = -glyphHalf },
                _glyphScale,
                new TranslateTransform { X = glyphHalf, Y = glyphHalf },
            },
        };
    }

    #endregion Геометрия кольца — равные зазоры на всех размерах

    public ConnectHeroView()
    {
        InitializeComponent();

        //  Движение подавляем, если включён «Облегчённый режим» (Настройки → LiteMode,
        //  _config.UiItem.LiteMode) ЛИБО система просит меньше анимаций
        //  («Показывать анимации в Windows» выкл). По умолчанию — движение включено.
        ReducedMotion = LiteModeEnabled() || !SystemAnimationsEnabled();

        //  Стата-строка над щитом скрыта целиком в lite (task 2) — начальное состояние по флагу.
        StatsRow.IsVisible = !ReducedMotion;

        //  Держим ссылки на декларативные переходы, чтобы гнуть их темп по направлению.
        //  Порядок соответствует XAML: ShieldOutline[0]=Opacity,[1]=Fill; Filled[0]=Opacity; Glow[0]=Opacity.
        _outlineOpacity = ShieldOutline.Transitions?[0] as DoubleTransition;
        _outlineFill = ShieldOutline.Transitions?[1] as BrushTransition;
        _filledOpacity = ShieldFilled.Transitions?[0] as DoubleTransition;
        _glowOpacity = GlowHalo.Transitions?[0] as DoubleTransition;

        //  Свой press-scale вместо общего перехода RenderTransform из GlobalStyles: масштаб —
        //  единственный отклик (без ripple/glow). Перекрываем переходы диска пустыми и ставим ScaleTransform.
        //
        //  ЦЕНТР МАСШТАБА. RenderTransformOrigin НЕ применяется к анимируемым render-transform в этой
        //  сборке (та же причина, по которой комета держит центр через RotateTransform.CenterX/Y,
        //  а не через origin). У ScaleTransform НЕТ CenterX/CenterY, поэтому голый ScaleTransform
        //  масштабировал диск от ЛЕВОГО-ВЕРХНЕГО угла → диск «проваливался» влево-вверх. Оборачиваем в
        //  группу: сдвиг центра диска (88,88) в (0,0) → масштаб → сдвиг обратно = масштаб строго вокруг
        //  центра. Переход живёт на самом ScaleTransform (асимметрия 90/160 сохраняется).
        _discScale.Transitions = new Transitions { _discScaleX, _discScaleY };
        //  Переход фона диска (hover surface-lift) живёт на самом Border; press-scale — отдельный
        //  вложенный ScaleTransform (ниже), поэтому эти два отклика не мешают друг другу.
        ConnectDisc.Transitions = new Transitions { _discSurface };
        _glyphScale.Transitions = new Transitions { _glyphScaleX, _glyphScaleY };

        //  Геометрия кольца (кадр, три кольца, комета, сонар, диск, щит) + самоцентрирующие
        //  press-трансформы — из ОДНОЙ таблицы пресетов, с равными зазорами по построению.
        ApplyHeroGeometry();

        //  Переходы, которыми управляет код-behind по сайту вызова (длительность/кривая/направление).
        PressScrim.Transitions = new Transitions { _scrimOpacity };
        Comet.Transitions = new Transitions { _arcOpacity };
        RingHoverGlow.Transitions = new Transitions { _ringHoverOpacity };
        RetryHint.Transitions = new Transitions { _retryHintOpacity };

        //  Кривые hover/retry-переходов + начальные длительности по режиму (lite = 0мс).
        _discSurface.Easing = _ringHoverOpacity.Easing = EaseOutQuart;
        _retryHintOpacity.Easing = EaseStandard;
        ApplyMotionGatedDurations();

        //  Диск — кнопка connect: press-scale + клик = переключение; hover = surface-lift + ring-brighten.
        ConnectDisc.PointerPressed += OnDiscPointerPressed;
        ConnectDisc.PointerReleased += OnDiscPointerReleased;
        ConnectDisc.PointerCaptureLost += OnDiscPressCancel;
        ConnectDisc.PointerExited += OnDiscPressCancel;
        ConnectDisc.PointerEntered += OnDiscPointerEntered;
        ConnectDisc.PointerExited += OnDiscHoverExit;

        GoAccountButton.Click += (_, _) => AccountRequested?.Invoke(this, EventArgs.Empty);
        AddClipboardButton.Click += (_, _) => AddFromClipboardRequested?.Invoke(this, EventArgs.Empty);

        //  Угловой «+» (правый-верхний угол панели) — меню импорта (буфер / QR) через команды
        //  унаследованного HomeViewModel (см. XAML Command-биндинги); code-behind проводка не нужна.

        //  Реактивный lite: подписываемся, пока во визуальном дереве, и синхронизируем ReducedMotion
        //  с текущим MotionState при входе (значение уже засеяно MainWindow к моменту показа окна).
        AttachedToVisualTree += OnHeroAttached;
        DetachedFromVisualTree += OnHeroDetached;

        //  Cold-start «сборка» героя по первому показу окна (гвард статик-флагом). Прячем герой
        //  ДО первого кадра, чтобы не мелькнул в покое перед анимацией «сборки».
        if (!ReducedMotion && !_assembled)
        {
            HeroFrame.Opacity = 0;

            //  СТРАХОВКА ОТ ПУСТОГО ЩИТА. Пре-скрытие полагается на Loaded, который вернёт видимость.
            //  Если Loaded не придёт (переиспользование окна, прерванная раскладка, исключение внутри
            //  «сборки») — щит остался бы невидимым НАВСЕГДА. Форсируем видимость с запасом: на штатном
            //  пути «сборка» (≈460мс) уже вернула Opacity=1, поэтому здесь это no-op.
            DispatcherTimer.RunOnce(EnsureHeroVisible, TimeSpan.FromMilliseconds(700));
        }

        Loaded += OnFirstLoaded;

        //  Первый рендер: idle БЕЗ сервера — идентичность скрыта, пока HomeView не подставит
        //  реальный активный сервер (data-driven: никаких плейсхолдеров в рантайме).
        SetConnectState(ConnectVisualState.Idle, hasServer: false, animate: false);
    }

    /// <summary>
    /// Переключает панель между героем (подписка есть) и онбордингом «нет подписки»
    /// (нет серверов) — аналог Android updateHomeEmptyState.
    /// </summary>
    public void ShowEmptyState(bool empty)
    {
        _empty = empty;
        LayerEmpty.IsVisible = empty;
        LayerNormal.IsVisible = !empty;

        //  Онбординг снимает герой со сцены → гасим ambient-петлю (иначе она крутила бы компоновщик
        //  под скрытым слоем). Возврат к герою re-применяет ambient текущего состояния.
        if (empty)
        {
            RemoveAmbientLoops();
            AmbientRing.IsVisible = false;
            AmbientSonar.IsVisible = false;
        }
        else
        {
            SetAmbient(_visualState);
        }
    }

    /// <summary>
    /// Подпись под щитом в состоянии Error: ПОЧЕМУ не подключилось и что делать (00-rules §9.4).
    /// Пустая строка возвращает прежнюю тихую аффорданс-подсказку «Нажмите, чтобы повторить» —
    /// щит остаётся кнопкой повтора в обоих случаях.
    /// </summary>
    public void SetErrorReason(string? reason)
    {
        RetryHint.Text = reason.IsNullOrEmpty() ? L.T("Home_RetryHint") : reason;
    }

    /// <summary>
    /// Задаёт щит/кольца/glow/подпись для состояния подключения. <paramref name="hasServer"/>
    /// тускнит idle-щит (0.38) и меняет «Не подключено» ↔ «Выберите сервер».
    /// <paramref name="animate"/> = живой переход в connected (играет сонар-подтверждение).
    /// Переход в idle трактуется как реверс (отключение) и идёт на ~75% темпа.
    /// </summary>
    public void SetConnectState(ConnectVisualState state, bool hasServer = true, bool animate = false)
    {
        _hasServer = hasServer;
        var motion = animate && !ReducedMotion;

        //  Idle/Error — «остывающая» цель ⇒ реверс-темп (165/225); connecting/connected — forward (220/300).
        //  Error приходит из Connecting как провал (мягко «оседает»), поэтому идёт по реверс-темпу.
        PrepareStateTiming(reverse: state is ConnectVisualState.Idle or ConnectVisualState.Error);

        //  Вторичный connecting-сигнал: щит «дышит» акцентом в УНИСОН с glow (см. SetShieldPulse).
        //  Ставим/снимаем ДО switch — на connected/idle класс снят прежде, чем ветка выставит
        //  Opacity контура (иначе анимация перекрыла бы crossfade контур→залив).
        SetShieldPulse(state == ConnectVisualState.Connecting);

        //  Вход в состояние (а не re-apply/восстановление): считаем по ПРЕДЫДУЩЕМУ _visualState (он
        //  ещё старый — обновляется в конце метода). Ведёт wind-up дуги и error-contract — оба
        //  приходят с animate:false, поэтому «свежесть» перехода нельзя брать из motion.
        var enteringConnecting = state == ConnectVisualState.Connecting && _visualState != ConnectVisualState.Connecting;
        var enteringError = state == ConnectVisualState.Error && _visualState != ConnectVisualState.Error;

        switch (state)
        {
            case ConnectVisualState.Connecting:
                ShieldOutline.Fill = AccentBrush;
                ShieldOutline.Opacity = 1;
                ShieldFilled.Opacity = 0;
                SetStatusPill(L.T("Status_Connecting"), AccentBrush, AccentBrush);
                ServerInfo.IsVisible = true;
                //  Проявление из покоя только на СВЕЖЕМ входе; re-apply уже в connecting → без фейда.
                SetArc(true, windUp: enteringConnecting);
                SetGlow(connecting: true, connected: false);
                HideSonar();
                break;

            case ConnectVisualState.Connected:
                ShieldOutline.Fill = AccentBrush;
                ShieldOutline.Opacity = 0;
                ShieldFilled.Opacity = 1;
                SetStatusPill(L.T("Status_Connected"), AccentBrush, AccentBrush);
                ServerInfo.IsVisible = true;
                SetGlow(connecting: false, connected: true);
                //  Payoff — одноразовый; играется ТОЛЬКО на живом переходе (motion) и на экране: дуга
                //  растворяется (не моргает), диск «приземляется» bloom-ом, одно кольцо сонара. На
                //  восстановлении/rebind (animate:false) — прыжок в конечный вид без повтора payoff.
                if (motion && !MotionSuppressed)
                {
                    DissolveArc();
                    PlaySonar();
                    PlayConnectBloom();
                }
                else if (_visualState != ConnectVisualState.Connected)
                {
                    SetArc(false);
                    HideSonar();
                }
                //  Иначе это ПОВТОРНОЕ применение того же connected, и идущий payoff не трогаем: он
                //  доигрывает и убирает себя сам. Подключение приходит в презентер двумя изменениями
                //  подряд (IsConnected, затем IsConnecting=false), и второе раньше снимало сонар и
                //  обрывало растворение дуги до первого кадра: подтверждения подключения не видел никто.
                //  Смена языка и темы тоже применяют состояние заново и тоже не должны его обрывать;
                //  облегчённый режим и скрытое окно снимают сонар сами, до повторного применения.

                break;

            case ConnectVisualState.Error:
                //  Провал подключения: тот же контур-щит, но тонирован Red (crossfade синий→красный
                //  по реверс-темпу), подпись + подсказка-ретрай красным. Диск остаётся кнопкой —
                //  тап = ConnectToggleRequested = повторная попытка (тогда HomeVM гонит Connecting и
                //  ветка Error сама затирается). Никаких петель/сонара — статичный конечный вид.
                ShieldOutline.Fill = ErrorBrush;
                ShieldOutline.Opacity = 1;
                ShieldFilled.Opacity = 0;
                SetStatusPill(L.T("Common_CouldntConnect"), ErrorBrush, ErrorBrush);
                ServerInfo.IsVisible = hasServer;
                SetArc(false);
                SetGlow(connecting: false, connected: false);
                HideSonar();
                //  Один спокойный «вдав» при входе (P1-3) — тихое подтверждение обрыва, НЕ shake/bounce.
                if (enteringError && !ReducedMotion && !MotionSuppressed)
                {
                    PlayErrorContract();
                }

                UpSpeed.Text = DownSpeed.Text = ByteSize.Speed(0);
                Uptime.Text = "00:00:00";
                break;

            default: // Idle
                //  Если указатель всё ещё над диском (in-place возврат в Idle / смена темы re-assert'ит
                //  Fill), сохраняем hover-подогрев глифа — иначе он «остыл» бы к серому под курсором.
                ShieldOutline.Fill = _hovering && hasServer ? OnSurfaceBrush : ShieldIdleBrush;
                ShieldOutline.Opacity = hasServer ? 1 : 0.38;
                ShieldFilled.Opacity = 0;
                SetStatusPill(hasServer ? L.T("Home_NotConnected") : L.T("Home_ChooseServer"), MutedBrush, OutlineBrush);
                ServerInfo.IsVisible = hasServer;
                SetArc(false);
                SetGlow(connecting: false, connected: false);
                HideSonar();
                UpSpeed.Text = DownSpeed.Text = ByteSize.Speed(0);
                Uptime.Text = "00:00:00";
                break;
        }

        //  Кольца по состоянию + приглушение всего, что ниже пилюли статуса.
        ApplyRingTint(state);
        ApplyMetaDim(state);

        //  Ambient «живой» слой вокруг щита: калм в Idle, чуть ярче/крупнее в Connected (поверх
        //  статичного glow), выключен в Connecting (там уже своё движение) и Error (статичен).
        //  Внутри gated по lite/reduced-motion, паузе окна и онбордингу — см. SetAmbient.
        SetAmbient(state);

        //  Подсказка-ретрай видна ТОЛЬКО в Error — тихая аффорданс «тапни щит, чтобы повторить».
        //  На свежем входе в Error плавно проявляем (fade 0→1); иначе (lite/suppressed/re-apply) —
        //  мгновенно (переход 0мс в lite). Вне Error — скрываем и сбрасываем Opacity для след. входа.
        if (state == ConnectVisualState.Error)
        {
            RetryHint.IsVisible = true;
            if (enteringError && !ReducedMotion && !MotionSuppressed)
            {
                RetryHint.Opacity = 0;
                Dispatcher.UIThread.Post(
                    () =>
                    {
                        if (_visualState == ConnectVisualState.Error)
                        {
                            RetryHint.Opacity = 1;
                        }
                    },
                    DispatcherPriority.Background);
            }
            else
            {
                RetryHint.Opacity = 1;
            }
        }
        else
        {
            RetryHint.IsVisible = false;
            RetryHint.Opacity = 0;
        }

        _visualState = state;
    }

    /// <summary>
    /// Пилюля статуса: текст + чернила + контур одним вызовом (tokens.md «Пилюля статуса»).
    /// Не подключено — приглушённый текст и серый контур; подключаемся/подключено — акцент в обоих.
    /// </summary>
    private void SetStatusPill(string text, IBrush ink, IBrush outline)
    {
        StatusText.Text = text;
        StatusText.Foreground = ink;
        StatusPill.BorderBrush = outline;
    }

    /// <summary>
    /// Цвет трёх колец по состоянию (прототип: внешнее всегда тусклое, среднее и активное оживают).
    /// Кольца сидят на СВОИХ токенах Brush.Ring.*, а не на акценте: плёнка из #1F6FF5 мутит на тёмной,
    /// а mono-оверлей и так уводит Ring.* в белый — поэтому «в чёрно-белой не остаётся синего»
    /// выполняется без единой ветки по теме.
    /// </summary>
    private void ApplyRingTint(ConnectVisualState state)
    {
        var live = state is ConnectVisualState.Connecting or ConnectVisualState.Connected;
        RingOuter.Stroke = RingDimBrush;
        RingMid.Stroke = live ? RingLiveBrush : RingDimBrush;
        RingActive.Stroke = live ? RingLiveBrush : RingDimBrush;

        //  Комета берёт RGB из того же токена активного кольца → синяя в тёмной и светлой, белая
        //  в чёрно-белой. Альфу задаёт её собственная коническая рампа, поэтому альфа токена здесь
        //  намеренно игнорируется (иначе хвост стал бы вдвое бледнее).
        if (RingLiveBrush is ISolidColorBrush solid)
        {
            var c = solid.Color;
            Comet.RingColor = Color.FromRgb(c.R, c.G, c.B);
        }
    }

    /// <summary>
    /// «Когда не подключено, всё под пилюлей приглушено до 45%» (screens.md). Один контейнер —
    /// скорости и имя сервера гаснут одной волной, а не двумя.
    /// </summary>
    private void ApplyMetaDim(ConnectVisualState state) =>
        HeroMeta.Opacity = state == ConnectVisualState.Connected ? 1 : 0.45;

    /// <summary>Обновляет ↑/↓ (строки <see cref="ByteSize.Speed"/>, напр. «1,2 МБ/с»).</summary>
    public void SetSpeeds(string up, string down)
    {
        UpSpeed.Text = up;
        DownSpeed.Text = down;
    }

    /// <summary>
    /// Резерв ширины под скорости: ячейки ↑ и ↓ держат ширину самой длинной возможной строки на
    /// текущем языке (стрелка + зазор + «99,9 МБ/с»), поэтому смена значения раз в секунду не
    /// двигает таймер и не перекладывает колонку героя. Мерим отдельным пробником с тем же шрифтом,
    /// кеглем, начертанием и tnum, что у живой строки: живую трогать нельзя, а цифры без tnum
    /// дали бы другую ширину. Зовётся при входе в дерево и на смену языка.
    /// </summary>
    private void ReserveSpeedWidth()
    {
        var arrow = MeasureLike(UpArrow, UpArrow.Text ?? string.Empty);
        var widest = ByteSize.SpeedSamples().Max(sample => MeasureLike(UpSpeed, sample));
        UpCell.MinWidth = DownCell.MinWidth = Math.Ceiling(arrow + UpGroup.Spacing + widest);
    }

    private static double MeasureLike(TextBlock like, string text)
    {
        var probe = new TextBlock
        {
            Text = text,
            FontFamily = like.FontFamily,
            FontSize = like.FontSize,
            FontWeight = like.FontWeight,
            FontStyle = like.FontStyle,
            FontFeatures = like.FontFeatures,
        };
        probe.Measure(Size.Infinity);
        return probe.DesiredSize.Width;
    }

    /// <summary>Обновляет центральный таймер аптайма (hh:mm:ss).</summary>
    public void SetUptime(string uptime) => Uptime.Text = uptime;

    /// <summary>
    /// Обновляет идентичность выбранного/подключённого сервера под щитом: флаг + имя акцентом
    /// (screens.md). Протокол и транспорт ПОКАЗЫВАЕТ строка списка слева — под кольцом они
    /// дублировались бы, поэтому параметры принимаются (совместимость с презентером), но не
    /// рисуются. Флаг заполняет FlagResolver; null-источник оставляет нейтральный круг.
    /// </summary>
    public void SetServerInfo(string name, string protocol, string transport, IImage? flag = null)
    {
        ServerName.Text = name;
        ServerFlagImage.Source = flag;
    }

    /// <summary>Show/hide the corner «+» (add-subscription) affordance. The widescreen connect panel
    /// keeps it in its top-right corner; the compact layout hides it (its own header carries the «+»).</summary>
    public void SetCornerAddVisible(bool visible) => CornerAddButton.IsVisible = visible;

    //  Угловой «+» (добавить подписку) — те же события, что онбординг-кнопки; MainWindow ведёт их
    //  в реальный поток добавления (буфер обмена / QR).
    private void OnCornerAddClipboard(object? sender, Avalonia.Interactivity.RoutedEventArgs e) => AddFromClipboardRequested?.Invoke(this, EventArgs.Empty);

    private void OnCornerAddQr(object? sender, Avalonia.Interactivity.RoutedEventArgs e) => AddByQrRequested?.Invoke(this, EventArgs.Empty);

    // ── Реактивный «Облегчённый режим» (runtime, без рестарта) ───────────────────────────
    //  MotionState.Changed → мгновенно гасим/оживляем ВСЮ хореографию щита (спин дуги, breathe glow,
    //  shieldbreathe, сонар, «сборку») и прячем/показываем стата-строку, прыгая в конечный вид.
    private void OnHeroAttached(object? sender, VisualTreeAttachmentEventArgs e)
    {
        MotionState.Changed += OnMotionStateChanged;

        //  Live language switch: the status caption is set imperatively (SetConnectState), so a
        //  {loc:T} binding can't refresh it. Re-apply the current visual state (animate:false = jump
        //  to its end-look, no re-played sonar) whenever the language changes so the caption follows.
        L.Instance.LanguageChanged += OnLanguageChanged;
        ReserveSpeedWidth();

        //  Reactive theme (Bug 1): the idle status foreground / shield / glyph tints are SNAPSHOT
        //  IBrush-и, разрешаемые из тема-токенов (Brush.OnSurface / OnSurfaceVariant / Accent / Red)
        //  под ТЕКУЩИЙ ActualThemeVariant. Приложение стартует Dark, а сохранённая Light/mono-тема
        //  применяется ПОСЛЕ первого SetConnectState(Idle) в ctor — поэтому idle-подпись «Выберите
        //  сервер» захватывала почти-белую Dark-кисть и была невидима на светлой/ч-б теме. Пере-
        //  применяем текущее состояние на смену темы (animate:false = прыжок в конечный вид, без
        //  повторного сонара) — это заново разрешает OnSurfaceBrush/ShieldIdleBrush/AccentBrush/
        //  ErrorBrush через их геттеры, и подпись + щит + глиф становятся корректны для новой темы.
        ActualThemeVariantChanged += OnThemeVariantChanged;
        //  Синхронизируемся с текущим режимом на входе в дерево (без перезапуска состояния —
        //  connect-состояние подаст HomeHeroPresenter). Обновляем только флаг + видимость статы.
        ApplyLiteMode(MotionState.IsLite, reapply: false);

        //  §C3: пауза петель, пока окно свёрнуто/скрыто-в-трей. Наблюдаем WindowState (свёрнуто) и
        //  IsVisible (Hide() в трей не меняет WindowState). GetObservable сразу отдаёт текущее
        //  значение → начальное состояние паузы выставится корректно на входе в дерево.
        _heroWindow = TopLevel.GetTopLevel(this) as Window;
        if (_heroWindow is not null)
        {
            _winStateSub = _heroWindow.GetObservable(Window.WindowStateProperty).Subscribe(_ => UpdateVisibilityPause());
            _winVisibleSub = _heroWindow.GetObservable(Visual.IsVisibleProperty).Subscribe(_ => UpdateVisibilityPause());

            //  Пресет кольца следует за шириной окна. GetObservable отдаёт текущее значение сразу,
            //  поэтому геометрия верна уже на первом кадре — без отдельного «первичного» вызова.
            _winBoundsSub = _heroWindow.GetObservable(Visual.BoundsProperty).Subscribe(_ => SyncHeroSizeToWindow());
        }

        //  In-app zoom (Ctrl +/−) меняет ЛОГИЧЕСКУЮ ширину, не трогая Bounds окна, — без этой
        //  подписки пресет застрял бы на значении, снятом при старом масштабе.
        UiScaleState.Changed += OnUiScaleChanged;
    }

    private void OnUiScaleChanged(object? sender, double scale) => SyncHeroSizeToWindow();

    private void OnHeroDetached(object? sender, VisualTreeAttachmentEventArgs e)
    {
        MotionState.Changed -= OnMotionStateChanged;
        L.Instance.LanguageChanged -= OnLanguageChanged;
        ActualThemeVariantChanged -= OnThemeVariantChanged;

        UiScaleState.Changed -= OnUiScaleChanged;

        _winStateSub?.Dispose();
        _winStateSub = null;
        _winVisibleSub?.Dispose();
        _winVisibleSub = null;
        _winBoundsSub?.Dispose();
        _winBoundsSub = null;
        _heroWindow = null;
        //  Сброс флага: следующий attach заново оценит видимость окна (priming-эмиссия наблюдений).
        _animationsPaused = false;
    }

    // ── §C3: пауза/возобновление бесконечных петель по видимости окна ─────────────────────
    //  Скрыто == окно свёрнуто ЛИБО спрятано (Hide() в трей: IsVisible=false, WindowState не
    //  меняется). Простой Deactivated (окно видимо, но не в фокусе) НЕ ставит паузу намеренно —
    //  иначе петля «замерла» бы прямо на экране у пользователя, переключившего окно.
    private void UpdateVisibilityPause()
    {
        var hidden = _heroWindow is not null
            && (_heroWindow.WindowState == WindowState.Minimized || !_heroWindow.IsVisible);
        if (hidden == _animationsPaused)
        {
            return;
        }

        if (hidden)
        {
            //  Тот же teardown, что и при выходе из connecting/connected — снимаем ВСЕ петли.
            _animationsPaused = true;
            Comet.Classes.Remove("spinning");
            GlowHalo.Classes.Remove("breathing");
            ShieldOutline.Classes.Remove("shieldbreathe");
            HideSonar();
            RemoveAmbientLoops(); // ambient «живой» слой — не тикаем компоновщик за экраном
        }
        else
        {
            //  Восстановление: снимаем флаг ПЕРЕД re-apply, чтобы петли снова навесились. animate:false
            //  — прыгаем в конечный вид текущего состояния (без повторного сонара), поэтому щит не
            //  может «залипнуть» в неверном визуале после разворачивания.
            _animationsPaused = false;
            SetConnectState(_visualState, hasServer: _hasServer, animate: false);
        }
    }

    // ── Layout deactivation: stop this hero's infinite loops when its layout goes inactive ─────────
    //  In the keep-alive shell only the ACTIVE-layout hero holds the live VM; the other sits at
    //  Opacity=0 (which does NOT halt Style animations) and is NEVER DetachedFromVisualTree, so its
    //  loops would otherwise tick the compositor forever off-screen — a real CPU/RAM regression.
    //  HomeHeroPresenter calls Deactivate() when it unbinds (host nulls the inactive DataContext) and
    //  Activate() when it (re)binds. Deactivate strips every loop immediately (same teardown as the
    //  window-hidden pause); Activate just clears the gate — the presenter's subsequent SetConnectState
    //  re-attaches whatever the current state needs. Idempotent.
    public void Deactivate()
    {
        if (_deactivated)
        {
            return;
        }
        _deactivated = true;
        Comet.Classes.Remove("spinning");
        GlowHalo.Classes.Remove("breathing");
        ShieldOutline.Classes.Remove("shieldbreathe");
        HideSonar();
        RemoveAmbientLoops();
        ClearHover();
    }

    public void Activate()
    {
        //  Just lift the gate; the presenter re-applies the current state (animate:false) right after,
        //  which re-attaches the loops this hero should be running now that it is the active layout.
        _deactivated = false;
    }

    private void OnMotionStateChanged(object? sender, bool lite) => ApplyLiteMode(lite, reapply: true);

    private void OnLanguageChanged(object? sender, EventArgs e)
    {
        //  Единицы скорости сменили язык («КБ/с» ↔ «KB/s») — и ширина резерва вместе с ними.
        ReserveSpeedWidth();
        SetConnectState(_visualState, hasServer: _hasServer, animate: false);
    }

    //  Тема сменилась (Dark ↔ Light ↔ mono) → пере-применяем текущее состояние, чтобы snapshot-кисти
    //  подписи/щита/глифа (OnSurfaceBrush / ShieldIdleBrush / AccentBrush / ErrorBrush) разрешились
    //  заново под новый ActualThemeVariant. animate:false = прыжок в конечный вид (без петель/сонара).
    private void OnThemeVariantChanged(object? sender, EventArgs e) =>
        SetConnectState(_visualState, hasServer: _hasServer, animate: false);

    private void ApplyLiteMode(bool lite, bool reapply)
    {
        //  Система тоже может требовать reduced-motion — тогда lite=off всё равно оставляет движение
        //  выключенным (как в ctor).
        ReducedMotion = lite || !SystemAnimationsEnabled();

        //  Task 2: показатели скорости/аптайма над кнопкой пропадают в lite.
        StatsRow.IsVisible = !ReducedMotion;

        //  Hover/retry-переходы мгновенны в lite (0мс) — «swap без моушена».
        ApplyMotionGatedDurations();

        if (!reapply)
        {
            return;
        }

        //  Рантайм-переключение: убиваем любую идущую петлю анимаций и прыгаем в текущий конечный вид.
        //  (При выключении lite SetConnectState заново навесит нужные петли, т.к. ReducedMotion=false.)
        Comet.Classes.Remove("spinning");
        GlowHalo.Classes.Remove("breathing");
        ShieldOutline.Classes.Remove("shieldbreathe");
        HideSonar();
        RemoveAmbientLoops();
        ClearHover();
        HeroFrame.Classes.Remove("assembling");
        EnsureHeroVisible();
        SetConnectState(_visualState, hasServer: _hasServer, animate: false);
    }

    //  Длительности hover/retry-переходов по режиму движения: в lite/reduced-motion — 0мс (мгновенный
    //  swap без «моушена»), иначе штатные 120/220мс. Зовём из ctor и на смену режима (ApplyLiteMode).
    private void ApplyMotionGatedDurations()
    {
        var hover = ReducedMotion ? TimeSpan.Zero : TimeSpan.FromMilliseconds(HoverMs);
        _discSurface.Duration = hover;
        _ringHoverOpacity.Duration = hover;
        _retryHintOpacity.Duration = ReducedMotion ? TimeSpan.Zero : TimeSpan.FromMilliseconds(220);
    }

    // ── Темп переходов (forward 220/300 ↔ реверс 165/225; reduced-motion → 0) ──────────
    private void PrepareStateTiming(bool reverse)
    {
        var state = ReducedMotion ? TimeSpan.Zero : TimeSpan.FromMilliseconds(reverse ? 165 : 220);
        var reveal = ReducedMotion ? TimeSpan.Zero : TimeSpan.FromMilliseconds(reverse ? 225 : 300);
        if (_outlineOpacity is not null)
        {
            _outlineOpacity.Duration = state;
        }

        if (_outlineFill is not null)
        {
            _outlineFill.Duration = state;
        }

        if (_filledOpacity is not null)
        {
            _filledOpacity.Duration = state;
        }

        if (_glowOpacity is not null)
        {
            _glowOpacity.Duration = reveal;
            _glowOpacity.Easing = reverse ? EaseStandard : EaseOutQuint; // reveal quint / hide standard
        }
    }

    // ── Press диска: quart-in 90мс / quint-out 160мс (без цвета/заливки — только масштаб) ──
    private void OnDiscPointerPressed(object? sender, PointerPressedEventArgs e)
    {
        _pressing = true;
        if (ReducedMotion)
        {
            return;
        }

        _discScaleX.Duration = _discScaleY.Duration = TimeSpan.FromMilliseconds(90);
        _discScaleX.Easing = _discScaleY.Easing = EaseOutQuart;
        _discScale.ScaleX = _discScale.ScaleY = DiscPressScale;

        //  Глубина нажатия (P1-2): скрим-«лунка» темнеет 0→0.12 и глиф садится на полступени глубже
        //  диска, в унисон с press-scale (90мс OutQuart). БЕЗ ripple/glow — только физический «вдав».
        _scrimOpacity.Duration = _glyphScaleX.Duration = _glyphScaleY.Duration = TimeSpan.FromMilliseconds(90);
        _scrimOpacity.Easing = _glyphScaleX.Easing = _glyphScaleY.Easing = EaseOutQuart;
        PressScrim.Opacity = 0.12;
        _glyphScale.ScaleX = _glyphScale.ScaleY = GlyphPressScale;
    }

    private void OnDiscPointerReleased(object? sender, PointerReleasedEventArgs e)
    {
        ReleaseDiscScale();
        if (_pressing)
        {
            _pressing = false;
            ConnectToggleRequested?.Invoke(this, EventArgs.Empty);
        }
    }

    private void OnDiscPressCancel(object? sender, EventArgs e)
    {
        _pressing = false;
        ReleaseDiscScale();
    }

    private void ReleaseDiscScale()
    {
        if (ReducedMotion)
        {
            return;
        }

        _discScaleX.Duration = _discScaleY.Duration = TimeSpan.FromMilliseconds(160);
        _discScaleX.Easing = _discScaleY.Easing = EaseOutQuint;
        _discScale.ScaleX = _discScale.ScaleY = 1.0;

        //  Отпускание: скрим гаснет и глиф возвращается вместе с диском (160мс OutQuint).
        _scrimOpacity.Duration = _glyphScaleX.Duration = _glyphScaleY.Duration = TimeSpan.FromMilliseconds(160);
        _scrimOpacity.Easing = _glyphScaleX.Easing = _glyphScaleY.Easing = EaseOutQuint;
        PressScrim.Opacity = 0;
        _glyphScale.ScaleX = _glyphScale.ScaleY = 1.0;
    }

    // ── Hover диска (P0-1): surface-lift + (в Idle) glyph-warm ─────────────────────────────
    //  Desktop-герой обязан отвечать на указатель. Отклик — БЕЗ scale и БЕЗ glow (glow зарезервирован
    //  под connected-payoff): диск поднимается на шаг поверхности (Brush.SurfaceHigh→Highest через
    //  класс .hover, тема-реактивно), а в Idle глиф теплеет к чернилам (OnSurfaceVariant→OnSurface —
    //  НЕ к акценту). Переходы 120мс (0мс в lite).
    //  КОЛЬЦА НА НАВЕДЕНИЕ НЕ РЕАГИРУЮТ (владелец: «не надо, чтобы светились кольца, а только сама
    //  кнопочка»): накладной RingHoverGlow больше не проявляется, отвечает только диск.
    private void OnDiscPointerEntered(object? sender, PointerEventArgs e)
    {
        _hovering = true;
        ConnectDisc.Classes.Add("hover");

        //  Glyph-warm имеет смысл только в Idle (в остальных состояниях Fill щита ведёт state-машина
        //  и re-assert перекроет hover); при отсутствии сервера не «приглашаем» (щит приглушён).
        if (_visualState == ConnectVisualState.Idle && _hasServer)
        {
            ShieldOutline.Fill = OnSurfaceBrush;
        }
    }

    private void OnDiscHoverExit(object? sender, PointerEventArgs e) => ClearHover();

    //  Снять hover-вид. Идемпотентно (PointerExited приходит и как отмена press, и как выход hover;
    //  зовётся также из teardown-ов раскладки/lite). Glyph восстанавливаем ТОЛЬКО если всё ещё Idle —
    //  иначе Fill уже выставлен верной state-веткой.
    private void ClearHover()
    {
        if (!_hovering)
        {
            return;
        }

        _hovering = false;
        ConnectDisc.Classes.Remove("hover");
        RingHoverGlow.Opacity = 0;
        if (_visualState == ConnectVisualState.Idle)
        {
            ShieldOutline.Fill = ShieldIdleBrush;
        }
    }

    // ── Комета / кольца / glow / сонар ────────────────────────────────────────────────

    /// <summary>
    /// Свет кометы. Она НЕ дуга поверх кольца, а само активное кольцо, окрашенное по углу: тот же
    /// диаметр, та же толщина, тот же центр (см. <see cref="ApplyHeroGeometry"/> и
    /// <see cref="CometRing"/>). Поэтому здесь только «зажечь/погасить» — никакой второй геометрии.
    /// Вращение ровное с первого кадра: motion.md требует «оборот 1500 мс, линейно», а разгон был бы
    /// разрывом скорости. Из покоя комета ПРОЯВЛЯЕТСЯ по прозрачности (220 мс), а не набегает.
    /// В lite/reduced-motion кометы нет вовсе — состояние читается пилюлей «Подключаемся…».
    /// </summary>
    private void SetArc(bool on, bool windUp = false)
    {
        var live = on && !ReducedMotion;
        Comet.IsVisible = live;

        if (live && !MotionSuppressed)
        {
            //  Свежий вход — проявляем из нуля; re-apply/восстановление — сразу на полной яркости.
            _arcOpacity.Duration = TimeSpan.FromMilliseconds(windUp ? 220 : 0);
            _arcOpacity.Easing = EaseStandard;
            if (windUp)
            {
                Comet.Opacity = 0;
            }

            Comet.Classes.Add("spinning");
            Dispatcher.UIThread.Post(
                () =>
                {
                    if (_visualState == ConnectVisualState.Connecting && Comet.IsVisible)
                    {
                        Comet.Opacity = 1;
                    }
                },
                DispatcherPriority.Background);
        }
        else
        {
            Comet.Classes.Remove("spinning");
            Comet.Opacity = 1;
        }
    }

    //  Comet dissolve (P0-2): на Connecting→Connected НЕ гасим комету мгновенно (IsVisible=false на
    //  кадре payoff = моргание), а растворяем Opacity 1→0 (220мс Standard) ОДНОВРЕМЕННО с сонаром —
    //  свет «стекает» в glow, продолжая бежать по кольцу. IsVisible=false выставляем ПОСЛЕ фейда.
    private void DissolveArc()
    {
        if (!Comet.IsVisible)
        {
            return;
        }

        _arcOpacity.Duration = TimeSpan.FromMilliseconds(220);
        _arcOpacity.Easing = EaseStandard;
        Comet.Opacity = 0; // .spinning остаётся → растворяется в движении

        DispatcherTimer.RunOnce(
            () =>
            {
                //  Финализируем только если всё ещё connected (не откатились обратно в connecting).
                if (_visualState != ConnectVisualState.Connected)
                {
                    return;
                }

                Comet.Classes.Remove("spinning");
                Comet.IsVisible = false;
                Comet.Opacity = 1; // сброс для следующего connect
            },
            TimeSpan.FromMilliseconds(220));
    }

    //  Connecting-«дыхание» щита: спокойный вторичный сигнал в унисон с glow-breathe (те же 850мс
    //  sine). Только OPACITY (1↔0.8) на контур-щите — БЕЗ transform, поэтому центрировать нечего и
    //  «улететь» физически нельзя. Reduced-motion/lite: класс не вешаем → щит статичен (дуга/сигнал
    //  подключения остаются читаемыми и без движения).
    private void SetShieldPulse(bool on)
    {
        if (on && !ReducedMotion && !MotionSuppressed)
        {
            ShieldOutline.Classes.Add("shieldbreathe");
        }
        else
        {
            ShieldOutline.Classes.Remove("shieldbreathe");
        }
    }

    // ── Ambient «живой» слой (idle + connected): дышащее кольцо + покойная сонар-волна ──────
    //  Медленные (5-6с) низкоконтрастные петли вокруг щита, чтобы герой «дышал» и в покое, и в
    //  подключении. Присутствует ТОЛЬКО в Idle и Connected (Connecting уже ведёт дугу+breathe-glow,
    //  Error статичен). Тот же guarded-паттерн, что SetArc/SetGlow/SetShieldPulse: сперва снимаем
    //  любую идущую петлю (никогда не стекаем), затем навешиваем нужную — и только если движение
    //  разрешено (не lite/reduced-motion), окно не на паузе (скрыто/свёрнуто) и не онбординг.
    private void SetAmbient(ConnectVisualState state)
    {
        //  Всегда снимаем ПЕРЕД условным добавлением → петли не стекают и не утекают.
        RemoveAmbientLoops();

        //  АМБИЕНТ ВЫКЛЮЧЕН НАСОВСЕМ (решение владельца). Два слоя — «дышащее» кольцо и волна —
        //  жили ПОВЕРХ трёх статических колец и в фазе роста вылезали ЗА кадр: в покое вместо трёх
        //  колец было видно четыре (замер по кадру: радиусы 120·115·108·97 при кадре 106), а в
        //  состоянии «подключено» кольцо на глазах «вылетало» из кнопки. Владелец: «убери пульсар
        //  мигающий, чтобы кольцо не вылетало, смысла нет от этой анимации».
        //  Слои оставлены в разметке (удалять нечего — это не мусор, а выключенная возможность),
        //  но не показываются и петель не заводят: компоновщик их больше не тикает вовсе.
        const bool show = false;

        AmbientRing.IsVisible = show;
        AmbientSonar.IsVisible = show;

        //  Пауза окна: держим слой без петли (base Opacity=0 → невидим); вернётся на восстановлении
        //  через re-apply (UpdateVisibilityPause → SetConnectState → сюда с _animationsPaused=false).
        if (!show || MotionSuppressed)
        {
            return;
        }

    }

    //  Снять все ambient-петли (обе фазы обоих слоёв). Идемпотентно — из SetAmbient, паузы окна,
    //  lite-тумблера и онбординга. Классы сняты → свойства ревертят к базе (Opacity=0, scale=1).
    private void RemoveAmbientLoops()
    {
        AmbientRing.Classes.Remove("breathe-idle");
        AmbientRing.Classes.Remove("breathe-live");
        AmbientSonar.Classes.Remove("rest-idle");
        AmbientSonar.Classes.Remove("rest-live");
    }

    private void SetGlow(bool connecting, bool connected)
    {
        GlowHalo.Classes.Remove("breathing");
        if (connecting)
        {
            //  Reduced-motion: halo не дышит — дуга остаётся единственным сигналом.
            if (ReducedMotion)
            {
                GlowHalo.Opacity = 0;
                return;
            }

            GlowHalo.IsVisible = true;
            GlowHalo.Opacity = 0.6; //  база под «дыханием» (0.3↔0.6) → плавный хэндофф к reveal
            //  Пауза (окно скрыто ИЛИ раскладка неактивна): держим статичный halo без петли — она
            //  вернётся на восстановлении/реактивации.
            if (!MotionSuppressed)
            {
                GlowHalo.Classes.Add("breathing");
            }
        }
        else if (connected)
        {
            GlowHalo.IsVisible = true;
            GlowHalo.Opacity = 1; //  reveal через переход (OutQuint, 300мс)
        }
        else
        {
            GlowHalo.Opacity = 0; //  hide через переход (Standard, 225мс); остаётся, но невидим
        }
    }

    //  Сколько живёт пинг: Emphasis (600) плюс запас на оборот диспетчера, которым навешивается класс.
    private static readonly TimeSpan SonarLifetime = Motion.Dur.Emphasis + TimeSpan.FromMilliseconds(100);

    private void PlaySonar()
    {
        //  Подтверждение подключения, единственный 600-мс момент продукта (master plan 5.3.6): ОДНО
        //  кольцо от активного кольца, 1.0→1.35 и α 0.6→0 за Emphasis, OutQuint, ОДИН раз на
        //  подключение, без повторов и без второго кольца. Класс снимаем и вешаем на следующем цикле
        //  диспетчера, чтобы одноразовая анимация чисто перезапускалась.
        SonarPulse.Classes.Remove("pulsing");
        SonarPulse.IsVisible = true;
        var run = ++_sonarRun;
        Dispatcher.UIThread.Post(
            () =>
            {
                if (run == _sonarRun && _visualState == ConnectVisualState.Connected)
                {
                    SonarPulse.Classes.Add("pulsing");
                }
            },
            DispatcherPriority.Background);

        //  Отыгравший пинг убирает себя сам: одноразовому классу незачем висеть на кольце до
        //  следующего отключения. К концу пинга кольцо уже прозрачно, поэтому снятие класса и
        //  скрытие на экране ничего не меняют, а часы анимаций после этого пусты.
        DispatcherTimer.RunOnce(
            () =>
            {
                if (run == _sonarRun)
                {
                    HideSonar();
                }
            },
            SonarLifetime);
    }

    //  Номер текущего запуска сонара. HideSonar его сдвигает, и отложенные старты уже снятого запуска
    //  ничего не делают. Без этого на каждом подключении выходило так: презентер получает ДВА
    //  изменения подряд (IsConnected, затем IsConnecting=false), первое запускает сонар, второе —
    //  повторное применение того же состояния — сразу прячет его, а отложенные старты срабатывали
    //  уже на скрытых кольцах. Анимация на невидимом элементе не доигрывает, стоит на паузе и держит
    //  часы анимаций: окно перерисовывалось 60 раз в секунду всё время, пока VPN подключён, —
    //  около 7% ядра на пустом месте (замер под Xvfb; в облегчённом режиме, где сонара нет, — 1%).
    private int _sonarRun;

    private void HideSonar()
    {
        _sonarRun++;
        SonarPulse.Classes.Remove("pulsing");
        SonarPulse.IsVisible = false;
    }

    //  Connect-bloom (P1-1): диск «приземляется» — 1.0→1.04 (180мс) → 1.04→1.0 (260мс), ОБЕ ноги
    //  OutQuint. Это оседание, НЕ bounce: пик ≤1.04, ниже покоя НЕ уходит, кривой elastic нет. Реюзает
    //  тот же self-centering _discScale, поэтому центр гарантирован. Второй leg (заканчивается ~440мс)
    //  оседает уже поверх полного glow (reveal 300мс) — в бюджете Emphasis (600мс).
    private void PlayConnectBloom() => PlayDiscSettle(1.04, 180, 260);

    //  Error-contract (P1-3): один спокойный «вдав» 1.0→0.98→1.0 (150+150мс OutQuint) — тихое
    //  подтверждение «связь сорвалась», без shake/bounce. Пик 0.98, без осцилляции.
    private void PlayErrorContract() => PlayDiscSettle(0.98, 150, 150);

    //  Двух-ногое оседание диска через press-scale переходы. Вызывающий уже проверил движение и не-
    //  suppressed; здесь ещё раз страхуемся от ReducedMotion. Если во время оседания начали press —
    //  прекращаем (press-scale перехватит), чтобы не бороться за _discScale.
    private async void PlayDiscSettle(double peak, int leg1Ms, int leg2Ms)
    {
        if (ReducedMotion || _pressing)
        {
            return;
        }

        try
        {
            _discScaleX.Easing = _discScaleY.Easing = EaseOutQuint;
            _discScaleX.Duration = _discScaleY.Duration = TimeSpan.FromMilliseconds(leg1Ms);
            _discScale.ScaleX = _discScale.ScaleY = peak;

            await Task.Delay(leg1Ms);
            if (_pressing)
            {
                return; // press взял управление диском — не дёргаем обратно
            }

            _discScaleX.Duration = _discScaleY.Duration = TimeSpan.FromMilliseconds(leg2Ms);
            _discScale.ScaleX = _discScale.ScaleY = 1.0;
        }
        catch
        {
            //  Оседание — чистая косметика; любая гонка/отсоединение не должны падать в connect-путь.
        }
    }

    // ── Cold-start «сборка» героя (ОДИН раз за процесс) ─────────────────────────────────
    private async void OnFirstLoaded(object? sender, RoutedEventArgs e)
    {
        Loaded -= OnFirstLoaded;
        if (_assembled || ReducedMotion)
        {
            EnsureHeroVisible(); //  сборку пропускаем — гарантируем видимость
            return;
        }

        _assembled = true;
        try
        {
            HeroFrame.Classes.Add("assembling");
            await Task.Delay(460);
        }
        finally
        {
            //  ВСЕГДА возвращаем видимость (даже если задержка/анимация прервана исключением) —
            //  щит физически не может остаться пустым.
            EnsureHeroVisible();
        }
    }

    //  Единая точка восстановления покоя героя: полная непрозрачность + снятие one-shot-класса
    //  «сборки». Идемпотентна — безопасно вызывать из Loaded, finally и страховочного таймера.
    private void EnsureHeroVisible()
    {
        HeroFrame.Classes.Remove("assembling");
        if (HeroFrame.Opacity < 1)
        {
            HeroFrame.Opacity = 1;
        }
    }

    // ── «Облегчённый режим» (lite): общий флаг reduced-motion из конфига ──────────────────
    private static bool LiteModeEnabled()
    {
        //  В дизайн-режиме конфига/движка нет — считаем режим выключенным.
        if (Design.IsDesignMode)
        {
            return false;
        }

        try
        {
            return AppManager.Instance.Config.UiItem.LiteMode;
        }
        catch
        {
            return false;
        }
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
}
