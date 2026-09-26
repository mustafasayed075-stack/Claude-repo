package com.personal.guardian.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure-JVM tests for the Stage 4 coverage expansion (README "Coverage expansion"):
 * glued compounds (`@fuse`), genital / private-area terms, adult clothing and sex
 * toys — each with newly-matched and correctly-excluded cases.
 */
class KeywordCoverageTest {

    private val bundled: KeywordMatcher by lazy {
        KeywordMatcher(File("src/main/assets/text/keywords.txt").bufferedReader().use { KeywordList.parse(it) })
    }

    private fun matcher(vararg lines: String) = KeywordMatcher(KeywordList.parse(lines.asSequence()))

    private fun assertMatches(m: KeywordMatcher, vararg texts: String) =
        texts.forEach { assertTrue("should match: $it", m.containsMatch(it)) }

    private fun assertNoMatch(m: KeywordMatcher, vararg texts: String) =
        texts.forEach { assertFalse("should NOT match: $it (${m.find(it).map { f -> f.term }})", m.containsMatch(it)) }

    // ---- 1. Glued compounds ----

    @Test
    fun fuseDirectiveGeneratesCompoundsThatTakeAffixes() {
        val list = KeywordList.parse(sequenceOf("@fuse كس > ام م"))
        assertEquals(listOf("كسام", "كسم"), list.entries.map { it.term })
        assertEquals("fuse كس", list.entries.first().origin)
        val m = KeywordMatcher(list)
        assertMatches(m, "كسم", "كسمك", "وكسمين", "كسامك", "كسمها")
        assertNoMatch(m, "كس", "كسب", "مكسب")
    }

    @Test
    fun arabicFusedInsultsMatch() {
        assertMatches(bundled,
            "كسمك", "كسم الزمالك", "ده كسم حياتي", "كسختك", "كسامك", "وكسمين", "كسمها", "كسعرض امك",
            "طيزمك", "كس امك",
            "يامتناكه", "ياشرموطه", "يا شرموطة", "ابنالمتناكه", "يامنيوك")
    }

    @Test
    fun taMarbutaEntriesAreFoundBehindSuffixes() {
        // ة is written ت before a suffix; the index lookup must map it back (was a bug:
        // قحبتك / عاهرتك / موخرتها were missed even though the affix rule allowed them).
        assertMatches(bundled, "قحبتك", "عاهرتك", "شرموطتها", "موخرتها", "متناكتك")
    }

    @Test
    fun franco_ArabicAndEnglishCompoundsMatch() {
        assertMatches(bundled, "kosomak", "kossomak", "kosokhtak", "kos omak",
            "cocksucker", "cocksuckers", "cumslut", "dickhead", "pornstar", "sextape", "buttplug", "c0cksucker")
    }

    @Test
    fun fusedRulesDoNotCatchOrdinaryWords() {
        assertNoMatch(bundled,
            "كسب", "كسبت فلوس", "مكسب", "كسر", "كسوف", "كسول", "الكسكسي", "يانيك كاراسكو لاعب", "يا جماعة",
            "cocktail", "Dickens", "sextant", "pussycat", "Beavis and Butthead", "Kusum", "cockpit", "Sussex", "titbits")
    }

    // ---- 2. Genitals / private areas ----

    @Test
    fun slangAnatomyMatchesAlone() {
        assertMatches(bundled, "طياز", "ازبار", "خصاوي", "نهدها", "cameltoe", "nutsack", "coochie", "vajayjay",
            "punani", "minge", "show me your pecker", "nice knockers", "family jewels", "موخرتها")
    }

    @Test
    fun formalAnatomyMatchesUnlessClinicalContext() {
        assertMatches(bundled, "penis", "breasts", "vagina", "قضيبي", "ثديها")
        assertNoMatch(bundled,
            "doctor, my penis has a rash", "chicken breasts recipe", "I have a lump in my breasts",
            "vaginal discharge and itching", "قضيبي فيه التهاب يا دكتور", "سرطان الثدي", "فتحة الشرج فيها بواسير")
    }

    @Test
    fun clinicalVocabularyNeedsCorroboration() {
        assertNoMatch(bundled,
            "testicle pain", "the scrotum is swollen", "scrotum", "foreskin", "حجم الخصية اليسرى أكبر من اليمنى",
            "التهاب المهبل", "العضو الذكري", "شعر العانة", "شن هجوم يهدد هذه المنطقة الحساسة")
        assertTrue("مهبل" in bundled.find("كسها ومهبلها").map { it.term })
    }

