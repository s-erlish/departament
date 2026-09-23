using System.Reactive.Disposables;
using Avalonia;
using Avalonia.Animation;
using Avalonia.Animation.Easings;
using Avalonia.Controls;
using Avalonia.Media;
using Avalonia.Media.Transformation;
using v2rayN.Desktop.Account;
using v2rayN.Desktop.Common;
using v2rayN.Desktop.ViewModels;

namespace v2rayN.Desktop.Views;

/// <summary>
/// Экран прогрузки (screens.md «Экран прогрузки», motion.md «Поток добавления подписки») —
/// полноэкранный прозрачный слой, который держит кадр между «пользователь выбрал способ» и
/// «Главная собралась».
///
/// ==================== РАСПИСАНИЕ ====================
/// Ровно из motion.md: 0 → шаг 0 (18%), 1200 → шаг 1 (54%), 3000 → шаг 2 (86%),
/// 4600 → шаг 3 (100%, сонар + галочка + тост), 5900 → оверлей начинает таять (520 мс),
/// 6450 → оверлей снят, ТЕМ ЖЕ КАДРОМ пошла сборка Главной.
///
/// Расписание — ПОЛ, а не потолок: шаг 3 наступает не раньше 4600 мс И не раньше, чем реальная
/// работа закончилась. Реальный импорт обычно укладывается в 4.6 с, и тогда видно ровно
/// хореографию пакета; если он затянулся — экран честно стоит на шаге 2, а не врёт «Подписка
/// добавлена» и не дёргается вперёд. Обратное (проскочить шаги, потому что работа кончилась за
/// 300 мс) тоже запрещено: четыре шага объясняют, что именно произошло.
///
/// ==================== ОДНА ТРАНЗАКЦИЯ ====================
/// «Снимать оверлей и запускать сборку в одной транзакции — иначе главная мелькнёт целиком».
/// Поэтому <see cref="ShellHandoffRequested"/> поднимается СИНХРОННО, в том же обороте UI-потока,
/// в котором слой снимается: между снятием и стартом сборки нет ни await, ни Post, ни отдельного
/// кадра. Обработчик обязан в этом же вызове: (1) выставить сборке Главной ПРЕД-состояние,
/// (2) показать шелл, (3) запустить стаггер. Разложи это на два оборота — и Главная покажется
/// целиком на один кадр, ровно то, что запрещено.
///
/// ==================== ЧЕГО ЗДЕСЬ НЕТ ====================
/// Размытия нет — motion.md прямо запрещает («оно лагает»); уход = прозрачность + отдаление 1.06.
/// Имени подписки нет нигде: ни в заголовке, ни в пояснении, ни в тосте.
///
/// Ветка ошибки (SyncFailed) — не из прототипа, а из реального движка: колонка меняется на месте
/// на «Не удалось синхронизировать» с «Повторить» / «Войти заново». Без неё упавший импорт дал бы
/// вечный экран прогрузки.
/// </summary>
public partial class AccountSyncView : UserControl, ISubPage
{
    /// <summary>Набор текстов потока (screens.md, таблица «Экран прогрузки»).</summary>
    public enum FlowKind
    {
        /// <summary>Вход через Telegram (и через сайт — терминальный путь тот же).</summary>
        Telegram,

        /// <summary>Добавление подписки из буфера обмена.</summary>
        Clipboard,
    }

    // ==================== Расписание (motion.md) ====================
    private static readonly TimeSpan _stepAt1 = TimeSpan.FromMilliseconds(1200);
    private static readonly TimeSpan _stepAt2 = TimeSpan.FromMilliseconds(3000);
    private static readonly TimeSpan _stepAt3 = TimeSpan.FromMilliseconds(4600);
    // Отсчитываются от МОМЕНТА шага 3, а не от старта: если работа задержалась, вся концовка
    // сдвигается вместе с ней, сохраняя ритм 4600 → 5900 → 6450.
    private static readonly TimeSpan _dissolveAfterStep3 = TimeSpan.FromMilliseconds(1300);
    private static readonly TimeSpan _removeAfterStep3 = TimeSpan.FromMilliseconds(1850);
    private static readonly TimeSpan _dissolveOpacity = TimeSpan.FromMilliseconds(520);
    private static readonly TimeSpan _dissolveScale = TimeSpan.FromMilliseconds(600);

    // Кривая «смена экрана» cubic-bezier(0.33,0,0.2,1) (motion.md, «Кривые»). В GlobalResources
    // такого токена пока нет — заведён локально; при промоушене в Ease.* заменить ссылкой.
    private static readonly Easing _screenEase = new SplineEasing(0.33, 0, 0.2, 1);

