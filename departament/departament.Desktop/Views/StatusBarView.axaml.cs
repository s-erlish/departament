using System.Reactive.Disposables;
using DialogHostAvalonia;
using departament.Desktop.Common;

namespace departament.Desktop.Views;

public partial class StatusBarView : ReactiveUserControl<StatusBarViewModel>
{
    // Connect-state drives the tray + window icon (grey shield = idle, blue shield = connecting /
    // connected) and one-shot transition toasts. State is read from the real engine
    // (AppManager.IsRunningCore) exactly like the in-app connect shield (HomeViewModel), so the tray
    // icon and the shield can never disagree.
    private enum ConnState
    {
        Idle,
        Connecting,
        Connected
    }

    private ConnState _connState = ConnState.Idle;
    private bool _seenReloadEnabled;

    // Shields are immutable — load once, reuse for the app lifetime (cheap on weak PCs).
    private static WindowIcon? _iconIdle;
    private static WindowIcon? _iconOn;

    public StatusBarView()
    {
        InitializeComponent();

        txtRunningServerDisplay.Tapped += TxtRunningServerDisplay_Tapped;
        txtRunningInfoDisplay.Tapped += TxtRunningServerDisplay_Tapped;

        this.WhenActivated(disposables =>
        {
            //status bar
            this.OneWayBind(ViewModel, vm => vm.InboundDisplay, v => v.txtInboundDisplay.Text).DisposeWith(disposables);
            this.OneWayBind(ViewModel, vm => vm.InboundLanDisplay, v => v.txtInboundLanDisplay.Text).DisposeWith(disposables);
            this.OneWayBind(ViewModel, vm => vm.RunningServerDisplay, v => v.txtRunningServerDisplay.Text).DisposeWith(disposables);
            this.OneWayBind(ViewModel, vm => vm.RunningInfoDisplay, v => v.txtRunningInfoDisplay.Text).DisposeWith(disposables);
            this.OneWayBind(ViewModel, vm => vm.SpeedProxyDisplay, v => v.txtSpeedProxyDisplay.Text).DisposeWith(disposables);
            this.OneWayBind(ViewModel, vm => vm.SpeedDirectDisplay, v => v.txtSpeedDirectDisplay.Text).DisposeWith(disposables);
            this.Bind(ViewModel, vm => vm.EnableTun, v => v.togEnableTun.IsChecked).DisposeWith(disposables);

            this.Bind(ViewModel, vm => vm.SystemProxySelected, v => v.cmbSystemProxy.SelectedIndex).DisposeWith(disposables);
            this.Bind(ViewModel, vm => vm.SelectedRouting, v => v.cmbRoutings2.SelectedItem).DisposeWith(disposables);

            ViewModel.SetClipboardDataInteraction.RegisterHandler(async interaction =>
            {
                var strData = interaction.Input;
                await AvaUtils.SetClipboardData(this, strData);
                interaction.SetOutput(Unit.Default);
            }).DisposeWith(disposables);

            ViewModel.PasswordInputInteraction.RegisterHandler(async interaction =>
            {
                var result = await PasswordInputAsync();
                interaction.SetOutput(result);
            }).DisposeWith(disposables);

            ViewModel.DispatcherRefreshIconInteraction.RegisterHandler(interaction =>
            {
                // Proxy/routing changes still re-assert the icon; it now reflects connect state.
                Dispatcher.UIThread.Post(RefreshIcon, DispatcherPriority.Default);
                interaction.SetOutput(Unit.Default);
            }).DisposeWith(disposables);

            // idle/perf B1: connect state is event-driven now — NO 1 s poll. It can only change on two
            // signals: (1) the core actually starting/stopping (AppEvents.CoreRunningStateChanged, raised
            // by CoreManager), and (2) a reload going in/out of flight (MainWindowViewModel.BlReloadEnabled
            // → the "Connecting" sub-state / failed-connect toast). Subscribe to both, marshal to the UI
            // thread (the core event fires on a background thread) and dispose on deactivation.
            AppEvents.CoreRunningStateChanged
                .AsObservable()
                .Subscribe(_ => Dispatcher.UIThread.Post(EvaluateConnectState))
                .DisposeWith(disposables);

            if (Application.Current?.ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desk
                && desk.MainWindow?.DataContext is MainWindowViewModel mainVm)
            {
                mainVm.WhenAnyValue(x => x.BlReloadEnabled)
                    .Subscribe(_ => Dispatcher.UIThread.Post(EvaluateConnectState))
                    .DisposeWith(disposables);
            }

            EvaluateConnectState();
        });

        //spEnableTun.IsVisible = (Utils.IsWindows() || AppHandler.Instance.IsAdministrator);

        if (Utils.IsNonWindows() && cmbSystemProxy.Items.IsReadOnly == false)
        {
            cmbSystemProxy.Items.RemoveAt(cmbSystemProxy.Items.Count - 1);
        }

        // Because this view has not yet been initialized when DispatcherRefreshIconInteraction is first called.
        RefreshIcon();
    }

