using System.Collections.ObjectModel;
using System.Collections.Specialized;
using System.ComponentModel;
using System.Runtime.CompilerServices;
using ServiceLib.Handler.SysProxy;
using v2rayN.Desktop.Account;
using v2rayN.Desktop.Common;
using v2rayN.Desktop.Views;

namespace v2rayN.Desktop.ViewModels;

/// <summary>
/// Aggregator ViewModel for the Home screen. It WRAPS the real engine — it does not duplicate any
/// engine logic. It reuses the existing <see cref="ProfilesViewModel"/> and singleton
/// <see cref="StatusBarViewModel"/> held by <see cref="MainWindowViewModel"/>, and exposes for the
/// two-panel Home:
///   • the grouped server list (<see cref="ServerGroups"/>) projected from the real
///     <c>ProfilesViewModel.ProfileItems</c>, grouped by subscription;
///   • the empty / onboarding flags (<see cref="HasServers"/> / <see cref="IsEmpty"/>);
///   • the connect state (<see cref="IsConnected"/> / <see cref="IsConnecting"/>) derived from the
///     actually-running core;
///   • live up/down speed and a session uptime;
///   • commands: connect toggle (start via <c>MainWindowViewModel.Reload</c>, stop via
///     <c>CoreManager.CoreStop</c>), select server (<c>SetDefaultServer</c>), import (clipboard/QR),
///     refresh subscription.
///
/// Consumer-VPN (Happ) model: the app starts DISCONNECTED. The core is only started when the user
/// taps connect or picks a server — never on startup (the startup auto-connect is gated in
/// <see cref="MainWindowViewModel.Init"/>).
/// </summary>
public class HomeViewModel : MyReactiveObject, IDisposable
{
    private readonly MainWindowViewModel? _main;

    /// <summary>Real engine list VM (source of ProfileItems / SelectedProfile / ServerFilter / ping).</summary>
    public ProfilesViewModel? Profiles { get; }

    /// <summary>Real engine status VM (singleton) — running server / speed / TUN.</summary>
    public StatusBarViewModel? StatusBar { get; }

    /// <summary>Servers grouped by subscription, projected live from <c>Profiles.ProfileItems</c>.</summary>
    public ObservableCollection<HomeServerGroup> ServerGroups { get; } = new();

    //  Указатель «IndexId → отображаемая строка» для FindRowByIndexId. Строится лениво, сбрасывается
    //  в ReconcileGroups — там и только там меняются ServerGroups и Servers внутри них.
    private Dictionary<string, ProfileItemModel>? _rowIndex;

    private readonly Dictionary<string, bool> _groupExpanded = new();
    // Coalesces the Clear()+AddRange() CollectionChanged burst the engine emits on every
    // refresh/select into ONE deferred reconcile (see OnProfileItemsChanged / ScheduleReconcile).
    private bool _reconcilePending;
    // Движок уже хоть раз наполнил ProfileItems — см. IsResolved.
    private bool _sourceLoaded;
    // Event-driven core state (B1/B3): no permanent 1s poll. The uptime tick below exists ONLY while
    // connected. Both are torn down in Dispose.
    private readonly IDisposable? _coreStateSub;
    private readonly IDisposable? _switchSettledSub;
    private readonly IDisposable? _recoveryFailedSub;
    private readonly IDisposable? _statsSub;
    // Per-item live-sync: latency/speed results are reported by MUTATING the ProfileItems instances in
    // place (ProfilesViewModel.SetSpeedTestResult sets Delay/DelayVal/SpeedVal/IpInfo) — a per-ITEM
    // property change that raises NO CollectionChanged, so the CollectionChanged-driven reconcile never
    // fires and the DISPLAYED rows (distinct retained instances) would never show the "Testing…" spinner
    // or the ms result. We subscribe to each source item's PropertyChanged and mirror the changed field
    // onto the matching displayed row. Re-synced on every reconcile (items are rebuilt wholesale).
    private readonly List<ProfileItemModel> _observedItems = new();
    private DispatcherTimer? _uptimeTimer;
    private DateTime? _connectedSince;
    private DateTime? _connectingUntil;
    // True ONLY during a server SWITCH from a live connection (A5: a changed default reloads the
    // core). The shield shows Connecting while the old core cycles DOWN→UP; this flag holds that
    // Connecting state until a real stop transition (CoreRunningStateChanged=false) is observed, so
    // the still-running OLD core can't snap the shield straight back to Connected before the switch
    // actually re-establishes. Cleared on the stop event, on the deadline, or on an aborted pick.
    private bool _awaitingCoreCycle;

    //  Идёт переключение сервера с ЖИВОГО подключения: SetDefaultServer перезагружает ядро, и до
    //  того, как состояние снова определится (подключено / отказ / отключено), туннель находится
    //  «между» серверами. Держится от тапа до этого момента.
    private bool _switching;

    //  ОЧЕРЕДЬ НА ОДИН ЭЛЕМЕНТ. Тап по другой строке во время переключения раньше молча пропадал:
    //  SelectServer читал wasConnected = IsConnected, а первый тап уже уронил IsConnected в false
    //  ради крутилки, поэтому второй уходил в ветку «просто назначить сервер, не подключать» —
    //  конфиг менялся, туннель оставался на первом выборе, а список подсвечивал второй. Теперь
    //  такой тап запоминается ЗДЕСЬ и применяется, когда переключение осядет. Побеждает
    //  ПОСЛЕДНИЙ выбор, очередь не копится и не переживает отключение или отказ подключения.
    private string? _pendingServerId;

    private ServerSpeedItem? _lastSpeed;

    //  Когда пришёл _lastSpeed (Environment.TickCount64). Замеры идут раз в секунду, пока окно видно;
    //  замер старше SpeedSampleMaxAgeMs — уже не скорость «сейчас» (см. SyncState).
    private long _lastSpeedTick;
    private const long SpeedSampleMaxAgeMs = 3000;

    #region Reactive state

    [Reactive] public bool IsConnected { get; set; }

    [Reactive] public bool IsConnecting { get; set; }

    /// <summary>
    /// True after a TRUTHFUL connect failure (core failed to start / reload returned failed / the
    /// connect deadline elapsed) — the attempt collapsed back to disconnected. Sticky until the next
    /// connect attempt (<see cref="BeginConnecting"/>) or a successful connect clears it. Drives the
    /// hero's Error shield state (A4) via HomeHeroPresenter; NOT set for a plain user-initiated
    /// disconnect or an invalid server pick.
    /// </summary>
    [Reactive] public bool ConnectFailed { get; set; }

    /// <summary>
    /// Почему не подключилось и что делать дальше — одна фраза под щитом. Пустая, пока отказа нет.
    /// Заполняется из <see cref="CoreManager.LastStartFailure"/> в момент, когда отказ признан.
    /// </summary>
    [Reactive] public string ConnectFailureHint { get; set; } = string.Empty;

    [Reactive] public bool HasServers { get; set; }

    [Reactive] public bool IsEmpty { get; set; } = true;