    private static readonly ITransform _scale1 = TransformOperations.Parse("scale(1)");
    private static readonly ITransform _scale106 = TransformOperations.Parse("scale(1.06)");
    private static readonly ITransform _scale06 = TransformOperations.Parse("scale(0.6)");
    private static readonly ITransform _scale155 = TransformOperations.Parse("scale(1.55)");
    private static readonly ITransform _lift16 = TransformOperations.Parse("translateY(16px)");
    private static readonly ITransform _lift0 = TransformOperations.Parse("translateY(0px)");

    private static readonly double[] _progress = [0.18, 0.54, 0.86, 1.00];

    private readonly AccountViewModel? _vm;
    private readonly CompositeDisposable _subs = new();

    private CancellationTokenSource? _flowCts;      // весь поток целиком
    private CancellationTokenSource? _columnAnim;

    // Поколение подтверждающих движений (сонар / галочка / тост / уход слоя). Они идут ПЕРЕХОДАМИ,
    // а переход не отменяется токеном — отменяется его «хвост». Сброс визуалов поднимает счётчик, и
    // все отложенные хвосты прошлого проигрыша становятся неактуальными по сравнению.
    private int _confirmGen;

    private FlowKind _kind = FlowKind.Telegram;
    private int _step = -1;
    private bool _showingError;
    private bool _workDone;
    private bool _prevSyncing;

    // Экран ВЕДЁТ вход через Telegram сам: сначала держит шаг 0 («Открываем Telegram · Подтвердите
    // вход в приложении»), пока пользователь подтверждает, и только потом идёт дальше. Промежуточной
    // страницы ожидания больше нет — ждут здесь.
    private bool _drivingLogin;

    // Что именно упало — от этого зависит, что делают две кнопки ветки ошибки.
    private FailureKind _failure = FailureKind.None;

    // Сигнал «работа кончилась», которого ждёт шаг 3. Заводится на каждый поток заново.
    private TaskCompletionSource? _work;

    // Сигнал «вход подтверждён», которого ждёт переход с шага 0 на шаг 1.
    private TaskCompletionSource? _login;

    /// <summary>
    /// Кто СЕЙЧАС играет поток. Экземпляров экрана в приложении два: статический в шелле (его
    /// поднимают сигналы импорта) и тот, что кладут на стек подэкранов три двери начального экрана.
    /// Без этой отметки вход через Telegram запускал бы ОБА: шелловый увидел бы
    /// <c>IsImportingAccount</c> и начал бы свой поток за кадром, а по снятию подэкрана шелл показал
    /// бы его недоигранным поверх уже собранной «Главной».
    /// </summary>
    private static AccountSyncView? _activeFlow;

    /// <summary>Ветка ошибки объясняет РАЗНОЕ и предлагает разное — от того, где именно оборвалось.</summary>
    private enum FailureKind
    {
        None,

        /// <summary>Синхронизация аккаунта (импорт/подписки) — «Повторить» / «Войти заново».</summary>
        Sync,

        /// <summary>Вход через Telegram не дошёл (истекли 3 минуты, 410, 401) — «Повторить» / «Назад».</summary>
        Login,
    }

    /// <summary>Поток идёт (от <see cref="RunFlow"/> до снятия слоя). MainWindow держит оверлей поднятым, пока это true.</summary>
    public bool FlowRunning { get; private set; }

    /// <summary>
    /// Слой снят — кадр отдан сборке «Главной». Поднимается СИНХРОННО в момент снятия (см. «одна
    /// транзакция» в шапке класса). Обработчик MainWindow обязан в этом же вызове выставить
    /// пред-состояние сборки, показать шелл и запустить стаггер.
    /// </summary>
    public event EventHandler? ShellHandoffRequested;

    /// <summary>
    /// <see cref="ISubPage"/>: экран снимает сам себя со стека подэкранов. Поднимается, когда поток
    /// доигран (вместо <see cref="ShellHandoffRequested"/>, если слой живёт подэкраном, а не
    /// поверхностью шелла) и когда поток буфера упал — иначе полноэкранный слой стал бы тупиком.
    /// </summary>
    public event EventHandler? BackRequested;

