using System.Runtime.InteropServices;

namespace departament.Desktop.Common;

/// <summary>
/// Не даёт Windows выгрузить на диск память программы, пока окно спрятано в трей.
///
/// Владелец: «когда он в трее висит, то потом начинает зависать, когда открываешь то подлагивает …
/// обычно после запуска всё ок, но спустя время оно начинает лагать». Причина — не в самой
/// программе, а в том, как Windows обращается с долго бездействующим процессом. Спрятанное окно
/// почти ничего не трогает (на стенде — около 15 МБ памяти за 25 секунд в трее), и когда памяти
/// другим программам не хватает, система отдаёт нетронутые страницы им: пишет их в файл подкачки.
/// Первая же отрисовка вернувшегося окна трогает десятки мегабайт (40 МБ за первую секунду, до
/// 80 МБ за 15 секунд), и каждую выгруженную страницу приходится читать с диска обратно. На стенде,
/// где память выгружали перед показом, а подкачка читалась со скоростью обычного жёсткого диска,
/// окно замирало на 3 секунды и сразу ещё на 2; без выгрузки не было ни одной задержки дольше 25 мс.
///
/// ЖЁСТКИЙ МИНИМУМ РАБОЧЕГО НАБОРА (SetProcessWorkingSetSizeEx с QUOTA_LIMITS_HARDWS_MIN_ENABLE)
/// запрещает системе ужимать процесс ниже заданного размера. Минимум — вся память, которую программа
/// держит в этот момент, а не её часть: какие страницы отдать, система выбирает сама, начиная с давно
/// не тронутых, — а окно в трее как раз не трогает всё, что нужно для его отрисовки. Лишней памяти
/// это не занимает: минимум не больше уже занятого, он только не даёт её отобрать, а перед ним
/// ненужное убирает сборка мусора (MainWindow.SettleInTrayAsync). Потолок — 1/16 оперативной памяти
/// и не больше 512 МБ (на машине с 8 ГБ — до 512 МБ, с 4 ГБ — до 256), чтобы на слабой машине
/// система не лишалась свободы.
///
/// Ставится каждый раз, когда окно уходит в трей (<see cref="MainWindow"/>): тогда рабочий набор
/// как раз тот, что нужен окну, — только что нарисованное окно со всем, что оно трогало. Нужна
/// привилегия SeIncreaseWorkingSetPrivilege: она есть у всех пользователей, но в токене выключена,
/// поэтому сначала включается. Любая неудача — строка в журнале, и программа работает как раньше.
/// </summary>
[SupportedOSPlatform("windows")]
internal static partial class WorkingSetGuard
{
    private const long MaxGuardBytes = 512L * 1024 * 1024;
    private const long MinGuardBytes = 64L * 1024 * 1024;

    /// <summary>Разница меньше этой — не повод переставлять минимум и писать об этом в журнал.</summary>
    private const long ChangeStepBytes = 8L * 1024 * 1024;

    private const uint QuotaLimitsHardwsMinEnable = 0x1;
    private const uint QuotaLimitsHardwsMaxDisable = 0x8;
    private const uint TokenAdjustPrivileges = 0x20;
    private const uint TokenQuery = 0x8;
    private const uint SePrivilegeEnabled = 0x2;
    private const int ErrorNotAllAssigned = 1300;

    private static readonly object Gate = new();

    /// <summary>Сколько сейчас защищено, байт; 0 — ещё ни разу.</summary>
    private static long _guarded;

    public static void Apply()
    {
        try
        {
            lock (Gate)
            {
                ApplyCore();
            }
        }
        catch (Exception ex)
        {
            Logging.SaveLog("WorkingSetGuard", ex);
        }
    }

