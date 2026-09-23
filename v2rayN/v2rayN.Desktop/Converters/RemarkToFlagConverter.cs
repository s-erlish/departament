using Avalonia.Data.Converters;
using v2rayN.Desktop.Common;

namespace v2rayN.Desktop.Converters;

/// <summary>
/// Maps a server remark string to a circular country-flag image
/// (<c>avares://v2rayN/Assets/Flags/&lt;iso&gt;.png</c>) via <see cref="FlagResolver"/>, with a
/// globe fallback (<c>xx.png</c>) whenever no country can be derived or its asset is not bundled.
///
/// Registered app-wide as <c>{StaticResource RemarkToFlag}</c> (see Assets/GlobalResources.axaml),
/// so a row template can bind an Image's Source to a remark:
/// <code>&lt;Image Source="{Binding Remarks, Converter={StaticResource RemarkToFlag}}" /&gt;</code>
/// Decoded bitmaps are cached per ISO code (there are only ~16 tiny PNGs) and are safely shared as
/// the Source of many list rows.
/// </summary>
public class RemarkToFlagConverter : IValueConverter
{
    private static readonly Dictionary<string, Bitmap?> Cache = new(StringComparer.Ordinal);

    public object? Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        var iso = FlagResolver.ResolveIso(value?.ToString());
        return LoadFlag(iso) ?? LoadFlag(FlagResolver.Fallback);
    }

    public object? ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        return null;
    }

    private static Bitmap? LoadFlag(string iso)
    {
        lock (Cache)
        {
            if (Cache.TryGetValue(iso, out var cached))
            {
                return cached;
            }

            Bitmap? bitmap = null;
            try
            {
                // AssetLoader.Open throws for a missing avares:// resource; the catch turns that
                // into the globe fallback (a resolved-but-unbundled ISO, e.g. "ca", lands here).
                var uri = new Uri($"{Global.AvaAssets}Flags/{iso}.png");
                using var stream = AssetLoader.Open(uri);
                bitmap = new Bitmap(stream);
            }
            catch
            {
                bitmap = null;
            }

            Cache[iso] = bitmap;
            return bitmap;
        }
    }
}
