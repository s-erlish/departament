using Avalonia.Animation;
using Avalonia.Controls.Presenters;
using Avalonia.Data;
using Avalonia.Layout;
using Avalonia.VisualTree;
using v2rayN.Desktop.Common;

namespace v2rayN.Desktop.Views;

/// <summary>
/// One option inside a <see cref="ValuePopup"/>. Rebuilt on every open, so it needs no change
/// notification — the popup owns the list and throws it away on close.
/// </summary>
public sealed class ValuePopupItem
{
    public ValuePopupItem(string text, bool isSelected)
    {
        Text = text;
        IsSelected = isSelected;
    }

    public string Text { get; }

    public bool IsSelected { get; }
}

/// <summary>
/// «Окошко у значения» — the shared selection surface that replaces every modal choice dialog
/// (handoff work order, item 6). Settings, Home and Account all drive THIS control; none of them
/// gets to grow its own popup, its own reveal or its own dismissal rules.
///
/// <para><b>Geometry</b> (tokens.md «Окошко у значения»): pinned to the anchor row's TOP-RIGHT
/// corner, offset top 48 / right 10, radius 14, «--popBg» fill, 1px outline, shadow
/// <c>0 22 46 rgba(0,0,0,.5)</c>. Option: min height 34, radius 9, check 15. The width differs per
/// caller (196…250), so it is a parameter — see <see cref="Widths"/> for the catalogue.</para>
///
/// <para><b>Motion</b> (motion.md): the open is a TOP-DOWN CLIP REVEAL, 260 ms, plus opacity
/// 180 ms. <b>No scale</b> — scale makes the text inside jitter. The reveal is implemented as a
/// growing shell height over STATIONARY, top-pinned, clipped content: not one glyph moves or
/// resamples during the animation. Close runs at the repo's documented 75% reverse tempo.
/// Everything is snapped instantly under «Облегчённый режим» (<see cref="MotionState"/>).</para>
///
/// <para><b>Only one is ever open</b> — opening any instance closes the previous one, app-wide,
/// through the static <c>_current</c> registry.</para>
///
/// <para><b>Two problems the acceptance list calls out, and how they are solved here:</b></para>
///
/// <para>1. <i>«окошко выбора не срезается карточкой»</i>. The popup lives IN THE TREE, under the
/// row, so any clipping ancestor slices it — a popup on the last row of a section is cut at the
/// card's bottom edge. The package's answer (and Android's, on the same product) is that the CARD
/// STOPS CLIPPING and the EDGE ROWS ROUND THEMSELVES instead. In Avalonia that is
/// <c>ClipToBounds="False"</c> on the section <c>Border.Card</c> plus a per-row
/// <see cref="Control.CornerRadius"/> — the card can no longer be relied on to mask the corners, so
/// ownership of the rounding moves down to the rows. <see cref="RowCorners"/> computes it so every
/// consumer rounds identically. This control cannot flip that flag itself: turning a card's clip
/// off while its rows are still square would break the card's corners, which is worse than the
/// clipped popup. It is a contract, not a repair.</para>
///
/// <para>2. <i>«не просвечивает соседними строками»</i>. Avalonia paints siblings in
/// <see cref="Visual.ZIndex"/> order, so the open row must outrank the rows after it — and its card
/// must outrank the next card, or the popup overflowing the card is painted over by the following
/// section. <see cref="RaiseZ"/> therefore lifts the whole ancestor chain from this control up to
/// the nearest scroll viewport, and restores every previous value on close. The walk stops at the
/// viewport because the viewport clips regardless: raising anything above it buys nothing and would
/// let a settings page outrank a sub-page overlay.</para>
///
/// <para><b>Why not a real <see cref="Avalonia.Controls.Primitives.Popup"/> or the OverlayLayer</b>,
/// which would escape both the clip and the z-order in one move: both host their content ABOVE the
/// <c>LayoutTransformControl</c> named <c>uiScaleHost</c> in MainWindow, which carries the app's
/// in-app UI zoom. Content hosted there is outside that transform, so the popup would render at 100%
/// while the screen under it sits at 125% or 150% — and one of the callers is «Масштаб интерфейса»
/// itself, which would demonstrate the bug on contact. A native Popup costs more on top: it is a
/// real OS window, so a height-driven reveal resizes a platform window every frame (the 260 ms clip
/// would stutter, or has to be faked with a fixed-size window plus an inner clip and a hand-cut
/// shadow gutter), and its own shadow and corner handling fight the exact
/// <c>0 22 46 rgba(0,0,0,.5)</c> / radius 14 the package specifies. Staying in the tree keeps zoom,
/// theming and DynamicResource working for free.</para>
/// </summary>
public partial class ValuePopup : UserControl
{
    /// <summary>Per-caller widths from tokens.md «Окошко у значения». Consumers pass one of these
    /// to <see cref="PopupWidth"/> instead of typing a number, so the set stays auditable.
    /// <para>DNS и Пинг — 236/246 из ПРОТОТИПА (<c>pop: ['dnsSel', DNSP, '236px']</c> /
    /// <c>['pingSel', PINGP, '246px']</c>), а не 210/208 из ранней таблицы tokens.md: в прототипе
    /// эти две строки — окошки, и их набор длиннее («Cloudflare + Google», «Реальная задержка»).</para></summary>
    public static class Widths
    {
        public const double Mode = 196;          // Режим: TUN · Только прокси
        public const double Dns = 236;           // DNS
        public const double Ping = 246;          // Пинг
        public const double Look = 200;          // Оформление
        public const double Language = 180;      // Язык
        public const double AutoUpdate = 190;    // Автообновление подписки
        public const double MuxCount = 120;      // Число соединений Mux
        public const double UiScale = 140;       // Масштаб интерфейса
        public const double AddMenu = 250;       // меню «+» на Главной
    }

