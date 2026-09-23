using ServiceLib.UdpTest;

namespace ServiceLib.Services;

public class SpeedtestService(Config config, Func<SpeedTestResult, Task> updateFunc)
{
    private static readonly string _tag = "SpeedtestService";
    private readonly Config? _config = config;
    private readonly Func<SpeedTestResult, Task>? _updateFunc = updateFunc;
    // Ключи ИДУЩИХ прогонов. Раньше это был ConcurrentBag, из которого нельзя удалить один элемент:
    // каждый замер добавлял свой GUID и НИКОГДА его не убирал — очистить набор умел только ExitLoop,
    // то есть кнопка «остановить ВСЁ». За сеанс с двадцатью пингами в наборе копилось двадцать
    // мёртвых ключей, а ShouldStopTest — он вызывается на КАЖДЫЙ сервер в КАЖДОМ пакете — линейно
    // просматривал их все. Словарь позволяет прогону снять СВОЙ ключ по завершении и проверять
    // остановку за O(1).
    private static readonly ConcurrentDictionary<string, byte> _lstExitLoop = new();
    private readonly int _speedTestPageSize = config.SpeedTestItem.SpeedTestPageSize ?? Global.SpeedTestPageSize;
    private readonly TimeSpan _delayInterval = TimeSpan.FromSeconds(config.SpeedTestItem.SpeedTestDelayInterval ?? 1);

    /// <summary>
    /// Прогон в фоне. Возвращённая задача завершается, когда прогон ДЕЙСТВИТЕЛЬНО закончен (или
    /// остановлен), и никогда не падает: ошибка пишется в журнал. Раньше метод ничего не возвращал,
    /// команда «пинг всех» завершалась в ту же миллисекунду, и карточка подписки гасила свой
    /// индикатор и писала «Задержка обновлена», пока все строки ещё крутили замер — на тридцати
    /// серверах за секунды до первого результата.
    /// </summary>
    public Task RunLoop(ESpeedActionType actionType, List<ProfileItem> selecteds)
    {
        return Task.Run(async () =>
        {
            try
            {
                await RunAsync(actionType, selecteds);
                await ProfileExManager.Instance.SaveTo();
                await UpdateFunc("", ResUI.SpeedtestingCompleted);
            }
            catch (Exception ex)
            {
                Logging.SaveLog(_tag, ex);
            }
        });
    }

    public void ExitLoop()
    {
        if (!_lstExitLoop.IsEmpty)
        {
            _ = UpdateFunc("", ResUI.SpeedtestingStop);

            _lstExitLoop.Clear();
        }
    }

    private static bool ShouldStopTest(string exitLoopKey)
    {
        return !_lstExitLoop.ContainsKey(exitLoopKey);
    }

    private async Task RunAsync(ESpeedActionType actionType, List<ProfileItem> selecteds)
    {
        var exitLoopKey = Utils.GetGuid(false);
        _lstExitLoop[exitLoopKey] = 0;
        try
        {
            await RunActionAsync(actionType, selecteds, exitLoopKey);
        }
        finally
        {
            // Прогон закончился (сам или по «остановить») — ключ больше не нужен.
            _lstExitLoop.TryRemove(exitLoopKey, out _);
        }
    }

    private async Task RunActionAsync(ESpeedActionType actionType, List<ProfileItem> selecteds, string exitLoopKey)
    {
        var lstSelected = await GetClearItem(actionType, selecteds);

        switch (actionType)
        {
            case ESpeedActionType.Tcping:
                await RunTcpingAsync(lstSelected, exitLoopKey);
                break;

            case ESpeedActionType.Realping:
                await RunRealPingBatchAsync(lstSelected, exitLoopKey);
                break;

            case ESpeedActionType.UdpTest:
                await RunUdpTestBatchAsync(lstSelected, exitLoopKey);
                break;

            case ESpeedActionType.Speedtest:
                await RunMixedTestAsync(lstSelected, 1, true, exitLoopKey);
                break;

            case ESpeedActionType.Mixedtest:
                await RunMixedTestAsync(lstSelected, _config.SpeedTestItem.MixedConcurrencyCount, true, exitLoopKey);
                break;
        }
    }

