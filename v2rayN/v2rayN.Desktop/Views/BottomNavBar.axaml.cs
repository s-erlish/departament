using Avalonia.Animation;
using v2rayN.Desktop.Common;

namespace v2rayN.Desktop.Views;

/// <summary>The compact tabs — the single source of truth for tab identity across both layouts.
/// There is no «Сервера» tab: the compact Home already lists servers in its single scroll.</summary>
public enum AppTab
{
    Home,
    Settings,
    Account,
}

/// <summary>
/// Bottom navigation for the compact (phone-like) layout (CA-2). Drives the SAME tab switching the
/// widescreen left rail does — it only raises <see cref="TabSelected"/>; the host (<c>MainWindow</c>)
/// decides how to show the tab, so tab + connection state survive a width change. «Аккаунт» виден
/// ВСЕГДА: в узком окне рейла нет, и если прятать вкладку до входа, войти будет негде.
/// </summary>
public partial class BottomNavBar : UserControl
{
    /// <summary>Raised when a tab is tapped.</summary>
    public event EventHandler<AppTab>? TabSelected;

    private AppTab _selected = AppTab.Home;

    // ==================== Путешествующий индикатор (P0-1) ====================
    // ОДНА акцентная полоса (BottomIndicator) физически СКОЛЬЗИТ по X к центру активной трети —
    // вместо трёх независимых пилюль, «мигавших» на месте. Центр берётся из ЖИВЫХ bounds активной
    // кнопки, поэтому пере-решается при ресайзе окна. Первый показ — мгновенно на активной
    // трети (без скольжения с X=0); дальше — переезд Motion.Dur.Nav 280мс ease-out-quart (motion.md
    // «Навигация»). Под lite / off-screen — мгновенно. Незавершённое скольжение перебивает сам
    // переход на трансформе: он подхватывает живое X, отменять нечего.
    private readonly TranslateTransform _indicatorTransform = new();
    private bool _indicatorSeeded;
    private double _lastTargetX = double.NaN;

    // Ширина полоски (tokens.md «Нижняя панель»: 30×3). Держать равной Width у BottomIndicator в
    // разметке — из неё считается «центр трети минус половина полоски».
    private const double IndicatorWidth = 30d;

    public BottomNavBar()
    {
        InitializeComponent();

        BottomIndicator.RenderTransform = _indicatorTransform;

        ItemHome.Click += (_, _) => Raise(AppTab.Home);
        ItemSettings.Click += (_, _) => Raise(AppTab.Settings);
        ItemAccount.Click += (_, _) => Raise(AppTab.Account);

        // Пере-ставим индикатор на активную треть после КАЖДОГО прохода раскладки (первый layout, ресайз
        // окна в компакте). LayoutUpdated (а не Bounds одной кнопки)
        // гарантирует, что ВСЕ bounds пунктов уже финальные — иначе при активной не-первой трети коллбэк
        // мог сработать до арранжа ItemSettings/ItemAccount и увидеть нулевую ширину. Мгновенно, без
        // скольжения; _lastTargetX-guard гасит холостые повторы и не рвёт идущее скольжение по тапу.
        LayoutUpdated += (_, _) => PositionIndicator(animate: false);

        SetSelected(AppTab.Home);
    }

    private void Raise(AppTab tab)
    {
        SetSelected(tab);
        TabSelected?.Invoke(this, tab);
    }

    /// <summary>Reflect the active tab without raising the event (host-driven, e.g. on layout swap).</summary>
    public void SetSelected(AppTab tab)
    {
        var changed = _selected != tab;
        _selected = tab;
        SetItemState(ItemHome, tab == AppTab.Home);
        SetItemState(ItemSettings, tab == AppTab.Settings);
        SetItemState(ItemAccount, tab == AppTab.Account);
        // Скольжение только на реальную смену трети; повторный host-вызов той же вкладки (Raise + ShowTab
        // на один тап) не рвёт уже идущее скольжение — см. _lastTargetX-guard в PositionIndicator.
        PositionIndicator(animate: changed);
    }

    private static void SetItemState(Button item, bool selected)
    {
        if (selected)
        {
            if (!item.Classes.Contains("sel"))
            {
                item.Classes.Add("sel");
            }
        }
        else
        {
            item.Classes.Remove("sel");
        }
    }

    // Активная кнопка (для позиции индикатора). Её ЖИВЫЕ bounds дают центр активной трети.
    private Button ActiveItem => _selected switch
    {
        AppTab.Settings => ItemSettings,
        AppTab.Account => ItemAccount,
        _ => ItemHome,
    };

    private void PositionIndicator(bool animate)
    {
        var item = ActiveItem;
        var w = item.Bounds.Width;
        if (w <= 0)
        {
            return;   // ещё не разложено — переставит следующий Bounds-тик ItemHome
        }
        var targetX = item.Bounds.X + (w / 2d) - (IndicatorWidth / 2d);

        // Тот же слот, что и прошлый запрос → ничего не делаем: не рвём уже идущее скольжение к нему
        // (страхует от двойного SetSelected — Raise + host ShowTab дают два вызова на один тап) и
        // не дёргаем позицию на «пустых» Bounds-тиках без изменения геометрии.
        if (_indicatorSeeded && !double.IsNaN(_lastTargetX) && Math.Abs(targetX - _lastTargetX) < 0.5)
        {
            return;
        }
        _lastTargetX = targetX;

        var instant = !animate || !_indicatorSeeded || MotionState.IsLite || !IsWindowLive();
        if (instant)
        {
            _indicatorTransform.Transitions?.Clear();
            _indicatorTransform.X = targetX;
            _indicatorSeeded = true;
            return;
        }

        //  motion.md «Навигация»: переезд 280мс ease-out-quart. Едем ПЕРЕХОДОМ на трансформе, а не
        //  императивным Animation.RunAsync: тот же приём в рейле запускался, но полоска всё равно
        //  оказывалась в конечной точке за один кадр (замер по 16 кадрам — ни одного промежуточного
        //  положения). С переходом переезд виден: 124 → 164 → 233 → 268 → 284 → 290.
        _indicatorTransform.Transitions ??= new Transitions();
        if (_indicatorTransform.Transitions.Count == 0)
        {
            _indicatorTransform.Transitions.Add(new DoubleTransition
            {
                Property = TranslateTransform.XProperty,
                Duration = Motion.Dur.Nav,
                Easing = Motion.Ease.OutQuart,
            });
        }
        _indicatorTransform.X = targetX;
    }

    // Off-screen-guard: анимируем только когда окно реально видно (не в трее / не свёрнуто), иначе
    // индикатор тикал бы за экраном (правило «нет off-screen циклов»).
    private bool IsWindowLive()
        => TopLevel.GetTopLevel(this) is Window w && w.IsVisible && w.WindowState != WindowState.Minimized;

}