    // ── Моушен «Окошко у значения» (motion.md). 260/180 нет в общей шкале Motion.Dur —
    //    держим их здесь под собственными именами (заявка на промоушен — в отчёте).
    //    Закрытие = 75% темпа: репо-правило реверса из GlobalResources («revState/revReveal»).
    private static readonly TimeSpan RevealIn = TimeSpan.FromMilliseconds(260);

    private static readonly TimeSpan FadeIn = TimeSpan.FromMilliseconds(180);
    private static readonly TimeSpan RevealOut = TimeSpan.FromMilliseconds(195);
    private static readonly TimeSpan FadeOut = TimeSpan.FromMilliseconds(135);

    // Подъём над соседями. 30 = «rowZ» прототипа; конкретное число не важно, важно что
    // выше любого штатного ZIndex в разметке (везде 0) и что старое значение возвращается.
    private const int RaisedZIndex = 30;

    // ...и симметричное понижение соседей, стоящих в разметке ПОСЛЕ предка (см. RaiseZ).
    private const int LoweredZIndex = -1;

    /// <summary>Открыто ВСЕГДА ОДНО окошко на всё приложение (motion.md).</summary>
    private static ValuePopup? _current;

    private readonly List<(Visual Visual, int ZIndex)> _raised = new();
    private Canvas? _host;
    private Border? _shell;
    private ItemsControl? _itemsControl;
    private TopLevel? _hooked;
    private Window? _hookedWindow;
    private CancellationTokenSource? _closeAnim;
    private double _lastLeft = double.NaN;
    private double _lastTop = double.NaN;

    #region Properties

    /// <summary>Строка (или кнопка), к правому верхнему углу которой прижато окошко. Обязателен:
    /// без него окошко не откроется.</summary>
    public static readonly StyledProperty<Control?> AnchorProperty =
        AvaloniaProperty.Register<ValuePopup, Control?>(nameof(Anchor));