    /// <summary>
    /// Список серверов хотя бы раз прочитан из базы. До этого <see cref="IsEmpty"/> = true значит
    /// «ещё не читали», а не «серверов нет», и оболочка не выбирает по нему экран: иначе на холодном
    /// старте на пару секунд вставал экран входа или пустая «Главная», пока база не отдала список.
    /// </summary>
    [Reactive] public bool IsResolved { get; set; }

    [Reactive] public string Subtitle { get; set; } = string.Empty;

    //  Скорость печатает ByteSize на языке интерфейса («3,0 КБ/с»), а не движковый HumanFy
    //  с английскими «KB/s» и двумя разными нулями («0 KB/s» до подключения, «0.0 B/s» после).
    [Reactive] public string UpSpeed { get; set; } = ByteSize.Speed(0);

    [Reactive] public string DownSpeed { get; set; } = ByteSize.Speed(0);

    [Reactive] public string Uptime { get; set; } = "00:00:00";

    #endregion Reactive state

    #region Commands

    public ReactiveCommand<Unit, Unit> AddViaClipboardCmd { get; }
    public ReactiveCommand<Unit, Unit> AddViaQrCmd { get; }
    public ReactiveCommand<Unit, Unit> RefreshSubscriptionCmd { get; }

    #endregion Commands

    /// <summary>Runtime constructor: wires the real engine instances (no duplication).</summary>
    public HomeViewModel(MainWindowViewModel main)
    {
        _config = AppManager.Instance.Config;
        _main = main;
        Profiles = main.ProfilesViewModel;
        StatusBar = main.StatusBarViewModel;

        AddViaClipboardCmd = ReactiveCommand.CreateFromTask(AddViaClipboard);
        AddViaQrCmd = ReactiveCommand.CreateFromTask(AddViaQr);
        RefreshSubscriptionCmd = ReactiveCommand.CreateFromTask(RefreshSubscription);

        // Reconcile grouped list + counters whenever the real server collection changes. The
        // reconcile mutates ServerGroups IN PLACE (never Clear()+rebuild) so a mere active-flag flip
        // on selection does not tear the whole list down — see ReconcileGroups (Bug 6).
        Profiles.ProfileItems.CollectionChanged += OnProfileItemsChanged;
        //  Модель могла собраться уже после первого чтения базы: тогда список в руках и ждать нечего.
        _sourceLoaded = Profiles.ProfileItems.Count > 0;
        ReconcileGroups();

        // Live language switch: the fallback group name ("My servers") and the servers/providers
        // meta line are composed in code, so re-run the projection when the language changes.
        L.Instance.LanguageChanged += OnLanguageChanged;

        // Live speed from the same statistics event the status bar consumes.
        _statsSub = AppEvents.DispatcherStatisticsRequested
            .AsObservable()
            .ObserveOn(RxSchedulers.MainThreadScheduler)
            .Subscribe(update =>
            {
                _lastSpeed = update;
                _lastSpeedTick = Environment.TickCount64;
            });

        // Core state is now event-driven (B1/B3): CoreRunningStateChanged fires ONLY on a true/false
        // transition and ON A BACKGROUND THREAD, so we marshal to the UI thread before touching
        // reactive state. This replaces the old permanent 1s poller — nothing wakes at idle. The
        // per-second UPTIME clock still needs a tick while connected: it is started on the "true"
        // transition and stopped+disposed on "false" (see UpdateStateTick).
        _coreStateSub = AppEvents.CoreRunningStateChanged
            .AsObservable()
            .ObserveOn(RxSchedulers.MainThreadScheduler)
            .Subscribe(OnCoreRunningStateChanged);

        // Positive "seamless switch settled" signal (Tier 1/Tier 2 make-before-break). That path raises
        // NO CoreRunningStateChanged(false), so the mid-switch Connecting hold (_awaitingCoreCycle) would
        // otherwise linger up to the 12s deadline after an INSTANT switch. Same background-thread
        // contract → marshal to the UI thread, same disposable pattern as _coreStateSub.
        _switchSettledSub = AppEvents.CoreSwitchSettled
            .AsObservable()
            .ObserveOn(RxSchedulers.MainThreadScheduler)
            .Subscribe(_ => OnCoreSwitchSettled());

        //  Подключение упало само и не восстановилось — это отказ, а не отключение: щит уходит в
        //  Error с причиной. Тот же фоновый поток у события, тот же переход в поток интерфейса.
        _recoveryFailedSub = AppEvents.CoreRecoveryFailed
            .AsObservable()
            .ObserveOn(RxSchedulers.MainThreadScheduler)
            .Subscribe(_ => OnCoreRecoveryFailed());

        // Reflect whatever the core is doing right now (it may already be running when this VM builds).
        SyncState();
        UpdateStateTick();
    }

    /// <summary>Design-time constructor: sample groups only, no engine, no timers.</summary>
    private HomeViewModel()
    {
    }

    #region Connect / disconnect (wraps engine — does NOT auto-connect on startup)

    /// <summary>Shield tap: connect if idle, disconnect if running.</summary>
    public void ConnectToggle()
    {
        //  Решение пользователя главнее подключения, которое вернул бы перезапуск ради обновления.
        UpdateReconnect.NoteUserAction();
        if (IsConnected)
        {
            _ = Disconnect();
        }
        else
        {
            _ = Connect();
        }
    }

    /// <summary>
    /// Подключение, которое возвращает перезапуск ради обновления (<see cref="UpdateReconnect"/>). Тот же
    /// путь, что у тапа по щиту, только без отметки «пользователь сам тронул подключение».
    /// </summary>
    internal Task ReconnectAfterUpdate() => IsConnected || IsConnecting ? Task.CompletedTask : Connect();

    private async Task Connect()
    {
        if (_main == null)
        {
            return;
        }
        BeginConnecting();
        // Reload builds the config and starts the core with the current default server. Its return
        // value tells us whether it actually RAN the attempt or merely deferred to a reload already in
        // flight (semaphore contended — e.g. a rapid second tap, or a background reload).
        var executed = await _main.Reload();

        // Truthful failure, but ONLY when Reload actually executed: it has then fully awaited the core
        // start, so a not-running core means the attempt failed (wrong exe / bad config / blocked) —
        // surface it now instead of spinning to the 12s deadline. If Reload merely DEFERRED (executed
        // == false), the in-flight owner's follow-up job will still bring the core up, so we must NOT
        // paint a failure here; we keep the Connecting spin and let the CoreRunningStateChanged event
        // resolve it (or the 12s deadline surface an honest timeout if it never comes). This closes the
        // "first tap does nothing, second tap connects" race. Verbatim Android string (toast_status_failed).
        if (executed && !IsCoreRunning())
        {
            IsConnecting = false;
            _connectingUntil = null;
            // A4: surface a distinct FAILURE state instead of collapsing silently to Idle. Sticky
            // until the next attempt / a successful connect; drives the hero's Error shield — which is
            // the ONLY surface for connect state now (no bottom snack: the owner doesn't want it).
            MarkConnectFailed();
            ClearSwitchQueue();
        }
        SyncState();
    }

