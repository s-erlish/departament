using ServiceLib.Models.Entities;
using System.Diagnostics;
using v2rayN.Desktop.Manager;
using v2rayN.Desktop.ViewModels;

namespace v2rayN.Desktop.DesignData;

/// <summary>
/// Provides design-time data for Avalonia XAML previewer.
/// Each inner class lazily initializes <see cref="AppManager"/> with a stub config
/// so that ViewModel constructors don't fail during design-time rendering.
/// </summary>
public static class DesignData
{
    // ── Parameterless-constructor ViewModels ───────────────────────────────

    public static MainWindowViewModel? MainWindow { get; } = SafeCreate(CreateMainWindow);

    // Home aggregator — design-only sample groups (runtime is real and empty by default).
    public static HomeViewModel? Home { get; } = SafeCreate(HomeViewModel.CreateDesign);

    public static StatusBarViewModel? StatusBar { get; } = SafeCreate(CreateStatusBar);

    public static CheckUpdateViewModel? CheckUpdate { get; } = SafeCreate(() => new CheckUpdateViewModel());

    public static ProfilesSelectViewModel? ProfilesSelect { get; } = SafeCreate(() => new ProfilesSelectViewModel());

    public static BackupAndRestoreViewModel? BackupAndRestore { get; } = SafeCreate(() => new BackupAndRestoreViewModel());

    // ── ViewModels that require constructor parameters ─────────────────────

    public static AddGroupServerViewModel? AddGroupServer { get; } = SafeCreate(() => new AddGroupServerViewModel(new ProfileItem { Remarks = "Design Group", ConfigType = EConfigType.PolicyGroup }));

    public static AddServer2ViewModel? AddServer2 { get; } = SafeCreate(() => new AddServer2ViewModel(new ProfileItem { Remarks = "Design Custom Server", ConfigType = EConfigType.Custom }));

    public static AddServerViewModel? AddServer { get; } = SafeCreate(() => new AddServerViewModel(new ProfileItem { Remarks = "Design VMess Server", ConfigType = EConfigType.VMess, Address = "example.com", Port = 443 }));

    // ── Helper factories ───────────────────────────────────────────────────

    private static MainWindowViewModel CreateMainWindow()
    {
        var vm = new MainWindowViewModel { DesignMode = true };
        return vm;
    }

    private static StatusBarViewModel CreateStatusBar()
    {
        var vm = StatusBarViewModel.Instance;
        vm.InboundDisplay = "socks:10808";
        vm.InboundLanDisplay = "http:10809";
        vm.RunningServerDisplay = "🚀 Design Server (Active)";
        vm.RunningInfoDisplay = "v2rayN Design Mode";
        vm.SpeedProxyDisplay = "↑ 1.2 MB/s";
        vm.SpeedDirectDisplay = "↓ 5.6 MB/s";
        vm.RoutingItems.Add(new RoutingItem { Remarks = "Default Routing" });
        vm.RoutingItems.Add(new RoutingItem { Remarks = "Global" });
        return vm;
    }

    private static T? SafeCreate<T>(Func<T> factory) where T : class
    {
        try
        {
            AppManager.Instance.InitApp();
            AppManager.Instance.WindowDialog = new WindowDialog();
            return factory();
        }
        catch (Exception ex)
        {
            Debug.WriteLine($"[DesignData] Failed to create {typeof(T).Name}: {ex}");
            return null;
        }
    }
}
