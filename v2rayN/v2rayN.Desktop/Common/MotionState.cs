namespace v2rayN.Desktop.Common;

/// <summary>
/// Runtime «Облегчённый режим» (reduced-motion) broadcast — the single source of truth the whole
/// desktop shell listens to so a LiteMode toggle takes effect INSTANTLY, with no restart.
///
/// Before this, <c>.lite</c> on the window and <see cref="ConnectHeroView.ReducedMotion"/> were read
/// ONCE in each constructor from <c>UiItem.LiteMode</c>, so flipping the switch at runtime changed the
/// persisted flag but left the shield spinning and the page transitions running until the next launch.
///
/// Now <see cref="SettingsViewModel"/> pushes every change through <see cref="SetLite"/>; the shell
/// (MainWindow) and the connect hero (<see cref="ConnectHeroView"/>) subscribe to <see cref="Changed"/>
/// and re-apply their motion state on the spot. <see cref="IsLite"/> caches the last value so a
/// subscriber attaching late (e.g. a hero re-entering the visual tree) can read the current mode.
/// </summary>
public static class MotionState
{
    private static bool _isLite;

    /// <summary>Current reduced-motion state (last broadcast value).</summary>
    public static bool IsLite => _isLite;

    /// <summary>Raised whenever the lite state actually flips; argument = the new state.</summary>
    public static event EventHandler<bool>? Changed;

    /// <summary>Seed the cached value at startup WITHOUT notifying (initial config load).</summary>
    public static void Initialize(bool lite) => _isLite = lite;

    /// <summary>Broadcast a new lite state; notifies subscribers only on a genuine change.</summary>
    public static void SetLite(bool lite)
    {
        if (_isLite == lite)
        {
            return;
        }
        _isLite = lite;
        Changed?.Invoke(null, lite);
    }
}
