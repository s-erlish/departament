using Microsoft.Win32;
using ServiceLib.Handler.SysProxy;
using departament.Desktop.Common;
using departament.Desktop.Views;

namespace departament.Desktop;

public partial class App : Application
{
    public override void Initialize()
    {
        AvaloniaXamlLoader.Load(this);

        AppDomain.CurrentDomain.UnhandledException += CurrentDomain_UnhandledException;
        TaskScheduler.UnobservedTaskException += TaskScheduler_UnobservedTaskException;
    }

    public override void OnFrameworkInitializationCompleted()
    {
        var viewLocator = SimpleViewLocator.Instance;
        DataTemplates.Add(viewLocator);

        if (ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop)
        {
            if (!Design.IsDesignMode)
            {
                AppManager.Instance.InitComponents();
                DataContext = StatusBarViewModel.Instance;

                //  Журнал ядра собирается С ЭТОЙ СЕКУНДЫ, а не с первого открытия подэкрана.
                //  Без этого вызова «Настройки → Журнал» открывался пустым: подписка начиналась
                //  в момент открытия, и всё, что случилось раньше (в том числе причина неудачного
                //  подключения, ради которой туда и идут), в буфер не попадало вовсе.
                CoreLogBuffer.Start();

                // Оформление из конфига ДО построения окна: первый кадр рисуется правильной темой.
                // База Тёмная/Светлая + отдельный МОНОХРОМНЫЙ (чёрно-белый) оверлей поверх неё.
                var cfg = AppManager.Instance.Config;
                ApplyTheme(cfg.UiItem.CurrentTheme, cfg.UiItem.BlackTheme);

                // Локализация из конфига ДО построения окна: первый кадр рисуется на нужном языке
                // (ru/en) и все {loc:T} биндинги открываются уже правильными. Живое переключение
                // без перезапуска — см. L.SetLanguage.
                L.Init();

                // Remnawave HWID: send the same stable per-machine device id the account API uses
                // (X-HWID) on every subscription GET, so a panel with HWID device-limit enabled serves
                // the real server list instead of the «Приложение не поддерживается» placeholder.
                ServiceLib.Global.SubscriptionHwidProvider = () => departament.Desktop.Account.AuthTokenStore.DeviceId();

                //  Подписки аккаунта при запуске скачивает импорт аккаунта (AccountViewModel.StartupLoad),
                //  пока сессия жива. Разовая дозагрузка устаревших их не трогает: иначе одна и та же
                //  подписка качалась дважды и список серверов пересобирался на глазах два раза подряд.
                ServiceLib.Global.SubscriptionRefreshedElsewhere = id =>
                    departament.Desktop.Account.AccountSession.IsLoggedIn()
                    && departament.Desktop.Account.AuthTokenStore.GetManagedGuids().ContainsValue(id);
            }

            // Экранов Clash в departament для ПК нет ни в каком виде — ни вкладок, ни списка прокси.
            // Флаг ставится ДО создания MainWindowViewModel: иначе он успеет построить оба вида Clash,
            // а каждый из них в конструкторе запускает бесконечный цикл опроса (5 и 60 секунд).
            // Ставится и в дизайн-режиме тоже: превьювер поднимает ту же модель.
            ServiceLib.Global.ClashUiAvailable = false;

            var mainWindowViewModel = new MainWindowViewModel();
            var mainWindow = (MainWindow)viewLocator.Build(mainWindowViewModel);
            mainWindow.ViewModel = mainWindowViewModel;
            desktop.MainWindow = mainWindow;

            // idle/perf B5: пометить UI как скрытый, пока окно свёрнуто, чтобы простаивающие защиты
            // (циклы статистики и любые проверки по ShowInTaskbar) считали «свёрнуто в панель задач»
            // тем же, что «скрыто в трей». Наблюдение живёт всё время работы приложения (одно окно);
            // GetObservable сразу отдаёт текущее состояние и далее — изменения.
            mainWindow
                .GetObservable(Window.WindowStateProperty)
                .Subscribe(state => AppManager.Instance.IsWindowMinimized = state == WindowState.Minimized);

            if (!Design.IsDesignMode)
            {
                SetupTray();
                SetupConnectivityHooks(desktop);
                SetupAppHandoff();
                if (OperatingSystem.IsWindows())
                {
                    _ = Task.Run(SyncInstalledVersion);
                }
            }

            if (OperatingSystem.IsMacOS())
            {
                Current?.TryGetFeature<IActivatableLifetime>()?.Activated += OnMacOSActivated;
            }
        }

        base.OnFrameworkInitializationCompleted();
    }

    #region MacOS Activation

    private void OnMacOSActivated(object? sender, ActivatedEventArgs args)
    {
        if (args.Kind != ActivationKind.Reopen)
        {
            return;
        }

        if ((ApplicationLifetime as IClassicDesktopStyleApplicationLifetime)?.MainWindow is not MainWindow mainWindow)
        {
            return;
        }

        var isMiniaturized = MacAppUtils.IsWindowMiniaturized(mainWindow);

        Dispatcher.UIThread.Post(() =>
        {
            if (isMiniaturized)
            {
                RestoreMacOSAccessoryPolicyAfterMiniaturize(mainWindow);
                mainWindow.ShowHideWindow(true);
                return;
            }

            if (!AppManager.Instance.Config.UiItem.MacOSShowInDock)
            {
                MacAppUtils.SetActivationPolicyAccessory();
            }

            mainWindow.ShowHideWindow(true);
        });
    }

