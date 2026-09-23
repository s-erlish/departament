using Avalonia.Animation;
using Avalonia.Layout;
using v2rayN.Desktop.Common;
using v2rayN.Desktop.ViewModels;

namespace v2rayN.Desktop.Views;

/// <summary>
/// Вкладка «Аккаунт»: семь полос из screens.md — профиль, кольцо трафика с блоком тарифа, пара
/// кнопок «Пополнить»/«Продлить», автопродление, «Управление», «Способы входа» и «Выйти из аккаунта».
///
/// Code-behind держит ровно три вещи: (1) навигацию наружу событиями, чтобы MainWindow не менялся;
/// (2) чип баланса, который открывает ТОТ ЖЕ флайаут пополнения, что и кнопка «Пополнить» (одна форма,
/// два якоря); (3) появление полосы 2 при активации вкладки. Всё остальное — биндинги к
/// <see cref="AccountViewModel"/>.
///
/// Прогиба строк здесь СОЗНАТЕЛЬНО нет: строки живут на общем лекале (Border.SubRow), из которого
/// press-scale был убран после реального дефекта — строка уезжала из-под курсора и жест Tapped
/// отменялся, тап срабатывал через раз (коммит «Settings rows: remove press-scale»).
/// </summary>
public partial class AccountView : UserControl
{
    /// <summary>Строка «Купить подписку» / CTA карточки — хост открывает Buy.</summary>
    public event EventHandler? BuyRequested;

    /// <summary>Строка «Устройства» — хост открывает Devices.</summary>
    public event EventHandler? DevicesRequested;

    /// <summary>Строка «История платежей» — хост открывает History.</summary>
    public event EventHandler? HistoryRequested;

    /// <summary>CTA входа (logged-out) — хост открывает суб-страницу «Вход».</summary>
    public event EventHandler? LoginRequested;

    /// <summary>
    /// Действие строки «Почта» в «Способах входа» — хост открывает суб-страницу поручения. Какое
    /// именно поручение, решает состояние аккаунта: адреса нет — привязать, адрес есть, а пароля нет —
    /// задать пароль, есть и то и другое — сменить адрес.
    /// </summary>
    public event EventHandler<EmailErrand>? EmailErrandRequested;

    /// <summary>
    /// CTA «Войти через Telegram» на гейте входа (logged-out).
    ///
    /// Ведёт РОВНО туда же, куда та же кнопка начального экрана: открывает экран прогрузки
    /// (<see cref="AccountSyncView.OpenFlow"/>), и он же запускает авторизацию, ждёт подтверждения
    /// на шаге 0 и доводит до собранной «Главной». Одна дверь — один экран.
    ///
    /// Раньше отсюда открывалась суб-страница «Вход» с собственным экраном ожидания («Ожидаем
    /// подтверждения в Telegram»). Владелец её убрал: два разных ожидания на одно действие, причём
    /// второе — по старому лекалу. Ещё раньше кнопка была привязана прямо к <c>LoginTelegramCmd</c>:
    /// авторизация уходила в фон, кадр не менялся вовсе, а опрос тикал до трёх минут за спиной у
    /// экрана, который о нём молчал. Команду сюда дублировать нельзя: <c>OpenFlow</c> выполняет её
    /// сам, а второй запуск отменил бы первый вместе с уже выданным deep link.
    /// </summary>
    private void OnLoginTelegram()
    {
        if (AccountSyncView.OpenFlow(this, AccountSyncView.FlowKind.Telegram, null, driveLogin: true) is not null)
        {
            return;
        }
        // Хоста нет (превью/дизайн-режим) — по крайней мере не молчим: тот же путь, что и у прочих CTA.
        LoginRequested?.Invoke(this, EventArgs.Empty);
    }

    private AccountViewModel? _vm;

    /// <summary>
    /// Одно действие строки почты — три поручения. Выбор делается по ПРОФИЛЮ, а не по надписи на
    /// кнопке: надпись и выбор считаются из одного и того же состояния, поэтому разойтись не могут.
    /// </summary>
    private void OnEmailAction()
    {
        if (DataContext is not AccountViewModel vm)
        {
            return;
        }
        var errand = !vm.EmailLinked ? EmailErrand.Link
            : !vm.EmailHasPassword ? EmailErrand.SetPassword
            : EmailErrand.Change;
        EmailErrandRequested?.Invoke(this, errand);
    }

