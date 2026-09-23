using Avalonia.Animation;
using Avalonia.Media.Transformation;
using departament.Desktop.Common;
using departament.Desktop.ViewModels;

namespace departament.Desktop.Views;

/// <summary>
/// Начальный экран (первый запуск, нет подписок) — screens.md «Начальный экран».
///
/// Навигация скрыта целиком: MainWindow держит эту вью отдельной поверхностью шелла (3-way гейт
/// SYNCING &gt; EMPTY &gt; CONTENT), поэтому ни рейла, ни нижней панели в дереве нет, и колонка
/// центрируется по всему окну.
///
/// Ведёт code-behind:
///   • ПОЯВЛЕНИЕ (motion.md «Появление начального экрана») — щит 620 мс из 0.82×, заголовок
///     460 мс/80 мс, подзаголовок 140 мс, блок кнопок 200 мс. Один раз, затем полная статика.
///   • КАРТОЧКУ НАЙДЕННОЙ ССЫЛКИ — реальная проверка буфера обмена (при показе экрана и при
///     возврате фокуса окну), раскрытие/схлопывание по высоте 340/240 мс.
///   • «ДРУГИЕ СПОСОБЫ» — раскрытие 320/220 мс + поворот каретки.
///   • ДЕЙСТВИЯ — четыре пути добавления доступа, см. <see cref="FlowRequested"/>.
///
/// Всё движение гасится «Облегчённым режимом» (<see cref="MotionState.IsLite"/>): под ним экран
/// сразу отдаётся полностью видимым, раскрытия переключаются мгновенно (переходы обнулены стилями
/// по классу .lite на окне).
/// </summary>
public partial class OnboardingView : UserControl
{
    // ── Моушен появления ─────────────────────────────────────────────────────
    // Появление ведут ПЕРЕХОДЫ (Transitions), а не Animation. Это не стилистический выбор, а
    // единственный работающий способ, проверенный на живом окне:
    //   • Animation с ключевыми кадрами по Visual.RenderTransform падает на первом же кадре —
    //     «No animator registered for the property RenderTransform»;
    //   • Animation по числам самого трансформа (ScaleTransform.ScaleX/Y, TranslateTransform.Y)
    //     падает на Animation.RunAsync — «Unable to cast ScaleTransform to Avalonia.Visual».
    // Оба падения тихие (задача fire-and-forget уходит в UnobservedTaskException), поэтому со
    // стороны это выглядело как «появление просто не играет»: блок мгновенно оказывался на месте.
    // TransformOperationsTransition же работает — на нём стоит вся лестница :pressed в GlobalStyles.
    private static readonly ITransform _lift16 = TransformOperations.Parse("translateY(16px)");
    private static readonly ITransform _lift0 = TransformOperations.Parse("translateY(0px)");
    private static readonly ITransform _scale082 = TransformOperations.Parse("scale(0.82)");
    private static readonly ITransform _scale1 = TransformOperations.Parse("scale(1)");

    // Длительности/задержки — 1:1 из motion.md «Появление начального экрана».
    private static readonly TimeSpan _bloomDuration = TimeSpan.FromMilliseconds(620);
    private static readonly TimeSpan _liftDuration = TimeSpan.FromMilliseconds(460);

    // Отступ блока кнопок: 24 без карточки, 12 с карточкой (карточка приносит свои 24 сверху,
    // иначе разрыв сложился бы в 48 и блок кнопок «отвалился» бы от подзаголовка). Оба — из шкалы
    // отступов; прежние 26 и 14 были подобраны на глаз.
    private static readonly Thickness _actionsGapPlain = new(0, 24, 0, 0);
    private static readonly Thickness _actionsGapWithCard = new(0, 12, 0, 0);

    // Щит на низком окне: 3/4 от 118 освобождают колонке ~30 точек — ровно то, чего не хватало на
    // минимальном окне 420×560 с карточкой найденной ссылки. Темп — тот же, что у раскрытий (340).
    private const double ShieldTightScale = 0.75;
    private static readonly TimeSpan _shieldFitDuration = TimeSpan.FromMilliseconds(340);
    private bool _shieldTight;
    private readonly ScaleTransform _shieldFitScale = new(1, 1);

    private bool _entryPending;
    private bool _moreOpen;
    private bool _clipCardShown;
    private bool _clipboardProbeRunning;