    private static void RestoreMacOSAccessoryPolicyAfterMiniaturize(MainWindow mainWindow)
    {
        if (AppManager.Instance.Config.UiItem.MacOSShowInDock)
        {
            return;
        }

        mainWindow
            .GetObservable(Window.WindowStateProperty)
            .Skip(1)
            .Where(state => state != WindowState.Minimized)
            .Take(1)
            .ObserveOn(RxSchedulers.MainThreadScheduler)
            .Subscribe(_ => QueueMacOSAccessoryPolicyRestore(mainWindow));
    }

    private static void QueueMacOSAccessoryPolicyRestore(MainWindow mainWindow)
    {
        // AppKit may keep isMiniaturized set until the Dock restore animation finishes.
        DispatcherTimer.RunOnce(() => RestoreMacOSAccessoryPolicy(mainWindow), TimeSpan.FromMilliseconds(300));
    }

    private static void RestoreMacOSAccessoryPolicy(MainWindow mainWindow)
    {
        if (AppManager.Instance.Config.UiItem.MacOSShowInDock || MacAppUtils.IsWindowMiniaturized(mainWindow))
        {
            return;
        }

        MacAppUtils.SetActivationPolicyAccessory();
        mainWindow.Activate();
        mainWindow.Focus();
    }

    #endregion MacOS Activation

    #region Browser→app SSO handoff (departamentvpn://auth?code=…)

    /// <summary>
    /// Registers the <c>departamentvpn://</c> URL scheme (Windows, per-user, no admin) so the OS routes the
    /// site's <c>/app-login</c> return back to the app, and wires the receiver that redeems the handoff
    /// code. The scheme name matches the site's safe-return allowlist (<c>^departament[a-z0-9]*$</c>), so a
    /// browser→app return needs zero site changes. On non-Windows the registry step is skipped (guarded);
    /// the pipe receiver + «войти по коду» fallback still work.
    /// </summary>
    private void SetupAppHandoff()
    {
        RegisterAuthScheme();
        // The pipe/cold-start URL is delivered on a background thread; marshal to the UI thread to route it.
        AppHandoffChannel.SetHandler(url => Dispatcher.UIThread.Post(() =>
        {
            //  Установщик просит выйти перед заменой файлов (departament.exe --quit): тот же путь, что
            //  «Выход» в трее, — ядро остановлено, системный прокси снят.
            if (AppHandoffChannel.IsQuit(url))
            {
                MenuExit_Click(this, EventArgs.Empty);
                return;
            }
            OnAuthCallbackUrl(url);
        }));
    }

    //  AppId установщика (installer/departament.iss). Менять нельзя: по нему Windows узнаёт установленную
    //  программу, а новая установка встаёт поверх прежней, а не рядом.
    private const string InstallerAppId = "{60C53B95-8ECB-4307-A76B-756BEAC48B89}";

    /// <summary>
    /// Версия в «Параметры → Приложения» — такая же, как у запущенной программы. Её записывает установщик,
    /// а дальше программа обновляется сама, мимо него (AmazTool), и запись застывала бы на версии первой
    /// установки. Установщик ставит программу для всех (HKLM, она всё равно запускается с правами
    /// администратора); HKCU — на случай установки только для себя. Трогаем только запись своей папки:
    /// копия, распакованная из архива в другое место, версию установленной не переписывает.
    /// </summary>
    [SupportedOSPlatform("windows")]
    private static void SyncInstalledVersion()
    {
        var path = $@"Software\Microsoft\Windows\CurrentVersion\Uninstall\{InstallerAppId}_is1";
        foreach (var hive in new[] { Registry.LocalMachine, Registry.CurrentUser })
        {
            try
            {
                using var key = hive.OpenSubKey(path, writable: true);
                if (key?.GetValue("InstallLocation") is not string location
                    || !string.Equals(Path.GetFullPath(location).TrimEnd('\\'), Path.GetFullPath(Utils.StartupPath()).TrimEnd('\\'), StringComparison.OrdinalIgnoreCase))
                {
                    continue;
                }
                var version = Utils.GetVersionInfo();
                if (!string.Equals(key.GetValue("DisplayVersion") as string, version, StringComparison.Ordinal))
                {
                    key.SetValue("DisplayVersion", version);
                }
                return;
            }
            catch (Exception ex)
            {
                Logging.SaveLog("SyncInstalledVersion", ex);
            }
        }
    }

