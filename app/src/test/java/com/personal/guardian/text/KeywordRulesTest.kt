package com.personal.guardian.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure-JVM tests for the keyword-matching improvements: bare "نيك" and other
 * over-restricted terms restored, context-rule disambiguation, and systematic
 * Arabic/English morphology — each with newly-triggered and still-suppressed cases.
 */
class KeywordRulesTest {

    private val bundled: KeywordMatcher by lazy {
        KeywordMatcher(File("src/main/assets/text/keywords.txt").bufferedReader().use { KeywordList.parse(it) })
    }

    private fun matcher(vararg lines: String) = KeywordMatcher(KeywordList.parse(lines.asSequence()))

    private fun assertMatches(m: KeywordMatcher, vararg texts: String) =
        texts.forEach { assertTrue("should match: $it", m.containsMatch(it)) }

    private fun assertNoMatch(m: KeywordMatcher, vararg texts: String) =
        texts.forEach { assertFalse("should NOT match: $it (${m.find(it).map { f -> f.term }})", m.containsMatch(it)) }

    // ---- 1. Bare "نيك" and other over-restricted terms ----

    @Test
    fun bareNeekMatchesButNotTheNameNick() {
        assertMatches(bundled, "نيك", "عايز نيك", "نيييييك", "ضحكت نيك")
        assertNoMatch(bundled, "نيك فيوري في فيلم مارفل", "المغني نيك جوناس", "اللاعب نيك كيريوس فاز",
            "رائد الفضاء الأميركي نيك هايغ", "نيكول كيدمان", "كاميرا نيكون", "هناك مشكلة")
    }

    @Test
    fun neekDerivedFormsMatch() {
        assertMatches(bundled, "ناك", "بيتناك", "اتناكت", "متناكه", "متناكتك", "منيوك", "هينيكها", "بنيكك", "نياكه")
    }

    @Test
    fun nikeIsSuppressedButNayekAloneMatches() {
        assertMatches(bundled, "نايك")
        assertNoMatch(bundled, "اشتريت كوتشي نايك جديد", "ماركة نايك ولا اديداس")
    }

    @Test
    fun otherRestoredTermsMatchAloneAndStaySuppressedInTheirInnocentSense() {
        assertMatches(bundled, "سحاق", "زبري", "زبرك", "بورن", "عندك نودز؟", "xxx", "intercourse", "S&M", "قضيب")
        assertNoMatch(bundled,
            "إسحاق نيوتن", "واسحاق كمان",                        // Isaac
            "الزبرة حيوان مخطط",                                // zebra
            "جيسون بورن فيلم أكشن", "دراجون بورن بطل",           // Bourne, Dragonborn
            "الشبكة فيها كذا نودز", "وصل النودز ببعض",           // network nodes
            "CHAPTER XXX.", "phone format XXX-XXX-XXXX",        // numerals, placeholders
            "their social intercourse was pleasant",
            "SMD components on the board", "Fetch \$15.5M")
    }

    // ---- 2. Context rules ----

    @Test
    fun companionNearbyInTheSameSentenceSuppresses() {
        val m = matcher("qadib ~ steel iron")
        assertMatches(m, "qadib")
        assertNoMatch(m, "a steel qadib", "qadib of iron for the frame")
        val hit = m.analyze("a steel qadib").single()
        assertEquals("steel", hit.suppressedBy)
    }

    @Test
    fun companionInAnotherSentenceOrFarAwayDoesNotSuppress() {
        val m = matcher("qadib ~ steel")
        assertMatches(m, "We bought steel. qadib", "steel " + "word ".repeat(KeywordMatcher.CONTEXT_WINDOW + 1) + "qadib")
    }

    @Test
    fun unambiguousExplicitTermInTheSentenceOverridesSuppression() {
        val m = matcher("qadib ~ steel", "explicit")
        assertEquals(listOf("qadib", "explicit"), m.find("steel qadib explicit").map { it.term })
    }

    @Test
    fun ambiguousTermsDoNotVouchForEachOther() {
        val m = matcher("aaa ~ steel", "bbb ~ steel")
        assertNoMatch(m, "steel aaa bbb")
    }

    @Test
    fun multiWordCompanions() {
        val m = matcher("dessert ~ \"ice cream\"")
        assertNoMatch(m, "dessert with ice cream")
        assertMatches(m, "dessert with ice", "dessert cream")
    }

    @Test
    fun corroborationOnlyTermsNeedAnotherExplicitTerm() {
        val m = matcher("?farag", "explicit")
        assertNoMatch(m, "farag came to work")
        assertEquals(listOf("farag", "explicit"), m.find("farag explicit").map { it.term })
    }

    @Test
    fun rodExamplesFromTheRequest() {
        assertMatches(bundled, "قضيب", "وريني القضيب")
        assertNoMatch(bundled, "قضيب حديد", "قضيب معدني للستارة", "قضبان تسليح وقضيب خرساني", "قضيب صلب",
            "المكبس متصل بمحور عن طريق قضيب توصيل")
        assertMatches(bundled, "قضيب حديد و طيز") // an unambiguous term in the sentence overrides
    }

