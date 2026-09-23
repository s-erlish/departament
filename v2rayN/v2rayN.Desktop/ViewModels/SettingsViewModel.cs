namespace v2rayN.Desktop.ViewModels;

/// <summary>
/// Desktop ViewModel backing the Incy <c>SettingsView</c> (вкладка «Настройки», шесть разделов).
/// It does NOT duplicate any engine logic — it reads/writes the REAL <see cref="Config"/> (via
/// <see cref="ConfigHandler.SaveConfig"/>) and reuses the shared engine view-models, mirroring the
/// reference <c>OptionSettingViewModel</c> / <c>StatusBarViewModel</c> / <c>ThemeSettingViewModel</c>:
///
///   • Режим            → <c>TunModeItem.EnableTun</c>            (passive — see <see cref="SetTunMode"/>);
///   • Обход лок. сети   → <c>Inbound[0].AllowLANConn</c>          (== OptionSettingViewModel.AllowLANConn);
///   • IPv6             → <c>TunModeItem.EnableIPv6Address</c>    (== OptionSettingViewModel.TunEnableIPv6Address);
///   • Mux              → <c>Mux4SboxItem.Protocol</c> on/off     (== OptionSettingViewModel.Mux4SboxProtocol);
///   • Число Mux        → <c>Mux4SboxItem.MaxConnections</c>      (окошко; строка видна только при Mux ON);
///   • Фрагментация     → <c>CoreBasicItem.EnableFragment</c>     (== OptionSettingViewModel.EnableFragment);
///   • Локальный прокси  → <c>Inbound[0].LocalPort / User / Pass</c> (== OptionSettingViewModel local proxy);
///   • Запуск с системой → <c>GuiItem.AutoRun</c> + <see cref="Common.AutostartHelper"/> (Windows: задача
///     планировщика, т.к. приложение elevated) / <see cref="AutoStartupHandler.UpdateTask"/> (Linux/macOS);
///   • Автообновление    → <c>GuiItem.AutoUpdateInterval</c>       (окошко, минуты);
///   • Язык             → <c>UiItem.CurrentLanguage</c>           (окошко, живое переключение);
///   • Оформление       → <c>UiItem.CurrentTheme</c> + <c>UiItem.BlackTheme</c> (окошко, четыре равных
///     пункта — см. <see cref="SetLook"/>);
///   • Масштаб          → <c>UiItem.UiScale</c> через общий <see cref="Common.UiScaleState"/>;
///   • Облегчённый режим → <c>UiItem.LiteMode</c>                 (общий флаг reduced-motion);
///   • DNS / Пинг / Маршрутизация / … → Incy in-app суб-страницы на стеке оболочки.
///
/// <para><b>Строки-окошки.</b> Шесть строк открывают «окошко у значения» (общий <c>ValuePicker</c> /
/// <c>ValuePopup</c>): Режим · Оформление · Язык · Масштаб · Автообновление · Число Mux. Компоненту
/// нужны ДВА значения — список подписей и индекс выбранного, — поэтому каждая такая строка имеет пару
/// <c>*Options</c> / <c>*Index</c>. Подписи локализованы и пересобираются при смене языка; индекс
/// TwoWay, и его запись — единственная точка персиста этой строки.</para>
///
/// <para><b>Значение вне набора.</b> Персистированное значение может не совпасть ни с одним пунктом
/// (масштаб набран горячими клавишами Ctrl +/−, интервал обновления достался от старой сборки, язык —
/// из легаси-списка). Пустое значение в строке было бы враньём, поэтому набор ДОСТРАИВАЕТСЯ фактическим
/// значением (<see cref="MergeOption{T}"/>): строка всегда показывает то, что реально записано, а как
/// только пользователь выберет штатный пункт — лишний исчезает при следующей пересборке.</para>
///
/// Every toggle/value shown reflects the current persisted config (never hardcoded); flipping a toggle
/// writes back immediately and, when the core is already running, re-applies it live via the engine's
/// <see cref="StatusBarViewModel.ReloadRequested"/> channel (consumer-VPN OFF model: it never
/// auto-connects a disconnected app — no settings row starts the core).
/// </summary>
public class SettingsViewModel : MyReactiveObject
{
    private readonly bool _designMode;

    // Protocol written to Mux4SboxItem when the Mux switch is turned on (matches Global.SingboxMuxs).
    private const string DefaultMuxProtocol = "h2mux";

    #region Option value sets (config side of every «окошко у значения»)

    //  Интервал автообновления подписки хранится в МИНУТАХ. Набор — из screens.md
    //  (1 час · 3 часа · 6 часов · 12 часов · Выключено); 0 = выключено.
    private static readonly int[] AutoUpdateMinutes = [60, 180, 360, 720, 0];

    //  Число соединений Mux — набор из screens.md (4 · 8 · 16 · 32).
    private static readonly int[] MuxCounts = [4, 8, 16, 32];

    //  Масштаб интерфейса. К пресетам из screens.md (100 · 110 · 125 · 150) добавлены ступени ВНИЗ —
    //  75, 80, 90: владелец просил «доп выбор 80 процентов, 75 и т.д., тк их нету», и без них с
    //  экрана нельзя было выбрать ничего мельче исходного. Ctrl +/− по-прежнему ходят шагом 0.1 по
    //  всему диапазону UiScaleState.Min..Max, поэтому текущее значение может не совпасть ни с одним
    //  пресетом — тогда оно добавляется в набор (см. MergeOption).
    private static readonly double[] UiScalePresets = [0.75, 0.8, 0.9, 1.0, 1.1, 1.25, 1.5];

    //  Язык: пусто = «Системный» (L.Instance разрешает его в культуру ОС при загрузке).
    private static readonly string[] LanguageCodes = ["", "ru", "en"];

