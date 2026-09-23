using System.Reactive.Disposables;
using System.Text.RegularExpressions;
using Avalonia.Animation;
using Avalonia.Animation.Easings;
using Avalonia.Media.Transformation;
using v2rayN.Desktop.Account;
using v2rayN.Desktop.Account.Dto;
using v2rayN.Desktop.Common;
using v2rayN.Desktop.ViewModels;

namespace v2rayN.Desktop.Views;

/// <summary>
/// Экран «Вход» departament (порт V2rayNG ui/LoginActivity.kt + activity_login.xml, 1:1).
/// Две секции: Telegram (deep link + опрос подтверждения) и сайт (email + пароль, при
/// необходимости 6-значный код 2FA/TOTP).
///
/// Привязан к СУЩЕСТВУЮЩЕМУ <see cref="AccountViewModel"/> (login-члены): состояния
/// idle / awaiting-TG / loading-site / 2FA / success / error переключаются по
/// <see cref="AccountViewModel.CurrentLoginState"/> — но теперь С ДВИЖЕНИЕМ (кроссфейд MethodBlock↔
/// AwaitingBlock, reveal 2FA/ошибки, success-момент дуга→галочка), а НЕ мгновенным IsVisible.
/// Каждая анимация имеет lite-фолбэк (<see cref="IsReducedMotion"/> → мгновенно), петли гасятся
/// селектором :is(Window):not(.lite) и по видимости. При успешном входе success-момент играется
/// ДО отпускания <see cref="BackRequested"/> (оверлей IsImportingAccount перекрывает хэндофф).
/// </summary>
public partial class LoginView : UserControl
{
    /// <summary>Прагматичная проверка email (аналог Android Patterns.EMAIL_ADDRESS).</summary>
    private static readonly Regex _emailRegex = new(@"^[^@\s]+@[^@\s]+\.[^@\s]{2,}$", RegexOptions.Compiled);

    // Моушен-трансформы: TransformOperations композируются чисто с TransformOperationsTransition
    // из стилей (приём ServerListView) — «сырой» ScaleTransform/TranslateTransform конфликтовал бы
    // с переходом RenderTransform. Масштаб центрируется по RenderTransformOrigin="50%,50%" блоков.
    private static readonly ITransform _scale1 = TransformOperations.Parse("scale(1)");
    private static readonly ITransform _scale098 = TransformOperations.Parse("scale(0.98)");
    private static readonly ITransform _scale090 = TransformOperations.Parse("scale(0.9)");
    private static readonly ITransform _rise0 = TransformOperations.Parse("translateY(0px)");
    private static readonly ITransform _rise8 = TransformOperations.Parse("translateY(8px)");
    private static readonly ITransform _riseNeg4 = TransformOperations.Parse("translateY(-4px)");

    /// <summary>Хост подписывается и закрывает суб-страницу (кнопка «назад» / успешный вход).</summary>
    public event EventHandler? BackRequested;

    private CompositeDisposable? _subscriptions;
    private AccountViewModel? _vm;

    // Запрос входа через сайт / 2FA в полёте (LoginState.SiteLoading).
    private bool _siteBusy;

    // Блок 2FA видим (TwoFaTempToken != null) — спиннер занятости идёт на «Подтвердить».
    private bool _twoFaVisible;

    // Форма в режиме регистрации (сегмент «Регистрация») — иначе режим входа.
    private bool _registerMode;

    // ── Поручение «Способов входа» ──────────────────────────────────────────
    // null = обычный вход. Иначе страница открыта НАД живой сессией: привязать почту, сменить адрес
    // или задать первый пароль. Задаётся конструктором, а не свойством после него: подписка на
    // IsLoggedIn (см. Rebind) срабатывает сразу и на уже вошедшем аккаунте закрыла бы страницу
    // первым же действием — ровно тот дефект, что был на Android.
    private readonly EmailErrand? _errand;

    // Шаг поручения: false — поля (адрес, при смене ещё и текущий пароль); true — «придумайте пароль».
    private bool _passwordStep;

    // Запрос поручения в полёте: спиннер на его CTA.
    private bool _errandBusy;

    // Панель ПОТРЕБОВАЛА текущий пароль (400 PASSWORD_REQUIRED), хотя кэшированный профиль говорил,
    // что его нет. Профиль мог устареть — пароль завели на сайте или в другом клиенте, — и без этого
    // флага экран показывал бы «Введите текущий пароль» над полем, которого на нём не нарисовано.
    private bool _errandPasswordRequired;

    // Поручение доведено до конца (галочка сыграна) — гейт того же хэндоффа, что и у входа.
    private bool _errandDone;

    // Запрос регистрации в полёте (LoginState.RegisterLoading) — спиннер на «Создать аккаунт».
    private bool _registerBusy;

    // Какой из трёх блоков колонки контента показан сейчас (управляет кроссфейдом/CTA/дугой).
    private ViewBlock _viewBlock = ViewBlock.Method;

    // Вид активного пред-состояния «письмо отправлено» (задаёт заголовок/подсказку/действие «отправить снова»).
    private PendingKind _pendingKind = PendingKind.Verify;

    private enum ViewBlock
    {
        Method,
        EmailPending,
    }

    private enum PendingKind
    {
        Verify,
        Magic,
        Reset,

        // Ожидание письма привязки почты и ожидание письма смены адреса. Экран тот же самый — те же
        // кольцо, конверт, «Отправить снова» и выход, — потому что и МОМЕНТ тот же: письмо ушло, ждём
        // человека. Разными остаются только слова и вопрос под капотом (какой факт опрашивает профиль).
        Link,
        Change,

        // Browser→app SSO handoff: a one-time code is being redeemed (departamentvpn://auth callback or a
        // pasted code). Transient, self-resolving — no resend/back actions; the ring spins while it redeems.
        Handoff,
    }

    // Ключ ошибки ИМЕННО логин-потока (LoginState.Error → auth_err_*); имеет приоритет над общим
    // AccountViewModel.ErrorText. Храним КЛЮЧ (не текст), чтобы строка переводилась вживую.
    private string _loginErrorKey = string.Empty;

    // Готовая фраза ПАНЕЛИ (не наш ключ): у 400 на поручениях несколько разных смыслов, и своя строка
    // их не различает. Живёт отдельно от ключа именно потому, что переводить её нечем — её написал
    // сервер, на языке аккаунта. Ключ и фраза взаимоисключающи: что поставили последним, то и видно.
    private string _loginErrorText = string.Empty;

    private bool _revealPassword;

    // ── Координация движения/состояния ──────────────────────────────────────
    // Первая раскладка прошла. ДО неё смены состояния СНАПАЮТСЯ (Telegram-вход выставляет ожидание
    // синхронно до первого кадра — самоанимироваться на открытии нельзя).
    private bool _firstRenderDone;

    // Колонка метода пред-скрыта в ctor под entrance-стаггер — стаггер ещё не сыгран.
    private bool _entryPending;

    // Строка ошибки сейчас раскрыта (чтобы не переигрывать reveal при смене текста/языка).
    private bool _errorShown;

    // Гардит перекрывающиеся кроссфейды колонки метода и пред-экрана «письмо отправлено».
    private CancellationTokenSource? _blockCts;

    // Success-момент → хэндофф: BackRequested отпускается ТОЛЬКО после проигрыша success-момента.
    private bool _loggedIn;
    private bool _beatStarted;
    private bool _beatDone;
    private bool _handoffFired;
    private bool _detached;

    public LoginView() : this(null)
    {
    }

