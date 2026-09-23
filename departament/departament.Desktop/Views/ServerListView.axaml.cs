using System.ComponentModel;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using Avalonia.Animation;
using Avalonia.Animation.Easings;
using Avalonia.Data.Converters;
using Avalonia.Media.Transformation;
using Avalonia.VisualTree;
using DialogHostAvalonia;
using departament.Desktop.Common;
using departament.Desktop.ViewModels;

namespace departament.Desktop.Views;

/// <summary>
/// The unified left-column list of Home: one collapsible section per subscription, each headed by its
/// rich meta-bar (<see cref="SubscriptionMetaView"/> = the section header) flowing directly into its
/// server rows. There is NO separate «Сервера» header / toolbar / top-right «+» — those belonged to
/// the standalone Servers tab (which desktop has no rail entry for); the subscription meta-bar's own
/// actions + its «+» are the single source of add / refresh / ping / pin / collapse (owner demand).
///
/// DATA-DRIVEN: the runtime DataContext is the real <see cref="HomeViewModel"/> (inherited from
/// <see cref="HomeView"/>), so sections bind to real <c>ProfileItemModel</c>s grouped by subscription:
///   name ← Remarks (StripLeadingFlag), protocol ← ConfigType (ConfigTypeToProtocol), transport ←
///   Network·StreamSecurity (ProfileTransport), ping ← DelayVal, selected ← IsActive. Sample rows
///   exist ONLY at design time (DesignData.Home).
///
/// Interactions: a row tap selects + connects the server; collapse/pin/refresh/ping/add live on the
/// per-section meta-bar; a right-click context menu exposes the §2.13 server actions (make default /
/// ping / edit / duplicate / share / delete) against the shared <see cref="ProfilesViewModel"/>.
/// Because that VM raises its confirm / share / clipboard results through ReactiveUI interactions
/// (normally handled by ProfilesView, which is not in this two-panel Home), those three interaction
/// handlers are registered here so the actions work.
/// </summary>
public partial class ServerListView : UserControl
{
    private static readonly string _tag = "ServerListView";
    private readonly List<IDisposable> _interactionHandlers = new();

    // The row a context menu was opened on (captured on ContextRequested, before the menu shows) —
    // robust across Avalonia versions vs. relying on the MenuItem's inherited DataContext.
    private ProfileItemModel? _actionTarget;

    public ServerListView()
    {
        InitializeComponent();
        // NOTE: no runtime DataContext here — it inherits the real HomeViewModel from HomeView.
        // The XAML Design.DataContext (DesignData.Home) only feeds the previewer.
        DataContextChanged += (_, _) =>
        {
            RegisterInteractions();
            // Re-arm the one-shot list-reveal stagger only when a genuinely NEW view-model is bound
            // (identity change), so the reveal plays once per bind — never again on scroll/refresh
            // (those keep the same VM instance and only mutate its ServerGroups collection).
            if (!ReferenceEquals(DataContext, _revealBoundContext))
            {
                _revealBoundContext = DataContext;
                // G1: the reveal is one-shot PER VIEW-MODEL, not per view instance. Leaving Home and
                // returning tears down + re-creates this view (or re-attaches it), which used to reset
                // these instance fields and REPLAY the stagger on every show. The static weak table
                // remembers which VM instances have already played, so a re-shown / re-attached list
                // stays settled; only a genuinely new VM (e.g. after re-login) plays once more.
                var alreadyRevealed = DataContext is { } ctx && _revealedContexts.TryGetValue(ctx, out _);
                _revealStarted = alreadyRevealed;
                _revealFinished = alreadyRevealed;
                }
        };
    }

    #region Server-action interaction handlers (mirror ProfilesView, so share/delete work here)

    protected override void OnAttachedToVisualTree(VisualTreeAttachmentEventArgs e)
    {
        base.OnAttachedToVisualTree(e);
        RegisterInteractions();
    }

    protected override void OnDetachedFromVisualTree(VisualTreeAttachmentEventArgs e)
    {
        base.OnDetachedFromVisualTree(e);
        foreach (var handler in _interactionHandlers)
        {
            handler.Dispose();
        }
        _interactionHandlers.Clear();
    }

