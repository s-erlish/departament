namespace ServiceLib.Manager;

/// <summary>
/// Core process processing class
/// </summary>
public class CoreManager
{
    private static readonly Lazy<CoreManager> _instance = new(() => new());
    public static CoreManager Instance => _instance.Value;
    private Config _config;

    [SupportedOSPlatform("windows")]
    private WindowsJobService? _processJob;

    private ProcessService? _processService;
    private ProcessService? _processPreService;
    private bool _linuxSudo = false;
    private Func<bool, string, Task>? _updateFunc;
    private const string _tag = "CoreHandler";

    // Seamless server-switch state, captured on every FULL (re)start from the config actually written
    // to disk. Used by SwitchServer to decide whether a live make-before-break switch is possible:
    //   _runningProxyTag — the proxy outbound tag the LIVE routing references (Global.ProxyTag for a
    //                      normal node, the provider template's first proxy-protocol outbound tag for
    //                      a custom node). A hot-swap re-adds the new server UNDER THIS SAME tag so the
    //                      untouched routing keeps pointing at it. Null ⇒ no proxy outbound / unknown.
    //   _runningApiPort  — the Xray HandlerService api port bound by the running config, or 0 when the
    //                      running core exposes no HandlerService api (⇒ hot-swap tier unavailable).
    // Guards concurrent/rapid switches so hot-swaps never stack.
    private string? _runningProxyTag;
    private int _runningApiPort;
    private readonly SemaphoreSlim _switchSemaphore = new(1, 1);

    // ── Crash detection / auto-restart / health-probe watchdog ──────────────────────────────────────
    // TRUE only for the brief window of an INTENTIONAL teardown (CoreStop, the stop-before-start inside
    // LoadCore, a SwitchServer Tier-1 teardown, app exit). While set, a process Exited callback or a
    // watchdog liveness miss is treated as expected, NOT as a crash. This is the primary guard that
    // keeps the auto-restart path from firing on a deliberate stop.
    private volatile bool _stopping;

    // The last main + pre contexts actually handed to LoadCore (and refreshed on a seamless switch), so
    // a crash can re-run the SAME full-restart primitive with the CURRENT server. Never reaches up into
    // any Desktop/ViewModel layer — recovery is fully self-contained in ServiceLib.
    private CoreConfigContext? _lastMainContext;
    private CoreConfigContext? _lastPreContext;

    //  ПРИЧИНА ПОСЛЕДНЕГО НЕУДАЧНОГО ЗАПУСКА — сырым текстом (сообщение о ненайденном ядре, ошибка
    //  сборки конфига, хвост stderr самого ядра). Раньше всё это уходило только в панель журнала,
    //  а наверх поднималось безымянное «Не удалось запустить ядро»: пользователь видел красный щит
    //  без единого слова о том, ЧТО не так. Экран «Главная» читает это поле и переводит его в
    //  человеческую подсказку под щитом. Сбрасывается на каждой новой попытке и на успехе.
    public string? LastStartError { get; private set; }

    /// <summary>Чем кончилась последняя попытка. См. <see cref="ECoreStartFailure"/>.</summary>
    public ECoreStartFailure LastStartFailure { get; private set; } = ECoreStartFailure.None;

    /// <summary>Записать причину отказа подключения. Пустой текст не записывается: он затёр бы более
    /// внятную причину, уже найденную в ходе той же попытки.</summary>
    public void ReportStartFailure(ECoreStartFailure kind, string? reason)
    {
        if (reason.IsNullOrEmpty())
        {
            return;
        }
        LastStartFailure = kind;
        LastStartError = reason;
    }

    /// <summary>Новая попытка подключения — прошлая причина больше не про неё.</summary>
    public void ClearStartFailure()
    {
        LastStartFailure = ECoreStartFailure.None;
        LastStartError = null;
    }

    // Tier 2 (live `xray api rmo/ado` outbound hot-swap) is DISABLED. It declared success on the api
    // command's exit code alone, which does not prove traffic actually moved: against the panel's custom
    // XRAY_JSON (Remnawave) configs the swap could exit 0 yet leave routing on the previous outbound, so
    // the UI painted "connected → new server" while the real exit IP stayed on the FIRST server, and the
    // "success" suppressed the fallback. Until the live swap is verified to actually re-route (an exit-IP
    // probe) against a real panel, seamless switches go through Tier 1 (restart only the Xray main core,
    // keeping sing-box + the tun adapter alive) — a genuine config reload, so the new server is
    // GUARANTEED, with no adapter flap. Flip to true only once Tier 2 is proven to move traffic.
    private static readonly bool EnableHotSwapTier = false;

    // THE single serialization point for ALL core start/stop state transitions (LoadCoreInternal /
    // CoreStopInternal, the SwitchServer seamless tiers, and every recovery reload). It is the INNERMOST
    // lock — nothing is ever acquired while it is held — so it can never deadlock against _reloadSemaphore
    // (VM), _switchSemaphore, or _restartGate. Guarantees a user reload/disconnect and a background
    // auto-restart/health-check reload never touch _processService concurrently (C1/H1).
    private readonly SemaphoreSlim _coreOpGate = new(1, 1);

    // "Recovery driver" singleton: ensures at most one auto-restart loop OR health-check reload runs at a
    // time. Held OUTSIDE _coreOpGate (order: _restartGate → _coreOpGate; never the reverse).
    private readonly SemaphoreSlim _restartGate = new(1, 1);
    private readonly object _restartStatsLock = new();
    //  Перезапуски ПОДРЯД, ни один из которых не продержался _restartWindow. Раньше счётчик жил в
    //  окне 60 с от первой попытки: ядро, падающее раз в 15–16 с (sing-box, не дождавшийся набора
    //  правил), укладывалось в окно по четыре раза, окно обнулялось, и круг «подключено → упало →
    //  снова» шёл бесконечно — до отказа с причиной дело не доходило никогда. Теперь счётчик
    //  сбрасывают только устойчивая работа (сторож) и новое подключение пользователя.
    private int _restartAttempts;
    private DateTime? _coreUpSince;

    // Sticky user-stop intent — set by CoreStop(byUser:true), cleared by a USER connect. While set, no
    // recovery path may re-establish the tunnel.
    private volatile bool _userStopRequested;
    // Bumped by EVERY public CoreStop (any caller: disconnect, tray, app-exit, core update, logout). A
    // restart loop captures it at start and bails permanently once it changes — so any external stop
    // supersedes recovery even for call sites outside this class.
    private int _coreStopGeneration;
    // Cancels the in-flight restart loop's backoff wait / gate wait so a user disconnect breaks in at once.
    private CancellationTokenSource _restartLoopCts = new();

    // Health-probe watchdog (belt-and-suspenders for silent wedges + sleep/resume/network staleness).
    private CancellationTokenSource? _watchdogCts;

    // Debounce for RequestHealthCheckAsync so an OS resume + a network-change burst coalesce into one probe.
    private DateTime _lastHealthCheckRequest = DateTime.MinValue;

    private const int _maxRestartAttempts = 5;
    private static readonly TimeSpan _restartWindow = TimeSpan.FromSeconds(60);

    public async Task Init(Config config, Func<bool, string, Task> updateFunc)
    {
        _config = config;
        _updateFunc = updateFunc;

        //Copy the bin folder to the storage location (for init)
        if (Environment.GetEnvironmentVariable(Global.LocalAppData) == "1")
        {
            var fromPath = Utils.GetBaseDirectory("bin");
            var toPath = Utils.GetBinPath("");
            if (fromPath != toPath)
            {
                FileUtils.CopyDirectory(fromPath, toPath, true, false);
            }
        }

        if (Utils.IsNonWindows())
        {
            var coreInfo = CoreInfoManager.Instance.GetCoreInfo();
            foreach (var it in coreInfo)
            {
                if (it.CoreType == ECoreType.departament)
                {
                    if (Utils.UpgradeAppExists(out var upgradeFileName))
                    {
                        await Utils.SetLinuxChmod(upgradeFileName);
                    }
                    continue;
                }

                foreach (var name in it.CoreExes)
                {
                    var exe = Utils.GetBinPath(Utils.GetExeName(name), it.CoreType.ToString());
                    if (File.Exists(exe))
                    {
                        await Utils.SetLinuxChmod(exe);
                    }
                }
            }
        }
    }