    public AccountSyncView()
    {
        InitializeComponent();

        _vm = Design.IsDesignMode ? null : AccountViewModel.Shared;
        DataContext = _vm;

        ProgressTrack.SizeChanged += (_, e) =>
        {
            if (e.WidthChanged)
            {
                ApplyProgress(animate: false);
            }
        };

        // Цвет кометы — из темы (motion.md: синий в тёмной и светлой, белый в чёрно-белой). Берётся
        // из ТОКЕНА Brush.Accent, а не из литерала; конический градиент требует альфу НА КАЖДОЙ
        // ступени рампы, поэтому кисть собирается кодом, а не {DynamicResource} на одном свойстве.
        ApplyCometColor();
        ActualThemeVariantChanged += (_, _) => ApplyCometColor();

        if (Design.IsDesignMode)
        {
            SetStepTexts(0);
            return;
        }

        // Поток можно ЗАПУСТИТЬ извне (RunFlow с начального экрана), а можно не запускать вовсе —
        // тогда экран сам поднимается на сигналах VM: пост-логин импорт и холодный старт с
        // сохранённой сессией это тот же «Войти через Telegram», только пользователь его не нажимал.
        //  ТОЛЬКО пост-логин импорт. Раньше сюда же входил IsStartupLoading — восстановление
        //  аккаунта при запуске, — и экран поднимался на холодном старте, рисуя шаг «Открываем
        //  Telegram · Подтвердите вход в приложении». Владелец перезапускал уже вошедшим и видел
        //  экран ухода в Telegram, которого не просил, а следом Главную. Восстановление — не вход:
        //  показывать его нечем и незачем, оболочка просто ждёт готовности и открывает Главную.
        _subs.Add(_vm!.WhenAnyValue(x => x.IsImportingAccount)
            .DistinctUntilChanged()
            .ObserveOn(RxSchedulers.MainThreadScheduler)
            .Subscribe(OnSyncingChanged));

        _subs.Add(_vm.WhenAnyValue(x => x.SyncFailed)
            .ObserveOn(RxSchedulers.MainThreadScheduler)
            .Subscribe(failed =>
            {
                if (failed)
                {
                    SignalWorkFailed();
                }
                else if (_showingError && _failure == FailureKind.Sync)
                {
                    // «Повторить» синхронизации — возвращаемся к потоку с начала.
                    RunFlow(_kind, null);
                }
            }));

        // ==================== Вход через Telegram ЖИВЁТ ЗДЕСЬ ====================
        // Опрос ведёт AuthManager (те же 2 с между запросами, те же 3 минуты, те же правила:
        // переходный сбой пережидается, 410/401 останавливают сразу, ответ после отмены не входит
        // за спиной). Сюда переехало только ОЖИДАНИЕ: раньше его показывала отдельная страница
        // «Ожидаем подтверждения в Telegram», теперь — шаг 0 этого экрана.
        _subs.Add(_vm.WhenAnyValue(x => x.CurrentLoginState)
            .ObserveOn(RxSchedulers.MainThreadScheduler)
            .Subscribe(OnLoginStateChanged));

        // Кнопки ветки ошибки ведут РАЗНОЕ в зависимости от того, что упало (см. FailureKind),
        // поэтому команды навешаны кодом, а не биндингом в разметке: биндинг знал бы только один
        // из двух случаев и в другом вёл бы не туда.
        RetryButton.Click += (_, _) => OnErrorRetry();
        ReLoginButton.Click += (_, _) => OnErrorSecondary();

        DetachedFromVisualTree += (_, _) =>
        {
            CancelFlow();
            _subs.Dispose();
        };
    }

    // ==================== Ветка ошибки: две кнопки, два смысла ====================

    private void OnErrorRetry()
    {
        if (_failure == FailureKind.Login)
        {
            // Тот же поток с начала — вместе с новым токеном входа: прежний уже потрачен.
            RunFlow(FlowKind.Telegram, null, driveLogin: true);
            return;
        }
        _vm?.SyncRetryCmd.Execute().Subscribe();
    }

    private void OnErrorSecondary()
    {
        if (_failure == FailureKind.Login)
        {
            // «Назад» — на начальный экран. «Войти заново» здесь было бы не про то: аккаунта ещё нет.
            _vm?.CancelLogin();
            LeaveFlow();
            return;
        }
        _vm?.SyncReLoginCmd.Execute().Subscribe();
    }

    // Уводит слой с экрана: подэкраном — снимает себя со стека, поверхностью шелла — прячется и
    // отдаёт кадр штатному гейту.
    private void LeaveFlow()
    {
        CancelFlow();
        _showingError = false;
        _failure = FailureKind.None;
        var back = BackRequested;
        if (back is not null)
        {
            back(this, EventArgs.Empty);
            return;
        }
        IsVisible = false;
    }

    // Состояния входа приходят и когда поток ведём мы, и когда его ведёт кто-то другой (гейт входа
    // «Аккаунта»). Реагируем ТОЛЬКО на свой.
    private void OnLoginStateChanged(LoginState? state)
    {
        if (!_drivingLogin || state is null)
        {
            return;
        }
        switch (state)
        {
            case LoginState.Success:
                _login?.TrySetResult();
                break;

            case LoginState.Error:
                SignalLoginFailed();
                break;
        }
    }

    // ==================== Публичный вход ====================