    // Registered once against the shared ProfilesViewModel. Idempotent; disposed on detach.
    private void RegisterInteractions()
    {
        if (_interactionHandlers.Count > 0)
        {
            return;
        }
        if (DataContext is not HomeViewModel { Profiles: { } profiles })
        {
            return;
        }

        // Delete confirmation ("Удалить сервер?" → yes/no).
        _interactionHandlers.Add(profiles.ShowYesNoInteraction.RegisterHandler(async interaction =>
        {
            var result = await UI.ShowYesNo(interaction.Input);
            interaction.SetOutput(result == ButtonResult.Yes);
        }));

        // Share via QR-code (dialog hosted by MainWindow's DialogHost).
        _interactionHandlers.Add(profiles.ShareServerInteraction.RegisterHandler(async interaction =>
        {
            var url = interaction.Input;
            if (url.IsNotEmpty())
            {
                try
                {
                    await DialogHost.Show(new QrcodeView(url));
                }
                catch (Exception ex)
                {
                    Logging.SaveLog(_tag, ex);
                }
            }
            interaction.SetOutput(Unit.Default);
        }));

        // Share via clipboard (copy share-url / export).
        _interactionHandlers.Add(profiles.SetClipboardDataInteraction.RegisterHandler(async interaction =>
        {
            await AvaUtils.SetClipboardData(this, interaction.Input);
            interaction.SetOutput(Unit.Default);
        }));
    }

    #endregion Server-action interaction handlers

    #region Row selection

    //  Manual press/release selection (NOT Tapped): the rows live inside a ScrollViewer, and
    //  Avalonia cancels the Tapped gesture on the slightest pointer movement / scroll-drag, so
    //  a click frequently never selected. Mirroring the ConnectHero disc (_pressing flag), we
    //  track press → release on the row Border itself: on press we remember the target row; on
    //  release, if we are still pressing AND the pointer is still over that same row, we select.
    //  A drag that starts a scroll makes the ScrollViewer capture the pointer, which fires
    //  PointerCaptureLost on the Border and cancels the press — so a scroll-drag never selects.
    private bool _rowPressing;
    private ProfileItemModel? _rowPressTarget;

    // Row press feedback: subtle scale (Border.ServerRow.pressed), no ripple/glow (§0.6), and
    // arm selection for the row under the pointer (left button only).
    private void OnRowPointerPressed(object? sender, PointerPressedEventArgs e)
    {
        if (sender is not Border b)
        {
            return;
        }

        if (!b.Classes.Contains("pressed"))
        {
            b.Classes.Add("pressed");
        }

        if (e.GetCurrentPoint(b).Properties.IsLeftButtonPressed && b.DataContext is ProfileItemModel item)
        {
            _rowPressing = true;
            _rowPressTarget = item;
        }
        else
        {
            ClearRowPress();
        }
    }

    // Server row select + connect (make default server → engine reloads the core). Fires only
    // when the press completes over the same row it started on — reliable inside the ScrollViewer.
    private void OnRowPointerReleased(object? sender, PointerReleasedEventArgs e)
    {
        if (sender is not Border b)
        {
            return;
        }

        b.Classes.Remove("pressed");

        if (_rowPressing
            && e.InitialPressMouseButton == MouseButton.Left
            && _rowPressTarget is { } item
            && b.IsPointerOver
            && DataContext is HomeViewModel vm)
        {
            _ = vm.SelectServer(item.IndexId);
        }

        ClearRowPress();
    }

    private void OnRowPointerExited(object? sender, PointerEventArgs e)
    {
        if (sender is Border b)
        {
            b.Classes.Remove("pressed");
        }
        ClearRowPress();
    }

    // Scroll-drag steals pointer capture → cancel the pending selection (no accidental switch).
    private void OnRowPointerCaptureLost(object? sender, PointerCaptureLostEventArgs e)
    {
        if (sender is Border b)
        {
            b.Classes.Remove("pressed");
        }
        ClearRowPress();
    }

    private void ClearRowPress()
    {
        _rowPressing = false;
        _rowPressTarget = null;
    }

    /// <summary>
    /// Волосяной разделитель есть у каждой строки, КРОМЕ первой в группе (tokens.md «Строка сервера»).
    /// «Первая» вычисляется здесь, а не биндингом: контейнеры виртуализируются и переиспользуются,
    /// поэтому позицию надо пересчитывать и на реализации (Loaded), и на смене данных в уже живом
    /// контейнере (DataContextChanged) — иначе переработанная строка унесла бы чужой признак.
    ///
    /// Линия ГАСИТСЯ ПРОЗРАЧНОСТЬЮ, а не видимостью: её высота 1 остаётся в раскладке всегда,
    /// поэтому ни первая строка, ни выбранная не сдвигают список (приёмка «не смещается при выборе»).
    /// </summary>
    //  Loaded даёт EventHandler<RoutedEventArgs>, DataContextChanged — обычный EventHandler:
    //  две тонкие обёртки над одной логикой, чтобы XAML-компилятор видел точные сигнатуры.
    private void OnRowHairlineSync(object? sender, RoutedEventArgs e) => SyncHairline(sender);