    /// <summary>
    /// Public (USER-initiated) full connect/reload entry point: VM.Reload, preset apply, SwitchServer
    /// fallback via the VM path, etc. Serialized through <see cref="_coreOpGate"/> — the SINGLE gate all
    /// core start/stop transitions acquire — so a user reload can never run concurrently with the
    /// background auto-restart / health-check reload (H1). A user connect is an explicit intent to be
    /// connected, so it clears any sticky user-stop intent and arms a fresh restart-loop cancellation.
    /// </summary>
    /// <param name="mainContext">Resolved main context (with pre-socks ports already merged if applicable).</param>
    /// <param name="preContext">Optional pre-socks context passed to <see cref="CoreStartPreService"/>.</param>
    public async Task LoadCore(CoreConfigContext? mainContext, CoreConfigContext? preContext)
    {
        await _coreOpGate.WaitAsync();
        try
        {
            // A user connect supersedes any pending auto-restart abort and re-arms recovery.
            _userStopRequested = false;
            ResetRestartLoopCts();
            await LoadCoreInternal(mainContext, preContext);
        }
        finally
        {
            _coreOpGate.Release();
        }
    }

    /// <summary>
    /// Ungated core (re)start implementation. MUST be called with <see cref="_coreOpGate"/> already held
    /// (by <see cref="LoadCore"/>, <see cref="SwitchServer"/>, or the auto-restart/health-check reload).
    /// Never acquires the gate itself, so the internal stop-before-start below is safe (no re-entrancy).
    /// </summary>
    private async Task LoadCoreInternal(CoreConfigContext? mainContext, CoreConfigContext? preContext)
    {
        if (mainContext == null)
        {
            ReportStartFailure(ECoreStartFailure.NoServer, ResUI.CheckServerSettings);
            await UpdateFunc(false, ResUI.CheckServerSettings);
            return;
        }

        // Cache the contexts BEFORE starting so a later crash can re-run the SAME full restart with the
        // current server (this is also the switch surface a crash-restart lands on after a seamless swap).
        _lastMainContext = mainContext;
        _lastPreContext = preContext;

        var node = mainContext.Node;
        var fileName = Utils.GetBinConfigPath(Global.CoreConfigFileName);
        var result = await CoreConfigHandler.GenerateClientConfig(mainContext, fileName);
        if (result.Success != true)
        {
            ReportStartFailure(ECoreStartFailure.ConfigFailed, result.Msg);
            await UpdateFunc(true, result.Msg);
            return;
        }

        await UpdateFunc(false, $"{node.GetSummary()}");
        await UpdateFunc(false, $"{Utils.GetRuntimeInfo()}");
        await UpdateFunc(false, string.Format(ResUI.StartService, DateTime.Now.ToString("yyyy/MM/dd HH:mm:ss")));
        // CoreStopInternal stops any running cores AND (on Windows + TUN) removes the wintun adapter, so
        // a stale adapter from a previous session/crash can never break this connect. A single short
        // settle delay lets the OS release the freed local ports before we re-bind them. Uses the
        // INTERNAL stop (we already hold _coreOpGate) and does NOT bump the stop generation, so this
        // stop-before-start is never mistaken for an external/user stop that would abort a restart loop.
        await CoreStopInternal();
        await Task.Delay(100);

        // First start attempt. On a weak PC the very first connect after launch (or right after a
        // disconnect) sometimes fails transiently: the freed socks port / wintun adapter is not yet
        // released, or a CPU-starved core exits during its first spin-up. That is exactly the reported
        // «первый раз не подключается — жмёшь ещё раз и работает». So instead of returning an error and
        // making the user tap Connect a second time, we do ONE clean internal re-arm here. This is a
        // single retry, NOT a loop — a genuinely broken config still fails after the second attempt and
        // surfaces an honest error below.
        //  Причина прошлой попытки к этой не относится: чистим ПЕРЕД стартом, чтобы под щитом
        //  не всплыла позавчерашняя ошибка, если эта попытка сорвётся без единого слова.
        ClearStartFailure();
        var started = await TryStartCoresOnce(mainContext, preContext);
        if (!started)
        {
            // Tear down whatever half-started (frees the port / removes the stale adapter), let the OS
            // settle a touch longer than the first attempt, then try exactly once more.
            //  Снимаем причину ДО CoreStopInternal: он обнуляет _processService вместе с хвостом
            //  stderr. Вторая попытка перезапишет её только если ядро скажет что-то своё.
            await CaptureCoreErrorTail();
            await CoreStopInternal();
            await Task.Delay(300);
            started = await TryStartCoresOnce(mainContext, preContext);
        }

        if (started)
        {
            // Both the main core and (if required) the pre-service actually started — mark the app as
            // running so IsRunningCore()/the connect shield/tray honestly read "connected".
            // The MAIN core always owns the proxy, routing and traffic stats/metrics — for a CUSTOM
            // (Remnawave XRAY_JSON) node that is Xray, whose config carries stats/metrics (see
            // MergeAppInbounds). The pre-service (sing-box) is only a TUN/socks transport shim and
            // exposes no stats API. Reporting the pre-service's core type here would (a) make
            // StatisticsXrayService skip polling (RunningCoreType != Xray) so the traffic widget
            // stays blank, and (b) wrongly flip the app into Clash UI mode. So mark the app as
            // running under the MAIN core's type.
            AppManager.Instance.RunningCoreType = mainContext.RunCoreType;
            // Capture the hot-swap surface (proxy tag + api port) of the config we just started so a
            // later server switch can attempt a live make-before-break outbound swap.
            await CaptureSwitchContext(mainContext, fileName);
            // idle/perf B1: broadcast the running transition so the tray label, status-bar tray icon
            // and Home shield update event-driven instead of busy-polling IsRunningCore every second.
            AppEvents.CoreRunningStateChanged.Publish(true);
            // The core is up — (re)start the health-probe watchdog and mark the uptime clock so the
            // auto-restart attempt counter can reset after a stretch of stable connected time.
            _coreUpSince = DateTime.Now;
            StartWatchdog();
            //  Подключились — прошлой причине отказа больше нечего объяснять.
            ClearStartFailure();
            await UpdateFunc(true, $"{node.GetSummary()}");
        }
        else
        {
            // Either the main core failed to start (RunProcess returned null: wrong exe / blocked /
            // crash) OR a required TUN/pre-socks pre-service failed. Tear down any half-started core
            // so no orphan process lingers, and do NOT leave RunningCoreType set — otherwise
            // IsRunningCore() would falsely report "connected" (blue shield + tray + false
            // «Подключено» toast) with a dead tunnel behind it. CoreStopInternal resets RunningCoreType
            // to the idle sentinel; the next start re-assigns it. Internal stop — we hold _coreOpGate.
            await CaptureCoreErrorTail();
            await CoreStopInternal();
            await UpdateFunc(true, ResUI.FailedToRunCore);
        }
    }

    #region Seamless server switch

