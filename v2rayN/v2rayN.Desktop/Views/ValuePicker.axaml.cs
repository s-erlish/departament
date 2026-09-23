using Avalonia.Data;

namespace v2rayN.Desktop.Views;

/// <summary>
/// The right-hand half of a settings row that opens «окошко у значения»: the value text, the caret,
/// and the <see cref="ValuePopup"/> itself, wired together once so no screen re-implements them.
///
/// <para>It owns the two row-side behaviours motion.md attaches to the OPEN state — the caret turns
/// 180° over 300 ms, and the value dims to muted while the popup is open — plus the detail that is
/// easy to miss: a row that carries a popup shows its value in FULL ink at rest (the prototype's
/// <c>valueFg = open ? fg2 : fg</c>), unlike a navigating row whose value is permanently muted.</para>
///
/// <para><b>The anchor is the ROW, not this control.</b> tokens.md measures the popup from the row's
/// top-right corner (top 48 / right 10), and the tap target is the whole 56dp row. So the consumer
/// passes the row Border as <see cref="Anchor"/> and calls <see cref="Toggle"/> from the row's own
/// click handler. If <see cref="Anchor"/> is left null the control anchors to itself, which is right
/// for a standalone trigger (a button) and wrong for a row — pass it.</para>
///
/// <para>Consumers must still honour the popup's clipping contract — see <see cref="ValuePopup"/>
/// and <see cref="ValuePopup.RowCorners"/>.</para>
/// </summary>
public partial class ValuePicker : UserControl
{
    private readonly TextBlock? _value;
    private readonly PathIcon? _caret;
    private readonly ValuePopup? _popup;
    private bool _syncing;

    #region Properties

    /// <summary>Подписи вариантов сверху вниз — они же источник текста значения.</summary>
    public static readonly StyledProperty<IReadOnlyList<string>?> OptionsProperty =
        AvaloniaProperty.Register<ValuePicker, IReadOnlyList<string>?>(nameof(Options));

    /// <summary>Выбранный вариант; −1 = значение не показывается. TwoWay.</summary>
    public static readonly StyledProperty<int> SelectedIndexProperty =
        AvaloniaProperty.Register<ValuePicker, int>(nameof(SelectedIndex), -1, defaultBindingMode: BindingMode.TwoWay);

    /// <summary>Ширина окошка — см. <see cref="ValuePopup.Widths"/>.</summary>
    public static readonly StyledProperty<double> PopupWidthProperty =
        AvaloniaProperty.Register<ValuePicker, double>(nameof(PopupWidth), ValuePopup.Widths.Mode);

    /// <summary>Строка, к правому верхнему углу которой прижимается окошко.</summary>
    public static readonly StyledProperty<Control?> AnchorProperty =
        AvaloniaProperty.Register<ValuePicker, Control?>(nameof(Anchor));

    public static readonly StyledProperty<double> OffsetTopProperty =
        AvaloniaProperty.Register<ValuePicker, double>(nameof(OffsetTop), 48d);

    public static readonly StyledProperty<double> OffsetRightProperty =
        AvaloniaProperty.Register<ValuePicker, double>(nameof(OffsetRight), 10d);

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

    public double PopupWidth
    {
        get => GetValue(PopupWidthProperty);
        set => SetValue(PopupWidthProperty, value);
    }

    public Control? Anchor
    {
        get => GetValue(AnchorProperty);
        set => SetValue(AnchorProperty, value);
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

    /// <summary>Окошко сейчас открыто.</summary>
    public bool IsOpen => _popup?.IsOpen ?? false;

    /// <summary>Вариант выбран; аргумент — его индекс.</summary>
    public event EventHandler<int>? Picked;

    #endregion Properties

    public ValuePicker()
    {
        InitializeComponent();

        _value = this.FindControl<TextBlock>("PART_Value");
        _caret = this.FindControl<PathIcon>("PART_Caret");
        _popup = this.FindControl<ValuePopup>("PART_Popup");

        if (_popup is not null)
        {
            _popup.PropertyChanged += OnPopupPropertyChanged;
            _popup.Picked += OnPopupPicked;
        }

        AttachedToVisualTree += (_, _) => PushToPopup();
        PushToPopup();
        UpdateValueText();
    }

    /// <summary>Переключить окошко — вызывается обработчиком нажатия на строке.</summary>
    public void Toggle() => _popup?.Toggle();

    /// <summary>Закрыть окошко (уход с экрана, смена вкладки, Esc снаружи).</summary>
    public void Close() => _popup?.Close();

    protected override void OnPropertyChanged(AvaloniaPropertyChangedEventArgs change)
    {
        base.OnPropertyChanged(change);

        if (change.Property == OptionsProperty || change.Property == SelectedIndexProperty)
        {
            PushToPopup();
            UpdateValueText();
        }
        else if (change.Property == PopupWidthProperty
                 || change.Property == AnchorProperty
                 || change.Property == OffsetTopProperty
                 || change.Property == OffsetRightProperty)
        {
            PushToPopup();
        }
    }

    private void PushToPopup()
    {
        if (_popup is null || _syncing)
        {
            return;
        }

        _syncing = true;
        try
        {
            // Якорь по умолчанию — сам компонент: верно для одиночного триггера, для строки
            // настроек потребитель обязан передать саму строку (см. док-комментарий класса).
            var anchor = Anchor ?? this;
            _popup.Anchor = anchor;
            _popup.Trigger = anchor;
            _popup.Options = Options;
            _popup.PopupWidth = PopupWidth;
            _popup.OffsetTop = OffsetTop;
            _popup.OffsetRight = OffsetRight;
            _popup.SelectedIndex = SelectedIndex;
        }
        finally
        {
            _syncing = false;
        }
    }

    private void OnPopupPropertyChanged(object? sender, AvaloniaPropertyChangedEventArgs e)
    {
        if (e.Property == ValuePopup.IsOpenProperty)
        {
            var open = e.GetNewValue<bool>();
            _caret?.Classes.Set("open", open);
            _value?.Classes.Set("dim", open);
        }
        else if (e.Property == ValuePopup.SelectedIndexProperty && !_syncing)
        {
            _syncing = true;
            try
            {
                SetCurrentValue(SelectedIndexProperty, e.GetNewValue<int>());
            }
            finally
            {
                _syncing = false;
            }
            UpdateValueText();
        }
    }

    private void OnPopupPicked(object? sender, int index) => Picked?.Invoke(this, index);

    private void UpdateValueText()
    {
        if (_value is null)
        {
            return;
        }
        var options = Options;
        var index = SelectedIndex;
        _value.Text = options is not null && index >= 0 && index < options.Count ? options[index] : string.Empty;
    }
}