    private async Task Disconnect()
    {
        IsConnecting = false;
        _connectingUntil = null;
        // A deliberate user disconnect ends any mid-switch hold and is not a failure.
        _awaitingCoreCycle = false;
        //  Отключение отменяет переключение вместе с его очередью: применять запомненный выбор
        //  на выключенном туннеле значило бы поднять его без спроса.
        ClearSwitchQueue();
        ClearConnectFailure();
        // byUser:true records sticky user-stop intent and aborts any in-flight auto-restart so the
        // tunnel the user just tore down can never be silently re-established (C1).
        await CoreManager.Instance.CoreStop(byUser: true);
        // Clear the Windows system proxy so the user keeps internet after disconnecting. Without
        // this the OS keeps routing through the now-dead 127.0.0.1:port and every browser breaks
        // until reconnect/reboot. forceDisable=true mirrors AppManager.AppExitAsync.
        await SysProxyHandler.UpdateSysProxy(_config, true);
        _connectedSince = null;
        SyncState();
    }

    /// <summary>Server-row click: make it the default server, then connect per the W1d contract.</summary>
    public async Task SelectServer(string? indexId)
    {
        if (Profiles == null || indexId.IsNullOrEmpty())
        {
            return;
        }
        UpdateReconnect.NoteUserAction();

        //  Переключение ещё не осело — тап не теряется и не бежит наперегонки с ним. Запоминаем
        //  ПОСЛЕДНИЙ выбор (очередь на один элемент) и применяем его в ApplyPendingServer, когда
        //  состояние определится. Подсветка строки при этом не двигается: её ведёт движок по
        //  IsActive, а активным сервер станет ровно тогда, когда выбор действительно применится.
        if (_switching)
        {
            _pendingServerId = indexId;
            return;
        }

        // Capture BEFORE the call (SetDefaultServer mutates _config.IndexId):
        //   changed      — does this pick move the active default? (its Reload connects/reconnects)
        //   wasConnected — was a core already running? (drives the switch-vs-fresh-connect handling)
        // W1d SetDefaultServer contract: returns true = ready to connect; when the pick is ALREADY
        // the default it returns true WITHOUT reloading (the caller must Connect); when the pick
        // CHANGES it persists + Reload()s (which connects, incl. while disconnected); false = do not
        // connect.
        var changed = indexId != _config?.IndexId;
        var wasConnected = IsConnected;

        //  ВЫБОР — ЭТО НЕ ПОДКЛЮЧЕНИЕ. Владелец: «при нажатии на локацию не должен впн включаться,
        //  а просто выбираться сервер». Пока туннель выключен, тап по строке только назначает сервер
        //  активным и ничего не запускает — включение остаётся за щитом. Если туннель УЖЕ поднят,
        //  выбор другой локации по-прежнему переключает на неё: пользователь и так подключён, и
        //  оставить его на прежнем сервере значило бы проигнорировать тап.
        if (!wasConnected)
        {
            //  startWhenIdle: false — иначе туннель поднимет сам SetDefaultServer через Reload(),
            //  и воздержаться от Connect() здесь было бы бесполезно (так и было: правка «выбор не
            //  подключает» не работала, потому что подключал не этот метод).
            await Profiles.SetDefaultServer(indexId, startWhenIdle: false);
            SyncState();
            return;
        }

        // Ниже по коду туннель ЗАВЕДОМО поднят: отключённый случай ушёл ранним возвратом выше, а
        // wasConnected между ними не меняется. Поэтому единственное, что здесь решает, — сменился ли
        // сервер по умолчанию. Раньше условия ещё раз спрашивали «а вдруг мы отключены»
        // (`changed || !wasConnected`, `!changed && !wasConnected`), и обе эти ветки были
        // недостижимы — вместе с вызовом Connect() внутри одной из них.
        if (changed)
        {
            _switching = true;
            // Переключение сервера с живого подключения — та же крутилка Connecting, что у щита (A5):
            // SetDefaultServer перезагружает ядро, то есть это настоящий реконнект.
            BeginConnecting();
            // Роняем Connected СЕЙЧАС, чтобы герой рисовал крутилку на время переключения (презентер
            // держит Connected, пока IsConnected истинно). Ещё живое СТАРОЕ ядро не должно читаться
            // как Connected, пока не отработает цикл: SyncState держит Connecting до события останова
            // (_awaitingCoreCycle), а старт нового ядра уже разрешается в Connected.
            IsConnected = false;
            _awaitingCoreCycle = true;
        }

        if (!await Profiles.SetDefaultServer(indexId))
        {
            // Invalid / failed pick — abort the spinner, do not connect.
            IsConnecting = false;
            _connectingUntil = null;
            _awaitingCoreCycle = false;
            ClearSwitchQueue();
            SyncState();
            return;
        }

        // Повторный тап по УЖЕ активному серверу при живом подключении не перезагружает ничего —
        // щит остаётся Connected. Смена сервера подключает сама (Reload внутри SetDefaultServer).
        SyncState();
    }

    private void BeginConnecting()
    {
        IsConnecting = true;
        // A new attempt clears the previous failure (A4): the hero leaves Error for Connecting.
        ClearConnectFailure();
        // Safety deadline so a failed connect can't leave the shield spinning forever.
        _connectingUntil = DateTime.Now.AddSeconds(12);
        // Run the transient tick while pending so the deadline is actually evaluated even when the
        // connect came from a fire-and-forget Reload (server switch) that raises no failure event.
        UpdateStateTick();
    }

    private static bool IsCoreRunning() =>
        AppManager.Instance.IsRunningCore(ECoreType.Xray) || AppManager.Instance.IsRunningCore(ECoreType.sing_box);

