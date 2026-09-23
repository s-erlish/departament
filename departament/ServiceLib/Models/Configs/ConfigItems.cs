namespace ServiceLib.Models.Configs;

[Serializable]
public class CoreBasicItem
{
    public bool LogEnabled { get; set; }

    public string Loglevel { get; set; }

    public string DefFingerprint { get; set; }

    public string DefUserAgent { get; set; }

    public string? SendThrough { get; set; }

    public string? BindInterface { get; set; }

    public bool EnableFragment { get; set; }

    public bool EnableFinalFragment { get; set; }

    public bool EnableCacheFile4Sbox { get; set; } = true;
}

[Serializable]
public class InItem
{
    public int LocalPort { get; set; }
    public string Protocol { get; set; }
    public bool UdpEnabled { get; set; }
    public bool SniffingEnabled { get; set; } = true;
    public List<string>? DestOverride { get; set; } = ["http", "tls"];
    public bool RouteOnly { get; set; }
    public bool AllowLANConn { get; set; }
    public bool NewPort4LAN { get; set; }
    public string User { get; set; }
    public string Pass { get; set; }
    public bool SecondLocalPortEnabled { get; set; }
}

[Serializable]
public class KcpItem
{
    public int Mtu { get; set; }

    public int Tti { get; set; }

    public int UplinkCapacity { get; set; }

    public int DownlinkCapacity { get; set; }

    public int CwndMultiplier { get; set; }

    public int MaxSendingWindow { get; set; }
}

[Serializable]
public class GrpcItem
{
    public int? IdleTimeout { get; set; }
    public int? HealthCheckTimeout { get; set; }
    public bool? PermitWithoutStream { get; set; }
    public int? InitialWindowsSize { get; set; }
}

[Serializable]
public class GUIItem
{
    public bool AutoRun { get; set; }

    // departament: traffic stats ON by default so the ↑/↓ speed widget (Home stats row, both compact
    // and widescreen) actually updates — a fresh config previously left both false, so the stats event
    // was never published and speed stayed frozen at 0 KB/s even while connected.
    public bool EnableStatistics { get; set; } = true;
    public bool DisplayRealTimeSpeed { get; set; } = true;
    public bool KeepOlderDedupl { get; set; }

    // departament: subscription/geo auto-update cadence in MINUTES. Default 60 (= 1 час) so a fresh
    // config auto-refreshes hourly out of the box; the Settings «Автообновление подписки» row cycles
    // 60/360/720/1440 (1/6/12/24 ч.). 0 disables. Kept in minutes to match the per-sub interval unit.
    public int AutoUpdateInterval { get; set; } = 60;
    public int TrayMenuServersLimit { get; set; } = 20;
    public bool EnableHWA { get; set; } = false;
    public bool EnableLog { get; set; } = true;
    public string? RootCertProvider { get; set; }
}

[Serializable]
public class MsgUIItem
{
    public string? MainMsgFilter { get; set; }
    public bool? AutoRefresh { get; set; }
}

[Serializable]
public class UIItem
{
    public bool EnableAutoAdjustMainLvColWidth { get; set; }
    public int MainGirdHeight1 { get; set; }
    public int MainGirdHeight2 { get; set; }
    public EGirdOrientation MainGirdOrientation { get; set; } = EGirdOrientation.Vertical;
    public string? ColorPrimaryName { get; set; }

    // departament: base appearance variant — Тёмная (Dark, default) / Светлая (Light). Persisted as the
    // ETheme name string and applied as the Avalonia RequestedThemeVariant (App.ApplyTheme).
    public string? CurrentTheme { get; set; }

    // departament: «Чёрная (AMOLED)» — a SEPARATE toggle that composes ON TOP of the Dark/Light base
    // (mirrors Android's Mono overlay applied over day/night). When true, App.ApplyTheme merges a
    // true-black overlay (pure #000000 surfaces + high-contrast ink) over whichever base variant is
    // active. Additive + defaults false, so existing JSON configs deserialize unchanged (black off).
    public bool BlackTheme { get; set; }
    public string CurrentLanguage { get; set; }
    public string CurrentFontFamily { get; set; }
    public int CurrentFontSize { get; set; }
    public bool EnableDragDropSort { get; set; }
    public bool DoubleClick2Activate { get; set; }
    public bool AutoHideStartup { get; set; }
    public bool Hide2TrayWhenClose { get; set; }

