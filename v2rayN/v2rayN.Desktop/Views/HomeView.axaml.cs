using Avalonia.Animation;
using Avalonia.Animation.Easings;
using Avalonia.Interactivity;
using Avalonia.VisualTree;
using v2rayN.Desktop.Common;
using v2rayN.Desktop.ViewModels;

namespace v2rayN.Desktop.Views;

/// <summary>
/// Главная — двухпанельная (широкая раскладка). Левая колонка (чип аккаунта сверху + единый список
/// подписок→серверов) биндится к реальному <see cref="HomeViewModel"/> через наследуемый DataContext;
/// правая (кольцо со щитом) связывается общим <see cref="HomeHeroPresenter"/> — той же проводкой, что
/// использует компактная раскладка, поэтому connect-состояние идентично при любой ширине окна.
///
/// Здесь же живут две вещи уровня ЭКРАНА, а не панели:
///   • «+» в правом верхнем углу ОКНА с меню добавления (screens.md «Главная»). Раньше «+» сидел в
///     углу connect-панели (<see cref="ConnectHeroView"/>) — то есть в правой половине, а не в углу
///     окна; здесь он лежит поверх обеих колонок, как в прототипе. Угловой «+» героя поэтому гасится
///     (<see cref="ConnectHeroView.SetCornerAddVisible"/>), чтобы не было двух кнопок.
///   • тост подтверждения (<see cref="HomeToast"/>) — его поднимают действия карточки подписки,
///     лежащие глубоко в списке, а рисуется он по низу экрана.
///
/// Чип аккаунта — общий <see cref="HomeAccountChip"/> (сам показывает/прячет себя по
/// <see cref="Account.AccountSession"/>); его тап здесь превращается в открытие вкладки «Аккаунт»
/// через кнопку рейла (тот же путь, что и раньше).
/// </summary>
public partial class HomeView : ReactiveUserControl<HomeViewModel>
{
    //  Кривые/длительности — единый каталог Motion (зеркало Ease.*/Dur.* из GlobalResources).
    private static readonly Easing EaseOutQuart = Motion.Ease.OutQuart;

    //  Меню добавления: срез сверху вниз 260 мс + прозрачность 180 мс (motion.md «Окошко у значения»).
    //  МАСШТАБ НЕ ИСПОЛЬЗУЕМ — от него дёргается текст внутри.
    private static readonly TimeSpan MenuClipMs = TimeSpan.FromMilliseconds(260);
    private static readonly TimeSpan MenuFadeMs = TimeSpan.FromMilliseconds(180);

    //  Каретка «+» доворачивается на 45° за 300 мс (screens.md).
    private static readonly TimeSpan GlyphSpinMs = TimeSpan.FromMilliseconds(300);

    //  Тост: выезжает снизу 280 мс, уходит сам через ~3 с (motion.md «Тост»).
    private static readonly TimeSpan ToastInMs = TimeSpan.FromMilliseconds(280);
    private static readonly TimeSpan ToastHold = TimeSpan.FromSeconds(3);

    private IDisposable? _heroBinding;
    private bool _attached;

    private readonly RotateTransform _glyphRotate = new() { Angle = 0 };
    private readonly TranslateTransform _toastSlide = new() { Y = 0 };
    private bool _menuOpen;
    private double _menuHeight;
    private IDisposable? _toastTimer;

    //  Окно, на котором висят глобальные обработчики закрытия меню (клик мимо / Escape).
    //  Держим ссылку, а не IDisposable-обёртку: снимать надо ровно те же обработчики с того же
    //  TopLevel, даже если вид к этому моменту уже открепился от дерева.
    private TopLevel? _dismissHost;