    private void SyncState()
    {
        var running = IsCoreRunning();

        // Mid server-switch hold (A5): while _awaitingCoreCycle is set the OLD core is still up but
        // we are re-establishing on the newly-picked server, so a running core must NOT read as
        // Connected yet — keep the Connecting spin. The stop transition clears the flag
        // (OnCoreRunningStateChanged), after which the new core's start resolves to Connected. The
        // connect deadline bounds the hold: if it elapses with a core still up and no observed stop
        // (defensive — CoreStop always publishes the stop), give up and treat the core as Connected
        // rather than spin forever.
        if (running && _awaitingCoreCycle)
        {
            if (_connectingUntil is { } holdUntil && DateTime.Now <= holdUntil)
            {
                return;
            }
            _awaitingCoreCycle = false;
        }

        if (running)
        {
            _connectedSince ??= DateTime.Now;
            IsConnected = true;
            IsConnecting = false;
            _connectingUntil = null;
            // A successful connect clears any error shield (A4).
            ClearConnectFailure();
            //  Ядро поднялось и удержания больше нет — переключение ОСЕЛО. Сам запомненный выбор
            //  применяют обработчики событий (ApplyPendingServer), чтобы не входить в SelectServer
            //  рекурсивно из SyncState, который SelectServer же и вызывает.
            _switching = false;
            Uptime = FormatUptime(DateTime.Now - _connectedSince.Value);

            var s = _lastSpeed;
            if (s != null)
            {
                //  Старый замер — не скорость. Пока окно в трее, замеры не публикуются, и при возврате
                //  щит несколько секунд показывал последнюю скорость ДО ухода, потом ноль от
                //  догоняющего замера и только потом настоящую: «300 → 0 → 300» на ровной загрузке.
                //  Теперь без свежего замера на щите ноль, пока не придёт настоящий.
                var fresh = Environment.TickCount64 - _lastSpeedTick <= SpeedSampleMaxAgeMs;
                //  Замер движка в КиБ/с, а не в байтах: перевод в ByteSize.EngineSpeed.
                UpSpeed = ByteSize.EngineSpeed(fresh ? s.ProxyUp : 0);
                DownSpeed = ByteSize.EngineSpeed(fresh ? s.ProxyDown : 0);
            }
        }
        else
        {
            // Core is down — any mid-switch hold is over (the old core stopped). Belt-and-suspenders
            // to the stop-event clear, so a hold can never outlive an observed not-running state.
            _awaitingCoreCycle = false;
            _connectedSince = null;
            IsConnected = false;
            Uptime = "00:00:00";
            UpSpeed = ByteSize.Speed(0);
            DownSpeed = ByteSize.Speed(0);

            if (IsConnecting && _connectingUntil is { } until && DateTime.Now > until)
            {
                // The connect deadline elapsed with no running core — a truthful timeout failure (A4).
                // ConnectFailed alone drives the hero's Error shield; no bottom snack is published for
                // connect/disconnect transitions (the owner doesn't want them, and the shield already
                // conveys the state). Covers the fire-and-forget/server-switch connect that only fails
                // via the deadline.
                IsConnecting = false;
                _connectingUntil = null;
                MarkConnectFailed();
                ClearSwitchQueue();
            }
            else if (!IsConnecting)
            {
                //  Ядро стоит и попыток больше нет — оседать нечему.
                ClearSwitchQueue();
            }
        }
    }

    /// <summary>
    /// Отказ признан: щит уходит в Error И под ним появляется ПРИЧИНА с шагом дальше. Причину
    /// движок откладывает в <see cref="CoreManager.LastStartFailure"/> ровно в момент срыва
    /// попытки, поэтому читаем её здесь, а не храним копию.
    /// </summary>
    private void MarkConnectFailed()
    {
        ConnectFailed = true;
        ConnectFailureHint = DescribeConnectFailure(
            CoreManager.Instance.LastStartFailure,
            CoreManager.Instance.LastStartError);
    }

    private void ClearConnectFailure()
    {
        ConnectFailed = false;
        ConnectFailureHint = string.Empty;
    }

    /// <summary>
    /// Причина отказа человеческим языком: что произошло, почему и что делать (00-rules §9.4).
    /// Структурные случаи движок называет сам (<see cref="ECoreStartFailure"/>); для вывода ядра
    /// разбираем его собственный текст — он английский, версионно-нестабильный и пользователю не
    /// показывается ни при каких условиях, в интерфейс идёт только наша фраза.
    /// </summary>
    internal static string DescribeConnectFailure(ECoreStartFailure kind, string? raw)
    {
        switch (kind)
        {
            case ECoreStartFailure.NoServer:
                return L.T("Home_FailNoServer");

            case ECoreStartFailure.CoreMissing:
                return L.T("Home_FailCoreMissing");

            case ECoreStartFailure.ConfigFailed:
                return L.T("Home_FailConfig");

            case ECoreStartFailure.CoreOutput:
                break;

            default:
                return L.T("Home_FailUnknown");
        }

        //  Разбираем ТОЛЬКО строки, похожие на отказ. Хвост вывода — это последние строки ядра
        //  целиком, включая обычный журнал: искать приметы по всему хвосту значило бы ловить
        //  «tun» или «invalid» в проходной информационной записи и объявлять не ту причину.
        var text = string.Join(
            "\n",
            (raw ?? string.Empty)
                .Split('\n')
                .Select(line => line.ToLowerInvariant())
                .Where(line => line.Contains("failed")
                               || line.Contains("error")
                               || line.Contains("panic")
                               || line.Contains("fatal")
                               || line.Contains("cannot")
                               || line.Contains("unable")
                               || line.Contains("refused")
                               || line.Contains("denied")
                               || line.Contains("in use")));

        if (text.Length == 0)
        {
            return L.T("Home_FailUnknown");
        }

        //  Порт занят: второй VPN, второй экземпляр приложения, чужая программа на том же порту.
        if (text.Contains("address already in use")
            || text.Contains("only one usage of each socket address")
            || text.Contains("failed to listen"))
        {
            return L.T("Home_FailPortBusy");
        }

        //  Сетевой адаптер/маршруты: нужны права администратора (Windows) или root (Linux/macOS).
        if (text.Contains("wintun")
            || text.Contains("tun device")
            || text.Contains("create tun")
            || text.Contains("operation not permitted")
            || text.Contains("access is denied")
            || text.Contains("administrator"))
        {
            return L.T("Home_FailNoRights");
        }

        //  Сервер отказал/недоступен — сеть, а не настройки. Сюда же — sing-box, не скачавший
        //  набор правил («initial rule-set»): качает он его через туннель, то есть через этот же
        //  сервер, и срыв значит, что трафик сервер не пропускает (обрыв, «context deadline
        //  exceeded», EOF — формулировки разные, суть одна).
        if (text.Contains("connection refused")
            || text.Contains("no such host")
            || text.Contains("i/o timeout")
            || text.Contains("network is unreachable")
            || text.Contains("initial rule-set"))
        {
            return L.T("Home_FailServerRefused");
        }

        //  Ядро прочитало конфиг и отвергло его: поля узла из подписки не годятся этой версии ядра.
        if (text.Contains("failed to load config")
            || text.Contains("failed to build")
            || text.Contains("failed to parse")
            || text.Contains("unmarshal")
            || text.Contains("decode config"))
        {
            return L.T("Home_FailCoreRejected");
        }

        return L.T("Home_FailUnknown");
    }

    /// <summary>Снимает признак переключения вместе с очередью — одной строкой во всех местах,
    /// где переключение кончилось не подключением (отключение, отказ, отменённый выбор).</summary>
    private void ClearSwitchQueue()
    {
        _switching = false;
        _pendingServerId = null;
    }

    /// <summary>
    /// Применяет выбор, отложенный на время переключения. Зовётся из обработчиков состояния ядра
    /// ПОСЛЕ <see cref="SyncState"/>, то есть когда состояние уже определилось. Очередь на один
    /// элемент: значение забирается сразу, поэтому повторных применений быть не может.
    /// </summary>
    private void ApplyPendingServer()
    {
        if (_switching)
        {
            return;   // ещё не осело
        }

        var pending = _pendingServerId;
        _pendingServerId = null;
        if (pending is null)
        {
            return;
        }

        //  Подключения нет — применять нечего: тап по строке никогда не поднимает туннель сам.
        //  Выбор уже совпал с активным — переподключаться незачем.
        if (!IsConnected || string.Equals(pending, _config?.IndexId, StringComparison.Ordinal))
        {
            return;
        }

        _ = SelectServer(pending);
    }