    #region Connect-state tray icon + transition toasts

    private void EvaluateConnectState()
    {
        var running = AppManager.Instance.IsRunningCore(ECoreType.Xray)
                   || AppManager.Instance.IsRunningCore(ECoreType.sing_box);

        var next = running
            ? ConnState.Connected
            : IsReloadInFlight()
                ? ConnState.Connecting
                : ConnState.Idle;

        if (next == _connState)
        {
            return;
        }

        _connState = next;

        RefreshIcon();
        // Connect-state transition snacks are published solely by HomeViewModel (the single owner
        // of the connect pipeline) — see AppEvents.SendSnackMsgRequested in HomeViewModel.Connect /
        // SyncState. StatusBar used to publish the same transitions too, producing a DOUBLE inline
        // snack, so it no longer raises them here; it still owns only the tray/window icon.
    }

    // "Connecting" == a core reload is in progress. MainWindowViewModel.BlReloadEnabled flips false
    // while Reload() builds the config and starts the core, then back to true when it settles. Read
    // through the window's public DataContext (never mutated here) with a graceful fallback: if it
    // is unavailable we simply skip the connecting hint. The "seen enabled" latch disambiguates the
    // initial false (app sitting idle, never reloaded) from a genuine in-flight reload.
    private bool IsReloadInFlight()
    {
        try
        {
            if (Application.Current?.ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop
                && desktop.MainWindow?.DataContext is MainWindowViewModel mainVm)
            {
                if (mainVm.BlReloadEnabled)
                {
                    _seenReloadEnabled = true;
                }
                return _seenReloadEnabled && !mainVm.BlReloadEnabled;
            }
        }
        catch
        {
            // best-effort only — a missing connecting hint must never break the tray
        }

        return false;
    }

    private void RefreshIcon()
    {
        if (Application.Current?.ApplicationLifetime is not IClassicDesktopStyleApplicationLifetime desktop)
        {
            return;
        }

        // Idle → grey shield; connecting & connected → blue shield (DESIGN §2.12).
        var icon = _connState == ConnState.Idle ? IconIdle : IconOn;
        if (icon is null)
        {
            return;
        }

        if (desktop.MainWindow is not null)
        {
            desktop.MainWindow.Icon = icon;
        }

        //  Значок в трее живёт в App: его может ещё не быть (окно не показано) или не быть вовсе
        //  (скрыт в настройках). App запоминает картинку и ставит её, когда значок есть.
        App.SetTrayImage(icon);
    }

    private static WindowIcon? IconIdle => _iconIdle ??= App.LoadTrayImage("NotifyShieldIdle.ico");

    private static WindowIcon? IconOn => _iconOn ??= App.LoadTrayImage("NotifyShieldOn.ico");

    #endregion Connect-state tray icon + transition toasts

    private async Task<string?> PasswordInputAsync()
    {
        var dialog = new SudoPasswordInputView();
        var obj = await DialogHost.Show(dialog);

        var password = obj?.ToString();
        if (password.IsNullOrEmpty())
        {
            togEnableTun.IsChecked = false;
            return password;
        }

        AppManager.Instance.LinuxSudoPwd = password;
        return password;
    }

    private void TxtRunningServerDisplay_Tapped(object? sender, Avalonia.Input.TappedEventArgs e)
    {
        ViewModel?.TestServerAvailability();
    }
}