    //  DNS-пресеты (screens.md). Значения — РЕАЛЬНЫЕ адреса движка из Global.DomainRemoteDNSAddress,
    //  то есть ровно те строки, которые ядро уже умеет разбирать (в т.ч. составную «Cloudflare + Google»:
    //  V2ray принимает список через запятую, sing-box берёт из него первый). Пустая строка = встроенный
    //  резолвер («По умолчанию»), она же дефолт ветки.
    private const string DnsCloudflare = "https://cloudflare-dns.com/dns-query";
    private const string DnsGoogle = "https://dns.google/dns-query";
    private const string DnsBoth = "https://cloudflare-dns.com/dns-query,https://dns.google/dns-query";
    private const string DnsAdGuard = "https://dns.adguard-dns.com/dns-query";
    private const string DnsQuad9 = "https://dns.quad9.net/dns-query";

    private static readonly string[] DnsPresets = [DnsCloudflare, DnsGoogle, DnsBoth, DnsAdGuard, DnsQuad9];

    //  Пинг. Ядро измеряет ТОЛЬКО реальную задержку и TCP — прочие методы в движке отсутствуют, и
    //  PingSettingsPage этой же ветки их сознательно не предлагает, сводя старые значения к реальной.
    //  Показывать «HTTP-запрос» и «ICMP (ping)», которые молча работают как реальная задержка, — врать
    //  строкой; поэтому в наборе два честных метода. Вопрос вынесен в отчёт.
    private static readonly string[] PingMethods = [nameof(ESpeedActionType.Realping), nameof(ESpeedActionType.Tcping)];

    //  Оформление — четыре РАВНЫХ пункта (screens.md). Внутри они лежат на двух полях конфига:
    //  CurrentTheme (база) и BlackTheme (чёрно-белая), см. SetLook/ResolveLookIndex.
    private const int LookDark = 0;
    private const int LookLight = 1;
    private const int LookMono = 2;
    private const int LookSystem = 3;

    //  Живые наборы: копии эталонных, при необходимости достроенные фактическим значением.
    private int[] _autoUpdateValues = AutoUpdateMinutes;
    private int[] _muxCountValues = MuxCounts;
    private double[] _uiScaleValues = UiScalePresets;
    private string[] _languageValues = LanguageCodes;
    private string[] _dnsValues = DnsPresets;

    //  Пока идёт заполнение индексов из конфига, подписки-персисты обязаны молчать: иначе первичное
    //  значение из конфига тут же «сохранилось» бы обратно, а смена языка перезаписала бы тему.
    private bool _loading;

    #endregion Option value sets

    #region Toggle-backed settings (two-way from the iOS switches → real config)

    [Reactive] public bool BypassLan { get; set; }
    [Reactive] public bool EnableIpv6 { get; set; }
    [Reactive] public bool MuxEnabled { get; set; }
    [Reactive] public bool FragmentEnabled { get; set; }
    [Reactive] public bool AutoStart { get; set; }
    [Reactive] public bool HideTrayIcon { get; set; }

    /// <summary>Owner-custom «Облегчённый режим». Backed by the SHARED persisted
    /// <see cref="UIItem.LiteMode"/> flag — survives restart and is the same field the desktop
    /// animation layer reads (App/MainWindow/ConnectHeroView/PressFeedback) to suppress motion.</summary>
    [Reactive] public bool LiteMode { get; set; }

    #endregion Toggle-backed settings

    //  Полей локального прокси здесь БОЛЬШЕ НЕТ: порт и SOCKS5-авторизация переехали на подэкран
    //  LocalProxyPage, который пишет тот же Inbound[0] напрямую и, в отличие от прежней инлайн-панели,
    //  запрещает менять порт на живом туннеле. Дубликата состояния во вкладке не держим.

    #region «Окошко у значения» rows — option labels + selected index (TwoWay)

    [Reactive] public IReadOnlyList<string> ModeOptions { get; set; } = [];
    [Reactive] public int ModeIndex { get; set; }

    [Reactive] public IReadOnlyList<string> LookOptions { get; set; } = [];
    [Reactive] public int LookIndex { get; set; }

    [Reactive] public IReadOnlyList<string> LanguageOptions { get; set; } = [];
    [Reactive] public int LanguageIndex { get; set; }

    [Reactive] public IReadOnlyList<string> UiScaleOptions { get; set; } = [];
    [Reactive] public int UiScaleIndex { get; set; }

    [Reactive] public IReadOnlyList<string> AutoUpdateOptions { get; set; } = [];
    [Reactive] public int AutoUpdateIndex { get; set; }

    [Reactive] public IReadOnlyList<string> MuxCountOptions { get; set; } = [];
    [Reactive] public int MuxCountIndex { get; set; }

    [Reactive] public IReadOnlyList<string> DnsOptions { get; set; } = [];
    [Reactive] public int DnsIndex { get; set; }

    [Reactive] public IReadOnlyList<string> PingOptions { get; set; } = [];
    [Reactive] public int PingIndex { get; set; }

    #endregion «Окошко у значения» rows

    #region One-way display values (read from the real config)

    /// <summary>«Прокси по приложениям» — строка-навигация, значение приглушённое.</summary>
    [Reactive] public string PerAppText { get; set; } = string.Empty;

    /// <summary>«Проверить обновления» — текущая версия приложения справа от строки.</summary>
    [Reactive] public string VersionText { get; set; } = string.Empty;

    #endregion One-way display values

