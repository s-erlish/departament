package com.v2ray.ang.util

import com.v2ray.ang.dto.entities.ProfileItem
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves a country flag emoji for a server, Happ/Incy style.
 *
 * Layered strategy (cheapest first, all offline):
 *  1. A flag emoji already present anywhere in the server remark (e.g. "🇳🇱 Amsterdam").
 *  2. An explicit country marker in the remark — an upper-case bracketed "[NL]" code, a known
 *     country or city name ("Amsterdam", "Германия"), or an upper-case code opening it ("SE-1").
 *  3. A globe fallback.
 *
 * Whatever [resolveFlag] lifts out of the remark, [stripLeadingFlag] takes back out of the name —
 * the two are one contract, so the tile never shows a flag the name repeats.
 *
 * Rendering a regional-indicator emoji pair in a TextView is zero-asset and matches how
 * Remnawave/Happ/Incy show flags.
 */
object FlagUtil {

    private const val GLOBE = "🌐" // 🌐

    /** Regional indicator base; 'A' -> 0x1F1E6. */
    private const val REGIONAL_BASE = 0x1F1E6

    /**
     * Returns a flag emoji for the given profile, or a globe if none can be derived.
     *
     * MEMOISED, BECAUSE THIS RUNS IN `onBindViewHolder`. Both this and [stripLeadingFlag] are
     * called for every server row on every bind — i.e. once per row per scroll recycle, on the main
     * thread, in the middle of a frame — and the answer is a pure function of a remark that does
     * not change while the list is on screen.
     *
     * The cost when the remark carries no flag emoji of its own is not small: [parseCountryCode]
     * lowercases the whole string, runs a regex over it, then walks ~90 country/city names doing a
     * word-boundaried search for each. That is the NORMAL path for a Russian panel, which writes
     * «Германия 2» rather than «🇩🇪 Germany» — so on those lists every frame of a scroll was paying
     * for the same hundred string searches over and over.
     */
    fun resolveFlag(profile: ProfileItem): String = memo(flagMemo, profile.remarks) { remark ->
        extractFlagEmoji(remark)
            ?: parseCountryCode(remark)?.let { codeToFlag(it) }
            ?: GLOBE
    }

    /**
     * Extracts an existing regional-indicator flag emoji (two consecutive indicators) from [text].
     */
    fun extractFlagEmoji(text: String?): String? {
        if (text.isNullOrEmpty()) return null
        var i = 0
        val len = text.length
        while (i < len) {
            val cp = text.codePointAt(i)
            if (cp in REGIONAL_BASE..(REGIONAL_BASE + 25)) {
                val next = i + Character.charCount(cp)
                if (next < len) {
                    val cp2 = text.codePointAt(next)
                    if (cp2 in REGIONAL_BASE..(REGIONAL_BASE + 25)) {
                        return text.substring(i, next + Character.charCount(cp2))
                    }
                }
            }
            i += Character.charCount(cp)
        }
        return null
    }

    /**
     * Removes the flag [resolveFlag] displays — wherever it sits in the remark — together with the
     * punctuation that separated it, so the name beside the tile doesn't show the same flag twice.
     * Stripping only a *leading* flag left "Amsterdam 🇳🇱" rendering it in the tile and again in the
     * name. No-op when the remark carries no flag; when the flag is all the remark has, the remark
     * is returned untouched, because a name that is only a flag beats a name that is nothing.
     */
    fun stripLeadingFlag(remark: String): String = memo(strippedMemo, remark) { text ->
        val flag = extractFlagEmoji(text)
        if (flag == null) {
            text
        } else {
            var out = text
            while (true) {
                val at = out.indexOf(flag)
                if (at < 0) break
                val head = out.substring(0, at).trimEnd(*FLAG_SEPARATORS)
                val tail = out.substring(at + flag.length).trimStart(*FLAG_SEPARATORS)
                out = if (head.isEmpty() || tail.isEmpty()) head + tail else "$head $tail"
            }
            out.trim().ifBlank { text }
        }
    }