    /// <summary>
    /// Прятать значок в области уведомлений. Значок нужен не всем: у кого-то трей и так забит, а
    /// приложение живёт в панели задач. Выключенный значок НЕ меняет поведения окна — закрытие
    /// по-прежнему сворачивает или завершает по своей настройке.
    /// </summary>
    public bool HideTrayIcon { get; set; }
    public bool MacOSShowInDock { get; set; }
    public List<ColumnItem> MainColumnItem { get; set; }
    public List<WindowSizeItem> WindowSizeItem { get; set; }
    public bool HideColumnIpInfo { get; set; }

    // departament: «Облегчённый режим» (lite / performance). Persisted source of truth for
    // reduced-motion across the desktop shell: when true the connect choreography, page
    // cross-fade and press/hover transitions are suppressed. Read by App/MainWindow/ConnectHeroView
    // AND written by the Settings «Облегчённый режим» toggle (SettingsViewModel.LiteMode) — one
    // shared flag, additive + defaults false, so existing JSON configs deserialize unchanged.
    public bool LiteMode { get; set; }

    // departament: «Масштаб интерфейса» (in-app UI zoom) — a pure UI factor the user controls to make the
    // WHOLE desktop interface larger/smaller INDEPENDENT of the OS DPI scale. Fixes «всё крошечное» on a 4K
    // monitor left at 100% OS scaling: the OS renders 1:1 (physically small), so instead of fighting the OS
    // scale we let the user zoom the app itself. The desktop shell wraps its root content in a
    // LayoutTransformControl whose ScaleTransform reads this factor (range 0.8–2.0, default 1.0). Additive:
    // the default 1.0 initializer means old JSON configs (missing the field) deserialize as 1.0, and the
    // consumer (UiScaleState.Clamp) treats any 0/out-of-range value as 1.0 too, so nothing breaks.
    public double UiScale { get; set; } = 1.0;

    // departament: «Прокси по приложениям» (split-tunnel) UI state for the desktop Settings screen.
    // The EFFECTIVE routing lives in the active RoutingItem.RuleSet (managed RulesItem with
    // process_name/process_path, injected on save); these fields only persist what the picker shows.
    // Additive + safe defaults, so existing JSON configs deserialize unchanged.
    public bool PerAppProxyEnabled { get; set; }
    public bool PerAppProxyBypass { get; set; } = true; // true = exclude/bypass listed apps; false = only listed apps via VPN
    public List<string>? PerAppProxyList { get; set; }
}

[Serializable]
public class ConstItem
{
    public string? SubConvertUrl { get; set; }
    public string? GeoSourceUrl { get; set; }
    public string? SrsSourceUrl { get; set; }
    public string? RouteRulesTemplateSourceUrl { get; set; }
}

[Serializable]
public class KeyEventItem
{
    public EGlobalHotkey EGlobalHotkey { get; set; }

    public bool Alt { get; set; }

    public bool Control { get; set; }

    public bool Shift { get; set; }

    public int? KeyCode { get; set; }
}

[Serializable]
public class CoreTypeItem
{
    public EConfigType ConfigType { get; set; }

    public ECoreType CoreType { get; set; }
}

[Serializable]
public class TunModeItem
{
    public bool EnableTun { get; set; } = true;
    public bool AutoRoute { get; set; } = true;
    public bool StrictRoute { get; set; } = true;
    public string Stack { get; set; }
    public int Mtu { get; set; }
    public bool EnableIPv6Address { get; set; }
    public string IcmpRouting { get; set; }
    public bool EnableLegacyProtect { get; set; }
    public List<string>? RouteExcludeAddress { get; set; }
}

[Serializable]
public class SpeedTestItem
{
    public int SpeedTestTimeout { get; set; }
    public string SpeedTestUrl { get; set; }
    public string SpeedPingTestUrl { get; set; }
    public int MixedConcurrencyCount { get; set; }
    public string IPAPIUrl { get; set; }
    public string UdpTestTarget { get; set; }
    public int? SpeedTestPageSize { get; set; }
    public int? SpeedTestDelayInterval { get; set; }

