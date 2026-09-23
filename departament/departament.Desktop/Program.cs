using System.IO;
using System.IO.Pipes;
using System.Text;
using System.Threading;
using departament.Desktop.Common;
using departament.Desktop.Manager;

namespace departament.Desktop;

internal class Program
{
    public static EventWaitHandle ProgramStarted;

    // Initialization code. Don't use any Avalonia, third-party APIs or any
    // SynchronizationContext-reliant code before AppMain is called: things aren't initialized
    // yet and stuff might break.
    [STAThread]
    public static void Main(string[] args)
    {
        if (OnStartup(args) == false)
        {
            Environment.Exit(0);
            return;
        }

        BuildAvaloniaApp()
            .StartWithClassicDesktopLifetime(args);
    }

    //  «departament.exe --quit» — просьба к уже запущенной копии штатно выйти: так установщик и
    //  деинсталлятор закрывают программу перед заменой файлов. Штатно — значит с остановкой ядра и
    //  снятием системного прокси; убитый процесс оставил бы прокси на мёртвом порту, и интернет лёг
    //  бы до следующего запуска. Сама по себе эта команда программу НЕ запускает.
    public const string QuitArg = "--quit";

    private static bool OnStartup(string[]? Args)
    {
        var args = Args ?? [];
        // Browser→app SSO return (departamentvpn://auth?code=…): the OS launches us with the URL as an arg.
        var authUrl = ExtractAuthUrl(args);
        var quit = args.Any(a => string.Equals(a, QuitArg, StringComparison.OrdinalIgnoreCase));

        if (Utils.IsWindows())
        {
            var exePathKey = Utils.GetMd5(Utils.GetExePath());
            var rebootas = args.Any(t => t == Global.RebootAs);
            ProgramStarted = new EventWaitHandle(false, EventResetMode.AutoReset, exePathKey, out var bCreatedNew);
            if (!rebootas && !bCreatedNew)
            {
                if (quit)
                {
                    // Окно не поднимаем (ProgramStarted не взводим): просьба только о выходе.
                    AppHandoffChannel.ForwardToRunningInstance(AppHandoffChannel.QuitMessage);
                    return false;
                }
                // A live instance already holds the single-instance gate. Previously the second instance
                // simply exited, dropping its args — so a scheme callback could never reach the running
                // app. Now, if we were launched to deliver an auth URL, forward it over the named pipe
                // keyed off this exe, THEN exit; the running instance receives + completes the login.
                if (authUrl != null)
                {
                    AppHandoffChannel.ForwardToRunningInstance(authUrl);
                }
                ProgramStarted.Set();
                return false;
            }
        }
        else
        {
            _ = new Mutex(true, "departament", out var bOnlyOneInstance);
            if (!bOnlyOneInstance)
            {
                if (quit)
                {
                    AppHandoffChannel.ForwardToRunningInstance(AppHandoffChannel.QuitMessage);
                    return false;
                }
                if (authUrl != null)
                {
                    AppHandoffChannel.ForwardToRunningInstance(authUrl);
                }
                return false;
            }
        }

        // Выходить некому: программа не запущена, и запускать её ради выхода не нужно.
        if (quit)
        {
            return false;
        }

        // This is the primary (surviving) instance. Start the pipe receiver so any later scheme callback
        // forwarded by a second instance routes into the running app. If WE were the one launched by the
        // scheme (cold start), buffer the URL until App wires its handler.
        AppHandoffChannel.StartServer();
        if (authUrl != null)
        {
            AppHandoffChannel.Receive(authUrl);
        }

        if (!AppManager.Instance.InitApp())
        {
            return false;
        }

        AppManager.Instance.WindowDialog = new WindowDialog();
        return true;
    }

    private static string? ExtractAuthUrl(string[] args)
        => args.FirstOrDefault(a => a != null && a.StartsWith(AppHandoffChannel.SchemePrefix, StringComparison.OrdinalIgnoreCase));

