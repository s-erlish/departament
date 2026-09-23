using System.Globalization;
using ServiceLib.Services.AppUpdate;

namespace ServiceLib.Manager;

/// <summary>
/// Самообновление departament: проверка ленты, загрузка, сверка и передача пакета установщику.
///
/// <para><b>Одно состояние на всё приложение.</b> Экран «Проверить обновление» и уведомление в окне
/// показывают один и тот же снимок (<see cref="State"/>, <see cref="StateChanged"/>): начатая из
/// уведомления загрузка видна на экране с тем же прогрессом, и наоборот.</para>
///
/// <para><b>Автопроверка не на пути запуска.</b> <see cref="StartSchedule"/> зовёт оболочка после первого
/// кадра окна; первая проверка — ещё через 20–30 с, дальше раз в 6 часов, пока приложение открыто.
/// Автопроверка молчалива: состояние «проверяем» она не показывает, сбой (нет сети, лимит GitHub) пишет
/// только в журнал, а на экран выносит лишь предложение новой версии.</para>
///
/// <para><b>Перезапуск только по подтверждению.</b> Скачать и проверить — да, само. Установить — никогда:
/// установка закрывает приложение, а вместе с ним рвёт подключение. <see cref="InstallAsync"/> вызывается
/// только из явного «Перезапустить» пользователя.</para>
/// </summary>
public sealed class AppUpdateManager
{
    private static readonly Lazy<AppUpdateManager> _instance = new(() => new());
    public static AppUpdateManager Instance => _instance.Value;

    private const string _tag = "AppUpdate";

    // Файл суммы — одна строка; потолок не даёт скачать под его именем что-то большое.
    private const long ChecksumMaxBytes = 4096;

    // Потолок пакета, если лента не назвала его размер.
    private const long PackageMaxBytes = 1L << 30;

    private readonly object _gate = new();
    private readonly object _publishGate = new();
    private readonly AppUpdateClient _client;

    private AppUpdateState _state = new() { Stage = AppUpdateStage.Idle };

    // Растёт при каждом действии пользователя. Фоновая работа запоминает его в начале и не трогает
    // состояние, если за это время пользователь что-то сделал: результат уже не про то, что на экране.
    private long _generation;

    private CancellationTokenSource? _work;
    private string? _expectedSha256;
    private int _scheduled;

    // Итог прошлой установки читается один раз за сеанс, и все, кто о нём спрашивает, ждут, пока он
    // прочитан: оболочке нужен не факт «кто-то уже начал читать», а сама метка.
    private readonly object _reconcileGate = new();
    private bool _reconciled;
    private AppUpdatePendingInstall? _reconnect;

    private AppUpdateManager()
    {
        Channel = AppUpdateChannel.Current;
        if (!AppVersion.TryParse(Utils.GetVersionInfo(), out var running))
        {
            Logging.SaveLog($"{_tag}: running version «{Utils.GetVersionInfo()}» is not SemVer, treating it as 0.0.0");
            AppVersion.TryParse("0.0.0", out running);
        }
        Running = running!;
        _client = new AppUpdateClient(Channel, $"departament/{Running}");
    }

    public AppUpdateChannel Channel { get; }

    /// <summary>Запущенная версия (из тега выпуска, с которым собран exe).</summary>
    public AppVersion Running { get; }

    /// <summary>Новый снимок состояния. Приходит с фонового потока; подписчик сам переходит в поток UI.</summary>
    public event EventHandler<AppUpdateState>? StateChanged;

    public AppUpdateState State
    {
        get
        {
            lock (_gate)
            {
                return _state;
            }
        }
    }

    public bool IsSupported => Channel.IsSupportedPlatform;

    public bool IncludePreRelease => AppManager.Instance.Config.CheckUpdateItem?.CheckPreReleaseUpdate ?? false;

    private static string UpdateDir => Utils.GetTempPath("update");
    private static string PackagePath => Path.Combine(UpdateDir, AppUpdateChannel.AssetName);
    private static string ChecksumPath => Path.Combine(UpdateDir, AppUpdateChannel.ChecksumAssetName);
    private static string PendingPath => Path.Combine(UpdateDir, "pending.json");

    #region Schedule