    private static string FormatUptime(TimeSpan t) =>
        $"{(int)t.TotalHours:D2}:{t.Minutes:D2}:{t.Seconds:D2}";

    /// <summary>
    /// Core start/stop transition (already marshalled to the UI thread). Sync the shield/uptime once
    /// and (re)evaluate the transient 1s tick.
    /// </summary>
    private void OnCoreRunningStateChanged(bool running)
    {
        // A real STOP transition ends any mid-switch hold: the old core went down, so the next
        // start is the NEW connection. Use the event's own flag rather than a live IsCoreRunning()
        // probe — this marshalled callback can arrive AFTER LoadCore's back-to-back stop→start has
        // already brought the new core up, which a probe would misread as "still running".
        if (!running)
        {
            _awaitingCoreCycle = false;
        }
        SyncState();
        UpdateStateTick();
        ApplyPendingServer();
    }

    /// <summary>
    /// A seamless server switch (Tier 1/Tier 2) completed successfully. The switch keeps the tunnel up
    /// and raises no stop event, so resolve the mid-switch Connecting hold NOW instead of waiting on the
    /// 12s deadline: clear the hold, snap to Connected, and re-sync the shield.
    /// </summary>
    private void OnCoreSwitchSettled()
    {
        _awaitingCoreCycle = false;
        _connectingUntil = null;
        IsConnecting = false;
        IsConnected = true;
        ClearConnectFailure();
        SyncState();
        UpdateStateTick();
        ApplyPendingServer();
    }

    /// <summary>
    /// Автовосстановление сдалось (см. <see cref="AppEvents.CoreRecoveryFailed"/>). Причина уже лежит
    /// в <see cref="CoreManager.LastStartFailure"/>, поэтому щит показывает отказ с подсказкой, а не
    /// тихое «Не подключено». Если пользователь тем временем сам начал подключение или ядро снова
    /// работает — это уже другая попытка, её не трогаем.
    /// </summary>
    private void OnCoreRecoveryFailed()
    {
        if (!IsConnecting && !IsCoreRunning())
        {
            MarkConnectFailed();
            ClearSwitchQueue();
        }
        SyncState();
        UpdateStateTick();
    }

    /// <summary>
    /// The transient 1s tick exists ONLY while connected (to advance the uptime clock) OR while a
    /// connect is pending (to enforce the connect deadline now that the permanent 1s poll is gone —
    /// the disconnected-idle app has no timer at all). It stops itself the moment neither holds.
    /// </summary>
    private void UpdateStateTick()
    {
        if (IsCoreRunning() || IsConnecting)
        {
            if (_uptimeTimer == null)
            {
                _uptimeTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
                _uptimeTimer.Tick += OnUptimeTick;
            }
            _uptimeTimer.Start();
        }
        else
        {
            StopUptimeTick();
        }
    }

    private void OnUptimeTick(object? sender, EventArgs e)
    {
        SyncState();
        // Stop once neither connected nor still attempting — covers a normal disconnect, a
        // self-healed silent core crash, and a connect that timed out (SyncState flips the flags).
        UpdateStateTick();
        //  Страховка на случай, когда событие ядра не пришло вовсе и переключение осело по сроку:
        //  отложенный выбор не должен зависнуть в очереди до конца сеанса.
        ApplyPendingServer();
    }

    private void StopUptimeTick()
    {
        if (_uptimeTimer != null)
        {
            _uptimeTimer.Stop();
            _uptimeTimer.Tick -= OnUptimeTick;
            _uptimeTimer = null;
        }
    }

    #endregion Connect / disconnect

    #region Import / refresh (wraps MainWindowViewModel)

    public async Task AddViaClipboard()
    {
        if (_main != null)
        {
            await _main.AddServerViaClipboardAsync(null);
        }
    }

    public async Task AddViaQr()
    {
        if (_main != null)
        {
            await _main.AddServerViaScanAsync();
        }
    }

    public async Task RefreshSubscription()
    {
        if (_main != null)
        {
            await _main.UpdateSubscriptionProcess("", false);
        }
    }

    #endregion Import / refresh

    #region Grouped list projection

    // Coalesce the transient Clear()+AddRange() burst the engine emits on EVERY refresh/select
    // (ProfilesViewModel.RefreshServersBiz rebuilds ProfileItems wholesale) into ONE reconcile on the
    // next dispatcher tick. Reconciling synchronously on the Clear() would observe a transient
    // count==0 and (a) latch IsEmpty=true for one frame — the MainWindow shell faithfully crossfades
    // to the onboarding surface and back, i.e. the "black flash" on select — and (b) tear every group
    // down only to rebuild it on the following AddRange, defeating the in-place diff. Deferring makes
    // the reconcile read the SETTLED list (post-AddRange): a pure selection then flips only IsActive,
    // while a GENUINE empty (logout / no subs) still latches onboarding because the settled count
    // really is zero. A single pending post absorbs any number of bursts before it fires.
    private void OnProfileItemsChanged(object? sender, NotifyCollectionChangedEventArgs e)
    {
        //  Движок пересобирает коллекцию целиком (Clear + AddRange), и Clear поднимает событие даже на
        //  пустой базе. Значит, первое событие и есть «база прочитана», при любом числе серверов.
        _sourceLoaded = true;
        ScheduleReconcile();
    }

    private void ScheduleReconcile()
    {
        if (_reconcilePending)
        {
            return;
        }
        _reconcilePending = true;
        //  Первая сверка — вне фоновой очереди. Пока список не прочитан, окно держит пустой кадр
        //  (IsResolved), а сверка на фоновом приоритете ждала, пока окно само разложится и нарисуется:
        //  база отдавала список ещё ДО первого кадра, но на экране сперва стоял голый фон, и
        //  «Главная» со списком приходила на 300–400 мс позже. Обычный приоритет идёт раньше раскладки
        //  и отрисовки, поэтому первый же кадр окна несёт список. Склейке Clear+AddRange приоритет не
        //  мешает: оба вызова синхронны и отрабатывают до любой отложенной задачи. Дальше — фон, как
        //  было: обновления списка уступают вводу и отрисовке.
        Dispatcher.UIThread.Post(
            () =>
            {
                _reconcilePending = false;
                ReconcileGroups();
            },
            IsResolved ? DispatcherPriority.Background : DispatcherPriority.Normal);
    }