    private async Task<List<ServerTestItem>> GetClearItem(ESpeedActionType actionType, List<ProfileItem> selecteds)
    {
        var lstSelected = new List<ServerTestItem>(selecteds.Count);
        // CUSTOM (raw xray-json) nodes are wrapped configs — they have no typed Port on the row but
        // DO carry a real proxy outbound, so they are testable (IsComplexType() covers them).
        var ids = selecteds.Where(it => !it.IndexId.IsNullOrEmpty()
            && (it.ConfigType.IsComplexType() || it.Port > 0))
            .Select(it => it.IndexId)
            .ToList();
        var profileMap = await AppManager.Instance.GetProfileItemsByIndexIdsAsMap(ids);
        for (var i = 0; i < selecteds.Count; i++)
        {
            var it = selecteds[i];
            if (!it.ConfigType.IsComplexType() && it.Port <= 0)
            {
                continue;
            }

            var profile = profileMap.GetValueOrDefault(it.IndexId, it);

            // For a CUSTOM node, resolve the wrapped proxy outbound's real server address/port so a
            // tcping test has a target (real-ping later rewrites Port to a local inbound port).
            var address = it.Address;
            var port = it.Port;
            if (it.ConfigType == EConfigType.Custom)
            {
                var info = XrayJsonTemplateFmt.Introspect(profile);
                if (info != null)
                {
                    address = info.Address.IsNullOrEmpty() ? address : info.Address;
                    port = info.Port > 0 ? info.Port : port;
                }
            }

            lstSelected.Add(new ServerTestItem()
            {
                IndexId = it.IndexId,
                Address = address,
                Port = port,
                ConfigType = it.ConfigType,
                QueueNum = i,
                Profile = profile,
                CoreType = AppManager.Instance.GetCoreType(profile, it.ConfigType),
            });
        }

        //clear test result
        foreach (var it in lstSelected)
        {
            switch (actionType)
            {
                case ESpeedActionType.Tcping:
                case ESpeedActionType.Realping:
                case ESpeedActionType.UdpTest:
                    await UpdateFunc(it.IndexId, ResUI.Speedtesting, "");
                    ProfileExManager.Instance.SetTestDelay(it.IndexId, 0);
                    break;

                case ESpeedActionType.Speedtest:
                    await UpdateFunc(it.IndexId, "", ResUI.SpeedtestingWait);
                    ProfileExManager.Instance.SetTestSpeed(it.IndexId, 0);
                    break;

                case ESpeedActionType.Mixedtest:
                    await UpdateFunc(it.IndexId, ResUI.Speedtesting, ResUI.SpeedtestingWait);
                    ProfileExManager.Instance.SetTestDelay(it.IndexId, 0);
                    ProfileExManager.Instance.SetTestSpeed(it.IndexId, 0);
                    break;
            }
        }

        if (lstSelected.Count > 1 && (actionType == ESpeedActionType.Speedtest || actionType == ESpeedActionType.Mixedtest))
        {
            NoticeManager.Instance.Enqueue(ResUI.SpeedtestingPressEscToExit);
        }

        return lstSelected;
    }

    private async Task RunTcpingAsync(List<ServerTestItem> selecteds, string exitLoopKey)
    {
        var pageSize = Math.Min(selecteds.Count, _speedTestPageSize);
        var lstBatch = GetTestBatchItem(selecteds, pageSize);

        foreach (var lst in lstBatch)
        {
            if (ShouldStopTest(exitLoopKey))
            {
                await UpdateFunc("", ResUI.SpeedtestingSkip);
                return;
            }

            List<Task> tasks = [];

            foreach (var it in lst)
            {
                if (ShouldStopTest(exitLoopKey))
                {
                    return;
                }

                tasks.Add(Task.Run(async () =>
                {
                    try
                    {
                        var responseTime = await GetTcpingTime(it.Address, it.Port);

                        ProfileExManager.Instance.SetTestDelay(it.IndexId, responseTime);
                        await UpdateFunc(it.IndexId, responseTime.ToString());
                    }
                    catch (Exception ex)
                    {
                        Logging.SaveLog(_tag, ex);
                    }
                }));
            }

            await Task.WhenAll(tasks);

            if (ShouldStopTest(exitLoopKey))
            {
                return;
            }

            await Task.Delay(_delayInterval);
        }
    }