    /// <summary>
    /// Запускает автопроверку: первая через <paramref name="firstDelay"/>, дальше каждые <paramref name="period"/>.
    /// Не удалась фоновая проверка из-за сети (<see cref="QuickRetryFailures"/>) — следующая через
    /// <see cref="QuickRetryDelay"/>, а не через период, но не больше <see cref="QuickRetries"/> раз подряд:
    /// на запуске сеть часто ещё поднимается (подключение VPN перестраивает маршруты), и первая проверка
    /// не должна откладывать новость на целый период. Повторный вызов ничего не делает.
    /// Выключателя у автопроверки нет: владелец решил, что кнопки «Проверить обновления» достаточно, а о
    /// новой версии приложение сообщает само. Поле AutoCheck из конфигов прежних сборок при чтении
    /// пропускается и при следующей записи пропадает.
    /// </summary>
    public void StartSchedule(TimeSpan firstDelay, TimeSpan period)
    {
        if (Interlocked.Exchange(ref _scheduled, 1) == 1)
        {
            return;
        }
        _ = Task.Run(async () =>
        {
            EnsureReconciled();
            var delay = firstDelay;
            var retries = 0;
            while (true)
            {
                await Task.Delay(delay);
                delay = period;
                if (!IsSupported)
                {
                    continue;
                }
                _lastBackgroundFailure = null;
                try
                {
                    await CheckAsync(userInitiated: false);
                }
                catch (Exception ex)
                {
                    Logging.SaveLog(_tag, ex);
                }
                if (_lastBackgroundFailure is { } failure && QuickRetryFailures.Contains(failure) && retries < QuickRetries)
                {
                    retries++;
                    delay = QuickRetryDelay;
                    Logging.SaveLog($"{_tag}: background check failed ({failure}), retry {retries} in {QuickRetryDelay.TotalMinutes:0} min");
                }
                else
                {
                    retries = 0;
                }
            }
        });
    }

    /// <summary>Через сколько повторить фоновую проверку, которой помешала сеть.</summary>
    private static readonly TimeSpan QuickRetryDelay = TimeSpan.FromMinutes(2);

    /// <summary>Сколько раз подряд повторять раньше периода.</summary>
    private const int QuickRetries = 3;

    /// <summary>
    /// Сбои, которые проходят сами за минуты: нет сети, сервер не ответил, ответил ошибкой. Ограничение
    /// числа запросов GitHub сюда не входит: оно снимается только через час, частые повторы его продлят.
    /// </summary>
    private static readonly AppUpdateFailure[] QuickRetryFailures =
        [AppUpdateFailure.Offline, AppUpdateFailure.Unreachable, AppUpdateFailure.ServerError];

    /// <summary>Итог последней фоновой проверки: причина сбоя или null. Читает только цикл автопроверки.</summary>
    private AppUpdateFailure? _lastBackgroundFailure;

    /// <summary>
    /// Итог прошлой установки и уборка скачанного. Один раз за сеанс, до первой проверки. Установщик
    /// перезапускает приложение и в случае успеха, и после отката, поэтому о результате судим по версии:
    /// запущена та, что передавали установщику, — обновились; прежняя — замена откатилась, и это
    /// показывается как сбой с «Повторить», а не молчанием. Если в момент «Перезапустить» было
    /// подключение, метка откладывается для <see cref="TakeReconnect"/>: вернуть его решает оболочка.
    /// </summary>
    public void EnsureReconciled()
    {
        lock (_reconcileGate)
        {
            if (_reconciled)
            {
                return;
            }
            _reconciled = true;
            try
            {
                var pending = File.Exists(PendingPath) ? AppUpdatePendingInstall.TryParse(File.ReadAllText(PendingPath)) : null;
                if (pending is not null && AppVersion.TryParseTag(pending.Tag, out var expected))
                {
                    if (Running >= expected)
                    {
                        Logging.SaveLog($"{_tag}: updated to {Running}");
                    }
                    else
                    {
                        Logging.SaveLog($"{_tag}: installing {expected} did not take, still {Running} (see guiLogs/upgrade.log)");
                        var offer = new AppUpdateOffer(expected, pending.Tag!, pending.PreRelease, 0, null);
                        Transition(null, new AppUpdateState { Stage = AppUpdateStage.Failed, Failure = AppUpdateFailure.InstallFailed, Offer = offer });
                    }
                    if (pending.Reconnect is { } marker)
                    {
                        Logging.SaveLog($"{_tag}: the restart for {pending.Tag} closed a connection (server {marker.ServerId}, tun {marker.Tun})");
                        _reconnect = pending;
                    }
                }
            }
            catch (Exception ex)
            {
                Logging.SaveLog(_tag, ex);
            }
            // Метка удаляется при первом же чтении: второй запуск её уже не увидит, что бы ни случилось с этим.
            DeleteUpdateFiles();
        }

        // Прежний установщик: заменяя сам себя, он отодвигается в AmazTool.exe.tmp (запущенный exe на Windows
        // нельзя удалить, но можно переименовать), а убрал бы эту копию только при следующем обновлении. К этой
        // минуте он давно вышел; если файл ещё занят, его уберёт следующий запуск.
        try
        {
            File.Delete(Path.Combine(Utils.GetBaseDirectory(), Utils.GetExeName(AppUpdateChannel.InstallerBaseName) + ".tmp"));
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            Logging.SaveLog($"{_tag}: the previous installer copy is still busy: {ex.Message}");
        }
    }

