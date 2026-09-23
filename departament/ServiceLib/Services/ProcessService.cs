namespace ServiceLib.Services;

public class ProcessService : IDisposable
{
    private readonly Process _process;
    private readonly Func<bool, string, Task>? _updateFunc;
    private bool _isDisposed;
    // Set BEFORE any intentional Kill (StopAsync / Dispose) so the always-on Exited handler can tell an
    // intentional teardown apart from a genuine crash and suppress the public Exited event for the former.
    private volatile bool _stopping;
    // 0 → not yet raised, 1 → raised. Guarantees the public Exited event fires at most once.
    private int _exitedRaised;
    // Единый обработчик ОБОИХ потоков вывода; отцепляется только stdout-часть при выходе процесса.
    private DataReceivedEventHandler? _dataHandler;

    //  ПОСЛЕДНИЕ СТРОКИ ВЫВОДА ЯДРА — почему запуск не удался, словами самого ядра.
    //  Раньше вывод перенаправлялся только при displayLog, а он выключен для узлов типа Custom
    //  (провайдерский XRAY_JSON). Ядро печатало «Failed to start: ...» и умирало, строка уходила
    //  в никуда, и наверх поднималось безымянное «Не удалось запустить ядро». Теперь перенаправлены
    //  ОБА потока всегда, и последние строки лежат здесь, чтобы отказ подключения мог назвать
    //  причину. Именно оба: Xray печатает фатальную строку старта в stdout, а не в stderr, — на
    //  одном stderr буфер оставался пустым, и причина снова терялась. В журнал строки по-прежнему
    //  уходят только при displayLog: поведение панели сообщений не менялось.
    private const int _outputTailCapacity = 12;
    private readonly Queue<string> _outputTail = new(_outputTailCapacity);
    private readonly object _outputTailLock = new();

    /// <summary>
    /// Raised exactly once when the underlying process exits UNEXPECTEDLY on its own (crash / OOM /
    /// killed / stale after sleep-resume/network-change). Wired for EVERY process regardless of
    /// <c>displayLog</c>. It is deliberately NOT raised for an intentional teardown via
    /// <see cref="StopAsync"/> or <see cref="Dispose"/> (the <see cref="_stopping"/> guard suppresses
    /// it), so a normal stop never looks like a crash. Fires on the thread <see cref="Process.Exited"/>
    /// fires on (a ThreadPool thread); the subscriber (CoreManager) marshals/serializes as needed.
    /// </summary>
    public event Action<ProcessService>? Exited;

    public int Id => _process.Id;
    public IntPtr Handle => _process.Handle;
    public bool HasExited => _process.HasExited;

    public ProcessService(
        string fileName,
        string arguments,
        string workingDirectory,
        bool displayLog,
        bool redirectInput,
        Dictionary<string, string>? environmentVars,
        Func<bool, string, Task>? updateFunc)
    {
        _updateFunc = updateFunc;

        _process = new Process
        {
            StartInfo = new ProcessStartInfo
            {
                FileName = fileName,
                Arguments = arguments,
                WorkingDirectory = workingDirectory,
                UseShellExecute = false,
                RedirectStandardInput = redirectInput,
                // Оба потока — всегда: только по ним ядро называет причину отказа.
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                CreateNoWindow = true,
                StandardOutputEncoding = Encoding.UTF8,
                StandardErrorEncoding = Encoding.UTF8,
            },
            EnableRaisingEvents = true
        };

        if (environmentVars != null)
        {
            foreach (var kv in environmentVars)
            {
                _process.StartInfo.Environment[kv.Key] = kv.Value;
            }
        }

        RegisterLogHandlers(displayLog);

        // ALWAYS observe process exit (not only for the displayLog case) so an unexpected death of the
        // core / pre-service can be surfaced to CoreManager for crash detection + auto-restart. The
        // handler detaches the log pipe (displayLog only) AND raises the public Exited event — unless an
        // intentional stop is in progress (see _stopping), in which case the exit is expected and quiet.
        _process.Exited += OnProcessExited;
    }

    public async Task StartAsync(string pwd = null)
    {
        _process.Start();

        if (_process.StartInfo.RedirectStandardOutput)
        {
            _process.BeginOutputReadLine();
        }
        if (_process.StartInfo.RedirectStandardError)
        {
            _process.BeginErrorReadLine();
        }

        if (_process.StartInfo.RedirectStandardInput)
        {
            await Task.Delay(10);
            await _process.StandardInput.WriteLineAsync(pwd);
        }
    }