    /// <summary>
    /// Запускает поток по расписанию motion.md.
    /// </summary>
    /// <param name="kind">Какой набор текстов показывать.</param>
    /// <param name="work">
    /// Реальная работа под потоком. Завершилась — шаг 3 разрешён; упала — ветка ошибки.
    /// <c>null</c> — работу ведёт не вызывающий (вход через Telegram доводит AccountViewModel), и
    /// сигналом служат его же флаги импорта.
    /// </param>
    /// <param name="driveLogin">
    /// Экран сам запускает вход через Telegram и сам его ЖДЁТ на шаге 0. Так работают все три двери
    /// начального экрана и CTA входа во вкладке «Аккаунт»: нажал — сразу этот экран, дальше он
    /// доводит до конца. Отдельной страницы ожидания больше нет.
    /// </param>
    public void RunFlow(FlowKind kind, Task? work, bool driveLogin = false)
    {
        CancelFlow();

        _kind = kind;
        _step = -1;
        _workDone = false;
        _showingError = false;
        _failure = FailureKind.None;
        _drivingLogin = driveLogin;
        FlowRunning = true;
        _activeFlow = this;

        ShowFlowColumn(animate: false);
        ResetFlowVisuals();

        _work = new TaskCompletionSource();
        if (work is not null)
        {
            _ = TrackWork(work);
        }

        _login = new TaskCompletionSource();
        if (driveLogin)
        {
            if (_vm is { IsLoggedIn: true })
            {
                // Уже вошли (например, «Повторить» после сбоя синхронизации) — ждать нечего.
                _login.TrySetResult();
            }
            else
            {
                // Порядок важен: кадр экрана уже стоит, и только потом уходит сетевой запрос —
                // между нажатием и первым шагом нет ни одного кадра без объяснения.
                _vm?.LoginTelegramCmd.Execute().Subscribe();
            }
        }

        var cts = new CancellationTokenSource();
        _flowCts = cts;
        _ = RunSchedule(cts.Token);
    }

    /// <summary>
    /// ОДИН вход в поток для всех дверей: «Войти через Telegram» и «Добавить из буфера обмена» на
    /// начальном экране, кнопка внутри карточки найденной ссылки и CTA входа во вкладке «Аккаунт».
    /// Нажал — экран прогрузки стоит СРАЗУ, дальше он сам ждёт подтверждения и сам доводит до
    /// собранной «Главной». Промежуточных страниц по дороге нет.
    ///
    /// Слой кладётся на стек ПОДЭКРАНОВ (публичный <see cref="MainWindow.OpenSubPage"/>): пока стек
    /// не пуст, MainWindow гасит все три поверхности шелла и не пересчитывает их видимость — значит
    /// пришедшие во время импорта сервера не сдёрнут кадр с недоигранного потока. Сняв себя со
    /// стека, слой возвращает кадр штатному гейту, и тот показывает уже заполненную «Главную».
    /// </summary>
    /// <returns>Поднятый слой либо <c>null</c>, если хоста нет (превью/дизайн).</returns>
    public static AccountSyncView? OpenFlow(Visual anchor, FlowKind kind, Task? work, bool driveLogin)
    {
        if (TopLevel.GetTopLevel(anchor) is not MainWindow window)
        {
            return null;
        }
        var flow = new AccountSyncView();
        window.OpenSubPage(flow);   // подписывает BackRequested на снятие со стека
        flow.RunFlow(kind, work, driveLogin);
        return flow;
    }

    /// <summary>Вход не дошёл (3 минуты молчания, 410, 401) — ветка ошибки с «Повторить» / «Назад».</summary>
    public void SignalLoginFailed()
    {
        if (_showingError)
        {
            return;
        }
        CancelFlow();
        FlowRunning = false;
        SetComet(false);
        _showingError = true;
        _failure = FailureKind.Login;
        ApplyErrorTexts();
        ShowErrorColumn(animate: !IsReducedMotion() && IsVisible);
    }

    /// <summary>Реальная работа завершилась успешно — шаг 3 разрешён (не раньше 4600 мс от старта).</summary>
    public void SignalWorkDone()
    {
        _workDone = true;
        _work?.TrySetResult();
    }

    /// <summary>Реальная работа упала — поток прерывается, колонка меняется на ветку ошибки.</summary>
    public void SignalWorkFailed()
    {
        if (!FlowRunning && _showingError)
        {
            return;
        }
        CancelFlow();
        FlowRunning = false;
        SetComet(false);

        // Ветка ошибки — про синхронизацию АККАУНТА: «Повторить» и «Войти заново» ведут в него.
        // Для потока БУФЕРА она была бы не про то, а слой-подэкран без тулбара стал бы тупиком —
        // выходим на начальный экран. Причину падения импорта пишет сам движок (Logging).
        if (_kind == FlowKind.Clipboard && BackRequested is not null)
        {
            _showingError = false;
            BackRequested(this, EventArgs.Empty);
            return;
        }

        _showingError = true;
        _failure = FailureKind.Sync;
        ApplyErrorTexts();
        ShowErrorColumn(animate: !IsReducedMotion() && IsVisible);
    }

    private async Task TrackWork(Task work)
    {
        try
        {
            await work;
            SignalWorkDone();
        }
        catch
        {
            SignalWorkFailed();
        }
    }

    // ==================== Расписание ====================