    /// <summary>
    /// <paramref name="errand"/> null — обычный экран входа. Иначе страница открывается поверх УЖЕ
    /// вошедшего аккаунта как поручение «Способов входа»; сегмент «Вход | Регистрация», пароль входа,
    /// пассворлесс-ссылки и альтернативные способы с неё сняты — выбирать не из чего.
    /// </summary>
    public LoginView(EmailErrand? errand)
    {
        _errand = errand;
        _passwordStep = errand == EmailErrand.SetPassword;

        InitializeComponent();

        if (Design.IsDesignMode)
        {
            DataContext = AccountViewModel.CreateDesign();
        }

        // Manual back claims the handoff: if the user taps back DURING the ~0.4s success beat, the
        // beat's finally→TryHandoff must not raise BackRequested a second time (a double pop). Setting
        // _handoffFired here makes the two mutually exclusive — whoever fires first wins.
        BackButton.Click += (_, _) =>
        {
            _handoffFired = true;
            BackRequested?.Invoke(this, EventArgs.Empty);
        };
        TelegramButton.Click += OnTelegramClick;
        TogglePasswordButton.Click += OnTogglePasswordClick;

        // Сегмент «Вход | Регистрация» — переключает режим формы (ApplyMode).
        SignInTab.Click += (_, _) => SetMode(false);
        RegisterTab.Click += (_, _) => SetMode(true);

        // CTA поручения и «Пропустить» на шаге пароля.
        ErrandButton.Click += OnErrandSubmitClick;
        SkipButton.Click += OnSkipPasswordClick;

        // Пред-состояния «письмо отправлено»: повторная отправка (по виду) и возврат ко входу.
        ResendButton.Click += OnResendClick;
        BackToSignInButton.Click += OnBackToSignInClick;

        // «Войти по коду» (§A1 #5): разворачивает поле ручного ввода handoff-кода (фолбэк, если callback-
        // схема departamentvpn://auth не сработала). Enter в поле = отправить код тем же путём, что кнопка.
        CodeEntryToggle.Click += OnToggleCodeEntry;
        HandoffCodeBox.KeyDown += OnHandoffCodeKeyDown;

        // Отправка с клавиатуры (паритет imeOptions actionNext/actionDone).
        EmailBox.KeyDown += OnEmailKeyDown;
        PasswordBox.KeyDown += OnPasswordKeyDown;
        ConfirmPasswordBox.KeyDown += OnConfirmPasswordKeyDown;
        CodeBox.KeyDown += OnCodeKeyDown;
        // Активная ячейка 2FA подсвечивается только в фокусе — перерисовываем при смене фокуса.
        CodeBox.GotFocus += (_, _) => RenderCodeCells();
        CodeBox.LostFocus += (_, _) => RenderCodeCells();

        // Email подрезается перед отправкой командой (VM использует значение как есть).
        SiteButton.Click += (_, _) => TrimEmail();

        // Idle-вход (§P2 9): пред-скрываем колонку метода, чтобы стаггер раскрыл её сверху вниз без
        // пред-вспышки (приём ConnectHeroView). Только при включённом движении; lite/preview — видно.
        if (!IsReducedMotion())
        {
            foreach (var child in MethodBlock.Children)
            {
                child.Opacity = 0;
            }
            _entryPending = true;
        }

        Loaded += OnFirstLoaded;

        DataContextChanged += (_, _) => Rebind();
        AttachedToVisualTree += (_, _) => Rebind();
        DetachedFromVisualTree += (_, _) =>
        {
            _detached = true;
            Unbind();
        };

        Rebind();
        RenderCodeCells();
        ApplyMode();
    }

    // ── Первая раскладка: entrance-стаггер / фиксация _firstRenderDone ────────
    private void OnFirstLoaded(object? sender, RoutedEventArgs e)
    {
        Loaded -= OnFirstLoaded;
        _firstRenderDone = true;

        if (!_entryPending)
        {
            return;
        }
        _entryPending = false;

        // Движение выключено или колонка метода не на экране (пред-экран «письмо отправлено») — не
        // стаггерим; просто возвращаем колонку метода видимой.
        if (IsReducedMotion() || !MethodBlock.IsVisible)
        {
            RestoreMethodChildren();
            return;
        }
        PlayEntryStagger();
    }

    // ── Привязка к VM ───────────────────────────────────────────────────────

    /// <summary>Пересобирает подписки на login-состояние VM (идемпотентно).</summary>
    private void Rebind()
    {
        Unbind();

        _vm = DataContext as AccountViewModel;
        if (_vm is null)
        {
            return;
        }

        var d = new CompositeDisposable();
        _subscriptions = d;

        // Машина состояний входа: idle / awaiting-TG / loading-site / success / error. Доставляем
        // СИНХРОННО, когда мы уже на UI-потоке (см. StartTelegramLogin: ожидание выставляется до первого
        // кадра — инлайн-применение гарантирует, что первый кадр уже «ожидание», а не MethodBlock).
        _vm.WhenAnyValue(x => x.CurrentLoginState)
            .Subscribe(state =>
            {
                if (Dispatcher.UIThread.CheckAccess())
                {
                    ApplyLoginState(state);
                }
                else
                {
                    Dispatcher.UIThread.Post(() => ApplyLoginState(state));
                }
            })
            .DisposeWith(d);

        // Блок 2FA виден, пока бэкенд держит tempToken (паритет onTwoFactor).
        _vm.WhenAnyValue(x => x.TwoFaTempToken)
            .ObserveOn(RxSchedulers.MainThreadScheduler)
            .Subscribe(Apply2Fa)
            .DisposeWith(d);

        // Живая валидация: submit активен только при валидном вводе (обе формы — вход и регистрация,
        // либо — на поручении — его собственный CTA; поля общие, гейты разные).
        _vm.WhenAnyValue(x => x.LoginEmail, x => x.LoginPassword)
            .ObserveOn(RxSchedulers.MainThreadScheduler)
            .Subscribe(_ => UpdateGates())
            .DisposeWith(d);

        // Повтор пароля: гейт «Создать аккаунт» / «Сохранить пароль» + подсказка несовпадения.
        _vm.WhenAnyValue(x => x.RegisterConfirmPassword)
            .ObserveOn(RxSchedulers.MainThreadScheduler)
            .Subscribe(_ => UpdateGates())
            .DisposeWith(d);

        _vm.WhenAnyValue(x => x.TwoFaCode)
            .ObserveOn(RxSchedulers.MainThreadScheduler)
            .Subscribe(_ => Update2FaGate())
            .DisposeWith(d);

        // Общий ErrorText VM (реальный диагностик) — если нет ошибки логин-потока.
        _vm.WhenAnyValue(x => x.ErrorText)
            .ObserveOn(RxSchedulers.MainThreadScheduler)
            .Subscribe(_ => UpdateErrorLine())
            .DisposeWith(d);

        // Вход выполнен — но суб-страница закрывается ТОЛЬКО после success-момента (см. OnLoggedIn):
        // BackRequested гейтится дугой→галочкой, чтобы был кадр подтверждения (§3.4).
        //
        // ТОЛЬКО ДЛЯ НАСТОЯЩЕГО ВХОДА. У поручения аккаунт вошёл ДО открытия страницы, и подписка,
        // которая срабатывает сразу текущим значением, закрыла бы её через четверть секунды после
        // появления — человек нажал «Привязать почту» и увидел моргнувший экран. Своё завершение у
        // поручения есть (FinishErrand), и наступает оно по делу, а не по факту наличия сессии.
        if (_errand is null)
        {
            _vm.WhenAnyValue(x => x.IsLoggedIn)
                .DistinctUntilChanged()
                .Where(loggedIn => loggedIn)
                .ObserveOn(RxSchedulers.MainThreadScheduler)
                .Subscribe(_ => OnLoggedIn())
                .DisposeWith(d);
        }

        // Живой перевод императивных строк (строка ошибки + подсказка глаза).
        void OnLanguageChanged(object? s, EventArgs e) => RunOnUiLang(ApplyLanguage);
        L.Instance.LanguageChanged += OnLanguageChanged;
        Disposable.Create(() => L.Instance.LanguageChanged -= OnLanguageChanged).DisposeWith(d);

        // VM появилась только сейчас (DataContext ставится ПОСЛЕ конструктора), а от неё зависит вид
        // формы: у смены адреса поле текущего пароля рисуется только когда пароль у аккаунта есть.
        ApplyMode();
    }

    /// <summary>Пере-считывает гейты той формы, которая сейчас на экране (вход/регистрация или поручение).</summary>
    private void UpdateGates()
    {
        if (_errand is not null)
        {
            UpdateErrandGate();
            return;
        }
        UpdateSiteGate();
        UpdateRegisterGate();
    }

    /// <summary>Диспетчеризует на UI-поток (событие языка/lite может прийти не из UI).</summary>
    private static void RunOnUiLang(Action action)
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

    /// <summary>Пере-применяет императивные строки под текущий язык.</summary>
    private void ApplyLanguage()
    {
        UpdateErrorLine();
        ToolTip.SetTip(TogglePasswordButton, L.T(_revealPassword ? "Login_HidePassword" : "Login_ShowPassword"));
        // Re-derive the imperative mode labels (title/subtitle/toolbar/watermark) in the new language.
        ApplyMode();
        // Re-derive the pending-screen copy if it's up.
        if (_viewBlock == ViewBlock.EmailPending)
        {
            ConfigureEmailPending(_pendingKind, _vm?.LoginEmail?.Trim() ?? string.Empty);
        }
    }

    private void Unbind()
    {
        _subscriptions?.Dispose();
        _subscriptions = null;
    }

    // ── Машина состояний (паритет LoginActivity.render) ─────────────────────

