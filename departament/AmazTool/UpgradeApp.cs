using System.IO.Compression;

namespace AmazTool;

/// <summary>
/// Замена файлов приложения содержимым пакета обновления.
///
/// <para><b>Порядок.</b> Дождаться выхода приложения (и добить ядра, если их бросили) → разложить пакет
/// во временный каталог <c>.update/staging</c> рядом с exe → по одному файлу отодвинуть старый в
/// <c>.update/backup</c> и поставить на его место новый → запустить приложение. Раскладка идёт в тот же
/// каталог установки, а не в %TEMP%: перенос файла внутри одного тома — переименование, оно атомарно и
/// мгновенно, а копирование между томами — нет.</para>
///
/// <para><b>Что будет при сбое.</b> Пока пакет раскладывается, установка не тронута: битый архив, нехватка
/// места или любая ошибка — и приложение запускается прежним. Если сбой случился посреди замены (файл
/// занят и не освободился за все попытки), всё уже заменённое возвращается из <c>.update/backup</c> в
/// обратном порядке. Если замену оборвало снаружи (выключили питание), при следующем запуске установщик
/// видит метку <c>swap-in-progress</c> и сначала возвращает отодвинутые файлы.</para>
///
/// <para><b>Что не трогается никогда.</b> Данные пользователя: guiConfigs, guiLogs, binConfigs, guiTemps
/// (и guiBackups, guiFonts). Запись пакета в такой каталог пропускается, даже если она там окажется.
/// Всё остальное, bin/ целиком, перезаписывается: раньше установщик пропускал уже существующие файлы в
/// bin/, и закреплённые версии ядер из нового выпуска не доезжали бы до пользователя никогда.</para>
/// </summary>
internal static class UpgradeApp
{
    private const string WorkDirName = ".update";

    private static readonly string[] UserDataDirs =
        ["guiConfigs", "guiLogs", "binConfigs", "guiTemps", "guiBackups", "guiFonts", WorkDirName];

    // Штатный выход приложения укладывается в пару секунд; 30 — запас на медленный диск и остановку ядра.
    private static readonly TimeSpan AppExitTimeout = TimeSpan.FromSeconds(30);

    private static string WorkDir => Path.Combine(Utils.AppDir, WorkDirName);
    private static string StagingDir => Path.Combine(WorkDir, "staging");
    private static string BackupDir => Path.Combine(WorkDir, "backup");
    private static string SwapMarker => Path.Combine(WorkDir, "swap-in-progress");

    /// <summary>
    /// Куда отодвигается сам установщик, если пакет несёт его новую версию. Запущенный exe на Windows нельзя
    /// ни удалить, ни перезаписать, но можно переименовать. Этот файл удаляет новая версия приложения при
    /// первом запуске (AppUpdateManager.EnsureReconciled), а если не смогла — следующий запуск установщика.
    /// </summary>
    private static string SelfTmp => Utils.SelfPath + ".tmp";

    /// <returns>true, если новая версия установлена. Приложение запускается в обоих случаях.</returns>
    public static bool Run(string packagePath, int? appPid)
    {
        Log.Write($"upgrade: package {packagePath}, app {Utils.AppExePath}");

        Utils.WaitForAppExit(appPid, AppExitTimeout);
        Utils.StopCoresFromBin();

        if (!RecoverInterruptedSwap())
        {
            Log.Write("upgrade not installed: the previous interrupted upgrade is not undone yet");
            Utils.StartApp();
            return false;
        }
        TryDelete(SelfTmp);

        var installed = false;
        try
        {
            if (!File.Exists(packagePath))
            {
                Log.Write("package not found, nothing to install");
            }
            else
            {
                TryDeleteDir(WorkDir);
                Directory.CreateDirectory(StagingDir);
                var files = Extract(packagePath);
                installed = files is not null && Swap(files);
            }
        }
        catch (Exception ex)
        {
            // Сюда приходят сбои РАСКЛАДКИ (архив не читается, нет места): замена ещё не начиналась.
            Log.Write($"upgrade failed before any file was replaced: {ex.Message}");
        }
        finally
        {
            TryDeleteDir(StagingDir);
            // Рабочий каталог остаётся, только если откат не вернул всё: метка и backup нужны следующему
            // запуску установщика. В остальных случаях от .update в каталоге приложения не остаётся ничего.
            if (!File.Exists(SwapMarker))
            {
                TryDeleteDir(WorkDir);
            }
        }

        Log.Write(installed ? "upgrade installed" : "upgrade not installed, the previous version stays");
        Utils.StartApp();
        return installed;
    }

