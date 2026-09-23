namespace ServiceLib.Services.Statistics;

public class StatisticsXrayService
{
    private const long linkBase = 1024;
    private ServerSpeedItem _serverSpeedItem = new();
    private readonly Config _config;
    private bool _exitFlag;
    private readonly Func<ServerSpeedItem, Task>? _updateFunc;
    private string Url => $"{Global.HttpProtocol}{Global.Loopback}:{AppManager.Instance.StatePort}/debug/vars";

    public StatisticsXrayService(Config config, Func<ServerSpeedItem, Task> updateFunc)
    {
        _config = config;
        _updateFunc = updateFunc;
        _exitFlag = false;

        _ = Task.Run(Run);
    }

    public void Close()
    {
        _exitFlag = true;
    }

    // idle/perf B2: cadence when Xray is the active core AND the window is visible (accurate 1 s speed);
    // idle/perf B2: back-off cadence when Xray is NOT the running core (disconnected / sing-box active)
    // so the loop stops waking every second for the whole app lifetime.
    private const int ActiveDelayMs = 1000;
    private const int IdleDelayMs = 5000;

    private async Task Run()
    {
        while (!_exitFlag)
        {
            // Choose the wait BEFORE sleeping: only sample at 1 s while Xray actually owns the tunnel;
            // otherwise idle at 5 s. This is the same guard the loop used to hit with `continue`, just
            // without a perpetual 1 s spin.
            var active = AppManager.Instance.RunningCoreType == ECoreType.Xray;
            await Task.Delay(active ? ActiveDelayMs : IdleDelayMs);
            if (_exitFlag)
            {
                break;
            }
            try
            {
                if (AppManager.Instance.RunningCoreType != ECoreType.Xray)
                {
                    continue;
                }

                // idle/perf B2+B5: when the window is hidden to tray OR minimized, the sample is
                // discarded by the publish guard anyway (MainWindowViewModel.UpdateStatisticsHandler),
                // so skip the HTTP GET + JSON parse entirely. The Xray /debug/vars counters are
                // cumulative, so on restore the next delta captures the whole hidden period — today's
                // traffic total stays accurate (only the on-screen speed widget was paused).
                if (AppManager.Instance.IsUiHidden)
                {
                    continue;
                }

                var result = await HttpClientHelper.Instance.TryGetAsync(Url);
                if (result != null)
                {
                    var server = ParseOutput(result) ?? new ServerSpeedItem();
                    await _updateFunc?.Invoke(server);
                }
            }
            catch
            {
                // ignored
            }
        }
    }

    private ServerSpeedItem? ParseOutput(string result)
    {
        try
        {
            var source = JsonUtils.Deserialize<V2rayMetricsVars>(result);
            if (source?.stats?.outbound == null)
            {
                return null;
            }

            ServerSpeedItem server = new();
            foreach (var key in source.stats.outbound.Keys.Cast<string>())
            {
                var value = source.stats.outbound[key];
                if (value == null)
                {
                    continue;
                }
                var state = JsonUtils.Deserialize<V2rayMetricsVarsLink>(value.ToString());

                if (key.StartsWith(Global.ProxyTag))
                {
                    server.ProxyUp += state.uplink / linkBase;
                    server.ProxyDown += state.downlink / linkBase;
                }
                else if (key == Global.DirectTag)
                {
                    server.DirectUp = state.uplink / linkBase;
                    server.DirectDown = state.downlink / linkBase;
                }
            }

            if (server.DirectDown < _serverSpeedItem.DirectDown || server.ProxyDown < _serverSpeedItem.ProxyDown)
            {
                _serverSpeedItem = new();
                return null;
            }

            ServerSpeedItem curItem = new()
            {
                ProxyUp = server.ProxyUp - _serverSpeedItem.ProxyUp,
                ProxyDown = server.ProxyDown - _serverSpeedItem.ProxyDown,
                DirectUp = server.DirectUp - _serverSpeedItem.DirectUp,
                DirectDown = server.DirectDown - _serverSpeedItem.DirectDown,
            };
            _serverSpeedItem = server;
            return curItem;
        }
        catch
        {
            // ignored
        }

        return null;
    }
}
