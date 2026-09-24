package com.v2ray.ang.util

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [FlagUtil], whose one rule is that no flag beats a wrong flag: a remark earns a flag
 * only when it carries an explicit country marker, and anything ambiguous gets the globe.
 *
 * The cases below are the ones that actually broke: two-letter English words read as country codes
 * ("No limit", "IT support"), a title-cased word after a code that a case heuristic mistook for a
 * place name ("IT Support" -> Italy), lower-case and mismatched brackets, and a trailing flag emoji
 * that the tile lifted out but the name kept, showing it twice.
 */
class FlagUtilTest {

    private companion object {
        const val GLOBE = "🌐"          // 🌐
        const val NL = "🇳🇱" // 🇳🇱
        const val DE = "🇩🇪" // 🇩🇪
        const val SE = "🇸🇪" // 🇸🇪
        const val GB = "🇬🇧" // 🇬🇧
        const val IT = "🇮🇹" // 🇮🇹
    }

    private fun flagOf(remark: String): String =
        FlagUtil.resolveFlag(ProfileItem(configType = EConfigType.VLESS, remarks = remark))

    // ---- Explicit markers earn a flag -------------------------------------------------------

    @Test
    fun bracketedUpperCaseCodeIsAMarker() {
        assertEquals(NL, flagOf("[NL] Amsterdam"))
        assertEquals(SE, flagOf("[SE] 1"))
        assertEquals(IT, flagOf("Node (IT)"))
    }

    @Test
    fun knownPlaceNameIsAMarkerInEnglishAndRussian() {
        assertEquals(NL, flagOf("Amsterdam"))
        assertEquals(DE, flagOf("DE Frankfurt"))
        assertEquals(NL, flagOf("NL - Amsterdam"))
        assertEquals(DE, flagOf("Германия"))
        assertEquals(DE, flagOf("Германия 2"))
    }

    @Test
    fun leadingUpperCaseCodeAloneOrWithAnIndexIsAMarker() {
        assertEquals(DE, flagOf("DE"))
        assertEquals(SE, flagOf("SE-1"))
        assertEquals(NL, flagOf("NL 2"))
        assertEquals(SE, flagOf("SE2"))
    }

    @Test
    fun ukIsNotIsoAndMapsToGb() {
        assertEquals(GB, flagOf("UK"))
        assertEquals(GB, flagOf("UK London"))     // via the city name, which the table knows
        assertEquals(GB, flagOf("[UK] 3"))
        assertEquals(GB, FlagUtil.codeToFlag("UK"))
        assertEquals("GB", FlagUtil.parseCountryCode("UK-1"))
    }

    // ---- Ambiguity earns the globe ----------------------------------------------------------

    @Test
    fun twoLetterEnglishWordsAreNotCountryCodes() {
        assertEquals(GLOBE, flagOf("No limit"))   // not Norway
        assertEquals(GLOBE, flagOf("IT support")) // not Italy
        assertEquals(GLOBE, flagOf("in-1"))       // not India
        assertEquals(GLOBE, flagOf("us-west-2"))  // not the United States
    }

    /**
     * The regression this file exists for: the old heuristic called a title-cased word after a code
     * a place name, so "IT Support" flew the Italian flag. "IT Support" and "US East" are one shape
     * and nothing offline separates them, so neither gets a flag.
     */
    @Test
    fun aWordAfterACodeIsAmbiguousWithOrWithoutASeparator() {
        assertEquals(GLOBE, flagOf("IT Support"))
        assertEquals(GLOBE, flagOf("IT-Support"))
        assertEquals(GLOBE, flagOf("US East"))
        assertEquals(GLOBE, flagOf("NO LIMIT"))
        assertEquals(GLOBE, flagOf("AT Home"))
    }

    @Test
    fun aCodeInsideTheRemarkIsNotALeadingCode() {
        assertEquals(GLOBE, flagOf("Server IN 3"))
        assertEquals(GLOBE, flagOf("Fast NO 2"))
    }

    @Test
    fun bracketsMustMatchAndBeUpperCase() {
        // The name carries these, so the flag is right for a reason that is not the bracket...
        assertEquals(NL, flagOf("(nl) Amsterdam"))
        assertEquals(NL, flagOf("[NL) Amsterdam"))
        // ...and with the name gone, the bad bracket is worth nothing.
        assertEquals(GLOBE, flagOf("(nl) Server"))
        assertEquals(GLOBE, flagOf("[NL) Server"))
        assertEquals(GLOBE, flagOf("(NL] Server"))
        assertEquals(GLOBE, flagOf("Call (us) now"))
    }

    @Test
    fun emptyAndBlankRemarksGetTheGlobe() {
        assertEquals(GLOBE, flagOf(""))
        assertEquals(GLOBE, flagOf("   "))
        assertNull(FlagUtil.parseCountryCode(""))
        assertNull(FlagUtil.parseCountryCode(null))
    }

