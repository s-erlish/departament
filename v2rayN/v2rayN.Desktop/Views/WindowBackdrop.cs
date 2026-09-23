namespace v2rayN.Desktop.Views;

/// <summary>
/// Фон окна: радиальный <c>Brush.HomeGradient</c>, растеризованный ОДИН раз в битмап размером с окно.
/// <para/>
/// Зачем. Радиальный градиент не кэшируется ни Avalonia, ни Skia: каждый кадр любой анимации
/// (кроссфейд вкладок, въезд подэкрана, ховер строки, секундомер) заново считает его по всей
/// перерисовываемой области, а при программной отрисовке (Linux без GL, в том числе в виртуальной
/// машине) это самая дорогая операция кадра: на смене вкладки три четверти работы потока рендера
/// уходили в эту заливку. Готовый битмап того же размера копируется попиксельно,
/// в разы дешевле. Картинка та же: кисть та же, прямоугольник тот же, сетка пикселей та же
/// (начало в углу окна), поэтому и дизеринг градиента ложится один в один.
/// <para/>
/// Почему не <c>CacheMode = BitmapCache</c>. В Avalonia 12.1 обход дерева после первого
/// закэшированного элемента перестаёт снимать TextOptions / RenderOptions / маски прозрачности
/// у всех следующих элементов кадра (флаг <c>_usedCache</c> в RenderContext не сбрасывается):
/// проверено по снимкам, у части подписей менялось сглаживание. Поэтому кэш сделан руками.
/// <para/>
/// Пока размер окна, масштаб или тема меняются (живой ресайз, смена темы), кадр рисует кисть
/// напрямую, как раньше, а битмап собирается, когда всё успокоилось: иначе каждый шаг ресайза
/// растеризовал бы градиент ещё и на потоке интерфейса.
/// </summary>
public sealed class WindowBackdrop : Control
{
    public static readonly StyledProperty<IBrush?> BackgroundProperty =
        AvaloniaProperty.Register<WindowBackdrop, IBrush?>(nameof(Background));

    //  Сколько ждать тишины после смены размера / кисти, прежде чем собирать битмап. Шаги живого
    //  ресайза идут чаще, поэтому во время ресайза битмап не собирается ни разу.
    private static readonly TimeSpan SettleDelay = TimeSpan.FromMilliseconds(200);

    private RenderTargetBitmap? _bitmap;
    private (PixelSize px, double scale, IBrush? brush) _bitmapKey;
    private PixelSize _renderedPx;
    private DispatcherTimer? _settleTimer;
    private TopLevel? _topLevel;

    public IBrush? Background
    {
        get => GetValue(BackgroundProperty);
        set => SetValue(BackgroundProperty, value);
    }

    static WindowBackdrop()
    {
        AffectsRender<WindowBackdrop>(BackgroundProperty);
        IsHitTestVisibleProperty.OverrideDefaultValue<WindowBackdrop>(false);
    }

    protected override void OnAttachedToVisualTree(VisualTreeAttachmentEventArgs e)
    {
        base.OnAttachedToVisualTree(e);
        _topLevel = TopLevel.GetTopLevel(this);
        if (_topLevel is not null)
        {
            _topLevel.ScalingChanged += OnScalingChanged;
        }
    }

    protected override void OnDetachedFromVisualTree(VisualTreeAttachmentEventArgs e)
    {
        base.OnDetachedFromVisualTree(e);
        if (_topLevel is not null)
        {
            _topLevel.ScalingChanged -= OnScalingChanged;
            _topLevel = null;
        }
        _settleTimer?.Stop();
        DropBitmap();
    }

    private void OnScalingChanged(object? sender, EventArgs e) => InvalidateVisual();

    //  Новый размер известен в Arrange, а Render ещё не вызван: здесь и просим перерисовку, чтобы
    //  ни битмап, ни прямая заливка не остались старого размера.
    protected override Size ArrangeOverride(Size finalSize)
    {
        var arranged = base.ArrangeOverride(finalSize);
        if (PixelSizeFor(arranged, Scaling()) != _renderedPx)
        {
            InvalidateVisual();
        }
        return arranged;
    }

    public override void Render(DrawingContext context)
    {
        var brush = Background;
        var size = Bounds.Size;
        if (brush is null || size.Width <= 0 || size.Height <= 0)
        {
            return;
        }

        var scale = Scaling();
        var px = PixelSizeFor(size, scale);
        _renderedPx = px;
        if (_bitmap is not null && _bitmapKey == (px, scale, brush))
        {
            //  Битмап ровно в пиксели окна: прямоугольник назначения — его собственный размер,
            //  а не Bounds, иначе дробный остаток пикселя дал бы пересэмплирование.
            context.DrawImage(_bitmap, new Rect(0, 0, px.Width / scale, px.Height / scale));
            return;
        }

        //  Что-то поменялось: этот кадр — кистью напрямую (та же картинка), битмап — после паузы.
        context.FillRectangle(brush, new Rect(size));
        ScheduleBuild();
    }

    private void ScheduleBuild()
    {
        _settleTimer ??= new DispatcherTimer(SettleDelay, DispatcherPriority.Background, (_, _) =>
        {
            _settleTimer!.Stop();
            BuildBitmap();
        });
        _settleTimer.Stop();
        _settleTimer.Start();
    }

    private void BuildBitmap()
    {
        var brush = Background;
        var size = Bounds.Size;
        if (brush is null || size.Width <= 0 || size.Height <= 0 || _topLevel is null)
        {
            return;
        }

        var scale = Scaling();
        var px = PixelSizeFor(size, scale);
        try
        {
            var bitmap = new RenderTargetBitmap(px, new Vector(96 * scale, 96 * scale));
            using (var dc = bitmap.CreateDrawingContext())
            {
                //  Тот же прямоугольник, что у прямой заливки: относительные центр и радиус кисти
                //  считаются от него, поэтому градиент совпадает с прежним попиксельно.
                dc.FillRectangle(brush, new Rect(size));
            }
            DropBitmap();
            _bitmap = bitmap;
            _bitmapKey = (px, scale, brush);
        }
        catch (Exception ex)
        {
            //  Нет битмапа — значит, фон и дальше рисуется кистью напрямую, как раньше.
            Logging.SaveLog("WindowBackdrop", ex);
            DropBitmap();
            return;
        }
        InvalidateVisual();
    }

    //  Кадр, уже отправленный композитору, держит свою ссылку на битмап, поэтому освобождать
    //  старый можно сразу: композитор отпустит его сам, когда кадр сменится.
    private void DropBitmap()
    {
        _bitmap?.Dispose();
        _bitmap = null;
        _bitmapKey = default;
    }

    private double Scaling() => _topLevel?.RenderScaling ?? 1.0;

    private static PixelSize PixelSizeFor(Size size, double scale) =>
        new(Math.Max(1, (int)Math.Ceiling(size.Width * scale - 0.001)),
            Math.Max(1, (int)Math.Ceiling(size.Height * scale - 0.001)));
}