    private void OnRowHairlineRebound(object? sender, EventArgs e) => SyncHairline(sender);

    private static void SyncHairline(object? sender)
    {
        if (sender is not Border hairline)
        {
            return;
        }

        var first = false;
        if (hairline.DataContext is { } item
            && hairline.FindAncestorOfType<ItemsControl>()?.ItemsSource is System.Collections.IList list
            && list.Count > 0)
        {
            first = ReferenceEquals(list[0], item);
        }

        hairline.Classes.Set("first", first);
    }

    #endregion Row selection

    #region List-reveal stagger (§A.4 — first-population only; Lite / reduced-motion disables it)

    //  Android parity: on the FIRST population of the server list the first ≤8 rows RISE
    //  (translateY 12→0) + fade (0→1) with a per-row delay of index×40ms over ~300ms OutQuint;
    //  rows beyond the first 8 just appear. This is a one-shot per bind — refresh / collapse /
    //  scroll re-run Loaded on fresh containers but are suppressed by _revealFinished.
    //
    //  ROBUSTNESS: rows are visible by DEFAULT (their XAML rest is Opacity 1, no transform). The
    //  reveal only ENHANCES that — it sets the transient hidden start state as part of actually
    //  running, and a safety timer + finally ALWAYS restore rest, so a row can never be stranded
    //  hidden if the animation clock never ticks (headless / inactive render).

    private const double RevealMs = 300; //  Dur.Reveal (rise = translateY 12 → 0, see _riseFrom)

    private object? _revealBoundContext;
    private bool _revealStarted; //  first-population window has opened (rows attaching)
    private bool _revealFinished; //  first population done → no further reveals this bind

    //  G1: which HomeViewModel instances have already played their one-shot reveal. STATIC so the
    //  guard survives this view being torn down / re-created / re-attached when the Home tab is
    //  re-shown (the source of the residual replay). Weak keys → a discarded VM is collected and a
    //  genuinely new VM (re-login) reveals once more; harmless if the view instance is reused.
    private static readonly ConditionalWeakTable<object, object> _revealedContexts = new();

    //  Latch the current VM as "revealed" so a later re-show / re-attach can't replay the stagger.
    private void MarkContextRevealed()
    {
        if (DataContext is { } ctx)
        {
            _revealedContexts.AddOrUpdate(ctx, ctx);
        }
    }

    // Each server row raises Loaded when its container is realized. On the FIRST population we
    // stagger the first ≤8 rows in; every later realization (refresh / re-expand) is skipped so
    // rows simply appear at their visible rest.
    private void OnServerRowLoaded(object? sender, RoutedEventArgs e)
    {
        if (sender is not Border row)
        {
            return;
        }

        // Previewer: never animate — the design surface shows the final rest frame.
        if (Design.IsDesignMode)
        {
            return;
        }

        // One-shot: refresh / collapse / scroll re-run Loaded on new containers → leave them at
        // their visible XAML rest (no stagger, no flash).
        if (_revealFinished)
        {
            return;
        }

        // Lite / reduced-motion: no stagger. Rows are visible by default, so nothing to do but
        // close the window so no row in this population animates. Latch the VM so re-enabling
        // motion + re-showing later doesn't retro-stagger an already-populated list.
        if (IsReducedMotion())
        {
            _revealFinished = true;
            MarkContextRevealed();
            return;
        }

        if (!_revealStarted)
        {
            _revealStarted = true;
            // Latch this VM as revealed so re-showing / re-attaching Home never replays the stagger (G1).
            MarkContextRevealed();
            // Close the first-population window once this layout batch drains. Loaded callbacks run
            // at a higher dispatcher priority than Background, so every row realized in this pass is
            // indexed before the window closes; later (refresh) rows fall under _revealFinished.
            Dispatcher.UIThread.Post(() => _revealFinished = true, DispatcherPriority.Background);
        }

        //  СТАГГЕРА БОЛЬШЕ НЕТ. Строки проявлялись по очереди (i×40мс), и владелец читал это как
        //  моргание: «когда сервера появляются, они почему-то моргают и перестают». Ровно так оно и
        //  выглядит — часть строк уже видна, часть ещё нет, а после виртуализации, снятой ради
        //  корректного отображения, все строки создаются одним заходом, и разнобой стал заметнее.
        //  Список появляется целиком: моргать нечему. Задел на возврат движения оставлен в PlayRowReveal.
        _ = row;
    }

