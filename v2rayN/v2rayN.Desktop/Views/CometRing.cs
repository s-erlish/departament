using Avalonia.Media;
using Avalonia.Media.Immutable;

namespace v2rayN.Desktop.Views;

/// <summary>
/// «Комета» кнопки подключения — САМО активное кольцо, а не отдельная дуга поверх него
/// (motion.md, «Кнопка подключения»): элемент лежит ровно на том же отступе и той же толщине,
/// что активное кольцо, и подсвечивается вращающимся КОНИЧЕСКИМ градиентом — голова 85%, хвост
/// уходит через 62 → 30 → 10% в ноль на две трети окружности, оборот 1500 мс линейно.
///
/// ПРОБЛЕМА: в Avalonia нет конического градиента (есть только Linear/Radial/Conic-less). В
/// прототипе комета сделана как <c>conic-gradient</c> + радиальная маска, вырезающая из круга
/// кольцевую полосу. Прямого аналога нет, а подделка «дуга с StrokeDashArray поверх кольца» даёт
/// ровно то, что приёмка запрещает — вторую дугу рядом с кольцом.
///
/// РЕШЕНИЕ: конический градиент РАЗБИРАЕТСЯ на дуги. Кольцо рисуется не одним эллипсом, а лентой
/// коротких дуг (шаг 3°) по ОДНОЙ окружности с ОДНОЙ толщиной; каждой дуге назначается альфа,
/// снятая с той же самой конической рампы в её середине. Соседние дуги перекрываются на пол-шага,
/// поэтому шва не видно, а глаз читает непрерывный перелив по кольцу. Прозрачный сектор (альфа 0)
/// не рисуется вовсе. Геометрия и кисти строятся ОДИН раз на размер/цвет и кэшируются: вращение
/// идёт через <c>RotateTransform</c> на самом контроле (композитор), <see cref="Render"/> при
/// вращении не вызывается — стоимость нулевая.
///
/// Итог: комета физически ЛЕЖИТ НА кольце (тот же радиус, та же толщина, тот же центр), потому что
/// она И ЕСТЬ кольцо — просто окрашенное по углу.
/// </summary>
public sealed class CometRing : Control
{
    //  Шаг дуги. 3° = 120 сегментов на полный круг; в прозрачной трети не рисуется ничего,
    //  поэтому реально строится ~70 дуг. Меньше шаг — глаже перелив, дороже построение;
    //  на 3° полосы уже не различимы даже на 4K-масштабе.
    private const double StepDeg = 3.0;

    //  Перекрытие соседних дуг (половина шага): без него на стыке остаётся волосяной зазор,
    //  который на вращении читается как мерцающая «лесенка».
    private const double OverlapDeg = StepDeg * 0.5;

    /// <summary>Базовый цвет кометы (RGB). Альфу задаёт коническая рампа, не этот цвет.</summary>
    public static readonly StyledProperty<Color> RingColorProperty =
        AvaloniaProperty.Register<CometRing, Color>(nameof(RingColor), Color.Parse("#4C8DFF"));

    /// <summary>Толщина ленты — ОБЯЗАНА совпадать с толщиной активного кольца (2.5 на Главной).</summary>
    public static readonly StyledProperty<double> StrokeThicknessProperty =
        AvaloniaProperty.Register<CometRing, double>(nameof(StrokeThickness), 2.5);

    public Color RingColor
    {
        get => GetValue(RingColorProperty);
        set => SetValue(RingColorProperty, value);
    }

    public double StrokeThickness
    {
        get => GetValue(StrokeThicknessProperty);
        set => SetValue(StrokeThicknessProperty, value);
    }

    static CometRing()
    {
        AffectsRender<CometRing>(RingColorProperty, StrokeThicknessProperty);
    }

    //  Смена размера обязана перестроить ленту: радиус дуг считается от Bounds. Arrange —
    //  единственное место, где новый размер уже известен, а Render ещё не вызван.
    protected override Size ArrangeOverride(Size finalSize)
    {
        var arranged = base.ArrangeOverride(finalSize);
        if (Math.Abs(arranged.Width - _key.w) > 0.01 || Math.Abs(arranged.Height - _key.h) > 0.01)
        {
            InvalidateVisual();
        }
        return arranged;
    }