    // departament: selected latency-probe method for the server list, mirroring Android
    // (Realping = реальная задержка через ядро / Tcping = TCP / Httping = HTTP / Icmping = ICMP).
    // Persisted as the method key. Default = Tcping so a FRESH install pings successfully out of the box
    // (a TCP handshake needs no running core — Realping-through-the-core returns «—» while disconnected).
    // Read by the ping trigger + Settings «Пинг» row. (Realping additionally falls back to a TCP probe
    // when the core can't be started — see SpeedtestService.RunRealPingAsync.)
    public string? PingMethod { get; set; } = nameof(ESpeedActionType.Tcping);
}

[Serializable]
public class RoutingBasicItem
{
    public string DomainStrategy { get; set; }
    public string DomainStrategy4Singbox { get; set; }
    public string RoutingIndexId { get; set; }
}

[Serializable]
public class ColumnItem
{
    public string Name { get; set; }
    public int Width { get; set; }
    public int Index { get; set; }
}

[Serializable]
public class Mux4RayItem
{
    public int? Concurrency { get; set; }
    public int? XudpConcurrency { get; set; }
    public string? XudpProxyUDP443 { get; set; }
}

[Serializable]
public class Mux4SboxItem
{
    public string Protocol { get; set; }
    public int MaxConnections { get; set; }
    public bool? Padding { get; set; }
}

[Serializable]
public class HysteriaItem
{
    public int UpMbps { get; set; }
    public int DownMbps { get; set; }
    public int HopInterval { get; set; } = Global.Hysteria2DefaultHopInt;
}

[Serializable]
public class ClashUIItem
{
    public ERuleMode RuleMode { get; set; }
    public bool EnableIPv6 { get; set; }
    public bool EnableMixinContent { get; set; }
    public int ProxiesSorting { get; set; }
    public bool ProxiesAutoRefresh { get; set; }
    public int ProxiesAutoDelayTestInterval { get; set; } = 10;
    public bool ConnectionsAutoRefresh { get; set; }
    public int ConnectionsRefreshInterval { get; set; } = 2;
    public List<ColumnItem> ConnectionsColumnItem { get; set; }
}

[Serializable]
public class SystemProxyItem
{
    public ESysProxyType SysProxyType { get; set; }
    public string SystemProxyExceptions { get; set; }
    public bool NotProxyLocalAddress { get; set; } = true;
    public string SystemProxyAdvancedProtocol { get; set; }
    public string? CustomSystemProxyPacPath { get; set; }
    public string? CustomSystemProxyScriptPath { get; set; }
}

[Serializable]
public class WebDavItem
{
    public string? Url { get; set; }
    public string? UserName { get; set; }
    public string? Password { get; set; }
    public string? DirName { get; set; }
}

[Serializable]
public class CheckUpdateItem
{
    /// <summary>«Искать предварительный выпуск»: выпуски vX.Y.Z-rc.N тоже предлагаются.</summary>
    public bool CheckPreReleaseUpdate { get; set; }
}

[Serializable]
public class Fragment4RayItem
{
    public string? Packets { get; set; }
    public string? Length { get; set; }
    public string? Interval { get; set; }
    public string? MaxSplit { get; set; }
}

[Serializable]
public class WindowSizeItem
{
    public string TypeName { get; set; }
    public int Width { get; set; }
    public int Height { get; set; }
}

[Serializable]
public class SimpleDNSItem
{
    public bool? UseSystemHosts { get; set; }
    public bool? AddCommonHosts { get; set; }
    public bool? FakeIP { get; set; }
    public bool? GlobalFakeIp { get; set; }
    public bool? BlockBindingQuery { get; set; }
    public string? DirectDNS { get; set; }
    public string? RemoteDNS { get; set; }
    public string? BootstrapDNS { get; set; }
    public string? Strategy4Freedom { get; set; }
    public string? Strategy4Proxy { get; set; }
    public bool? ServeStale { get; set; }
    public bool? ParallelQuery { get; set; }
    public string? Hosts { get; set; }
    public string? DirectExpectedIPs { get; set; }

    /// <summary>
    /// До какой версии встроенных умолчаний доведён этот блок (ConfigHandler.MigrateSimpleDnsDefaults).
    /// Null — конфиг старше миграции: его прямой DNS ещё может быть китайским умолчанием апстрима.
    /// </summary>
    public int? DefaultsVersion { get; set; }
}