    private async Task RunRealPingBatchAsync(List<ServerTestItem> lstSelected, string exitLoopKey, int pageSize = 0)
    {
        if (pageSize <= 0)
        {
            pageSize = Math.Min(lstSelected.Count, _speedTestPageSize);
        }
        var lstTest = GetTestBatchItem(lstSelected, pageSize);

        List<ServerTestItem> lstFailed = [];
        foreach (var lst in lstTest)
        {
            var ret = await RunRealPingAsync(lst, exitLoopKey);
            if (ret == false)
            {
                lstFailed.AddRange(lst);
            }
            await Task.Delay(_delayInterval);
        }

        //Retest the failed part
        var pageSizeNext = pageSize / 2;
        if (lstFailed.Count > 0 && pageSizeNext > 0)
        {
            if (ShouldStopTest(exitLoopKey))
            {
                await UpdateFunc("", ResUI.SpeedtestingSkip);
                return;
            }

            await UpdateFunc("", string.Format(ResUI.SpeedtestingTestFailedPart, lstFailed.Count));

            if (pageSizeNext > _config.SpeedTestItem.MixedConcurrencyCount)
            {
                await RunRealPingBatchAsync(lstFailed, exitLoopKey, pageSizeNext);
            }
            else
            {
                await RunMixedTestAsync(lstSelected, _config.SpeedTestItem.MixedConcurrencyCount, false, exitLoopKey);
            }
        }
    }

    private async Task<bool> RunRealPingAsync(List<ServerTestItem> selecteds, string exitLoopKey)
    {
        ProcessService processService = null;
        // departament: snapshot each node's REAL server address/port BEFORE GenerateClientSpeedtestConfig
        // rewrites ServerTestItem.Port to a local inbound. If the test core can't be started (e.g.
        // «Реальная задержка» chosen from a fresh start / while disconnected), we probe these originals
        // with a direct TCP handshake so the row still shows a latency value instead of «—»
        // (graceful Realping→Tcping fallback — ping works out of the box).
        var realTargets = selecteds.ToDictionary(it => it, it => (it.Address, it.Port));
        try
        {
            processService = await CoreManager.Instance.LoadCoreConfigSpeedtest(selecteds);
            if (processService is null)
            {
                // Core not running / config could not be built → TCP-handshake fallback, then report done.
                await RunRealPingTcpFallbackAsync(selecteds, realTargets, exitLoopKey);
                return true;
            }
            await Task.Delay(1000);

            List<Task> tasks = [];
            foreach (var it in selecteds)
            {
                if (!it.AllowTest)
                {
                    await UpdateFunc(it.IndexId, ResUI.SpeedtestingSkip);
                    continue;
                }

                if (ShouldStopTest(exitLoopKey))
                {
                    return false;
                }

                tasks.Add(Task.Run(async () =>
                {
                    await DoRealPing(it);
                }));
            }
            await Task.WhenAll(tasks);
        }
        catch (Exception ex)
        {
            Logging.SaveLog(_tag, ex);
        }
        finally
        {
            if (processService != null)
            {
                await processService?.StopAsync();
            }
        }
        return true;
    }

    // departament: Realping→Tcping graceful fallback. When the speedtest core can't be started, probe
    // each node's real address/port with the SAME TCP handshake as Tcping (GetTcpingTime) so «Реальная
    // задержка» still returns a value while disconnected. No AllowTest gate — that flag is only set while
    // BUILDING the (here-absent) core config, so we probe every node that carries a real address/port.
    private async Task RunRealPingTcpFallbackAsync(List<ServerTestItem> selecteds, Dictionary<ServerTestItem, (string? Address, int Port)> realTargets, string exitLoopKey)
    {
        List<Task> tasks = [];
        foreach (var it in selecteds)
        {
            if (ShouldStopTest(exitLoopKey))
            {
                return;
            }

            var target = realTargets.GetValueOrDefault(it);
            if (target.Address.IsNullOrEmpty() || target.Port <= 0)
            {
                await UpdateFunc(it.IndexId, ResUI.SpeedtestingSkip);
                continue;
            }

            tasks.Add(Task.Run(async () =>
            {
                try
                {
                    var responseTime = await GetTcpingTime(target.Address, target.Port);
                    ProfileExManager.Instance.SetTestDelay(it.IndexId, responseTime);
                    await UpdateFunc(it.IndexId, responseTime.ToString());
                }
                catch (Exception ex)
                {
                    Logging.SaveLog(_tag, ex);
                }
            }));
        }

        await Task.WhenAll(tasks);
    }

