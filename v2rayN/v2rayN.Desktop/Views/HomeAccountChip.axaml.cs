using Avalonia.Animation;
using Avalonia.Animation.Easings;
using v2rayN.Desktop.Account;
using v2rayN.Desktop.Common;

namespace v2rayN.Desktop.Views;

/// <summary>
/// Account chip shared by the widescreen <see cref="HomeView"/> and the compact
/// <see cref="CompactHomeView"/> (CA-1). Self-manages its visibility and identity from the
/// read-only <see cref="AccountSession"/>: shown only when signed in, filled with the
/// «@handle» / display name / email and a monogram avatar. Tap / Enter / Space raises
/// <see cref="AccountRequested"/> — the host decides how to open the Account tab (rail vs
/// bottom nav) so this control stays layout-agnostic.
///
/// Phase 3: a loading SKELETON covers the window between «logged in» and «identity resolved»
/// (returning-user cold start, before the profile lands) instead of popping in fully-formed;
/// the chip is keyboard-reachable (Focusable + Enter/Space → the FocusAdorner ring already wired
/// for Border.AccountChip fires); and it ARRIVES on resolve (fade + 8px rise, ~120ms after Home
/// paints) rather than hard-cutting. Every motion has an instant fallback under
/// <see cref="MotionState.IsLite"/>.
/// </summary>
public partial class HomeAccountChip : UserControl
{
    /// <summary>Chip tapped / activated — host should open the Account tab.</summary>
    public event EventHandler? AccountRequested;

    private Action<AccountState>? _handler;
    private CancellationTokenSource? _entranceAnim;
    private bool _attached;
    private bool _shownResolved;   // true once the filled (resolved) row has been shown → entrance is one-shot

    public HomeAccountChip()
    {
        InitializeComponent();
        IsVisible = false;

        // Bind to the shared session while attached; drop the handler on detach so a swapped-out
        // (invisible) layout host does not leak or double-update.
        AttachedToVisualTree += OnAttached;
        DetachedFromVisualTree += OnDetached;
    }

    private void OnAttached(object? sender, VisualTreeAttachmentEventArgs e)
    {
        _attached = true;
        ApplyAccountState(AccountSession.State);
        _handler = state => Dispatcher.UIThread.Post(() => ApplyAccountState(state));
        AccountSession.StateChanged += _handler;
    }

    private void OnDetached(object? sender, VisualTreeAttachmentEventArgs e)
    {
        _attached = false;
        _entranceAnim?.Cancel();
        if (_handler is not null)
        {
            AccountSession.StateChanged -= _handler;
            _handler = null;
        }
    }

    // Data-driven from the shared session: shown only when logged in; while logged-in-but-unresolved
    // (name/avatar not yet known) → skeleton; once resolved → the filled row (which ARRIVES via the
    // entrance on its first appearance). Hidden when logged out. Never fabricated.
    private void ApplyAccountState(AccountState state)
    {
        if (state is AccountState.LoggedIn)
        {
            var name = AccountSession.DisplayName;
            if (name.IsNotEmpty())
            {
                AccountName.Text = name;
                AccountInitial.Text = AccountSession.AvatarInitial;
                ShowSkeleton(false);
                ChipRoot.IsHitTestVisible = true;
                ChipRoot.Focusable = true;
                IsVisible = true;
                if (!_shownResolved)
                {
                    // First time the account resolves (logged-out→in, or skeleton→resolved): the chip
                    // arrives. Subsequent profile refreshes / layout swaps must NOT re-trigger it.
                    _shownResolved = true;
                    MaybeRunEntrance();
                }
            }
            else
            {
                // Logged in but identity not resolved yet → skeleton chip (no fully-formed pop-in).
                // Not interactive (no half-loaded account to open) and not in the tab order until resolved.
                ShowSkeleton(true);
                ChipRoot.IsHitTestVisible = false;
                ChipRoot.Focusable = false;
                _shownResolved = false;
                IsVisible = true;
            }
        }
        else
        {
            // Logged out → hidden (Home's onboarding owns the signed-out CTA; no duplicate login affordance).
            _entranceAnim?.Cancel();
            _shownResolved = false;
            IsVisible = false;
            Opacity = 1;
            RenderTransform = null;
            ShowSkeleton(false);
            AccountName.Text = string.Empty;
            AccountInitial.Text = string.Empty;
        }
    }