    //  ПОДЪЁМ СНЯТ — остался стаггер по прозрачности. Почему: кадры ставили Visual.RenderTransform
    //  (сначала значением TransformOperations, потом через TranslateTransform.Y — Avalonia сводит
    //  подсвойство трансформы к тому же RenderTransform), а аниматора на RenderTransform в этой
    //  сборке НЕТ: каждая строка роняла из RunAsync
    //  «No animator registered for the property RenderTransform». Вызов fire-and-forget, поэтому
    //  исключение уходило в UnobservedTaskException — на живом окне под Xvfb одна заливка списка
    //  давала ровно 8 таких ошибок (по числу стаггер-строк), а подъём НЕ проигрывался НИ РАЗУ:
    //  finally тут же возвращал строку в покой. То есть код обещал движение, которого не было.
    //
    //  Осталась честная версия того же приёма: строки проявляются по очереди (i×40мс, ~300мс
    //  OutQuint) — тот же ритм, без единого исключения. Смещение можно вернуть только через
    //  трансформу, которую переход стиля Border.ServerRow (TransformOperationsTransition на
    //  RenderTransform) не перехватывает; это отдельное решение владельца — вынесено в отчёт.


    // Reduced-motion decision — same signal the rest of the app gates on (ConnectHeroView): the
    // live `.lite` window class (set from UiItem.LiteMode), the persisted LiteMode flag, and the
    // Windows "show animations" system preference. Any one true → rows appear instantly.
    private bool IsReducedMotion()
    {
        if (Design.IsDesignMode)
        {
            return false;
        }

        // Live lite state: MainWindow carries the `.lite` class whenever LiteMode is on.
        if (TopLevel.GetTopLevel(this) is { } top && top.Classes.Contains("lite"))
        {
            return true;
        }

        try
        {
            if (AppManager.Instance.Config.UiItem.LiteMode)
            {
                return true;
            }
        }
        catch
        {
            //  Config not ready → treat motion as enabled.
        }

        return !SystemAnimationsEnabled();
    }

    private const uint SPI_GETCLIENTAREAANIMATION = 0x1042;

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SystemParametersInfo(uint uiAction, uint uiParam, ref bool pvParam, uint fWinIni);

    private static bool SystemAnimationsEnabled()
    {
        //  No direct signal off Windows → assume motion is enabled.
        if (!OperatingSystem.IsWindows())
        {
            return true;
        }

        try
        {
            var enabled = true;
            return !SystemParametersInfo(SPI_GETCLIENTAREAANIMATION, 0, ref enabled, 0) || enabled;
        }
        catch
        {
            return true;
        }
    }

    #endregion List-reveal stagger

    #region Group expand/collapse reveal (Transitions-driven height+opacity accordion; instant under lite)

    //  The meta-bar chevron flips HomeServerGroup.IsExpanded. Each group's rows live in a clipped
    //  reveal Border, and THIS is the container we open/close.
    //
    //  WHY THE OLD REVEAL SNAPPED (root cause of the persistent bug): it drove the motion with
    //  `Animation.RunAsync` keyframes on the container's layout `Height` (0↔measured) plus a fade on the
    //  inner rows. In this window that keyframe-Animation-on-a-layout-Height path did NOT interpolate
    //  visibly, and the rest states made it worse: collapsed rest was `IsVisible=false` (rows un-hosted)
    //  and expanded rest was `Height=NaN` (auto) — and NOTHING (neither a keyframe Animation nor a
    //  transition) can interpolate to/from `Auto`/NaN. A single out-of-cycle `Measure` right after the
    //  container flips visible could also resolve target≤0, dropping into the `animateHeight=false`
    //  branch (or the `finally` slamming `Height=NaN`). Net result BOTH ways: the container jumped to
    //  full height / 0 in one layout frame — an instant SNAP — while only a 12px inner fade played.
    //
    //  WHY THIS NOW ANIMATES: we switch to the SAME primitive that already animates flawlessly in this
    //  exact window — Avalonia `Transitions` + `DoubleTransition` (the chevron's rotate uses precisely
    //  this). The reveal Border carries a `DoubleTransition` on Height AND Opacity; a toggle just SETS
    //  the target values and the transition system interpolates ~300ms OutQuint. The classic accordion
    //  gotchas are handled explicitly: we never transition to/from `Auto` — we prime a CONCRETE start
    //  height with the transition detached (instant), re-attach the transition, then set the concrete
    //  target so the change animates; after the motion settles we drop back to `Auto` (expanded) or 0 +
    //  hidden (collapsed) with the transition detached (imperceptible, content already equals target).
    //  ClipToBounds on the Border makes the growing/shrinking height visibly clip the rows.
    //
    //  Under lite / reduced-motion the toggle is INSTANT (same gate as the list-reveal stagger, and the
    //  same one the chevron transition honours). Rows stay UN-hosted while collapsed (IsVisible=false at
    //  rest), so a collapsed group never realizes its rows — preserving the one-shot list-reveal stagger
    //  (it keys off row Loaded, which only fires for expanded groups). The outer groups ItemsControl uses
    //  a non-virtualizing StackPanel, so each reveal container is stable; we hook its group on Loaded,
    //  unhook on Unloaded, and a per-container generation counter voids a superseded settle on re-toggle.

