using System.Diagnostics;

namespace AmazTool;

/// <summary>Пути и процессы, общие для обеих команд установщика.</summary>
internal static class Utils
{
    /// <summary>
    /// Имя приложения — и файла, и процесса. Сборка называется <c>departament</c> (AssemblyName в
    /// departament.Desktop.csproj), exe — одиночный файл, поэтому процесс зовётся так же, как файл. Раньше
    /// здесь стояло имя исходной программы: установщик ждал выхода и перезапускал процесс, которого у
    /// departament нет,
    /// то есть не дожидался приложения и после обновления ничего не запускал.
    /// </summary>
    public const string AppName = "departament";

    /// <summary>Каталог установки — там, где лежит сам установщик (пакет кладёт его рядом с exe приложения).</summary>
    public static string AppDir { get; } = Path.TrimEndingDirectorySeparator(Path.GetFullPath(AppContext.BaseDirectory));

    public static string AppExeName => OperatingSystem.IsWindows() ? AppName + ".exe" : AppName;

    public static string AppExePath => Path.Combine(AppDir, AppExeName);

    /// <summary>Свой exe. У NativeAOT <see cref="Environment.ProcessPath"/> — это и есть файл установщика.</summary>
    public static string SelfPath => Environment.ProcessPath
        ?? Path.Combine(AppDir, OperatingSystem.IsWindows() ? "AmazTool.exe" : "AmazTool");

    /// <summary>
    /// Каталог данных пользователя. Тот же выбор, что у <c>Utils.StartupPath()</c> приложения: при
    /// <c>DEPARTAMENT_LOCAL_APPLICATION_DATA=1</c> данные живут в LocalAppData, иначе рядом с exe.
    /// Переменная наследуется от приложения, которое запустило установщик, так что журнал попадает в
    /// тот же guiLogs, что и журнал приложения.
    /// </summary>
    public static string DataDir => Environment.GetEnvironmentVariable("DEPARTAMENT_LOCAL_APPLICATION_DATA") == "1"
        ? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "departament")
        : AppDir;

    /// <summary>Сравнение путей по правилам файловой системы: на Windows регистр не различается.</summary>
    public static StringComparison PathComparison =>
        OperatingSystem.IsWindows() ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal;

    public static bool SamePath(string a, string b) =>
        string.Equals(Path.GetFullPath(a), Path.GetFullPath(b), PathComparison);

    /// <summary>Лежит ли <paramref name="path"/> внутри <paramref name="dir"/> (сам каталог не считается).</summary>
    public static bool IsUnder(string? path, string dir)
    {
        if (string.IsNullOrEmpty(path))
        {
            return false;
        }
        var root = Path.TrimEndingDirectorySeparator(Path.GetFullPath(dir)) + Path.DirectorySeparatorChar;
        return Path.GetFullPath(path).StartsWith(root, PathComparison);
    }

    /// <summary>Путь exe процесса или null: чужой процесс без прав, процесс уже вышел.</summary>
    public static string? ExePathOf(Process p)
    {
        try
        {
            return p.MainModule?.FileName;
        }
        catch
        {
            return null;
        }
    }

    /// <summary>
    /// Ждёт, пока приложение выйдет само: сначала процесс с переданным PID, потом любой «departament»,
    /// запущенный из этого же каталога (старый вызов без PID и второй экземпляр). Штатный выход — остановка
    /// ядра, снятие системного прокси, запись настроек — занимает секунды, поэтому сначала ждём, и только
    /// по истечении <paramref name="timeout"/> завершаем процесс принудительно: иначе его файлы остались бы
    /// заняты и замена упала бы на первом же.
    /// </summary>
    public static void WaitForAppExit(int? pid, TimeSpan timeout)
    {
        if (pid is > 0 && pid != Environment.ProcessId)
        {
            try
            {
                using var p = Process.GetProcessById(pid.Value);
                WaitOrKill(p, timeout, $"pid {pid}");
            }
            catch (ArgumentException)
            {
                Log.Write($"app pid {pid} has already exited");
            }
            catch (Exception ex)
            {
                Log.Write($"waiting for pid {pid} failed: {ex.Message}");
            }
        }

        foreach (var p in Process.GetProcessesByName(AppName))
        {
            using (p)
            {
                if (p.Id == Environment.ProcessId)
                {
                    continue;
                }
                var path = ExePathOf(p);
                // Путь не прочитался — это не повод трогать процесс: departament из другого каталога
                // (вторая установка) обновлению не мешает и не должен закрываться из-за него.
                if (path is null || !SamePath(path, AppExePath))
                {
                    continue;
                }
                WaitOrKill(p, timeout, $"{AppName} pid {p.Id}");
            }
        }
    }

    private static void WaitOrKill(Process p, TimeSpan timeout, string what)
    {
        if (p.HasExited)
        {
            return;
        }
        Log.Write($"waiting for {what} to exit");
        if (p.WaitForExit(timeout))
        {
            Log.Write($"{what} exited");
            return;
        }
        Log.Write($"{what} did not exit in {timeout.TotalSeconds:0} s, terminating it");
        try
        {
            p.Kill(entireProcessTree: true);
            p.WaitForExit(TimeSpan.FromSeconds(10));
        }
        catch (Exception ex)
        {
            // Без прав администратора процесс приложения может быть недоступен. Дальше замена либо
            // пройдёт, либо упрётся в занятый файл и откатится — приложение при этом останется целым.
            Log.Write($"could not terminate {what}: {ex.Message}");
        }
    }

    /// <summary>
    /// Ядра, оставшиеся от приложения: любой процесс, чей exe лежит в bin/ этого каталога. Штатный выход
    /// их останавливает, но если приложение было завершено принудительно (выше или диспетчером задач),
    /// xray.exe или sing-box.exe держат свои файлы, и обновление ядер из пакета упало бы. Процессы из
    /// других каталогов не трогаем.
    /// </summary>
    public static void StopCoresFromBin()
    {
        var binDir = Path.Combine(AppDir, "bin");
        foreach (var p in Process.GetProcesses())
        {
            using (p)
            {
                if (p.Id == Environment.ProcessId)
                {
                    continue;
                }
                var path = ExePathOf(p);
                if (!IsUnder(path, binDir))
                {
                    continue;
                }
                Log.Write($"stopping core still running from bin: {path} (pid {p.Id})");
                try
                {
                    p.Kill(entireProcessTree: true);
                    p.WaitForExit(TimeSpan.FromSeconds(5));
                }
                catch (Exception ex)
                {
                    Log.Write($"could not stop {path}: {ex.Message}");
                }
            }
        }
    }

    /// <summary>
    /// Поднимает приложение. На Windows — через оболочку, как ярлык: манифест приложения требует прав
    /// администратора, и CreateProcess из процесса без них отказал бы (ERROR_ELEVATION_REQUIRED), а
    /// оболочка в таком случае показала бы запрос UAC. Установщик наследует права приложения, которое
    /// его запустило, поэтому обычно запроса нет.
    /// </summary>
    public static bool StartApp()
    {
        try
        {
            var psi = new ProcessStartInfo(AppExePath)
            {
                WorkingDirectory = AppDir,
                UseShellExecute = OperatingSystem.IsWindows(),
            };
            using var p = Process.Start(psi);
            Log.Write($"started {AppExePath}" + (p is null ? string.Empty : $" (pid {p.Id})"));
            return true;
        }
        catch (Exception ex)
        {
            Log.Write($"could not start {AppExePath}: {ex.Message}");
            return false;
        }
    }
}