    // Экран прогрузки, который мы подняли сами (см. RaiseFlowOverlay). Пока он на стеке, второй
    // поток не запускаем: второй Push сменил бы содержимое хоста, оторвал первый слой от дерева
    // (и убил его поток), а в стеке осталось бы две страницы — одно «назад» вернуло бы мёртвый слой.
    private AccountSyncView? _flowOverlay;

    /// <summary>Поток добавления подписки, который выбрал пользователь на начальном экране.</summary>
    public enum StartFlow
    {
        /// <summary>«Войти через Telegram» — подписка приезжает вместе с аккаунтом.</summary>
        Telegram,

        /// <summary>«Добавить из буфера обмена» (в том числе тапом по карточке найденной ссылки).</summary>
        Clipboard,
    }

    /// <summary>
    /// Запрос на запуск экрана прогрузки. Поднимается ДО того, как реальная работа началась, и несёт
    /// её задачу: <see cref="AccountSyncView.RunFlow"/> ждёт эту задачу как сигнал «работа сделана» и
    /// доводит хореографию до конца (см. <see cref="AccountSyncView"/>).
    ///
    /// Проводку делает MainWindow (эта вью не может показать оверлей — видимостью поверхностей шелла
    /// владеет он):
    /// <code>
    /// onboardingView.FlowRequested += (_, e) =&gt; { accountSyncView.RunFlow(e.Kind, e.Work); ApplyShellVisibility(); };
    /// </code>
    /// </summary>
    public event EventHandler<StartFlowRequest>? FlowRequested;

    /// <summary>Полезная нагрузка <see cref="FlowRequested"/>: какой поток и какая реальная работа под ним.</summary>
    /// <param name="Kind">Набор текстов экрана прогрузки (Telegram / из буфера).</param>
    /// <param name="Work">
    /// Задача реальной работы. <c>null</c> — работа не наша (вход через Telegram доводит
    /// AccountViewModel), тогда экран прогрузки ждёт сигналов VM.
    /// </param>
    public sealed record StartFlowRequest(StartFlow Kind, Task? Work);

    public OnboardingView()
    {
        InitializeComponent();

        TelegramButton.Click += OnTelegram;
        ClipboardButton.Click += OnClipboard;
        MoreButton.Click += OnToggleMore;
        // По самой карточке обработчика НЕТ: она вывеска. Нажимается только её CTA.
        ClipCardButton.Click += OnClipboard;
        QrRow.PointerReleased += (_, _) => OnQr();
        EmailRow.PointerReleased += (_, _) => OnEmail();

        ClipRevealHost.SizeChanged += OnRevealHostSizeChanged;
        MoreRevealHost.SizeChanged += OnRevealHostSizeChanged;
        PillRevealHost.SizeChanged += OnRevealHostSizeChanged;

        ActionsBlock.Margin = _actionsGapPlain;
        ShieldFit.LayoutTransform = _shieldFitScale;

        // Хватает ли колонке высоты, решает её собственная высота против высоты окна скролла —
        // так решение само следует за шрифтом, языком, масштабом и раскрытиями. Пересчёт — когда
        // меняется любая из двух высот (раскрытие карточки, «Другие способы», размер окна).
        Column.SizeChanged += (_, e) =>
        {
            if (e.HeightChanged)
            {
                UpdateShieldFit();
            }
        };
        Scroll.SizeChanged += (_, e) =>
        {
            if (e.HeightChanged)
            {
                UpdateShieldFit();
            }
        };

        // Пред-скрываем анимируемые блоки, чтобы появление не «вспыхивало» из готового кадра.
        // ТОЛЬКО при включённом движении: под lite/preview/дизайном экран обязан быть виден сразу —
        // появление УЛУЧШАЕТ уже видимый дефолт, а не создаёт его.
        if (!IsReducedMotion())
        {
            ShieldFit.Opacity = 0;
            TitleText.Opacity = 0;
            SubtitleText.Opacity = 0;
            ActionsBlock.Opacity = 0;
            _entryPending = true;
        }

        Loaded += OnFirstLoaded;
    }

    // ==================== Появление (motion.md) ====================