    /// <summary>
    /// Раскладывает пакет в <see cref="StagingDir"/>. Пакет — один верхний каталог
    /// (<c>departament-windows-x64/</c>) с приложением внутри; его имя здесь не проверяется, это делает
    /// приложение до передачи пакета. Здесь проверяется то, без чего раскладка опасна: запись не выходит из
    /// каталога (<c>..</c>, абсолютный путь, диск, поток NTFS), верхний каталог один, exe приложения есть.
    /// </summary>
    /// <returns>Относительные пути разложенных файлов или null, если пакет не годится.</returns>
    private static List<string>? Extract(string packagePath)
    {
        var stagingRoot = Path.TrimEndingDirectorySeparator(Path.GetFullPath(StagingDir)) + Path.DirectorySeparatorChar;
        var files = new List<string>();
        string? top = null;

        using var archive = ZipFile.OpenRead(packagePath);
        foreach (var entry in archive.Entries)
        {
            // Архиватор Windows PowerShell 5 пишет «\» вместо «/»: сводим к одному разделителю.
            var name = entry.FullName.Replace('\\', '/');
            var slash = name.IndexOf('/');
            if (slash <= 0)
            {
                Log.Write($"rejected package: entry outside the top folder: {name}");
                return null;
            }
            var first = name[..slash];
            top ??= first;
            if (!string.Equals(first, top, StringComparison.Ordinal))
            {
                Log.Write($"rejected package: second top folder: {name}");
                return null;
            }

            var rel = name[(slash + 1)..];
            if (rel.Length == 0)
            {
                continue;
            }
            var isDir = rel.EndsWith('/');
            var segments = rel.TrimEnd('/').Split('/');
            if (segments.Any(s => s is "" or "." or ".." || s.Contains(':')))
            {
                Log.Write($"rejected package: unsafe entry: {name}");
                return null;
            }
            if (UserDataDirs.Contains(segments[0], StringComparer.OrdinalIgnoreCase))
            {
                Log.Write($"skipped (user data is never overwritten): {name}");
                continue;
            }

            var dest = Path.GetFullPath(Path.Combine(StagingDir, Path.Combine(segments)));
            if (!dest.StartsWith(stagingRoot, Utils.PathComparison))
            {
                Log.Write($"rejected package: entry escapes the install folder: {name}");
                return null;
            }
            if (isDir)
            {
                Directory.CreateDirectory(dest);
                continue;
            }
            Directory.CreateDirectory(Path.GetDirectoryName(dest)!);
            entry.ExtractToFile(dest, overwrite: true);
            files.Add(Path.Combine(segments));
        }

        var exe = Path.Combine(StagingDir, Utils.AppExeName);
        if (top is null || !File.Exists(exe) || new FileInfo(exe).Length == 0)
        {
            Log.Write($"rejected package: no {Utils.AppExeName} in it");
            return null;
        }

        SetUnixModes(files);
        Log.Write($"staged {files.Count} files from {top}/");
        return files;
    }

    /// <summary>
    /// Ставит разложенные файлы на место. Каждый старый файл сначала отодвигается (переименованием), потом
    /// на его место встаёт новый; журнал этих шагов — то, по чему делается откат. exe приложения ставится
    /// ПОСЛЕДНИМ: пока замена не дошла до конца, на месте остаётся рабочая старая версия запускаемого файла.
    /// </summary>
    private static bool Swap(List<string> files)
    {
        files.Sort((a, b) => IsAppExe(a).CompareTo(IsAppExe(b)));
        File.WriteAllText(SwapMarker, DateTime.Now.ToString("O"));

        var journal = new List<(string Target, string? Saved)>();
        foreach (var rel in files)
        {
            var source = Path.Combine(StagingDir, rel);
            var target = Path.Combine(Utils.AppDir, rel);
            try
            {
                if (Directory.Exists(target))
                {
                    throw new IOException($"a folder is in the way of {target}");
                }
                Directory.CreateDirectory(Path.GetDirectoryName(target)!);

                string? saved = null;
                if (File.Exists(target))
                {
                    saved = Utils.SamePath(target, Utils.SelfPath) ? SelfTmp : Path.Combine(BackupDir, rel);
                    Directory.CreateDirectory(Path.GetDirectoryName(saved)!);
                    Retry(() => File.Move(target, saved, overwrite: true), $"move aside {target}");
                }
                // В журнал — ДО установки нового файла: если она упадёт, откат вернёт отодвинутый.
                journal.Add((target, saved));
                Retry(() => File.Move(source, target), $"install {target}");
            }
            catch (Exception ex)
            {
                Log.Write($"replacing files failed: {ex.Message}");
                if (Rollback(journal))
                {
                    TryDelete(SwapMarker);
                }
                return false;
            }
        }

        // Метка снимается первой: без неё следующий запуск не станет «восстанавливать» из backup, даже
        // если удалить сам backup сейчас не получится.
        TryDelete(SwapMarker);
        TryDeleteDir(BackupDir);
        Log.Write($"replaced {files.Count} files");
        return true;
    }