    /**
     * The two memos above, and the one rule that keeps them from being a leak.
     *
     * A remark is at most a few dozen characters and there is one per server, so the natural bound
     * is the size of the server list. [MEMO_LIMIT] is generous against that and is a backstop
     * against the one thing that could make the key space unbounded — a подписка refresh that
     * renames everything on every run. It is cleared wholesale rather than evicted one entry at a
     * time: the next hundred binds refill exactly what is on screen, and an LRU here would cost
     * more bookkeeping than the parse it is protecting.
     *
     * `ConcurrentHashMap` because the two readers are in different processes AND on different
     * threads: the list binds on the UI process's main thread, `NotificationManager` builds the
     * shade's title on the daemon's.
     */
    private fun memo(cache: ConcurrentHashMap<String, String>, key: String, compute: (String) -> String): String {
        val hit = cache[key]
        if (hit != null) return hit
        val value = compute(key)
        if (cache.size >= MEMO_LIMIT) cache.clear()
        cache[key] = value
        return value
    }

    private const val MEMO_LIMIT = 512
    private val flagMemo = ConcurrentHashMap<String, String>()
    private val strippedMemo = ConcurrentHashMap<String, String>()

    /**
     * Converts a 2-letter country code (e.g. "NL") to its flag emoji. Aliases such as the
     * non-ISO "UK" are normalised first, otherwise the pair renders as boxed letters.
     */
    fun codeToFlag(code: String): String {
        if (code.length != 2) return GLOBE
        val upper = normalizeCode(code.uppercase())
        val a = upper[0]
        val b = upper[1]
        if (a !in 'A'..'Z' || b !in 'A'..'Z') return GLOBE
        val sb = StringBuilder()
        sb.appendCodePoint(REGIONAL_BASE + (a - 'A'))
        sb.appendCodePoint(REGIONAL_BASE + (b - 'A'))
        return sb.toString()
    }

    /**
     * Parses a country code from a remark. Only an explicit marker counts — an upper-case bracketed
     * code, a known country/city name (English or Russian), or an upper-case code opening the remark
     * and not followed by a word. Matching any two-letter token turned "No limit" into Norway,
     * "IT support" into Italy and "in-1" into India — a missing flag is cheaper than a wrong one.
     * Returns ISO-2 or null.
     */
    fun parseCountryCode(remark: String?): String? {
        if (remark.isNullOrBlank()) return null
        bracketedCode(remark)?.let { return it }
        val lower = remark.lowercase()
        NAME_TABLES.forEach { table ->
            table.forEach { (name, code) ->
                if (containsWord(lower, name)) return code
            }
        }
        return leadingCode(remark)
    }

    /**
     * "[NL] Amsterdam" or "Amsterdam (US)" — an upper-case code inside *matching* brackets. Case is
     * not free: "it", "no", "in", "at" and "us" are ordinary words, so a lower-case parenthetical
     * like "(no)" is prose far more often than a country tag. Mismatched delimiters ("[NL)") are a
     * typo, not a marker, so they buy no flag either.
     */
    private fun bracketedCode(remark: String): String? {
        BRACKETED_CODE.findAll(remark).forEach { m ->
            val c = m.groupValues[1].ifEmpty { m.groupValues[2] }
            if (isKnownCode(c)) return normalizeCode(c)
        }
        return null
    }

    /**
     * "DE", "SE-1", "NL 2", "US2" — the code alone or the code with an index. It must open the
     * remark in upper case, and the next word-ish thing after it must be a number or nothing.
     *
     * A word after the code buys no flag, with or without a separator between them: "IT Support",
     * "US East", "IT-Support", "US-East" and "IR تهران" are one shape, and offline nothing tells the
     * place name from the prose inside it — the old case-based guess read "IT Support" as a place
     * and flew the Italian flag over a helpdesk. Either reading mislabels half the servers that use
     * the other one, so the shape gets nothing. A place name after a code still counts when the name
     * table knows it ("NL - Amsterdam", "DE Frankfurt"): that is a marker, not a guess.
     */
    private fun leadingCode(remark: String): String? {
        var start = 0
        while (start < remark.length && !remark[start].isLetter()) start++
        if (start + 2 > remark.length) return null
        val a = remark[start]
        val b = remark[start + 1]
        if (a !in 'A'..'Z' || b !in 'A'..'Z') return null
        var after = start + 2
        while (after < remark.length && !remark[after].isLetterOrDigit()) after++
        if (after < remark.length && remark[after].isLetter()) return null
        val c = "$a$b"
        return if (isKnownCode(c)) normalizeCode(c) else null
    }