    public AccountView()
    {
        InitializeComponent();

        // Только для превьювера: в рантайме общий VM приходит от MainWindow.
        if (Design.IsDesignMode)
        {
            DataContext = AccountViewModel.CreateDesign();
        }

        BuyRow.Tapped += (_, _) => BuyRequested?.Invoke(this, EventArgs.Empty);
        // «Купить подписку» из пустого состояния ведёт ТУДА ЖЕ, куда строка «Управление» ниже —
        // одно действие, одна дверь: два разных пути в каталог разошлись бы при первой же правке.
        EmptyBuyButton.Click += (_, _) => BuyRequested?.Invoke(this, EventArgs.Empty);
        DevicesRow.Tapped += (_, _) => DevicesRequested?.Invoke(this, EventArgs.Empty);
        HistoryRow.Tapped += (_, _) => HistoryRequested?.Invoke(this, EventArgs.Empty);
        LoginTelegramButton.Click += (_, _) => OnLoginTelegram();
        LoginSiteButton.Click += (_, _) => LoginRequested?.Invoke(this, EventArgs.Empty);
        EmailActionButton.Click += (_, _) => OnEmailAction();
        LogoutRow.Tapped += (_, _) => (DataContext as AccountViewModel)?.LogoutCmd.Execute().Subscribe();

        // Чип баланса — второй якорь того же флайаута пополнения. Второй Flyout здесь завёл бы
        // вторую форму (и второй набор состояний валидации) для одного и того же действия.
        BalanceChip.Click += (_, _) => TopUpButton.Flyout?.ShowAt(BalanceChip);

        DataContextChanged += (_, _) => HookVm();
        HookVm();

        // Полоса 2 складывается в столбик, когда колонка перестаёт вмещать кольцо и блок рядом.
        this.GetObservable(BoundsProperty).Subscribe(b => ApplyBandLayout(b.Width));
    }

    // ==================== узкая раскладка полосы 2 ====================

    //  Ширина КОЛОНКИ (не окна): gutter 20 с двух сторон, потолок 720. Кольцо 164 + зазор 32 +
    //  минимальная ширина блока тарифа 260 = 456 — ниже этого блок пришлось бы сжимать, поэтому
    //  кольцо уезжает НАД блоком, а сам блок центрируется (узкий кадр прототипа).
    private const double BandStackBelow = 456.0;

    private bool _bandStacked;
    private bool _bandLayoutSeeded;

    private void ApplyBandLayout(double viewWidth)
    {
        if (viewWidth <= 0)
        {
            return;
        }

        var column = Math.Min(720.0, viewWidth - 40.0);
        var stacked = column < BandStackBelow;
        if (_bandLayoutSeeded && stacked == _bandStacked)
        {
            return;
        }

        _bandStacked = stacked;
        _bandLayoutSeeded = true;

        if (stacked)
        {
            PlanBand.ColumnDefinitions = new ColumnDefinitions("*");
            PlanBand.RowDefinitions = new RowDefinitions("Auto,Auto");
            Grid.SetColumn(PlanBlock, 0);
            Grid.SetRow(PlanBlock, 1);
            TrafficRing.HorizontalAlignment = HorizontalAlignment.Center;
            PlanBlock.Margin = new Thickness(0, 18, 0, 0);
            PlanTitle.TextAlignment = TextAlignment.Center;
            PlanMetaRow.HorizontalAlignment = HorizontalAlignment.Center;
            // Силуэт первой загрузки живёт по тем же правилам, что и блок, который он замещает:
            // иначе в столбике полосы скелета липли бы к левому краю под центрированным кольцом.
            SetSkeletonAlignment(HorizontalAlignment.Center);
        }
        else
        {
            PlanBand.RowDefinitions = new RowDefinitions("*");
            PlanBand.ColumnDefinitions = new ColumnDefinitions("Auto,*");
            Grid.SetRow(PlanBlock, 0);
            Grid.SetColumn(PlanBlock, 1);
            TrafficRing.HorizontalAlignment = HorizontalAlignment.Left;
            PlanBlock.Margin = new Thickness(32, 0, 0, 0);
            PlanTitle.TextAlignment = TextAlignment.Left;
            PlanMetaRow.HorizontalAlignment = HorizontalAlignment.Left;
            SetSkeletonAlignment(HorizontalAlignment.Left);
        }
    }