    /// <summary>
    /// Switch the active server SEAMLESSLY while connected, degrading through a fallback chain so the
    /// user is never left disconnected and the path is never worse than a full restart:
    ///
    ///   Tier 2 (preferred) — HOT-SWAP the Xray proxy outbound at runtime via `xray api rmo/ado`.
    ///       No process restart, no TUN teardown, no sing-box bounce; only in-flight connections on
    ///       the old server reset. Requires the running config to expose a HandlerService api.
    ///   Tier 1 (fallback)  — restart ONLY the Xray main core with the new config, keeping the
    ///       sing-box pre-service and the OS tun adapter ALIVE (no adapter flap — the visible drop).
    ///   Full restart (final fallback) — today's <see cref="LoadCore"/>, used when not connected, when
    ///       the plumbing shape changed, or when both tiers above fail.
    ///
    /// The tunnel/adapter never goes down on Tier 1/2, and — crucially — this path NEVER calls
    /// <see cref="CoreStop"/> nor resets <see cref="AppManager.RunningCoreType"/>, so
    /// <c>IsRunningCore()</c> stays true throughout and no subscriber (Home shield, tray, status bar)
    /// ever observes a "disconnected" state mid-switch.
    /// </summary>
    /// <returns>true when a seamless tier handled the switch; false when it fell back to a full restart.</returns>
    public async Task<bool> SwitchServer(CoreConfigContext? mainContext, CoreConfigContext? preContext)
    {
        // Debounce rapid repeated switches so hot-swaps never stack. The newest target always wins
        // because ProfilesViewModel has already persisted it as the default; if a switch is already in
        // flight we simply skip this one and let the in-flight one (re-read from disk on the next tap)
        // converge. Non-blocking: never queue behind a long operation.
        if (!await _switchSemaphore.WaitAsync(0))
        {
            return false;
        }

        // A switch is a USER-initiated connect, so it mutates core state under the SAME _coreOpGate as
        // LoadCore/CoreStop — the seamless tiers touch _processService, so they must not race a
        // background auto-restart reload. Lock order is _switchSemaphore → _coreOpGate (never reversed);
        // all fallbacks below use LoadCoreInternal (NOT public LoadCore) because we already hold the gate.
        await _coreOpGate.WaitAsync();
        try
        {
            // User intent to be connected supersedes any pending auto-restart abort.
            _userStopRequested = false;
            ResetRestartLoopCts();

            // No target, or not currently connected → this is a normal (fresh) start, not a switch.
            if (mainContext == null
                || _processService is null or { HasExited: true }
                || AppManager.Instance.RunningCoreType == ECoreType.departament)
            {
                await LoadCoreInternal(mainContext, preContext);
                return false;
            }

            // A main-core TYPE change (e.g. Xray → sing-box) rebuilds the whole plumbing → full reload.
            if (AppManager.Instance.RunningCoreType != mainContext.RunCoreType)
            {
                await LoadCoreInternal(mainContext, preContext);
                return false;
            }

            // The pre-service SHAPE must be unchanged: a pre-service required now must already be
            // running, and if none is needed now none may be running. Ports are deterministic, so for a
            // pure server switch this always holds; anything else is a plumbing change → full reload.
            var preRequiredNow = preContext != null;
            var preAlive = _processPreService is { HasExited: false };
            if (preRequiredNow != preAlive)
            {
                await LoadCoreInternal(mainContext, preContext);
                return false;
            }

            // Regenerate the run-config for the new server on disk up-front. This both (a) feeds the
            // Xray-only restart tier and (b) means a later cold start / crash-restart already has the
            // new server. A generation failure aborts to a full reload (which re-reports the error).
            var fileName = Utils.GetBinConfigPath(Global.CoreConfigFileName);
            var gen = await CoreConfigHandler.GenerateClientConfig(mainContext, fileName);
            if (gen.Success != true)
            {
                await LoadCoreInternal(mainContext, preContext);
                return false;
            }

            // From here on the switch is committed to the new server, so a crash-restart must reload IT,
            // not the old server. The seamless tiers below do NOT go through LoadCore, so refresh the
            // cached recovery contexts here (LoadCore refreshes them itself on the fallback paths).
            _lastMainContext = mainContext;
            _lastPreContext = preContext;

            // Tier 2: true make-before-break — swap the proxy outbound live, keeping everything else up.
            // DISABLED (see EnableHotSwapTier): it could report success without actually re-routing, so
            // the switch would silently keep the old server. Tier 1 below is the correct-by-construction
            // seamless path (real config reload, tun adapter stays up).
            if (EnableHotSwapTier
                && mainContext.RunCoreType == ECoreType.Xray
                && _runningApiPort > 0
                && _runningProxyTag.IsNotEmpty()
                && await TryHotSwapOutbound(fileName))
            {
                await UpdateFunc(false, $"{mainContext.Node.GetSummary()}");
                // Positive "seamless switch settled" so the UI resolves its mid-switch hold immediately
                // (this path publishes no CoreRunningStateChanged, so this is the only completion signal).
                AppEvents.CoreSwitchSettled.Publish(true);
                return true;
            }

            // Tier 1: restart ONLY the Xray main core; keep the sing-box pre-service + tun adapter alive.
            if (await TryRestartMainOnly(mainContext, preContext))
            {
                await UpdateFunc(false, $"{mainContext.Node.GetSummary()}");
                AppEvents.CoreSwitchSettled.Publish(true);
                return true;
            }

            // Final fallback: full restart. Never leaves the user disconnected.
            await LoadCoreInternal(mainContext, preContext);
            // On a successful full restart the switch has still settled — signal it so any mid-switch UI
            // hold resolves at once (harmless alongside LoadCore's own CoreRunningStateChanged(true)).
            if (AppManager.Instance.RunningCoreType != ECoreType.departament)
            {
                AppEvents.CoreSwitchSettled.Publish(true);
            }
            return false;
        }
        catch (Exception ex)
        {
            Logging.SaveLog(_tag, ex);
            // Any unexpected failure on the seamless path falls back to the full restart so the user is
            // never left in a broken/half-swapped state.
            await LoadCoreInternal(mainContext, preContext);
            // L2: resolve the UI's mid-switch hold immediately on a successful recovery instead of
            // waiting the 12s deadline (the recovery raises CoreRunningStateChanged, but publishing the
            // positive switch-settled signal too keeps the contract uniform across every success path).
            if (AppManager.Instance.RunningCoreType != ECoreType.departament)
            {
                AppEvents.CoreSwitchSettled.Publish(true);
            }
            return false;
        }
        finally
        {
            _coreOpGate.Release();
            _switchSemaphore.Release();
        }
    }

    /// <summary>
    /// Tier 2 — hot-swap the live Xray proxy outbound to the new server with no core restart.
    /// Reads the freshly generated config, lifts its first proxy-protocol outbound, RE-TAGS it to the
    /// tag the LIVE routing already references (<see cref="_runningProxyTag"/>), then
    /// <c>xray api rmo</c> (remove old) + <c>xray api ado</c> (add new) against the running core's
    /// HandlerService. Routing/inbounds/TUN/sing-box are all untouched. Returns false on any failure so
    /// the caller degrades to the Xray-only restart tier (which fully restores the outbound from the
    /// same config already on disk).
    /// </summary>
    private async Task<bool> TryHotSwapOutbound(string newConfigFile)
    {
        try
        {
            if (JsonUtils.ParseJson(await File.ReadAllTextAsync(newConfigFile)) is not JsonObject root
                || root["outbounds"] is not JsonArray outbounds)
            {
                return false;
            }

            var proxy = outbounds
                .OfType<JsonObject>()
                .FirstOrDefault(o => XrayJsonTemplateFmt.IsProxyProtocol(GetOutboundProtocol(o)));
            if (proxy == null)
            {
                return false;
            }

            var newOutbound = (JsonObject)proxy.DeepClone();
            newOutbound["tag"] = _runningProxyTag;
            // Mux on a live-added outbound is fragile across a swap; strip it (mirrors the speedtest
            // outbound graft) — plain proxying is what a VPN switch needs.
            newOutbound.Remove("mux");

            var swapFile = Utils.GetBinConfigPath("hotswap_outbound.json");
            var payload = new JsonObject { ["outbounds"] = new JsonArray(newOutbound) };
            await File.WriteAllTextAsync(swapFile, JsonUtils.Serialize(payload));

            var server = $"{Global.Loopback}:{_runningApiPort}";

            // Remove the current proxy outbound first — Xray's AddOutbound rejects a duplicate tag, so
            // add cannot precede remove. The gap with no "proxy" outbound is sub-millisecond.
            if (!await RunXrayApiCommand($"api rmo --server={server} \"{_runningProxyTag}\""))
            {
                return false;
            }

            // Add the new server under the same tag. If this fails the live core is momentarily left
            // with no proxy outbound; returning false makes the caller restart Xray from the config on
            // disk, which fully restores it (no adapter flap).
            if (!await RunXrayApiCommand($"api ado --server={server} \"{swapFile}\""))
            {
                return false;
            }

            // Re-assert system proxy is unaffected: ports are unchanged, nothing else to do.
            return true;
        }
        catch (Exception ex)
        {
            Logging.SaveLog(_tag, ex);
            return false;
        }
    }

    /// <summary>
    /// Tier 1 — restart ONLY the Xray main core with the new config, keeping the sing-box pre-service
    /// and the OS tun adapter ALIVE. sing-box keeps forwarding to the same deterministic socks port
    /// while the new Xray rebinds it (~a few hundred ms); the adapter/OS routes never drop, so there
    /// is no visible disconnect. Deliberately does NOT call <see cref="CoreStop"/> (which would kill
    /// the pre-service, destroy the wintun adapter and publish the stopped state). Returns false on
    /// failure so the caller falls back to a full restart. The config is assumed already generated on
    /// disk by the caller.
    /// </summary>
    private async Task<bool> TryRestartMainOnly(CoreConfigContext mainContext, CoreConfigContext? preContext)
    {
        try
        {
            // If a pre-service is required but not alive we cannot keep the tunnel up through the
            // switch — let the caller do a full restart instead.
            if (preContext != null && _processPreService is null or { HasExited: true })
            {
                return false;
            }

            // Stop ONLY the Xray main process. Do not touch the pre-service, the tun adapter, or
            // RunningCoreType. Bracket this intentional stop with _stopping (belt-and-suspenders to the
            // switch-in-progress guard) and detach the crash hook first so the old process's exit is
            // never surfaced as a crash.
            if (_processService != null)
            {
                _stopping = true;
                _processService.Exited -= OnCoreProcessExited;
                await _processService.StopAsync();
                _processService.Dispose();
                _processService = null;
                _stopping = false;
            }

            // Let the OS release the freed socks port before the new Xray re-binds it.
            await Task.Delay(100);

            await CoreStart(mainContext);

            if (_processService is null or { HasExited: true })
            {
                // New Xray failed to bind — signal failure; caller runs a full restart to recover.
                return false;
            }

            // The new config is now the fully running one, so refresh the hot-swap surface from it
            // (its routing/proxy tag/api port may differ from the previous server's template).
            await CaptureSwitchContext(mainContext, Utils.GetBinConfigPath(Global.CoreConfigFileName));
            return true;
        }
        catch (Exception ex)
        {
            Logging.SaveLog(_tag, ex);
            return false;
        }
    }