    /// <summary>Runtime constructor — binds to the live config and the shared status-bar VM.</summary>
    public SettingsViewModel()
    {
        _config = AppManager.Instance.Config;

        LoadFromConfig();
        WirePersistence();
        ReconcileAutostart();
        ReconcileSystemPreferences();

        // Режим отражает общее состояние TUN (единственный источник правды). Сам сегмент пишет конфиг
        // напрямую (см. SetTunMode) — пассивно, минуя reload/UAC-путь.
        StatusBarViewModel.Instance
            .WhenAnyValue(x => x.EnableTun)
            .Subscribe(tun => SetIndexQuietly(nameof(ModeIndex), tun ? 0 : 1));

        // Строка «Масштаб интерфейса» держится в синхроне с оболочкой: когда пользователь меняет zoom
        // горячими клавишами (Ctrl +/Ctrl −/Ctrl 0 в MainWindow), UiScaleState.Changed обновляет строку
        // здесь. Единственный рантайм-экземпляр (keep-alive SettingsView) живёт всё приложение, поэтому
        // отписка не нужна (тот же паттерн, что MotionState в MainWindow).
        v2rayN.Desktop.Common.UiScaleState.Changed += OnUiScaleStateChanged;

        // Смена языка (в т.ч. из этой же строки) пересобирает ВСЕ подписи пунктов: они локализованы.
        Common.L.Instance.LanguageChanged += (_, _) => RebuildOptionLabels();
    }

    /// <summary>Design-time constructor — sample strings only, never touches AppManager/config.</summary>
    private SettingsViewModel(bool design)
    {
        _designMode = true;
        ModeOptions = ["TUN", "Только прокси"];
        LookOptions = ["Тёмная", "Светлая", "Чёрно-белая", "Как в системе"];
        LanguageOptions = ["Системный", "Русский", "English"];
        LanguageIndex = 1;
        UiScaleOptions = ["100%", "110%", "125%", "150%"];
        AutoUpdateOptions = ["1 час", "3 часа", "6 часов", "12 часов", "Выключено"];
        MuxCountOptions = ["4", "8", "16", "32"];
        MuxCountIndex = 1;
        DnsOptions = ["Cloudflare", "Google", "Cloudflare + Google", "AdGuard", "Quad9"];
        PingOptions = ["Реальная задержка", "TCP-соединение"];
        PerAppText = "Выкл";
        VersionText = Utils.GetVersionInfo();
        BypassLan = true;
    }

    /// <summary>Design-only instance referenced from <c>Design.DataContext</c> in the axaml.</summary>
    public static SettingsViewModel Design { get; } = new(true);

    #region Load

    private void LoadFromConfig()
    {
        _loading = true;
        try
        {
            var inbound = _config.Inbound.FirstOrDefault();

            BypassLan = inbound?.AllowLANConn ?? false;
            EnableIpv6 = _config.TunModeItem.EnableIPv6Address;
            MuxEnabled = _config.Mux4SboxItem.Protocol.IsNotEmpty();
            FragmentEnabled = _config.CoreBasicItem.EnableFragment;
            AutoStart = _config.GuiItem.AutoRun;
            HideTrayIcon = _config.UiItem.HideTrayIcon;
            LiteMode = _config.UiItem.LiteMode;

            PerAppText = ResolvePerAppText();
            VersionText = Utils.GetVersionInfo();

            //  Наборы значений сначала достраиваем фактическим состоянием, потом строим подписи —
            //  иначе строка с «нештатным» значением осталась бы пустой.
            _autoUpdateValues = MergeOption(AutoUpdateMinutes, _config.GuiItem.AutoUpdateInterval);
            _muxCountValues = MergeOption(MuxCounts, ResolveMuxCount());
            _dnsValues = MergeDns(CurrentDns());
            //  Фактор читаем ПРЯМО из конфига (клампя), а не из UiScaleState.Current: этот VM
            //  конструируется в field-init MainWindow ДО того, как MainWindow засеет UiScaleState,
            //  поэтому Current тут ещё дефолтный.
            _uiScaleValues = MergeOption(UiScalePresets, v2rayN.Desktop.Common.UiScaleState.Clamp(_config.UiItem.UiScale));
            _languageValues = LanguageCodes;

            RebuildOptionLabels();

            ModeIndex = StatusBarViewModel.Instance.EnableTun ? 0 : 1;
            LookIndex = ResolveLookIndex();
            LanguageIndex = ResolveLanguageIndex();
            UiScaleIndex = IndexOf(_uiScaleValues, v2rayN.Desktop.Common.UiScaleState.Clamp(_config.UiItem.UiScale));
            AutoUpdateIndex = IndexOf(_autoUpdateValues, _config.GuiItem.AutoUpdateInterval);
            MuxCountIndex = IndexOf(_muxCountValues, ResolveMuxCount());
            DnsIndex = IndexOf(_dnsValues, CurrentDns());
            PingIndex = ResolvePingIndex();
        }
        finally
        {
            _loading = false;
        }
    }

    /// <summary>
    /// Пересобирает ПОДПИСИ пунктов всех окошек. Отдельно от значений: подписи локализованы и меняются
    /// при переключении языка, а сами значения (минуты, множители, коды) — нет.
    /// </summary>
    private void RebuildOptionLabels()
    {
        ModeOptions = ["TUN", Common.L.T("Settings_ModeProxy")];
        LookOptions =
        [
            Common.L.T("Settings_ThemeDark"),
            Common.L.T("Settings_ThemeLight"),
            Common.L.T("Settings_ThemeMono"),
            Common.L.T("Settings_ThemeSystem"),
        ];
        LanguageOptions = _languageValues.Select(FormatLanguage).ToArray();
        UiScaleOptions = _uiScaleValues.Select(FormatUiScale).ToArray();
        AutoUpdateOptions = _autoUpdateValues.Select(FormatAutoUpdate).ToArray();
        MuxCountOptions = _muxCountValues.Select(v => v.ToString()).ToArray();
        DnsOptions = _dnsValues.Select(FormatDns).ToArray();
        PingOptions = PingMethods.Select(FormatPing).ToArray();

        //  Значение строки-навигации тоже локализовано («Выкл» / «кроме N»).
        if (!_designMode)
        {
            PerAppText = ResolvePerAppText();
        }
    }

