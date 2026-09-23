using System.Diagnostics.CodeAnalysis;
using System.Globalization;

namespace ServiceLib.Services.AppUpdate;

/// <summary>
/// Версия departament по SemVer 2.0.0: <c>X.Y.Z</c> для выпуска, <c>X.Y.Z-rc.N</c> для
/// предварительного. Ей сравниваются версия, которая запущена, и версия, которую предлагает лента
/// выпусков, поэтому разбор строгий, а сравнение — по правилу 11 SemVer:
/// <c>1.0.0-rc.2 &lt; 1.0.0-rc.10 &lt; 1.0.0 &lt; 1.0.1</c>.
///
/// <para>Сравнение, которым апстрим сверял версии (<c>SemanticVersion</c>), хвост <c>-rc.N</c> не понимало
/// вовсе: <c>int.Parse("0-rc")</c> падал, и такая версия молча становилась 0.0.0. Предварительный выпуск
/// ни с чем не сравнивался, а rc.2 и rc.10 были для него одним и тем же.</para>
///
/// <para>Нестрогая строка (<c>1.2</c>, <c>v1.2.0.1</c>, <c>1.02.0</c>, <c>latest</c>) — не версия:
/// <see cref="TryParse"/> её отвергает. Лента с таким тегом ничего не предложит, и это правильно:
/// опечатка в теге не должна превращаться в установку.</para>
/// </summary>
public sealed class AppVersion : IComparable<AppVersion>, IEquatable<AppVersion>
{
    // Грамматика SemVer 2.0.0 (semver.org, «Is there a suggested regular expression»), плюс необязательная
    // «v» в начале — так выглядят теги выпусков.
    private static readonly Regex Grammar = new(
        @"^[vV]?(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)" +
        @"(?:-((?:0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\.(?:0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?" +
        @"(?:\+([0-9a-zA-Z-]+(?:\.[0-9a-zA-Z-]+)*))?$",
        RegexOptions.CultureInvariant);

    private AppVersion(int major, int minor, int patch, string[] preRelease)
    {
        Major = major;
        Minor = minor;
        Patch = patch;
        PreRelease = preRelease;
    }

    public int Major { get; }
    public int Minor { get; }
    public int Patch { get; }

    /// <summary>Идентификаторы после «-»: для <c>1.0.0-rc.2</c> это <c>["rc", "2"]</c>. Пусто у выпуска.</summary>
    public IReadOnlyList<string> PreRelease { get; }

    public bool IsPreRelease => PreRelease.Count > 0;

    /// <summary>
    /// Разбирает версию. Метаданные сборки после «+» допускаются и в сравнении не участвуют — так
    /// выглядит версия самого приложения: SDK дописывает к ней хеш коммита (<c>1.0.0-rc.2+d163c4f…</c>).
    /// </summary>
    public static bool TryParse(string? text, [NotNullWhen(true)] out AppVersion? version)
    {
        version = null;
        if (string.IsNullOrWhiteSpace(text))
        {
            return false;
        }
        var m = Grammar.Match(text.Trim());
        if (!m.Success
            || !int.TryParse(m.Groups[1].Value, NumberStyles.None, CultureInfo.InvariantCulture, out var major)
            || !int.TryParse(m.Groups[2].Value, NumberStyles.None, CultureInfo.InvariantCulture, out var minor)
            || !int.TryParse(m.Groups[3].Value, NumberStyles.None, CultureInfo.InvariantCulture, out var patch))
        {
            return false;
        }
        var pre = m.Groups[4].Success ? m.Groups[4].Value.Split('.') : [];
        version = new AppVersion(major, minor, patch, pre);
        return true;
    }

    /// <summary>
    /// Тег выпуска: та же версия, но без метаданных сборки. Тег становится частью адреса загрузки
    /// (<c>/releases/download/&lt;тег&gt;/…</c>), поэтому в нём допустимы только буквы, цифры, «.», «-» и
    /// ведущая «v» — никаких «/», «?», «%» и «+», которые могли бы увести адрес в сторону.
    /// </summary>
    public static bool TryParseTag(string? tag, [NotNullWhen(true)] out AppVersion? version)
    {
        version = null;
        return tag is not null && !tag.Contains('+') && TryParse(tag, out version) && tag.Trim() == tag;
    }

    public int CompareTo(AppVersion? other)
    {
        if (other is null)
        {
            return 1;
        }
        var c = Major.CompareTo(other.Major);
        if (c == 0)
        {
            c = Minor.CompareTo(other.Minor);
        }
        if (c == 0)
        {
            c = Patch.CompareTo(other.Patch);
        }
        if (c != 0)
        {
            return c;
        }

        // Правило 11.3: выпуск старше любой своей предварительной версии.
        if (!IsPreRelease || !other.IsPreRelease)
        {
            return other.IsPreRelease.CompareTo(IsPreRelease);
        }

        // Правило 11.4: по идентификаторам слева направо; числовые — как числа и младше буквенных,
        // буквенные — побайтно (ASCII); при равном начале старше та, у которой идентификаторов больше.
        for (var i = 0; i < Math.Min(PreRelease.Count, other.PreRelease.Count); i++)
        {
            c = CompareIdentifier(PreRelease[i], other.PreRelease[i]);
            if (c != 0)
            {
                return c;
            }
        }
        return PreRelease.Count.CompareTo(other.PreRelease.Count);
    }

    private static int CompareIdentifier(string a, string b)
    {
        var aNum = a.All(char.IsAsciiDigit);
        var bNum = b.All(char.IsAsciiDigit);
        if (aNum && bNum)
        {
            // Числа без ведущих нулей (их отсекает грамматика): длиннее — значит больше, при равной длине
            // решает побайтное сравнение. Так «rc.99999999999» не упирается в размер int.
            var byLength = a.Length.CompareTo(b.Length);
            return byLength != 0 ? byLength : string.CompareOrdinal(a, b);
        }
        if (aNum != bNum)
        {
            return aNum ? -1 : 1;
        }
        return Math.Sign(string.CompareOrdinal(a, b));
    }

    public bool Equals(AppVersion? other) => other is not null && CompareTo(other) == 0;

    public override bool Equals(object? obj) => obj is AppVersion other && Equals(other);

    public override int GetHashCode() => HashCode.Combine(Major, Minor, Patch, string.Join('.', PreRelease));

    public static bool operator >(AppVersion a, AppVersion b) => a.CompareTo(b) > 0;

    public static bool operator <(AppVersion a, AppVersion b) => a.CompareTo(b) < 0;

    public static bool operator >=(AppVersion a, AppVersion b) => a.CompareTo(b) >= 0;

    public static bool operator <=(AppVersion a, AppVersion b) => a.CompareTo(b) <= 0;

    /// <summary><c>1.2.0</c> или <c>1.2.0-rc.3</c> — без «v» и без метаданных сборки.</summary>
    public override string ToString() =>
        IsPreRelease ? $"{Major}.{Minor}.{Patch}-{string.Join('.', PreRelease)}" : $"{Major}.{Minor}.{Patch}";
}