    // Avalonia configuration, don't remove; also used by visual designer.
    public static AppBuilder BuildAvaloniaApp()
    {
        var builder = AppBuilder.Configure<App>()
           .UsePlatformDetect()
           //.WithInterFont()
           .WithFontByDefault()
#if DEBUG
           .WithDeveloperTools()
#endif
           .LogToTrace()
           .UseReactiveUI(_ => { });

        // Linux без GL рисует окно программно, в кадровый буфер. По умолчанию Avalonia выбрасывает этот
        // буфер после каждого кадра, и следующий кадр заводит новый (2 МБ на окно 900×600, около 8 МБ
        // на весь экран), очищает его и копирует туда весь кадр из своего промежуточного слоя. Каждый
        // такой буфер Avalonia регистрирует как давление на память (GC.AddMemoryPressure), и среда
        // отвечала полными сборками мусора: на каждую смену вкладки их было две. Сохранённый буфер
        // перерисовывается на месте и только там, где что-то изменилось. Цена — этот один буфер в
        // памяти, пока окно открыто. С GL и на других системах настройка ни на что не влияет.
        if (OperatingSystem.IsLinux())
        {
            builder = builder.With(new X11PlatformOptions { UseRetainedFramebuffer = true });
        }

        if (OperatingSystem.IsMacOS())
        {
            var showInDock = Design.IsDesignMode || AppManager.Instance.Config.UiItem.MacOSShowInDock;
            builder = builder.With(new MacOSPlatformOptions { ShowInDock = showInDock });
        }

        return builder;
    }
}

/// <summary>
/// Browser→app SSO handoff channel. The site's <c>/app-login</c> page returns to the app via the custom
/// scheme <c>departamentvpn://auth?code=…</c>. Because the app is single-instance, a scheme launch while
/// the app is already running spawns a throwaway second process; this channel forwards that process's URL
/// to the live instance over a per-exe named pipe (cross-platform: Windows named pipes / Unix domain
/// sockets under the hood). The live instance's <see cref="StartServer"/> loop receives it and hands it to
/// the App-level handler (<see cref="SetHandler"/>), which routes the code into the login flow. A URL that
/// arrives before the handler is wired (cold-start launch) is buffered and drained on <see cref="SetHandler"/>.
/// </summary>
internal static class AppHandoffChannel
{
    public const string SchemePrefix = AccountVmScheme + "://";

    // Kept in sync with AccountViewModel.AppScheme (matches the site allowlist ^departament[a-z0-9]*$).
    private const string AccountVmScheme = "departamentvpn";

    //  Просьба выйти (Program.QuitArg). Намеренно НЕ ссылка departamentvpn://: такую ссылку может
    //  открыть любая страница в браузере, и она закрывала бы VPN одним щелчком. Пересылка по схеме
    //  всегда начинается с SchemePrefix, так что этой строки через браузер не получить.
    public const string QuitMessage = "departament:quit";

    public static bool IsQuit(string? message) => string.Equals(message?.Trim(), QuitMessage, StringComparison.Ordinal);

    private static readonly object _gate = new();
    private static string? _pending;
    private static Action<string>? _handler;

    private static string PipeName()
        => "departamentvpn-" + (Utils.IsWindows() ? Utils.GetMd5(Utils.GetExePath()) : "departament");

    /// <summary>Wires the App-level receiver and drains any URL buffered before it was ready.</summary>
    public static void SetHandler(Action<string> handler)
    {
        string? pending;
        lock (_gate)
        {
            _handler = handler;
            pending = _pending;
            _pending = null;
        }
        if (pending != null)
        {
            try { handler(pending); } catch { }
        }
    }

    /// <summary>Delivers a scheme URL to the App handler, or buffers it until the handler is set.</summary>
    public static void Receive(string url)
    {
        Action<string>? handler;
        lock (_gate)
        {
            handler = _handler;
            if (handler == null)
            {
                _pending = url;
                return;
            }
        }
        try { handler(url); } catch { }
    }

    /// <summary>Runs a background loop that accepts one forwarded URL per connection for the app lifetime.</summary>
    public static void StartServer()
    {
        var t = new Thread(ServerLoop) { IsBackground = true, Name = "AppHandoffPipe" };
        t.Start();
    }

    private static void ServerLoop()
    {
        var name = PipeName();
        while (true)
        {
            try
            {
                using var server = new NamedPipeServerStream(name, PipeDirection.In, 1, PipeTransmissionMode.Byte, PipeOptions.None);
                server.WaitForConnection();
                using var reader = new StreamReader(server, Encoding.UTF8);
                var url = reader.ReadToEnd()?.Trim();
                if (!string.IsNullOrEmpty(url))
                {
                    Receive(url);
                }
            }
            catch
            {
                // Pipe fault — pause briefly and recreate rather than tearing the app down.
                try { Thread.Sleep(200); } catch { }
            }
        }
    }

    /// <summary>Second-instance side: hands the scheme URL to the running app, then the caller exits.</summary>
    public static void ForwardToRunningInstance(string url)
    {
        try
        {
            using var client = new NamedPipeClientStream(".", PipeName(), PipeDirection.Out);
            client.Connect(2000);
            using var writer = new StreamWriter(client, new UTF8Encoding(false)) { AutoFlush = true };
            writer.Write(url);
        }
        catch
        {
            // The live instance didn't accept the URL — exit quietly; the user can still «войти по коду».
        }
    }
}