    private static void ApplyCore()
    {
        var totalRam = GC.GetGCMemoryInfo().TotalAvailableMemoryBytes;
        var cap = Math.Min(MaxGuardBytes, totalRam / 16);
        if (cap < MinGuardBytes)
        {
            return;
        }

        var current = Environment.WorkingSet;
        var min = Math.Clamp(current, MinGuardBytes, cap);
        if (Math.Abs(min - _guarded) < ChangeStepBytes)
        {
            return;
        }
        //  Потолок мягкий (MAX_DISABLE): при избытке памяти процесс может его превышать, так что он
        //  лишь не мешает минимуму — ниже минимума потолок быть не может.
        var max = Math.Max(min * 2, 512L * 1024 * 1024);

        if (!EnableIncreaseWorkingSetPrivilege())
        {
            return;
        }
        if (!SetProcessWorkingSetSizeEx(GetCurrentProcess(), (nuint)min, (nuint)max,
                QuotaLimitsHardwsMinEnable | QuotaLimitsHardwsMaxDisable))
        {
            Logging.SaveLog($"WorkingSetGuard: SetProcessWorkingSetSizeEx failed ({Marshal.GetLastPInvokeError()})");
            return;
        }
        _guarded = min;
        Logging.SaveLog($"WorkingSetGuard: память программы не выгружается ниже {min >> 20} МБ (занято {current >> 20} МБ, ОЗУ {totalRam >> 20} МБ)");
    }

    private static bool EnableIncreaseWorkingSetPrivilege()
    {
        if (!OpenProcessToken(GetCurrentProcess(), TokenAdjustPrivileges | TokenQuery, out var token))
        {
            Logging.SaveLog($"WorkingSetGuard: OpenProcessToken failed ({Marshal.GetLastPInvokeError()})");
            return false;
        }
        try
        {
            if (!LookupPrivilegeValueW(null, "SeIncreaseWorkingSetPrivilege", out var luid))
            {
                Logging.SaveLog($"WorkingSetGuard: LookupPrivilegeValue failed ({Marshal.GetLastPInvokeError()})");
                return false;
            }
            var privileges = new TokenPrivileges { PrivilegeCount = 1, Luid = luid, Attributes = SePrivilegeEnabled };
            if (!AdjustTokenPrivileges(token, false, ref privileges, 0, IntPtr.Zero, IntPtr.Zero))
            {
                Logging.SaveLog($"WorkingSetGuard: AdjustTokenPrivileges failed ({Marshal.GetLastPInvokeError()})");
                return false;
            }
            //  AdjustTokenPrivileges отвечает «успех», даже если привилегии у пользователя нет вовсе, —
            //  правду говорит только код последней ошибки.
            if (Marshal.GetLastPInvokeError() == ErrorNotAllAssigned)
            {
                Logging.SaveLog("WorkingSetGuard: SeIncreaseWorkingSetPrivilege is not assigned to this user");
                return false;
            }
            return true;
        }
        finally
        {
            CloseHandle(token);
        }
    }

    #region Win32 API

    [StructLayout(LayoutKind.Sequential)]
    private struct Luid
    {
        public uint LowPart;
        public int HighPart;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct TokenPrivileges
    {
        public uint PrivilegeCount;
        public Luid Luid;
        public uint Attributes;
    }

    [LibraryImport("kernel32.dll")]
    private static partial IntPtr GetCurrentProcess();

    [LibraryImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool SetProcessWorkingSetSizeEx(IntPtr hProcess, nuint dwMinimumWorkingSetSize,
        nuint dwMaximumWorkingSetSize, uint flags);

    [LibraryImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool CloseHandle(IntPtr hObject);

    [LibraryImport("advapi32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool OpenProcessToken(IntPtr processHandle, uint desiredAccess, out IntPtr tokenHandle);

    [LibraryImport("advapi32.dll", SetLastError = true, StringMarshalling = StringMarshalling.Utf16)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool LookupPrivilegeValueW(string? lpSystemName, string lpName, out Luid lpLuid);

    [LibraryImport("advapi32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool AdjustTokenPrivileges(IntPtr tokenHandle, [MarshalAs(UnmanagedType.Bool)] bool disableAllPrivileges,
        ref TokenPrivileges newState, uint bufferLength, IntPtr previousState, IntPtr returnLength);

    #endregion Win32 API
}