    //  Полосы силуэта равняются так же, как настоящие строки блока (кнопочная пара — всегда во всю
    //  ширину, её выравнивание задаёт сама сетка).
    private void SetSkeletonAlignment(HorizontalAlignment align)
    {
        foreach (var child in PlanSkeleton.Children)
        {
            if (child is Border bar)
            {
                bar.HorizontalAlignment = align;
            }
        }
    }

    // ==================== VM wiring ====================

    private void HookVm()
    {
        if (_vm != null)
        {
            _vm.BuyIntentRequested -= OnBuyIntent;
            _vm.DevicesIntentRequested -= OnDevicesIntent;
            _vm.TopUpCheckoutOpened -= OnTopUpCheckoutOpened;
        }

        _vm = DataContext as AccountViewModel;
        if (_vm is null)
        {
            return;
        }

        // Флайаут пополнения закрывается ТОЛЬКО при успехе (чекаут открылся): невалидная сумма
        // оставляет его открытым, чтобы показать инлайн-ошибку.
        _vm.TopUpCheckoutOpened += OnTopUpCheckoutOpened;
        // Карточка подписки может сама попроситься в Buy/Devices (например «Продлить» без тарифа).
        _vm.BuyIntentRequested += OnBuyIntent;
        _vm.DevicesIntentRequested += OnDevicesIntent;
    }

    private void OnTopUpCheckoutOpened(object? sender, EventArgs e) => TopUpButton.Flyout?.Hide();

    private void OnBuyIntent(object? sender, EventArgs e) => BuyRequested?.Invoke(this, EventArgs.Empty);

    private void OnDevicesIntent(object? sender, EventArgs e) => DevicesRequested?.Invoke(this, EventArgs.Empty);

    // ==================== появление полосы 2 ====================

    protected override void OnPropertyChanged(AvaloniaPropertyChangedEventArgs change)
    {
        base.OnPropertyChanged(change);
        // MainWindow помечает ровно одну keep-alive вкладку hit-test'абельной при свопе: переход
        // false→true = «эта вкладка стала активной» → проигрываем появление кольца и блока тарифа.
        if (change.Property == IsHitTestVisibleProperty
            && change.GetNewValue<bool>()
            && !change.GetOldValue<bool>())
        {
            PlayEntrance();
        }
    }

    // Кольцо «распускается» из 0.94 (bloomIn прототипа), блок тарифа приезжает снизу на +8 с
    // задержкой в один стаггер-шаг. Под «Облегчённым режимом» не запускаем вовсе — полоса просто есть.
    private void PlayEntrance()
    {
        if (MotionState.IsLite)
        {
            return;
        }

        if (TrafficRing is not null)
        {
            var bloom = new Animation
            {
                Duration = TimeSpan.FromMilliseconds(560),
                Easing = Motion.Ease.OutQuint,
                FillMode = FillMode.Both,
                Children =
                {
                    new KeyFrame
                    {
                        Cue = new Cue(0d),
                        Setters =
                        {
                            new Setter(OpacityProperty, 0d),
                            new Setter(ScaleTransform.ScaleXProperty, 0.94d),
                            new Setter(ScaleTransform.ScaleYProperty, 0.94d),
                        },
                    },
                    new KeyFrame
                    {
                        Cue = new Cue(1d),
                        Setters =
                        {
                            new Setter(OpacityProperty, 1d),
                            new Setter(ScaleTransform.ScaleXProperty, 1d),
                            new Setter(ScaleTransform.ScaleYProperty, 1d),
                        },
                    },
                },
            };
            _ = bloom.RunAsync(TrafficRing);
        }

        if (PlanBlock is not null)
        {
            var lift = new Animation
            {
                Duration = TimeSpan.FromMilliseconds(460),
                Delay = Motion.Dur.Stagger * 2,
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
            _ = lift.RunAsync(PlanBlock);
        }
    }
}