    private static void RegisterAuthScheme()
    {
        if (!Utils.IsWindows())
        {
            return;
        }
        try
        {
            var exe = Environment.ProcessPath;
            if (exe.IsNullOrEmpty())
            {
                return;
            }
            using var root = Registry.CurrentUser.CreateSubKey(@"Software\Classes\departamentvpn");
            root.SetValue(null, "URL:departament protocol");
            root.SetValue("URL Protocol", string.Empty);
            using (var icon = root.CreateSubKey("DefaultIcon"))
            {
                icon.SetValue(null, $"\"{exe}\",0");
            }
            using (var cmd = root.CreateSubKey(@"shell\open\command"))
            {
                cmd.SetValue(null, $"\"{exe}\" \"%1\"");
            }
        }
        catch (Exception ex)
        {
            Logging.SaveLog("RegisterAuthScheme", ex);
        }
    }

    private void OnAuthCallbackUrl(string url)
    {
        try
        {
            var code = ParseHandoffCode(url);
            if (code.IsNullOrEmpty())
            {
                return;
            }
            if ((ApplicationLifetime as IClassicDesktopStyleApplicationLifetime)?.MainWindow is MainWindow mainWindow)
            {
                mainWindow.HandleAuthCallback(code!);
            }
        }
        catch (Exception ex)
        {
            Logging.SaveLog("OnAuthCallbackUrl", ex);
        }
    }

    /// <summary>Extracts the <c>code</c> query value from <c>departamentvpn://auth?code=…</c> (manual parse
    /// — a custom scheme isn't guaranteed to expose <see cref="Uri.Query"/>, and this avoids a System.Web dep).</summary>
    private static string? ParseHandoffCode(string url)
    {
        if (url.IsNullOrEmpty())
        {
            return null;
        }
        // Take the query/fragment portion, then match the pair whose key is EXACTLY "code" — a plain
        // IndexOf("code=") would also match "barcode="/"mycode=" if the return URL ever gains such a param.
        var qStart = url.IndexOfAny(new[] { '?', '#' });
        var query = qStart >= 0 ? url.Substring(qStart + 1) : url;
        foreach (var pair in query.Split('&', '#'))
        {
            var eq = pair.IndexOf('=');
            if (eq <= 0)
            {
                continue;
            }
            if (pair.Substring(0, eq).Trim().Equals("code", StringComparison.OrdinalIgnoreCase))
            {
                var value = Uri.UnescapeDataString(pair.Substring(eq + 1).Trim());
                return value.IsNullOrEmpty() ? null : value;
            }
        }
        return null;
    }

    #endregion Browser→app SSO handoff

    #region Connectivity hooks (network change → core health check)

    // Retained so it can be unsubscribed on shutdown (NetworkChange holds a static handler list).
    private System.Net.NetworkInformation.NetworkAddressChangedEventHandler? _networkAddressChangedHandler;

    /// <summary>
    /// Cross-platform connection-reliability hook (BCL only — no new NuGet package). A network address
    /// change (Wi-Fi flip, DHCP lease, VPN adapter churn, or a sleep/resume storm) can leave the running
    /// core's tunnel stale. We ask <see cref="CoreManager.RequestHealthCheckAsync"/> to probe and reload
    /// if dead; it debounces internally and no-ops when idle. We deliberately do NOT reference the
    /// Windows-only <c>Microsoft.Win32.SystemEvents.PowerModeChanged</c>: a Windows resume also surfaces
    /// as network changes and the periodic watchdog re-probes within ~7s, so this hook plus the watchdog
    /// cover resume recovery while keeping the Linux/macOS build clean with zero new dependencies.
    /// </summary>
    private void SetupConnectivityHooks(IClassicDesktopStyleApplicationLifetime desktop)
    {
        _networkAddressChangedHandler = (_, _) =>
        {
            // Only act when a core is supposed to be running (CoreManager re-checks + debounces too).
            if (IsCoreRunning())
            {
                _ = CoreManager.Instance.RequestHealthCheckAsync();
            }
        };

        try
        {
            System.Net.NetworkInformation.NetworkChange.NetworkAddressChanged += _networkAddressChangedHandler;
        }
        catch (Exception ex)
        {
            Logging.SaveLog("SetupConnectivityHooks", ex);
        }

        // Clean up on app shutdown to avoid a leaked static handler.
        desktop.Exit += (_, _) =>
        {
            try
            {
                if (_networkAddressChangedHandler != null)
                {
                    System.Net.NetworkInformation.NetworkChange.NetworkAddressChanged -= _networkAddressChangedHandler;
                    _networkAddressChangedHandler = null;
                }
            }
            catch
            {
            }
        };
    }

    #endregion Connectivity hooks (network change → core health check)

    #region App Event

    private void CurrentDomain_UnhandledException(object sender, UnhandledExceptionEventArgs e)
    {
        if (e.ExceptionObject != null)
        {
            Logging.SaveLog("CurrentDomain_UnhandledException", (Exception)e.ExceptionObject);
        }
    }

    private void TaskScheduler_UnobservedTaskException(object? sender, UnobservedTaskExceptionEventArgs e)
    {
        Logging.SaveLog("TaskScheduler_UnobservedTaskException", e.Exception);
    }