    /// <returns>
    /// true, если на место вернулось всё. false — какой-то старый файл вернуть не удалось: тогда backup и
    /// метка остаются, и следующий запуск установщика первым делом вернёт их (<see cref="RecoverInterruptedSwap"/>).
    /// Раньше backup удалялся и в этом случае, то есть вместе с единственной копией невозвращённого файла.
    /// </returns>
    private static bool Rollback(List<(string Target, string? Saved)> journal)
    {
        Log.Write($"rolling back {journal.Count} files");
        var complete = true;
        for (var i = journal.Count - 1; i >= 0; i--)
        {
            var (target, saved) = journal[i];
            try
            {
                // Старый файл всегда отодвигается до установки нового, поэтому на месте может быть только новый.
                if (File.Exists(target))
                {
                    Retry(() => File.Delete(target), $"remove {target}");
                }
                if (saved is not null && File.Exists(saved))
                {
                    Retry(() => File.Move(saved, target), $"restore {target}");
                }
            }
            catch (Exception ex)
            {
                complete = false;
                Log.Write($"ROLLBACK INCOMPLETE for {target}: {ex.Message}");
            }
        }
        if (!complete)
        {
            Log.Write($"backup kept in {BackupDir}, the next installer run restores it first");
            return false;
        }
        TryDeleteDir(BackupDir);
        Log.Write("rollback complete, the previous version is back in place");
        return true;
    }

    /// <summary>
    /// Прошлую замену оборвали снаружи: в каталоге смесь версий, а старые файлы лежат в backup. Возвращаем
    /// их на место — это снова целая прошлая версия (новые файлы, которых в ней не было, ей не мешают).
    /// Без метки backup — просто остаток удачной замены, который не удалось стереть; он удаляется.
    /// </summary>
    /// <returns>
    /// false, если вернуть удалось не всё. Тогда метка и backup остаются (в них единственная копия
    /// невозвращённых файлов), а новая установка не начинается: она стёрла бы рабочий каталог вместе с ними.
    /// </returns>
    private static bool RecoverInterruptedSwap()
    {
        if (File.Exists(SwapMarker) && Directory.Exists(BackupDir))
        {
            Log.Write("the previous upgrade was interrupted mid-way, restoring its backup first");
            var complete = true;
            foreach (var saved in Directory.EnumerateFiles(BackupDir, "*", SearchOption.AllDirectories).ToList())
            {
                var target = Path.Combine(Utils.AppDir, Path.GetRelativePath(BackupDir, saved));
                try
                {
                    Directory.CreateDirectory(Path.GetDirectoryName(target)!);
                    Retry(() => File.Move(saved, target, overwrite: true), $"restore {target}");
                }
                catch (Exception ex)
                {
                    complete = false;
                    Log.Write($"could not restore {target}: {ex.Message}");
                }
            }
            if (!complete)
            {
                Log.Write($"backup kept in {BackupDir} for the next installer run");
                return false;
            }
            Log.Write("the interrupted upgrade is undone");
        }
        TryDeleteDir(WorkDir);
        return true;
    }

    /// <summary>
    /// На Linux и macOS — одни и те же права при любом архиве: 0755 у exe приложения, установщика и ядер в
    /// bin/ (у ядер нет расширения), 0644 у остального. Архив из Windows прав не несёт, и без этого ядро не
    /// запустилось бы; архив, собранный иначе, несёт какие угодно: e2e на Linux поймал 0600 у библиотек
    /// из архива Python (их не прочёл бы другой пользователь той же машины). На Windows ничего не делает.
    /// </summary>
    private static void SetUnixModes(List<string> files)
    {
        if (OperatingSystem.IsWindows())
        {
            return;
        }
        const UnixFileMode Plain = UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.GroupRead | UnixFileMode.OtherRead;
        const UnixFileMode Executable = Plain | UnixFileMode.UserExecute | UnixFileMode.GroupExecute | UnixFileMode.OtherExecute;
        var bin = "bin" + Path.DirectorySeparatorChar;
        foreach (var rel in files)
        {
            var name = Path.GetFileName(rel);
            var executable = name is Utils.AppName or "AmazTool"
                || (rel.StartsWith(bin, StringComparison.Ordinal) && !Path.HasExtension(name));
            File.SetUnixFileMode(Path.Combine(StagingDir, rel), executable ? Executable : Plain);
        }
    }

    private static bool IsAppExe(string rel) => string.Equals(rel, Utils.AppExeName, Utils.PathComparison);

    /// <summary>
    /// Файл бывает занят несколько секунд после выхода процесса: антивирус проверяет новый exe, ядро ещё
    /// отпускает библиотеку. Десять попыток с растущей паузой — около 14 с на файл в худшем случае.
    /// </summary>
    private static void Retry(Action action, string what)
    {
        const int attempts = 10;
        for (var i = 1; ; i++)
        {
            try
            {
                action();
                return;
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException && i < attempts)
            {
                Log.Write($"{what}: {ex.Message} (attempt {i}/{attempts})");
                Thread.Sleep(250 * i);
            }
        }
    }

    private static void TryDelete(string path)
    {
        try
        {
            if (File.Exists(path))
            {
                File.Delete(path);
            }
        }
        catch (Exception ex)
        {
            Log.Write($"could not delete {path}: {ex.Message}");
        }
    }

    private static void TryDeleteDir(string path)
    {
        try
        {
            if (Directory.Exists(path))
            {
                Directory.Delete(path, recursive: true);
            }
        }
        catch (Exception ex)
        {
            Log.Write($"could not delete {path}: {ex.Message}");
        }
    }
}
