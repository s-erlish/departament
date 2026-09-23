namespace AmazTool;

/// <summary>
/// Установщик обновлений departament. Команды:
/// <code>
///   AmazTool upgrade --package &lt;путь к zip&gt; [--pid &lt;PID приложения&gt;]
///   AmazTool rebootas
///   AmazTool &lt;путь к zip в URL-кодировке&gt;      (старая форма вызова апстрима)
/// </code>
/// Окна нет, консоли нет: всё пишется в guiLogs/upgrade.log. Код возврата 0 — сделано, 1 — нет.
/// </summary>
internal static class Program
{
    private static int Main(string[] args)
    {
        Log.Init();
        try
        {
            Log.Write($"AmazTool started: {string.Join(' ', args.Select(a => a.Contains(' ') ? $"\"{a}\"" : a))}");
            if (args.Length == 0)
            {
                Log.Write("no command, nothing to do");
                return 1;
            }

            switch (args[0].ToLowerInvariant())
            {
                case "upgrade":
                    return Upgrade(args[1..]);

                case "rebootas":
                    return Reboot();

                default:
                    // Форма апстрима: весь хвост — один путь в URL-кодировке, без PID.
                    return UpgradeApp.Run(Uri.UnescapeDataString(string.Join(" ", args)), appPid: null) ? 0 : 1;
            }
        }
        catch (Exception ex)
        {
            Log.Write($"fatal: {ex}");
            return 1;
        }
    }

    private static int Upgrade(string[] options)
    {
        string? package = null;
        int? pid = null;
        for (var i = 0; i + 1 < options.Length; i += 2)
        {
            switch (options[i])
            {
                case "--package":
                    package = options[i + 1];
                    break;

                case "--pid" when int.TryParse(options[i + 1], out var value):
                    pid = value;
                    break;
            }
        }
        if (string.IsNullOrWhiteSpace(package))
        {
            Log.Write("upgrade: --package is missing");
            return 1;
        }
        return UpgradeApp.Run(package, pid) ? 0 : 1;
    }

    /// <summary>
    /// Перезапуск после восстановления настроек из копии (на Linux и macOS приложение перезапускает себя
    /// через установщик и сразу выходит). Раньше установщик ждал ровно секунду: если старый экземпляр к
    /// тому времени ещё не вышел, новый упирался в замок единственного экземпляра и молча закрывался.
    /// Теперь ждём выхода по-настоящему.
    /// </summary>
    private static int Reboot()
    {
        Utils.WaitForAppExit(pid: null, TimeSpan.FromSeconds(30));
        return Utils.StartApp() ? 0 : 1;
    }
}
