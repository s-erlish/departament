using System.Collections.Concurrent;
using System.Diagnostics;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;

namespace departament.Desktop.Common;

/// <summary>
/// Настоящая иконка программы по пути к её .exe — та же, что показывает Проводник. Нужна списку
/// «Прокси по приложениям»: по одной букве в плитке программы не узнать, по иконке — сразу.
///
/// Только Windows: иконку отдаёт оболочка (SHDefExtractIcon), на остальных системах плитка остаётся
/// с буквой. Иконка берётся ровно того размера в пикселях, каким её нарисует экран: у Windows в .exe
/// лежат отдельно нарисованные 16, 24, 32, 48 и 256, и готовая 24 чётче, чем 48, ужатая вдвое.
///
/// Разбор идёт в фоне, результат (и «иконки нет») запоминается на всё время работы программы:
/// повторный заход на экран и листание страниц назад иконки не пересобирают.
/// </summary>
internal static partial class AppIconLoader
{
    private static readonly ConcurrentDictionary<string, Task<Bitmap?>> Cache = new(StringComparer.OrdinalIgnoreCase);

    public static bool IsSupported => OperatingSystem.IsWindows();

    /// <param name="path">Полный путь к .exe (или к .dll/.ico — оболочке всё равно).</param>
    /// <param name="pixelSize">Сторона иконки в пикселях экрана, то есть уже с учётом масштаба.</param>
    public static Task<Bitmap?> LoadAsync(string path, int pixelSize)
    {
        if (!OperatingSystem.IsWindows() || path.IsNullOrEmpty())
        {
            return Task.FromResult<Bitmap?>(null);
        }
        return LoadWindows(path, Math.Clamp(pixelSize, 16, 256));
    }

    [SupportedOSPlatform("windows")]
    private static Task<Bitmap?> LoadWindows(string path, int size) =>
        Cache.GetOrAdd($"{size}|{path}", _ => Task.Run(() => Extract(path, size)));

    /// <summary>
    /// Путь к .exe запущенного процесса. <c>Process.MainModule</c> открывает процесс с правом читать его
    /// память, а программа, запущенная от администратора, такого права не даёт — у неё не было бы ни
    /// пути, ни иконки. Ограниченного права «узнать имя образа» хватает почти всем процессам.
    /// </summary>
    public static string? ProcessPath(Process process)
    {
        if (OperatingSystem.IsWindows())
        {
            var handle = OpenProcess(ProcessQueryLimitedInformation, false, process.Id);
            if (handle != IntPtr.Zero)
            {
                try
                {
                    var buffer = new char[1024];
                    var length = buffer.Length;
                    if (QueryFullProcessImageNameW(handle, 0, buffer, ref length) && length > 0)
                    {
                        return new string(buffer, 0, length);
                    }
                }
                finally
                {
                    CloseHandle(handle);
                }
            }
        }
        try
        {
            return process.MainModule?.FileName;
        }
        catch
        {
            return null;
        }
    }

    [SupportedOSPlatform("windows")]
    private static Bitmap? Extract(string path, int size)
    {
        try
        {
            if (!File.Exists(path))
            {
                return null;
            }
            // Иконка №0 — та, что у файла в Проводнике. Нет её — S_FALSE, и тогда буква: безликая
            // «иконка программы по умолчанию» ничего не говорит о том, что это за программа.
            var sizes = (uint)(size | (size << 16));
            if (SHDefExtractIconW(path, 0, 0, out var icon, IntPtr.Zero, sizes) != 0 || icon == IntPtr.Zero)
            {
                return null;
            }
            try
            {
                return ToBitmap(icon);
            }
            finally
            {
                DestroyIcon(icon);
            }
        }
        catch (Exception ex)
        {
            Logging.SaveLog("AppIconLoader", ex);
            return null;
        }
    }

    [SupportedOSPlatform("windows")]
    private static Bitmap? ToBitmap(IntPtr icon)
    {
        if (!GetIconInfo(icon, out var info))
        {
            return null;
        }
        try
        {
            // Чёрно-белые иконки из времён Windows 3.x — без цветного слоя. Им место буквы.
            if (info.hbmColor == IntPtr.Zero || GetObjectW(info.hbmColor, Marshal.SizeOf<BITMAP>(), out var bm) == 0)
            {
                return null;
            }
            var w = bm.bmWidth;
            var h = Math.Abs(bm.bmHeight);
            if (w <= 0 || h <= 0)
            {
                return null;
            }
            var px = ReadPixels(info.hbmColor, w, h);
            if (px is null)
            {
                return null;
            }

            // Иконки старого образца (24 бита) без прозрачности в цвете: её несёт отдельная маска,
            // где белое — фон, чёрное — рисунок. Без этого у них был бы чёрный квадрат вокруг.
            var hasAlpha = false;
            for (var i = 3; i < px.Length; i += 4)
            {
                if (px[i] != 0)
                {
                    hasAlpha = true;
                    break;
                }
            }
            if (!hasAlpha)
            {
                var mask = info.hbmMask != IntPtr.Zero ? ReadPixels(info.hbmMask, w, h) : null;
                for (var i = 0; i < px.Length; i += 4)
                {
                    px[i + 3] = mask is not null && mask[i] != 0 ? (byte)0 : (byte)255;
                }
            }

            // Альфа у иконок Windows прямая; рендер ждёт умноженную на неё — пересчитываем сами,
            // а не доверяем это бэкенду.
            for (var i = 0; i < px.Length; i += 4)
            {
                var a = px[i + 3];
                if (a == 255)
                {
                    continue;
                }
                px[i] = (byte)(px[i] * a / 255);
                px[i + 1] = (byte)(px[i + 1] * a / 255);
                px[i + 2] = (byte)(px[i + 2] * a / 255);
            }

            var bitmap = new WriteableBitmap(new PixelSize(w, h), new Vector(96, 96), PixelFormat.Bgra8888, AlphaFormat.Premul);
            using (var fb = bitmap.Lock())
            {
                for (var y = 0; y < h; y++)
                {
                    Marshal.Copy(px, y * w * 4, fb.Address + (y * fb.RowBytes), w * 4);
                }
            }
            return bitmap;
        }
        finally
        {
            if (info.hbmColor != IntPtr.Zero)
            {
                DeleteObject(info.hbmColor);
            }
            if (info.hbmMask != IntPtr.Zero)
            {
                DeleteObject(info.hbmMask);
            }
        }
    }

