using v2rayN.Desktop.ViewModels;

namespace v2rayN.Desktop.Views;

/// <summary>
/// Compact (phone-like) Home — the single-scroll re-host of the SAME pieces the widescreen Home uses
/// (CA-3). It reuses <see cref="ConnectHeroView"/>, <see cref="HomeAccountChip"/> and
/// <see cref="ServerListView"/>, all bound to the ONE <see cref="HomeViewModel"/> the host supplies
/// via DataContext. The hero is wired through the shared <see cref="HomeHeroPresenter"/> so the
/// connect pipeline is identical to widescreen.
///
/// CRITICAL lifecycle note: the hero binding is (re)created on EVERY attach and torn down on detach —
/// mirroring the widescreen <see cref="HomeView"/>'s <c>WhenActivated</c>. Earlier this view bound
/// only in <c>DataContextChanged</c> (which fires once) yet disposed on detach, so after the first
/// tab-switch / layout-swap the compact hero was left dead: tapping the shield did nothing and the
/// connected state / speeds never showed. Rebinding on attach keeps the compact connect pipeline
/// live and identical to widescreen no matter how often the view is shown/hidden.
///
/// The reused <see cref="ConnectHeroView"/> carries the speed/uptime stats row UNDER the shield, so
/// this compact tree keeps no top stats row. There is NO «+» in compact at all — the header «+» was
/// removed (owner: the empty top-right «+» looked bad) and the hero's corner «+» is hidden here; the
/// add-subscription «+» lives ONLY in the widescreen connect panel corner.
/// </summary>
public partial class CompactHomeView : UserControl
{
    /// <summary>Account chip tapped — host should open the Account tab.</summary>
    public event EventHandler? AccountRequested;

    private IDisposable? _heroBinding;
    private bool _attached;

    // ── Scroll-offset preservation (tab switch / minimize→restore / app-switch) ─────────────────
    //  The page ScrollViewer resets its offset to 0 whenever the view is re-attached (tab return) or
    //  the window viewport collapses (minimize) and grows back. We cache the last user offset while
    //  visible and put it back once layout settles, so the list never jumps. _restoringScroll gates
    //  the cache so layout-driven churn (the reset to 0) never overwrites the saved value.
    private Vector _savedOffset;
    private bool _restoringScroll;
    private IDisposable? _offsetSub;
    private IDisposable? _winStateSub;
    private WindowState _lastWinState = WindowState.Normal;

    public CompactHomeView()
    {
        InitializeComponent();

        AccountChip.AccountRequested += (_, _) => AccountRequested?.Invoke(this, EventArgs.Empty);

        DataContextChanged += (_, _) => BindHero();
        AttachedToVisualTree += OnAttached;
        DetachedFromVisualTree += OnDetached;
    }

    private void OnAttached(object? sender, VisualTreeAttachmentEventArgs e)
    {
        _attached = true;

        //  Ignore the offset churn that attach/relayout produces until we have restored the saved
        //  position (set before wiring the offset observable so its priming emission is ignored too).
        _restoringScroll = true;

        //  Compact never shows the corner «+» (widescreen keeps it in the hero corner).
        ConnectHero.SetCornerAddVisible(false);

        //  Кольцо узкой раскладки — 190 / диск 138 / щит 64 (tokens.md «Кольцо подключения»).
        //  Пресет назначает раскладка-хозяин; здесь хозяин — дерево УЗКОЙ «Главной», и оно узкое ВСЕГДА
        //  (широкая и компактная показывают HomeView — см. MainWindow.ApplyHomeMetrics). Ставим на каждом attach:
        //  SetHeroSize идемпотентен (тот же пресет → выход без работы).
        ConnectHero.SetHeroSize(ConnectHeroView.HeroSize.Narrow);

        //  Rewire the shared connect pipeline every time compact Home is shown. Done SYNCHRONOUSLY so
        //  the connected state / speeds are correct on the very first frame (e.g. shrinking from a
        //  connected widescreen straight into compact — no idle→connected flash, no dead shield).
        BindHero();

        //  Cache scroll offset as the user scrolls (skip while minimized / restoring).
        _offsetSub = PageScroll.GetObservable(ScrollViewer.OffsetProperty).Subscribe(OnScrollOffsetChanged);

        //  Restore scroll after a minimize→restore (window kept attached, so no attach fires).
        if (TopLevel.GetTopLevel(this) is Window w)
        {
            _lastWinState = w.WindowState;
            _winStateSub = w.GetObservable(Window.WindowStateProperty).Subscribe(OnWindowStateChanged);
        }

        //  Returning to this tab: put the last known scroll position back once layout settles.
        RestoreScrollOffset();
    }

    private void OnDetached(object? sender, VisualTreeAttachmentEventArgs e)
    {
        _attached = false;
        DisposeBinding();

        _offsetSub?.Dispose();
        _offsetSub = null;
        _winStateSub?.Dispose();
        _winStateSub = null;
    }

    private void BindHero()
    {
        DisposeBinding();
        if (_attached && DataContext is HomeViewModel vm)
        {
            _heroBinding = HomeHeroPresenter.Bind(ConnectHero, vm);
        }
    }

    private void OnScrollOffsetChanged(Vector offset)
    {
        if (_restoringScroll)
        {
            return;
        }

        //  Don't cache the collapse-to-0 that a minimized window produces.
        if (TopLevel.GetTopLevel(this) is Window w && w.WindowState == WindowState.Minimized)
        {
            return;
        }

        _savedOffset = offset;
    }

    private void OnWindowStateChanged(WindowState state)
    {
        if (_lastWinState == WindowState.Minimized && state != WindowState.Minimized)
        {
            RestoreScrollOffset();
        }

        _lastWinState = state;
    }

    private void RestoreScrollOffset()
    {
        //  Guard synchronously so any interim offset changes (during restore/relayout) are ignored,
        //  then re-apply the saved offset once layout has run, and release the guard afterwards.
        _restoringScroll = true;
        var target = _savedOffset;
        Dispatcher.UIThread.Post(
            () =>
            {
                PageScroll.Offset = target;
                Dispatcher.UIThread.Post(() => _restoringScroll = false, DispatcherPriority.Background);
            },
            DispatcherPriority.Loaded);
    }

    private void DisposeBinding()
    {
        _heroBinding?.Dispose();
        _heroBinding = null;
    }
}