    /// <summary>Элемент, нажатие по которому НЕ считается «кликом мимо». Обычно — та же строка,
    /// что и <see cref="Anchor"/>: иначе тап по строке сначала закрыл бы окошко глобальным
    /// обработчиком, а потом её же обработчик открыл бы его заново (мигание).</summary>
    public static readonly StyledProperty<Control?> TriggerProperty =
        AvaloniaProperty.Register<ValuePopup, Control?>(nameof(Trigger));

    /// <summary>Ширина окошка — своя у каждого вызывающего, см. <see cref="Widths"/>.</summary>
    public static readonly StyledProperty<double> PopupWidthProperty =
        AvaloniaProperty.Register<ValuePopup, double>(nameof(PopupWidth), Widths.Mode);

    /// <summary>Смещение от ВЕРХА якоря (tokens.md: 48 у строки настроек, 42 у меню «+»).</summary>
    public static readonly StyledProperty<double> OffsetTopProperty =
        AvaloniaProperty.Register<ValuePopup, double>(nameof(OffsetTop), 48d);

    /// <summary>Смещение от ПРАВОГО края якоря (tokens.md: 10 у строки, 0 у меню «+»).</summary>
    public static readonly StyledProperty<double> OffsetRightProperty =
        AvaloniaProperty.Register<ValuePopup, double>(nameof(OffsetRight), 10d);

    /// <summary>Подписи пунктов сверху вниз.</summary>
    public static readonly StyledProperty<IReadOnlyList<string>?> OptionsProperty =
        AvaloniaProperty.Register<ValuePopup, IReadOnlyList<string>?>(nameof(Options));

    /// <summary>Индекс выбранного пункта; −1 = ничего не выбрано (меню «+»). TwoWay: выбор
    /// пункта пишет значение обратно в модель.</summary>
    public static readonly StyledProperty<int> SelectedIndexProperty =
        AvaloniaProperty.Register<ValuePopup, int>(nameof(SelectedIndex), -1, defaultBindingMode: BindingMode.TwoWay);

    /// <summary>Состояние окошка. Открывать и закрывать — через него (каретка строки цепляется
    /// к тому же свойству).</summary>
    public static readonly StyledProperty<bool> IsOpenProperty =
        AvaloniaProperty.Register<ValuePopup, bool>(nameof(IsOpen), defaultBindingMode: BindingMode.TwoWay);

    public Control? Anchor
    {
        get => GetValue(AnchorProperty);
        set => SetValue(AnchorProperty, value);
    }

    public Control? Trigger
    {
        get => GetValue(TriggerProperty);
        set => SetValue(TriggerProperty, value);
    }

    public double PopupWidth
    {
        get => GetValue(PopupWidthProperty);
        set => SetValue(PopupWidthProperty, value);
    }

    public double OffsetTop
    {
        get => GetValue(OffsetTopProperty);
        set => SetValue(OffsetTopProperty, value);
    }

    public double OffsetRight
    {
        get => GetValue(OffsetRightProperty);
        set => SetValue(OffsetRightProperty, value);
    }

    public IReadOnlyList<string>? Options
    {
        get => GetValue(OptionsProperty);
        set => SetValue(OptionsProperty, value);
    }

    public int SelectedIndex
    {
        get => GetValue(SelectedIndexProperty);
        set => SetValue(SelectedIndexProperty, value);
    }

    public bool IsOpen
    {
        get => GetValue(IsOpenProperty);
        set => SetValue(IsOpenProperty, value);
    }

    /// <summary>Пункт выбран; аргумент — его индекс. Значение <see cref="SelectedIndex"/> к этому
    /// моменту УЖЕ обновлено, окошко уже закрывается.</summary>
    public event EventHandler<int>? Picked;

    #endregion Properties

