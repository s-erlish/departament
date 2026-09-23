using System.Reactive.Disposables;
using v2rayN.Desktop.Common;
using v2rayN.Desktop.Converters;
using v2rayN.Desktop.ViewModels;

namespace v2rayN.Desktop.Views;

/// <summary>
/// Binds ONE <see cref="ConnectHeroView"/> instance to a <see cref="HomeViewModel"/> — the exact
/// «state ↔ view» wiring the widescreen <see cref="HomeView"/> always used, extracted so the compact
/// <see cref="CompactHomeView"/> reuses it verbatim (no drift, one connect pipeline). Both layouts
/// hold their OWN hero control but share the same VM, so connect/disconnect, speeds, uptime, the
/// selected server and the empty state are identical regardless of window width.
///
/// <see cref="Bind"/> returns an <see cref="IDisposable"/> bundling every subscription/handler; the
/// host disposes it on deactivation. Static, side-effect-free helpers — safe to call from either tree.
/// </summary>
internal sealed class HomeHeroPresenter
{
    private static readonly RemarkToFlagConverter _flag = new();

    private readonly ConnectHeroView _hero;
    private readonly HomeViewModel _vm;
    private ConnectHeroView.ConnectVisualState _lastState = ConnectHeroView.ConnectVisualState.Idle;

    // The FIRST ApplyConnectState after a (re)bind must JUMP to the VM's current connect state
    // (animate:false), never animate INTO it. On a compact↔widescreen layout swap the live (already
    // Connected) VM is rebound to the newly-active hero; without this the initial apply would read
    // _lastState==Idle, treat Connected as a fresh transition and re-fire the confirm sonar. Seeded
    // true so the newly-active hero shows the connected shield + running uptime + live speed with no
    // reset and no spurious pulse; real transitions after the bind animate normally.
    private bool _firstApply = true;

    private HomeHeroPresenter(ConnectHeroView hero, HomeViewModel vm)
    {
        _hero = hero;
        _vm = vm;
    }

    public static IDisposable Bind(ConnectHeroView hero, HomeViewModel vm)
    {
        var presenter = new HomeHeroPresenter(hero, vm);
        return presenter.Wire();
    }

    private IDisposable Wire()
    {
        var d = new CompositeDisposable();
        var hero = _hero;
        var vm = _vm;

        // ── Layout activation ──────────────────────────────────────────────
        //  This presenter binds the ACTIVE-layout hero. Lift the motion gate now (so the re-apply
        //  below re-attaches the state's loops) and, on unbind (layout goes inactive), stop them so
        //  the off-screen hero never keeps ticking the compositor. See ConnectHeroView.Deactivate.
        hero.Activate();
        Disposable.Create(() => hero.Deactivate()).DisposeWith(d);

        // ── Connect-hero events → VM commands ──────────────────────────────
        void OnToggle(object? s, EventArgs e) => vm.ConnectToggle();
        void OnAddQr(object? s, EventArgs e) => _ = vm.AddViaQr();
        void OnAddClip(object? s, EventArgs e) => _ = vm.AddViaClipboard();

        hero.ConnectToggleRequested += OnToggle;
        hero.AddByQrRequested += OnAddQr;
        hero.AddFromClipboardRequested += OnAddClip;
        Disposable.Create(() =>
        {
            hero.ConnectToggleRequested -= OnToggle;
            hero.AddByQrRequested -= OnAddQr;
            hero.AddFromClipboardRequested -= OnAddClip;
        }).DisposeWith(d);

        // ── Empty / onboarding: hero shows onboarding CTAs ─────────────────
        vm.WhenAnyValue(x => x.IsEmpty)
            .ObserveOn(RxSchedulers.MainThreadScheduler)
            .Subscribe(empty => hero.ShowEmptyState(empty))
            .DisposeWith(d);

        // ── Connect visual state (idle / connecting / connected / error) ───
        //  ConnectFailed is observed alongside the other connect signals so a failed attempt
        //  paints the Error shield on BOTH hero instances (each has its own presenter over the
        //  shared VM). The VM auto-clears ConnectFailed on the next attempt/success/disconnect,
        //  so ApplyConnectState just reads it — no manual reset here.
        vm.WhenAnyValue(x => x.IsConnected, x => x.IsConnecting, x => x.HasServers, x => x.ConnectFailed)
            .ObserveOn(RxSchedulers.MainThreadScheduler)
            .Subscribe(_ => ApplyConnectState())
            .DisposeWith(d);

        //  Причина отказа приезжает вместе с самим отказом (одна и та же попытка), но отдельным
        //  свойством — подписываемся отдельно, чтобы поздняя причина всё равно попала под щит.
        vm.WhenAnyValue(x => x.ConnectFailureHint)
            .ObserveOn(RxSchedulers.MainThreadScheduler)
            .Subscribe(hint => hero.SetErrorReason(hint))
            .DisposeWith(d);

        // ── Live speed + uptime ────────────────────────────────────────────
        vm.WhenAnyValue(x => x.UpSpeed, x => x.DownSpeed)
            .ObserveOn(RxSchedulers.MainThreadScheduler)
            .Subscribe(_ => hero.SetSpeeds(vm.UpSpeed, vm.DownSpeed))
            .DisposeWith(d);
        vm.WhenAnyValue(x => x.Uptime)
            .ObserveOn(RxSchedulers.MainThreadScheduler)
            .Subscribe(u => hero.SetUptime(u))
            .DisposeWith(d);

        // ── Active server identity under the shield ────────────────────────
        if (vm.Profiles is not null)
        {
            vm.Profiles.WhenAnyValue(x => x.SelectedProfile)
                .ObserveOn(RxSchedulers.MainThreadScheduler)
                .Subscribe(_ => ApplyServerInfo())
                .DisposeWith(d);

            // The active row is flagged during a list rebuild (IsActive), which may not move
            // SelectedProfile — re-resolve identity whenever the collection itself changes.
            void OnItemsChanged(object? s, System.Collections.Specialized.NotifyCollectionChangedEventArgs e) =>
                Dispatcher.UIThread.Post(ApplyServerInfo);
            vm.Profiles.ProfileItems.CollectionChanged += OnItemsChanged;
            Disposable.Create(() => vm.Profiles.ProfileItems.CollectionChanged -= OnItemsChanged)
                .DisposeWith(d);
        }

        hero.ShowEmptyState(vm.IsEmpty);
        hero.SetErrorReason(vm.ConnectFailureHint);
        ApplyConnectState();
        ApplyServerInfo();
        return d;
    }