    /** Word-boundaried search, so "india" doesn't match "Indiana" nor "usa" "Usain". */
    private fun containsWord(haystack: String, needle: String): Boolean {
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return false
            val before = at == 0 || !haystack[at - 1].isLetter()
            val end = at + needle.length
            val after = end == haystack.length || !haystack[end].isLetter()
            if (before && after) return true
            from = at + 1
        }
    }

    private fun isKnownCode(code: String) = code in ISO2_CODES || code in CODE_ALIASES

    private fun normalizeCode(code: String) = CODE_ALIASES[code] ?: code

    private val BRACKETED_CODE = Regex("""\[\s*([A-Z]{2})\s*\]|\(\s*([A-Z]{2})\s*\)""")

    /** Punctuation panels put between a flag and the name; it goes when the flag goes. */
    private val FLAG_SEPARATORS = charArrayOf(' ', '\t', '-', '–', '—', '·', '|', ':', ',')

    // Common country names (English) → ISO-2. Kept small and offline; extend as needed.
    private val COUNTRY_NAME_TO_CODE = linkedMapOf(
        "netherlands" to "NL", "amsterdam" to "NL",
        "germany" to "DE", "frankfurt" to "DE",
        "united states" to "US", "usa" to "US", "america" to "US",
        "united kingdom" to "GB", "britain" to "GB", "london" to "GB",
        "france" to "FR", "paris" to "FR",
        "finland" to "FI", "helsinki" to "FI",
        "sweden" to "SE", "stockholm" to "SE",
        "denmark" to "DK",
        "norway" to "NO",
        "poland" to "PL",
        "latvia" to "LV",
        "lithuania" to "LT",
        "estonia" to "EE",
        "russia" to "RU", "moscow" to "RU",
        "ukraine" to "UA",
        "turkey" to "TR", "istanbul" to "TR",
        "japan" to "JP", "tokyo" to "JP",
        "singapore" to "SG",
        "hong kong" to "HK",
        "korea" to "KR",
        "canada" to "CA",
        "switzerland" to "CH",
        "spain" to "ES",
        "italy" to "IT",
        "austria" to "AT",
        "czech" to "CZ",
        "iran" to "IR",
        "india" to "IN",
        "australia" to "AU",
        "brazil" to "BR",
        "emirates" to "AE", "dubai" to "AE",
    )

    // The same table in Russian, because this deployment's panel is Russian and writes remarks
    // like "Германия" or "Нидерланды 2" — which had no marker the English table or the upper-case
    // leading-code rule could see, so every Russian-named host fell through to the globe. Cyrillic
    // cannot collide with the ASCII prose the leading-code rule exists to reject ("IT support"),
    // and the match is word-boundaried like the English one, so this adds flags without adding
    // wrong ones. Declined forms ("Германии") deliberately miss: a globe, not a guess.
    private val COUNTRY_NAME_TO_CODE_RU = linkedMapOf(
        "нидерланды" to "NL", "голландия" to "NL", "амстердам" to "NL",
        "германия" to "DE", "франкфурт" to "DE",
        "сша" to "US", "америка" to "US",
        "великобритания" to "GB", "британия" to "GB", "англия" to "GB", "лондон" to "GB",
        "франция" to "FR", "париж" to "FR",
        "финляндия" to "FI", "хельсинки" to "FI",
        "швеция" to "SE", "стокгольм" to "SE",
        "дания" to "DK",
        "норвегия" to "NO",
        "польша" to "PL",
        "латвия" to "LV",
        "литва" to "LT",
        "эстония" to "EE",
        "россия" to "RU", "москва" to "RU",
        "украина" to "UA",
        "турция" to "TR", "стамбул" to "TR",
        "япония" to "JP", "токио" to "JP",
        "сингапур" to "SG",
        "гонконг" to "HK",
        "корея" to "KR",
        "канада" to "CA",
        "швейцария" to "CH",
        "испания" to "ES",
        "италия" to "IT",
        "австрия" to "AT",
        "чехия" to "CZ",
        "иран" to "IR",
        "индия" to "IN",
        "австралия" to "AU",
        "бразилия" to "BR",
        "оаэ" to "AE", "эмираты" to "AE", "дубай" to "AE",
    )

    private val NAME_TABLES = listOf(COUNTRY_NAME_TO_CODE, COUNTRY_NAME_TO_CODE_RU)

    private val ISO2_CODES = setOf(
        "NL", "DE", "US", "GB", "FR", "FI", "SE", "DK", "NO", "PL", "LV", "LT", "EE",
        "RU", "UA", "TR", "JP", "SG", "HK", "KR", "CA", "CH", "ES", "IT", "AT", "CZ", "IR",
        "IN", "AU", "BR", "AE",
    )

    // Codes panels actually write that are not ISO 3166-1 alpha-2, so they have no flag emoji.
    private val CODE_ALIASES = mapOf("UK" to "GB")
}