    /// <summary>
    /// Record the hot-swap surface (proxy tag + HandlerService api port) from the config actually
    /// written to disk. Called on every full start and after a Tier 1 restart, so
    /// <see cref="_runningProxyTag"/> / <see cref="_runningApiPort"/> always describe the LIVE config.
    /// A non-Xray core, a config with no proxy outbound, or one with no HandlerService api simply
    /// disables the hot-swap tier (the switch degrades to the Xray-only restart / full restart).
    /// </summary>
    private async Task CaptureSwitchContext(CoreConfigContext mainContext, string fileName)
    {
        _runningProxyTag = null;
        _runningApiPort = 0;
        try
        {
            if (mainContext.RunCoreType != ECoreType.Xray || !File.Exists(fileName))
            {
                return;
            }
            if (JsonUtils.ParseJson(await File.ReadAllTextAsync(fileName)) is not JsonObject root)
            {
                return;
            }

            // Proxy tag: the first proxy-protocol outbound's tag (Global.ProxyTag for a normal node,
            // the provider tag for a custom node).
            if (root["outbounds"] is JsonArray outbounds)
            {
                var proxy = outbounds
                    .OfType<JsonObject>()
                    .FirstOrDefault(o => XrayJsonTemplateFmt.IsProxyProtocol(GetOutboundProtocol(o)));
                var tag = proxy?["tag"] as JsonValue;
                if (tag != null && tag.TryGetValue<string>(out var tagStr) && tagStr.IsNotEmpty())
                {
                    _runningProxyTag = tagStr;
                }
            }

            // Api port: only when a HandlerService api is advertised AND an inbound is tagged "api".
            var services = (root["api"] as JsonObject)?["services"] as JsonArray;
            var hasHandler = services?.OfType<JsonValue>()
                .Any(v => v.TryGetValue<string>(out var s) && s == "HandlerService") == true;
            if (hasHandler && root["inbounds"] is JsonArray inbounds)
            {
                foreach (var inb in inbounds.OfType<JsonObject>())
                {
                    if (inb["tag"] is JsonValue tv && tv.TryGetValue<string>(out var itag) && itag == Global.ApiTag
                        && inb["port"] is JsonValue pv && pv.TryGetValue<int>(out var port) && port > 0)
                    {
                        _runningApiPort = port;
                        break;
                    }
                }
            }
        }
        catch (Exception ex)
        {
            Logging.SaveLog(_tag, ex);
            _runningProxyTag = null;
            _runningApiPort = 0;
        }
    }

    private static string? GetOutboundProtocol(JsonObject outbound)
    {
        if (outbound["protocol"] is JsonValue v && v.TryGetValue<string>(out var s))
        {
            return s;
        }
        return null;
    }

    /// <summary>
    /// Run a short-lived `xray api ...` command against the running core's HandlerService and return
    /// true only on a clean (exit code 0) completion within the timeout. Spawns the same Xray binary
    /// the core uses; kills and reports failure on timeout so a hung api call can never wedge a switch.
    /// </summary>
    private async Task<bool> RunXrayApiCommand(string arguments, int timeoutMs = 3000)
    {
        try
        {
            var coreInfo = CoreInfoManager.Instance.GetCoreInfo(ECoreType.Xray);
            var fileName = CoreInfoManager.Instance.GetCoreExecFile(coreInfo, out _);
            if (fileName.IsNullOrEmpty())
            {
                return false;
            }

            var psi = new ProcessStartInfo
            {
                FileName = fileName,
                Arguments = arguments,
                WorkingDirectory = Utils.GetBinConfigPath(),
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
            };
            foreach (var kv in coreInfo.Environment)
            {
                psi.Environment[kv.Key] = kv.Value;
            }

            using var proc = new Process { StartInfo = psi };
            proc.Start();

            // Drain both pipes so a chatty api command can never stall on a full buffer.
            var drainOut = proc.StandardOutput.ReadToEndAsync();
            var drainErr = proc.StandardError.ReadToEndAsync();

            using var cts = new CancellationTokenSource(timeoutMs);
            try
            {
                await proc.WaitForExitAsync(cts.Token);
            }
            catch (OperationCanceledException)
            {
                try { proc.Kill(true); } catch { }
                Logging.SaveLog($"{_tag} xray api command timed out: {arguments}");
                return false;
            }

            try { await Task.WhenAll(drainOut, drainErr); } catch { }

            return proc.ExitCode == 0;
        }
        catch (Exception ex)
        {
            Logging.SaveLog(_tag, ex);
            return false;
        }
    }

    #endregion Seamless server switch

    #region Crash detection / auto-restart / health watchdog

    /// <summary>
    /// A supervised core / pre-service process exited on its own. Distinguish a genuine crash from an
    /// intentional teardown or a seamless-switch bounce; on a real crash, run the common recovery path.
    /// Runs on the ThreadPool thread <see cref="Process.Exited"/> fires on.
    /// </summary>
    private void OnCoreProcessExited(ProcessService sender)
    {
        // Not a crash when: an intentional teardown is in progress (_stopping), a user stop was requested
        // (_userStopRequested), a seamless switch holds the switch semaphore (Tier1/Tier2 bounce the main
        // core deliberately), or we are already idle.
        if (_stopping
            || _userStopRequested
            || _switchSemaphore.CurrentCount == 0
            || AppManager.Instance.RunningCoreType == ECoreType.departament)
        {
            return;
        }

        // A stale sender already replaced by a Tier-1 swap is not the live core — ignore it.
        if (!ReferenceEquals(sender, _processService) && !ReferenceEquals(sender, _processPreService))
        {
            return;
        }

        _ = RecordExitReasonAsync(sender);
        _ = HandleUnexpectedExitAsync();
    }

    /// <summary>
    /// Ядро умерло само — забираем его последние слова СРАЗУ. Умереть оно может и позже окна
    /// оседания (медленная машина, отвалившийся адаптер, конфликт порта, пойманный на секунде), и
    /// тогда запуск уже был засчитан удачным: без этой записи щит, упавший в ошибку по сроку
    /// ожидания, снова не смог бы назвать причину. Хвост читается ДО того, как перезапуск заменит
    /// процесс, поэтому это делается здесь, а не в обработчике восстановления.
    /// </summary>
    private async Task RecordExitReasonAsync(ProcessService sender)
    {
        try
        {
            await sender.FlushOutputAsync();
            var tail = sender.GetOutputTail();
            if (tail.IsNotEmpty())
            {
                ReportStartFailure(ECoreStartFailure.CoreOutput, tail);
            }
        }
        catch (Exception ex)
        {
            Logging.SaveLog(_tag, ex);
        }
    }

    /// <summary>
    /// Common recovery for an Exited callback OR a watchdog liveness/readiness miss: mark idle, clear a
    /// stranded system proxy, drop the shield honestly, then kick a rate-limited auto-restart.
    /// Idempotent — a racing second detector no-ops on the RunningCoreType guard.
    /// </summary>
    private async Task HandleUnexpectedExitAsync()
    {
        if (_stopping
            || _userStopRequested
            || _switchSemaphore.CurrentCount == 0
            || AppManager.Instance.RunningCoreType == ECoreType.departament)
        {
            return;
        }

        try
        {
            // 1. Honest idle marker (also dedupes a racing detector — the guards above then short-circuit).
            AppManager.Instance.RunningCoreType = ECoreType.departament;
            _coreUpSince = null;

            // 3. If a system-proxy / PAC mode was active, clear the OS proxy FIRST so the user is not
            //    stranded routing through a dead 127.0.0.1:port. TUN mode leaves SysProxyType Unchanged,
            //    so this only fires for the modes that actually point the OS at the local port.
            try
            {
                var sysType = _config?.SystemProxyItem?.SysProxyType;
                if (sysType is ESysProxyType.ForcedChange or ESysProxyType.Pac)
                {
                    await SysProxyHandler.UpdateSysProxy(_config, true);
                }
            }
            catch (Exception ex)
            {
                Logging.SaveLog(_tag, ex);
            }

            // 2. Honest shield drop — keep the documented background-thread contract; UI subscribers
            //    marshal to their own thread.
            AppEvents.CoreRunningStateChanged.Publish(false);
            await UpdateFunc(false, "Core exited unexpectedly — attempting auto-restart.");
        }
        catch (Exception ex)
        {
            Logging.SaveLog(_tag, ex);
        }

        // 4. Rate-limited auto-restart.
        await AttemptAutoRestartAsync();
    }