    private async Task RunSchedule(CancellationToken ct)
    {
        try
        {
            SetStep(0);
            await Task.Delay(_stepAt1, ct);

            // Пол шага 0 пройден — но пока вход не подтверждён, «Проверяем вход» было бы враньём:
            // пользователь ещё в Telegram. Экран честно стоит на «Открываем Telegram · Подтвердите
            // вход в приложении» столько, сколько нужно (опрос сам оборвётся через 3 минуты и
            // приведёт сюда ветку ошибки). Ритм пакета при этом сохраняется: как только вход прошёл,
            // дальше идут те же 1800 и 1600 мс между шагами.
            if (_drivingLogin && _login is not null)
            {
                await _login.Task.WaitAsync(ct);
            }

            SetStep(1);
            await Task.Delay(_stepAt2 - _stepAt1, ct);
            SetStep(2);
            await Task.Delay(_stepAt3 - _stepAt2, ct);

            // Пол расписания пройден — ждём реальную работу, если она ещё идёт. Экран стоит на
            // шаге 2 («Проверяем сервера» / «Почти готово») сколько нужно: это правда.
            if (!_workDone && _work is not null)
            {
                await _work.Task.WaitAsync(ct);
            }

            SetStep(3);
            PlayConfirmation();

            await Task.Delay(_dissolveAfterStep3, ct);
            StartDissolve();

            await Task.Delay(_removeAfterStep3 - _dissolveAfterStep3, ct);
            ct.ThrowIfCancellationRequested();
            Handoff();
        }
        catch (OperationCanceledException)
        {
        }
    }

    /// <summary>
    /// ОДНА ТРАНЗАКЦИЯ: слой снимается и кадр отдаётся сборке «Главной» в одном обороте UI-потока.
    /// Ни await, ни Dispatcher.Post между этими двумя действиями — иначе между кадрами успеет
    /// показаться собранная Главная целиком.
    /// </summary>
    private void Handoff()
    {
        FlowRunning = false;
        _flowCts = null;
        if (ReferenceEquals(_activeFlow, this))
        {
            _activeFlow = null;
        }

        SetComet(false);
        _confirmGen++;
        FlowRoot.Transitions = null;

        var handler = ShellHandoffRequested;
        if (handler is not null)
        {
            // Готовим себя к следующему разу ДО передачи кадра — после неё мы уже не на экране.
            FlowRoot.Opacity = 1;
            FlowRoot.RenderTransform = null;
            handler(this, EventArgs.Empty);
            return;
        }

        // Проводки в MainWindow нет. Слой живёт ПОДЭКРАНОМ (его положил OnboardingView через
        // MainWindow.OpenSubPage) — снимаем себя со стека: хост ВЕРНЁТ ШЕЛЛ ДО выходной анимации
        // (ApplySubPageShellGate), то есть заполненная «Главная» уже стоит на месте в том же кадре.
        // Хореографии сборки в этом случае не будет — она принадлежит MainWindow и включится, как
        // только там появится подписка на ShellHandoffRequested.
        //
        // Прозрачность НЕ восстанавливаем: слой только что растворился в 0, и возврат единицы дал бы
        // вспышку под выходной анимацией хоста.
        var back = BackRequested;
        if (back is not null)
        {
            back(this, EventArgs.Empty);
            return;
        }
        FlowRoot.Opacity = 1;
        FlowRoot.RenderTransform = null;
        IsVisible = false;
    }

    private void CancelFlow()
    {
        _flowCts?.Cancel();
        _flowCts = null;
        _work?.TrySetCanceled();
        _work = null;
        _login?.TrySetCanceled();
        _login = null;
        _confirmGen++;   // хвосты подтверждающих переходов больше не наши
        FlowRunning = false;
        if (ReferenceEquals(_activeFlow, this))
        {
            _activeFlow = null;
        }
    }

    // ==================== Шаги ====================

    private void SetStep(int step)
    {
        if (_step == step)
        {
            return;
        }
        _step = step;

        SetStepTexts(step);
        ApplyProgress(animate: true);

        var busy = step < 3;
        SetComet(busy);
        // Глиф Telegram объясняет ожидание на шагах 0–2 потока входа; в потоке буфера кольцо
        // остаётся пустым — там объясняет текст, а не глиф.
        TelegramGlyph.IsVisible = busy && _kind == FlowKind.Telegram;
    }

    /// <summary>
    /// Колонка ошибки одна, а объяснений два. Сбой СИНХРОНИЗАЦИИ — аккаунт уже наш, но данные не
    /// доехали: «Повторить» пробует ещё раз, «Войти заново» сбрасывает сессию. Не дошедший ВХОД —
    /// аккаунта ещё нет: «Войти заново» тут не про что, вторая кнопка ведёт назад на начальный экран.
    /// </summary>
    private void ApplyErrorTexts()
    {
        var login = _failure == FailureKind.Login;
        ErrorTitle.Text = L.T(login ? "Flow_LoginErrorTitle" : "Account_SyncErrorTitle");
        ErrorHint.Text = L.T(login ? "Flow_LoginErrorHint" : "Account_SyncErrorHint");
        RetryButton.Content = L.T("Account_SyncRetry");
        ReLoginButton.Content = L.T(login ? "Common_Back" : "Account_SyncReLogin");
    }