    private void ApplyLoginState(LoginState state)
    {
        if (_errand is not null)
        {
            ApplyErrandState(state);
            return;
        }
        switch (state)
        {
            // AwaitingTelegram/Polling сюда больше НЕ приходят своим ходом: вход через Telegram
            // ведёт экран прогрузки, а он открывается вместо этой страницы, а не поверх неё. Если
            // состояние всё же долетело (общий VM, чужой поток) — молчим: страница не про него.
            case LoginState.AwaitingTelegram:
            case LoginState.Polling:
                break;

            case LoginState.SiteLoading:
                ShowBlock(ViewBlock.Method);
                SetSiteBusy(true);
                SetLoginError(string.Empty);
                break;

            case LoginState.SiteHandoffLoading:
                // Browser→app handoff code is being redeemed: focused «завершаем вход через сайт…» step
                // (reuses the pending block with the handoff kind — spinner, no resend/back), then Success
                // plays the badge beat over it exactly like an email/2FA login.
                SetSiteBusy(false);
                SetRegisterBusy(false);
                SetLoginError(string.Empty);
                ConfigureEmailPending(PendingKind.Handoff, string.Empty);
                ShowBlock(ViewBlock.EmailPending);
                break;

            case LoginState.RegisterLoading:
                SetLoginError(string.Empty);
                if (_viewBlock == ViewBlock.EmailPending)
                {
                    // «Отправить снова» с экрана verify-email — остаёмся на месте, крутим кольцо.
                    var spin = !IsReducedMotion();
                    SetSpinning(PendingSpinner, spin);
                    PendingSpinner.Opacity = spin ? 1 : 0;
                }
                else
                {
                    // Первичная регистрация из формы — спиннер на «Создать аккаунт».
                    SetSiteBusy(false);
                    SetRegisterBusy(true);
                }
                break;

            case LoginState.AwaitingEmailVerification verify:
                SetSiteBusy(false);
                SetRegisterBusy(false);
                SetLoginError(string.Empty);
                ConfigureEmailPending(PendingKind.Verify, verify.Email);
                ShowBlock(ViewBlock.EmailPending);
                break;

            case LoginState.MagicLinkSent magic:
                SetSiteBusy(false);
                SetRegisterBusy(false);
                SetLoginError(string.Empty);
                ConfigureEmailPending(PendingKind.Magic, magic.Email);
                ShowBlock(ViewBlock.EmailPending);
                break;

            case LoginState.PasswordResetSent reset:
                SetSiteBusy(false);
                SetRegisterBusy(false);
                SetLoginError(string.Empty);
                ConfigureEmailPending(PendingKind.Reset, reset.Email);
                ShowBlock(ViewBlock.EmailPending);
                break;

            case LoginState.Success:
                // НЕ возвращаемся в MethodBlock здесь — success-момент играется на том блоке, что виден
                // (кольцо ожидания → галочка, или транзиентная плашка над формой), затем отпускает
                // хэндофф. SetSiteBusy(false) гасит инлайн-спиннер кнопки под ним.
                SetSiteBusy(false);
                SetRegisterBusy(false);
                SetLoginError(string.Empty);
                PlaySuccessBeat();
                break;

            case LoginState.Error error:
                ShowBlock(ViewBlock.Method);
                SetSiteBusy(false);
                SetRegisterBusy(false);
                SetLoginError(MessageKeyFor(error.ErrorValue));
                // Неверные учётные данные → вспышка рамок email+пароль в Red (без shake, §3.5).
                if (error.ErrorValue is ApiError.Unauthorized)
                {
                    FlashCredentialFields();
                }
                break;

            default: // Idle. Ошибку НЕ трогаем: Idle приходит и сразу после показа ошибки.
                ShowBlock(ViewBlock.Method);
                SetSiteBusy(false);
                SetRegisterBusy(false);
                break;
        }
    }

    // ── Поручения «Способов входа» (привязка · смена адреса · первый пароль) ──

    /// <summary>
    /// Машина состояний поручения. Отдельная от входной по одной причине: у поручения НЕТ успеха
    /// входа. <see cref="LoginState.Success"/> сюда не приходит и приходить не должен — сессия уже
    /// была, а её обработчики заново импортируют подписки и отдают оболочку экрану прогрузки.
    /// Кончается поручение на <see cref="LoginState.EmailAttached"/>, и «кончается» значит разное:
    /// на шаге пароля — совсем, а на ожидании письма — только когда аккаунту больше ничего не нужно.
    /// </summary>
    private void ApplyErrandState(LoginState state)
    {
        switch (state)
        {
            case LoginState.SiteLoading:
                SetLoginError(string.Empty);
                if (_viewBlock == ViewBlock.EmailPending)
                {
                    // «Отправить снова» — остаёмся на экране ожидания и крутим кольцо, как у регистрации.
                    var spin = !IsReducedMotion();
                    SetSpinning(PendingSpinner, spin);
                    PendingSpinner.Opacity = spin ? 1 : 0;
                }
                else
                {
                    SetErrandBusy(true);
                }
                break;

            case LoginState.AwaitingEmailVerification pending:
                SetErrandBusy(false);
                SetLoginError(string.Empty);
                ConfigureEmailPending(_errand == EmailErrand.Change ? PendingKind.Change : PendingKind.Link, pending.Email);
                ShowBlock(ViewBlock.EmailPending);
                break;

            case LoginState.EmailAttached attached:
                SetErrandBusy(false);
                SetLoginError(string.Empty);
                // На шаге пароля это ответ панели «Пароль установлен»: больше ничего не нужно.
                // На ожидании письма — ссылка открыта: адрес есть, но привязка пароля НЕ задаёт, и
                // пока его нет, обещать вход по почте нельзя. Поэтому по умолчанию ведём в пароль.
                //
                // Спрашиваем CanSetPassword(), а не «есть ли пароль»: панель отклоняет set-password
                // только при `passwordHash && onboardingCompleted`, и шаг предлагается ровно там,
                // где он сработает. Иначе поручение упиралось бы в отказ на то, чего никто не просил
                // — или, наоборот, обрывалось бы там, где пароль ещё можно задать.
                if (_passwordStep || !attached.Profile.CanSetPassword())
                {
                    FinishErrand();
                }
                else
                {
                    EnterPasswordStep();
                }
                break;

            case LoginState.Error error:
                SetErrandBusy(false);
                ShowBlock(ViewBlock.Method);
                ShowErrandError(error.ErrorValue);
                break;

            default: // Idle — открытие страницы. Ошибку не трогаем.
                SetErrandBusy(false);
                break;
        }
    }

    /// <summary>
    /// Переводит поручение на шаг «Придумайте пароль». Поле адреса уходит, его место занимают пароль
    /// и повтор — те же самые поля, что у регистрации: второй набор полей для того же ввода был бы
    /// вторым языком там, где хватает одного.
    /// </summary>
    private void EnterPasswordStep()
    {
        _passwordStep = true;
        if (_vm != null)
        {
            // Пароль смены адреса (если он там был) в поле «придумайте пароль» — не подсказка, а
            // подстава: человек нажмёт «сохранить» и заведёт себе пароль, которого не выбирал.
            _vm.LoginPassword = string.Empty;
            _vm.RegisterConfirmPassword = string.Empty;
        }
        ApplyMode();
        ShowBlock(ViewBlock.Method);
        if (!Design.IsDesignMode)
        {
            PasswordBox.Focus();
        }
    }

    /// <summary>
    /// Поручение доведено: та же 64-галочка, что подтверждает вход, и тот же хэндофф назад к вкладке.
    /// Кадр подтверждения тут нужен ровно затем же — действие закончилось на сервере, и без него
    /// страница просто исчезла бы.
    /// </summary>
    private void FinishErrand()
    {
        if (_errandDone)
        {
            return;
        }
        _errandDone = true;
        _vm?.CancelEmailErrand();
        // Кольцо ожидания гасим ДО галочки: крутящаяся дуга под подтверждением говорила бы, что мы
        // всё ещё чего-то ждём, ровно в тот момент, когда ждать больше нечего.
        SetSpinning(PendingSpinner, false);
        _ = PlayErrandDone();
    }

    private async Task PlayErrandDone()
    {
        try
        {
            await PlayBadgeSuccess(IsReducedMotion());
        }
        catch (Exception ex)
        {
            // Движение — не причина оставить человека на законченном экране.
            Logging.SaveLog("LoginView.PlayErrandDone", ex);
        }
        if (_handoffFired || _detached)
        {
            return;
        }
        _handoffFired = true;
        BackRequested?.Invoke(this, EventArgs.Empty);
    }