    #endregion Load

    #region Persistence (write-back on change → SaveConfig, reload only when the core is running)

    private void WirePersistence()
    {
        // Each subscription emits the loaded value once on subscribe; the equality guards below make
        // that initial emission a no-op, so only genuine user changes are persisted.
        this.WhenAnyValue(x => x.BypassLan).Subscribe(async v => await OnBypassLanChanged(v));
        this.WhenAnyValue(x => x.EnableIpv6).Subscribe(async v => await OnIpv6Changed(v));
        this.WhenAnyValue(x => x.MuxEnabled).Subscribe(async v => await OnMuxChanged(v));
        this.WhenAnyValue(x => x.FragmentEnabled).Subscribe(async v => await OnFragmentChanged(v));
        this.WhenAnyValue(x => x.AutoStart).Subscribe(async v => await OnAutoStartChanged(v));
        this.WhenAnyValue(x => x.HideTrayIcon).Subscribe(async v => await OnHideTrayIconChanged(v));
        this.WhenAnyValue(x => x.LiteMode).Subscribe(async v => await OnLiteModeChanged(v));

        //  Строки-окошки: индекс — единственная точка записи. _loading гасит первичную эмиссию и
        //  все служебные пере-выставления (внешняя смена TUN, zoom с клавиатуры, смена языка).
        this.WhenAnyValue(x => x.ModeIndex).Subscribe(async i => await OnModeIndexChanged(i));
        this.WhenAnyValue(x => x.LookIndex).Subscribe(async i => await OnLookIndexChanged(i));
        this.WhenAnyValue(x => x.LanguageIndex).Subscribe(async i => await OnLanguageIndexChanged(i));
        this.WhenAnyValue(x => x.UiScaleIndex).Subscribe(OnUiScaleIndexChanged);
        this.WhenAnyValue(x => x.AutoUpdateIndex).Subscribe(async i => await OnAutoUpdateIndexChanged(i));
        this.WhenAnyValue(x => x.MuxCountIndex).Subscribe(async i => await OnMuxCountIndexChanged(i));
        this.WhenAnyValue(x => x.DnsIndex).Subscribe(async i => await OnDnsIndexChanged(i));
        this.WhenAnyValue(x => x.PingIndex).Subscribe(async i => await OnPingIndexChanged(i));
    }

    private async Task OnBypassLanChanged(bool v)
    {
        var inbound = _config.Inbound.FirstOrDefault();
        if (_designMode || inbound == null || inbound.AllowLANConn == v)
        {
            return;
        }
        inbound.AllowLANConn = v;
        await PersistAndMaybeReload();
    }

    private async Task OnIpv6Changed(bool v)
    {
        if (_designMode || _config.TunModeItem.EnableIPv6Address == v)
        {
            return;
        }
        _config.TunModeItem.EnableIPv6Address = v;
        await PersistAndMaybeReload();
    }

    private async Task OnMuxChanged(bool v)
    {
        if (_designMode || _config.Mux4SboxItem.Protocol.IsNotEmpty() == v)
        {
            return;
        }
        _config.Mux4SboxItem.Protocol = v ? DefaultMuxProtocol : string.Empty;
        await PersistAndMaybeReload();
    }

    private async Task OnFragmentChanged(bool v)
    {
        if (_designMode || _config.CoreBasicItem.EnableFragment == v)
        {
            return;
        }
        _config.CoreBasicItem.EnableFragment = v;
        await PersistAndMaybeReload();
    }

    //  Значок прячется и возвращается СРАЗУ, без перезапуска: строка описывает наблюдаемое
    //  состояние, а не намерение на следующий запуск.
    private async Task OnHideTrayIconChanged(bool v)
    {
        if (_designMode || _config.UiItem.HideTrayIcon == v)
        {
            return;
        }
        _config.UiItem.HideTrayIcon = v;
        await ConfigHandler.SaveConfig(_config);
        v2rayN.Desktop.App.ApplyTrayIconVisibility(v);
    }

    private async Task OnAutoStartChanged(bool v)
    {
        if (_designMode || _config.GuiItem.AutoRun == v)
        {
            return;
        }
        _config.GuiItem.AutoRun = v;
        await ConfigHandler.SaveConfig(_config);
        // Windows: регистрируем/снимаем автозапуск под именем «departament». departament запускается
        // elevated (app.manifest requireAdministrator ради TUN), поэтому AutostartHelper заводит ЗАДАЧУ
        // ПЛАНИРОВЩИКА — Run-ключ Windows для elevated-приложения при входе молча пропускает.
        // Non-Windows: AutostartHelper — no-op, автозапуском владеет общий handler (.desktop/LaunchAgent).
        if (Utils.IsWindows())
        {
            v2rayN.Desktop.Common.AutostartHelper.Apply(v);
        }
        else
        {
            await AutoStartupHandler.UpdateTask(_config);
        }
    }