    /// <summary>
    /// Project <c>Profiles.ProfileItems</c> onto the grouped <see cref="ServerGroups"/> by RECONCILING
    /// the existing collection IN PLACE — never <c>Clear()</c>+rebuild (Bug 6). The engine rebuilds
    /// ProfileItems wholesale (Clear + AddRange of brand-new instances) on EVERY change, including a
    /// mere active-flag flip when a server is selected. A blind rebuild made the non-virtualizing
    /// ItemsControl destroy + recreate all group headers, rows and reveal containers (and re-run the
    /// SubscriptionMetaView async resolve) → the visible jerk/flash on every tap.
    ///
    /// Instead we diff:
    ///   • groups by <see cref="HomeServerGroup.Key"/> (<c>{subid}|{name}</c>) — add / remove / move
    ///     only when the grouping itself changes;
    ///   • rows within a matched group by <c>IndexId</c> — a persisting row keeps its container and
    ///     only its reactive fields (IsActive / Delay / …) are copied across, so the selected-pill
    ///     binding (<c>Classes.selected="{Binding IsActive}"</c>) updates smoothly with NO teardown.
    /// A pure selection therefore mutates nothing structural: only two rows' IsActive flip. A full
    /// container rebuild now happens ONLY for a genuine grouping change (sub added/removed/reordered,
    /// server added/removed/reordered, or a renamed row whose displayed non-reactive fields changed).
    /// </summary>
    private void ReconcileGroups()
    {
        var items = Profiles?.ProfileItems;
        var count = items?.Count ?? 0;
        HasServers = count > 0;
        IsEmpty = !HasServers;

        var plan = BuildGroupPlan(items, out var providers);
        ReconcileServerGroups(plan);

        Subtitle = FormatServersProvidersMeta(count, providers);

        //  Поднимаем ПОСЛЕ IsEmpty и групп: оболочка, увидев «прочитано», сразу берёт готовый список.
        if (_sourceLoaded && !IsResolved)
        {
            IsResolved = true;
        }

        //  Список только что мог смениться — указатель «строка по IndexId» строится заново при
        //  первом же обращении. Это ЕДИНСТВЕННОЕ место, где меняются ServerGroups и их Servers.
        _rowIndex = null;

        // Point the per-item ping/speedtest live-sync at the current (possibly rebuilt) source items.
        ResyncItemSubscriptions();
    }

    /// <summary>(Re)attach the per-item PropertyChanged handler to the live ProfileItems so an in-place
    /// latency/speed update propagates to the displayed rows. Detaches the previous set first — the
    /// engine rebuilds ProfileItems wholesale, so stale instances must be dropped.</summary>
    private void ResyncItemSubscriptions()
    {
        foreach (var it in _observedItems)
        {
            it.PropertyChanged -= OnSourceItemChanged;
        }
        _observedItems.Clear();

        var items = Profiles?.ProfileItems;
        if (items == null)
        {
            return;
        }
        foreach (var it in items)
        {
            it.PropertyChanged += OnSourceItemChanged;
            _observedItems.Add(it);
        }
    }

    /// <summary>A source ProfileItems row changed a REACTIVE field in place (ping/speedtest result,
    /// selection). Mirror just that state onto the matching displayed row (by IndexId) so the ping
    /// spinner and result appear without a full reconcile. Runs on the UI thread (the engine marshals
    /// SetSpeedTestResult through the main scheduler).</summary>
    private void OnSourceItemChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (sender is not ProfileItemModel src)
        {
            return;
        }
        switch (e.PropertyName)
        {
            case nameof(ProfileItemModel.Delay):
            case nameof(ProfileItemModel.DelayVal):
            case nameof(ProfileItemModel.SpeedVal):
            case nameof(ProfileItemModel.IpInfo):
            case nameof(ProfileItemModel.IsActive):
                break;
            default:
                return;
        }