    /// <summary>
    /// Ошибку поручения ГОВОРИТ ПАНЕЛЬ там, где ей есть что сказать: у 400 на привязке три разных
    /// смысла («Почта уже привязана», «Некорректный email», «Эта почта уже используется другим
    /// аккаунтом»), и различить их по коду статуса нельзя.
    ///
    /// Два случая мы называем СВОИМИ словами, потому что они указывают на поле:
    /// <c>PASSWORD_REQUIRED</c> — пароль не введён, <c>INVALID_PASSWORD</c> — введён неверный. Общая
    /// красная строка на оба отправила бы человека искать опечатку в поле, которого он не заполнял.
    /// И 401 без кода — это не «неверная почта или пароль»: пароля тут не спрашивали, это умерший
    /// семидневный токен, и сказано про сессию.
    /// </summary>
    private void ShowErrandError(ApiError error)
    {
        var code = ApiErrorText.ServerCodeOf(error);
        if (code == "PASSWORD_REQUIRED" || (code == "INVALID_PASSWORD" && error is ApiError.Unauthorized))
        {
            if (code == "PASSWORD_REQUIRED")
            {
                _errandPasswordRequired = true;
                UpdateFormVisibility();
            }
            SetLoginErrorText(L.T(code == "PASSWORD_REQUIRED" ? "Account_PasswordRequired" : "Account_PasswordWrong"));
            SetClass(PasswordBox, "fieldError", true);
            DispatcherTimer.RunOnce(() => SetClass(PasswordBox, "fieldError", false), TimeSpan.FromMilliseconds(220));
            if (!Design.IsDesignMode)
            {
                PasswordBox.Focus();
            }
            return;
        }
        if (error is ApiError.Unauthorized)
        {
            SetLoginError("Account_SessionExpired");
            return;
        }
        var quoted = ApiErrorText.ServerMessageOf(error);
        if (quoted.IsNotEmpty())
        {
            SetLoginErrorText(quoted!);
            return;
        }
        SetLoginError(MessageKeyFor(error));
    }

    /// <summary>Спиннер на CTA поручения; сама кнопка блокируется на время запроса.</summary>
    private void SetErrandBusy(bool busy)
    {
        _errandBusy = busy;
        ErrandSpinner.IsVisible = busy;
        ErrandButtonLabel.Opacity = busy ? 0 : 1;
        SetSpinning(ErrandSpinner, busy && !IsReducedMotion());
        UpdateErrandGate();
    }

    /// <summary>CTA поручения: отправить письмо (шаг полей) либо сохранить пароль (шаг пароля).</summary>
    private void OnErrandSubmitClick(object? sender, RoutedEventArgs e)
    {
        if (_vm is null)
        {
            return;
        }
        if (_passwordStep)
        {
            Execute(_vm.SetPasswordCmd);
            return;
        }
        TrimEmail();
        Execute(_errand == EmailErrand.Change ? _vm.ChangeEmailCmd : _vm.LinkEmailCmd);
    }

    /// <summary>
    /// «Пропустить» на шаге пароля. Шаг не обязателен — адрес уже привязан, и это настоящий выход,
    /// а не отмена: возвращаемся во вкладку тем же движением, что и по завершении.
    /// </summary>
    private void OnSkipPasswordClick(object? sender, RoutedEventArgs e) => FinishErrand();

    /// <summary>
    /// Доступность CTA поручения. Привязка ждёт валидный адрес; смена — ещё и текущий пароль, когда
    /// он у аккаунта есть (панель без него откажет, и лучше не давать нажать, чем ловить отказ);
    /// шаг пароля — шесть символов и совпадающий повтор.
    /// </summary>
    private void UpdateErrandGate()
    {
        if (_errand is null)
        {
            return;
        }
        var email = _vm?.LoginEmail?.Trim() ?? string.Empty;
        var password = _vm?.LoginPassword ?? string.Empty;
        var confirm = _vm?.RegisterConfirmPassword ?? string.Empty;

        bool ready;
        if (_passwordStep)
        {
            ready = password.Length >= AccountViewModel.MinNewPasswordLength && confirm == password;
            ConfirmPasswordError.IsVisible = confirm.Length > 0 && confirm != password;
            EmailError.IsVisible = false;
        }
        else
        {
            var needsPassword = _errand == EmailErrand.Change
                && (_errandPasswordRequired || _vm?.EmailHasPassword == true);
            ready = IsEmail(email) && (!needsPassword || password.Length > 0);
            EmailError.IsVisible = email.Length > 0 && !IsEmail(email);
            ConfirmPasswordError.IsVisible = false;
        }
        ErrandButton.IsEnabled = !_errandBusy && ready;
    }

    /// <summary>
    /// Показывает один из двух блоков колонки контента (форма / «письмо отправлено»), ЗАМЕНЯЯ текущий
    /// КРОССФЕЙДОМ (220мс Ease.Standard, микро-scale), кроме первого кадра/lite — там мгновенный снап.
    /// </summary>
    private void ShowBlock(ViewBlock target)
    {
        var changed = target != _viewBlock;
        var prev = _viewBlock;
        _viewBlock = target;

        // Кольцо пред-состояния крутится только внутри EmailPending (verify-email); гасим при выходе.
        if (target != ViewBlock.EmailPending)
        {
            SetSpinning(PendingSpinner, false);
        }

        if (!_firstRenderDone || IsReducedMotion())
        {
            SnapBlocks(target);
            return;
        }
        if (!changed)
        {
            return;
        }
        CrossfadeBlocks(BlockControl(target), BlockControl(prev));
    }

    private Control BlockControl(ViewBlock block) => block switch
    {
        ViewBlock.EmailPending => EmailPendingBlock,
        _ => MethodBlock,
    };

    /// <summary>Мгновенно ставит нужный блок (первый кадр / lite / reduced-motion).</summary>
    private void SnapBlocks(ViewBlock target)
    {
        _blockCts?.Cancel();
        _blockCts = null;
        void Set(Control c, bool vis)
        {
            c.IsVisible = vis;
            c.Opacity = 1;
            c.RenderTransform = null;
        }
        Set(MethodBlock, target == ViewBlock.Method);
        Set(EmailPendingBlock, target == ViewBlock.EmailPending);
    }

    /// <summary>Кроссфейд между блоками (оба видны в перекрытии → нет пустого кадра).</summary>
    private async void CrossfadeBlocks(Control incoming, Control outgoing)
    {
        if (ReferenceEquals(incoming, outgoing))
        {
            return;
        }

        _blockCts?.Cancel();
        var cts = new CancellationTokenSource();
        _blockCts = cts;

        incoming.Opacity = 0;
        incoming.RenderTransform = _scale098;
        incoming.IsVisible = true;
        outgoing.IsVisible = true;

        try
        {
            await Task.WhenAll(
                PlayMotion(incoming, 0d, 1d, _scale098, _scale1, Motion.Dur.State, Motion.Ease.Standard, token: cts.Token),
                PlayMotion(outgoing, 1d, 0d, _scale1, _scale098, Motion.Dur.State, Motion.Ease.Standard, token: cts.Token, keepTransform: true));
        }
        catch (OperationCanceledException)
        {
        }
        catch (Exception ex)
        {
            // Движение — не причина потерять экран входа. Ниже кадр всё равно доводится до финального
            // состояния, поэтому пользователь видит нужный блок даже когда переход не сыграл.
            Logging.SaveLog("LoginView.CrossfadeBlocks", ex);
        }

        if (cts.IsCancellationRequested)
        {
            return; // новее переход владеет финальным состоянием
        }

        outgoing.IsVisible = false;
        outgoing.Opacity = 1;
        outgoing.RenderTransform = null;
        incoming.Opacity = 1;
        incoming.RenderTransform = null;
        _blockCts = null;
    }

    /// <summary>
    /// Занятость входа через сайт / 2FA: спиннер на инициировавшей кнопке, submit-кнопки
    /// заблокированы (паритет setSiteBusy). CTA Telegram НЕ трогаем — он уводит на экран прогрузки.
    /// </summary>
    private void SetSiteBusy(bool busy)
    {
        _siteBusy = busy;

        var onSite = busy && !_twoFaVisible;
        var on2Fa = busy && _twoFaVisible;

        // Under reduced-motion/lite the inline arc can't rotate (its keyframe is gated off by
        // :is(Window):not(.lite)), so a hidden label + a frozen dashed ring reads as broken. Keep the
        // label and skip the spinner entirely — the button is already disabled (dimmed) via the gates
        // below, which conveys "busy" without any motion.
        var lite = IsReducedMotion();
        var showSiteSpin = onSite && !lite;
        var show2FaSpin = on2Fa && !lite;

        SiteSpinner.IsVisible = showSiteSpin;
        SetSpinning(SiteSpinner, showSiteSpin);
        SiteButtonLabel.IsVisible = !showSiteSpin;

        ConfirmSpinner.IsVisible = show2FaSpin;
        SetSpinning(ConfirmSpinner, show2FaSpin);
        ConfirmButtonLabel.IsVisible = !show2FaSpin;

        UpdateSiteGate();
        Update2FaGate();
    }

