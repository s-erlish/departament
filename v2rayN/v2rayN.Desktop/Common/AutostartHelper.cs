using System.Text.RegularExpressions;
using Microsoft.Win32;

namespace v2rayN.Desktop.Common;

/// <summary>
/// Запуск при загрузке (Windows).
///
/// ПОЧЕМУ НЕ ПРОСТО Run-КЛЮЧ. departament манифестирован как <c>requireAdministrator</c>
/// (app.manifest: TUN создаёт wintun-адаптер, нужны права администратора). Windows НЕ поднимает
/// elevated-приложение из <c>HKCU\Software\Microsoft\Windows\CurrentVersion\Run</c> при входе в
/// систему: автозагрузку оболочка выполняет под отфильтрованным (обычным) токеном, запуск падает с
/// ERROR_ELEVATION_REQUIRED, а UAC-запрос на старте не показывается — запись просто молча
/// пропускается. Именно поэтому раньше тумблер честно сохранялся, значение в реестре появлялось, а
/// приложение при входе не запускалось.
///
/// Единственный поддерживаемый способ поднять elevated-приложение при входе — ЗАДАЧА ПЛАНИРОВЩИКА
/// с триггером «при входе в систему» и «наивысшими правами». Её и регистрируем, переиспользуя уже
/// существующую <see cref="AutoStartupHandler.AutoStartTaskService"/> — второй механизм рядом не
/// заводим. Run-ключ остаётся запасным путём для НЕ-elevated запуска (например, из-под отладчика
/// или сборки без манифеста), и ровно один из двух путей живёт в системе одновременно.
///
/// Всё Windows-огорожено (<see cref="Utils.IsWindows"/>): на Linux/macOS автозапуск живёт в
/// файлах autostart/LaunchAgent (см. <c>AutoStartupHandler</c>), а этот хелпер там — no-op.
/// </summary>
public static class AutostartHelper
{
    private static readonly string _tag = "AutostartHelper";

    // Человекочитаемое имя (по просьбе владельца — «departament», а не хэш): под ним запись видна и в
    // «Планировщике заданий», и в списке автозагрузки диспетчера задач.
    private const string Name = "departament";
    private const string RunKeyPath = @"Software\Microsoft\Windows\CurrentVersion\Run";

    // Описание задачи — то, что владелец увидит в «Планировщике заданий» (там же, где имя).
    private const string TaskDescription = "departament — запуск при входе в систему";

    /// <summary>Включить автозапуск для ТЕКУЩЕГО exe. Под администратором — задача планировщика
    /// («наивысшие права» + вход в систему); иначе — Run-значение. Лишний механизм всегда снимается,
    /// чтобы регистрация была ровно одна. Идемпотентно: повторный вызов перезаписывает путь.
    /// Возвращает false, если платформа не Windows, путь к exe неизвестен или регистрация не удалась.</summary>
    public static bool Set()
    {
        if (!Utils.IsWindows())
        {
            return false;
        }

        var exe = Utils.GetExePath();
        if (exe.IsNullOrEmpty())
        {
            return false;
        }

        try
        {
            if (Utils.IsAdministrator())
            {
                // AutoStartTaskService сама кладёт путь в кавычки и ставит рабочий каталог рядом с exe,
                // так что пробелы в каталоге установки безопасны.
                AutoStartupHandler.AutoStartTaskService(Name, exe, TaskDescription);
                TryCleanup(RemoveRunValue);
            }
            else
            {
                // Кавычки вокруг пути — на случай пробелов в каталоге установки.
                using var key = Registry.CurrentUser.CreateSubKey(RunKeyPath);
                key.SetValue(Name, exe.AppendQuotes());
                TryCleanup(RemoveTask);
            }
            return true;
        }
        catch (Exception ex)
        {
            Logging.SaveLog(_tag, ex);
            return false;
        }
    }

    /// <summary>Выключить автозапуск: снимает ОБА механизма (задачу и Run-значение), включая записи,
    /// оставшиеся от прежних сборок.</summary>
    public static bool Remove()
    {
        if (!Utils.IsWindows())
        {
            return false;
        }

        try
        {
            RemoveRunValue();
            RemoveTask();
            return true;
        }
        catch (Exception ex)
        {
            Logging.SaveLog(_tag, ex);
            return false;
        }
    }