    private async Task RunUdpTestBatchAsync(List<ServerTestItem> lstSelected, string exitLoopKey, int pageSize = 0)
    {
        if (pageSize <= 0)
        {
            pageSize = Math.Min(lstSelected.Count, _speedTestPageSize);
        }
        var lstTest = GetTestBatchItem(lstSelected, pageSize);

        List<ServerTestItem> lstFailed = [];
        foreach (var lst in lstTest)
        {
            var ret = await RunUdpTestAsync(lst, exitLoopKey);
            if (ret == false)
            {
                lstFailed.AddRange(lst);
            }
            await Task.Delay(_delayInterval);
        }

        //Retest the failed part
        if (lstFailed.Count > 0)
        {
            if (ShouldStopTest(exitLoopKey))
            {
                await UpdateFunc("", ResUI.SpeedtestingSkip);
                return;
            }

            await UpdateFunc("", string.Format(ResUI.SpeedtestingTestFailedPart, lstFailed.Count));

            await RunUdpTestAsync(lstFailed, exitLoopKey);
        }
    }

    private async Task<bool> RunUdpTestAsync(List<ServerTestItem> selecteds, string exitLoopKey)
    {
        ProcessService processService = null;
        try
        {
            processService = await CoreManager.Instance.LoadCoreConfigSpeedtest(selecteds);
            if (processService is null)
            {
                return false;
            }
            await Task.Delay(1000);

            List<Task> tasks = [];
            foreach (var it in selecteds)
            {
                if (!it.AllowTest)
                {
                    continue;
                }

                if (ShouldStopTest(exitLoopKey))
                {
                    return false;
                }

                tasks.Add(Task.Run(async () =>
                {
                    await DoUdpTest(it);
                }));
            }
            await Task.WhenAll(tasks);
        }
        catch (Exception ex)
        {
            Logging.SaveLog(_tag, ex);
        }
        finally
        {
            if (processService != null)
            {
                await processService?.StopAsync();
            }
        }
        return true;
    }

    private async Task RunMixedTestAsync(List<ServerTestItem> selecteds, int concurrencyCount, bool blSpeedTest, string exitLoopKey)
    {
        using var concurrencySemaphore = new SemaphoreSlim(concurrencyCount);
        var downloadHandle = new DownloadService();
        List<Task> tasks = [];
        foreach (var it in selecteds)
        {
            if (ShouldStopTest(exitLoopKey))
            {
                await UpdateFunc(it.IndexId, "", ResUI.SpeedtestingSkip);
                continue;
            }
            await concurrencySemaphore.WaitAsync();

            tasks.Add(Task.Run(async () =>
            {
                ProcessService processService = null;
                try
                {
                    processService = await CoreManager.Instance.LoadCoreConfigSpeedtest(it);
                    if (processService is null)
                    {
                        await UpdateFunc(it.IndexId, "", ResUI.FailedToRunCore);
                        return;
                    }

                    await Task.Delay(1000);

                    var delay = await DoRealPing(it);
                    if (blSpeedTest)
                    {
                        if (ShouldStopTest(exitLoopKey))
                        {
                            await UpdateFunc(it.IndexId, "", ResUI.SpeedtestingSkip);
                            return;
                        }

                        if (delay > 0)
                        {
                            await DoSpeedTest(downloadHandle, it);
                        }
                        else
                        {
                            await UpdateFunc(it.IndexId, "", ResUI.SpeedtestingSkip);
                        }
                    }
                }
                catch (Exception ex)
                {
                    Logging.SaveLog(_tag, ex);
                }
                finally
                {
                    if (processService != null)
                    {
                        await processService?.StopAsync();
                    }
                    concurrencySemaphore.Release();
                }
            }));
        }
        await Task.WhenAll(tasks);
    }

    private async Task<int> DoRealPing(ServerTestItem it)
    {
        var webProxy = new WebProxy($"socks5://{Global.Loopback}:{it.Port}");
        var responseTime = await ConnectionHandler.GetRealPingTime(webProxy);

        ProfileExManager.Instance.SetTestDelay(it.IndexId, responseTime);
        await UpdateFunc(it.IndexId, responseTime.ToString());

        if (!_config.UiItem.HideColumnIpInfo && responseTime > 0)
        {
            var ipInfo = await ConnectionHandler.GetIPInfo(webProxy);
            var ipStr = ipInfo?.ToString() ?? Global.None;
            ProfileExManager.Instance.SetTestIpInfo(it.IndexId, ipStr);
            await UpdateIpInfoFunc(it.IndexId, ipStr);
        }
        else
        {
            await UpdateIpInfoFunc(it.IndexId, ResUI.SpeedtestingSkip);
        }

        return responseTime;
    }