    public HomeView()
    {
        InitializeComponent();

        // ── Account chip tap → open the Account tab (rail button, unchanged path) ──
        //  Independent of the ViewModel, so wire it once, unconditionally.
        AccountChip.AccountRequested += (_, _) => OpenAccountTab();

        //  Та же дверь из приветственной карточки: у нового человека подписки ещё нет, и первый
        //  осмысленный шаг — не «добавить сервер», а купить её во вкладке «Аккаунт».
        ConnectHero.AccountRequested += (_, _) => OpenAccountTab();

        // ── «+» и его меню ────────────────────────────────────────────────────────────
        AddGlyph.RenderTransform = _glyphRotate;
        _glyphRotate.Transitions = new Transitions
        {
            new DoubleTransition { Property = RotateTransform.AngleProperty, Duration = GlyphSpinMs, Easing = EaseOutQuart },
        };
        //  Срез: высота меню 0 ↔ измеренная, при ClipToBounds это ровно inset(0 0 100% 0) из прототипа —
        //  нижняя кромка (вместе с рамкой) уезжает вниз, содержимое не масштабируется.
        AddMenu.ClipToBounds = true;
        AddMenu.Height = 0;
        AddMenu.Transitions = new Transitions
        {
            new DoubleTransition { Property = HeightProperty, Duration = MenuClipMs, Easing = EaseOutQuart },
            new DoubleTransition { Property = OpacityProperty, Duration = MenuFadeMs, Easing = EaseOutQuart },
        };

        // ── Тост ──────────────────────────────────────────────────────────────────────
        Toast.RenderTransform = _toastSlide;
        _toastSlide.Transitions = new Transitions
        {
            new DoubleTransition { Property = TranslateTransform.YProperty, Duration = ToastInMs, Easing = EaseOutQuart },
        };
        Toast.Transitions = new Transitions
        {
            new DoubleTransition { Property = OpacityProperty, Duration = ToastInMs, Easing = EaseOutQuart },
        };

        // ── Connect-hero binding: mirror CompactHomeView EXACTLY (attach + DataContext driven) ──────
        //  The old wiring lived inside ReactiveUI `WhenActivated`, whose Avalonia activation for a
        //  Control is raised off the `Loaded`/`Unloaded` events (AvaloniaActivationForViewFetcher.
        //  GetActivationForControl). In this keep-alive shell the wide Home is a permanent child whose
        //  ancestor `bodyRoot` starts IsVisible=false and is toggled by CrossfadeShellTo, and whose own
        //  visibility is driven by Opacity — so its `Loaded` activation was not reliably raised/kept,
        //  and the hero binding (shield tap, empty/connect state) never got wired → the widescreen
        //  shield was dead. CompactHomeView never had this problem because it binds on
        //  `AttachedToVisualTree` + `DataContextChanged`, both of which fire independent of
        //  layout/visibility. We now do the same here: bind whenever this view is attached AND the host
        //  has assigned the shared HomeViewModel as DataContext, tearing down on detach. The host
        //  (MainWindow.BindActiveHome) assigns the VM to ONLY the active-layout Home, so exactly one
        //  Home holds the live pipeline — identical, reliable connect behaviour at any width.
        DataContextChanged += (_, _) => BindHero();
        AttachedToVisualTree += (_, _) =>
        {
            _attached = true;
            //  «+» живёт в углу ОКНА (здесь), поэтому угловой «+» самого героя не нужен —
            //  иначе на широкой раскладке было бы две одинаковые кнопки добавления.
            ConnectHero.SetCornerAddVisible(false);
            HomeToast.Requested += OnToastRequested;
            BindHero();
        };
        DetachedFromVisualTree += (_, _) =>
        {
            _attached = false;
            HomeToast.Requested -= OnToastRequested;
            CloseAddMenu(instant: true);
            DisposeBinding();
        };
    }

    // (Re)create the connect-hero binding for the current DataContext; a null/foreign DataContext
    // (the host unbinds the INACTIVE layout to release its rows) leaves the hero unbound.
    private void BindHero()
    {
        DisposeBinding();
        if (_attached && DataContext is HomeViewModel vm)
        {
            _heroBinding = HomeHeroPresenter.Bind(ConnectHero, vm);
        }
    }

    private void DisposeBinding()
    {
        _heroBinding?.Dispose();
        _heroBinding = null;
    }

    // Chip tap → open the Account tab: raise a click on the nav-rail's «Аккаунт» button (the same
    // path the rail uses). Read-only reach into the host window; no MainWindow edits.
    private void OpenAccountTab()
    {
        var nav = TopLevel.GetTopLevel(this)?
            .GetVisualDescendants()
            .OfType<Button>()
            .FirstOrDefault(b => b.Name == "navAccount");
        nav?.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
    }

    #region «+» и меню добавления

    private void OnAddButtonClick(object? sender, RoutedEventArgs e)
    {
        if (_menuOpen)
        {
            CloseAddMenu();
        }
        else
        {
            OpenAddMenu();
        }
    }