    private void OnFirstLoaded(object? sender, RoutedEventArgs e)
    {
        Loaded -= OnFirstLoaded;

        // Экран может быть показан и повторно (выход из аккаунта → снова пусто): буфер проверяем
        // всегда, появление играем один раз.
        ProbeClipboard();
        HookWindowActivation();

        if (!_entryPending)
        {
            return;
        }
        _entryPending = false;

        // Движение выключили между ctor и первым кадром (живой тумблер «Облегчённый режим»).
        if (IsReducedMotion())
        {
            RestoreAll();
            return;
        }

        // Щит — bloom: 620 мс из 0.82×, кривая появления (0.22,1,0.36,1) = Ease.OutQuint.
        // Щит «расцветает» на ОБЁРТКЕ, а не на самом кольце: обёртка ужимает кольцо собственным
        // трансформом кольца (так устроен LayoutTransformControl), и появление, обнулявшее этот
        // трансформ по окончании, оставляло кольцо полноразмерным в ужатом слоте — его обрезало.
        PlayReveal(ShieldFit, TimeSpan.Zero, _bloomDuration, _scale082, _scale1);
        // Остальное — подъём translateY 16→0 + проявление, 460 мс, со сдвигом по смыслу.
        PlayReveal(TitleText, TimeSpan.FromMilliseconds(80), _liftDuration, _lift16, _lift0);
        PlayReveal(SubtitleText, TimeSpan.FromMilliseconds(140), _liftDuration, _lift16, _lift0);
        PlayReveal(ActionsBlock, TimeSpan.FromMilliseconds(200), _liftDuration, _lift16, _lift0);
    }

    /// <summary>
    /// Появление одного блока: прозрачность 0→1 и трансформ from→to за одну длительность с одной
    /// задержкой. Ведут два перехода, поставленных на сам элемент.
    ///
    /// Порядок важен: сначала выставляем ИСХОДНОЕ состояние БЕЗ переходов (иначе первая же
    /// установка сама поехала бы анимацией из значения по умолчанию), затем вешаем переходы, и
    /// только следующим оборотом диспетчера ставим целевое — эта установка и анимируется.
    ///
    /// По окончании переходы снимаются, а трансформ обнуляется: он живёт на том же свойстве, что
    /// и :pressed-прогиб кнопок внутри блока, и оставленная «единица» перебивала бы его.
    /// </summary>
    private static void PlayReveal(Control el, TimeSpan delay, TimeSpan duration, ITransform from, ITransform to)
    {
        el.Transitions = null;
        el.Opacity = 0;
        el.RenderTransform = from;

        el.Transitions =
        [
            new TransformOperationsTransition
            {
                Property = Visual.RenderTransformProperty,
                Duration = duration,
                Delay = delay,
                Easing = Motion.Ease.OutQuint,
            },
            new DoubleTransition
            {
                Property = Visual.OpacityProperty,
                Duration = duration,
                Delay = delay,
                Easing = Motion.Ease.OutQuint,
            },
        ];

        Dispatcher.UIThread.Post(
            () =>
            {
                el.Opacity = 1;
                el.RenderTransform = to;
            },
            DispatcherPriority.Background);

        // Уборка: переходы нужны ровно на один проигрыш, дальше экран полностью статичен.
        DispatcherTimer.RunOnce(
            () =>
            {
                el.Transitions = null;
                el.Opacity = 1;
                el.RenderTransform = null;
            },
            delay + duration + TimeSpan.FromMilliseconds(120));
    }

    private void RestoreAll()
    {
        foreach (var el in new Control[] { ShieldFit, TitleText, SubtitleText, ActionsBlock })
        {
            el.Transitions = null;
            el.Opacity = 1;
            el.RenderTransform = null;
        }
    }

    // ==================== Карточка найденной ссылки ====================

    // Экран живёт в дереве постоянно (MainWindow только переключает IsVisible), поэтому буфер
    // перечитываем на КАЖДЫЙ показ, а не один раз в ctor: пользователь мог скопировать ссылку,
    // пока экран был скрыт.
    protected override void OnPropertyChanged(AvaloniaPropertyChangedEventArgs change)
    {
        base.OnPropertyChanged(change);
        if (change.Property == IsVisibleProperty && IsVisible)
        {
            ProbeClipboard();
        }
    }

    // Возврат фокуса окну — второй момент, когда ссылка могла появиться (пользователь сходил в
    // браузер/Telegram и скопировал её). Подписка одна на время жизни вью.
    private void HookWindowActivation()
    {
        if (TopLevel.GetTopLevel(this) is Window w)
        {
            w.Activated += (_, _) =>
            {
                if (IsVisible)
                {
                    ProbeClipboard();
                }
            };
        }
    }

