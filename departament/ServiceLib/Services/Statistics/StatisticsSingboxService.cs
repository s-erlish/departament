using System.Net.WebSockets;

namespace ServiceLib.Services.Statistics;

public class StatisticsSingboxService
{
    private readonly Config _config;
    private bool _exitFlag;
    private ClientWebSocket? webSocket;
    private readonly Func<ServerSpeedItem, Task>? _updateFunc;
    private string Url => $"ws://{Global.Loopback}:{AppManager.Instance.StatePort2}/traffic";
    private static readonly string _tag = "StatisticsSingboxService";

    public StatisticsSingboxService(Config config, Func<ServerSpeedItem, Task> updateFunc)
    {
        _config = config;
        _updateFunc = updateFunc;
        _exitFlag = false;

        _ = Task.Run(Run);
    }

    // idle/perf B2: retry cadence while sing-box is the active core (connect / reconnect the socket);
    // idle/perf B2: back-off cadence when sing-box is NOT running so the loop stops waking every
    // second (and holding a websocket) for the whole app lifetime while disconnected / Xray-active.
    private const int ActiveDelayMs = 1000;
    private const int IdleDelayMs = 5000;

    // Connect lazily, only once sing-box owns the tunnel (called from Run). On failure the socket is
    // dropped so the loop retries on a later tick — no perpetual reconnect spin while disconnected.
    private async Task Init()
    {
        try
        {
            webSocket?.Abort();
            webSocket = new ClientWebSocket();
            await webSocket.ConnectAsync(new Uri(Url), CancellationToken.None);
        }
        catch
        {
            try
            {
                webSocket?.Abort();
            }
            catch { }
            webSocket = null;
        }
    }

    public void Close()
    {
        try
        {
            _exitFlag = true;
            if (webSocket != null)
            {
                webSocket.Abort();
                webSocket = null;
            }
        }
        catch (Exception ex)
        {
            Logging.SaveLog(_tag, ex);
        }
    }

    private async Task Run()
    {
        while (!_exitFlag)
        {
            // Sample at 1 s only while sing-box actually owns the tunnel; otherwise idle at 5 s. The
            // websocket is created lazily (below) once the core is up, and dropped on disconnect.
            var active = AppManager.Instance.IsRunningCore(ECoreType.sing_box);
            await Task.Delay(active ? ActiveDelayMs : IdleDelayMs);
            if (_exitFlag)
            {
                break;
            }
            try
            {
                if (!AppManager.Instance.IsRunningCore(ECoreType.sing_box))
                {
                    continue;
                }

                // Lazily connect / reconnect only once sing-box is running.
                if (webSocket == null)
                {
                    await Init();
                    continue;
                }
                if (webSocket.State is WebSocketState.Aborted or WebSocketState.Closed)
                {
                    webSocket.Abort();
                    webSocket = null;
                    continue;
                }
                if (webSocket.State != WebSocketState.Open)
                {
                    continue;
                }

                //  Читаем и пока окно спрятано. sing-box шлёт замер каждую секунду, читают его или нет, и
                //  раньше, пока окно было в трее, непрочитанные копились в сокете: возвращение после
                //  долгого простоя начиналось с тысяч устаревших замеров разом, и каждый уходил на экран.
                //  Прочитанный в трее замер идёт только в итог за день (StatisticsManager), экран его
                //  отбрасывает (MainWindowViewModel.UpdateStatisticsHandler).
                var buffer = new byte[1024];
                var res = await webSocket.ReceiveAsync(new ArraySegment<byte>(buffer), CancellationToken.None);
                while (!res.CloseStatus.HasValue)
                {
                    var result = Encoding.UTF8.GetString(buffer, 0, res.Count);
                    if (result.IsNotEmpty())
                    {
                        ParseOutput(result, out var up, out var down);

                        //  В КиБ, как у Xray (linkBase = 1024): экран делит на 1024 и подписывает
                        //  «КБ/с». Делитель 1000 показывал скорость sing-box на 2,4% выше настоящей.
                        await _updateFunc?.Invoke(new ServerSpeedItem()
                        {
                            ProxyUp = (long)(up / 1024),
                            ProxyDown = (long)(down / 1024)
                        });
                    }
                    if (_exitFlag)
                    {
                        break;
                    }
                    res = await webSocket.ReceiveAsync(new ArraySegment<byte>(buffer), CancellationToken.None);
                }
            }
            catch
            {
            }
        }
    }

    private void ParseOutput(string source, out ulong up, out ulong down)
    {
        up = 0;
        down = 0;
        try
        {
            var trafficItem = JsonUtils.Deserialize<TrafficItem>(source);
            if (trafficItem != null)
            {
                up = trafficItem.Up;
                down = trafficItem.Down;
            }
        }
        catch
        {
        }
    }
}