    private void SetStepTexts(int step)
    {
        var prefix = _kind == FlowKind.Telegram ? "Flow_Tg" : "Flow_Clip";
        FlowTitle.Text = L.T($"{prefix}Title{step}");
        FlowNote.Text = L.T($"{prefix}Note{step}");
    }

    // Ширина заливки = доля живой ширины дорожки: процентов в раскладке Avalonia нет, а привязка
    // к Bounds через Binding не даёт нужного перехода — ведём кодом, переход живёт в разметке.
    private void ApplyProgress(bool animate)
    {
        if (_step < 0)
        {
            return;
        }
        var track = ProgressTrack.Bounds.Width;
        if (track <= 0)
        {
            return;
        }
        var target = track * _progress[Math.Clamp(_step, 0, _progress.Length - 1)];
        if (animate && !IsReducedMotion())
        {
            ProgressFill.Width = target;
            return;
        }
        // Мгновенно: снимаем переход на время присвоения, иначе значение всё равно поедет.
        var transitions = ProgressFill.Transitions;
        ProgressFill.Transitions = null;
        ProgressFill.Width = target;
        ProgressFill.Transitions = transitions;
    }

    // ==================== Подтверждение (шаг 3) ====================

    private void PlayConfirmation()
    {
        DoneRing.IsVisible = true;
        CheckGlyph.IsVisible = true;
        FlowToastText.Text = L.T(_kind == FlowKind.Telegram ? "Flow_ToastTg" : "Flow_ToastClip");
        FlowToast.IsVisible = true;

        if (IsReducedMotion())
        {
            CheckGlyph.Opacity = 1;
            CheckGlyph.RenderTransform = null;
            FlowToast.Opacity = 1;
            FlowToast.RenderTransform = null;
            return;
        }

        PlaySonar();
        PlayCheckPop();
        PlayToast();
    }

    // ==================== Почему ПЕРЕХОДЫ, а не ключевые кадры ====================
    // Все движения ниже раньше были Animation с KeyFrame по Visual.RenderTransform. Такого
    // аниматора в Avalonia НЕТ: Animation.RunAsync срывается на первом же кадре («No animator
    // registered for the property RenderTransform»), а падение уходило в try/catch и в
    // UnobservedTaskException — то есть в тишину. Со стороны это выглядело как «подтверждение и
    // уход слоя просто не играют»: сонар/галочка/тост оказывались на месте мгновенно, а слой
    // пропадал без растворения. Тот же корень уже вылечен в LoginView и MainWindow тем же приёмом —
    // TransformOperationsTransition + DoubleTransition. Анимации по Opacity ключевыми кадрами
    // работают и остаются как есть (кроссфейд колонок ниже).
    //
    // Переход отменяется не токеном, а снятием самих Transitions, поэтому «хвост» каждого проигрыша
    // сверяет поколение _confirmGen: сброс визуалов его поднимает, и опоздавший хвост ничего не
    // трогает.

    /// <summary>
    /// Один проигрыш «из → в» переходами: трансформ и прозрачность за одну длительность.
    /// Порядок обязателен: исходное состояние ставится БЕЗ переходов (иначе первая же установка
    /// сама поехала бы), переходы вешаются следом, целевое — только следующим оборотом диспетчера.
    /// </summary>
    private void PlayTransition(
        Control el,
        ITransform from,
        ITransform to,
        double fromOpacity,
        double toOpacity,
        TimeSpan duration,
        Easing easing,
        Action? onDone = null)
    {
        var gen = _confirmGen;

        el.Transitions = null;
        el.Opacity = fromOpacity;
        el.RenderTransform = from;

        el.Transitions =
        [
            new TransformOperationsTransition
            {
                Property = Visual.RenderTransformProperty,
                Duration = duration,
                Easing = easing,
            },
            new DoubleTransition
            {
                Property = Visual.OpacityProperty,
                Duration = duration,
                Easing = easing,
            },
        ];

        Dispatcher.UIThread.Post(
            () =>
            {
                if (gen != _confirmGen)
                {
                    return;
                }
                el.Opacity = toOpacity;
                el.RenderTransform = to;
            },
            DispatcherPriority.Background);

        DispatcherTimer.RunOnce(
            () =>
            {
                if (gen != _confirmGen)
                {
                    return;
                }
                el.Transitions = null;
                onDone?.Invoke();
            },
            duration + TimeSpan.FromMilliseconds(80));
    }

    // Сонар: кольцо расходится до 1.55× и гаснет, 600 мс, ОДИН раз (motion.md «Сонар»).
    private void PlaySonar()
    {
        Sonar.IsVisible = true;
        PlayTransition(
            Sonar,
            _scale1,
            _scale155,
            1d,
            0d,
            Motion.Dur.Emphasis,
            Motion.Ease.OutQuint,
            () =>
            {
                Sonar.IsVisible = false;
                Sonar.Opacity = 0;
                Sonar.RenderTransform = null;
            });
    }