    // «Выход». Обработчик события — значит async void: исключение из него НЕКОМУ поймать и оно
    // валит процесс целиком. У трёх соседних пунктов трея try/catch есть, у этого не было — а он
    // как раз останавливает ядро и снимает системный прокси, то есть трогает и процессы, и сеть.
    // Падение здесь означало бы, что выход из приложения выглядит как крах, да ещё и с оставленным
    // системным прокси на мёртвом порту. Ловим и всё равно завершаемся: пользователь просил выйти.
    private async void MenuExit_Click(object? sender, EventArgs e)
    {
        try
        {
            await AppManager.Instance.AppExitAsync(false);
        }
        catch (Exception ex)
        {
            Logging.SaveLog("MenuExit_Click", ex);
        }
        AppManager.Instance.Shutdown(true);
    }

    #endregion App Event

    #region DEV: вехи запуска (DP_TIMELINE)

    //  DP_TIMELINE=путь — вехи запуска и подключения построчно в файл: «мс от старта процесса, событие».
    //  Проверка на Windows снаружи меряет, когда окно стало видно и когда значок
    //  встал в трей; вехи изнутри показывают, между какими шагами ушло время, и остаются доказательством
    //  там, где область уведомлений снаружи не видна. Без переменной — ни одного обращения к диску.
    private static readonly string? s_timelinePath = Environment.GetEnvironmentVariable("DP_TIMELINE");

    private static readonly object s_timelineGate = new();

    internal static void DevMark(string what)
    {
        if (s_timelinePath is null)
        {
            return;
        }
        try
        {
            var ms = (DateTime.Now - System.Diagnostics.Process.GetCurrentProcess().StartTime).TotalMilliseconds;
            lock (s_timelineGate)
            {
                File.AppendAllText(s_timelinePath, $"{ms.ToString("F0", CultureInfo.InvariantCulture)} {what}{Environment.NewLine}");
            }
        }
        catch
        {
            //  Вехи нужны только стенду: сбой записи не должен задевать приложение.
        }
    }

    #endregion DEV: вехи запуска (DP_TIMELINE)

    #region Tray menu (departament: Перезапустить · Подключить/Отключить · Показать · Выход)

    // Значок в трее появляется ВМЕСТЕ С ОКНОМ, а не при загрузке приложения (см. App.axaml): иначе запуск
    // выглядел как «программа висит в трее, а окно появляется потом». Разрешение даёт окно — своим
    // первым кадром или уходом в трей, если на старте его не показывают (ShowTrayIcon). До этого
    // переключатель в настройках и смена картинки только запоминаются.
    //
    // Скрытый значок здесь не существует, а не стоит с IsVisible=false. Avalonia на Windows при каждой
    // смене картинки заново добавляет значок в трей, не глядя на IsVisible, поэтому спрятанный так
    // значок возвращался при каждом подключении и отключении. Удалённый не возвращается.
    private TrayIcon? _trayIcon;
    private WindowIcon? _trayImage;
    private bool _trayReleased;

    // Пункты меню: Перезапустить · Подключить/Отключить · Показать · Выход. Переключатель держим
    // синхронным с реальным состоянием ядра; подписи всех — из L.T (live).
    private NativeMenuItem? _trayRestartItem;
    private NativeMenuItem? _trayToggleItem;
    private NativeMenuItem? _trayShowItem;
    private NativeMenuItem? _trayExitItem;

    private static bool IsCoreRunning() =>
        AppManager.Instance.IsRunningCore(ECoreType.Xray) || AppManager.Instance.IsRunningCore(ECoreType.sing_box);

    /// <summary>Окно показано или ушло в трей: значку можно появиться. Повторный вызов ничего не делает.</summary>
    public static void ShowTrayIcon()
    {
        if (Current is App app)
        {
            app._trayReleased = true;
            app.CreateTrayIcon();
        }
    }

    /// <summary>Показать или убрать значок в области уведомлений — по строке настроек, на месте.</summary>
    public static void ApplyTrayIconVisibility(bool hidden)
    {
        if (Current is not App app)
        {
            return;
        }
        if (hidden)
        {
            app.RemoveTrayIcon();
        }
        else
        {
            app.CreateTrayIcon();
        }
    }

    /// <summary>Картинка значка по состоянию подключения. Если значка ещё нет, она встанет при его создании.</summary>
    public static void SetTrayImage(WindowIcon icon)
    {
        if (Current is not App app)
        {
            return;
        }
        app._trayImage = icon;
        if (app._trayIcon is { } tray)
        {
            tray.Icon = icon;
        }
    }

    internal static WindowIcon? LoadTrayImage(string fileName)
    {
        try
        {
            using var stream = AssetLoader.Open(new Uri(Global.AvaAssets + fileName));
            return new WindowIcon(stream);
        }
        catch
        {
            return null;
        }
    }