    private const double RevealSlideMs = 300; //  deliberate reveal — OutQuint, both directions.

    private readonly Dictionary<Border, (HomeServerGroup group, PropertyChangedEventHandler handler)> _revealHooks = new();
    //  Per-container animation generation: every toggle bumps it, so the delayed "settle to rest"
    //  callback of a superseded toggle sees a mismatch and no-ops (rapid re-toggle safety).
    private readonly Dictionary<Border, int> _revealGen = new();

    //  Build a fresh Transitions collection per container (a Transitions instance is owned by one
    //  control, never shared). Height + Opacity both ride the OutQuint curve used across the app.
    private static Transitions BuildRevealTransitions() => new()
    {
        new DoubleTransition
        {
            Property = Control.HeightProperty,
            Duration = TimeSpan.FromMilliseconds(RevealSlideMs),
            Easing = new SplineEasing { X1 = 0.22, Y1 = 1, X2 = 0.36, Y2 = 1 },
        },
        new DoubleTransition
        {
            Property = Visual.OpacityProperty,
            Duration = TimeSpan.FromMilliseconds(RevealSlideMs),
            Easing = new SplineEasing { X1 = 0.22, Y1 = 1, X2 = 0.36, Y2 = 1 },
        },
    };

    // Hook the group behind this reveal container and set its rest state (no animation on first bind).
    private void OnGroupRevealLoaded(object? sender, RoutedEventArgs e)
    {
        if (sender is not Border reveal || reveal.DataContext is not HomeServerGroup group)
        {
            return;
        }

        // Already hooked to the SAME group (Loaded can re-fire) → just re-assert rest, don't double-subscribe.
        if (_revealHooks.TryGetValue(reveal, out var existing))
        {
            if (ReferenceEquals(existing.group, group))
            {
                ApplyRevealState(reveal, group.IsExpanded);
                return;
            }
            existing.group.PropertyChanged -= existing.handler;
            _revealHooks.Remove(reveal);
        }

        void Handler(object? _, PropertyChangedEventArgs args)
        {
            if (args.PropertyName == nameof(HomeServerGroup.IsExpanded))
            {
                AnimateReveal(reveal, group.IsExpanded);
            }
        }

        group.PropertyChanged += Handler;
        _revealHooks[reveal] = (group, Handler);
        ApplyRevealState(reveal, group.IsExpanded);
    }

    private void OnGroupRevealUnloaded(object? sender, RoutedEventArgs e)
    {
        if (sender is not Border reveal)
        {
            return;
        }
        if (_revealHooks.TryGetValue(reveal, out var hook))
        {
            hook.group.PropertyChanged -= hook.handler;
            _revealHooks.Remove(reveal);
        }
        //  Void any pending settle callback and detach transitions so a torn-down container is inert.
        _revealGen.Remove(reveal);
        reveal.Transitions = null;
    }

    // Instant rest state (NO transition): expanded → auto height, visible; collapsed → height 0, hidden
    // (rows un-hosted). Detaching Transitions first guarantees these sets never animate.
    private static void ApplyRevealState(Border reveal, bool expanded)
    {
        reveal.Transitions = null;

        if (expanded)
        {
            reveal.IsVisible = true;
            reveal.Height = double.NaN; //  auto — rows can reflow (ping/rename) with no fixed clip.
            reveal.Opacity = 1;
        }
        else
        {
            reveal.Height = 0;
            reveal.Opacity = 0;
            reveal.IsVisible = false; //  un-host the rows so a collapsed group never realizes them.
        }
    }