    @Test
    fun faragReliefAndNameAreSuppressedButExplicitSentenceMatches() {
        assertNoMatch(bundled, "ربنا يفرجها", "فرج الله قريب", "مفتاح الفرج", "فرج جه النهاردة الشغل", "بتتفرج على ايه")
        assertMatches(bundled, "فرجها و بزازها")
    }

    @Test
    fun reIncludedEverydayWordsAreContextRuled() {
        assertMatches(bundled, "escort", "fingering", "انا عايز الحسك")
        assertNoMatch(bundled,
            "The police escort led the convoy", "guitar fingering technique", "That sucks, the traffic was awful",
            "ice cream كلب بيلحس", "بيلحس جزمة المدير", "سرطان الثدي", "الثدييات البحرية", "يا جماعة", "العمل الجماعي",
            "اجماع الاحزاب", "Costco faces sex discrimination suit", "the opposite sex", "same-sex marriage",
            "kick his butt", "world domination", "هيجي بكرة", "بزي مدني")
    }

    @Test
    fun analyzeReportsWhatSuppressedAMatch() {
        val hit = bundled.analyze("قضيب حديد").single { it.term == "قضيب" }
        assertEquals("حديد", hit.suppressedBy)
        val weak = bundled.analyze("ربنا يفرج").firstOrNull()
        assertTrue(weak == null || weak.suppressedBy != null)
    }

    // ---- 3. Morphology ----

    @Test
    fun arabicRootDerivations() {
        assertEquals(listOf("نيك", "ناك", "نايك", "منيوك", "اتناك", "متناك", "تناك", "نياك"),
            ArabicMorphology.derive(listOf("ن", "ي", "ك")))
        assertEquals(listOf("لحس", "لاحس", "ملحوس", "اتلحس", "متلحس", "تلحس", "لحاس"),
            ArabicMorphology.derive(listOf("ل", "ح", "س")))
        assertEquals(listOf("شرمط", "شرموط", "اتشرمط", "متشرمط", "تشرمط", "شراميط"),
            ArabicMorphology.derive(listOf("ش", "ر", "م", "ط")))
        assertTrue("يمص" in ArabicMorphology.derive(listOf("م", "ص", "ص")))
    }

    @Test
    fun rootDirectiveGeneratesEntriesAndExplicitLinesKeepTheirRule() {
        val list = KeywordList.parse(sequenceOf("نيك ~ فيوري", "@root ن ي ك"))
        val neek = list.entries.single { it.term == "نيك" }
        assertEquals("list", neek.origin)
        assertTrue(neek.hasContextRule)
        assertEquals("root ن ي ك", list.entries.single { it.term == "منيوك" }.origin)
    }

    @Test
    fun arabicPrefixesAndSuffixesAppliedSystematically() {
        val m = matcher("شرموطه", "طيز", "سكس", "@root ش ر م ط")
        assertMatches(m,
            "بشرموطه", "وشرموطه", "الشرموطه", "شرموطتك", "شرموطتها",  // ة → ت before a suffix
            "طيزها", "طيزو", "طيزت", "بطيزك", "هطيزك", "سكسين",
            "بيتشرمط", "اتشرمطت", "متشرمطه", "هتتشرمط")
    }

    @Test
    fun arabicNounModeRejectsVerbAffixes() {
        val m = matcher("=farj ~ x".replace("farj", "فرج"))
        assertMatches(m, "فرج", "الفرج", "فرجها")
        assertNoMatch(m, "اتفرج", "افرجت", "بتتفرج", "يفرجها")
    }

    @Test
    fun collapsedSpellingsDoNotInventWordsAcrossAffixes() {
        val m = matcher("سكس", "فرج")
        assertMatches(m, "سكسسسس", "ســـكـــس")
        assertNoMatch(m, "بتتفرج")
    }

    @Test
    fun englishInflectionsAndSlangSpellings() {
        val m = matcher("grope", "cum", "boob", "slut", "pussy", "cock", "porn", "sexy", "nude")
        assertMatches(m, "groping", "groped", "cumming", "cummin", "boobies", "boobie", "sluttie", "pussys",
            "puzzy", "c0ck", "p*rn", "s*xy", "nudez", "nudes")
    }

    @Test
    fun englishMorphologyDoesNotMatchOrdinaryWords() {
        assertNoMatch(bundled,
            "success", "we will succeed", "new skates for skating", "My Little Pony", "scatter plot",
            "titter", "a cookie jar", "IBM seeks dismissal", "the tower is 2,717 feet", "cumin and paprika",
            "cocktail bar", "Scunthorpe", "assassin", "Essex")
    }

    @Test
    fun spacedLettersJoinOnlyAcrossSpacingSeparators() {
        val m = matcher("sex", "xx")
        assertMatches(m, "s e x", "s.e.x", "s-e-x", "s_e_x")
        assertNoMatch(m, "x+x", "x = x", "(x) (x)")
    }
}