    private void CreateTrayIcon()
    {
        if (!_trayReleased || _trayIcon is not null || AppManager.Instance.Config.UiItem.HideTrayIcon)
        {
            return;
        }
        try
        {
            _trayRestartItem = new NativeMenuItem();
            _trayRestartItem.Click += MenuRestart_Click;
            _trayToggleItem = new NativeMenuItem();
            _trayToggleItem.Click += MenuToggleConnect_Click;
            _trayShowItem = new NativeMenuItem { Command = StatusBarViewModel.Instance.ShowWindowCmd };
            _trayExitItem = new NativeMenuItem();
            _trayExitItem.Click += MenuExit_Click;
            LocalizeTray();

            var menu = new NativeMenu();
            menu.Items.Add(_trayRestartItem);
            menu.Items.Add(_trayToggleItem);
            menu.Items.Add(_trayShowItem);
            menu.Items.Add(_trayExitItem);

            //  Подсказка при наведении — всегда имя бренда, а не строка статуса подключения.
            _trayIcon = new TrayIcon
            {
                Icon = _trayImage ??= LoadTrayImage("NotifyShieldIdle.ico"),
                ToolTipText = "departament",
                Menu = menu,
                Command = StatusBarViewModel.Instance.NotifyLeftClickCmd,
            };
            //  Avalonia регистрирует значок в системе уже в этом инициализаторе (Shell_NotifyIcon при
            //  установке картинки), поэтому веха ставится здесь, а не после добавления в коллекцию.
            DevMark("tray.created");
            if (TrayIcon.GetIcons(this) is { } icons)
            {
                icons.Add(_trayIcon);
            }
            else
            {
                TrayIcon.SetIcons(this, new TrayIcons { _trayIcon });
            }
        }
        catch (Exception ex)
        {
            Logging.SaveLog("CreateTrayIcon", ex);
        }
    }

    private void RemoveTrayIcon()
    {
        if (_trayIcon is not { } tray)
        {
            return;
        }
        _trayIcon = null;
        _trayRestartItem = _trayToggleItem = _trayShowItem = _trayExitItem = null;
        try
        {
            //  Убранный из коллекции значок Avalonia сама освобождает, и он уходит из трея.
            TrayIcon.GetIcons(this)?.Remove(tray);
        }
        catch (Exception ex)
        {
            Logging.SaveLog("RemoveTrayIcon", ex);
        }
    }

    private void SetupTray()
    {
        // idle/perf B1: подпись «Подключить/Отключить» теперь обновляется ПО СОБЫТИЮ старта/останова
        // ядра (AppEvents.CoreRunningStateChanged из CoreManager), а не опросом каждые 2с. Событие
        // приходит с фонового потока (ядро стартует в Task.Run), поэтому обновление NativeMenuItem
        // маршалим в UI-поток. Подписка живёт всё время работы приложения (один трей).
        // Subscription lives for the whole app lifetime (single tray, like the old never-stopped poll);
        // the static EventChannel keeps the observer alive, so no field/dispose is needed.
        AppEvents.CoreRunningStateChanged
            .AsObservable()
            .Subscribe(_ => Dispatcher.UIThread.Post(UpdateTrayToggleLabel));

        // Локализация трея: подписи ставятся при создании значка и заново при смене языка (live).
        // Родное OS-меню читает Header при открытии, так что достаточно переустановить строки.
        L.Instance.LanguageChanged += (_, _) => Dispatcher.UIThread.Post(LocalizeTray);

        //  Страховка: значок — единственный путь назад к спрятанному окну, поэтому он появится и тогда,
        //  если первый кадр окна почему-то так и не случился. В обычном запуске окно давно успевает раньше.
        DispatcherTimer.RunOnce(ShowTrayIcon, TimeSpan.FromSeconds(10));
    }

    // Ре-локализация трея: подписи всех пунктов из L.T(...) — при создании значка и при смене языка.
    // Родное OS-меню читает Header при открытии, поэтому достаточно переустановить строки.
    private void LocalizeTray()
    {
        if (_trayRestartItem is not null)
        {
            _trayRestartItem.Header = L.T("Tray_Restart");
        }
        if (_trayShowItem is not null)
        {
            _trayShowItem.Header = L.T("Tray_Show");
        }
        if (_trayExitItem is not null)
        {
            _trayExitItem.Header = L.T("Tray_Exit");
        }
        UpdateTrayToggleLabel();
    }

    // Подпись переключателя = живое состояние ядра, из L.T (Отключить/Подключить · Disconnect/Connect).
    private void UpdateTrayToggleLabel()
    {
        if (_trayToggleItem is not null)
        {
            _trayToggleItem.Header = IsCoreRunning() ? L.T("Tray_Disconnect") : L.T("Tray_Connect");
        }
    }

    // «Подключить»/«Отключить» — тот же путь, что тап по connect-щиту.
    private async void MenuToggleConnect_Click(object? sender, EventArgs e)
    {
        UpdateReconnect.NoteUserAction();
        try
        {
            if (IsCoreRunning())
            {
                // Отключение: стоп ядра + снятие системного прокси (иначе интернет ляжет на мёртвый порт).
                // byUser:true — намеренный стоп пользователем: гасит любой авто-рестарт (C1).
                await CoreManager.Instance.CoreStop(byUser: true);
                await SysProxyHandler.UpdateSysProxy(AppManager.Instance.Config, true);
            }
            else if ((Current?.ApplicationLifetime as IClassicDesktopStyleApplicationLifetime)?.MainWindow is MainWindow mw
                     && mw.ViewModel is { } vm)
            {
                // Подключение — явное действие пользователя ⇒ старт ядра разрешён. Reload собирает
                // конфиг и поднимает ядро для текущего сервера по умолчанию (путь щита Connect()).
                await vm.Reload();
            }
        }
        catch (Exception ex)
        {
            Logging.SaveLog("MenuToggleConnect_Click", ex);
        }

        UpdateTrayToggleLabel();
    }