    private void OpenAddMenu()
    {
        //  Натуральную высоту меряем один раз и лениво: до первого раскрытия меню имеет Height=0,
        //  поэтому DesiredSize у него нулевой, а анимировать «в авто» Avalonia не умеет.
        //  ВЫЧИТАЕМ ОТСТУП. DesiredSize включает собственный Margin элемента, а у меню он «0,42,0,0»
        //  (тот самый сдвиг под кнопку). Без вычитания меню открывалось на 42 выше нужного: снизу
        //  оставалась пустая полоса подложки в высоту отступа — на живом окне это видно сразу
        //  (два пункта × 38 + паддинг 12 = 88, а раскрывалось 130).
        if (_menuHeight <= 0)
        {
            AddMenu.Height = double.NaN;
            AddMenu.Measure(Size.Infinity);
            _menuHeight = Math.Max(0, AddMenu.DesiredSize.Height - AddMenu.Margin.Top - AddMenu.Margin.Bottom);
            AddMenu.Height = 0;
        }

        _menuOpen = true;
        AddMenu.IsHitTestVisible = true;
        AddMenu.Height = _menuHeight;
        AddMenu.Opacity = 1;
        _glyphRotate.Angle = 45;

        //  Закрытие по клику мимо и по Escape: подписка живёт ровно столько, сколько меню открыто.
        //  Tunnel — чтобы поймать нажатие раньше, чем его обработает контрол под ним.
        if (TopLevel.GetTopLevel(this) is { } top)
        {
            _dismissHost = top;
            top.AddHandler(PointerPressedEvent, OnGlobalPointerPressed, RoutingStrategies.Tunnel);
            top.AddHandler(KeyDownEvent, OnGlobalKeyDown, RoutingStrategies.Tunnel);
        }
    }

    private void CloseAddMenu(bool instant = false)
    {
        if (_dismissHost is { } host)
        {
            host.RemoveHandler(PointerPressedEvent, OnGlobalPointerPressed);
            host.RemoveHandler(KeyDownEvent, OnGlobalKeyDown);
            _dismissHost = null;
        }

        if (!_menuOpen && !instant)
        {
            return;
        }

        _menuOpen = false;
        AddMenu.IsHitTestVisible = false;
        AddMenu.Height = 0;
        AddMenu.Opacity = 0;
        _glyphRotate.Angle = 0;
    }

    private void OnGlobalPointerPressed(object? sender, PointerPressedEventArgs e)
    {
        //  Клик по самой кнопке обрабатывает её Click (он же переключает меню) — здесь только «мимо».
        if (e.Source is Visual v && (v == AddButton || v.FindAncestorOfType<Button>() == AddButton))
        {
            return;
        }
        if (e.Source is Visual inside && inside.FindAncestorOfType<Border>() is { } b && IsInsideMenu(b))
        {
            return;
        }

        CloseAddMenu();
    }

    private bool IsInsideMenu(Visual v)
    {
        for (Visual? cur = v; cur is not null; cur = cur.GetVisualParent())
        {
            if (ReferenceEquals(cur, AddMenu))
            {
                return true;
            }
        }
        return false;
    }

    private void OnGlobalKeyDown(object? sender, KeyEventArgs e)
    {
        if (e.Key == Key.Escape)
        {
            CloseAddMenu();
        }
    }

    private void OnAddClipboard(object? sender, PointerReleasedEventArgs e)
    {
        CloseAddMenu();
        if (DataContext is HomeViewModel vm)
        {
            _ = vm.AddViaClipboard();
        }
    }

    private void OnAddQr(object? sender, PointerReleasedEventArgs e)
    {
        CloseAddMenu();
        if (DataContext is HomeViewModel vm)
        {
            _ = vm.AddViaQr();
        }
    }

    #endregion «+» и меню добавления

    #region Тост

    private void OnToastRequested(object? sender, string text)
    {
        if (text.IsNullOrEmpty())
        {
            return;
        }

        _toastTimer?.Dispose();
        ToastText.Text = text;
        Toast.IsVisible = true;

        //  Выезд снизу: старт на 16 ниже конечного положения, затем к нулю вместе с прозрачностью.
        _toastSlide.Y = 16;
        Toast.Opacity = 0;
        Dispatcher.UIThread.Post(
            () =>
            {
                _toastSlide.Y = 0;
                Toast.Opacity = 1;
            },
            DispatcherPriority.Background);

        _toastTimer = DispatcherTimer.RunOnce(
            () =>
            {
                Toast.Opacity = 0;
                _toastSlide.Y = 16;
                DispatcherTimer.RunOnce(() => Toast.IsVisible = Toast.Opacity > 0, ToastInMs);
            },
            ToastHold);
    }

    #endregion Тост
}

/// <summary>
/// Мост «действие → подтверждение». Пинг и обновление подписки живут в карточке подписки
/// (<see cref="SubscriptionMetaView"/>), которая лежит глубоко внутри списка и про низ экрана
/// ничего не знает; тост же обязан всплывать по низу окна. Прямой вызов вверх по дереву был бы
/// хрупким (в компактной раскладке над карточкой нет <see cref="HomeView"/>), поэтому связь
/// событийная: кто рисует тост — тот и подписывается, а без подписчика вызов просто ничего не делает.
///
/// Это НЕ общий канал уведомлений: сюда ходят ровно два подтверждения из motion.md
/// («Задержка обновлена», «Подписка обновлена · N серверов»), инициированные явным нажатием.
/// </summary>
public static class HomeToast
{
    public static event EventHandler<string>? Requested;

    public static void Show(string text) => Requested?.Invoke(null, text);
}