    /// <summary>
    /// Читает буфер обмена и показывает/прячет карточку. Проверка КОНСЕРВАТИВНА: карточка обещает
    /// «ссылка в буфере обмена», поэтому показывается только для того, что реально может быть
    /// подпиской — схема протокола (vless/vmess/ss/trojan/…) или http(s)-ссылка. Ошибка чтения
    /// буфера (нет доступа, пустой clipboard) — просто нет карточки, без всякого шума.
    /// </summary>
    private async void ProbeClipboard()
    {
        if (_clipboardProbeRunning || Design.IsDesignMode)
        {
            return;
        }
        _clipboardProbeRunning = true;
        try
        {
            // Через AvaUtils, а не напрямую через IClipboard: чтение буфера в проекте уже один раз
            // написано (и один раз обёрнуто в try), второй копии этой детали платформы не нужно.
            var text = TopLevel.GetTopLevel(this) is Window w ? await AvaUtils.GetClipboardData(w) : null;
            SetClipCard(LooksLikeSubscriptionLink(text));
        }
        catch
        {
            SetClipCard(false);
        }
        finally
        {
            _clipboardProbeRunning = false;
        }
    }

    private static bool LooksLikeSubscriptionLink(string? text)
    {
        if (string.IsNullOrWhiteSpace(text))
        {
            return false;
        }
        var s = text.Trim();
        // Отсекаем «портянки» и многострочный мусор до разбора схемы: карточка про ОДНУ ссылку.
        if (s.Length > 8192 || s.Contains('\n') || s.Contains(' '))
        {
            return false;
        }
        var scheme = s.IndexOf("://", StringComparison.Ordinal);
        if (scheme <= 0)
        {
            return false;
        }
        return s[..scheme].ToLowerInvariant() switch
        {
            "http" or "https" or "vless" or "vmess" or "ss" or "ssr" or "trojan" or "socks" or "hysteria" or "hysteria2" or "hy2" or "tuic" or "wireguard" or "anytls" => true,
            _ => false,
        };
    }

    private void SetClipCard(bool show)
    {
        if (show == _clipCardShown)
        {
            return;
        }
        _clipCardShown = show;
        ActionsBlock.Margin = show ? _actionsGapWithCard : _actionsGapPlain;
        SetReveal(ClipRevealHost, ClipCard, show);

        // Карточка и пилюля «Добавить из буфера обмена» ведут в одно действие: пока видна карточка,
        // пилюля схлопывается тем же темпом, каким карточка раскрывается, — одно движение, а не два.
        SetReveal(PillRevealHost, ClipboardButton, !show);
        KeyboardNavigation.SetIsTabStop(ClipboardButton, !show);
    }

    /// <summary>
    /// Ужимает щит до 3/4, когда колонка не помещается по высоте, и возвращает его, как только
    /// полный щит снова влезает. Порог — не число, а сама колонка против видимой высоты скролла.
    /// Мерить по Extent скролла нельзя: короткое содержимое скролл растягивает до своей высоты,
    /// и «влезло обратно» по нему не видно никогда. Обратно разжимаем с запасом ровно на
    /// освобождённую щитом высоту, иначе щит дёргался бы туда-обратно на границе.
    /// </summary>
    private void UpdateShieldFit()
    {
        var viewport = Scroll.Viewport.Height > 0 ? Scroll.Viewport.Height : Scroll.Bounds.Height;
        if (viewport <= 0)
        {
            return;
        }
        var need = Column.Bounds.Height + Column.Margin.Top + Column.Margin.Bottom;
        var saving = ShieldRing.Height * (1 - ShieldTightScale);
        if (!_shieldTight && need > viewport + 0.5)
        {
            SetShieldTight(true);
        }
        else if (_shieldTight && need + saving < viewport - 0.5)
        {
            SetShieldTight(false);
        }
    }

    private void SetShieldTight(bool tight)
    {
        _shieldTight = tight;
        if (IsReducedMotion())
        {
            _shieldFitScale.Transitions = null;
        }
        else if (_shieldFitScale.Transitions is null)
        {
            _shieldFitScale.Transitions =
            [
                new DoubleTransition { Property = ScaleTransform.ScaleXProperty, Duration = _shieldFitDuration, Easing = Motion.Ease.OutQuart },
                new DoubleTransition { Property = ScaleTransform.ScaleYProperty, Duration = _shieldFitDuration, Easing = Motion.Ease.OutQuart },
            ];
        }
        var scale = tight ? ShieldTightScale : 1.0;
        _shieldFitScale.ScaleX = scale;
        _shieldFitScale.ScaleY = scale;
    }