        var row = FindRowByIndexId(src.IndexId);
        if (row == null || ReferenceEquals(row, src))
        {
            return;   // no displayed copy, or the row IS the source (INPC already propagates)
        }
        row.Delay = src.Delay;
        row.DelayVal = src.DelayVal;
        row.SpeedVal = src.SpeedVal;
        row.IpInfo = src.IpInfo;
        row.IsActive = src.IsActive;
    }

    /// <summary>
    /// Строка по IndexId — СЛОВАРЁМ, а не обходом всех групп.
    ///
    /// <see cref="OnSourceItemChanged"/> приходит на каждое реактивное поле каждого результата
    /// пинга (Delay, DelayVal, SpeedVal, IpInfo), и каждый обход просматривал весь список. Один
    /// прогон «пинг всех» на 150 серверах — это 150 результатов × 4 поля × в среднем 75 сравнений
    /// строк ≈ 45 000 сравнений, почти всегда ради вывода «найденная строка И ЕСТЬ отправитель,
    /// копировать нечего». Теперь это 600 поисков в словаре.
    ///
    /// Словарь строится лениво и сбрасывается ровно там, где список меняется, — в
    /// <see cref="ReconcileGroups"/>: ни <see cref="ServerGroups"/>, ни Servers внутри групп не
    /// меняются нигде больше. Первое совпадение, как и раньше: <c>TryAdd</c> не затирает.
    /// </summary>
    private ProfileItemModel? FindRowByIndexId(string? indexId)
    {
        if (string.IsNullOrEmpty(indexId))
        {
            return null;
        }
        var index = _rowIndex ??= BuildRowIndex();
        return index.TryGetValue(indexId, out var row) ? row : null;
    }

    private Dictionary<string, ProfileItemModel> BuildRowIndex()
    {
        var map = new Dictionary<string, ProfileItemModel>(StringComparer.Ordinal);
        foreach (var g in ServerGroups)
        {
            foreach (var r in g.Servers)
            {
                if (r.IndexId.IsNotEmpty())
                {
                    map.TryAdd(r.IndexId!, r);
                }
            }
        }
        return map;
    }

    /// <summary>
    /// Имя группы серверов. Порядок источников — от самого правдивого к самому слабому:
    ///
    ///  1. НАСТОЯЩЕЕ название подписки, которое прислал провайдер заголовком profile-title. Оно
    ///     появляется после первой же загрузки подписки и не зависит от того, как её добавили.
    ///  2. Пометка самой подписки, если она что-то называет.
    ///  3. Общий заголовок «Мои серверы».
    ///
    /// Раньше здесь читалась ТОЛЬКО пометка. Добавление ссылки из буфера штампует пометку
    /// «import_sub» — заглушку, которую показывать нельзя, — и группа навсегда оставалась
    /// «Моими серверами», хотя настоящее имя уже лежало рядом, в той же записи подписки.
    /// </summary>
    private string GroupNameFor(ProfileItemModel item)
    {
        var subId = item.Subid ?? string.Empty;
        if (subId.IsNotEmpty())
        {
            var sub = Profiles?.SubItems.FirstOrDefault(s => s.Id == subId);
            var title = SubscriptionSyncManager.DisplayName(sub?.ProfileTitle);
            if (title.IsNotEmpty())
            {
                return title!;
            }
        }
        return SubscriptionSyncManager.DisplayName(item.SubRemarks) ?? L.T("Home_MyServers");
    }

    /// <summary>The desired shape of one group for a reconcile pass (no view objects allocated yet).</summary>
    private readonly struct GroupPlan
    {
        public GroupPlan(string key, string name, bool pinned, bool expanded, List<ProfileItemModel> servers)
        {
            Key = key;
            Name = name;
            Pinned = pinned;
            Expanded = expanded;
            Servers = servers;
        }

        public string Key { get; }
        public string Name { get; }
        public bool Pinned { get; }
        public bool Expanded { get; }
        public List<ProfileItemModel> Servers { get; }
    }

    /// <summary>Compute the desired ordered grouping from the live ProfileItems (same rule as before:
    /// group by subscription, pinned subs first). Purely a data projection — it touches no view state.</summary>
    private List<GroupPlan> BuildGroupPlan(IList<ProfileItemModel>? items, out int providers)
    {
        providers = 0;
        var plan = new List<GroupPlan>();
        if (items == null || items.Count == 0)
        {
            return plan;
        }

        var grouped = items
            .GroupBy(i => new
            {
                Key = i.Subid ?? string.Empty,
                Name = GroupNameFor(i),
            })
            .ToList();

        providers = grouped.Count(g => !string.IsNullOrEmpty(g.Key.Key));
        if (providers == 0)
        {
            providers = grouped.Count;
        }

        // A9: pinned subscriptions float to the top. SubItem.Pinned is read from the in-memory
        // sub cache (Profiles.SubItems, keyed by Subid == the group key).
        //
        //  Под закреплёнными — порядок САМИХ подписок (SubItem.Sort, в нём и лежит кэш), а не порядок
        //  строк в базе. Раньше группы шли в порядке первой строки, а обновление подписки удаляет её
        //  серверы и вставляет заново — в КОНЕЦ таблицы. Каждое обновление уводило свою группу вниз:
        //  на запуске с двумя подписками первый кадр показывал сверху вторую (первую только что
        //  обновил импорт аккаунта), а через полсекунды, когда обновлялась вторая, группы менялись
        //  местами на глазах. Группы без подписки (свои серверы) — после подписок. Сортировка
        //  устойчивая: равные сохраняют прежний порядок.
        var subs = Profiles?.SubItems.ToList() ?? [];
        var ordered = grouped
            .Select(g =>
            {
                var position = g.Key.Key.IsNullOrEmpty() ? -1 : subs.FindIndex(s => s.Id == g.Key.Key);
                return new
                {
                    Group = g,
                    Pinned = position >= 0 && subs[position].Pinned,
                    Position = position >= 0 ? position : int.MaxValue,
                };
            })
            .OrderByDescending(x => x.Pinned)
            .ThenBy(x => x.Position)
            .ToList();

        foreach (var x in ordered)
        {
            var g = x.Group;
            var key = $"{g.Key.Key}|{g.Key.Name}";
            var expanded = !_groupExpanded.TryGetValue(key, out var ex) || ex;
            plan.Add(new GroupPlan(key, g.Key.Name, x.Pinned, expanded, g.ToList()));
        }

        return plan;
    }

    /// <summary>Reconcile the live <see cref="ServerGroups"/> collection against the plan, mutating
    /// only what actually differs. Matched groups are updated in place (see <see cref="GroupPlan"/>).</summary>
    private void ReconcileServerGroups(List<GroupPlan> plan)
    {
        // 1. Drop groups that no longer exist (match by Key). Only their own containers are torn down.
        var planKeys = new HashSet<string>(StringComparer.Ordinal);
        foreach (var p in plan)
        {
            planKeys.Add(p.Key);
        }
        for (var i = ServerGroups.Count - 1; i >= 0; i--)
        {
            if (!planKeys.Contains(ServerGroups[i].Key))
            {
                ServerGroups.RemoveAt(i);
            }
        }

        // 2. Walk the plan in order: insert new groups, move displaced ones, update matched ones in
        //    place. Everything left of i is already correct, so a match is found at index >= i.
        for (var i = 0; i < plan.Count; i++)
        {
            var p = plan[i];
            var existingIndex = IndexOfGroup(p.Key);
            if (existingIndex < 0)
            {
                // A genuinely new subscription group — only this one container is created.
                ServerGroups.Insert(i, new HomeServerGroup(p.Key, p.Name, p.Servers, p.Expanded, p.Pinned, OnGroupExpandedChanged));
                continue;
            }
            if (existingIndex != i)
            {
                ServerGroups.Move(existingIndex, i);
            }
            var group = ServerGroups[i];
            group.UpdateHeader(p.Name, p.Pinned);
            group.ReconcileServers(p.Servers);
        }
    }

    private int IndexOfGroup(string key)
    {
        for (var i = 0; i < ServerGroups.Count; i++)
        {
            if (string.Equals(ServerGroups[i].Key, key, StringComparison.Ordinal))
            {
                return i;
            }
        }
        return -1;
    }

    /// <summary>Compose the "{n} servers · {n} providers" meta line from the locale-aware plural
    /// forms (Common_ServersPlural / Common_ProvidersPlural) and the Home_ServersProvidersMeta
    /// template. Shared by the live list and the design-time sample so neither hardcodes the words.</summary>
    private static string FormatServersProvidersMeta(int count, int providers) =>
        L.F("Home_ServersProvidersMeta", L.Plural("Common_ServersPlural", count), L.Plural("Common_ProvidersPlural", providers));

    private void OnGroupExpandedChanged(string key, bool expanded) => _groupExpanded[key] = expanded;

    private void OnLanguageChanged(object? sender, EventArgs e) => ReconcileGroups();

    #endregion Grouped list projection

    #region Teardown

    /// <summary>
    /// Tear down the event-driven core-state subscription, the live-speed subscription, the
    /// per-second uptime tick, and the ProfileItems change hook (B3). HomeViewModel is a plain
    /// app-lifetime VM (no view-activation lifecycle), so disposal runs when the shell disposes it.
    /// </summary>
    public void Dispose()
    {
        if (Profiles != null)
        {
            Profiles.ProfileItems.CollectionChanged -= OnProfileItemsChanged;
        }
        foreach (var it in _observedItems)
        {
            it.PropertyChanged -= OnSourceItemChanged;
        }
        _observedItems.Clear();
        L.Instance.LanguageChanged -= OnLanguageChanged;
        StopUptimeTick();
        _coreStateSub?.Dispose();
        _switchSettledSub?.Dispose();
        _recoveryFailedSub?.Dispose();
        _statsSub?.Dispose();
    }

    #endregion Teardown

    #region Design-time

    /// <summary>Design-only factory — sample rows so the previewer renders (runtime stays real/empty).</summary>
    public static HomeViewModel CreateDesign()
    {
        var vm = new HomeViewModel();
        var servers = new List<ProfileItemModel>
        {
            new() { Remarks = "Germany", ConfigType = EConfigType.VLESS, Network = "tcp", StreamSecurity = "reality", IsActive = true, IndexId = "d1" },
            new() { Remarks = "Finland", ConfigType = EConfigType.VLESS, Network = "tcp", StreamSecurity = "reality", IndexId = "d2" },
            new() { Remarks = "Netherlands", ConfigType = EConfigType.VLESS, Network = "grpc", StreamSecurity = "reality", IndexId = "d3" },
            new() { Remarks = "France", ConfigType = EConfigType.Trojan, Network = "tcp", StreamSecurity = "tls", IndexId = "d4" },
            new() { Remarks = "Japan", ConfigType = EConfigType.Shadowsocks, Network = "tcp", StreamSecurity = "", IndexId = "d5" },
        };
        vm.ServerGroups.Add(new HomeServerGroup("sub|import sub", "import sub", servers, true));
        vm.HasServers = true;
        vm.IsEmpty = false;
        vm.Subtitle = FormatServersProvidersMeta(5, 1);
        return vm;
    }

    #endregion Design-time
}