    private async Task DoSpeedTest(DownloadService downloadHandle, ServerTestItem it)
    {
        await UpdateFunc(it.IndexId, "", ResUI.Speedtesting);

        var webProxy = new WebProxy($"socks5://{Global.Loopback}:{it.Port}");
        var url = _config.SpeedTestItem.SpeedTestUrl;
        var timeout = _config.SpeedTestItem.SpeedTestTimeout;
        await downloadHandle.DownloadDataAsync(url, webProxy, timeout, async (success, msg) =>
        {
            decimal.TryParse(msg, out var dec);
            if (dec > 0)
            {
                ProfileExManager.Instance.SetTestSpeed(it.IndexId, dec);
            }
            await UpdateFunc(it.IndexId, "", msg);
        });
    }

    private async Task<int> DoUdpTest(ServerTestItem it)
    {
        var udpService = UdpTestService.CreateFromTarget(_config?.SpeedTestItem.UdpTestTarget, out var udpTestUrl);
        var responseTime = -1;
        try
        {
            responseTime = (int)(await udpService.SendUdpRequestAsync(udpTestUrl, it.Port, TimeSpan.FromSeconds(5))).TotalMilliseconds;
        }
        catch
        {
            // ignored
        }

        ProfileExManager.Instance.SetTestDelay(it.IndexId, responseTime);
        await UpdateFunc(it.IndexId, responseTime.ToString());
        return responseTime;
    }

    private async Task<int> GetTcpingTime(string url, int port)
    {
        var responseTime = -1;

        if (!IPAddress.TryParse(url, out var ipAddress))
        {
            var ipHostInfo = await Dns.GetHostEntryAsync(url);
            ipAddress = ipHostInfo.AddressList.First();
        }

        IPEndPoint endPoint = new(ipAddress, port);
        using Socket clientSocket = new(endPoint.AddressFamily, SocketType.Stream, ProtocolType.Tcp);

        var timer = Stopwatch.StartNew();
        try
        {
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(5));
            await clientSocket.ConnectAsync(endPoint, cts.Token).ConfigureAwait(false);
            responseTime = (int)timer.ElapsedMilliseconds;
        }
        catch (OperationCanceledException)
        {
        }
        finally
        {
            timer.Stop();
        }
        return responseTime;
    }

    /// <summary>
    /// Режет список на пакеты по ядру: замер поднимает ОДНО ядро на пакет, поэтому смешивать их
    /// нельзя. Раньше здесь отбирались ровно два ядра — Xray и sing_box, — а узел с любым другим
    /// (v2fly, mihomo и т.п.) не попадал НИ В ОДИН пакет и молча исчезал из замера: строка навсегда
    /// оставалась без значения, и об этом никто не сообщал. Теперь группируем по всем ядрам, что
    /// реально встретились, сохраняя прежний порядок (сначала Xray, затем sing_box, затем остальные),
    /// — для списков из двух известных ядер поведение ровно прежнее.
    /// </summary>
    private List<List<ServerTestItem>> GetTestBatchItem(List<ServerTestItem> lstSelected, int pageSize)
    {
        List<List<ServerTestItem>> lstTest = [];
        var groups = lstSelected
            .GroupBy(t => t.CoreType)
            .OrderBy(g => g.Key switch
            {
                ECoreType.Xray => 0,
                ECoreType.sing_box => 1,
                _ => 2,
            });

        foreach (var group in groups)
        {
            var lst = group.ToList();
            for (var num = 0; num < (int)Math.Ceiling(lst.Count * 1.0 / pageSize); num++)
            {
                lstTest.Add(lst.Skip(num * pageSize).Take(pageSize).ToList());
            }
        }

        return lstTest;
    }

    private async Task UpdateFunc(string indexId, string delay, string speed = "")
    {
        await _updateFunc?.Invoke(new() { IndexId = indexId, Delay = delay, Speed = speed });
        if (indexId.IsNotEmpty() && speed.IsNotEmpty())
        {
            ProfileExManager.Instance.SetTestMessage(indexId, speed);
        }
    }

    private async Task UpdateIpInfoFunc(string indexId, string ip)
    {
        await _updateFunc?.Invoke(new() { IndexId = indexId, IpInfo = ip });
    }
}