    private void ApplyConnectState()
    {
        var vm = _vm;
        //  Error wins over idle: a failed connect leaves IsConnected/IsConnecting false, so without
        //  this guard the shield would silently fall back to Idle (the A4 bug). Connecting/Connected
        //  still take precedence — ConnectFailed is only latched while genuinely stopped.
        var state = vm.IsConnected
            ? ConnectHeroView.ConnectVisualState.Connected
            : vm.IsConnecting
                ? ConnectHeroView.ConnectVisualState.Connecting
                : vm.ConnectFailed
                    ? ConnectHeroView.ConnectVisualState.Error
                    : ConnectHeroView.ConnectVisualState.Idle;

        // Play the connect-confirm sonar only on a genuine transition INTO connected — never on the
        // first apply after a (re)bind, which must jump straight to the current end-state so a
        // layout swap while connected shows the connected shield + live uptime/speed with no reset
        // and no re-pulse (see _firstApply).
        var animate = !_firstApply
                      && state == ConnectHeroView.ConnectVisualState.Connected
                      && _lastState != ConnectHeroView.ConnectVisualState.Connected;

        _hero.SetConnectState(state, hasServer: vm.HasServers, animate: animate);
        _lastState = state;
        _firstApply = false;
    }

    private void ApplyServerInfo()
    {
        var vm = _vm;

        // Follow the ACTIVE / default server (the row that will actually connect), not the first
        // list row. The engine marks it IsActive (IndexId == _config.IndexId); fall back to the
        // config's IndexId, then to the selected row. Data-driven — read-only over ProfileItems.
        var p = ResolveActiveProfile(vm);
        if (p is null || p.IndexId.IsNullOrEmpty())
        {
            return;
        }

        var flag = _flag.Convert(p.Remarks, typeof(IImage), null, CultureInfo.CurrentCulture) as IImage;
        _hero.SetServerInfo(
            // Strip the leading flag emoji — the flag renders in its own tile, and Windows draws
            // emoji flags as tofu boxes otherwise.
            FlagResolver.StripLeadingFlag(p.Remarks) ?? string.Empty,
            ProfileDisplay.Protocol(p),
            ProfileDisplay.Transport(p.Network, p.StreamSecurity),
            flag);
    }

    private static ProfileItemModel? ResolveActiveProfile(HomeViewModel vm)
    {
        var items = vm.Profiles?.ProfileItems;
        if (items is null || items.Count == 0)
        {
            return vm.Profiles?.SelectedProfile;
        }

        var active = items.FirstOrDefault(t => t.IsActive);
        if (active is not null)
        {
            return active;
        }

        var indexId = AppManager.Instance.Config?.IndexId;
        if (indexId.IsNotEmpty())
        {
            var match = items.FirstOrDefault(t => t.IndexId == indexId);
            if (match is not null)
            {
                return match;
            }
        }

        return vm.Profiles?.SelectedProfile;
    }
}