    public ValuePopup()
    {
        InitializeComponent();

        _host = this.FindControl<Canvas>("PART_Host");
        _shell = this.FindControl<Border>("PART_Shell");
        _itemsControl = this.FindControl<ItemsControl>("PART_Items");

        if (_shell is not null)
        {
            _shell.Width = PopupWidth;
        }

        // Один обработчик на весь список: клик по пункту всплывает от кнопки к ItemsControl,
        // поэтому подписываться на каждую кнопку отдельно не нужно.
        _itemsControl?.AddHandler(Button.ClickEvent, OnOptionClick);

        DetachedFromVisualTree += OnDetached;
    }

    /// <summary>
    /// Скругление КРАЙНИХ строк секции. Как только карточка перестаёт обрезать содержимое
    /// (обязательное условие, чтобы окошко не срезалось её нижней кромкой), маскировать углы
    /// становится нечем — и первая/последняя строка обязаны скруглиться сами. Один расчёт на всех
    /// потребителей, чтобы Настройки, Аккаунт и Главная не разошлись в цифрах.
    /// Внутренний радиус = радиус карточки минус её контур, иначе строка «вылезает» из скругления.
    /// </summary>
    /// <param name="index">Индекс ВИДИМОЙ строки (скрытые зависимые строки не считаются).</param>
    /// <param name="count">Число ВИДИМЫХ строк в карточке.</param>
    /// <param name="cardRadius">Скругление карточки (Radius.Card).</param>
    /// <param name="cardBorderThickness">Толщина контура карточки.</param>
    public static CornerRadius RowCorners(int index, int count, double cardRadius, double cardBorderThickness = 1d)
    {
        var r = Math.Max(0d, cardRadius - cardBorderThickness);
        if (count <= 1)
        {
            return new CornerRadius(r);
        }
        if (index <= 0)
        {
            return new CornerRadius(r, r, 0, 0);
        }
        if (index >= count - 1)
        {
            return new CornerRadius(0, 0, r, r);
        }
        return new CornerRadius(0);
    }

    /// <summary>Переключить окошко — то, что вызывает обработчик нажатия на строке.</summary>
    public void Toggle() => SetCurrentValue(IsOpenProperty, !IsOpen);

    /// <summary>Закрыть без анимации выбора (Esc, уход со страницы, смена вкладки).</summary>
    public void Close() => SetCurrentValue(IsOpenProperty, false);

    protected override void OnPropertyChanged(AvaloniaPropertyChangedEventArgs change)
    {
        base.OnPropertyChanged(change);

        if (change.Property == IsOpenProperty)
        {
            if (change.GetNewValue<bool>())
            {
                OpenCore();
            }
            else
            {
                CloseCore();
            }
        }
        else if (change.Property == PopupWidthProperty && _shell is not null)
        {
            _shell.Width = change.GetNewValue<double>();
        }
    }

    #region Open / close

    private void OpenCore()
    {
        if (_shell is null || _host is null || _itemsControl is null)
        {
            return;
        }

        var anchor = Anchor;
        if (!IsLive(anchor) || !IsLive(this))
        {
            // Нечего к чему прижаться — не открываемся молча «в углу».
            SetCurrentValue(IsOpenProperty, false);
            return;
        }

        // «Открыто всегда одно» (motion.md) — на всё приложение, а не на один экран.
        if (!ReferenceEquals(_current, this))
        {
            _current?.Close();
            _current = this;
        }

        _closeAnim?.Cancel();
        _closeAnim = null;

        BuildItems();

        _shell.Width = PopupWidth;
        _shell.IsVisible = true;

        // Мерить ОБЯЗАТЕЛЬНО после IsVisible = true: невидимый Layoutable даёт DesiredSize = 0.
        _shell.Height = double.NaN;
        _shell.Measure(new Size(PopupWidth, double.PositiveInfinity));
        var target = _shell.DesiredSize.Height;

        Reposition();
        RaiseZ();
        HookDismissal();

        if (MotionState.IsLite)
        {
            // «Облегчённый режим» гасит всё движение — окошко просто есть.
            _shell.Transitions = null;
            _shell.Height = target;
            _shell.Opacity = 1;
            FocusSelected();
            return;
        }

        // Стартовое состояние ставим БЕЗ транзишенов, иначе первый кадр поедет от прежней высоты.
        _shell.Transitions = null;
        _shell.Height = 0;
        _shell.Opacity = 0;

        ApplyMotion(closing: false);

        // Один кадр на высоте 0, потом срез вниз: гарантирует, что анимация начнётся с нуля,
        // а не схлопнется в мгновенный скачок внутри одного layout-прохода.
        Dispatcher.UIThread.Post(() =>
        {
            if (!IsOpen || _shell is null)
            {
                return;
            }
            _shell.Height = target;
            _shell.Opacity = 1;
            FocusSelected();
        }, DispatcherPriority.Render);
    }