    //  Кэш ленты: пересобирается только при смене размера / цвета / толщины.
    private (double w, double h, uint argb, double t) _key;
    private List<(Geometry geo, Pen pen)>? _band;

    /// <summary>
    /// Коническая рампа прототипа, 1:1: <c>from 0deg</c>, 0 до 150°, затем .10 (230°), .30 (295°),
    /// .62 (340°), .85 (358°) и обрыв в 0 на 360°. Голова — у 358°, хвост тает против хода.
    /// Между стопами — линейная интерполяция, как в CSS.
    /// </summary>
    private static readonly (double deg, double alpha)[] Ramp =
    [
        (0, 0.00),
        (150, 0.00),
        (230, 0.10),
        (295, 0.30),
        (340, 0.62),
        (358, 0.85),
        (360, 0.00),
    ];

    private static double AlphaAt(double deg)
    {
        //  Нормализуем в [0,360) — середина сегмента может выйти за круг из-за перекрытия.
        deg -= Math.Floor(deg / 360.0) * 360.0;

        for (var i = 1; i < Ramp.Length; i++)
        {
            if (deg > Ramp[i].deg)
            {
                continue;
            }

            var (d0, a0) = Ramp[i - 1];
            var (d1, a1) = Ramp[i];
            var span = d1 - d0;
            var t = span <= 0 ? 0 : (deg - d0) / span;
            return a0 + ((a1 - a0) * t);
        }

        return 0;
    }

    //  Точка на окружности: угол 0 = 12 часов, ход по часовой (как conic-gradient «from 0deg»).
    private static Point OnCircle(Point c, double r, double deg)
    {
        var rad = deg * Math.PI / 180.0;
        return new Point(c.X + (r * Math.Sin(rad)), c.Y - (r * Math.Cos(rad)));
    }

    private List<(Geometry geo, Pen pen)> BuildBand(double w, double h, Color color, double thickness)
    {
        var band = new List<(Geometry, Pen)>(128);
        var radius = (Math.Min(w, h) - thickness) / 2.0;
        if (radius <= 0)
        {
            return band;
        }

        var centre = new Point(w / 2.0, h / 2.0);

        for (var a = 0.0; a < 360.0; a += StepDeg)
        {
            var alpha = AlphaAt(a + (StepDeg / 2.0));
            if (alpha <= 0.004)
            {
                //  Прозрачная треть окружности — просто не рисуется (там видно само кольцо).
                continue;
            }

            //  Дуга шире шага на перекрытие с обеих сторон — стыки не дают волосяных зазоров.
            var from = a - OverlapDeg;
            var to = a + StepDeg + OverlapDeg;

            var geo = new StreamGeometry();
            using (var ctx = geo.Open())
            {
                ctx.BeginFigure(OnCircle(centre, radius, from), false);
                ctx.ArcTo(
                    OnCircle(centre, radius, to),
                    new Size(radius, radius),
                    0,
                    isLargeArc: false,
                    SweepDirection.Clockwise);
                ctx.EndFigure(false);
            }

            var brush = new ImmutableSolidColorBrush(color, alpha);
            var pen = new Pen(brush, thickness) { LineCap = PenLineCap.Flat };
            band.Add((geo, pen));
        }

        return band;
    }

    public override void Render(DrawingContext context)
    {
        var w = Bounds.Width;
        var h = Bounds.Height;
        var thickness = StrokeThickness;
        if (w <= 0 || h <= 0 || thickness <= 0)
        {
            return;
        }

        var color = RingColor;
        var key = (w, h, argb: color.ToUInt32(), t: thickness);
        if (_band is null || _key != key)
        {
            _band = BuildBand(w, h, color, thickness);
            _key = key;
        }

        foreach (var (geo, pen) in _band)
        {
            context.DrawGeometry(null, pen, geo);
        }
    }
}
