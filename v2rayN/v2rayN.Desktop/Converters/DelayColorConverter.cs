using Avalonia.Data.Converters;
using Avalonia.Styling;

namespace v2rayN.Desktop.Converters;

/// <summary>
/// Красит значение задержки (пинга) ОДНИМ тема-зависимым чернилом — без зелёного/красного «хорошо/плохо».
/// Владелец: пинг должен следовать теме — «на светлой синий, на тёмной белый». Поэтому:
///   • Светлая  → <c>Brush.Accent</c>  (синий #4C8DFF; в mono-светлой — серый accent-токен);
///   • Тёмная   → <c>Brush.OnSurface</c> (белый #F2F4F8; в mono-тёмной — светло-серые чернила).
/// Кисть резолвим из ресурсов приложения по активному <see cref="ThemeVariant"/> (учитывает mono-оверлей),
/// поэтому цвет совпадает с текущей темой. Значение показывается лишь для реальных числовых результатов
/// (видимость гейтит DelayResultConverter; во время теста — спиннер). Если ресурсы недоступны — защитный
/// откат к литеральным токенам Incy, чтобы конвертер никогда не ронял привязку.
/// </summary>
public class DelayColorConverter : IValueConverter
{
    // Литеральный откат = базовые токены Incy, если ресурсы темы почему-то не резолвятся.
    private static readonly IBrush _blueFallback = new SolidColorBrush(Color.Parse("#4C8DFF"));  // Brush.Accent (Light)
    private static readonly IBrush _whiteFallback = new SolidColorBrush(Color.Parse("#F2F4F8")); // Brush.OnSurface (Dark)

    public object? Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        // Единое тема-адаптивное чернило: синий на светлой, белый на тёмной (mono-маппинг — через токены).
        var light = Application.Current?.ActualThemeVariant == ThemeVariant.Light;
        return light
            ? Resolve("Brush.Accent", _blueFallback)
            : Resolve("Brush.OnSurface", _whiteFallback);
    }

    // Резолвит кисть темы по активному ThemeVariant (учитывает mono-оверлей). Защитно: любой сбой
    // доступа к ресурсам → литеральный откат, чтобы конвертер никогда не ронял привязку.
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
            // проваливаемся в откат
        }
        return fallback;
    }

    public object? ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        return null;
    }
}
