using Avalonia.Data.Converters;
using ServiceLib.Enums;
using ServiceLib.Models.Dto;
using departament.Desktop.Common;

namespace departament.Desktop.Converters;

/// <summary>
/// Maps to the Incy protocol-chip token (upper-case, e.g. "VLESS"). Bind either the whole
/// <see cref="ProfileItemModel"/> row (so a CUSTOM node shows its introspected real protocol) or a
/// bare <see cref="EConfigType"/>. Registered app-wide as <c>{StaticResource ConfigTypeToProtocol}</c>.
/// </summary>
public class ConfigTypeToProtocolConverter : IValueConverter
{
    public object? Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
        => value switch
        {
            ProfileItemModel item => ProfileDisplay.Protocol(item),
            EConfigType t => ProfileDisplay.Protocol(t),
            _ => string.Empty,
        };

    public object? ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture) => null;
}
