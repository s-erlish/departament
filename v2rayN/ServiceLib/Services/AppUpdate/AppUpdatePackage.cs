using System.IO.Compression;

namespace ServiceLib.Services.AppUpdate;

/// <summary>
/// Последняя проверка пакета перед установщиком — аналог проверки «это наше приложение» у departament
/// для Android (там читается имя пакета APK). Сумма уже сошлась, то есть файл такой, каким его выложил
/// выпуск; здесь проверяется, что выложен именно пакет departament по контракту CI:
/// <list type="bullet">
///   <item>все записи лежат под одним верхним каталогом <see cref="AppUpdateChannel.PackageTopFolder"/>;</item>
///   <item>ни одна запись не выходит из него (<c>..</c>, абсолютный путь, диск, поток NTFS);</item>
///   <item>внутри есть exe приложения и установщик: пакет без установщика оставил бы пользователя на этой
///   версии навсегда — следующее обновление ставить было бы нечем;</item>
///   <item>размер после распаковки и число записей в разумных пределах (не «zip-бомба»).</item>
/// </list>
/// Установщик проверяет безопасность путей ещё раз сам: он не доверяет тому, кто его запустил.
/// </summary>
public static class AppUpdatePackage
{
    // Выпуск в распакованном виде — несколько сотен МБ (один exe с ReadyToRun около 170 МБ, ядра, базы):
    // гигабайт — запас на рост, а не предел, в который выпуск упрётся завтра.
    public const long MaxUnpackedBytes = 1L << 30;
    public const int MaxEntries = 20_000;

    /// <param name="zipPath">Скачанный пакет.</param>
    /// <param name="appExe">Имя exe приложения: <c>departament.exe</c> на Windows.</param>
    /// <param name="toolExe">Имя установщика: <c>AmazTool.exe</c> на Windows.</param>
    /// <param name="problem">Почему пакет отвергнут (для журнала).</param>
    public static bool Validate(string zipPath, string appExe, string toolExe, out string? problem)
    {
        problem = null;
        try
        {
            using var archive = ZipFile.OpenRead(zipPath);
            var prefix = AppUpdateChannel.PackageTopFolder + "/";
            var files = new HashSet<string>(StringComparer.Ordinal);
            long unpacked = 0;

            if (archive.Entries.Count > MaxEntries)
            {
                problem = $"too many entries: {archive.Entries.Count}";
                return false;
            }
            foreach (var entry in archive.Entries)
            {
                var name = entry.FullName.Replace('\\', '/');
                if (!name.StartsWith(prefix, StringComparison.Ordinal))
                {
                    problem = $"entry outside {prefix}: {name}";
                    return false;
                }
                var rel = name[prefix.Length..];
                if (rel.Length == 0)
                {
                    continue;
                }
                var segments = rel.TrimEnd('/').Split('/');
                if (segments.Any(s => s is "" or "." or ".." || s.Contains(':')))
                {
                    problem = $"unsafe entry: {name}";
                    return false;
                }
                unpacked += entry.Length;
                if (unpacked > MaxUnpackedBytes)
                {
                    problem = $"unpacks to more than {MaxUnpackedBytes} bytes";
                    return false;
                }
                if (!rel.EndsWith('/') && entry.Length > 0)
                {
                    files.Add(rel);
                }
            }

            foreach (var required in new[] { appExe, toolExe })
            {
                if (!files.Contains(required))
                {
                    problem = $"no {prefix}{required}";
                    return false;
                }
            }
            return true;
        }
        catch (Exception ex) when (ex is InvalidDataException or IOException or UnauthorizedAccessException)
        {
            problem = $"not a readable zip: {ex.Message}";
            return false;
        }
    }
}