    // Set a CONCRETE, instant (un-transitioned) state on the container — used to prime the transition's
    // "from" value so the subsequent target set has a real number to interpolate FROM (never Auto/NaN).
    private static void SetRevealInstant(Border reveal, double height, double opacity)
    {
        reveal.Transitions = null;
        reveal.IsVisible = true;
        reveal.Height = height;
        reveal.Opacity = opacity;
    }

    //  Transitions-driven accordion. Both directions resolve a CONCRETE height and animate Height +
    //  Opacity; after the motion we settle to the proper rest state. Instant under lite / reduced-motion
    //  or when the container can't be measured/rooted. Fired synchronously off HomeServerGroup.IsExpanded
    //  (no awaits — the delayed settle rides a DispatcherTimer), so the whole body is wrapped: a fault
    //  forces the correct instant rest state and is logged, never crashing the app.
    private void AnimateReveal(Border reveal, bool expand)
    {
        try
        {
            //  Bump the generation FIRST so any in-flight settle callback from a prior toggle no-ops.
            var gen = (_revealGen.TryGetValue(reveal, out var g) ? g : 0) + 1;
            _revealGen[reveal] = gen;

            //  Instant path: lite / reduced-motion, no inner content, or detached (no clock).
            if (IsReducedMotion() || reveal.Child is not Control || TopLevel.GetTopLevel(reveal) is null)
            {
                ApplyRevealState(reveal, expand);
                return;
            }

            if (expand)
            {
                //  Measure the natural (auto-height) content at the current column width.
                reveal.IsVisible = true;
                var target = MeasureRevealHeight(reveal);
                if (target <= 0)
                {
                    //  Genuinely unmeasurable → show at auto height (no crash, no snap-to-zero). Rare.
                    ApplyRevealState(reveal, true);
                    return;
                }

                //  Prime the "from" (closed + transparent) with the transition DETACHED so it's instant,
                //  then re-attach and set the target — that change is what the transition interpolates.
                SetRevealInstant(reveal, height: 0, opacity: 0);
                reveal.Transitions = BuildRevealTransitions();
                reveal.Height = target; //  animates 0 → measured height (clipped by ClipToBounds)
                reveal.Opacity = 1;     //  animates 0 → 1 in step

                //  After the motion, release the fixed pixel height back to Auto so rows can reflow.
                //  Content already equals `target`, so Auto is visually identical — no jump. Guard on the
                //  generation so a newer toggle owns the container instead.
                ScheduleRevealSettle(reveal, gen, expanded: true);
            }
            else
            {
                //  Concrete start height from the live bounds (rendered), else a fresh measure.
                var current = reveal.Bounds.Height;
                if (current <= 0)
                {
                    current = MeasureRevealHeight(reveal);
                }
                if (current <= 0)
                {
                    ApplyRevealState(reveal, false);
                    return;
                }

                //  Prime the concrete "from" instantly (out of Auto), then animate down to 0 + fade out.
                SetRevealInstant(reveal, height: current, opacity: 1);
                reveal.Transitions = BuildRevealTransitions();
                reveal.Height = 0;   //  animates current → 0
                reveal.Opacity = 0;  //  animates 1 → 0 in step

                ScheduleRevealSettle(reveal, gen, expanded: false);
            }
        }
        catch (Exception ex)
        {
            //  Last line of defence — never let a reveal fault escape; force the correct instant rest.
            Logging.SaveLog(_tag, ex);
            try
            {
                ApplyRevealState(reveal, expand);
            }
            catch
            {
                //  Even the instant fallback can't be allowed to escape.
            }
        }
    }

    //  Once the transition has run (duration + small margin), settle the container to its true rest
    //  state — but ONLY if THIS toggle is still the current one (generation match); a newer toggle owns
    //  the container otherwise. Detaching the transition inside ApplyRevealState makes the settle instant
    //  (expanded → Auto height; collapsed → 0 + hidden), so no fixed pixel Height is ever stranded.
    private void ScheduleRevealSettle(Border reveal, int gen, bool expanded)
    {
        DispatcherTimer.RunOnce(
            () =>
            {
                try
                {
                    if (_revealGen.TryGetValue(reveal, out var cur) && cur == gen)
                    {
                        ApplyRevealState(reveal, expanded);
                    }
                }
                catch (Exception ex)
                {
                    Logging.SaveLog(_tag, ex);
                }
            },
            TimeSpan.FromMilliseconds(RevealSlideMs + 80));
    }