    /// <summary>
    /// Автозапуск: источник правды — ОС, а не запомненный флаг. Строка «Запуск с системой» читала
    /// <c>GuiItem.AutoRun</c>, который мог разойтись с реальностью, поэтому сверяем их при старте:
    ///   • флаг включён, но рабочей регистрации нет (или она указывает на переехавший после обновления
    ///     exe, или осталась прежним Run-значением, которое Windows не выполняла из-за UAC) →
    ///     перерегистрируем — это чинит уже сломанную установку без участия пользователя;
    ///   • флаг выключен, а регистрация в системе есть → показываем строку включённой, чтобы она
    ///     не врала о том, что произойдёт при входе в систему.
    /// Работа с планировщиком задач идёт в фоне: конструктор окна не должен её ждать. Не-Windows —
    /// автозапуском владеет <see cref="AutoStartupHandler"/>, сверять здесь нечего.
    /// </summary>
    private void ReconcileAutostart()
    {
        if (_designMode || !Utils.IsWindows())
        {
            return;
        }

        _ = Task.Run(() =>
        {
            try
            {
                if (_config.GuiItem.AutoRun)
                {
                    if (!v2rayN.Desktop.Common.AutostartHelper.IsCurrent())
                    {
                        v2rayN.Desktop.Common.AutostartHelper.Set();
                    }
                }
                else if (v2rayN.Desktop.Common.AutostartHelper.IsEnabled())
                {
                    // Присвоение поднимет OnAutoStartChanged → флаг запишется в конфиг, регистрация
                    // приведётся к актуальному виду. Только из UI-потока: за ним следит биндинг строки.
                    Dispatcher.UIThread.Post(() => AutoStart = true);
                }
            }
            catch (Exception ex)
            {
                // Автозапуск — не критичный путь: сбой планировщика не должен ронять запуск приложения.
                Logging.SaveLog("SettingsViewModel", ex);
            }
        });
    }

    /// <summary>
    /// «Как в системе» — для темы и для языка. Оба системных варианта хранятся как «пусто/FollowSystem»
    /// и разрешаются в конкретное значение ЗДЕСЬ, потому что разрешать их некому:
    ///   • <c>App.ApplyTheme</c> сводит любую не-Light/Dark базу к тёмной (одна строка в App.axaml.cs —
    ///     вне этой полосы работ, заявка в отчёте), поэтому системную базу считаем сами;
    ///   • <c>L</c> при пустом коде языка откатывается к русскому, а не к культуре ОС.
    /// Пере-применяем сразу после загрузки: приложение уже нарисовало первый кадр по конфигу, и если
    /// система светлая/английская — этот вызов приводит интерфейс к системе без перезапуска.
    /// </summary>
    private void ReconcileSystemPreferences()
    {
        if (_designMode)
        {
            return;
        }

        if (_config.UiItem.CurrentTheme == nameof(ETheme.FollowSystem))
        {
            v2rayN.Desktop.App.ApplyTheme(EffectiveThemeName(_config.UiItem.CurrentTheme), _config.UiItem.BlackTheme);
        }

        if (_config.UiItem.CurrentLanguage.IsNullOrEmpty())
        {
            Common.L.Instance.SetLanguage(SystemLanguageCode());
        }
    }

    /// <summary>«Облегчённый режим» — a pure UI/motion flag. Persist it so it survives restart AND so the
    /// animation layer can read it live; it never touches the core, so there is deliberately no reload.</summary>
    private async Task OnLiteModeChanged(bool v)
    {
        if (_designMode || _config.UiItem.LiteMode == v)
        {
            return;
        }
        _config.UiItem.LiteMode = v;
        await ConfigHandler.SaveConfig(_config);
        // Broadcast the change so the shell (MainWindow), the connect hero (ConnectHeroView), the press
        // ladder (PressFeedback) and «окошко у значения» (ValuePopup) re-apply their motion state
        // IMMEDIATELY — no restart. This is what actually stops the shield spin, the row dip, the popup
        // reveal and the page rise the instant lite is enabled (and revives them off).
        v2rayN.Desktop.Common.MotionState.SetLite(v);
    }

    private async Task PersistAndMaybeReload()
    {
        await ConfigHandler.SaveConfig(_config);
        // Re-apply live only if the core is already up; a disconnected app stays disconnected.
        if (IsCoreRunning())
        {
            StatusBarViewModel.Instance.ReloadRequested.Publish();
        }
    }

    private static bool IsCoreRunning() =>
        AppManager.Instance.IsRunningCore(ECoreType.Xray) || AppManager.Instance.IsRunningCore(ECoreType.sing_box);

    #endregion Persistence

    #region «Окошко у значения» — index handlers (each is the single write point of its row)

    private async Task OnModeIndexChanged(int index)
    {
        if (_designMode || _loading || index < 0)
        {
            return;
        }
        await SetTunMode(index == 0);
    }

    /// <summary>
    /// «Оформление» — ЧЕТЫРЕ РАВНЫХ пункта (Тёмная · Светлая · Чёрно-белая · Как в системе), хотя в
    /// конфиге они лежат на двух полях: <c>CurrentTheme</c> (база) и <c>BlackTheme</c> (чёрно-белая).
    /// Раньше чёрно-белая была ОТДЕЛЬНЫМ тумблером-надстройкой над базой — то есть пятым состоянием,
    /// которого в списке нет. Теперь пункты взаимоисключающие: выбор чёрно-белой гасит базу как
    /// самостоятельный выбор (палитра сведена к одной и от базы больше не зависит), выбор любой другой
    /// снимает чёрно-белую.
    /// </summary>
    private async Task OnLookIndexChanged(int index)
    {
        if (_designMode || _loading || index < 0)
        {
            return;
        }
        await SetLook(index);
    }