    private void CloseCore()
    {
        UnhookDismissal();

        if (ReferenceEquals(_current, this))
        {
            _current = null;
        }

        if (_shell is null)
        {
            return;
        }

        if (MotionState.IsLite)
        {
            _shell.Transitions = null;
            _shell.Height = 0;
            _shell.Opacity = 0;
            FinishClose();
            return;
        }

        ApplyMotion(closing: true);
        _shell.Height = 0;
        _shell.Opacity = 0;

        var cts = new CancellationTokenSource();
        _closeAnim?.Cancel();
        _closeAnim = cts;
        _ = FinishCloseAfterAsync(cts.Token);
    }

    private async Task FinishCloseAfterAsync(CancellationToken token)
    {
        try
        {
            await Task.Delay(RevealOut, token);
        }
        catch (OperationCanceledException)
        {
            return;
        }

        if (token.IsCancellationRequested || IsOpen)
        {
            return;
        }
        FinishClose();
    }

    private void FinishClose()
    {
        if (_shell is not null)
        {
            _shell.IsVisible = false;
        }
        if (_itemsControl is not null)
        {
            _itemsControl.ItemsSource = null;
        }
        RestoreZ();
        _lastLeft = double.NaN;
        _lastTop = double.NaN;
    }

    private void ApplyMotion(bool closing)
    {
        if (_shell is null)
        {
            return;
        }

        _shell.Transitions = new Transitions
        {
            new DoubleTransition
            {
                Property = Layoutable.HeightProperty,
                Duration = closing ? RevealOut : RevealIn,
                Easing = Motion.Ease.OutQuart,
            },
            new DoubleTransition
            {
                Property = OpacityProperty,
                Duration = closing ? FadeOut : FadeIn,
                Easing = Motion.Ease.OutQuart,
            },
        };
    }

    private void BuildItems()
    {
        if (_itemsControl is null)
        {
            return;
        }

        var options = Options;
        var selected = SelectedIndex;
        var items = new List<ValuePopupItem>();
        if (options is not null)
        {
            for (var i = 0; i < options.Count; i++)
            {
                items.Add(new ValuePopupItem(options[i], i == selected));
            }
        }
        _itemsControl.ItemsSource = items;
    }

    /// <summary>
    /// Уводит фокус на выбранный пункт: строка получает Enter с клавиатуры, и без этого следующий
    /// Tab ушёл бы на СЛЕДУЮЩУЮ строку мимо раскрытого окошка. Контейнер ItemsControl — это
    /// ContentPresenter, а не сама кнопка, поэтому фокус ставим на кнопку внутри него.
    /// </summary>
    private void FocusSelected()
    {
        if (_itemsControl is null)
        {
            return;
        }

        var index = SelectedIndex >= 0 ? SelectedIndex : 0;
        var container = _itemsControl.ContainerFromIndex(index);
        var button = container as Button ?? container?.GetVisualDescendants().OfType<Button>().FirstOrDefault();
        button?.Focus();
    }