    /// <summary>Занятость регистрации: инлайн-спиннер на «Создать аккаунт», submit заблокирован.</summary>
    private void SetRegisterBusy(bool busy)
    {
        _registerBusy = busy;
        // Как и site-спиннер: под reduced-motion/lite дугу не крутим (её keyframe выключен селектором),
        // просто держим лейбл и заблокированную (притушенную) кнопку.
        var showSpin = busy && !IsReducedMotion();
        RegisterSpinner.IsVisible = showSpin;
        SetSpinning(RegisterSpinner, showSpin);
        RegisterButtonLabel.IsVisible = !showSpin;
        UpdateRegisterGate();
    }

    /// <summary>Переключает режим формы (вход ⇄ регистрация): сбрасывает ошибку и пере-применяет вид.</summary>
    private void SetMode(bool register)
    {
        if (_registerMode == register)
        {
            return;
        }
        _registerMode = register;
        SetLoginError(string.Empty);
        ApplyMode();
    }

    /// <summary>
    /// Применяет режим формы к видимости/лейблам: сегмент, заголовок/подзаголовок/тулбар, watermark
    /// пароля, поля регистрации (повтор пароля + подсказка + «Создать аккаунт») против sign-in-элементов
    /// (кнопка сайта + пассворлесс-ссылки + блок 2FA). Пере-считывает гейты валидации под новый режим.
    /// </summary>
    private void ApplyMode()
    {
        SetClass(SignInTab, "segActive", !_registerMode);
        SetClass(RegisterTab, "segActive", _registerMode);

        if (_errand is not null)
        {
            ApplyErrandCopy();
            UpdateFormVisibility();
            UpdateErrandGate();
            return;
        }

        ToolbarTitle.Text = L.T(_registerMode ? "Login_TabRegister" : "Login_SignIn");
        TitleText.Text = L.T(_registerMode ? "Login_TitleRegister" : "Login_Title");
        SubtitleText.Text = L.T(_registerMode ? "Login_SubtitleRegister" : "Login_Subtitle");
        PasswordBox.PlaceholderText = L.T(_registerMode ? "Login_PasswordRegister" : "Login_Password");

        UpdateFormVisibility();
        UpdateSiteGate();
        UpdateRegisterGate();
    }

    /// <summary>
    /// Слова поручения: тулбар, заголовок, подсказка, подписи полей и надпись на CTA. Подсказка НЕ
    /// обещает больше, чем делает: у привязки сказано, что почта добавится к тому же аккаунту (а не
    /// «станет вторым способом входа» — пароля после письма всё ещё нет), у смены — что до перехода
    /// по ссылке адрес остаётся прежним, у пароля — зачем он вообще нужен.
    /// </summary>
    private void ApplyErrandCopy()
    {
        var (titleKey, subtitleKey) = _passwordStep
            ? ("Account_SetPasswordTitle", "Account_SetPasswordSubtitle")
            : _errand == EmailErrand.Change
                ? ("Account_ChangeEmailTitle", "Account_ChangeEmailSubtitle")
                : ("Account_LinkEmailTitle", "Account_LinkEmailSubtitle");

        ToolbarTitle.Text = L.T(titleKey);
        TitleText.Text = L.T(titleKey);
        SubtitleText.Text = L.T(subtitleKey);

        EmailBox.PlaceholderText = L.T(_errand == EmailErrand.Change ? "Account_NewEmail" : "Login_Email");
        PasswordBox.PlaceholderText = L.T(_passwordStep ? "Login_Password" : "Account_CurrentPassword");
        RegisterPasswordHint.Text = L.T("Account_NewPasswordHint");
        ErrandButtonLabel.Text = L.T(_passwordStep ? "Account_SavePassword" : "Account_SendLink");
        SkipButton.Content = L.T("Account_SkipPassword");
        // «Назад», а не «Вернуться ко входу»: входить некуда — за шагом стоит та же форма поручения.
        BackToSignInButton.Content = L.T("Account_BackAction");
    }

    /// <summary>
    /// Gates the mutually-exclusive form regions: register fields + «Создать аккаунт» (register mode),
    /// email submit «Войти» + passwordless links + the demoted alternates block (sign-in mode), and the
    /// 2FA block (sign-in + tempToken). While 2FA is up the sign-in submit + alternates are hidden so the
    /// focused code step is the ONLY filled accent (its «Подтвердить»). Called from both <see cref="ApplyMode"/>
    /// and <see cref="Apply2Fa"/> so the two toggles never disagree.
    /// </summary>
    private void UpdateFormVisibility()
    {
        if (_errand is not null)
        {
            // С формы снято две трети: ни сегмента «Вход | Регистрация», ни пассворлесс-ссылок, ни
            // альтернативных способов, ни 2FA — это не вход и не регистрация, выбирать не из чего.
            // Остаётся то, что поручение действительно спрашивает.
            var fieldsStep = !_passwordStep;
            var needsCurrentPassword = fieldsStep
                && _errand == EmailErrand.Change
                && (_errandPasswordRequired || _vm?.EmailHasPassword == true);

            ModeSegment.IsVisible = false;
            EmailBox.IsVisible = fieldsStep;
            PasswordBox.IsVisible = _passwordStep || needsCurrentPassword;
            RegisterPasswordHint.IsVisible = _passwordStep;
            ConfirmPasswordBox.IsVisible = _passwordStep;

            SiteButtonHost.IsVisible = false;
            RegisterButtonHost.IsVisible = false;
            PasswordlessLinks.IsVisible = false;
            AltMethodsBlock.IsVisible = false;
            TwoFaBlock.IsVisible = false;

            ErrandButtonHost.IsVisible = true;
            SkipButton.IsVisible = _passwordStep;
            return;
        }

        var signInForm = !_registerMode && !_twoFaVisible;

        // Только регистрация.
        RegisterPasswordHint.IsVisible = _registerMode;
        ConfirmPasswordBox.IsVisible = _registerMode;
        RegisterButtonHost.IsVisible = _registerMode && !_twoFaVisible;

        // Только вход (и не во время 2FA).
        SiteButtonHost.IsVisible = signInForm;
        PasswordlessLinks.IsVisible = signInForm;
        AltMethodsBlock.IsVisible = signInForm;

        // Блок 2FA осмыслен только во входе; в регистрации всегда скрыт (иначе — по tempToken).
        TwoFaBlock.IsVisible = !_registerMode && _twoFaVisible;
    }

    /// <summary>Настраивает пред-экран «письмо отправлено» под вид состояния (verify / magic / reset).</summary>
    private void ConfigureEmailPending(PendingKind kind, string email)
    {
        _pendingKind = kind;
        var (titleKey, hintKey) = kind switch
        {
            PendingKind.Magic => ("Login_MagicSentTitle", "Login_MagicSentHint"),
            PendingKind.Reset => ("Login_ResetSentTitle", "Login_ResetSentHint"),
            PendingKind.Handoff => ("Login_SiteHandoff", string.Empty),
            PendingKind.Link => ("Account_LinkEmailWaitTitle", "Account_LinkEmailWaitHint"),
            PendingKind.Change => ("Account_ChangeEmailWaitTitle", "Account_ChangeEmailWaitHint"),
            _ => ("Login_VerifyTitle", "Login_VerifyHint"),
        };
        PendingTitle.Text = L.T(titleKey);
        PendingHint.Text = hintKey.IsNotEmpty() ? L.F(hintKey, email) : string.Empty;
        PendingHint.IsVisible = hintKey.IsNotEmpty();

        // The handoff step is transient + self-resolving — its resend/back actions would be meaningless
        // (there's nothing to re-send and the redeem finishes on its own), so hide them for that kind only.
        var transient = kind == PendingKind.Handoff;
        ResendButton.IsVisible = !transient;
        BackToSignInButton.IsVisible = !transient;

        // Кольцо крутится там, где приложение ДЕЙСТВИТЕЛЬНО ждёт ответа: verify-email опрашивает вход,
        // хэндофф гасит код, привязка и смена — профиль. magic/reset ничего не опрашивают, у них
        // спокойное статичное «отправлено» (дуга скрыта, трек и конверт остаются). Под lite дуги нет.
        var spin = kind is PendingKind.Verify or PendingKind.Handoff or PendingKind.Link or PendingKind.Change
            && !IsReducedMotion();
        SetSpinning(PendingSpinner, spin);
        PendingSpinner.Opacity = spin ? 1 : 0;
    }

