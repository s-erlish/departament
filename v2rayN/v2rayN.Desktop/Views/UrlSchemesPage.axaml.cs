using System.Runtime.Versioning;
using Microsoft.Win32;
using v2rayN.Desktop.Common;

namespace v2rayN.Desktop.Views;

/// <summary>
/// «Схемы URL-адресов» — подэкран настроек по единому лекалу (screens.md «Подэкраны»): список
/// команд (тап копирует) → регистрация схемы в системе.
///
/// Real на Windows: регистрирует/снимает протокол <c>depv://</c> в HKCU\Software\Classes (per-user,
/// без прав администратора), чтобы система запускала departament по таким ссылкам, и показывает
/// живое состояние регистрации. Ядро не трогается.
/// NOTE: доставка запущенной команды в работающее приложение — забота слоя старта (разбор
/// аргументов); этот экран владеет регистрацией протокола в ОС и её обнаружением.
/// Стрелка «назад» поднимает <see cref="BackRequested"/>.
/// </summary>
public partial class UrlSchemesPage : UserControl, ISubPage
{
    private const string Scheme = "depv";

    // Browser→app SSO return scheme. Distinct from the tunnel-action scheme «depv» because the site's
    // safe-return allowlist only accepts a «departament…»-prefixed scheme (^departament[a-z0-9]*$), which
    // «depv» does not match. Registered alongside «depv» so «Войти через сайт» can round-trip back.
    private const string AuthScheme = "departamentvpn";

    private bool _suppressSwitch;

    public event EventHandler? BackRequested;

    public UrlSchemesPage()
    {
        InitializeComponent();

        // Порядок — от самой частой команды к самой редкой: сначала то, ради чего схему заводят.
        var schemes = new List<(string Scheme, string Hint)>
        {
            ("depv://connect", L.T("UrlSchemes_ConnectHint")),
            ("depv://disconnect", L.T("UrlSchemes_DisconnectHint")),
            ("depv://toggle", L.T("UrlSchemes_Toggle")),
            ("depv://open", L.T("UrlSchemes_OpenApp")),
            ("depv://close", L.T("UrlSchemes_Stop")),
            ("depv://add/{url}", L.T("UrlSchemes_SubHint")),
            ("depv://import/{base64}", L.T("UrlSchemes_Import")),
            ("departamentvpn://auth", L.T("Common_SignInWebsite")),
        };
        listSchemes.ItemsSource = schemes.Select((s, i) => new SchemeRow(s.Scheme, s.Hint, i > 0)).ToList();

        btnBack.Click += (_, _) => BackRequested?.Invoke(this, EventArgs.Empty);

        // Тап по строке переключает тумблер — но не когда источником тапа был он сам.
        RowRegister.Tapped += (_, e) =>
        {
            if (!SubPageUtil.OriginatedIn<ToggleSwitch>(e.Source) && switchRegister.IsEnabled)
            {
                switchRegister.IsChecked = switchRegister.IsChecked != true;
            }
        };
        switchRegister.IsCheckedChanged += (_, _) =>
        {
            if (_suppressSwitch)
            {
                return;
            }
            if (switchRegister.IsChecked == true)
            {
                Register();
            }
            else
            {
                Unregister();
            }
        };

        RefreshStatus();
    }

    private void OnSchemeRowTapped(object? sender, TappedEventArgs e)
    {
        if (sender is Border { DataContext: SchemeRow row })
        {
            _ = CopyAsync(row.Scheme);
        }
    }

    private async Task CopyAsync(string text)
    {
        await SubPageUtil.CopyAsync(this, text);
        txtCopyState.Text = L.T("UrlSchemes_Copied");
    }

    /// <summary>Приводит тумблер и подпись к настоящему состоянию реестра. Ставим тумблер под
    /// заглушкой <c>_suppressSwitch</c>: иначе синхронизация состояния сама вызвала бы регистрацию.</summary>
    private void RefreshStatus()
    {
        _suppressSwitch = true;
        try
        {
            // Не Windows — группы просто нет: схему тут заводит не приложение, и показывать ради
            // этого навсегда выключенный тумблер значит рисовать орган управления, который на любое
            // нажатие отвечает отказом. Решение владельца: «Регистрация» только под Windows.
            if (!Utils.IsWindows())
            {
                RegistrationGroup.IsVisible = false;
                switchRegister.IsChecked = false;
                return;
            }
            RegistrationGroup.IsVisible = true;
            var reg = IsRegistered();
            switchRegister.IsChecked = reg;
            txtStatus.Text = reg ? L.T("UrlSchemes_Registered") : L.T("UrlSchemes_NotRegistered");
        }
        finally
        {
            _suppressSwitch = false;
        }
    }

    //  Реестр — только Windows. Вызывающий (RefreshStatus/Register) уже стоит за Utils.IsWindows(),
    //  который помечен [SupportedOSPlatformGuard("windows")]; атрибут доносит тот же контракт до
    //  анализатора внутри самого метода — иначе CA1416 ругается на каждое обращение к Registry.
    [SupportedOSPlatform("windows")]
    private static bool IsRegistered()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey($@"Software\Classes\{Scheme}\shell\open\command");
            var cmd = key?.GetValue(null) as string;
            return cmd.IsNotEmpty();
        }
        catch
        {
            return false;
        }
    }

    private void Register()
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
                txtStatus.Text = L.T("UrlSchemes_NoPath");
                return;
            }
            // Register both the tunnel-action scheme and the browser→app sign-in return scheme.
            RegisterScheme(Scheme, exe!);
            RegisterScheme(AuthScheme, exe!);
        }
        catch (Exception ex)
        {
            txtStatus.Text = L.T("UrlSchemes_RegisterFailed") + ex.Message;
            return;
        }
        RefreshStatus();
    }

    [SupportedOSPlatform("windows")]
    private static void RegisterScheme(string scheme, string exe)
    {
        using var root = Registry.CurrentUser.CreateSubKey($@"Software\Classes\{scheme}");
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

    private void Unregister()
    {
        if (!Utils.IsWindows())
        {
            return;
        }
        try
        {
            Registry.CurrentUser.DeleteSubKeyTree($@"Software\Classes\{Scheme}", throwOnMissingSubKey: false);
            Registry.CurrentUser.DeleteSubKeyTree($@"Software\Classes\{AuthScheme}", throwOnMissingSubKey: false);
        }
        catch (Exception ex)
        {
            txtStatus.Text = L.T("UrlSchemes_RemoveFailed") + ex.Message;
            return;
        }
        RefreshStatus();
    }

    /// <summary>Строка списка: схема, пояснение и флаг разделителя (он рисуется перед каждой строкой,
    /// кроме первой).</summary>
    public sealed record SchemeRow(string Scheme, string Hint, bool ShowDivider);
}