    @Test
    fun everydaySensesOfAnatomySlangAreExcluded() {
        assertNoMatch(bundled,
            "في مؤخرة الترتيب", "مؤخرة السيارة", "صداع في مؤخرة رأسي", "the family jewels, a diamond necklace, were stolen",
            "turn the volume knob", "knobby knees", "earmuffs and a muff for winter", "muffin top",
            "pirate booty", "ankle booties", "booty", "Zheng Yu Dong", "Fanny Price", "اير فرانس", "فاير")
        assertMatches(bundled, "booty and pussy") // corroboration-only term with an explicit one
    }

    @Test
    fun shoppingWordsExposedByTheClothingCorpusAreExcluded() {
        assertNoMatch(bundled, "long sleeve tees", "ordered the S & M sizes", "nude heels", "panty lines show",
            "my boobs need more padding in this bra", "these jeans fit my butt")
    }

    // ---- 3. Adult clothing ----

    @Test
    fun adultClothingMatches() {
        assertMatches(bundled, "crotchless panties", "edible underwear", "nipple tassels", "latex catsuit",
            "fetish wear", "peekaboo bra", "ملابس داخلية مثيرة", "قميص نوم شفاف", "ملابس اغراء", "بدلة إغراء", "فتيش")
    }

    @Test
    fun borderlineClothingAddedByOwnerDecisionMatches() {
        assertMatches(bundled, "lingerie", "thong", "thongs", "g-string", "g string", "gstring", "garter", "garters",
            "babydoll", "corset", "fishnets", "micro bikini", "لانجري", "قميص نوم", "قميص نومها", "بيبي دول",
            "كلوت فتلة", "بدلة رقص", "ملابس فاضحة")
        // Accepted false positives (max sensitivity): ordinary shopping / everyday senses alert too.
        assertMatches(bundled, "lingerie sale at the mall", "thong sandals", "the G string on my violin",
            "garter belt for the wedding", "babydoll dress", "corset top")
    }

    @Test
    fun clothingNotAddedStillDoesNotMatch() {
        assertNoMatch(bundled, "ملابس مثيرة للجدل", "تفتيش الشنط", "bralette", "a silk chemise", "wool stockings",
            "fishing net", "قميص قطن", "بدلة رسمي", "a bikini top")
    }

    // ---- 4. Sex toys / sexual aids ----

    @Test
    fun sexToysMatch() {
        assertMatches(bundled, "dildo", "dildos", "butt plug", "anal beads", "fleshlight", "cock ring", "sex doll",
            "chastity cage", "vibrator", "العاب جنسية", "لعبة جنسية", "قضيب صناعي", "دمية جنسية", "ديلدو", "هزاز جنسي",
            "منشط جنسي")
        assertTrue("هزاز" in bundled.find("هزاز في كسها").map { it.term })
    }

    @Test
    fun borderlineAidsAddedByOwnerDecisionMatch() {
        assertMatches(bundled, "lube", "lubricant", "lubricants", "magic wand", "aphrodisiac", "aphrodisiacs",
            "spanish fly", "مزلق", "جل مزلق", "المزلقات")
        // Accepted false positives.
        assertMatches(bundled, "lube the bike chain", "a magic wand for the fairy costume", "oysters are an aphrodisiac")
        assertNoMatch(bundled, "مزلقان السكة الحديد", "wand", "magic trick")
    }

    @Test
    fun activityWordsMatchInHealthTextByOwnerDecision() {
        // No clinical suppression for activity words (max sensitivity): sexual-health discussion alerts.
        assertMatches(bundled, "doctor, I have pain after sex", "is masturbation harmful? asking my doctor",
            "semen analysis results from the clinic", "pain during intercourse, doctor", "ألم بعد الجماع يا دكتور",
            "احتلام متكرر، هل يحتاج علاج؟", "ضعف الشهوة بعد العملية")
    }

    @Test
    fun innocentSensesOfToyWordsAreExcluded() {
        assertNoMatch(bundled, "خلي الموبايل هزاز", "كرسي هزاز", "phone vibrator motor",
            "penis pump for erectile dysfunction, per my doctor", "LEGO sets are adult toys too",
            "kegel balls for pelvic floor exercises", "منشطات جنسية مغشوشة ضبطتها وزارة الصحة")
    }
}