    private async Task OnLanguageIndexChanged(int index)
    {
        if (_designMode || _loading || index < 0 || index >= _languageValues.Length)
        {
            return;
        }

        var code = _languageValues[index];
        if ((_config.UiItem.CurrentLanguage ?? string.Empty) == code)
        {
            return;
        }

        _config.UiItem.CurrentLanguage = code;
        await ConfigHandler.SaveConfig(_config);

        // Живое переключение языка без перезапуска: L.SetLanguage синхронизирует CurrentUICulture
        // (движок/ResUI) и обновляет все открытые {loc:T} биндинги. Пустой код = «Системный» →
        // разрешаем в культуру ОС, иначе L молча остался бы на прежнем языке.
        Common.L.Instance.SetLanguage(code.IsNullOrEmpty() ? SystemLanguageCode() : code);
        RefreshDisplayValues();
    }

    private void OnUiScaleIndexChanged(int index)
    {
        if (_designMode || _loading || index < 0 || index >= _uiScaleValues.Length)
        {
            return;
        }

        var next = _uiScaleValues[index];
        if (Math.Abs(v2rayN.Desktop.Common.UiScaleState.Current - next) < 0.0001)
        {
            return;
        }

        // Толкаем фактор в общий UiScaleState → оболочка (MainWindow) применяет zoom мгновенно
        // (трансформ + мин-размер + брейкпоинт) и мы персистим его в UiItem.UiScale.
        v2rayN.Desktop.Common.UiScaleState.Set(next);
        _config.UiItem.UiScale = next;
        _ = ConfigHandler.SaveConfig(_config);
    }

    private async Task OnAutoUpdateIndexChanged(int index)
    {
        if (_designMode || _loading || index < 0 || index >= _autoUpdateValues.Length)
        {
            return;
        }

        var next = _autoUpdateValues[index];
        if (_config.GuiItem.AutoUpdateInterval == next)
        {
            return;
        }

        _config.GuiItem.AutoUpdateInterval = next;
        await ConfigHandler.SaveConfig(_config);
    }

    private async Task OnMuxCountIndexChanged(int index)
    {
        if (_designMode || _loading || index < 0 || index >= _muxCountValues.Length)
        {
            return;
        }

        var next = _muxCountValues[index];
        if (_config.Mux4SboxItem.MaxConnections == next)
        {
            return;
        }

        _config.Mux4SboxItem.MaxConnections = next;
        await PersistAndMaybeReload();
    }

    /// <summary>DNS: пишет РЕАЛЬНЫЙ адрес пресета в <c>SimpleDNSItem.RemoteDNS</c> — то же поле и те же
    /// значения, что сохраняет суб-страница DNS и читает ядро.</summary>
    private async Task OnDnsIndexChanged(int index)
    {
        if (_designMode || _loading || index < 0 || index >= _dnsValues.Length)
        {
            return;
        }

        var next = _dnsValues[index];
        if (CurrentDns() == next)
        {
            return;
        }

        _config.SimpleDNSItem ??= new SimpleDNSItem();
        _config.SimpleDNSItem.RemoteDNS = next;
        await PersistAndMaybeReload();
    }

    /// <summary>Пинг: пишет ключ метода в <c>SpeedTestItem.PingMethod</c> (значения ESpeedActionType —
    /// те же, что читает триггер замера).</summary>
    private async Task OnPingIndexChanged(int index)
    {
        if (_designMode || _loading || index < 0 || index >= PingMethods.Length)
        {
            return;
        }

        var next = PingMethods[index];
        if (_config.SpeedTestItem.PingMethod == next)
        {
            return;
        }

        _config.SpeedTestItem.PingMethod = next;
        await ConfigHandler.SaveConfig(_config);
    }

    /// <summary>Внешняя смена (TUN из другого экрана, zoom с клавиатуры) — обновить индекс БЕЗ записи
    /// в конфиг: значение уже оттуда и пришло, повторный персист был бы эхом.</summary>
    private void SetIndexQuietly(string property, int value)
    {
        var was = _loading;
        _loading = true;
        try
        {
            switch (property)
            {
                case nameof(ModeIndex): ModeIndex = value; break;
                case nameof(UiScaleIndex): UiScaleIndex = value; break;
            }
        }
        finally
        {
            _loading = was;
        }
    }

    private void OnUiScaleStateChanged(object? sender, double scale)
    {
        //  Значение с клавиатуры может выпасть из набора пресетов — тогда набор достраивается им,
        //  и строка продолжает показывать правду («115%»), а не пустоту.
        var merged = MergeOption(UiScalePresets, scale);
        if (!merged.SequenceEqual(_uiScaleValues))
        {
            _uiScaleValues = merged;
            UiScaleOptions = _uiScaleValues.Select(FormatUiScale).ToArray();
        }
        SetIndexQuietly(nameof(UiScaleIndex), IndexOf(_uiScaleValues, scale));
    }

    #endregion «Окошко у значения» — index handlers

    #region Row actions (invoked from the view code-behind on tap)

    /// <summary>
    /// Режим: set TUN vs «Только прокси» to a SPECIFIC value. PASSIVE setting — consumer-VPN OFF model:
    /// a settings tap must never start the core or relaunch the app. So we do NOT route through
    /// <see cref="StatusBarViewModel.EnableTun"/>'s <c>DoEnableTun</c> (which unconditionally reloads
    /// and, on non-admin Windows, calls <c>RebootAsAdmin()</c> with a UAC prompt). Instead we write the
    /// real config directly FIRST, persist, then mirror the shared VM (DoEnableTun early-returns because
    /// config already equals the new value), and re-apply live ONLY when the core is already running.
    /// Idempotent: no-op if already at <paramref name="enable"/>. TUN admin escalation belongs to the
    /// connect action, not this row.
    /// </summary>
    public async Task SetTunMode(bool enable)
    {
        if (_designMode || _config.TunModeItem.EnableTun == enable)
        {
            return;
        }

        _config.TunModeItem.EnableTun = enable;
        await ConfigHandler.SaveConfig(_config);

        // Keep the shared status-bar VM in sync WITHOUT triggering its reload/UAC path: DoEnableTun
        // early-returns because _config.TunModeItem.EnableTun already equals the value we assign here.
        StatusBarViewModel.Instance.EnableTun = enable;

        // Re-apply live only if the core is already up; a disconnected app stays disconnected.
        if (IsCoreRunning())
        {
            StatusBarViewModel.Instance.ReloadRequested.Publish();
        }
    }