    /// <summary>
    /// Метка прошлого перезапуска, если он закрыл подключение, — один раз за сеанс: второй вызов вернёт
    /// null. Решение, возвращать ли подключение, принимает <see cref="AppUpdateReconnect"/> в оболочке.
    /// Читает файл, поэтому звать не из потока интерфейса.
    /// </summary>
    public AppUpdatePendingInstall? TakeReconnect()
    {
        EnsureReconciled();
        lock (_reconcileGate)
        {
            var pending = _reconnect;
            _reconnect = null;
            return pending;
        }
    }

    private static bool IsConnected =>
        AppManager.Instance.IsRunningCore(ECoreType.Xray) || AppManager.Instance.IsRunningCore(ECoreType.sing_box);

    #endregion Schedule

    #region Check

    /// <summary>
    /// Спрашивает ленту. <paramref name="userInitiated"/> — с экрана: показывает «проверяем» и любой исход,
    /// включая сбой. Фоновая проверка ничего не показывает, кроме найденного обновления или «обновлений
    /// нет» там, где до этого не было ответа.
    /// </summary>
    public async Task CheckAsync(bool userInitiated)
    {
        EnsureReconciled();

        CancellationTokenSource cts;
        long generation;
        DateTime? checkedBefore;
        lock (_gate)
        {
            // Идущую работу и готовый к установке пакет проверка не отменяет. Сбой уже начатого
            // обновления остаётся на экране, пока пользователь не решит сам.
            if (_state.IsBusy || _state.Stage == AppUpdateStage.Ready
                || (!userInitiated && _state is { Stage: AppUpdateStage.Failed, Offer: not null }))
            {
                return;
            }
            checkedBefore = _state.CheckedAt;
            if (userInitiated)
            {
                _work?.Cancel();
                cts = _work = new CancellationTokenSource();
                generation = ++_generation;
                _state = new AppUpdateState { Stage = AppUpdateStage.Checking, CheckedAt = checkedBefore };
            }
            else
            {
                cts = new CancellationTokenSource();
                generation = _generation;
            }
        }
        if (userInitiated)
        {
            Publish();
        }

        AppUpdateState next;
        try
        {
            next = await CheckCoreAsync(cts.Token);
        }
        catch (OperationCanceledException) when (cts.IsCancellationRequested)
        {
            return;
        }
        catch (AppUpdateException ex)
        {
            Logging.SaveLog($"{_tag}: check failed, {ex.Reason}: {ex.Message}");
            next = new AppUpdateState { Stage = AppUpdateStage.Failed, Failure = ex.Reason, CheckedAt = checkedBefore };
        }
        catch (Exception ex)
        {
            Logging.SaveLog(_tag, ex);
            next = new AppUpdateState { Stage = AppUpdateStage.Failed, Failure = AppUpdateFailure.ServerError, CheckedAt = checkedBefore };
        }

        if (userInitiated)
        {
            Transition(generation, next);
            return;
        }

        // Фон: предложение показываем всегда, «новее нет» — если до этого показывать было нечего, сбой — никогда.
        _lastBackgroundFailure = next.Stage == AppUpdateStage.Failed ? next.Failure : null;
        lock (_gate)
        {
            var show = next.Stage == AppUpdateStage.Available
                ? _state.Stage != AppUpdateStage.Available || _state.Offer != next.Offer
                : next.Stage == AppUpdateStage.UpToDate && _state.Stage is AppUpdateStage.Idle or AppUpdateStage.UpToDate or AppUpdateStage.Available;
            if (!show || _generation != generation)
            {
                return;
            }
            _state = next;
        }
        Publish();
    }

