using System.Text.RegularExpressions;

namespace v2rayN.Desktop.Common;

/// <summary>
/// Resolves an ISO-3166 alpha-2 country code (lowercase) for a server remark so the desktop UI
/// can show a circular flag asset (<c>Assets/Flags/&lt;iso&gt;.png</c>).
///
/// C# port of the Android <c>com.v2ray.ang.util.FlagUtil</c>. On Android flags are rendered as
/// regional-indicator emoji (zero-asset); on Windows/Avalonia those code points do not render as
/// flags, so the same remark → country logic here instead yields an ISO code used to pick a
/// bundled PNG. Layered strategy (cheapest first, all offline):
///  1. A regional-indicator flag emoji already present in the remark (e.g. "🇳🇱 Amsterdam").
///  2. A known country name, or a standalone ISO-2 token, parsed from the remark.
///  3. The globe fallback ("xx").
/// </summary>
public static class FlagResolver
{
    /// <summary>Fallback ISO code — maps to <c>Assets/Flags/xx.png</c> (a globe).</summary>
    public const string Fallback = "xx";

    /// <summary>Regional-indicator base: 'A' → U+1F1E6.</summary>
    private const int RegionalBase = 0x1F1E6;

    /// <summary>Regional-indicator for 'Z'.</summary>
    private const int RegionalLast = RegionalBase + 25;

    /// <summary>
    /// Returns an ISO-3166 alpha-2 code (lowercase, e.g. "nl") for the given remark, or
    /// <see cref="Fallback"/> ("xx") when none can be derived. Port of FlagUtil.resolveFlag.
    /// </summary>
    public static string ResolveIso(string? remark)
    {
        var iso = ExtractFlagEmojiIso(remark) ?? ParseCountryCode(remark);
        return NormalizeIso(iso).ToLowerInvariant();
    }

    /// <summary>
    /// Extracts the ISO-2 code from the first regional-indicator flag emoji pair in
    /// <paramref name="text"/> (e.g. 🇳🇱 → "NL"), or null when there is none.
    /// Port of FlagUtil.extractFlagEmoji (returning the ISO code instead of the emoji).
    /// </summary>
    public static string? ExtractFlagEmojiIso(string? text)
    {
        if (FindFlagPair(text, 0, false, out var firstCp, out var secondCp) < 0)
        {
            return null;
        }

        var a = (char)('A' + (firstCp - RegionalBase));
        var b = (char)('A' + (secondCp - RegionalBase));
        return new string(new[] { a, b });
    }

    /// <summary>
    /// Removes a leading flag emoji (and a following separator) from a remark so a server name does
    /// not duplicate the flag shown in its tile. No-op when there is no leading flag.
    /// Port of FlagUtil.stripLeadingFlag.
    /// </summary>
    public static string StripLeadingFlag(string? remark)
    {
        if (string.IsNullOrEmpty(remark))
        {
            return remark ?? string.Empty;
        }

        var t = remark.TrimStart();
        // A flag emoji only counts when it is at the very start of the (trimmed) remark.
        var end = FindFlagPair(t, 0, true, out _, out _);
        if (end < 0)
        {
            return remark;
        }

        var stripped = t[end..].TrimStart(' ', '-', '·', '|', ':', '\t');
        return string.IsNullOrWhiteSpace(stripped) ? remark : stripped;
    }

    /// <summary>
    /// Parses an ISO-2 code from a remark: a known country name (English), or a standalone
    /// word-boundaried 2-letter token from the ISO-2 whitelist. Returns null when nothing matches.
    /// Port of FlagUtil.parseCountryCode.
    /// </summary>
    public static string? ParseCountryCode(string? remark)
    {
        if (string.IsNullOrWhiteSpace(remark))
        {
            return null;
        }

        var lower = remark.ToLowerInvariant();
        foreach (var (name, code) in CountryNameToCode)
        {
            if (lower.Contains(name, StringComparison.Ordinal))
            {
                return code;
            }
        }

        // ==================== Двухбуквенный код страны в имени ====================
        //  Код берём ТОЛЬКО из начала или из конца имени. Так его и пишут — «IT-Milan», «us-west-1»,
        //  «nl-ams-3», «UK London», «Poland RU», — а в СЕРЕДИНЕ имени два подряд стоящих латинских
        //  символа почти всегда обычное слово, а не страна. Прежний сквозной скан брал первое
        //  попавшееся совпадение где угодно в строке, и рядом с именем вставал флаг чужой страны:
        //  «Server in EU» → Индия (слово «in» стоит РАНЬШЕ «EU»), «Node at edge» → Австрия,
        //  «Fast IT node» → Италия.
        //
        //  Второе ограничение оставлено прежним: ЧЕТЫРЕ кода (IN · NO · IT · AT) совпадают с
        //  обычными английскими словами, и их принимаем только записанными ПРОПИСНЫМИ. Одно
        //  правило другому не мешает: «No limit 01» стоит в начале, но записано не прописными,
        //  поэтому Норвегией не становится.
        //
        //  Границы считаем по ОБРЕЗАННОЙ строке, чтобы пробелы по краям не мешали коду быть
        //  «в начале» или «в конце».
        var startBound = 0;
        while (startBound < remark.Length && char.IsWhiteSpace(remark[startBound]))
        {
            startBound++;
        }
        var endBound = remark.Length;
        while (endBound > startBound && char.IsWhiteSpace(remark[endBound - 1]))
        {
            endBound--;
        }

        foreach (Match m in TwoLetterToken.Matches(remark))
        {
            var atStart = m.Index == startBound;
            var atEnd = m.Index + m.Length == endBound;
            if (!atStart && !atEnd)
            {
                continue;
            }

            var token = m.Groups[1].Value;
            var c = token.ToUpperInvariant();
            if (!Iso2Codes.Contains(c))
            {
                continue;
            }

            if (AmbiguousWordCodes.Contains(c) && !string.Equals(token, c, StringComparison.Ordinal))
            {
                continue;
            }

            return c;
        }

        return null;
    }

