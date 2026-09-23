namespace v2rayN.Desktop.Common;

// ─────────────────────────────────────────────────────────────────────────────
// OWNER: WP6. Tray / App / MainWindow / BottomNav / StatusBar.
// Keys: Tray_*, Nav_*, Status_* (+ Common_* references, incl. the plural keys).
// Files: App(.axaml/.cs), MainWindow(.axaml/.cs), BottomNavBar, StatusBarView.axaml.cs,
//        ProfileDisplay.cs.
// Inventory: LOCALIZATION_PLAN.md §2.6.
// NOTE (WP0 already done): App.axaml.cs exposes a LocalizeTray() hook, called at startup
//       and on L.Instance.LanguageChanged. WP6 fills LocalizeTray() with the tray header
//       assignments (Tray_Restart/Connect/Disconnect/Show/Exit) and converts
//       ProfileDisplay.PluralRu → L.Plural. This is the ONLY L file WP6 edits.
// ─────────────────────────────────────────────────────────────────────────────

public sealed partial class L
{
    partial void RegisterShell()
    {
        // ── Tray menu (native, App.axaml): four items + the live toggle label. ──
        Add("Tray_Restart", "Перезапустить", "Restart");
        Add("Tray_Connect", "Подключить", "Connect");
        Add("Tray_Disconnect", "Отключить", "Disconnect");
        Add("Tray_Show", "Показать", "Show");
        Add("Tray_Exit", "Выход", "Exit");

        // ── Navigation labels (left rail + bottom bar) + rail collapse/expand tooltip. ──
        Add("Nav_Home", "Главная", "Home");
        Add("Nav_Settings", "Настройки", "Settings");
        Add("Nav_Account", "Аккаунт", "Account");
        Add("Nav_CollapsePanel", "Свернуть панель", "Collapse panel");
        Add("Nav_ExpandPanel", "Развернуть панель", "Expand panel");

        // ── Connection status line (shield / tray). Common_CouldntConnect lives in L.Common.cs. ──
        Add("Status_Connecting", "Подключение…", "Connecting…");
        Add("Status_Connected", "Подключено", "Connected");
    }
}
