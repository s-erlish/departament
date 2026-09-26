namespace ServiceLib.Common;

public static class ProcUtils
{
    private static readonly string _tag = "ProcUtils";

    /// <summary>
    /// Открывает ссылку, пришедшую извне, — из подписки или от сервера аккаунта, — и только ссылку.
    ///
    /// Кнопки «Поддержка» и Telegram у подписки открывали через оболочку Windows всё, что подписка
    /// прислала в заголовках support-url и profile-web-page-url, а программа работает с правами
    /// администратора. Путь к файлу в сети (<c>\\сервер\папка\p.exe</c>), <c>file:///</c> или чужой
    /// протокол запускались одним нажатием, с правами администратора и без вопроса Windows. Теперь
    /// открываются только веб-ссылки (http, https) и ссылки Telegram (tg); остальное — строка в журнале.
    /// </summary>
    public static bool TryOpenLink(string? url)
    {
        if (!Uri.TryCreate(url?.Trim(), UriKind.Absolute, out var uri)
            || !(uri.Scheme == Uri.UriSchemeHttps || uri.Scheme == Uri.UriSchemeHttp || uri.Scheme == "tg"))
        {
            Logging.SaveLog($"{_tag}: не открыта ссылка, это не веб-адрес: {url}");
            return false;
        }
        //  Открывается разобранный адрес, а не исходная строка: пробелы и прочее в нём уже закодированы,
        //  и оболочка получает ровно то, что проверено.
        return TryProcessStart(uri.AbsoluteUri);
    }

    public static void ProcessStart(string? fileName, string arguments = "")
    {
        _ = ProcessStart(fileName, arguments, null);
    }

    /// <summary>
    /// Launches <paramref name="fileName"/> through the shell and REPORTS whether the OS accepted it.
    ///
    /// <see cref="ProcessStart(string?, string)"/> returns void and swallows every failure, so a caller
    /// that wrapped it in a try/catch was guarding against an exception that can never arrive. On a
    /// machine with nothing registered for the URL — no default browser, no xdg-open, a shell-execute
    /// the OS refuses — the launch failed in silence and the caller went on to promise «завершите
    /// оплату в браузере» over a browser that never opened, then polled for a payment that could not
    /// have been made. This overload gives those callers the answer they were already written to want.
    ///
    /// A launch that HANDS the URL to an already-running browser is a success: <c>Process.Start()</c>
    /// itself returns false there (no new process resource was created), which is why the result is
    /// "the shell accepted it", not that return value.
    /// </summary>
    public static bool TryProcessStart(string? fileName, string arguments = "")
    {
        if (fileName.IsNullOrEmpty())
        {
            return false;
        }
        try
        {
            if (fileName.Contains(' '))
            {
                fileName = fileName.AppendQuotes();
            }
            if (arguments.Contains(' '))
            {
                arguments = arguments.AppendQuotes();
            }

            using Process proc = new()
            {
                StartInfo = new ProcessStartInfo
                {
                    UseShellExecute = true,
                    FileName = fileName,
                    Arguments = arguments,
                    WorkingDirectory = string.Empty
                }
            };
            proc.Start();
            return true;
        }
        catch (Exception ex)
        {
            Logging.SaveLog(_tag, ex);
            return false;
        }
    }

    public static int? ProcessStart(string? fileName, string arguments, string? dir)
    {
        if (fileName.IsNullOrEmpty())
        {
            return null;
        }
        try
        {
            if (fileName.Contains(' '))
            {
                fileName = fileName.AppendQuotes();
            }
            if (arguments.Contains(' '))
            {
                arguments = arguments.AppendQuotes();
            }

            Process proc = new()
            {
                StartInfo = new ProcessStartInfo
                {
                    UseShellExecute = true,
                    FileName = fileName,
                    Arguments = arguments,
                    WorkingDirectory = dir ?? string.Empty
                }
            };
            _ = proc.Start();
            return dir is null ? null : proc.Id;
        }
        catch (Exception ex)
        {
            Logging.SaveLog(_tag, ex);
        }
        return null;
    }

    public static bool RebootAsAdmin(bool blAdmin = true)
    {
        try
        {
            ProcessStartInfo startInfo = new()
            {
                UseShellExecute = true,
                Arguments = Global.RebootAs,
                WorkingDirectory = Utils.StartupPath(),
                FileName = Utils.GetExePath().AppendQuotes(),
                Verb = blAdmin ? "runas" : null,
            };
            return Process.Start(startInfo) != null;
        }
        catch (Exception ex)
        {
            Logging.SaveLog(_tag, ex);
            return false;
        }
    }
}