    /** No marker must read as "no answer", not as some default code. */
    @Test
    fun parseReturnsNullRatherThanAGuess() {
        assertNull(FlagUtil.parseCountryCode("No limit"))
        assertNull(FlagUtil.parseCountryCode("IT Support"))
        assertNull(FlagUtil.parseCountryCode("Server IN 3"))
        assertEquals("DE", FlagUtil.parseCountryCode("DE"))
        assertEquals("NL", FlagUtil.parseCountryCode("[NL] 1"))
    }

    // ---- A flag emoji already in the remark wins --------------------------------------------

    @Test
    fun aFlagEmojiInTheRemarkIsUsedWhereverItSits() {
        assertEquals(NL, flagOf("$NL Amsterdam"))
        assertEquals(NL, flagOf("Amsterdam $NL"))
        assertEquals(DE, flagOf("$DE IT Support")) // beats the ambiguity, and beats no flag at all
    }

    @Test
    fun extractFlagEmojiFindsOnlyCompletePairs() {
        assertNull(FlagUtil.extractFlagEmoji(null))
        assertNull(FlagUtil.extractFlagEmoji(""))
        assertNull(FlagUtil.extractFlagEmoji("Amsterdam"))
        assertNull(FlagUtil.extractFlagEmoji("🇳 half a flag"))
        assertEquals(NL, FlagUtil.extractFlagEmoji("$NL Amsterdam"))
        assertEquals(DE, FlagUtil.extractFlagEmoji("Frankfurt $DE"))
        assertEquals(NL, FlagUtil.extractFlagEmoji("$NL$DE")) // the first pair
    }

    @Test
    fun codeToFlagRejectsWhatIsNotAPairOfLetters() {
        assertEquals(NL, FlagUtil.codeToFlag("NL"))
        assertEquals(NL, FlagUtil.codeToFlag("nl"))
        assertEquals(GLOBE, FlagUtil.codeToFlag(""))
        assertEquals(GLOBE, FlagUtil.codeToFlag("N"))
        assertEquals(GLOBE, FlagUtil.codeToFlag("NLD"))
        assertEquals(GLOBE, FlagUtil.codeToFlag("N1"))
        assertEquals(GLOBE, FlagUtil.codeToFlag("НЛ")) // Cyrillic, not regional indicators
    }

    // ---- The name never repeats the flag the tile shows --------------------------------------

    @Test
    fun stripRemovesTheFlagWhereverItSits() {
        assertEquals("Amsterdam", FlagUtil.stripLeadingFlag("$NL Amsterdam"))
        assertEquals("Amsterdam", FlagUtil.stripLeadingFlag("Amsterdam $NL"))
        assertEquals("Amsterdam", FlagUtil.stripLeadingFlag("$NL-Amsterdam"))
        assertEquals("Amsterdam", FlagUtil.stripLeadingFlag("$NL · Amsterdam"))
        assertEquals("Server 1", FlagUtil.stripLeadingFlag("Server $NL 1"))
        assertEquals("Amsterdam", FlagUtil.stripLeadingFlag("$NL Amsterdam $NL"))
    }

    @Test
    fun stripLeavesRemarksThatHaveNoFlagAlone() {
        assertEquals("Amsterdam", FlagUtil.stripLeadingFlag("Amsterdam"))
        assertEquals("IT Support", FlagUtil.stripLeadingFlag("IT Support"))
        assertEquals("", FlagUtil.stripLeadingFlag(""))
        assertEquals("  spaced  ", FlagUtil.stripLeadingFlag("  spaced  "))
    }

    /** A name that is only a flag is odd; a name that is nothing is worse. */
    @Test
    fun stripKeepsTheRemarkWhenTheFlagIsAllThereIs() {
        assertEquals(NL, FlagUtil.stripLeadingFlag(NL))
        assertEquals(" $NL ", FlagUtil.stripLeadingFlag(" $NL "))
    }

    /**
     * The contract between the two call sites (MainRecyclerAdapter binds tvFlag and tvName from
     * these two calls; NotificationManager concatenates them): whatever the tile shows must be gone
     * from the name. Stripping only a leading flag broke this for "Amsterdam 🇳🇱".
     */
    @Test
    fun theNameNeverRepeatsTheTileFlag() {
        val remarks = listOf(
            "", "   ", "No limit", "IT support", "IT Support", "in-1", "us-west-2",
            "[NL] Amsterdam", "(nl) Amsterdam", "[NL) Amsterdam", "UK London", "Германия",
            "SE-1", "Server IN 3", "Amsterdam", "DE", "DE Frankfurt",
            "$NL Amsterdam", "Amsterdam $NL", "Server $DE 1", "$DE IT Support",
        )
        remarks.forEach { remark ->
            val tile = flagOf(remark)
            val name = FlagUtil.stripLeadingFlag(remark)
            if (tile != GLOBE) {
                assertFalse("name repeats the tile flag for \"$remark\": \"$name\"", name.contains(tile))
            }
        }
    }
}