    /// <summary>
    /// «Оформление»: применяет ОДИН из четырёх равных вариантов. Персистит обе части (базу и
    /// чёрно-белую) и применяет вживую через <c>App.ApplyTheme</c> — тот же путь ведёт theme-flood
    /// (круговая заливка новой темы), поэтому отдельной обратной связи строке не нужно.
    /// «Как в системе» разрешается в конкретную базу здесь (см. <see cref="ReconcileSystemPreferences"/>).
    /// </summary>
    public async Task SetLook(int look)
    {
        if (_designMode)
        {
            return;
        }

        var theme = look switch
        {
            LookLight => nameof(ETheme.Light),
            LookSystem => nameof(ETheme.FollowSystem),
            //  Чёрно-белая: палитра сведена к ОДНОЙ и от базы не зависит, поэтому базу фиксируем
            //  тёмной — так после выключения чёрно-белой приложение возвращается в предсказуемую тему.
            LookMono => nameof(ETheme.Dark),
            _ => nameof(ETheme.Dark),
        };
        var mono = look == LookMono;

        if (_config.UiItem.CurrentTheme == theme && _config.UiItem.BlackTheme == mono)
        {
            return;
        }

        _config.UiItem.CurrentTheme = theme;
        _config.UiItem.BlackTheme = mono;
        await ConfigHandler.SaveConfig(_config);
        v2rayN.Desktop.App.ApplyTheme(EffectiveThemeName(theme), mono);
    }

    /// <summary>Re-read values that a sub-screen (DNS / Пинг / per-app / …) may have changed, so the row
    /// labels stay truthful after it closes. Индексы окошек тоже пере-выставляются — под <c>_loading</c>,
    /// чтобы чтение не превратилось в запись.</summary>
    public void RefreshDisplayValues()
    {
        if (_designMode)
        {
            return;
        }

        _loading = true;
        try
        {
            PerAppText = ResolvePerAppText();

            _autoUpdateValues = MergeOption(AutoUpdateMinutes, _config.GuiItem.AutoUpdateInterval);
            _muxCountValues = MergeOption(MuxCounts, ResolveMuxCount());
            _dnsValues = MergeDns(CurrentDns());
            AutoUpdateOptions = _autoUpdateValues.Select(FormatAutoUpdate).ToArray();
            MuxCountOptions = _muxCountValues.Select(v => v.ToString()).ToArray();
            DnsOptions = _dnsValues.Select(FormatDns).ToArray();

            ModeIndex = StatusBarViewModel.Instance.EnableTun ? 0 : 1;
            LookIndex = ResolveLookIndex();
            LanguageIndex = ResolveLanguageIndex();
            AutoUpdateIndex = IndexOf(_autoUpdateValues, _config.GuiItem.AutoUpdateInterval);
            MuxCountIndex = IndexOf(_muxCountValues, ResolveMuxCount());
            DnsIndex = IndexOf(_dnsValues, CurrentDns());
            PingIndex = ResolvePingIndex();
        }
        finally
        {
            _loading = false;
        }
    }

    #endregion Row actions

    #region Display resolvers

    /// <summary>Текущий remote-DNS, нормализованный (пусто и пробелы — одно и то же «по умолчанию»).</summary>
    private string CurrentDns() => _config.SimpleDNSItem?.RemoteDNS?.Trim() ?? string.Empty;

    /// <summary>Подпись пресета DNS. Сырой DoH-адрес в строку не выводится — только имя провайдера;
    /// незнакомое значение (свой сервер, заданный где-то ещё) называется «Свой», но НЕ подменяется.</summary>
    private static string FormatDns(string value) => value switch
    {
        "" => Common.L.T("Common_Default"),
        DnsCloudflare => "Cloudflare",
        DnsGoogle => "Google",
        DnsBoth => "Cloudflare + Google",
        DnsAdGuard => "AdGuard",
        DnsQuad9 => "Quad9",
        _ => Common.L.T("Common_Custom"),
    };

    /// <summary>Подпись метода замера задержки.</summary>
    private static string FormatPing(string method) => method == nameof(ESpeedActionType.Tcping)
        ? Common.L.T("Ping_TcpTitle")
        : Common.L.T("Ping_RealTitle");

    /// <summary>Текущий метод замера. Старые Httping / Icmping движком не поддержаны и сводятся им же
    /// к реальной задержке — показываем то, что реально произойдёт.</summary>
    private int ResolvePingIndex() =>
        _config.SpeedTestItem?.PingMethod == nameof(ESpeedActionType.Tcping) ? 1 : 0;

    private int ResolveMuxCount() =>
        _config.Mux4SboxItem.MaxConnections > 0 ? _config.Mux4SboxItem.MaxConnections : 8;

    private string ResolvePerAppText()
    {
        if (!_config.UiItem.PerAppProxyEnabled)
        {
            return Common.L.T("Common_Off");
        }
        var n = _config.UiItem.PerAppProxyList?.Count ?? 0;
        var mode = _config.UiItem.PerAppProxyBypass ? Common.L.T("Settings_PerAppExcept") : Common.L.T("Settings_PerAppOnly");
        return n > 0 ? $"{mode} {n}" : Common.L.T("Common_On");
    }