    // ==================== «Другие способы» ====================

    private void OnToggleMore(object? sender, RoutedEventArgs e)
    {
        _moreOpen = !_moreOpen;
        if (_moreOpen)
        {
            MoreCaret.Classes.Add("open");
        }
        else
        {
            MoreCaret.Classes.Remove("open");
        }
        SetReveal(MoreRevealHost, (Control)MoreRevealHost.Child!, _moreOpen);

        // Раскрытые строки на низком окне уходили под нижний край: доводим их в поле зрения, когда
        // раскрытие доехало (раньше мерить нечего — высота ещё растёт).
        if (_moreOpen)
        {
            DispatcherTimer.RunOnce(
                () =>
                {
                    if (_moreOpen)
                    {
                        MoreRevealHost.BringIntoView();
                    }
                },
                TimeSpan.FromMilliseconds(340));
        }
    }

    /// <summary>
    /// Раскрытие/схлопывание по ВЫСОТЕ (motion.md «Раскрытия»). Темп и кривая живут в стилях
    /// (Border.StartReveal), здесь — только целевая высота.
    ///
    /// Почему высота считается вручную: у схлопнутого хоста Height=0, поэтому обычный проход
    /// раскладки зажимает DesiredSize ребёнка в ноль — натуральную высоту нужно спросить отдельным
    /// Measure с бесконечной высотой. Зовётся из обработчика (вне прохода раскладки), поэтому
    /// повторный Measure безопасен: следующий проход всё равно перемерит по реальному ограничению.
    ///
    /// Ширина для замера — живая ширина хоста, с откатом на ширину колонки: пока экран скрыт,
    /// раскладки ещё не было и Bounds пуст, а мерить по бесконечной ширине нельзя — перенос строк
    /// не сработал бы и высота вышла бы заниженной (текст обрезался бы при раскрытии).
    /// </summary>
    private void SetReveal(Border host, Control content, bool open)
    {
        if (!open)
        {
            // Хост, который ещё ни разу не схлопывался, стоит на авто-высоте, а из «авто» переход
            // не едет — сперва фиксируем текущую высоту, и уже со следующего оборота уводим в ноль.
            if (double.IsNaN(host.Height))
            {
                host.Height = host.Bounds.Height;
                host.IsHitTestVisible = false;
                Dispatcher.UIThread.Post(
                    () =>
                    {
                        // Пока ждали оборот, хост могли снова раскрыть — тогда схлопывать нечего.
                        if (!host.IsHitTestVisible)
                        {
                            SetReveal(host, content, false);
                        }
                    },
                    DispatcherPriority.Background);
                return;
            }
            host.Height = 0;
            host.Opacity = 0;
            host.IsHitTestVisible = false;
            return;
        }

        var width = host.Bounds.Width > 0
            ? host.Bounds.Width
            : (Column.Bounds.Width > 0 ? Column.Bounds.Width : Column.Width);
        content.Measure(new Size(width, double.PositiveInfinity));
        host.Height = content.DesiredSize.Height;
        host.Opacity = 1;
        host.IsHitTestVisible = true;
    }

    // Ширина изменилась (масштаб интерфейса, смена языка) — перемеряем раскрытый хост, иначе
    // «выросший» на перенос строк текст остался бы обрезанным зафиксированной высотой.
    private void OnRevealHostSizeChanged(object? sender, SizeChangedEventArgs e)
    {
        if (!e.WidthChanged || sender is not Border host || host.Height <= 0)
        {
            return;
        }
        if (host.Child is Control content)
        {
            SetReveal(host, content, true);
        }
    }

    // ==================== Действия ====================

    // «Войти через Telegram». Экран прогрузки поднимается ПЕРВЫМ и сам запускает авторизацию:
    // он же её и ждёт на шаге 0 («Открываем Telegram · Подтвердите вход в приложении»), он же
    // продолжается прогрузкой аккаунта и подписки. Страницы «Ожидаем подтверждения в Telegram»
    // между нажатием и этим экраном больше нет — ждут здесь.
    //
    // Порядок обязателен: сперва слой на стеке, потом старт входа. Наоборот — и первый кадр ушёл бы
    // на сетевой запрос, то есть нажатие снова «ничего не делало» бы полсекунды.
    private void OnTelegram(object? sender, RoutedEventArgs e)
    {
        FlowRequested?.Invoke(this, new StartFlowRequest(StartFlow.Telegram, null));
        RaiseFlowOverlay(AccountSyncView.FlowKind.Telegram, null, driveLogin: true);
    }