    /// <summary>Показывает/прячет блок 2FA по tempToken (паритет onTwoFactor) с reveal + автофокусом.</summary>
    private void Apply2Fa(string? tempToken)
    {
        var visible = tempToken != null;
        var appeared = visible && !_twoFaVisible;
        _twoFaVisible = visible;

        if (appeared)
        {
            UpdateFormVisibility();
            SetLoginError(string.Empty);
            RenderCodeCells();
            Reveal2Fa();
            if (!Design.IsDesignMode)
            {
                CodeBox.Focus();
            }
        }
        else if (!visible)
        {
            UpdateFormVisibility();
        }
        Update2FaGate();
    }

    /// <summary>Reveal блока 2FA (opacity 0→1 + translateY 8→0, 300мс OutQuint); lite — мгновенно.</summary>
    private void Reveal2Fa()
    {
        if (!_firstRenderDone || IsReducedMotion())
        {
            TwoFaBlock.Opacity = 1;
            TwoFaBlock.RenderTransform = null;
            return;
        }
        _ = RevealFrom(TwoFaBlock, _rise8, Motion.Dur.Reveal, Motion.Ease.OutQuint);
    }

    // ── Валидация ввода ─────────────────────────────────────────────────────

    /// <summary>Кнопка «Войти через сайт» активна только при валидном email + пароле. Пассворлесс-ссылки
    /// («Войти по ссылке» / «Забыли пароль?») активны при валидном email (пароль не нужен).</summary>
    private void UpdateSiteGate()
    {
        var email = _vm?.LoginEmail?.Trim() ?? string.Empty;
        var password = _vm?.LoginPassword ?? string.Empty;

        EmailError.IsVisible = email.Length > 0 && !IsEmail(email);
        SiteButton.IsEnabled = !_siteBusy && IsEmail(email) && password.Length > 0;

        var emailOk = IsEmail(email);
        MagicLinkButton.IsEnabled = emailOk;
        ForgotPasswordButton.IsEnabled = emailOk;
    }

    /// <summary>«Создать аккаунт» активна при валидном email, пароле ≥8 и совпадающем повторе.</summary>
    private void UpdateRegisterGate()
    {
        var email = _vm?.LoginEmail?.Trim() ?? string.Empty;
        var password = _vm?.LoginPassword ?? string.Empty;
        var confirm = _vm?.RegisterConfirmPassword ?? string.Empty;

        // Валидность email отражаем в общей строке EmailError (обе формы делят поле).
        EmailError.IsVisible = email.Length > 0 && !IsEmail(email);
        ConfirmPasswordError.IsVisible = _registerMode && confirm.Length > 0 && confirm != password;

        RegisterSubmitButton.IsEnabled = !_registerBusy
            && IsEmail(email)
            && password.Length >= 8
            && confirm.Length > 0
            && confirm == password;
    }

    /// <summary>
    /// «Подтвердить» активна только при 6 цифрах; нецифровые символы отбрасываются. Также перерисовывает
    /// сегментные ячейки под текущий код.
    /// </summary>
    private void Update2FaGate()
    {
        var code = _vm?.TwoFaCode ?? string.Empty;

        var digits = new string(code.Where(char.IsDigit).ToArray());
        if (digits != code && _vm != null)
        {
            _vm.TwoFaCode = digits; // повторное уведомление доведёт состояние ниже
            return;
        }

        CodeError.IsVisible = code.Length > 0 && !IsSixDigits(code);
        ConfirmButton.IsEnabled = !_siteBusy && IsSixDigits(code);
        RenderCodeCells();
    }

    /// <summary>
    /// Отражает код 2FA в 6 сегментных ячейках. Источник истины — реальный CodeBox (ввод/вставка/
    /// клавиатура/автофокус нетронуты): вставка 6-значного кода наполняет все ячейки. filled — цифра
    /// введена; active — следующая к вводу ячейка (только в фокусе, пока код неполон).
    /// </summary>
    private void RenderCodeCells()
    {
        var code = _vm?.TwoFaCode ?? string.Empty;
        var focused = CodeBox.IsFocused;
        var cells = CodeCells.Children;
        for (var i = 0; i < cells.Count; i++)
        {
            if (cells[i] is not Border border)
            {
                continue;
            }
            if (border.Child is TextBlock tb)
            {
                tb.Text = i < code.Length ? code[i].ToString() : string.Empty;
            }
            var active = focused && code.Length < 6 && i == code.Length;
            SetClass(border, "active", active);
            SetClass(border, "filled", i < code.Length && !active);
        }
    }

    private static void SetClass(StyledElement el, string name, bool on)
    {
        if (on)
        {
            if (!el.Classes.Contains(name))
            {
                el.Classes.Add(name);
            }
        }
        else
        {
            el.Classes.Remove(name);
        }
    }

    private static bool IsEmail(string value) => value.Length > 0 && _emailRegex.IsMatch(value);

    private static bool IsSixDigits(string value) => value.Length == 6 && value.All(char.IsDigit);

    /// <summary>VM отправляет LoginEmail как есть — подрезаем через привязку (паритет trim()).</summary>
    private void TrimEmail()
    {
        if (_vm is null)
        {
            return;
        }
        var trimmed = _vm.LoginEmail?.Trim() ?? string.Empty;
        if (trimmed != _vm.LoginEmail)
        {
            _vm.LoginEmail = trimmed;
        }
    }

    // ── Строка ошибки (tv_error) ────────────────────────────────────────────

    private void SetLoginError(string messageKey)
    {
        _loginErrorKey = messageKey;
        _loginErrorText = string.Empty;
        UpdateErrorLine();
    }

    /// <summary>Ставит ГОТОВУЮ фразу (цитата панели) вместо ключа — переводить её нечем и незачем.</summary>
    private void SetLoginErrorText(string text)
    {
        _loginErrorText = text;
        _loginErrorKey = string.Empty;
        UpdateErrorLine();
    }

    /// <summary>Ошибка логин-потока приоритетнее общего ErrorText; reveal при появлении (opacity 0→1 +
    /// translateY −4→0, 220мс), lite — мгновенно. Ключ переводится вживую.</summary>
    private void UpdateErrorLine()
    {
        var text = _loginErrorText.IsNotEmpty()
            ? _loginErrorText
            : (_loginErrorKey.IsNotEmpty() ? L.T(_loginErrorKey) : (_vm?.ErrorText ?? string.Empty));
        ErrorLine.Text = text;
        var show = text.IsNotEmpty();

        if (show == _errorShown)
        {
            if (!show)
            {
                ErrorLine.IsVisible = false;
            }
            return;
        }
        _errorShown = show;

        if (!show)
        {
            ErrorLine.IsVisible = false;
            return;
        }
        ErrorLine.IsVisible = true;
        if (!_firstRenderDone || IsReducedMotion())
        {
            ErrorLine.Opacity = 1;
            ErrorLine.RenderTransform = null;
            return;
        }
        _ = RevealFrom(ErrorLine, _riseNeg4, Motion.Dur.State, Motion.Ease.Standard);
    }

    /// <summary>
    /// Вспышка рамок email+пароль в Red на ~220мс (класс .fieldError → внутренняя рамка Red, возврат
    /// по BrushTransition шаблона). Только цвет — без shake/bounce (§3.5).
    /// </summary>
    private void FlashCredentialFields()
    {
        SetClass(EmailBox, "fieldError", true);
        SetClass(PasswordBox, "fieldError", true);
        DispatcherTimer.RunOnce(
            () =>
            {
                SetClass(EmailBox, "fieldError", false);
                SetClass(PasswordBox, "fieldError", false);
            },
            Motion.Dur.State);
    }

    /// <summary>Ключ строки отказа (порт LoginActivity.messageFor); текст берёт локализация.</summary>
    private static string MessageKeyFor(ApiError error) => error switch
    {
        // 401/403 на входе через сайт — почти всегда неверные учётные данные.
        ApiError.Unauthorized => "Login_ErrBadCreds",
        // 409 — registration hit an existing account (backend returns Conflict).
        ApiError.Server { Code: 409 } => "Login_ErrEmailTaken",
        ApiError.GoneError => "Login_ErrLinkExpired",
        ApiError.ServiceUnavailable => "Common_ServiceUnavailable",
        ApiError.NetworkError or ApiError.TimeoutError => "Common_NetworkError",
        ApiError.NotConfiguredError => "Login_ErrUnavailable",
        _ => "Login_ErrRetry",
    };

    // ── Success-момент + гейт хэндоффа ──────────────────────────────────────

    /// <summary>
    /// Вход выполнен. BackRequested НЕ отпускаем сразу: сперва играем success-момент (дуга→полное
    /// кольцо → самолётик→галочка + hold), затем хэндофф. Оверлей IsImportingAccount уже стоит ПОД
    /// суб-страницей (subPageHost поверх шелла) и перекрывает переход после pop — без пустого кадра.
    /// </summary>
    private void OnLoggedIn()
    {
        _loggedIn = true;
        // Защитно: если success-состояние не запустило момент (не должно случаться — Success ⟺
        // OnAuthenticated), запускаем здесь, чтобы всё равно был кадр подтверждения.
        if (!_beatStarted)
        {
            PlaySuccessBeat();
        }
        TryHandoff();
    }