    // «Перезапустить» — поднимаем свежий процесс и выходим из текущего.
    private async void MenuRestart_Click(object? sender, EventArgs e)
    {
        try
        {
            var exePath = Utils.GetExePath();
            // Останавливаем ядро/сохраняем состояние, затем стартуем новый инстанс. Global.RebootAs
            // обходит одно-экземплярный guard (Program.OnStartup) на Windows, поэтому новый процесс
            // поднимается, даже пока текущий ещё завершает выход.
            await AppManager.Instance.AppExitAsync(false);
            ProcUtils.ProcessStart(exePath, Global.RebootAs);
            AppManager.Instance.Shutdown(true);
        }
        catch (Exception ex)
        {
            Logging.SaveLog("MenuRestart_Click", ex);
        }
    }

    #endregion Tray menu

    #region Theme (departament: Тёмная / Светлая базы + Монохромный (чёрно-белый) оверлей)

    // Кэш монохромного оверлея — ОТДЕЛЬНО для светлой и тёмной базы (как Android
    // ThemeOverlay.Mono поверх day/night: свой набор mono_* в values/ и values-night/).
    // Оверлей добавляется ПОСЛЕДНИМ словарём в Application.Resources.MergedDictionaries,
    // поэтому перекрывает тема-зависимые Brush.* из GlobalResources (merged ищутся с конца),
    // а DynamicResource перекрашивает вживую. Держим ссылку на добавленный сейчас словарь,
    // чтобы при смене базы (или выключении) снять именно его и поставить нужный.
    private static ResourceDictionary? _monoLight;
    private static ResourceDictionary? _monoDark;
    private static ResourceDictionary? _activeMono;

    /// <summary>
    /// Хук плавной смены темы, который ставит <see cref="MainWindow"/>. Получает делегат-СВОП
    /// (мгновенную перекраску живых контролов) и оборачивает его в красивый переход: снимок текущей
    /// темы → вызвать своп под снимком → «залить» новую тему расширяющимся круговым клипом. Пока окно
    /// не построено (старт из конфига) хук = null → своп выполняется мгновенно. В lite/reduced-motion
    /// хук сам делает мгновенный своп (см. MainWindow.RunThemeTransition). Единственная точка входа —
    /// <see cref="ApplyTheme"/> (обе кнопки настроек: смена базы и монохромный оверлей идут через неё).
    /// </summary>
    public static Action<Action>? ThemeTransitionHook;

    /// <summary>
    /// Единая точка применения оформления. <paramref name="theme"/> = имя <see cref="ETheme"/>
    /// (Dark/Light) → базовый <see cref="ThemeVariant"/>; <paramref name="black"/> = ОТДЕЛЬНЫЙ
    /// МОНОХРОМНЫЙ оверлей поверх любой базы (как ThemeOverlay.Mono в Android поверх day/night:
    /// нейтрализует ВСЕ акцентные оттенки в серый, палитра — чёрно-белая; красный сохраняется
    /// только для деструктива). Вызывается на старте (из конфига) и вживую из настроек —
    /// перекраска без перезапуска. Живая смена из настроек проходит через <see cref="ThemeTransitionHook"/>
    /// (круговая заливка новой темы), если он установлен; на старте (хук ещё null) — мгновенно.
    /// </summary>
    public static void ApplyTheme(string? theme, bool black)
    {
        var app = Current;
        if (app is null)
        {
            return;
        }

        // Сам своп темы (мгновенная перекраска): переменная базы + пере-композиция моно-оверлея.
        // Токены — DynamicResource, поэтому живые контролы перекрашиваются в этот же тик.
        void Swap()
        {
            app.RequestedThemeVariant = theme switch
            {
                nameof(ETheme.Light) => ThemeVariant.Light,
                nameof(ETheme.Dark) => ThemeVariant.Dark,
                // Владелец: базы только Тёмная/Светлая; всё прочее (в т.ч. FollowSystem/null) → Тёмная.
                _ => ThemeVariant.Dark,
            };

            ApplyMonoOverlay(black);
        }

        // Окно построено → отдаём своп в переход-хук (снимок → своп → круговая заливка). На старте,
        // до построения окна, хук ещё null → мгновенный своп (первый кадр рисуется правильной темой).
        var hook = ThemeTransitionHook;
        if (hook is not null)
        {
            hook(Swap);
        }
        else
        {
            Swap();
        }
    }