    // ----------------------------------------------------------------------------------------

    /// <summary>Maps aliases to the ISO code whose asset we bundle (UK → GB); else unchanged.</summary>
    private static string NormalizeIso(string? iso)
    {
        if (string.IsNullOrEmpty(iso))
        {
            return Fallback;
        }

        return iso.ToUpperInvariant() switch
        {
            "UK" => "GB",
            _ => iso,
        };
    }

    /// <summary>
    /// Scans <paramref name="text"/> from <paramref name="start"/> for a regional-indicator pair.
    /// When <paramref name="atStartOnly"/> is true only the very first code point is considered.
    /// Returns the UTF-16 index just past the pair (and its two code points via out params), or -1.
    /// </summary>
    private static int FindFlagPair(string? text, int start, bool atStartOnly, out int firstCp, out int secondCp)
    {
        firstCp = 0;
        secondCp = 0;
        if (string.IsNullOrEmpty(text))
        {
            return -1;
        }

        var len = text.Length;
        var i = start;
        while (i < len)
        {
            var cp = CodePointAt(text, i);
            if (cp >= RegionalBase && cp <= RegionalLast)
            {
                var next = i + CharCount(cp);
                if (next < len)
                {
                    var cp2 = CodePointAt(text, next);
                    if (cp2 >= RegionalBase && cp2 <= RegionalLast)
                    {
                        firstCp = cp;
                        secondCp = cp2;
                        return next + CharCount(cp2);
                    }
                }
            }

            if (atStartOnly)
            {
                return -1;
            }

            i += CharCount(cp);
        }

        return -1;
    }

    /// <summary>Java-style codePointAt: the full code point at a valid surrogate pair, else the char value.</summary>
    private static int CodePointAt(string s, int index)
    {
        var c = s[index];
        if (char.IsHighSurrogate(c) && index + 1 < s.Length && char.IsLowSurrogate(s[index + 1]))
        {
            return char.ConvertToUtf32(c, s[index + 1]);
        }

        return c;
    }

    /// <summary>UTF-16 code units for a code point (astral → 2, BMP or lone surrogate → 1).</summary>
    private static int CharCount(int codePoint) => codePoint > 0xFFFF ? 2 : 1;

    private static readonly Regex TwoLetterToken = new(@"\b([A-Za-z]{2})\b", RegexOptions.CultureInvariant);

    // Common English country / city names → ISO-2. Port of FlagUtil.COUNTRY_NAME_TO_CODE, kept in
    // order (specific matches first, generic "europe" last so e.g. "Germany (Europe)" → DE).
    // "europe"/EU is added on top of the Android map because the desktop flag set includes eu.png.
    private static readonly (string Name, string Code)[] CountryNameToCode =
    {
        ("netherlands", "NL"), ("amsterdam", "NL"),
        ("germany", "DE"), ("frankfurt", "DE"),
        ("united states", "US"), ("usa", "US"), ("america", "US"),
        ("united kingdom", "GB"), ("britain", "GB"), ("london", "GB"),
        ("france", "FR"), ("paris", "FR"),
        ("finland", "FI"), ("helsinki", "FI"),
        ("sweden", "SE"), ("stockholm", "SE"),
        ("denmark", "DK"),
        ("norway", "NO"),
        ("poland", "PL"),
        ("latvia", "LV"),
        ("lithuania", "LT"),
        ("estonia", "EE"),
        ("russia", "RU"), ("moscow", "RU"),
        ("ukraine", "UA"),
        ("turkey", "TR"), ("istanbul", "TR"),
        ("japan", "JP"), ("tokyo", "JP"),
        ("singapore", "SG"),
        ("hong kong", "HK"),
        ("korea", "KR"),
        ("canada", "CA"),
        ("switzerland", "CH"),
        ("spain", "ES"),
        ("italy", "IT"),
        ("austria", "AT"),
        ("czech", "CZ"),
        ("iran", "IR"),
        ("india", "IN"),
        ("australia", "AU"),
        ("brazil", "BR"),
        ("emirates", "AE"), ("dubai", "AE"),
        ("european union", "EU"), ("europe", "EU"),
    };

    //  Коды, неотличимые от обычных английских слов: их принимаем только прописными (см. выше).
    private static readonly HashSet<string> AmbiguousWordCodes = new(StringComparer.Ordinal) { "IN", "NO", "IT", "AT" };

    // Whitelist for the standalone 2-letter token match. Port of FlagUtil.ISO2_CODES (+ EU).
    private static readonly HashSet<string> Iso2Codes = new(StringComparer.Ordinal)
    {
        "NL", "DE", "US", "GB", "UK", "FR", "FI", "SE", "DK", "NO", "PL", "LV", "LT", "EE",
        "RU", "UA", "TR", "JP", "SG", "HK", "KR", "CA", "CH", "ES", "IT", "AT", "CZ", "IR",
        "IN", "AU", "BR", "AE", "EU",
    };
}
