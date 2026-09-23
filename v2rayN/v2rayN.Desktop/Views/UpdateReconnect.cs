using ServiceLib.Services.AppUpdate;
using v2rayN.Desktop.ViewModels;

namespace v2rayN.Desktop.Views;

/// <summary>
/// Возврат подключения после перезапуска ради обновления.
///
/// <para>Прежняя версия в момент «Перезапустить» оставляет одноразовую метку (<see cref="AppUpdatePendingInstall"/>):
/// к какому серверу и в каком режиме было подключение. Следующий запуск читает её один раз, дожидается
/// движка и списка серверов и подключается тем же путём, что и тап по щиту: «Подключение…» на щите,
/// отказ с причиной, системный прокси после старта ядра — всё как при обычном подключении. Решает
/// <see cref="AppUpdateReconnect"/>: метка свежая, запущена та версия, режим тот же.</para>
///
/// <para>Пользователь главнее метки. Тап по щиту, выбор сервера, «Подключить» или «Отключить» в трее и F5
/// до этого момента отменяют возврат (<see cref="NoteUserAction"/>); уже идущее подключение — тоже.</para>
/// </summary>
internal static class UpdateReconnect
{
    // Движок (MainWindowViewModel.Init) и список серверов обычно готовы через 1–2 с после окна. Запуск,
    // который не собрался и за полминуты, подключать сам не должен: это уже не «сразу после перезапуска».
    private static readonly TimeSpan ReadyTimeout = TimeSpan.FromSeconds(30);

    private const string Tag = "AppUpdate";

    private static volatile bool s_userActed;
    private static int s_started;

    /// <summary>Пользователь сам тронул подключение или выбор сервера. После этого подключение за него не возвращается.</summary>
    public static void NoteUserAction() => s_userActed = true;

    /// <summary>Один раз за сеанс, из окна, как только есть модель «Главной».</summary>
    public static void Start(MainWindowViewModel main, Func<HomeViewModel?> home)
    {
        if (Interlocked.Exchange(ref s_started, 1) == 1)
        {
            return;
        }
        _ = RunAsync(main, home);
    }

    private static async Task RunAsync(MainWindowViewModel main, Func<HomeViewModel?> getHome)
    {
        try
        {
            // Чтение метки — файл, поэтому не в потоке интерфейса. Её нет почти всегда: обычный запуск.
            var pending = await Task.Run(AppUpdateManager.Instance.TakeReconnect);
            if (pending?.Reconnect is not { } marker)
            {
                return;
            }

            // Подключать можно, когда движок собран (BlReloadEnabled поднимает конец Init) и список серверов
            // прочитан: раньше Reload не знает ни ядра, ни сервера.
            var until = DateTime.UtcNow + ReadyTimeout;
            HomeViewModel? home;
            while ((home = getHome()) is not { IsResolved: true } || !main.BlReloadEnabled)
            {
                if (DateTime.UtcNow > until)
                {
                    Logging.SaveLog($"{Tag}: not reconnecting, the app was not ready within {ReadyTimeout.TotalSeconds:0} s");
                    return;
                }
                await Task.Delay(100);
            }

            var config = AppManager.Instance.Config;
            var running = AppUpdateManager.Instance.Running;
            var verdict = AppUpdateReconnect.Screen(pending, DateTime.UtcNow, running, Busy(home), config.TunModeItem.EnableTun);
            if (verdict != AppUpdateReconnectVerdict.Connect)
            {
                Logging.SaveLog($"{Tag}: not reconnecting after the restart for {pending.Tag}: {verdict} (running {running}, tun now {config.TunModeItem.EnableTun})");
                return;
            }

            var previous = marker.ServerId;
            var previousExists = !string.IsNullOrEmpty(previous) && await AppManager.Instance.GetProfileItem(previous) is not null;
            var fallback = previousExists ? null : (await ConfigHandler.GetDefaultServer(config))?.IndexId;
            var server = AppUpdateReconnect.ChooseServer(previous, previousExists, fallback);
            if (server is null)
            {
                Logging.SaveLog($"{Tag}: not reconnecting, neither {previous} nor a default server exists");
                return;
            }

            // Пока шли запросы к базе, пользователь мог успеть сам: тогда решение за ним.
            if (Busy(home))
            {
                Logging.SaveLog($"{Tag}: not reconnecting, the user acted first");
                return;
            }
            if (server != config.IndexId && home.Profiles is { } profiles && !await profiles.SetDefaultServer(server, startWhenIdle: false))
            {
                Logging.SaveLog($"{Tag}: not reconnecting, {server} could not become the default server");
                return;
            }

            Logging.SaveLog($"{Tag}: reconnecting to {server} (tun {marker.Tun}) after the restart for {pending.Tag}"
                            + (previousExists ? string.Empty : $", the previous server {previous} is gone"));
            await home.ReconnectAfterUpdate();
        }
        catch (Exception ex)
        {
            Logging.SaveLog(Tag, ex);
        }
    }

    // Подключение уже идёт или есть, или пользователь что-то сделал сам: возвращать нечего.
    private static bool Busy(HomeViewModel home) =>
        s_userActed || home.IsConnected || home.IsConnecting
        || AppManager.Instance.IsRunningCore(ECoreType.Xray) || AppManager.Instance.IsRunningCore(ECoreType.sing_box);
}
