package com.personal.guardian.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Stage 5 tier classification: the `@tier` syntax, which bundled entries are
 * borderline, and the tier of a text detection (pure JVM, real list).
 */
class KeywordTierTest {

    private val list = File("src/main/assets/text/keywords.txt").bufferedReader().use { KeywordList.parse(it) }
    private val matcher = KeywordMatcher(list)

    private fun tierOf(text: String): KeywordTier {
        val found = matcher.find(text)
        assertTrue("expected a match in: $text", found.isNotEmpty())
        return KeywordTier.of(found)
    }

    // ---- syntax ----

    @Test
    fun entriesAreExplicitByDefaultAndTierBlocksApplyUntilChanged() {
        val l = KeywordList.parse(
            sequenceOf(
                "alpha",
                "@tier borderline",
                "beta ~ gamma",
                "?=delta",
                "@fuse ab > cd",
                "@tier explicit",
                "epsilon",
            )
        )
        val tiers = l.entries.associate { it.term to it.tier }
        assertEquals(KeywordTier.EXPLICIT, tiers["alpha"])
        assertEquals(KeywordTier.BORDERLINE, tiers["beta"])
        assertEquals(KeywordTier.BORDERLINE, tiers["delta"])
        assertEquals("generated forms take the block's tier", KeywordTier.BORDERLINE, tiers["abcd"])
        assertEquals(KeywordTier.EXPLICIT, tiers["epsilon"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun anUnknownTierIsRejected() {
        KeywordList.parse(sequenceOf("@tier maybe", "x"))
    }

    // ---- the bundled list ----

    @Test
    fun theBundledBorderlineTierIsExactlyTheOwnerAddedItemsPlusSexyAndBusty() {
        val borderline = list.entries.filter { it.tier == KeywordTier.BORDERLINE }.map { it.term }.toSet()
        assertEquals(
            setOf(
                "sexy", "busty",
                "lingerie", "thong", "g-string", "gstring", "garter", "babydoll", "corset", "fishnets",
                "micro bikini", "lube", "lubricant", "magic wand", "aphrodisiac", "spanish fly",
                "لانجري", "قميص نوم", "بيبي دول", "كلوت فتله", "بدله رقص", "ملابس فاضحه", "مزلق", "جل مزلق",
            ),
            borderline
        )
        assertTrue("everything else is explicit", list.entries.count { it.tier == KeywordTier.EXPLICIT } > 600)
    }

    @Test
    fun coreVocabularyIsExplicit() {
        for (text in listOf("porn", "watch porn tonight", "send nudes", "blowjob", "cumshot", "sex", "نيك", "سكس", "كسمك", "p0rn"))
            assertEquals(text, KeywordTier.EXPLICIT, tierOf(text))
    }

    @Test
    fun borderlineWordsAloneAreBorderline() {
        for (text in listOf("sexy", "she looks sexy", "busty", "new lingerie collection", "a red thong", "corset top",
            "buy lube", "magic wand", "aphrodisiac", "fishnets", "لانجري", "قميص نوم احمر", "مزلق"))
            assertEquals(text, KeywordTier.BORDERLINE, tierOf(text))
    }

    @Test
    fun aBorderlineWordIsNotMadeExplicitByAnExplicitStemInsideIt() {
        // "sexy" is also explicit "sex" + the -y ending; the exact borderline entry decides.
        val found = matcher.find("sexy")
        assertTrue(found.any { it.term == "sexy" })
        assertTrue(found.all { it.tier == KeywordTier.BORDERLINE })
        assertEquals(KeywordTier.BORDERLINE, tierOf("sexyyy"))
        assertEquals("the stem itself stays explicit", KeywordTier.EXPLICIT, tierOf("sex video"))
    }

    @Test
    fun anExplicitTermAnywhereInTheDetectionMakesItExplicit() {
        assertEquals(KeywordTier.EXPLICIT, tierOf("sexy lingerie porn"))
        assertEquals(KeywordTier.EXPLICIT, tierOf("thong. Later: nudes"))
    }

    @Test
    fun aCorroborationOnlyTermDoesNotDecideTheTier() {
        // "booty" is corroboration-only (?booty): next to a borderline term it stays borderline.
        val found = matcher.find("thong booty")
        assertTrue(found.any { it.term == "booty" && it.corroborationOnly })
        assertEquals(KeywordTier.BORDERLINE, KeywordTier.of(found))
        assertEquals(KeywordTier.EXPLICIT, KeywordTier.of(matcher.find("porn booty")))
    }
}