    private void TryHandoff()
    {
        if (_handoffFired || _detached || !_loggedIn || !_beatDone)
        {
            return;   // already handed off, or the page was popped/detached — never fire on a dead view
        }
        _handoffFired = true;
        BackRequested?.Invoke(this, EventArgs.Empty);
    }

    /// <summary>Проигрывает success-момент один раз, затем отпускает хэндофф (finally — даже на ошибке).</summary>
    private async void PlaySuccessBeat()
    {
        if (_beatStarted)
        {
            return;
        }
        _beatStarted = true;
        try
        {
            await PlayBadgeSuccess(IsReducedMotion());
        }
        catch
        {
            // Никогда не оставляем пользователя на странице входа после реального успеха.
        }
        finally
        {
            _beatDone = true;
            TryHandoff();
        }
    }

    /// <summary>Success-момент: 64-галочка проявляется,
    /// текущий видимый блок (форма или пред-экран) гаснет под ней.</summary>
    private async Task PlayBadgeSuccess(bool lite)
    {
        var active = BlockControl(_viewBlock);
        SuccessBadge.IsVisible = true;
        if (lite)
        {
            active.Opacity = 0;
            SuccessBadge.Opacity = 1;
            SuccessBadge.RenderTransform = null;
            await Task.Delay(120);
            return;
        }
        _ = Fade(active, active.Opacity, 0d, Motion.Dur.PressOut, Motion.Ease.Standard);
        await ScaleFadeIn(SuccessBadge, Motion.Dur.State, Motion.Ease.OutQuint);
        await Task.Delay(120);
    }

    // ── Действия ────────────────────────────────────────────────────────────

    /// <summary>
    /// «Войти через Telegram» на этой странице ведёт ТУДА ЖЕ, куда та же кнопка начального экрана и
    /// гейта «Аккаунта»: на экран прогрузки, который сам запускает вход, сам ждёт подтверждения и сам
    /// доводит до собранной «Главной».
    ///
    /// Сначала снимаем СЕБЯ со стека, потом кладём слой: хост держит ОДИН контент, и слой, положенный
    /// поверх, вернул бы пользователя на эту же страницу по завершении потока. Порядок безопасен —
    /// входная анимация нового подэкрана отменяет выходную предыдущего, и снятая страница не успевает
    /// стереть новый контент.
    /// </summary>
    private void OnTelegramClick(object? sender, RoutedEventArgs e)
    {
        SetLoginError(string.Empty);
        var host = TopLevel.GetTopLevel(this) as MainWindow;
        if (host is null)
        {
            Execute(_vm?.LoginTelegramCmd);
            return;
        }
        BackRequested?.Invoke(this, EventArgs.Empty);
        AccountSyncView.OpenFlow(host, AccountSyncView.FlowKind.Telegram, null, driveLogin: true);
    }

    /// <summary>«Отправить снова» на пред-экране — повторяет запрос ПО ВИДУ состояния (verify/magic/reset).</summary>
    private void OnResendClick(object? sender, RoutedEventArgs e)
    {
        switch (_pendingKind)
        {
            case PendingKind.Magic:
                Execute(_vm?.MagicLinkCmd);
                break;
            case PendingKind.Reset:
                Execute(_vm?.PasswordResetCmd);
                break;
            case PendingKind.Link:
            case PendingKind.Change:
                // То же письмо на тот же адрес. Адрес (и пароль смены) держит VM — на этом экране их
                // уже нет, а после любой ошибки не было бы и в полях.
                Execute(_vm?.ResendEmailLinkCmd);
                break;
            default:
                // Verify-email: повторная регистрация переотправляет письмо и перезапускает поллинг.
                Execute(_vm?.RegisterCmd);
                break;
        }
    }

    /// <summary>«Вернуться ко входу»: отменяет поллинг/запрос и возвращает к форме (Idle → кроссфейд к MethodBlock).</summary>
    private void OnBackToSignInClick(object? sender, RoutedEventArgs e)
    {
        SetLoginError(string.Empty);
        if (_errand is not null)
        {
            // У поручения это «Назад», а не «Вернуться ко входу»: останавливаем опрос и возвращаемся
            // к форме, с которой письмо ушло, — страницу не закрываем, адрес в поле остаётся.
            _vm?.CancelEmailErrand();
            ShowBlock(ViewBlock.Method);
            UpdateErrandGate();
            return;
        }
        // The button says «Вернуться ко входу» — land on the sign-in form, not the register form we may
        // have come from (register → verify-email → back).
        SetMode(register: false);
        _vm?.CancelLogin();
    }

    /// <summary>«Войти по коду»: разворачивает поле ручного ввода handoff-кода и фокусирует его.</summary>
    private void OnToggleCodeEntry(object? sender, RoutedEventArgs e)
    {
        var show = !CodeEntryHost.IsVisible;
        CodeEntryHost.IsVisible = show;
        if (show && !Design.IsDesignMode)
        {
            HandoffCodeBox.Focus();
        }
    }

    /// <summary>Enter в поле handoff-кода = отправить код тем же путём, что кнопка (LoginByCodeCmd).</summary>
    private void OnHandoffCodeKeyDown(object? sender, KeyEventArgs e)
    {
        if (e.Key is Key.Enter or Key.Return)
        {
            Execute(_vm?.LoginByCodeCmd);
            e.Handled = true;
        }
    }

    private void OnTogglePasswordClick(object? sender, RoutedEventArgs e)
    {
        _revealPassword = !_revealPassword;
        PasswordBox.RevealPassword = _revealPassword;
        EyeOnIcon.IsVisible = !_revealPassword;
        EyeOffIcon.IsVisible = _revealPassword;
        ToolTip.SetTip(TogglePasswordButton, L.T(_revealPassword ? "Login_HidePassword" : "Login_ShowPassword"));
    }

    // ── Клавиатура ──────────────────────────────────────────────────────────

    private void OnEmailKeyDown(object? sender, KeyEventArgs e)
    {
        if (e.Key is Key.Enter or Key.Return)
        {
            PasswordBox.Focus();
            e.Handled = true;
        }
    }

    private void OnPasswordKeyDown(object? sender, KeyEventArgs e)
    {
        if (e.Key is Key.Enter or Key.Return)
        {
            // В регистрации Enter ведёт к повтору пароля; во входе — сразу вход.
            if (_registerMode)
            {
                ConfirmPasswordBox.Focus();
            }
            else
            {
                SubmitSite();
            }
            e.Handled = true;
        }
    }

    private void OnConfirmPasswordKeyDown(object? sender, KeyEventArgs e)
    {
        if (e.Key is Key.Enter or Key.Return)
        {
            SubmitRegister();
            e.Handled = true;
        }
    }

    private void OnCodeKeyDown(object? sender, KeyEventArgs e)
    {
        if (e.Key is Key.Enter or Key.Return)
        {
            Submit2Fa();
            e.Handled = true;
        }
    }

    /// <summary>Валидирует поля и запускает вход email/паролем (кнопка и Enter — один путь).</summary>
    private void SubmitSite()
    {
        if (_vm is null || _siteBusy)
        {
            return;
        }
        TrimEmail();
        if (!IsEmail(_vm.LoginEmail ?? string.Empty) || (_vm.LoginPassword ?? string.Empty).Length == 0)
        {
            return;
        }
        Execute(_vm.LoginSiteCmd);
    }

    /// <summary>Валидирует поля регистрации и запускает создание аккаунта (кнопка и Enter — один путь).</summary>
    private void SubmitRegister()
    {
        if (_vm is null || _registerBusy)
        {
            return;
        }
        TrimEmail();
        var email = _vm.LoginEmail ?? string.Empty;
        var password = _vm.LoginPassword ?? string.Empty;
        var confirm = _vm.RegisterConfirmPassword ?? string.Empty;
        if (!IsEmail(email) || password.Length < 8 || confirm != password)
        {
            return;
        }
        Execute(_vm.RegisterCmd);
    }

    /// <summary>Валидирует 6-значный код и завершает вход 2FA.</summary>
    private void Submit2Fa()
    {
        if (_vm is null || _siteBusy || !_twoFaVisible || !IsSixDigits(_vm.TwoFaCode ?? string.Empty))
        {
            return;
        }
        Execute(_vm.Submit2FaCmd);
    }

    // ── Entrance-стаггер (§P2 9) ────────────────────────────────────────────