    /// <summary>Текущий пункт «Оформления». Чёрно-белая перекрывает базу: она — самостоятельный
    /// вариант, а не надстройка, поэтому проверяется первой.</summary>
    private int ResolveLookIndex()
    {
        if (_config.UiItem.BlackTheme)
        {
            return LookMono;
        }
        return _config.UiItem.CurrentTheme switch
        {
            nameof(ETheme.Light) => LookLight,
            nameof(ETheme.FollowSystem) => LookSystem,
            _ => LookDark,
        };
    }

    /// <summary>Текущий пункт «Языка». Легаси-коды (zh-Hans, fa, fr …) в наборе не представлены, а
    /// таблица L знает только ru/en и печатает их русским — показываем «Русский», чтобы строка
    /// соответствовала тому, что на экране. Ничего при этом не переписывается.</summary>
    private int ResolveLanguageIndex() => _config.UiItem.CurrentLanguage switch
    {
        null or "" => 0,
        "en" => 2,
        _ => 1,
    };

    private static string FormatLanguage(string code) => code switch
    {
        "" => Common.L.T("Settings_LangSystem"),
        "ru" => Common.L.T("Settings_LangRussian"),
        "en" => "English",
        _ => code,
    };

    private static string FormatUiScale(double scale) => $"{Math.Round(scale * 100)}%";

    /// <summary>
    /// Подписи «Автообновления подписки»: «1 час · 3 часа · 6 часов · 12 часов · Выключено» —
    /// дословно из прототипа, под них же посчитана ширина окошка (190).
    ///
    /// <para>Почему не общими ключами: в таблице L есть только сокращения (<c>Common_HoursShort</c>
    /// «{0} ч.», <c>Common_Off</c> «Выкл»), а склонение часов требует множественной формы
    /// (<c>AddPlural</c>), которой для часов заведено не было. Заводить её — правка
    /// <c>Common/L.Common.cs</c>, файла чужой дорожки, поэтому формы живут здесь, а заявка на
    /// промоушен «Common_HoursPlural» + «Common_Disabled» — в отчёте. Язык берём у той же
    /// <see cref="Common.L"/>, так что строка переключается вместе со всем интерфейсом.</para>
    /// </summary>
    private static string FormatAutoUpdate(int minutes)
    {
        var english = Common.L.Instance.CurrentLang == "en";
        if (minutes <= 0)
        {
            return english ? "Disabled" : "Выключено";
        }

        var hours = minutes / 60;
        if (english)
        {
            return hours == 1 ? "1 hour" : $"{hours} hours";
        }

        //  Русская множественная форма: 1 — «час», 2–4 — «часа», 0 и 5–20 — «часов»; десятки
        //  считаются по последней цифре, кроме подростковых 11–14.
        var tail = hours % 100;
        var last = hours % 10;
        var word = tail is >= 11 and <= 14 ? "часов"
            : last == 1 ? "час"
            : last is >= 2 and <= 4 ? "часа"
            : "часов";
        return $"{hours} {word}";
    }

    #endregion Display resolvers

    #region System («Как в системе») resolution

    /// <summary>База, которую надо реально применить: «Как в системе» разрешается в вариант ОС.</summary>
    private static string EffectiveThemeName(string? stored) => stored switch
    {
        nameof(ETheme.Light) => nameof(ETheme.Light),
        nameof(ETheme.FollowSystem) => SystemThemeName(),
        _ => nameof(ETheme.Dark),
    };

    private static string SystemThemeName()
    {
        try
        {
            var variant = Application.Current?.PlatformSettings?.GetColorValues().ThemeVariant;
            return variant == PlatformThemeVariant.Light ? nameof(ETheme.Light) : nameof(ETheme.Dark);
        }
        catch
        {
            // Платформа не сообщила предпочтение — тёмная, как и везде по умолчанию.
            return nameof(ETheme.Dark);
        }
    }

    private static string SystemLanguageCode() =>
        CultureInfo.CurrentUICulture.TwoLetterISOLanguageName == "ru" ? "ru" : "en";

    #endregion System («Как в системе») resolution

    #region Option-set helpers

    /// <summary>
    /// Достраивает эталонный набор фактическим значением, если его там нет. Пустая строка значения —
    /// худший из возможных исходов: она не говорит НИЧЕГО о том, что записано в конфиге, поэтому набор
    /// расширяется, а не значение подменяется ближайшим. Числовые наборы держим отсортированными
    /// (кроме «Выключено» = 0, который остаётся в хвосте), чтобы порядок пунктов не прыгал.
    /// </summary>
    private static T[] MergeOption<T>(T[] canonical, T actual) where T : IComparable<T>
    {
        if (canonical.Contains(actual))
        {
            return canonical;
        }

        var tail = canonical.Where(v => v.CompareTo(default!) == 0).ToArray();   // «выключено» = 0
        var head = canonical.Where(v => v.CompareTo(default!) != 0).Append(actual).OrderBy(v => v).ToArray();
        return [.. head, .. tail];
    }

    /// <summary>
    /// То же для DNS, но порядок здесь смысловой, а не числовой: «По умолчанию» (пустое значение —
    /// встроенный резолвер, и дефолт ветки) встаёт ПЕРЕД пресетами, чужой адрес — ПОСЛЕ них. Так
    /// строка показывает уже настроенный где-то ещё свой сервер и не затирает его молча.
    /// </summary>
    private static string[] MergeDns(string actual)
    {
        if (DnsPresets.Contains(actual))
        {
            return DnsPresets;
        }
        return actual.IsNullOrEmpty() ? ["", .. DnsPresets] : [.. DnsPresets, actual];
    }

    private static int IndexOf<T>(T[] values, T value)
    {
        var i = Array.IndexOf(values, value);
        return i >= 0 ? i : 0;
    }

    #endregion Option-set helpers
}