    private void OnOptionClick(object? sender, RoutedEventArgs e)
    {
        var button = e.Source as Button
                     ?? (e.Source as Visual)?.GetVisualAncestors().OfType<Button>().FirstOrDefault();
        if (button?.DataContext is not ValuePopupItem item
            || _itemsControl?.ItemsSource is not IReadOnlyList<ValuePopupItem> built)
        {
            return;
        }

        // Индекс — по ССЫЛКЕ на элемент, а не по тексту и не через IndexFromContainer: подписи
        // вариантов могут совпадать, а контейнером ItemsControl считает ContentPresenter, не кнопку.
        var index = -1;
        for (var i = 0; i < built.Count; i++)
        {
            if (ReferenceEquals(built[i], item))
            {
                index = i;
                break;
            }
        }
        if (index < 0)
        {
            return;
        }

        SetCurrentValue(SelectedIndexProperty, index);
        SetCurrentValue(IsOpenProperty, false);
        Picked?.Invoke(this, index);
        e.Handled = true;
    }

    #endregion Open / close

    #region Placement

    /// <summary>
    /// Ставит окошко к правому верхнему углу якоря. Координаты считаются переводом точки якоря в
    /// систему Canvas'а, поэтому промежуточная вложенность (Grid внутри строки, строка внутри
    /// карточки) роли не играет.
    /// </summary>
    private void Reposition()
    {
        if (_shell is null || _host is null)
        {
            return;
        }

        var anchor = Anchor;
        if (!IsLive(anchor) || !IsLive(_host))
        {
            return;
        }

        var origin = anchor.TranslatePoint(new Point(0, 0), _host);
        if (origin is null)
        {
            return;
        }

        var top = origin.Value.Y + OffsetTop;
        var left = origin.Value.X + anchor.Bounds.Width - OffsetRight - PopupWidth;

        // Ставим только при реальном изменении: Canvas.Left/Top инвалидируют arrange, а
        // Reposition вызывается из LayoutUpdated — без этой отсечки был бы цикл раскладки.
        if (!IsClose(left, _lastLeft))
        {
            _lastLeft = left;
            Canvas.SetLeft(_shell, left);
        }
        if (!IsClose(top, _lastTop))
        {
            _lastTop = top;
            Canvas.SetTop(_shell, top);
        }
    }

    private static bool IsClose(double a, double b) => !double.IsNaN(b) && Math.Abs(a - b) < 0.01;

    private void OnLayoutUpdated(object? sender, EventArgs e)
    {
        if (!IsOpen)
        {
            return;
        }
        if (!IsLive(Anchor))
        {
            // Якорь ушёл из дерева (сменили вкладку, свернули секцию) — окошку не за что держаться.
            Close();
            return;
        }
        Reposition();
    }

    #endregion Placement

    #region Z-order

    /// <summary>
    /// Поднимает цепочку предков над соседями, чтобы следующие строки и следующая карточка не
    /// рисовались поверх окошка. Останавливается на ближайшем скролл-вьюпорте: он всё равно
    /// обрезает, выше подниматься нечего и вредно (страница перекрыла бы оверлей подэкрана).
    ///
    /// <para><b>Двумя ходами сразу, и это не перестраховка.</b> Одного подъёма предка НЕ ХВАТАЕТ:
    /// проверено на живом окне — окошко DNS, вылезающее из карточки «Подключение», всё равно
    /// закрывалось следующей карточкой «Обход блокировок», хотя карточка-владелец уже несла
    /// ZIndex 30, а соседка ноль. Ровно та же схема этажом ниже (строка над следующими строками)
    /// при этом работала. Что бы ни было тому причиной внутри рендера, ПОРЯДОК СТАНОВИТСЯ
    /// ОДНОЗНАЧНЫМ, когда каждый сосед, стоящий В РАЗМЕТКЕ ПОСЛЕ предка, получает ZIndex −1:
    /// теперь «выше» задано с обеих сторон, а не только сверху. Понижаем только тех, кто и так
    /// рисовался бы поверх, и только на время открытия — исходные значения возвращает
    /// <see cref="RestoreZ"/> из того же списка.</para>
    /// </summary>
    private void RaiseZ()
    {
        RestoreZ();

        Visual? v = this;
        while (v is not null)
        {
            if (v is ScrollContentPresenter or ScrollViewer || v is TopLevel)
            {
                break;
            }
            _raised.Add((v, v.ZIndex));
            v.ZIndex = RaisedZIndex;

            // Соседи ПОСЛЕ предка уходят под него: в разметке они позже, значит по умолчанию
            // рисуются поверх — см. развёрнутое объяснение в док-комментарии метода.
            if (v.GetVisualParent() is Panel panel && v is Control ctrl)
            {
                var index = panel.Children.IndexOf(ctrl);
                for (var i = index + 1; i < panel.Children.Count; i++)
                {
                    var later = panel.Children[i];
                    _raised.Add((later, later.ZIndex));
                    later.ZIndex = LoweredZIndex;
                }
            }

            v = v.GetVisualParent();
        }
    }