    /// <summary>
    /// Раскрывает колонку метода 4 АВТОРСКИМИ битами (не равномерный drip), паритет с онбордингом (§3a):
    /// щит → идентичность → CTA Telegram+разделитель → форма входа (как одна группа). Щит (бит 1) —
    /// scale 0.90→1 (§3b, общий с connect-героем); остальное — rise translateY 8→0. Итог ≈500мс.
    /// </summary>
    private void PlayEntryStagger()
    {
        var children = MethodBlock.Children;
        for (var i = 0; i < children.Count; i++)
        {
            var delay = BeatDelayMs(i);
            var from = i == 0 ? _scale090 : _rise8;
            var to = i == 0 ? _scale1 : _rise0;
            _ = PlayReveal((Control)children[i], delay, from, to);
        }
    }

    /// <summary>
    /// Задержка entrance-бита по роли ребёнка MethodBlock (§3a). Порядок XAML после §A2-реструктуризации:
    /// 0 щит; 1–3 идентичность (вордмарк + заголовок + подзаголовок); 4 сегмент «Вход|Регистрация»;
    /// 5+ форма входа + демотированные альтернативы (email/пароль/«Войти»/ссылки/сайт/Telegram/код/2FA/
    /// строка ошибки) как ОДНА группа. Члены бита делят его задержку — 4 бита, а не равномерный drip.
    /// </summary>
    private static int BeatDelayMs(int childIndex) => childIndex switch
    {
        0 => 0,              // бит 1 · щит-марка
        1 or 2 or 3 => 60,   // бит 2 · идентичность (вордмарк + заголовок + подзаголовок)
        4 => 120,            // бит 3 · сегмент «Вход|Регистрация»
        _ => 180,            // бит 4 · форма входа + альтернативы как одна группа
    };

    /// <summary>Раскрывает элемент: opacity 0→1 + RenderTransform from→to, 300мс OutQuint, с задержкой
    /// бита. База возвращается по окончании — чтобы не затенять :pressed-scale кнопок.</summary>
    private static async Task PlayReveal(Control el, int delayMs, ITransform from, ITransform to)
    {
        try
        {
            await PlayMotion(el, 0d, 1d, from, to, Motion.Dur.Reveal, Motion.Ease.OutQuint, TimeSpan.FromMilliseconds(delayMs));
        }
        catch (Exception ex)
        {
            Logging.SaveLog("LoginView.PlayReveal", ex);
        }
        finally
        {
            // Появление УЛУЧШАЕТ уже видимый дефолт, а не создаёт его: чем бы ни кончилось движение,
            // элемент остаётся видимым и без остаточного трансформа.
            el.Transitions = null;
            el.Opacity = 1;
            el.RenderTransform = null;
        }
    }

    /// <summary>Возвращает колонку метода видимой (когда стаггер пропущен: ожидание/lite).</summary>
    private void RestoreMethodChildren()
    {
        foreach (var child in MethodBlock.Children)
        {
            child.Opacity = 1;
            child.RenderTransform = null;
        }
    }

    // ── Помощники движения ──────────────────────────────────────────────────

    /// <summary>
    /// Прозрачность + трансформ одним движением. Ведут ПЕРЕХОДЫ (Transitions), а не Animation.
    ///
    /// ЭТО НЕ СТИЛИСТИЧЕСКИЙ ВЫБОР. Ключевые кадры по <see cref="Visual.RenderTransformProperty"/>
    /// в Avalonia не проигрываются вовсе: <c>Animation.RunAsync</c> бросает СРАЗУ, на разборе кадров —
    /// «No animator registered for the property RenderTransform». Всё, что здесь двигалось этим
    /// способом, на живом окне не двигалось никогда, а кроссфейд блоков (единственное место, где
    /// исключение не было проглочено) уносил ВСЁ ПРИЛОЖЕНИЕ: <c>async void</c> плюс
    /// <c>catch (OperationCanceledException)</c> — и падение уходило в необработанные. Достаточно было
    /// на странице «Вход» нажать «Войти через Telegram» или «Другой способ входа»: вместо экрана
    /// ожидания окно просто исчезало. Тот же диагноз и тот же вывод уже записаны в шапке
    /// <see cref="OnboardingView"/> — TransformOperationsTransition работает, на нём стоит вся
    /// лестница :pressed в GlobalStyles.
    ///
    /// Порядок обязателен: исходное состояние ставится БЕЗ переходов, переходы вешаются вторым шагом,
    /// и только следующим оборотом диспетчера ставится целевое — эта установка и анимируется. По
    /// окончании переходы снимаются, а трансформ обнуляется: он живёт на том же свойстве, что и
    /// :pressed-прогиб кнопок внутри блока, и оставленная «единица» перебивала бы его.
    /// </summary>
    /// <param name="keepTransform">
    /// Оставить конечный трансформ на элементе (уходящий блок кроссфейда — его тут же прячут).
    /// По умолчанию база возвращается, чтобы не мешать нажатиям.
    /// </param>
    private static async Task PlayMotion(
        Control el,
        double fromOpacity,
        double toOpacity,
        ITransform? from,
        ITransform? to,
        TimeSpan duration,
        Easing easing,
        TimeSpan delay = default,
        CancellationToken token = default,
        bool keepTransform = false)
    {
        el.Transitions = null;
        el.Opacity = fromOpacity;
        el.RenderTransform = from;

        el.Transitions =
        [
            new TransformOperationsTransition
            {
                Property = Visual.RenderTransformProperty,
                Duration = duration,
                Delay = delay,
                Easing = easing,
            },
            new DoubleTransition
            {
                Property = Visual.OpacityProperty,
                Duration = duration,
                Delay = delay,
                Easing = easing,
            },
        ];

        // Отдельный оборот на фоновом приоритете: раскладка/отрисовка успевают увидеть исходное
        // состояние, иначе переходу нечего интерполировать и кадр «перепрыгивает».
        await Dispatcher.UIThread.InvokeAsync(
            () =>
            {
                el.Opacity = toOpacity;
                el.RenderTransform = to;
            },
            DispatcherPriority.Background);

        try
        {
            await Task.Delay(delay + duration + TimeSpan.FromMilliseconds(40), token);
        }
        finally
        {
            el.Transitions = null;
            if (!token.IsCancellationRequested)
            {
                el.Opacity = toOpacity;
                el.RenderTransform = keepTransform ? to : null;
            }
        }
    }

    /// <summary>Reveal: opacity 0→1 + translateY(from)→0, затем сброс базы.</summary>
    private static async Task RevealFrom(Control el, ITransform from, TimeSpan dur, Easing easing)
    {
        try
        {
            await PlayMotion(el, 0d, 1d, from, _rise0, dur, easing);
        }
        catch (Exception ex)
        {
            Logging.SaveLog("LoginView.RevealFrom", ex);
        }
        finally
        {
            el.Transitions = null;
            el.Opacity = 1;
            el.RenderTransform = null;
        }
    }

    /// <summary>Чистый fade между двумя значениями opacity.</summary>
    private static Task Fade(Visual el, double from, double to, TimeSpan dur, Easing easing)
    {
        var anim = new Animation
        {
            Duration = dur,
            Easing = easing,
            FillMode = FillMode.Forward,
            Children =
            {
                new KeyFrame { Cue = new Cue(0d), Setters = { new Setter(Visual.OpacityProperty, from) } },
                new KeyFrame { Cue = new Cue(1d), Setters = { new Setter(Visual.OpacityProperty, to) } },
            },
        };
        return anim.RunAsync(el, CancellationToken.None);
    }

    /// <summary>opacity 0→1 + scale 0.9→1 (галочка success / плашка).</summary>
    private static Task ScaleFadeIn(Control el, TimeSpan dur, Easing easing)
        => PlayMotion(el, 0d, 1d, _scale090, _scale1, dur, easing);

    /// <summary>reduced-motion: превью-хук (PREVIEW_VIEW), дизайн-режим ИЛИ live lite (MotionState).</summary>
    private static bool IsReducedMotion()
        => Design.IsDesignMode
           || Environment.GetEnvironmentVariable("PREVIEW_VIEW") is not null
           || MotionState.IsLite;

    private static void Execute(ReactiveCommand<Unit, Unit>? command)
    {
        command?.Execute().Subscribe(_ => { }, _ => { });
    }

    /// <summary>Крутит дугу-спиннер только пока она видна (класс .spinning, как ConnectHeroView).</summary>
    private static void SetSpinning(Avalonia.Controls.Shapes.Ellipse spinner, bool spinning)
    {
        if (spinning)
        {
            if (!spinner.Classes.Contains("spinning"))
            {
                spinner.Classes.Add("spinning");
            }
        }
        else
        {
            spinner.Classes.Remove("spinning");
        }
    }
}