    /// <summary>
    /// Re-run the SAME full-restart primitive with the cached current contexts, serialized through
    /// <see cref="_restartGate"/> (single recovery driver) and, for the actual reload,
    /// <see cref="_coreOpGate"/> (single core-op gate — no race with a user reload/disconnect). Backoff
    /// 1s,2s,4s,8s… capped at ~30s; at most <see cref="_maxRestartAttempts"/> restarts in a row that did
    /// not hold <see cref="_restartWindow"/>, then it gives up (no crash-loop hammering). It bails PERMANENTLY the
    /// moment an external/user stop is observed — the captured stop generation changed,
    /// <see cref="_userStopRequested"/> is set, or the loop token was cancelled — so a user Disconnect
    /// during the backoff window can never be silently undone (C1). The token also makes the backoff wait
    /// and the gate wait interruptible so a Disconnect breaks in immediately.
    /// </summary>
    private async Task AttemptAutoRestartAsync()
    {
        if (!await _restartGate.WaitAsync(0))
        {
            return;
        }

        // Capture the stop generation + cancellation token for THIS recovery session. Any external stop
        // (CoreStop, from any caller) increments the generation and cancels the token → we bail for good.
        var startGen = Volatile.Read(ref _coreStopGeneration);
        var token = CurrentRestartToken();
        try
        {
            if (_lastMainContext == null)
            {
                return;
            }

            var delayMs = 1000;
            while (true)
            {
                if (ShouldAbortRecovery(startGen, token))
                {
                    return;
                }

                int attempt;
                lock (_restartStatsLock)
                {
                    if (_restartAttempts >= _maxRestartAttempts)
                    {
                        attempt = -1;
                    }
                    else
                    {
                        _restartAttempts++;
                        attempt = _restartAttempts;
                    }
                }

                if (attempt < 0)
                {
                    //  Круг падений — не долбим дальше. Щит уходит в отказ с причиной, уцелевшее ядро
                    //  останавливается; снова подключит пользователь, его попытки счётчиком не ограничены.
                    await GiveUpRecoveryAsync(startGen, token);
                    return;
                }

                try
                {
                    await Task.Delay(delayMs, token);
                }
                catch (OperationCanceledException)
                {
                    // User Disconnect (or a new user connect) cancelled the loop mid-backoff — stop.
                    return;
                }

                if (ShouldAbortRecovery(startGen, token))
                {
                    return;
                }

                await RestartLoadCoreAsync(startGen, token);

                if (AppManager.Instance.RunningCoreType != ECoreType.departament)
                {
                    // Recovered — LoadCoreInternal has restarted the watchdog and marked uptime.
                    return;
                }

                delayMs = Math.Min(delayMs * 2, 30000);
            }
        }
        catch (Exception ex)
        {
            Logging.SaveLog(_tag, ex);
        }
        finally
        {
            _restartGate.Release();
        }
    }

    /// <summary>True when an external/user stop has superseded the recovery session identified by
    /// <paramref name="startGen"/>/<paramref name="token"/>, or the app is already idle/stopping.</summary>
    private bool ShouldAbortRecovery(int startGen, CancellationToken token) =>
        _stopping
        || _userStopRequested
        || token.IsCancellationRequested
        || Volatile.Read(ref _coreStopGeneration) != startGen
        || AppManager.Instance.RunningCoreType != ECoreType.departament;

    /// <summary>
    /// Acquire the shared <see cref="_coreOpGate"/> (cancellable while waiting) and run ONE recovery
    /// reload via <see cref="LoadCoreInternal"/>. Re-checks the abort condition UNDER the gate — the
    /// definitive close on C1's hand-off race: a user Disconnect bumps the generation before it waits on
    /// the gate, so whichever side wins the gate, the loop never re-establishes a tunnel the user tore
    /// down (and the two never touch <c>_processService</c> concurrently ⇒ no orphan).
    /// Возвращает true, только если перезапуск действительно прошёл, а не был отменён: проверке
    /// здоровья нужно отличать «перезапустили и не поднялось» от «перезапуск отменили».
    /// </summary>
    private async Task<bool> RestartLoadCoreAsync(int startGen, CancellationToken token)
    {
        try
        {
            await _coreOpGate.WaitAsync(token);
        }
        catch (OperationCanceledException)
        {
            return false;
        }
        try
        {
            if (ShouldAbortRecovery(startGen, token))
            {
                return false;
            }
            await LoadCoreInternal(_lastMainContext, _lastPreContext);
            return true;
        }
        finally
        {
            _coreOpGate.Release();
        }
    }

    /// <summary>
    /// Восстановление сдалось. Раньше здесь только уходило общее «не удалось запустить ядро»:
    /// под щитом — голое «Не подключено» без причины, а уцелевшее ядро оставалось жить. Если
    /// упал sing-box, живой Xray держал порт впустую; если упал Xray, живой sing-box держал
    /// туннель со strict_route и заворачивал весь трафик системы в мёртвый порт — без интернета
    /// вовсе. Теперь: причину — словами умершего ядра, всё уцелевшее — остановить, интерфейсу —
    /// событие, чтобы щит показал отказ, а не тихое «отключено». Под тем же шлюзом и с той же
    /// проверкой отмены, что и перезапуск: отключение пользователя или новое подключение,
    /// успевшее раньше, этот разбор не трогает.
    /// </summary>
    private async Task GiveUpRecoveryAsync(int startGen, CancellationToken token)
    {
        try
        {
            await _coreOpGate.WaitAsync(token);
        }
        catch (OperationCanceledException)
        {
            return;
        }
        try
        {
            if (ShouldAbortRecovery(startGen, token))
            {
                return;
            }

            //  Хвост снимаем ДО остановки: CoreStopInternal обнуляет процессы вместе с выводом.
            await CaptureCoreErrorTail();
            if (LastStartFailure == ECoreStartFailure.None)
            {
                //  Ядро умерло молча (убито извне, не хватило памяти) — отказ всё равно назван:
                //  сырой текст уходит в журнал, интерфейс покажет общую подсказку.
                ReportStartFailure(ECoreStartFailure.CoreOutput,
                    $"core exited unexpectedly; auto-restart gave up after {_maxRestartAttempts} restarts in a row that did not hold {_restartWindow.TotalSeconds:0} s");
            }
            await CoreStopInternal();
        }
        finally
        {
            _coreOpGate.Release();
        }

        Logging.SaveLog($"{_tag} auto-restart gave up: {LastStartError}");
        AppEvents.CoreRecoveryFailed.Publish();
        await UpdateFunc(true, ResUI.FailedToRunCore);
    }

    private CancellationToken CurrentRestartToken()
    {
        lock (_restartStatsLock)
        {
            return _restartLoopCts.Token;
        }
    }

    /// <summary>Cancel the in-flight restart loop's waits (called by every public CoreStop).</summary>
    private void CancelRestartLoop()
    {
        lock (_restartStatsLock)
        {
            try
            {
                _restartLoopCts.Cancel();
            }
            catch
            {
            }
        }
    }

    /// <summary>Arm a FRESH restart-loop cancellation source for a new connect session (a user connect
    /// supersedes any prior loop). Cancels+disposes the old one so any loop still holding it bails.
    /// Заодно — свежий запас перезапусков: подключение пользователя начинает счёт заново, иначе после
    /// одного отказа первое же падение нового подключения сдавалось бы без единой попытки.</summary>
    private void ResetRestartLoopCts()
    {
        lock (_restartStatsLock)
        {
            _restartAttempts = 0;
            var old = _restartLoopCts;
            _restartLoopCts = new CancellationTokenSource();
            try
            {
                old.Cancel();
                old.Dispose();
            }
            catch
            {
            }
        }
    }