    public async Task StopAsync()
    {
        // Mark this as an intentional teardown BEFORE the Kill so the always-on Exited handler treats
        // the resulting process exit as expected (no public Exited → no false crash-restart upstream).
        _stopping = true;

        if (_process.HasExited)
        {
            return;
        }

        try
        {
            if (_process.StartInfo.RedirectStandardOutput)
            {
                try
                {
                    _process.CancelOutputRead();
                }
                catch { }
            }
            if (_process.StartInfo.RedirectStandardError)
            {
                try
                {
                    _process.CancelErrorRead();
                }
                catch { }
            }

            try
            {
                if (Utils.IsNonWindows())
                {
                    _process.Kill(true);
                }
            }
            catch { }

            try
            {
                _process.Kill();
            }
            catch { }

            await Task.Delay(100);
        }
        catch (Exception ex)
        {
            await _updateFunc?.Invoke(true, ex.Message);
        }
    }

    /// <summary>
    /// Последние строки вывода ядра, сверху вниз, одной строкой. Пусто, когда ядро ничего не сказало.
    /// Читается после падения запуска, чтобы отказ подключения назвал причину словами ядра.
    /// </summary>
    public string GetOutputTail()
    {
        lock (_outputTailLock)
        {
            return _outputTail.Count == 0 ? string.Empty : string.Join(Environment.NewLine, _outputTail);
        }
    }

    /// <summary>
    /// Дожидается, пока асинхронные читатели вывода доберут всё до конца. Событие Exited приходит
    /// РАНЬШЕ, чем дочитаны потоки, поэтому без этого последняя (она же фатальная) строка ядра могла
    /// не успеть попасть в буфер. Безпараметрный WaitForExit как раз и означает «выход + слив
    /// вывода»; ждём его в пуле с потолком, чтобы никакая заминка не подвесила вызывающего.
    /// </summary>
    public async Task FlushOutputAsync(int timeoutMs = 800)
    {
        try
        {
            await Task.Run(() => _process.WaitForExit()).WaitAsync(TimeSpan.FromMilliseconds(timeoutMs));
        }
        catch
        {
        }
    }

    private void RegisterLogHandlers(bool displayLog)
    {
        _dataHandler = (sender, e) =>
        {
            if (e.Data.IsNullOrEmpty())
            {
                return;
            }

            lock (_outputTailLock)
            {
                _outputTail.Enqueue(e.Data);
                while (_outputTail.Count > _outputTailCapacity)
                {
                    _outputTail.Dequeue();
                }
            }

            //  В панель сообщений вывод уходит только при displayLog — ровно как раньше. Буфер выше
            //  живёт всегда и сам никуда не печатает.
            if (displayLog)
            {
                _ = _updateFunc?.Invoke(false, e.Data + Environment.NewLine);
            }
        };

        _process.OutputDataReceived += _dataHandler;
        _process.ErrorDataReceived += _dataHandler;
    }

    private void OnProcessExited(object? sender, EventArgs e)
    {
        // Detach the log pipe handlers (present only in the displayLog case) — preserves the exact
        // behavior of the original Exited handler.
        //  Обработчик вывода НЕ отцепляем: Exited поднимается раньше, чем дочитаны асинхронные
        //  потоки, и последняя строка ядра — та самая, где написана причина, — приходит уже ПОСЛЕ
        //  выхода. Он только кладёт строку в ограниченную очередь и (при displayLog) шлёт её в
        //  панель, как и до выхода, поэтому жить ему до Dispose не мешает.

        // Intentional teardown (StopAsync / Dispose set _stopping first) → the exit is expected, stay
        // quiet so it is never mistaken for a crash.
        if (_stopping || _isDisposed)
        {
            return;
        }

        // Unexpected exit — raise the public event exactly once.
        if (Interlocked.Exchange(ref _exitedRaised, 1) == 0)
        {
            try
            {
                Exited?.Invoke(this);
            }
            catch
            {
            }
        }
    }

    public void Dispose()
    {
        if (_isDisposed)
        {
            return;
        }

        // Mark the intentional teardown BEFORE the Kill so a Dispose-driven exit is never surfaced as a
        // crash (belt-and-suspenders to StopAsync, which is normally called first).
        _stopping = true;

        try
        {
            if (!_process.HasExited)
            {
                try
                {
                    _process.CancelOutputRead();
                }
                catch { }
                try
                {
                    _process.CancelErrorRead();
                }
                catch { }

                _process.Kill();
            }

            _process.Dispose();
        }
        catch (Exception ex)
        {
            _updateFunc?.Invoke(true, ex.Message);
        }

        _isDisposed = true;
        GC.SuppressFinalize(this);
    }
}