    private async Task<AppUpdateState> CheckCoreAsync(CancellationToken token)
    {
        if (!IsSupported)
        {
            throw new AppUpdateException(AppUpdateFailure.UnsupportedPlatform, $"no package for {RuntimeInformation.OSDescription} {RuntimeInformation.ProcessArchitecture}");
        }

        var includePreRelease = IncludePreRelease;
        var response = await _client.GetFeedAsync(includePreRelease, token);
        var now = DateTime.Now;
        if (response.NoRelease)
        {
            Logging.SaveLog($"{_tag}: {AppUpdateChannel.Repo} has no published release yet");
            return new AppUpdateState { Stage = AppUpdateStage.UpToDate, NoReleaseYet = true, CheckedAt = now };
        }

        var result = AppUpdateFeed.Select(response.Body, isList: includePreRelease, Running, includePreRelease);
        Logging.SaveLog($"{_tag}: feed {AppUpdateChannel.Repo}: {result.Verdict}, newest {result.Newest?.ToString() ?? "none"}, running {Running}"
                        + (includePreRelease ? ", pre-releases on" : string.Empty));
        return result.Verdict switch
        {
            AppUpdateVerdict.Offer => new AppUpdateState { Stage = AppUpdateStage.Available, Offer = result.Offer, CheckedAt = now },
            AppUpdateVerdict.UpToDate => new AppUpdateState { Stage = AppUpdateStage.UpToDate, CheckedAt = now },
            AppUpdateVerdict.NoRelease => new AppUpdateState { Stage = AppUpdateStage.UpToDate, NoReleaseYet = true, CheckedAt = now },
            AppUpdateVerdict.NoPackage => new AppUpdateState { Stage = AppUpdateStage.Failed, Failure = AppUpdateFailure.NoPackage, CheckedAt = now },
            _ => throw new AppUpdateException(AppUpdateFailure.ServerError, "feed is not a GitHub release list"),
        };
    }

    /// <summary>
    /// Смена «Искать предварительный выпуск». Ответ зависит от этого переключателя, поэтому всё, что
    /// было найдено или скачано по старому правилу, сбрасывается, и лента опрашивается заново: иначе
    /// экран показывал бы rc под выключенным переключателем.
    /// </summary>
    public async Task OnPreReleaseChangedAsync()
    {
        lock (_gate)
        {
            _work?.Cancel();
            _generation++;
            _expectedSha256 = null;
            if (_state.Stage != AppUpdateStage.Installing)
            {
                _state = new AppUpdateState { Stage = AppUpdateStage.Idle, CheckedAt = _state.CheckedAt };
            }
        }
        DeleteUpdateFiles();
        Publish();
        await CheckAsync(userInitiated: true);
    }

    #endregion Check

    #region Download