    // Галочка: pop 0.6 → 1.06 (70% пути) → 1 плюс проявление, 320 мс ease-out-quart.
    // Два перехода подряд: ключевого кадра «на 70%» у перехода нет, поэтому pop разложен на
    // разгон 224 мс (0.6 → 1.06, туда же проявление) и осадку 96 мс (1.06 → 1).
    private void PlayCheckPop()
    {
        var gen = _confirmGen;
        var rise = TimeSpan.FromMilliseconds(224);
        var settle = TimeSpan.FromMilliseconds(96);

        PlayTransition(
            CheckGlyph,
            _scale06,
            _scale106,
            0d,
            1d,
            rise,
            Motion.Ease.OutQuart,
            () =>
            {
                if (gen != _confirmGen)
                {
                    return;
                }
                PlayTransition(
                    CheckGlyph,
                    _scale106,
                    _scale1,
                    1d,
                    1d,
                    settle,
                    Motion.Ease.OutQuart,
                    () =>
                    {
                        CheckGlyph.Opacity = 1;
                        CheckGlyph.RenderTransform = null;
                    });
            });
    }

    // Тост выезжает снизу 280 мс (motion.md «Тост»). Сам он не уходит: через 550 мс после него
    // растворяется весь оверлей — отдельное затухание тоста было бы вторым, лишним движением.
    private void PlayToast()
    {
        PlayTransition(
            FlowToast,
            _lift16,
            _lift0,
            0d,
            1d,
            TimeSpan.FromMilliseconds(280),
            Motion.Ease.OutQuint,
            () =>
            {
                FlowToast.Opacity = 1;
                FlowToast.RenderTransform = null;
            });
    }

    // ==================== Уход оверлея ====================

    // Прозрачность в 0 (520 мс) плюс отдаление до 1.06 (600 мс), кривая «смена экрана».
    // РАЗМЫТИЯ НЕТ — motion.md запрещает прямым текстом. Длительности РАЗНЫЕ, поэтому это два
    // перехода с разными Duration на одном элементе, а не один общий.
    private void StartDissolve()
    {
        if (IsReducedMotion())
        {
            FlowRoot.Opacity = 0;
            return;
        }

        var gen = _confirmGen;

        FlowRoot.Transitions = null;
        FlowRoot.Opacity = 1;
        FlowRoot.RenderTransform = _scale1;

        FlowRoot.Transitions =
        [
            new DoubleTransition
            {
                Property = Visual.OpacityProperty,
                Duration = _dissolveOpacity,
                Easing = _screenEase,
            },
            new TransformOperationsTransition
            {
                Property = Visual.RenderTransformProperty,
                Duration = _dissolveScale,
                Easing = _screenEase,
            },
        ];

        Dispatcher.UIThread.Post(
            () =>
            {
                if (gen != _confirmGen)
                {
                    return;
                }
                FlowRoot.Opacity = 0;
                FlowRoot.RenderTransform = _scale106;
            },
            DispatcherPriority.Background);
    }

    // ==================== Комета ====================

    private void SetComet(bool on)
    {
        // За кадром и под «Облегчённым режимом» комета не крутится: анимация, которую никто не
        // видит, всё равно тикает и жжёт кадры.
        var live = on && IsVisible && !IsReducedMotion();
        Comet.IsVisible = on;
        if (live)
        {
            if (!Comet.Classes.Contains("spinning"))
            {
                Comet.Classes.Add("spinning");
            }
        }
        else
        {
            Comet.Classes.Remove("spinning");
        }
    }

    private void ApplyCometColor()
    {
        if (this.TryFindResource("Brush.Accent", out var res) && res is ISolidColorBrush brush)
        {
            Comet.RingColor = brush.Color;
        }
    }

    // ==================== Колонки: поток ↔ ошибка ====================

    private void ShowFlowColumn(bool animate)
    {
        _columnAnim?.Cancel();
        FlowColumn.IsVisible = true;
        ProgressTrack.IsVisible = true;
        if (!animate)
        {
            FlowColumn.Opacity = 1;
            ErrorColumn.Opacity = 0;
            ErrorColumn.IsVisible = false;
            return;
        }
        var cts = new CancellationTokenSource();
        _columnAnim = cts;
        _ = CrossfadeColumns(FlowColumn, ErrorColumn, cts.Token);
    }

    private void ShowErrorColumn(bool animate)
    {
        _columnAnim?.Cancel();
        ErrorColumn.IsVisible = true;
        FlowToast.IsVisible = false;
        if (!animate)
        {
            ErrorColumn.Opacity = 1;
            FlowColumn.Opacity = 0;
            FlowColumn.IsVisible = false;
            return;
        }
        var cts = new CancellationTokenSource();
        _columnAnim = cts;
        _ = CrossfadeColumns(ErrorColumn, FlowColumn, cts.Token);
    }