    /// <summary>
    /// Debounced (~2.5s) on-demand health probe called by the OS resume / network-change hooks so
    /// recovery is near-instant instead of waiting a full watchdog cycle. Serialized through the same
    /// <see cref="_restartGate"/> as the auto-restart loop so resume + network-change can never launch
    /// two concurrent reloads.
    /// </summary>
    public async Task RequestHealthCheckAsync()
    {
        var now = DateTime.Now;
        lock (_restartStatsLock)
        {
            if (now - _lastHealthCheckRequest < TimeSpan.FromSeconds(2.5))
            {
                return;
            }
            _lastHealthCheckRequest = now;
        }

        if (_stopping
            || _userStopRequested
            || _switchSemaphore.CurrentCount == 0
            || AppManager.Instance.RunningCoreType == ECoreType.departament)
        {
            return;
        }

        if (!await _restartGate.WaitAsync(0))
        {
            return;
        }

        var startGen = Volatile.Read(ref _coreStopGeneration);
        var token = CurrentRestartToken();
        try
        {
            var mainDead = _processService is null or { HasExited: true };
            var preDead = _lastPreContext != null && _processPreService is null or { HasExited: true };
            var dead = mainDead || preDead;

            // Liveness above stays authoritative and immediate. For the readiness probe we demand a
            // SUSTAINED failure (several generous-timeout handshakes in a row all failing) before we
            // conclude the tunnel is wedged: on a weak PC a resume/network-change burst often coincides
            // with a CPU spike, and a single slow handshake must NEVER restart a still-alive core (that
            // is the self-inflicted disconnect we are eliminating). If ANY attempt answers, the core is
            // alive and left running.
            if (!dead && _lastPreContext is { } pre && pre.AppConfig.TunModeItem.EnableTun)
            {
                dead = !await ProbeSocksReadySustainedAsync(pre.Node.Port);
            }

            if (dead && !ShouldAbortRecovery(startGen, token) && _lastMainContext != null
                && await RestartLoadCoreAsync(startGen, token)
                && !ShouldAbortRecovery(startGen, token))
            {
                //  Перезапуск прошёл, ядро не поднялось, и никто извне его не отменял (иначе сработала
                //  бы проверка выше). LoadCore уже назвал причину и всё остановил, но щит без события
                //  так и стоял бы на тихом «Не подключено».
                AppEvents.CoreRecoveryFailed.Publish();
            }
        }
        catch (Exception ex)
        {
            Logging.SaveLog(_tag, ex);
        }
        finally
        {
            _restartGate.Release();
        }
    }

    private void StartWatchdog()
    {
        StopWatchdog();
        var cts = new CancellationTokenSource();
        _watchdogCts = cts;
        _ = Task.Run(() => WatchdogLoopAsync(cts.Token));
    }

    private void StopWatchdog()
    {
        var cts = _watchdogCts;
        _watchdogCts = null;
        if (cts != null)
        {
            try
            {
                cts.Cancel();
            }
            catch
            {
            }
            cts.Dispose();
        }
    }

    /// <summary>
    /// Single background supervision loop, live only while a core is up. Its ONLY job is to catch a
    /// genuinely DEAD core process — a dead main core or a dead REQUIRED pre-service — as a belt-and-
    /// suspenders backup to the immediate <see cref="ProcessService.Exited"/> callback, and funnel that
    /// into <see cref="HandleUnexpectedExitAsync"/>. It runs on a generous interval and does only a
    /// cheap <c>HasExited</c> boolean check, so it costs nothing on a weak PC.
    ///
    /// Deliberately it does NOT probe SOCKS5 readiness and NEVER restarts a core that is still ALIVE.
    /// A weak/CPU-starved PC under load makes the local handshake slow, so a periodic readiness probe
    /// would time out against a perfectly-healthy core and KILL+RESTART it — the exact self-inflicted
    /// «отключается сам и переподключается под нагрузкой» the owner reported. A living process is left
    /// running, full stop. Only a real process death here (or an OS resume / network-change health check,
    /// or the crash callback) triggers recovery; a slow-but-alive tunnel does not.
    /// </summary>
    private async Task WatchdogLoopAsync(CancellationToken token)
    {
        try
        {
            while (!token.IsCancellationRequested)
            {
                try
                {
                    // Generous interval: the Exited callback is the primary, instant crash detector; this
                    // loop is only the backup for the rare case it does not fire, so a slow cadence is
                    // plenty and keeps the weak-PC cost negligible.
                    await Task.Delay(TimeSpan.FromSeconds(15), token);
                }
                catch (OperationCanceledException)
                {
                    return;
                }

                // Supervise only while a core is supposed to be up and no intentional/switch/user-stop op runs.
                if (_stopping
                    || _userStopRequested
                    || _switchSemaphore.CurrentCount == 0
                    || AppManager.Instance.RunningCoreType == ECoreType.departament)
                {
                    continue;
                }

                // Cheap liveness ONLY — a dead main core or dead REQUIRED pre-service is a real crash.
                // Authoritative and immediate (no timeout, no false positive under load). A slow-but-alive
                // core is NEVER touched here (no readiness-probe restart).
                var mainDead = _processService is null or { HasExited: true };
                var preDead = _lastPreContext != null && _processPreService is null or { HasExited: true };
                if (mainDead || preDead)
                {
                    await HandleUnexpectedExitAsync();
                    continue;
                }

                // Reset the auto-restart attempt budget after a stretch of stable connected uptime.
                if (_coreUpSince is { } up && DateTime.Now - up > _restartWindow)
                {
                    lock (_restartStatsLock)
                    {
                        _restartAttempts = 0;
                    }
                }
            }
        }
        catch (Exception ex)
        {
            Logging.SaveLog(_tag, ex);
        }
    }

    /// <summary>
    /// "Alive" verdict for the resume / network-change health check: probe the local SOCKS5 port up to
    /// <paramref name="attempts"/> times with a generous per-attempt timeout, returning true as soon as
    /// ANY attempt succeeds. Only when EVERY attempt fails do we treat the tunnel as wedged. This makes a
    /// transient CPU spike (typical right after a sleep/resume storm) unable to manufacture a false
    /// "dead" verdict that would needlessly restart a healthy core. A non-positive port answers true
    /// (nothing to probe ⇒ do not manufacture a failure).
    /// </summary>
    private static async Task<bool> ProbeSocksReadySustainedAsync(int port, int attempts = 3, int timeoutMs = 3000, int gapMs = 500)
    {
        if (port <= 0)
        {
            return true;
        }
        for (var i = 0; i < attempts; i++)
        {
            if (await ProbeSocksReadyAsync(port, timeoutMs))
            {
                return true;
            }
            if (i < attempts - 1)
            {
                try
                {
                    await Task.Delay(gapMs);
                }
                catch
                {
                }
            }
        }
        return false;
    }

    /// <summary>
    /// Single-shot SOCKS5 readiness handshake against a local proxy port (same greeting
    /// <see cref="WaitForProxyPort"/> uses at connect time). Returns true when the port answers with a
    /// valid SOCKS5 method selection, false on any connect/handshake failure within the timeout. A
    /// non-positive port returns true (nothing to probe ⇒ do not manufacture a failure).
    /// </summary>
    private static async Task<bool> ProbeSocksReadyAsync(int port, int timeoutMs)
    {
        if (port <= 0)
        {
            return true;
        }
        try
        {
            using var tcp = new TcpClient();
            using var cts = new CancellationTokenSource(timeoutMs);
            await tcp.ConnectAsync(Global.Loopback, port, cts.Token);
            var stream = tcp.GetStream();
            ReadOnlyMemory<byte> greeting = new byte[] { 0x05, 0x01, 0x00 };
            await stream.WriteAsync(greeting, cts.Token);
            var buf = new byte[2];
            var read = await stream.ReadAsync(buf.AsMemory(0, 2), cts.Token);
            return read == 2 && buf[0] == 0x05;
        }
        catch
        {
            return false;
        }
    }

    #endregion Crash detection / auto-restart / health watchdog

    public async Task<ProcessService?> LoadCoreConfigSpeedtest(List<ServerTestItem> selecteds)
    {
        //  Ядро на ВЕСЬ пакет выбиралось по ПЕРВОМУ серверу в списке. Для departament это ломало
        //  пинг целиком: подписка хранит каждый сервер как CUSTOM — сырой xray-json провайдера, — а
        //  привить такие узлы в пакетный конфиг умеет только ветка Xray (InjectCustomSpeedtestNodes).
        //  Стоило первому в списке оказаться sing_box, и весь замер уходил в sing_box, где CUSTOM
        //  пропускается: у таких строк AllowTest оставался false, тест их молча обходил и в списке
        //  появлялось «n/a». Владелец видел это как «другие proxy из json не берутся в пинг».
        //  Теперь sing_box берётся, только если ВЕСЬ пакет — sing_box и в нём нет ни одного CUSTOM.
        //  Иначе Xray: он и умеет прививать сырые json-узлы, и сами эти конфиги написаны под него.
        var anyCustom = selecteds.Any(it => it.ConfigType == EConfigType.Custom);
        var allSingbox = selecteds.Count > 0 && selecteds.All(it => it.CoreType == ECoreType.sing_box);
        var coreType = allSingbox && !anyCustom ? ECoreType.sing_box : ECoreType.Xray;
        var fileName = string.Format(Global.CoreSpeedtestConfigFileName, Utils.GetGuid(false));
        var configPath = Utils.GetBinConfigPath(fileName);
        var result = await CoreConfigHandler.GenerateClientSpeedtestConfig(_config, configPath, selecteds, coreType);
        await UpdateFunc(false, result.Msg);
        if (result.Success != true)
        {
            return null;
        }

        await UpdateFunc(false, string.Format(ResUI.StartService, DateTime.Now.ToString("yyyy/MM/dd HH:mm:ss")));
        await UpdateFunc(false, configPath);

        var coreInfo = CoreInfoManager.Instance.GetCoreInfo(coreType);
        return await RunProcess(coreInfo, fileName, true, false);
    }

