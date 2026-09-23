using v2rayN.Desktop.Common;

namespace v2rayN.Desktop.Views;

/// <summary>
/// «Локальный прокси» — подэкран настроек по единому лекалу (screens.md «Подэкраны»). До редизайна
/// этих параметров не было отдельного экрана: они жили инлайн-панелью, раскрывающейся прямо в списке
/// настроек. Спецификация выносит их на подэкран, потому что это не одна настройка, а две группы —
/// «Параметры» (адрес и порты) и «Доступ» (авторизация, UDP), — и внутри строки они не помещаются.
///
/// Пишет в РЕАЛЬНЫЙ <c>Config.Inbound[0]</c> (<see cref="InItem"/>), тот же, что читает движок:
///   • порт → <c>LocalPort</c> (вход mixed: он же SOCKS5, он же HTTP);
///   • SOCKS5-авторизация → <c>User</c> / <c>Pass</c> (пустые = авторизация выключена);
///   • «Блокировать UDP» → <c>UdpEnabled</c> НАОБОРОТ (движок хранит разрешение, экран спрашивает запрет).
///
/// ═══ «Порты меняются только при отключённом туннеле» ═══
/// Это НАСТОЯЩЕЕ ограничение, а не подпись для красоты, и до этого экрана оно не соблюдалось:
/// инлайн-панель звала <c>SettingsViewModel.CommitLocalProxyAsync</c>, которая писала новый порт и
/// дёргала перезапуск ядра ПРЯМО НА ЖИВОМ ТУННЕЛЕ. Пользователь при этом терял соединение в момент,
/// когда он всего лишь правил цифру, а системный прокси и уже открытые приложения оставались на
/// старом порту.
/// Здесь поле порта ВЫКЛЮЧЕНО, пока ядро запущено, а сноска под группой прямо говорит, что нужно
/// отключиться. Запрет ставится на ввод, а не на сохранение: узнать о нём после того, как цифра
/// набрана, — хуже, чем не дать её набрать.
///
/// Чего на экране НЕТ и почему: в прототипе есть строка «Скрыть значок в трее / Работать только как
/// локальный прокси». В ветке такой настройки не существует (в <c>UIItem</c> есть только
/// <c>AutoHideStartup</c> и <c>Hide2TrayWhenClose</c> — это другое: сворачивание окна, а не скрытие
/// значка). Рисовать переключатель, за которым ничего нет, нельзя, поэтому строка не добавлена —
/// вопрос вынесен владельцу.
/// </summary>
public partial class LocalProxyPage : UserControl, ISubPage
{
    private readonly Config _config;
    private readonly bool _coreRunning;
    private bool _saved;

    public event EventHandler? BackRequested;

    public LocalProxyPage()
    {
        InitializeComponent();

        _config = AppManager.Instance.Config;
        _coreRunning = IsCoreRunning();

        var inbound = _config.Inbound.FirstOrDefault();
        var port = inbound?.LocalPort ?? 10808;

        txtPortSocks.Text = port.ToString();
        UpdateHttpPort(port);

        // Ограничение по туннелю: гасим ВВОД, а не сохранение, и объясняем причину на месте.
        txtPortSocks.IsEnabled = !_coreRunning;
        SubPageUtil.SetClass(RowPortSocks, "tap", false);
        txtPortFoot.Text = _coreRunning ? L.T("LocalProxy_FootLive") : L.T("LocalProxy_Foot");

        // Порт HTTP пересчитываем на лету — иначе строка врёт, пока пользователь правит SOCKS-порт.
        txtPortSocks.GetObservable(TextBox.TextProperty).Subscribe(_ =>
        {
            UpdateHttpPort(int.TryParse(txtPortSocks.Text?.Trim(), out var p) && p > 0 ? p : port);
        });

        var hasAuth = (inbound?.User).IsNotEmpty() || (inbound?.Pass).IsNotEmpty();
        switchAuth.IsChecked = hasAuth;
        txtUser.Text = inbound?.User ?? string.Empty;
        txtPass.Text = inbound?.Pass ?? string.Empty;
        authFields.IsVisible = hasAuth;
        switchAuth.IsCheckedChanged += (_, _) => authFields.IsVisible = switchAuth.IsChecked == true;

        // Движок хранит РАЗРЕШЕНИЕ UDP, экран спрашивает ЗАПРЕТ — поэтому инверсия здесь, один раз,
        // а не разбросанными «!» по коду сохранения.
        switchBlockUdp.IsChecked = inbound?.UdpEnabled == false;

        WireRowToggle(RowAuth, switchAuth);
        WireRowToggle(RowBlockUdp, switchBlockUdp);

        btnBack.Click += async (_, _) => await SaveAndBackAsync();
    }