    /// <summary>Пиксели растра сверху вниз, 32 бита BGRA.</summary>
    [SupportedOSPlatform("windows")]
    private static byte[]? ReadPixels(IntPtr hbm, int w, int h)
    {
        var bmi = new BITMAPINFO
        {
            bmiHeader = new BITMAPINFOHEADER
            {
                biSize = Marshal.SizeOf<BITMAPINFOHEADER>(),
                biWidth = w,
                biHeight = -h, // минус — строки сверху вниз
                biPlanes = 1,
                biBitCount = 32,
                biCompression = 0, // BI_RGB
            },
        };
        var buffer = new byte[w * h * 4];
        var dc = CreateCompatibleDC(IntPtr.Zero);
        if (dc == IntPtr.Zero)
        {
            return null;
        }
        try
        {
            return GetDIBits(dc, hbm, 0, (uint)h, buffer, ref bmi, 0) == h ? buffer : null;
        }
        finally
        {
            DeleteDC(dc);
        }
    }

    #region Win32 API

    private const uint ProcessQueryLimitedInformation = 0x1000;

    [LibraryImport("kernel32.dll")]
    private static partial IntPtr OpenProcess(uint dwDesiredAccess, [MarshalAs(UnmanagedType.Bool)] bool bInheritHandle, int dwProcessId);

    [LibraryImport("kernel32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool CloseHandle(IntPtr hObject);

    [LibraryImport("kernel32.dll", StringMarshalling = StringMarshalling.Utf16)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool QueryFullProcessImageNameW(IntPtr hProcess, uint dwFlags, [Out] char[] lpExeName, ref int lpdwSize);

    [LibraryImport("shell32.dll", StringMarshalling = StringMarshalling.Utf16)]
    private static partial int SHDefExtractIconW(string pszIconFile, int iIndex, uint uFlags,
        out IntPtr phiconLarge, IntPtr phiconSmall, uint nIconSize);

    [LibraryImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool GetIconInfo(IntPtr hIcon, out ICONINFO piconinfo);

    [LibraryImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool DestroyIcon(IntPtr hIcon);

    [LibraryImport("gdi32.dll")]
    private static partial int GetObjectW(IntPtr h, int c, out BITMAP pv);

    [LibraryImport("gdi32.dll")]
    private static partial IntPtr CreateCompatibleDC(IntPtr hdc);

    [LibraryImport("gdi32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool DeleteDC(IntPtr hdc);

    [LibraryImport("gdi32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool DeleteObject(IntPtr hObject);

    [LibraryImport("gdi32.dll")]
    private static partial int GetDIBits(IntPtr hdc, IntPtr hbm, uint start, uint cLines,
        byte[] lpvBits, ref BITMAPINFO lpbmi, uint usage);

    [StructLayout(LayoutKind.Sequential)]
    private struct ICONINFO
    {
        public int fIcon;
        public int xHotspot;
        public int yHotspot;
        public IntPtr hbmMask;
        public IntPtr hbmColor;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct BITMAP
    {
        public int bmType;
        public int bmWidth;
        public int bmHeight;
        public int bmWidthBytes;
        public ushort bmPlanes;
        public ushort bmBitsPixel;
        public IntPtr bmBits;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct BITMAPINFOHEADER
    {
        public int biSize;
        public int biWidth;
        public int biHeight;
        public short biPlanes;
        public short biBitCount;
        public int biCompression;
        public int biSizeImage;
        public int biXPelsPerMeter;
        public int biYPelsPerMeter;
        public int biClrUsed;
        public int biClrImportant;
    }

    /// <summary>Заголовок и место под палитру: GetDIBits пишет её за заголовком, если сочтёт нужной,
    /// и без запаса затёр бы чужую память.</summary>
    [StructLayout(LayoutKind.Sequential)]
    private struct BITMAPINFO
    {
        public BITMAPINFOHEADER bmiHeader;
        public ColorTable bmiColors;
    }

    [InlineArray(256)]
    private struct ColorTable
    {
        private uint _element0;
    }

    #endregion Win32 API
}
