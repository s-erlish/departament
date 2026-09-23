namespace ServiceLib.ViewModels;

public class MsgViewModel : MyReactiveObject
{
    public Interaction<string, Unit> DispatcherShowMsgInteraction { get; } = new();

    private readonly ConcurrentQueue<string> _queueMsg = new();
    private volatile bool _lastMsgFilterNotAvailable;
    private int _showLock = 0; // 0 = unlocked, 1 = locked
    public int NumMaxMsg { get; } = 500;

    [Reactive]
    public string MsgFilter { get; set; }

    [Reactive]
    public bool AutoRefresh { get; set; }

    public MsgViewModel()
    {
        _config = AppManager.Instance.Config;
        MsgFilter = _config.MsgUIItem.MainMsgFilter ?? string.Empty;
        AutoRefresh = _config.MsgUIItem.AutoRefresh ?? true;

        this.WhenAnyValue(
           x => x.MsgFilter)
               .Subscribe(c => DoMsgFilter());

        this.WhenAnyValue(
          x => x.AutoRefresh,
          y => y == true)
              .Subscribe(c => _config.MsgUIItem.AutoRefresh = AutoRefresh);

        AppEvents.SendMsgViewRequested
         .AsObservable()
         //.ObserveOn(RxSchedulers.MainThreadScheduler)
         .Subscribe(content => _ = AppendQueueMsg(content));
    }

    private async Task AppendQueueMsg(string msg)
    {
        if (AutoRefresh == false)
        {
            return;
        }

        EnqueueQueueMsg(msg);

        // Idle guard (B5): pause the log pump whenever the UI is not visible. IsUiHidden covers
        // both hidden-to-tray (ShowInTaskbar == false) AND minimized — the queue is still filled
        // above (so nothing is lost), but the expensive dispatcher pump is skipped for a window the
        // user cannot see.
        if (AppManager.Instance.IsUiHidden)
        {
            return;
        }

        if (Interlocked.CompareExchange(ref _showLock, 1, 0) != 0)
        {
            return;
        }

        try
        {
            await Task.Delay(500).ConfigureAwait(false);

            var sb = new StringBuilder();
            while (_queueMsg.TryDequeue(out var line))
            {
                sb.Append(line);
            }

            try
            {
                await DispatcherShowMsgInteraction.Handle(sb.ToString());
            }
            catch (UnhandledInteractionException<string, Unit>)
            {
                // No handler is registered: this shell builds no MsgView on the Home/Account layout
                // (only sub-screens that host one register it). The batch has nowhere to render and is
                // dropped — same net effect as before — but WITHOUT the throw: it used to escape from
                // this fire-and-forget task as a TaskScheduler UnobservedTaskException on EVERY
                // subscription sync/update message (observed twice per cold start in guiLogs).
            }
        }
        finally
        {
            Interlocked.Exchange(ref _showLock, 0);
        }
    }

    private void EnqueueQueueMsg(string msg)
    {
        //filter msg
        if (MsgFilter.IsNotEmpty() && !_lastMsgFilterNotAvailable)
        {
            try
            {
                if (!Regex.IsMatch(msg, MsgFilter))
                {
                    return;
                }
            }
            catch (Exception ex)
            {
                EnqueueWithLimit(ex.Message);
                _lastMsgFilterNotAvailable = true;
            }
        }

        EnqueueWithLimit(msg);
        if (!msg.EndsWith(Environment.NewLine))
        {
            EnqueueWithLimit(Environment.NewLine);
        }
    }

    private void EnqueueWithLimit(string item)
    {
        _queueMsg.Enqueue(item);

        while (_queueMsg.Count > NumMaxMsg)
        {
            _queueMsg.TryDequeue(out _);
        }
    }

    //public void ClearMsg()
    //{
    //    _queueMsg.Clear();
    //}

    private void DoMsgFilter()
    {
        _config.MsgUIItem.MainMsgFilter = MsgFilter;
        _lastMsgFilterNotAvailable = false;
    }
}