    // «Добавить из буфера обмена» (и кнопка внутри карточки найденной ссылки). Задачу импорта
    // отдаём экрану прогрузки: он держит хореографию до её завершения, а не до истечения таймера.
    private void OnClipboard(object? sender, RoutedEventArgs e)
    {
        if (DataContext is not HomeViewModel vm)
        {
            return;
        }
        var work = vm.AddViaClipboard();
        Observe(work, "Onboarding.AddViaClipboard");
        FlowRequested?.Invoke(this, new StartFlowRequest(StartFlow.Clipboard, work));
        RaiseFlowOverlay(AccountSyncView.FlowKind.Clipboard, work);
    }

    /// <summary>
    /// Поднимает экран прогрузки.
    ///
    /// Без этого нажатие не давало НИ ОДНОГО кадра прогрузки: <see cref="FlowRequested"/> уходило
    /// в пустоту (в MainWindow подписки нет), импорт молча шёл фоном, и начальный экран просто
    /// стоял на месте, пока его не сменяла собранная «Главная». Проверено кликом по живому окну —
    /// между кадрами до и после нажатия менялась ровно подсветка кнопки под курсором.
    ///
    /// Куда именно кладётся слой и почему — см. <see cref="AccountSyncView.OpenFlow"/>.
    /// Событие продолжаем поднимать: появится в MainWindow подписка (с хореографией «Сборки
    /// главной» из motion.md, которой отсюда не сделать) — она отработает поверх этого, а слой
    /// останется одним и тем же, потому что второй запуск отсекает <c>_flowOverlay</c>.
    /// </summary>
    private void RaiseFlowOverlay(AccountSyncView.FlowKind kind, Task? work, bool driveLogin = false)
    {
        if (_flowOverlay is not null)
        {
            return;
        }
        var flow = AccountSyncView.OpenFlow(this, kind, work, driveLogin);
        if (flow is null)
        {
            return;
        }
        _flowOverlay = flow;
        flow.BackRequested += (_, _) => _flowOverlay = null;
    }

    /// <summary>
    /// Наблюдаем задачу импорта, даже если её никто не подхватил. <see cref="FlowRequested"/> —
    /// событие: пока MainWindow на него не подписан (проводки нет), задача остаётся ничьей, и её
    /// падение уходит в UnobservedTaskException, то есть в тишину. Тап по кнопке в этом случае
    /// «ничего не делает» и следов не оставляет. Ошибка сама по себе здесь не лечится — она
    /// принадлежит движку импорта, — но она обязана быть видна в журнале.
    /// </summary>
    private static async void Observe(Task work, string tag)
    {
        try
        {
            await work;
        }
        catch (Exception ex)
        {
            Logging.SaveLog(tag, ex);
        }
    }

    // «Добавить по QR-коду» — скан экрана, БЕЗ экрана прогрузки. Две причины, обе по делу:
    // screens.md знает ровно два набора текстов (Telegram и «из буфера»), и «Читаем буфер обмена»
    // над сканом QR было бы враньём; а сам скан прячет окно (ScanScreenInteraction), так что
    // полноэкранному оверлею во время него всё равно негде жить. Добавление идёт напрямую.
    private void OnQr()
    {
        if (DataContext is HomeViewModel vm)
        {
            Observe(vm.AddViaQr(), "Onboarding.AddViaQr");
        }
    }

    // «Войти по почте» — форма «Вход» ВНУТРИ приложения (почта + пароль, там же регистрация), без
    // браузера. Раньше строка называлась «Войти через сайт» и сразу открывала браузер-хэндофф — вход
    // по почте на Android давно живёт в приложении, и владелец просил тот же путь здесь. Дальше —
    // тот же терминальный путь, что у Telegram (и та же причина не поднимать слой самим — страница
    // входа занимает хост подэкранов).
    private void OnEmail()
    {
        FlowRequested?.Invoke(this, new StartFlowRequest(StartFlow.Telegram, null));
        (TopLevel.GetTopLevel(this) as MainWindow)?.OpenLogin();
    }

    /// <summary>reduced-motion: дизайн-режим, превью-хук (PREVIEW_VIEW) ИЛИ живой «Облегчённый режим».</summary>
    private static bool IsReducedMotion()
        => Design.IsDesignMode
           || Environment.GetEnvironmentVariable("PREVIEW_VIEW") is not null
           || MotionState.IsLite;
}