    /// <summary>«Обновить»: скачать пакет и его сумму, сверить, проверить состав. Итог — «готово» или сбой.</summary>
    public async Task DownloadAsync()
    {
        AppUpdateOffer offer;
        CancellationTokenSource cts;
        long generation;
        lock (_gate)
        {
            if (_state.Offer is null || _state.Stage is not (AppUpdateStage.Available or AppUpdateStage.Failed))
            {
                return;
            }
            offer = _state.Offer;
            _work?.Cancel();
            cts = _work = new CancellationTokenSource();
            generation = ++_generation;
            _expectedSha256 = null;
            _state = new AppUpdateState { Stage = AppUpdateStage.Downloading, Offer = offer, Total = offer.PackageSize, CheckedAt = _state.CheckedAt };
        }
        Publish();

        try
        {
            DeleteUpdateFiles();
            Directory.CreateDirectory(UpdateDir);

            await _client.DownloadAsync(offer.Tag, AppUpdateChannel.ChecksumAssetName, ChecksumPath,
                expectedSize: 0, ChecksumMaxBytes, progress: null, cts.Token);
            if (!AppUpdateChecksum.TryParse(await File.ReadAllTextAsync(ChecksumPath, cts.Token), AppUpdateChannel.AssetName, out var expected))
            {
                throw new AppUpdateException(AppUpdateFailure.BadPackage, $"{AppUpdateChannel.ChecksumAssetName} of {offer.Tag} is not a sha256sum line");
            }

            await _client.DownloadAsync(offer.Tag, AppUpdateChannel.AssetName, PackagePath,
                offer.PackageSize, offer.PackageSize > 0 ? offer.PackageSize : PackageMaxBytes,
                new DownloadProgress(this, generation, offer), cts.Token);

            Transition(generation, State with { Stage = AppUpdateStage.Verifying, Received = 0, Total = 0 });
            if (!await AppUpdateChecksum.MatchesAsync(PackagePath, expected, cts.Token))
            {
                throw new AppUpdateException(AppUpdateFailure.ChecksumMismatch, $"{AppUpdateChannel.AssetName} of {offer.Tag} does not match its sha256");
            }
            if (!AppUpdatePackage.Validate(PackagePath, Utils.GetExeName(AppUpdateChannel.AppExeBaseName),
                    Utils.GetExeName(AppUpdateChannel.InstallerBaseName), out var problem))
            {
                throw new AppUpdateException(AppUpdateFailure.BadPackage, $"{AppUpdateChannel.AssetName} of {offer.Tag}: {problem}");
            }

            lock (_gate)
            {
                _expectedSha256 = expected;
            }
            Logging.SaveLog($"{_tag}: {offer.Tag} downloaded and verified (sha256 {expected})");
            Transition(generation, new AppUpdateState { Stage = AppUpdateStage.Ready, Offer = offer, CheckedAt = State.CheckedAt });
        }
        catch (OperationCanceledException) when (cts.IsCancellationRequested)
        {
            // «Отменить» — предложение остаётся на месте с тем же «Обновить». Если отменила смена
            // переключателя, поколение уже другое, и этот переход не состоится.
            DeleteUpdateFiles();
            Transition(generation, new AppUpdateState { Stage = AppUpdateStage.Available, Offer = offer, CheckedAt = State.CheckedAt });
        }
        catch (Exception ex)
        {
            // Пакет, который не сошёлся или не прошёл проверку, удаляется: до установщика он не дойдёт
            // ни сейчас, ни после перезапуска.
            DeleteUpdateFiles();
            var reason = ex is AppUpdateException u ? u.Reason : AppUpdateFailure.DownloadFailed;
            Logging.SaveLog($"{_tag}: download of {offer.Tag} failed, {reason}: {ex.Message}");
            Transition(generation, new AppUpdateState { Stage = AppUpdateStage.Failed, Failure = reason, Offer = offer, CheckedAt = State.CheckedAt });
        }
    }

    /// <summary>«Отменить» во время загрузки или сверки.</summary>
    public void CancelDownload()
    {
        lock (_gate)
        {
            if (_state.Stage is AppUpdateStage.Downloading or AppUpdateStage.Verifying)
            {
                _work?.Cancel();
            }
        }
    }

    /// <summary>Прогресс загрузки в состояние — не чаще шага в 0,5 % (или 256 КБ без длины): 160 МБ пакета
    /// иначе дали бы тысячи перерисовок ради одной и той же полоски.</summary>
    private sealed class DownloadProgress(AppUpdateManager owner, long generation, AppUpdateOffer offer) : IProgress<(long Received, long Total)>
    {
        private long _lastStep = -1;

        public void Report((long Received, long Total) value)
        {
            var step = value.Total > 0 ? value.Received * 200 / value.Total : value.Received / (256 * 1024);
            if (step == _lastStep)
            {
                return;
            }
            _lastStep = step;
            owner.Transition(generation, new AppUpdateState
            {
                Stage = AppUpdateStage.Downloading,
                Offer = offer,
                Received = value.Received,
                Total = value.Total,
                CheckedAt = owner.State.CheckedAt,
            });
        }
    }

    #endregion Download

    #region Install