    /// <summary>Тап по строке переключает её тумблер — кроме случая, когда тапнули сам тумблер
    /// (он уже переключился, и второе переключение вернуло бы всё назад).</summary>
    private static void WireRowToggle(Border row, ToggleSwitch sw) =>
        row.Tapped += (_, e) =>
        {
            if (SubPageUtil.OriginatedIn<ToggleSwitch>(e.Source))
            {
                return;
            }
            sw.IsChecked = !(sw.IsChecked ?? false);
        };

    /// <summary>
    /// Вход в конфиге ОДИН и он <c>mixed</c> — тот же порт принимает и SOCKS5, и HTTP
    /// (V2rayInboundService.BuildInbound). Отдельный HTTP-порт появляется только при
    /// <c>SecondLocalPortEnabled</c> и всегда равен основному + 1. Поэтому строка показывает
    /// РЕАЛЬНОЕ положение дел, а не выдуманное «10809»: либо второй порт, либо тот же самый.
    /// </summary>
    private void UpdateHttpPort(int socksPort)
    {
        var second = _config.Inbound.FirstOrDefault()?.SecondLocalPortEnabled == true;
        txtPortHttp.Text = (second ? socksPort + 1 : socksPort).ToString();
        txtPortHttpNote.IsVisible = !second;
    }

    private async Task SaveAndBackAsync()
    {
        if (_saved)
        {
            return;
        }
        _saved = true;

        var inbound = _config.Inbound.FirstOrDefault();
        if (inbound is null)
        {
            BackRequested?.Invoke(this, EventArgs.Empty);
            return;
        }

        var user = switchAuth.IsChecked == true ? txtUser.Text?.Trim() ?? string.Empty : string.Empty;
        var pass = switchAuth.IsChecked == true ? txtPass.Text ?? string.Empty : string.Empty;
        var udpEnabled = switchBlockUdp.IsChecked != true;

        // Порт принимаем только при отключённом туннеле И только валидный. Мусор молча отбрасываем:
        // записать битый порт — значит сломать вход, о котором пользователь узнает лишь при следующем
        // подключении.
        var port = inbound.LocalPort;
        if (!_coreRunning
            && int.TryParse(txtPortSocks.Text?.Trim(), out var typed)
            && typed > 0 && typed < Global.MaxPort)
        {
            port = typed;
        }

        var changed = inbound.LocalPort != port
                      || (inbound.User ?? string.Empty) != user
                      || (inbound.Pass ?? string.Empty) != pass
                      || inbound.UdpEnabled != udpEnabled;

        if (changed)
        {
            inbound.LocalPort = port;
            inbound.User = user;
            inbound.Pass = pass;
            inbound.UdpEnabled = udpEnabled;
            await ConfigHandler.SaveConfig(_config);

            // Живой перезапуск — только если ядро уже работает (OFF-модель). Порт при этом измениться
            // не мог (поле было выключено), так что перезапуск не рвёт соединение ради цифры.
            if (IsCoreRunning())
            {
                StatusBarViewModel.Instance.ReloadRequested.Publish();
            }
        }

        BackRequested?.Invoke(this, EventArgs.Empty);
    }

    private static bool IsCoreRunning() =>
        AppManager.Instance.IsRunningCore(ECoreType.Xray) || AppManager.Instance.IsRunningCore(ECoreType.sing_box);
}