    // Natural height of the reveal's rows at the current column width (measured with Height cleared).
    // Defensive: a manual Measure on a detached / mid-layout element can throw — any failure returns 0,
    // which the caller reads as "use auto height", so expand still works, just without a pixel-tween.
    private static double MeasureRevealHeight(Border reveal)
    {
        try
        {
            double width = 0;
            if (reveal.GetVisualParent() is Control parent && parent.Bounds.Width > 0)
            {
                width = parent.Bounds.Width;
            }
            else if (reveal.Bounds.Width > 0)
            {
                width = reveal.Bounds.Width;
            }

            reveal.Height = double.NaN;
            reveal.Measure(new Size(width > 0 ? width : double.PositiveInfinity, double.PositiveInfinity));
            return reveal.DesiredSize.Height;
        }
        catch (Exception ex)
        {
            Logging.SaveLog(_tag, ex);
            return 0;
        }
    }

    #endregion Group expand/collapse reveal

    #region Server-row context actions (§2.13)

    // Capture the right-clicked row before its context menu opens.
    private void OnRowContextRequested(object? sender, ContextRequestedEventArgs e)
    {
        if (sender is Control { DataContext: ProfileItemModel item })
        {
            _actionTarget = item;
        }
    }

    // Point the shared VM's selection at the captured row, then return the VM so an action can run.
    private ProfilesViewModel? SelectTargetRow()
    {
        if (_actionTarget is { } item && DataContext is HomeViewModel { Profiles: { } profiles })
        {
            profiles.SelectedProfile = item;
            profiles.SelectedProfiles = new List<ProfileItemModel> { item };
            return profiles;
        }
        return null;
    }

    private void OnRowMakeDefault(object? sender, RoutedEventArgs e)
    {
        if (_actionTarget is { } item && DataContext is HomeViewModel { Profiles: { } profiles })
        {
            UpdateReconnect.NoteUserAction();
            _ = profiles.SetDefaultServer(item.IndexId);
        }
    }

    private void OnRowPing(object? sender, RoutedEventArgs e)
    {
        if (SelectTargetRow() is { } profiles)
        {
            _ = profiles.ServerSpeedtest(ResolvePingAction());
        }
    }

    // Map the user-selected ping method (Настройки → Пинг → SpeedTestItem.PingMethod) to the engine
    // probe. A8(b): the core's ESpeedActionType has NO Httping/Icmping probe — those two are not
    // implementable without a core change, so they are dead options. This resolver therefore only
    // ever yields the two working probes (Tcping / Realping); any other persisted value (incl. a
    // stale «Httping»/«Icmping» left by an earlier build) safely falls back to Realping so ping still
    // works. The dead HTTP/ICMP rows must be removed from the picker — see PingSettingsPage.axaml
    // (RowHttp/RowIcmp), which this wave does not own; flagged for the settings owner.
    private static ESpeedActionType ResolvePingAction()
        => AppManager.Instance.Config.SpeedTestItem.PingMethod == "Tcping"
            ? ESpeedActionType.Tcping
            : ESpeedActionType.Realping;

    private void OnRowEdit(object? sender, RoutedEventArgs e)
    {
        if (SelectTargetRow() is { } profiles)
        {
            _ = profiles.EditServerAsync();
        }
    }

    private void OnRowDuplicate(object? sender, RoutedEventArgs e)
    {
        // CopyServer is private on the VM; go through the command (selection sets its canExecute).
        if (SelectTargetRow() is { } profiles)
        {
            profiles.CopyServerCmd.Execute().Subscribe(static _ => { }, static _ => { });
        }
    }

    private void OnRowShareQr(object? sender, RoutedEventArgs e)
    {
        if (SelectTargetRow() is { } profiles)
        {
            _ = profiles.ShareServerAsync();
        }
    }

    private void OnRowShareLink(object? sender, RoutedEventArgs e)
    {
        if (SelectTargetRow() is { } profiles)
        {
            _ = profiles.Export2ShareUrlAsync(false);
        }
    }

    private void OnRowDelete(object? sender, RoutedEventArgs e)
    {
        if (SelectTargetRow() is { } profiles)
        {
            _ = profiles.RemoveServerAsync();
        }
    }

    #endregion Server-row context actions
}