    public async Task<ProcessService?> LoadCoreConfigSpeedtest(ServerTestItem testItem)
    {
        var node = await AppManager.Instance.GetProfileItem(testItem.IndexId);
        if (node is null)
        {
            return null;
        }

        var fileName = string.Format(Global.CoreSpeedtestConfigFileName, Utils.GetGuid(false));
        var configPath = Utils.GetBinConfigPath(fileName);
        var (context, _) = await CoreConfigContextBuilder.Build(_config, node);
        var result = await CoreConfigHandler.GenerateClientSpeedtestConfig(_config, context, testItem, configPath);
        if (result.Success != true)
        {
            return null;
        }

        var coreType = context.RunCoreType;
        var coreInfo = CoreInfoManager.Instance.GetCoreInfo(coreType);
        return await RunProcess(coreInfo, fileName, true, false);
    }

    /// <summary>
    /// Public (external/intentional) stop entry point: user Disconnect, tray disconnect, app exit, core
    /// update, logout. Serialized through <see cref="_coreOpGate"/> so it can never race the auto-restart
    /// reload on <c>_processService</c> (no orphan process — C1). EVERY external stop supersedes an
    /// in-flight auto-restart: it bumps the stop generation (the restart loop bails when it changes) and
    /// cancels the restart-loop token (so a backoff wait breaks immediately). <paramref name="byUser"/>
    /// additionally records STICKY user-stop intent so the loop can never re-establish the tunnel the
    /// user just tore down, even across the tiny gate hand-off window.
    /// </summary>
    public async Task CoreStop(bool byUser = false)
    {
        // Supersede any in-flight auto-restart BEFORE acquiring the gate, so a restart loop blocked on
        // the gate (or between backoff waits) observes the abort at its next checkpoint.
        Interlocked.Increment(ref _coreStopGeneration);
        if (byUser)
        {
            _userStopRequested = true;
        }
        CancelRestartLoop();

        await _coreOpGate.WaitAsync();
        try
        {
            await CoreStopInternal();
        }
        finally
        {
            _coreOpGate.Release();
        }
    }

    /// <summary>
    /// Ungated teardown implementation. MUST be called with <see cref="_coreOpGate"/> already held (by
    /// <see cref="CoreStop"/> or the internal stop-before-start in <see cref="LoadCoreInternal"/>). Does
    /// NOT bump the stop generation, so a stop-before-start is never mistaken for an external stop.
    /// </summary>
    private async Task CoreStopInternal()
    {
        // Intentional teardown: brackets the whole stop so neither a process Exited callback nor a
        // watchdog liveness miss (both racing this Kill) is ever mistaken for a crash. Cleared in the
        // finally once teardown has settled. Also stop the watchdog up-front so it does not probe a
        // half-torn-down core.
        _stopping = true;
        StopWatchdog();
        _coreUpSince = null;
        try
        {
            if (_linuxSudo)
            {
                await CoreAdminManager.Instance.KillProcessAsLinuxSudo();
                _linuxSudo = false;
            }

            if (_processService != null)
            {
                _processService.Exited -= OnCoreProcessExited;
                await _processService.StopAsync();
                _processService.Dispose();
                _processService = null;
            }

            if (_processPreService != null)
            {
                _processPreService.Exited -= OnCoreProcessExited;
                await _processPreService.StopAsync();
                _processPreService.Dispose();
                _processPreService = null;
            }
        }
        catch (Exception ex)
        {
            Logging.SaveLog(_tag, ex);
        }

        // Fully tear down the OS TUN adapter on disconnect (Windows + TUN). The processes are already
        // stopped above, so the wintun device is free to remove. Without this a stale adapter lingers
        // after every disconnect and can block/stall the NEXT connect (sing-box hangs trying to create
        // a device that still exists). Doing it here means both an explicit user disconnect and the
        // stop-before-start inside LoadCore leave a clean slate. RemoveTunDevice swallows its own
        // errors, so a missing adapter is a no-op.
        if (Utils.IsWindows() && _config?.TunModeItem?.EnableTun == true)
        {
            await WindowsUtils.RemoveTunDevice();
        }

        // A stopped core has no live api/proxy to hot-swap; clear the switch surface so a stale tag or
        // api port can never be used against a dead core.
        _runningProxyTag = null;
        _runningApiPort = 0;

        // Reset the running-core marker so IsRunningCore() reports "stopped" after teardown.
        // The Incy connect shield (HomeViewModel) and the tray icon both derive connect state
        // from IsRunningCore; without this reset RunningCoreType stays sticky and a disconnect
        // never registers. LoadCore re-assigns it on the next connect.
        AppManager.Instance.RunningCoreType = ECoreType.departament;

        // idle/perf B1: broadcast the stopped transition (mirror of the start-side publish in LoadCore)
        // so every connect-state subscriber settles to "disconnected" without a poll. CoreStop is also
        // called as the stop-before-start step inside LoadCore and on a failed connect; a transient
        // false→true (reconnect) or false→false (failed connect) is harmless — subscribers dedupe by
        // state and, during a reload, StatusBarView still shows "Connecting" via BlReloadEnabled.
        AppEvents.CoreRunningStateChanged.Publish(false);

        // Teardown settled — reopen the crash path. Any process that dies from here on (before the next
        // start subscribes) is caught by the RunningCoreType==departament guard in OnCoreProcessExited.
        _stopping = false;
    }

    #region Private

    /// <summary>
    /// One full start attempt: start the main core, wait for its socks port, then start the required
    /// pre-service. Returns true only when the main core is genuinely alive AND (if a pre-service was
    /// required) it actually started. MUST be called with <see cref="_coreOpGate"/> held (always is —
    /// only <see cref="LoadCoreInternal"/> calls it). Factored out so <see cref="LoadCoreInternal"/> can
    /// re-arm it exactly once on a transient slow-PC start failure without duplicating the logic.
    ///
    /// A pre-context means a pre-service is REQUIRED for traffic to flow:
    ///  - FULL TUN + custom/legacy node: the sing-box pre-service owns the OS tun adapter and forwards
    ///    captured traffic into the main core's socks inbound;
    ///  - pre-socks chaining: the pre-core is the actual ingress.
    /// In all cases the main (Xray) core alone is useless without it — its socks inbound binds fine so
    /// the process is "alive", but nothing routes OS traffic to it. Treating that as "connected" is
    /// exactly the reported false «Подключено» with no traffic anywhere, so a required-but-missing
    /// pre-service is NOT a success. The main-core check uses HasExited (not just non-null) so a core
    /// that started and then died during the socks wait is correctly seen as a failed attempt.
    /// </summary>
    private async Task<bool> TryStartCoresOnce(CoreConfigContext mainContext, CoreConfigContext? preContext)
    {
        await CoreStart(mainContext);
        await WaitForProxyPort(preContext);
        await CoreStartPreService(preContext);

        //  ОКНО ОСЕДАНИЯ. Проверка «жив ли процесс» внутри RunProcessNormal стоит на отметке 100 мс,
        //  а ядро на плохом конфиге или занятом порту умирает ПОЗЖЕ: Xray успевает прочитать конфиг,
        //  напечатать «Failed to start: ... address already in use» и выйти примерно к 150–250 мс.
        //  Раньше такой запуск засчитывался успешным: приложение поднимало флаг «подключено», щит
        //  синел, и лишь потом сторож ловил смерть ядра — пользователь видел «Подключено», а затем
        //  красный щит без причины. Ждём ещё немного и перечитываем состояние: смерть в этом окне —
        //  это отказ запуска, с готовым хвостом вывода, по которому называется причина. На удачном
        //  подключении это стоит 350 мс — Reload и без того выдерживает секунду после LoadCore.
        if (_processService is { HasExited: false })
        {
            await Task.Delay(350);
        }

        //  Второе ядро тоже проверяем на «умерло в окне», а не только на «не запустилось». sing-box
        //  падает на старте позже Xray (например, не скачав набор правил), и его смерть в этом окне
        //  сторож не видит: флаг «подключено» ещё не поднят. Раньше такой запуск засчитывался, щит
        //  синел над мёртвым туннелем, а через 15 с сторож начинал круг перезапусков.
        var preServiceRequiredButFailed = preContext != null && _processPreService is null or { HasExited: true };
        return _processService is { HasExited: false } && !preServiceRequiredButFailed;
    }

    private async Task CoreStart(CoreConfigContext context)
    {
        var node = context.Node;
        var coreType = AppManager.Instance.GetCoreType(node, node.ConfigType);
        var coreInfo = CoreInfoManager.Instance.GetCoreInfo(coreType);

        var displayLog = node.ConfigType != EConfigType.Custom || node.DisplayLog;
        var proc = await RunProcess(coreInfo, Global.CoreConfigFileName, displayLog, true);
        if (proc is null)
        {
            return;
        }
        _processService = proc;
        // Surface an unexpected death of the main core to the crash-detection handler. Wiring here (not
        // via EConfigType.Custom import) means the hook exists for every core type, always.
        _processService.Exited += OnCoreProcessExited;
    }

