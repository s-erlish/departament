namespace AmazTool;

/// <summary>
/// Журнал установщика: guiLogs/upgrade.log рядом с журналом приложения. Окна у установщика нет, и
/// журнал — единственное место, где видно, что он сделал: дождался ли приложения, что заменил, почему
/// откатился. Строки на английском, как и остальные технические журналы приложения: их читают при
/// разборе обращений, а не пользователь.
/// </summary>
internal static class Log
{
    // Больше не нужно: одно обновление — несколько сотен строк, а старые попытки ценны только последние.
    private const long MaxBytes = 512 * 1024;

    private static string? _path;

    public static string? FilePath => _path;

    public static void Init()
    {
        try
        {
            var dir = Path.Combine(Utils.DataDir, "guiLogs");
            Directory.CreateDirectory(dir);
            _path = Path.Combine(dir, "upgrade.log");
            var info = new FileInfo(_path);
            if (info.Exists && info.Length > MaxBytes)
            {
                File.Move(_path, Path.Combine(dir, "upgrade.old.log"), overwrite: true);
            }
        }
        catch
        {
            // Без журнала установщик всё равно обязан отработать: запись в него — не условие обновления.
            _path = null;
        }
    }

    public static void Write(string line)
    {
        if (_path is null)
        {
            return;
        }
        try
        {
            File.AppendAllText(_path, $"{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff} {line}{Environment.NewLine}");
        }
        catch
        {
        }
    }
}
