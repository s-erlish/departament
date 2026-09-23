using System.Diagnostics.CodeAnalysis;

namespace ServiceLib.Services.AppUpdate;

/// <summary>
/// Сверка пакета с его контрольной суммой из того же выпуска
/// (<see cref="AppUpdateChannel.ChecksumAssetName"/>). Пакет, который не сошёлся, удаляется и до
/// установщика не доходит: обрыв связи, недокачанный файл, подмена по дороге — всё это ловится здесь.
/// </summary>
public static class AppUpdateChecksum
{
    /// <summary>
    /// Разбирает файл суммы в формате sha256sum: <c>&lt;64 hex&gt;  departament-windows-x64.zip</c>.
    /// Допускаются «*» перед именем (двоичный режим sha256sum), заглавные буквы в сумме и CRLF; имя,
    /// если указано, обязано совпасть с <paramref name="expectedName"/>, строка суммы — одна.
    /// </summary>
    /// <returns>Сумма в нижнем регистре или false, если файл не такой.</returns>
    public static bool TryParse(string? content, string expectedName, [NotNullWhen(true)] out string? sha256)
    {
        sha256 = null;
        var lines = (content ?? string.Empty)
            .Split('\n')
            .Select(l => l.TrimEnd('\r'))
            .Where(l => l.Trim().Length > 0)
            .ToList();
        if (lines.Count != 1)
        {
            return false;
        }

        var line = lines[0].Trim();
        var space = line.IndexOfAny([' ', '\t']);
        var hex = space < 0 ? line : line[..space];
        if (hex.Length != 64 || !hex.All(char.IsAsciiHexDigit))
        {
            return false;
        }
        if (space >= 0)
        {
            var name = line[space..].Trim().TrimStart('*');
            if (!string.Equals(name, expectedName, StringComparison.Ordinal))
            {
                return false;
            }
        }
        sha256 = hex.ToLowerInvariant();
        return true;
    }

    /// <summary>SHA-256 файла в нижнем регистре.</summary>
    public static async Task<string> ComputeAsync(string path, CancellationToken token = default)
    {
        await using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 1 << 16, useAsync: true);
        var hash = await SHA256.HashDataAsync(stream, token);
        return Convert.ToHexStringLower(hash);
    }

    /// <summary>Совпадает ли файл с ожидаемой суммой.</summary>
    public static async Task<bool> MatchesAsync(string path, string expectedSha256, CancellationToken token = default)
    {
        var actual = await ComputeAsync(path, token);
        return string.Equals(actual, expectedSha256, StringComparison.OrdinalIgnoreCase);
    }
}