    private static async Task CrossfadeColumns(Control incoming, Control outgoing, CancellationToken ct)
    {
        incoming.IsVisible = true;
        var inTask = RunOpacity(incoming, incoming.Opacity, 1d, Motion.Dur.Exit, Motion.Ease.Standard, ct);
        var outTask = RunOpacity(outgoing, outgoing.Opacity, 0d, Motion.Dur.Exit, Motion.Ease.Standard, ct);
        try { await Task.WhenAll(inTask, outTask); }
        catch { }
        if (ct.IsCancellationRequested)
        {
            return;
        }
        incoming.Opacity = 1;
        outgoing.Opacity = 0;
        outgoing.IsVisible = false;
    }

    private static Task RunOpacity(Visual target, double from, double to, TimeSpan duration, Easing easing, CancellationToken ct)
    {
        var anim = new Animation
        {
            Duration = duration,
            Easing = easing,
            FillMode = FillMode.Forward,
            Children =
            {
                new KeyFrame { Cue = new Cue(0d), Setters = { new Setter(Visual.OpacityProperty, from) } },
                new KeyFrame { Cue = new Cue(1d), Setters = { new Setter(Visual.OpacityProperty, to) } },
            },
        };
        return anim.RunAsync(target, ct);
    }

    // ==================== Самостоятельный подъём по сигналам VM ====================

    // Пост-логин импорт и холодный старт с сохранённой сессией — тот же поток «через Telegram»,
    // просто запущенный не с начального экрана. Если RunFlow уже позвали снаружи, ничего не
    // перезапускаем: внешний вызов знает набор текстов точнее.
    private void OnSyncingChanged(bool syncing)
    {
        var was = _prevSyncing;
        _prevSyncing = syncing;

        if (syncing)
        {
            // Поток уже играет — свой или чужой (экран прогрузки, поднятый начальным экраном на
            // стек подэкранов). Второй запуск дал бы два расписания на одно событие: наш ушёл бы
            // тикать за кадром и всплыл бы недоигранным поверх уже собранной «Главной».
            if (!FlowRunning && (_activeFlow is null || ReferenceEquals(_activeFlow, this)))
            {
                RunFlow(FlowKind.Telegram, null);
            }
            return;
        }

        // Импорт закончился успешно (не ошибка, всё ещё вошли) — разрешаем шаг 3.
        if (was && _vm is { SyncFailed: false, IsLoggedIn: true })
        {
            SignalWorkDone();
        }
    }

    // ==================== Жизненный цикл на экране ====================

    protected override void OnPropertyChanged(AvaloniaPropertyChangedEventArgs change)
    {
        base.OnPropertyChanged(change);
        if (change.Property != IsVisibleProperty || Comet is null)
        {
            return;
        }

        if (IsVisible)
        {
            // Показали заново — цвет кометы мог устареть (тему меняли, пока слоя не было).
            ApplyCometColor();
            ApplyProgress(animate: false);
            SetComet(_step is >= 0 and < 3);
        }
        else
        {
            // Слой сняли извне (штатный гейт шелла увёл кадр на Главную раньше, чем поток
            // доиграл) — отменяем всё, чтобы за кадром ничего не тикало и хэндофф не выстрелил
            // в пустоту. Регресса это не даёт: кадр уже ведёт шелл.
            CancelFlow();
            SetComet(false);
            ResetFlowVisuals();
        }
    }

    private void ResetFlowVisuals()
    {
        // Поколение вперёд — отложенные хвосты прошлого проигрыша перестают что-либо трогать.
        // Переходы снимаются ДО присвоений, иначе сброс сам поехал бы анимацией.
        _confirmGen++;
        Sonar.Transitions = null;
        CheckGlyph.Transitions = null;
        FlowToast.Transitions = null;
        FlowRoot.Transitions = null;

        Sonar.IsVisible = false;
        Sonar.Opacity = 0;
        Sonar.RenderTransform = null;
        DoneRing.IsVisible = false;
        CheckGlyph.IsVisible = false;
        CheckGlyph.Opacity = 0;
        CheckGlyph.RenderTransform = null;
        TelegramGlyph.IsVisible = false;
        FlowToast.IsVisible = false;
        FlowToast.Opacity = 0;
        FlowToast.RenderTransform = null;
        FlowRoot.Opacity = 1;
        FlowRoot.RenderTransform = null;

        var transitions = ProgressFill.Transitions;
        ProgressFill.Transitions = null;
        ProgressFill.Width = 0;
        ProgressFill.Transitions = transitions;
    }

    /// <summary>«Облегчённый режим» / дизайн-режим: движения нет, состояния переключаются мгновенно.</summary>
    private static bool IsReducedMotion() => Design.IsDesignMode || MotionState.IsLite;
}