    // Toggles the skeleton vs. the filled row. The SkeletonPulse class is added ONLY while the skeleton is
    // shown (removed otherwise) so the pulse never ticks off-screen; under .lite the global selector keeps
    // it static.
    private void ShowSkeleton(bool show)
    {
        if (show)
        {
            RealContent.IsVisible = false;
            SkeletonContent.IsVisible = true;
            if (!SkeletonContent.Classes.Contains("SkeletonPulse"))
            {
                SkeletonContent.Classes.Add("SkeletonPulse");
            }
        }
        else
        {
            SkeletonContent.Classes.Remove("SkeletonPulse");
            SkeletonContent.IsVisible = false;
            RealContent.IsVisible = true;
        }
    }

    // Entrance on resolve: проявление, отложенное на ~120мс — чип «приземляется» ПОСЛЕ того, как
    // Главная нарисовалась. Кривая та же, что у смены вкладки (Motion.Dur.Reveal / OutQuint).
    // Мгновенно под lite / когда чип вне дерева.
    //
    // ПОДЪЁМА 8px БОЛЬШЕ НЕТ, и это не упрощение, а исправление: он ехал через кадры
    // TranslateTransform.Y, а Avalonia сводит подсвойство трансформы к Visual.RenderTransform, на
    // который аниматора в этой сборке НЕТ — RunAsync каждый раз бросал
    // «No animator registered for the property RenderTransform». Здесь исключение глотал catch,
    // поэтому дефект был невидим: анимация «была» в коде и никогда не играла. Тот же промах жил в
    // стаггере строк ServerListView, где он к тому же засорял лог (снято на живом окне под Xvfb).
    private void MaybeRunEntrance()
    {
        if (!_attached || MotionState.IsLite)
        {
            Opacity = 1;
            RenderTransform = null;
            return;
        }
        _entranceAnim?.Cancel();
        var cts = new CancellationTokenSource();
        _entranceAnim = cts;
        _ = RunEntranceAsync(cts.Token);
    }

    private async Task RunEntranceAsync(CancellationToken ct)
    {
        Opacity = 0d;
        try { await Task.Delay(120, ct); }
        catch { return; }
        if (ct.IsCancellationRequested)
        {
            return;
        }
        try { await RunFade(this, 0d, 1d, Motion.Dur.Reveal, Motion.Ease.OutQuint, ct); }
        catch { }
        if (!ct.IsCancellationRequested)
        {
            Opacity = 1;
            RenderTransform = null;
        }
    }

    // Проявление (compositor-only). Opacity — единственное, что здесь анимируется: у него аниматор
    // есть, поэтому анимация действительно играет (см. комментарий у MaybeRunEntrance).
    private static Task RunFade(Visual target, double fromO, double toO, TimeSpan duration, Easing easing, CancellationToken ct)
    {
        var fade = new Animation
        {
            Duration = duration,
            Easing = easing,
            FillMode = FillMode.Forward,
            Children =
            {
                new KeyFrame { Cue = new Cue(0d), Setters = { new Avalonia.Styling.Setter(Visual.OpacityProperty, fromO) } },
                new KeyFrame { Cue = new Cue(1d), Setters = { new Avalonia.Styling.Setter(Visual.OpacityProperty, toO) } },
            },
        };
        return fade.RunAsync(target, ct);
    }

    private void OnChipKeyDown(object? sender, KeyEventArgs e)
    {
        // Keyboard activation parity with Tapped (a11y: the chip is a primary nav affordance). The
        // focus-visible ring is drawn automatically by the FocusAdorner on Border.AccountChip.
        if (e.Key is Key.Enter or Key.Space)
        {
            AccountRequested?.Invoke(this, EventArgs.Empty);
            e.Handled = true;
        }
    }

    private void OnChipTapped(object? sender, TappedEventArgs e) =>
        AccountRequested?.Invoke(this, EventArgs.Empty);

    private void OnChipPressed(object? sender, PointerPressedEventArgs e)
    {
        if (sender is Border b && !b.Classes.Contains("pressed"))
        {
            b.Classes.Add("pressed");
        }
    }

    private void OnChipReleased(object? sender, PointerReleasedEventArgs e)
    {
        if (sender is Border b)
        {
            b.Classes.Remove("pressed");
        }
    }

    private void OnChipExited(object? sender, PointerEventArgs e)
    {
        if (sender is Border b)
        {
            b.Classes.Remove("pressed");
        }
    }
}