    // Ставит/снимает монохромный оверлей, выбирая light- или dark-набор по активной базе.
    // RequestedThemeVariant уже выставлен в ApplyTheme, поэтому набор всегда совпадает с базой;
    // при переключении базы под включённым mono старый набор снимается, ставится корректный.
    private static void ApplyMonoOverlay(bool on)
    {
        var app = Current;
        if (app is null)
        {
            return;
        }

        var merged = app.Resources.MergedDictionaries;

        // Снять текущий mono (если был) — чтобы выключение чисто восстановило базу,
        // а смена базы не оставила несовместимый набор.
        if (_activeMono is not null)
        {
            merged.Remove(_activeMono);
            _activeMono = null;
        }

        if (!on)
        {
            return;
        }

        var light = app.RequestedThemeVariant == ThemeVariant.Light;
        var overlay = light
            ? _monoLight ??= BuildMonoOverlay(light: true)
            : _monoDark ??= BuildMonoOverlay(light: false);

        merged.Add(overlay);
        _activeMono = overlay;
    }

    // Монохромный набор: полный список тема-зависимых И акцент-производных Brush.* → чёрно-белая
    // палитра. Акцент (#4C8DFF) схлопывается в серый (light #111214 / dark #FFFFFF), «connected»
    // становится серо-белым, ВСЕ цветные плитки/чипы/иконки → серые. КРАСНЫЙ сохраняется только
    // для деструктива (delete/failed). Значения = Android mono_* (values / values-night §mono).
    private static ResourceDictionary BuildMonoOverlay(bool light)
    {
        static SolidColorBrush Solid(string hex) => new(Color.Parse(hex));
        static SolidColorBrush Alpha(string hex, double opacity) => new(Color.Parse(hex)) { Opacity = opacity };

        // Серый «акцент»/чернила и поверхности из Android mono_* набора.
        var accent = "#F2F4F8";            // акцент чёрно-белой = белый
        var onAccent = "#0A0A0B";
        var accentContainer = "#22242A";
        var onAccentContainer = "#F2F4F8";
        var connected = "#F2F4F8";         // «хорошо» сведено к белому
        var onSurface = "#F2F4F8";
        var onSurfaceVariant = "#9BA1AD";
        var highest = "#282A2E";           // плитка / окошко
        // Красный сохраняем для деструктива (Android mono держит iconTintRed/failed красным).
        // «Плохо» в этой теме тоже белое (tokens.md): цвета не остаётся нигде, кроме глифа Telegram
        // — а он здесь тоже белый. Красный держим ОДИН, для кнопки закрытия окна: она системная,
        // и её наведение красное во всех темах по README «Хром окна».
        var red = "#F2F4F8";
        // Красного текста в этой теме нет: ошибки печатаются тем же белым, что и всё остальное —
        // «в чёрно-белой теме не остаётся синего», и цвета вообще. Ключ существует ради единого API.
        var redText = "#F2F4F8";

        // Полупрозрачные производные — единый серый под tile/selected/статус-чип.
        // На тёмной базе — белый лифт, на светлой — чёрный (как ховер).
        var greyTint = "#F2F4F8";

        return new ResourceDictionary
        {
            // ── Поверхности / чернила / контуры (mono_*) ──
            ["Brush.Bg"] = Solid("#141416"),
            ["Brush.Surface"] = Solid("#1A1A1D"),
            ["Brush.SurfaceHigh"] = Solid("#202024"),
            ["Brush.SurfaceVariant"] = Solid("#232326"),
            ["Brush.SurfaceHighest"] = Solid(highest),
            ["Brush.OnSurface"] = Solid(onSurface),
            ["Brush.OnSurfaceVariant"] = Solid(onSurfaceVariant),
            ["Brush.Outline"] = Solid("#34363C"),
            ["Brush.OutlineVariant"] = Solid("#26262A"),

            // ── Акцент → серый (схлопывание #4C8DFF) ──
            ["Brush.Accent"] = Solid(accent),
            ["Brush.OnAccent"] = Solid(onAccent),
            //  Бегунок тумблера: в чёрно-белой акцент сам белый, и белый бегунок на белом треке
            //  становился невидимым (владелец: «почти не видно кнопку переключения, сделай там
            //  просто кружок чёрным»). Берём тот же тон, что и текст на акценте.
            ["Brush.KnobOn"] = Solid(onAccent),
            //  В чёрно-белой акцент сам белый — он же и акцентный текст.
            ["Brush.AccentText"] = Solid(accent),
            //  В чёрно-белой зелёного нет — значение пинга белое, как и весь текст.
            ["Brush.GreenText"] = Solid(accent),
            ["Brush.AccentContainer"] = Solid(accentContainer),
            ["Brush.OnAccentContainer"] = Solid(onAccentContainer),
            // Semi-тема тянет primary по DynamicResource — тоже в серый, иначе синие фокусы/кнопки.
            ["SemiColorPrimary"] = Solid(accent),
            ["SemiColorPrimaryHover"] = Solid("#E7E7E9"),
            ["SemiColorPrimaryActive"] = Solid("#C9C9CD"),

            // ── Семантика: зелёный/оранжевый/жёлтый → серый; КРАСНЫЙ остаётся (деструктив) ──
            ["Brush.Green"] = Solid(connected), // «подключено»/успех = mono connected (серо-белый)
            ["Brush.Red"] = Solid(red),
            ["Brush.RedText"] = Solid(redText),

            // ── Новые токены пакета. Без них чёрно-белая осталась бы с синими кольцами,
            //    синим диском и синим глифом Telegram — «в чёрно-белой не остаётся синего». ──
            ["Brush.ButtonOutline"] = Solid("#34363C"),
            ["Brush.ConnectDisc"] = Solid("#1E1E22"),
            ["Brush.ShieldOff"] = Solid("#4A4E57"),
            ["Brush.PressBg"] = Solid("#131315"),
            ["Brush.Yellow"] = Solid("#9BA1AD"),
            ["Brush.Telegram"] = Solid("#F2F4F8"),
            ["Brush.PopBg"] = Solid("#282A2E"),

            // ── Плитки иконок: цветные → серые; красная плитка остаётся красной ──
            ["Brush.Tile.Neutral"] = Solid(highest),
            ["Brush.Tile.Blue"] = Alpha(greyTint, 0.10),
            ["Brush.Tile.Purple"] = Alpha(greyTint, 0.10),
            ["Brush.Tile.Green"] = Alpha(greyTint, 0.10),
            ["Brush.Tile.Orange"] = Alpha(greyTint, 0.10),
            ["Brush.Tile.Yellow"] = Alpha(greyTint, 0.10),
            ["Brush.Tile.Red"] = Alpha(red, 0.20),
            ["Brush.Icon.Orange"] = Solid(onSurfaceVariant),
            ["Brush.Icon.Yellow"] = Solid(onSurfaceVariant),

            // ── Выбор / статус-чипы: серые; failed остаётся красным ──
            ["Brush.SelectedFill"] = Alpha(greyTint, 0.12),
            ["Brush.StatusChip.Green"] = Alpha(greyTint, 0.12),
            ["Brush.StatusChip.Orange"] = Alpha(greyTint, 0.12),
            ["Brush.StatusChip.Yellow"] = Alpha(greyTint, 0.12),
            ["Brush.StatusChip.Red"] = Alpha(red, 0.18),

            // ── Ховер: белый лифт. Палитра одна, поэтому и ховер один (tokens.md
            //    «Наведение rgba(255,255,255,.06)»). ──
            ["Brush.Hover"] = Alpha("#FFFFFF", 0.06),

            // ── Тост / фон Главной ──
            ["Brush.Toast.Bg"] = Solid(highest),
            ["Brush.HomeGradient"] = BuildMonoHomeGradient(light),

            // ── Connect-щит: halo и кольца из синего в серо-белый (mono connected) ──
            ["Brush.ConnectGlow"] = BuildMonoConnectGlow(connected),
            ["Brush.Ring.Outer"] = Alpha(connected, 0.20),
            ["Brush.Ring.Inner"] = Alpha(connected, 0.50),
        };
    }

