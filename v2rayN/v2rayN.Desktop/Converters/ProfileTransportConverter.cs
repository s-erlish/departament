using Avalonia.Data.Converters;
using ServiceLib.Models.Dto;
using v2rayN.Desktop.Common;

namespace v2rayN.Desktop.Converters;

/// <summary>
/// Maps a <see cref="ProfileItemModel"/> (bind the whole row item) to the Incy transport line
/// "NETWORK · SECURITY" (e.g. "TCP · REALITY"). Registered app-wide as
/// <c>{StaticResource ProfileTransport}</c>.
/// </summary>
public class ProfileTransportConverter : IValueConverter
{
    public object? Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
        => value is ProfileItemModel item ? ProfileDisplay.Transport(item) : string.Empty;

    public object? ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture) => null;
}