    private async Task CoreStartPreService(CoreConfigContext? preContext)
    {
        if (_processService is { HasExited: false } && preContext != null)
        {
            var preCoreType = preContext?.Node?.CoreType ?? ECoreType.sing_box;
            var fileName = Utils.GetBinConfigPath(Global.CorePreConfigFileName);
            var result = await CoreConfigHandler.GenerateClientConfig(preContext, fileName);
            if (!result.Success)
            {
                //  Без второго ядра туннеля нет, и попытка сорвётся — причину называем сразу, иначе
                //  под щитом осталось бы безымянное «не запустилось».
                ReportStartFailure(ECoreStartFailure.ConfigFailed, result.Msg);
            }
            else
            {
                var coreInfo = CoreInfoManager.Instance.GetCoreInfo(preCoreType);
                var proc = await RunProcess(coreInfo, Global.CorePreConfigFileName, true, true);
                if (proc is null)
                {
                    return;
                }
                _processPreService = proc;
                // The pre-service (sing-box TUN owner / pre-socks ingress) is REQUIRED for traffic; a
                // crash of it is as fatal as a main-core crash → same handler.
                _processPreService.Exited += OnCoreProcessExited;
            }
        }
    }

    private async Task UpdateFunc(bool notify, string msg)
    {
        await _updateFunc?.Invoke(notify, msg);
    }

    private async Task WaitForProxyPort(CoreConfigContext? preContext, int timeoutMs = 5000)
    {
        if (preContext is null)
        {
            return;
        }
        if (!preContext.AppConfig.TunModeItem.EnableTun)
        {
            return;
        }

        using var rootCts = new CancellationTokenSource(TimeSpan.FromMilliseconds(timeoutMs));
        var rootToken = rootCts.Token;

        var port = preContext.Node.Port;
        // SOCKS5 client greeting: VER=5, NMETHODS=1, METHOD=0x00 (no auth)
        ReadOnlyMemory<byte> greeting = new byte[] { 0x05, 0x01, 0x00 };
        var buf = new byte[2];

        while (!rootToken.IsCancellationRequested)
        {
            //  Главное ядро умерло или не запустилось вовсе — его порт уже не откроется. Раньше
            //  ожидание всё равно высиживало все 5 с, и сорвавшийся запуск с повтором внутри
            //  LoadCore стоил больше десяти секунд крутилки до честной ошибки.
            if (_processService is null or { HasExited: true })
            {
                Logging.SaveLog($"WaitForProxyPort: main core is not running, stop waiting for proxy port {port}.");
                return;
            }

            using var tcp = new TcpClient();
            using var attemptCts = new CancellationTokenSource(TimeSpan.FromMilliseconds(50));
            using var linkedCts = CancellationTokenSource.CreateLinkedTokenSource(rootToken, attemptCts.Token);
            var linkedToken = linkedCts.Token;
            try
            {
                await tcp.ConnectAsync(Global.Loopback, port, linkedToken);
                var stream = tcp.GetStream();

                await stream.WriteAsync(greeting, linkedToken);

                var read = await stream.ReadAsync(buf.AsMemory(0, 2), linkedToken);

                // Server selection: VER=5, METHOD=0x00 — proxy is fully ready
                if (read == 2 && buf[0] == 0x05)
                {
                    return;
                }
            }
            catch (OperationCanceledException)
            {
                if (!rootToken.IsCancellationRequested)
                {
                    continue;
                }
                Logging.SaveLog($"WaitForProxyPort Timeout waiting for proxy port {port} to be ready.");
                return;
            }
            catch (SocketException ex) when (ex.SocketErrorCode == SocketError.ConnectionRefused)
            {
                // Connection refused, proxy not ready yet, wait 50ms before retrying
                try
                {
                    await Task.Delay(50, rootToken);
                }
                catch (OperationCanceledException)
                {
                    Logging.SaveLog($"WaitForProxyPort Timeout waiting for proxy port {port} to be ready.");
                    return;
                }
            }
            catch
            {
                // Ignore other exceptions and continue
            }
        }
    }

    #endregion Private

    #region Process

    /// <summary>
    /// Забирает последние строки stderr у уже умерших процессов ядра и кладёт их в
    /// <see cref="LastStartError"/>. Тишину не записывает, чтобы не затереть более внятную причину
    /// (ненайденный файл ядра, ошибка сборки конфига), записанную раньше по ходу той же попытки.
    /// </summary>
    private async Task CaptureCoreErrorTail()
    {
        //  Причину называет тот, кто УМЕР. Раньше первым читался хвост главного ядра, даже живого:
        //  когда падал sing-box, Xray рядом был жив, и его обычная строка старта («Xray ... started»)
        //  затирала фатальную строку sing-box — под щитом оставалось безымянное «не запустилось».
        //  Событие выхода опережает дочитывание вывода, поэтому поток умершего сперва сливаем,
        //  иначе фатальной строки ещё нет в буфере.
        foreach (var proc in new[] { _processService, _processPreService })
        {
            if (proc is not { HasExited: true })
            {
                continue;
            }
            await proc.FlushOutputAsync();
            var tail = proc.GetOutputTail();
            if (tail.IsNotEmpty())
            {
                ReportStartFailure(ECoreStartFailure.CoreOutput, tail);
                return;
            }
        }
    }

    private async Task<ProcessService?> RunProcess(CoreInfo? coreInfo, string configPath, bool displayLog, bool mayNeedSudo)
    {
        var fileName = CoreInfoManager.Instance.GetCoreExecFile(coreInfo, out var msg);
        if (fileName.IsNullOrEmpty())
        {
            ReportStartFailure(ECoreStartFailure.CoreMissing, msg);
            await UpdateFunc(false, msg);
            return null;
        }

        try
        {
            if (mayNeedSudo
                && _config.TunModeItem.EnableTun
                && (coreInfo.CoreType is ECoreType.sing_box or ECoreType.mihomo or ECoreType.Xray)
                && Utils.IsNonWindows())
            {
                _linuxSudo = true;
                await CoreAdminManager.Instance.Init(_config, _updateFunc);
                return await CoreAdminManager.Instance.RunProcessAsLinuxSudo(fileName, coreInfo, configPath);
            }

            return await RunProcessNormal(fileName, coreInfo, configPath, displayLog);
        }
        catch (Exception ex)
        {
            Logging.SaveLog(_tag, ex);
            await UpdateFunc(mayNeedSudo, ex.Message);
            return null;
        }
    }

    private async Task<ProcessService?> RunProcessNormal(string fileName, CoreInfo? coreInfo, string configPath, bool displayLog)
    {
        var environmentVars = new Dictionary<string, string>();
        foreach (var kv in coreInfo.Environment)
        {
            environmentVars[kv.Key] = string.Format(kv.Value, coreInfo.AbsolutePath ? Utils.GetBinConfigPath(configPath).AppendQuotes() : configPath);
        }

        var procService = new ProcessService(
            fileName: fileName,
            arguments: string.Format(coreInfo.Arguments, coreInfo.AbsolutePath ? Utils.GetBinConfigPath(configPath).AppendQuotes() : configPath),
            workingDirectory: Utils.GetBinConfigPath(),
            displayLog: displayLog,
            redirectInput: false,
            environmentVars: environmentVars,
            updateFunc: _updateFunc
        );

        await procService.StartAsync();

        await Task.Delay(100);

        if (procService is null or { HasExited: true })
        {
            //  Ядро умерло на старте. Его последние слова приходят асинхронно, часто уже ПОСЛЕ
            //  выхода процесса: дожидаемся слива потоков и забираем хвост, иначе отказ снова
            //  окажется безымянным. Наверх по-прежнему уходит общее сообщение — сырой текст ядра
            //  не место в панели уведомлений, он нужен для подсказки под щитом.
            if (procService is not null)
            {
                await procService.FlushOutputAsync();
            }
            var tail = procService?.GetOutputTail();
            if (tail.IsNotEmpty())
            {
                ReportStartFailure(ECoreStartFailure.CoreOutput, tail);
                Logging.SaveLog($"{_tag} core start failed: {tail}");
            }
            throw new Exception(ResUI.FailedToRunCore);
        }
        AddProcessJob(procService.Handle);

        return procService;
    }

    private void AddProcessJob(nint processHandle)
    {
        if (Utils.IsWindows())
        {
            _processJob ??= new();
            try
            {
                _processJob?.AddProcess(processHandle);
            }
            catch { }
        }
    }

    #endregion Process
}