    private static RadialGradientBrush BuildMonoHomeGradient(bool light)
    {
        var brush = new RadialGradientBrush
        {
            Center = new RelativePoint(0.5, 0.30, RelativeUnit.Relative),
            GradientOrigin = new RelativePoint(0.5, 0.30, RelativeUnit.Relative),
            RadiusX = new RelativeScalar(0.75, RelativeUnit.Relative),
            RadiusY = new RelativeScalar(0.75, RelativeUnit.Relative),
        };
        // Одна палитра на тему, поэтому ветвления по базе больше нет. Значения tokens.md;
        // нижняя точка #101012, а НЕ чёрный — «не чистый чёрный, фон поднят до серого».
        brush.GradientStops.Add(new GradientStop(Color.Parse("#1C1C1F"), 0));
        brush.GradientStops.Add(new GradientStop(Color.Parse("#141416"), 0.55));
        brush.GradientStops.Add(new GradientStop(Color.Parse("#101012"), 1));
        return brush;
    }

    // Серо-белое halo connect-щита (mono): непрозрачный центр connected → к нулю по краю.
    private static RadialGradientBrush BuildMonoConnectGlow(string connectedHex)
    {
        var c = Color.Parse(connectedHex);
        var brush = new RadialGradientBrush
        {
            Center = new RelativePoint(0.5, 0.5, RelativeUnit.Relative),
            GradientOrigin = new RelativePoint(0.5, 0.5, RelativeUnit.Relative),
            RadiusX = new RelativeScalar(0.5, RelativeUnit.Relative),
            RadiusY = new RelativeScalar(0.5, RelativeUnit.Relative),
        };
        brush.GradientStops.Add(new GradientStop(Color.FromArgb(0x59, c.R, c.G, c.B), 0));
        brush.GradientStops.Add(new GradientStop(Color.FromArgb(0x26, c.R, c.G, c.B), 0.5));
        brush.GradientStops.Add(new GradientStop(Color.FromArgb(0x00, c.R, c.G, c.B), 1));
        return brush;
    }

    #endregion Theme
}