/// <summary>
/// One subscription group in the Home server list. <see cref="Servers"/> are real
/// <see cref="ProfileItemModel"/>s; the header is collapsible (default expanded).
/// </summary>
public sealed class HomeServerGroup : INotifyPropertyChanged
{
    private readonly Action<string, bool>? _onExpandedChanged;
    private bool _isExpanded;
    private string _name;
    private bool _pinned;

    public HomeServerGroup(string key, string name, IEnumerable<ProfileItemModel> servers, bool isExpanded, bool pinned = false, Action<string, bool>? onExpandedChanged = null)
    {
        Key = key;
        _name = name;
        Servers = new ObservableCollection<ProfileItemModel>(servers);
        _isExpanded = isExpanded;
        _pinned = pinned;
        _onExpandedChanged = onExpandedChanged;
    }

    public string Key { get; }

    public string Name
    {
        get => _name;
        private set
        {
            if (string.Equals(_name, value, StringComparison.Ordinal))
            {
                return;
            }
            _name = value;
            OnPropertyChanged();
        }
    }

    /// <summary>
    /// Live server rows for this group. An <see cref="ObservableCollection{T}"/> (not a static list)
    /// so it can be reconciled IN PLACE via <see cref="ReconcileServers"/> — a selection / refresh
    /// updates rows through their existing containers instead of tearing every row down (Bug 6).
    /// </summary>
    public ObservableCollection<ProfileItemModel> Servers { get; }

    /// <summary>True when this subscription is pinned — pinned groups are ordered first (A9).</summary>
    public bool Pinned
    {
        get => _pinned;
        private set
        {
            if (_pinned == value)
            {
                return;
            }
            _pinned = value;
            OnPropertyChanged();
        }
    }

    public int Count => Servers.Count;
    public string CountText => Count.ToString();

    /// <summary>Update header fields that can shift WITHOUT changing the group's identity (Key), so a
    /// pin toggle / re-projection keeps this exact group instance (its expand state, its hooked
    /// meta-bar and reveal container) rather than replacing it.</summary>
    internal void UpdateHeader(string name, bool pinned)
    {
        Name = name;
        Pinned = pinned;
    }

    /// <summary>
    /// Reconcile this group's rows against the latest engine projection IN PLACE, matching by
    /// <c>IndexId</c>. A row that persists keeps its existing container — only its reactive fields
    /// (IsActive / Delay / DelayVal / …) are copied across, so the selected-pill and ping bindings
    /// update with NO teardown/relayout (Bug 6). Rows are inserted / removed / reordered only for a
    /// genuine membership or order change; a row whose DISPLAYED (non-reactive) fields changed — e.g.
    /// a rename — is swapped so that one row re-renders.
    /// </summary>
    internal void ReconcileServers(IReadOnlyList<ProfileItemModel> desired)
    {
        var before = Servers.Count;

        // Remove rows that are gone (by IndexId).
        var desiredIds = new HashSet<string>(StringComparer.Ordinal);
        foreach (var d in desired)
        {
            desiredIds.Add(d.IndexId ?? string.Empty);
        }
        for (var i = Servers.Count - 1; i >= 0; i--)
        {
            if (!desiredIds.Contains(Servers[i].IndexId ?? string.Empty))
            {
                Servers.RemoveAt(i);
            }
        }

        // Insert / move / update to match the desired order. Positions left of i are already correct.
        for (var i = 0; i < desired.Count; i++)
        {
            var d = desired[i];
            var existingIndex = IndexOfServer(d.IndexId);
            if (existingIndex < 0)
            {
                Servers.Insert(i, d);
                continue;
            }
            if (existingIndex != i)
            {
                Servers.Move(existingIndex, i);
            }
            var current = Servers[i];
            if (SameDisplay(current, d))
            {
                // Same row — refresh only the reactive state so its container is NOT rebuilt.
                CopyLiveState(current, d);
            }
            else
            {
                // A displayed but non-reactive field changed (rename / protocol shift) — swap the
                // instance so ONLY this one row re-renders.
                Servers[i] = d;
            }
        }

        if (Servers.Count != before)
        {
            OnPropertyChanged(nameof(Count));
            OnPropertyChanged(nameof(CountText));
        }
    }

    private int IndexOfServer(string? indexId)
    {
        for (var i = 0; i < Servers.Count; i++)
        {
            if (string.Equals(Servers[i].IndexId, indexId, StringComparison.Ordinal))
            {
                return i;
            }
        }
        return -1;
    }

    /// <summary>The row's displayed, NON-reactive fields — when these all match, the two instances
    /// render identically and only their reactive state can differ, so an in-place copy suffices.</summary>
    private static bool SameDisplay(ProfileItemModel a, ProfileItemModel b) =>
        string.Equals(a.Remarks, b.Remarks, StringComparison.Ordinal)
        && a.ConfigType == b.ConfigType
        && string.Equals(a.ProtocolDisplay, b.ProtocolDisplay, StringComparison.Ordinal)
        && string.Equals(a.Network, b.Network, StringComparison.Ordinal)
        && string.Equals(a.StreamSecurity, b.StreamSecurity, StringComparison.Ordinal)
        && string.Equals(a.Subid, b.Subid, StringComparison.Ordinal)
        && string.Equals(a.SubRemarks, b.SubRemarks, StringComparison.Ordinal);

    /// <summary>Copy the [Reactive] fields the rows observe onto the retained instance. Each set is a
    /// no-op when unchanged (ReactiveObject raises only on real change), so a pure selection touches
    /// just IsActive — the selected pill flips, nothing else moves.</summary>
    private static void CopyLiveState(ProfileItemModel target, ProfileItemModel source)
    {
        target.IsActive = source.IsActive;
        target.Delay = source.Delay;
        target.DelayVal = source.DelayVal;
        target.SpeedVal = source.SpeedVal;
        target.IpInfo = source.IpInfo;
        target.TodayUp = source.TodayUp;
        target.TodayDown = source.TodayDown;
        target.TotalUp = source.TotalUp;
        target.TotalDown = source.TotalDown;
    }

    public bool IsExpanded
    {
        get => _isExpanded;
        set
        {
            if (_isExpanded == value)
            {
                return;
            }
            _isExpanded = value;
            _onExpandedChanged?.Invoke(Key, value);
            OnPropertyChanged();
        }
    }

    public event PropertyChangedEventHandler? PropertyChanged;

    private void OnPropertyChanged([CallerMemberName] string? name = null) =>
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}