    private void RestoreZ()
    {
        for (var i = _raised.Count - 1; i >= 0; i--)
        {
            _raised[i].Visual.ZIndex = _raised[i].ZIndex;
        }
        _raised.Clear();
    }

    #endregion Z-order

    #region Dismissal

    private void HookDismissal()
    {
        var top = TopLevel.GetTopLevel(this);
        if (top is null || ReferenceEquals(top, _hooked))
        {
            // Нет окна — вешать некуда; то же окно — уже подписаны.
            return;
        }

        UnhookDismissal();
        _hooked = top;
        top.AddHandler(PointerPressedEvent, OnGlobalPointerPressed, RoutingStrategies.Tunnel);
        top.AddHandler(KeyDownEvent, OnGlobalKeyDown, RoutingStrategies.Tunnel);
        if (top is Window window)
        {
            _hookedWindow = window;
            window.Deactivated += OnWindowDeactivated;
        }
        LayoutUpdated += OnLayoutUpdated;
    }

    private void UnhookDismissal()
    {
        LayoutUpdated -= OnLayoutUpdated;
        if (_hookedWindow is not null)
        {
            _hookedWindow.Deactivated -= OnWindowDeactivated;
            _hookedWindow = null;
        }
        if (_hooked is not null)
        {
            _hooked.RemoveHandler(PointerPressedEvent, OnGlobalPointerPressed);
            _hooked.RemoveHandler(KeyDownEvent, OnGlobalKeyDown);
            _hooked = null;
        }
    }

    private void OnGlobalPointerPressed(object? sender, PointerPressedEventArgs e)
    {
        if (!IsOpen)
        {
            return;
        }
        var source = e.Source as Visual;
        if (IsWithin(source, _shell) || IsWithin(source, Trigger))
        {
            return;
        }
        Close();
    }

    private void OnGlobalKeyDown(object? sender, KeyEventArgs e)
    {
        if (!IsOpen || e.Key != Key.Escape)
        {
            return;
        }
        Close();
        Trigger?.Focus();
        e.Handled = true;
    }

    private void OnWindowDeactivated(object? sender, EventArgs e) => Close();

    private void OnDetached(object? sender, VisualTreeAttachmentEventArgs e)
    {
        // Ушли из дерева при открытом окошке — снять глобальные обработчики и вернуть ZIndex,
        // иначе подписка на TopLevel переживёт сам экран.
        if (IsOpen)
        {
            Close();
        }
        UnhookDismissal();
        RestoreZ();
    }

    /// <summary>Элемент реально в живом дереве окна (а не осиротевший после смены экрана).</summary>
    private static bool IsLive(Visual? v) => v is not null && TopLevel.GetTopLevel(v) is not null;

    private static bool IsWithin(Visual? node, Visual? root)
    {
        if (node is null || root is null)
        {
            return false;
        }
        for (var v = node; v is not null; v = v.GetVisualParent())
        {
            if (ReferenceEquals(v, root))
            {
                return true;
            }
        }
        return false;
    }

    #endregion Dismissal
}