    /// <summary>Есть ли в системе НАША регистрация автозапуска — любым из двух механизмов.
    /// Это фактическое состояние ОС, а не запомненный флаг конфига.</summary>
    public static bool IsEnabled()
    {
        if (!Utils.IsWindows())
        {
            return false;
        }

        try
        {
            return ReadTaskExecPath() is not null || ReadRunValue() is not null;
        }
        catch (Exception ex)
        {
            Logging.SaveLog(_tag, ex);
            return false;
        }
    }

    /// <summary>Актуальна ли регистрация: нужный для текущих прав механизм И ровно текущий путь к exe.
    /// False, когда автозапуска нет, когда он остался в старом (не выполняемом при UAC) виде Run-ключа,
    /// или когда обновление переставило exe и записан устаревший путь. Вызывающий в этом случае
    /// перерегистрирует через <see cref="Set"/>.</summary>
    public static bool IsCurrent()
    {
        if (!Utils.IsWindows())
        {
            return false;
        }

        var exe = Utils.GetExePath();
        if (exe.IsNullOrEmpty())
        {
            return false;
        }

        try
        {
            return Utils.IsAdministrator()
                ? SamePath(ReadTaskExecPath(), exe) && ReadRunValue() is null
                : SamePath(ReadRunValue(), exe) && ReadTaskExecPath() is null;
        }
        catch (Exception ex)
        {
            Logging.SaveLog(_tag, ex);
            return false;
        }
    }

    /// <summary>Синхронизировать систему с флагом: enabled → Set(), иначе Remove().</summary>
    public static bool Apply(bool enabled) => enabled ? Set() : Remove();

    #region Механизмы по отдельности

    /// <summary>Снятие ЛИШНЕГО механизма — уборка, а не сама регистрация: её сбой (нет прав на чужую
    /// запись, недоступна служба планировщика) не должен превращать успешную регистрацию в неудачу.
    /// Следующий запуск попробует убрать остаток снова — см. <see cref="IsCurrent"/>.</summary>
    [SupportedOSPlatform("windows")]
    private static void TryCleanup(Action cleanup)
    {
        try
        {
            cleanup();
        }
        catch (Exception ex)
        {
            Logging.SaveLog(_tag, ex);
        }
    }

    [SupportedOSPlatform("windows")]
    private static void RemoveRunValue()
    {
        using var key = Registry.CurrentUser.OpenSubKey(RunKeyPath, writable: true);
        key?.DeleteValue(Name, throwOnMissingValue: false);
    }

    [SupportedOSPlatform("windows")]
    private static void RemoveTask()
    {
        // Пустой fileName — режим удаления в AutoStartTaskService (снимает задачи по имени).
        AutoStartupHandler.AutoStartTaskService(Name, "", "");
    }

    /// <summary>Путь из Run-значения, или null, если значения нет.</summary>
    [SupportedOSPlatform("windows")]
    private static string? ReadRunValue()
    {
        using var key = Registry.CurrentUser.OpenSubKey(RunKeyPath);
        var value = key?.GetValue(Name) as string;
        return value.IsNullOrEmpty() ? null : value;
    }

    /// <summary>Путь из задачи планировщика; пустая строка — задача есть, но без ExecAction;
    /// null — задачи нет.</summary>
    [SupportedOSPlatform("windows")]
    private static string? ReadTaskExecPath()
    {
        using var taskService = new Microsoft.Win32.TaskScheduler.TaskService();
        foreach (var task in taskService.RootFolder.GetTasks(new Regex($"^{Name}$")))
        {
            foreach (var action in task.Definition.Actions)
            {
                if (action is Microsoft.Win32.TaskScheduler.ExecAction exec)
                {
                    return exec.Path ?? string.Empty;
                }
            }
            return string.Empty;
        }
        return null;
    }

    /// <summary>Сравнение записанного пути с текущим exe. Записанный путь хранится в кавычках
    /// (и в задаче, и в Run-значении), поэтому кавычки снимаем перед сравнением.</summary>
    private static bool SamePath(string? registered, string exe) =>
        registered is not null
        && string.Equals(registered.Trim().Trim('"'), exe, StringComparison.OrdinalIgnoreCase);

    #endregion Механизмы по отдельности
}