    /// <summary>
    /// «Перезапустить» после подтверждения пользователя. Передаёт проверенный пакет установщику и выходит из
    /// приложения ТЕМ ЖЕ путём, что «Выход» в трее: ядро остановлено, системный прокси снят, настройки и
    /// статистика записаны. Установщик ждёт, пока процесс исчезнет, ставит новую версию и запускает её.
    /// </summary>
    /// <returns>false, если передать пакет не удалось: приложение остаётся открытым, причина — в состоянии.</returns>
    public async Task<bool> InstallAsync()
    {
        AppUpdateOffer offer;
        string? expected;
        long generation;
        lock (_gate)
        {
            if (_state.Stage != AppUpdateStage.Ready || _state.Offer is null)
            {
                return false;
            }
            offer = _state.Offer;
            expected = _expectedSha256;
            generation = ++_generation;
            _state = new AppUpdateState { Stage = AppUpdateStage.Installing, Offer = offer, CheckedAt = _state.CheckedAt };
        }
        Publish();

        try
        {
            // Пакет пролежал на диске, пока пользователь решал: сверяем ещё раз прямо перед установщиком.
            if (expected is null || !File.Exists(PackagePath) || !await AppUpdateChecksum.MatchesAsync(PackagePath, expected))
            {
                throw new AppUpdateException(AppUpdateFailure.ChecksumMismatch, "the verified package changed on disk before install");
            }
            if (!Utils.UpgradeAppExists(out var installer))
            {
                throw new AppUpdateException(AppUpdateFailure.InstallerMissing, $"{installer} not found");
            }

            // Метка для следующего запуска: что ставили и что было подключено. Сервер — тот, что по
            // умолчанию: к нему подключают и щит, и смена сервера. Режим — действующий: если TUN в этом
            // запуске недоступен, конфиг уже понижен до прокси (StatusBarViewModel), и подключение было им.
            var config = AppManager.Instance.Config;
            var pending = new AppUpdatePendingInstall
            {
                Tag = offer.Tag,
                PreRelease = offer.IsPreRelease,
                From = Running.ToString(),
                HandedOffUtc = DateTime.UtcNow,
                Reconnect = IsConnected ? new AppUpdateReconnectMarker { ServerId = config.IndexId, Tun = config.TunModeItem.EnableTun } : null,
            };
            await File.WriteAllTextAsync(PendingPath, pending.ToJson());

            // Без оболочки: установщик наследует права приложения (оно запущено от администратора), а
            // аргументы уходят списком, без склейки в строку, и путь с пробелами не разваливается.
            var start = new ProcessStartInfo(installer)
            {
                UseShellExecute = false,
                WorkingDirectory = Utils.GetBaseDirectory(),
            };
            start.ArgumentList.Add("upgrade");
            start.ArgumentList.Add("--package");
            start.ArgumentList.Add(PackagePath);
            start.ArgumentList.Add("--pid");
            start.ArgumentList.Add(Environment.ProcessId.ToString(CultureInfo.InvariantCulture));
            using var process = Process.Start(start)
                ?? throw new AppUpdateException(AppUpdateFailure.InstallerMissing, $"{installer} did not start");
            Logging.SaveLog($"{_tag}: {offer.Tag} handed to the installer (pid {process.Id}), exiting"
                            + (pending.Reconnect is { } marker ? $", reconnect to {marker.ServerId} (tun {marker.Tun}) after the restart" : string.Empty));
        }
        catch (Exception ex)
        {
            TryDelete(PendingPath);
            var reason = ex is AppUpdateException u ? u.Reason : AppUpdateFailure.InstallerMissing;
            Logging.SaveLog($"{_tag}: could not start the install of {offer.Tag}, {reason}: {ex.Message}");
            if (reason == AppUpdateFailure.ChecksumMismatch)
            {
                DeleteUpdateFiles();
            }
            Transition(generation, new AppUpdateState { Stage = AppUpdateStage.Failed, Failure = reason, Offer = offer, CheckedAt = State.CheckedAt });
            return false;
        }

        try
        {
            await AppManager.Instance.AppExitAsync(false);
        }
        catch (Exception ex)
        {
            Logging.SaveLog(_tag, ex);
        }
        AppManager.Instance.Shutdown(true);
        return true;
    }

    #endregion Install

    #region State

#if DEBUG
    /// <summary>
    /// Только отладочная сборка: показать экран и уведомление в заданном состоянии без сети — для снимков
    /// всех состояний при проверке оформления. В выпускной сборке метода нет.
    /// </summary>
    public void DevShowState(AppUpdateState state)
    {
        lock (_gate)
        {
            _generation++;
        }
        Transition(null, state);
    }
#endif

    /// <summary>Ставит состояние, если с начала работы <paramref name="generation"/> пользователь ничего не
    /// сделал (null — без проверки), и оповещает подписчиков.</summary>
    private void Transition(long? generation, AppUpdateState next)
    {
        lock (_gate)
        {
            if (generation is not null && generation != _generation)
            {
                return;
            }
            _state = next;
        }
        Publish();
    }

    /// <summary>
    /// Оповещение — под своим замком и всегда с САМЫМ СВЕЖИМ снимком: если два потока сменили состояние
    /// почти одновременно, последним подписчик увидит последнее, а не то, чей вызов опоздал.
    /// </summary>
    private void Publish()
    {
        lock (_publishGate)
        {
            StateChanged?.Invoke(this, State);
        }
    }

    private static void DeleteUpdateFiles()
    {
        foreach (var path in new[] { PackagePath, ChecksumPath, PendingPath })
        {
            TryDelete(path);
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
            Logging.SaveLog(_tag, ex);
        }
    }

    #endregion State
}