/// <summary>
/// Row name converter: strips a leading flag emoji from the remark (the flag already shows in its
/// tile) via <see cref="FlagResolver.StripLeadingFlag"/>. Instantiated locally in ServerListView.axaml
/// (kept out of GlobalResources by design — it is only used by this view).
/// </summary>
public sealed class StripLeadingFlagConverter : IValueConverter
{
    public object? Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
        => FlagResolver.StripLeadingFlag(value?.ToString());

    public object? ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture) => null;
}

/// <summary>
/// True once a latency test produced a real result — the bound <c>DelayVal</c> is a numeric
/// millisecond string (e.g. "123", "-1"). Empty (never tested) and the engine's non-numeric
/// "Testing…" placeholder both read false, so the coloured ms value shows only for real results.
/// Local to ServerListView (kept out of GlobalResources).
/// </summary>
public sealed class DelayResultConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        var s = value?.ToString();
        return !string.IsNullOrEmpty(s) && int.TryParse(s, out _);
    }

    public object? ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture) => null;
}

/// <summary>
/// True while a latency test is in flight for the row: <c>DelayVal</c> is populated with the engine's
/// non-numeric "Testing…" placeholder (never a parseable millisecond value). Drives the ping-slot
/// spinner so a testing row shows a spinner instead of any "Testing…" / "тест" text. Local to
/// ServerListView (kept out of GlobalResources).
/// </summary>
public sealed class DelayTestingConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        var s = value?.ToString();
        return !string.IsNullOrEmpty(s) && !int.TryParse(s, out _);
    }

    public object? ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture) => null;
}

/// <summary>
/// Текст пинга (screens.md «Список серверов»): реальный замер печатается как «133 мс», недоступный
/// узел («-1» / 0 от ядра) — дефисом, как на Android, и НИКОГДА сырым «-1». Сюда попадают только
/// числовые результаты (видимость держит <see cref="DelayResultConverter"/>, идущий тест показывает
/// спиннер), поэтому converter решает лишь «число или дефис». Локален для ServerListView.
/// </summary>
public sealed class DelayDisplayConverter : IValueConverter
{
    public object? Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        var s = value?.ToString();
        if (int.TryParse(s, out var ms) && ms <= 0)
        {
            //  Недоступен — дефис КРАСНЫМ (см. DelayInkConverter): на месте числа знак «числа нет».
            return L.T("Servers_PingFailed");
        }
        return $"{s} {L.T("Servers_Ms")}";
    }

    public object? ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture) => null;
}

/// <summary>
/// Чернила пинга: ЛЮБОЙ измеренный результат — зелёный, дефис «не ответил» — красный (та же пара
/// pingGood / pingBad, что на Android). Решение владельца, оно
/// заменило прежнюю трёхступенчатую шкалу «зелёный до 150 · жёлтый до 350 · дальше красный» из
/// screens.md: в списке она давала пёструю колонку из трёх цветов, в которой глазу не за что
/// зацепиться, а разница между 88 и 156 миллисекундами всё равно ничего не решает. Значимо ровно
/// одно — сервер ответил или нет.
/// Токены, а не литералы: в «Чёрно-белой» mono-оверлей сводит их к белому и серому, поэтому пара
/// сама обесцвечивается и цвета в теме не остаётся. Тема резолвится по активному
/// <see cref="ThemeVariant"/>, литеральные фолбэки нужны лишь чтобы биндинг не оборвался.
/// </summary>
public sealed class DelayInkConverter : IValueConverter
{
    private static readonly IBrush _greenFallback = new SolidColorBrush(Color.Parse("#4ADE80"));  // Brush.GreenText
    private static readonly IBrush _redFallback = new SolidColorBrush(Color.Parse("#FF6069"));    // Brush.RedText

    public object? Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        //  Недоступен / таймаут → КРАСНЫЙ дефис: это не медленный сервер, а молчащий.
        //  Всё остальное — зелёное, независимо от числа.
        return value is int ms && ms > 0
            ? Resolve("Brush.GreenText", _greenFallback)
            : Resolve("Brush.RedText", _redFallback);
    }

    private static IBrush Resolve(string key, IBrush fallback)
    {
        try
        {
            var app = Application.Current;
            if (app is not null
                && app.TryFindResource(key, app.ActualThemeVariant, out var res)
                && res is IBrush brush)
            {
                return brush;
            }
        }
        catch
        {
            //  fall through to the literal token fallback
        }
        return fallback;
    }

    public object? ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture) => null;
}
