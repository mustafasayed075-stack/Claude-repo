package com.personal.guardian.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure-JVM tests for the Stage 4 keyword matcher, using plain strings — both a small
 * synthetic list (to pin down individual rules) and the real bundled asset.
 */
class KeywordMatcherTest {

    private val bundled: KeywordMatcher by lazy {
        val file = File("src/main/assets/text/keywords.txt")
        assertTrue("missing ${file.absolutePath}", file.isFile)
        KeywordMatcher(file.bufferedReader().use { KeywordList.parse(it) })
    }

    private fun matcher(vararg lines: String) = KeywordMatcher(KeywordList.parse(lines.asSequence()))

    private fun terms(m: KeywordMatcher, text: String) = m.find(text).map { it.term }

    // ---- The list file ----

    @Test
    fun bundledListLoadsWithEnglishArabicAndArabiziEntries() {
        val list = File("src/main/assets/text/keywords.txt").bufferedReader().use { KeywordList.parse(it) }
        assertTrue("entries: ${list.entries.size}", list.entries.size > 350)
        assertTrue(list.entries.any { e -> e.tokens.any { t -> t.any { it in '؀'..'ۿ' } } })
        assertTrue(list.entries.any { it.term == "a7ba" })
        assertTrue("exceptions parsed", "نيكون" in list.exceptions)
    }

    @Test
    fun parserSkipsCommentsBlanksAndDuplicatesAndReadsExceptions() {
        val list = KeywordList.parse(sequenceOf("# comment", "", "Foo", "foo  # dup after normalising", "!Bar", "two words"))
        assertEquals(listOf("Foo", "two words"), list.entries.map { it.term })
        assertEquals(setOf("bar"), list.exceptions)
    }

    // ---- Case, whole words, phrases ----

    @Test
    fun matchesCaseInsensitivelyOnWholeWordsOnly() {
        val m = matcher("sex")
        assertEquals(listOf("sex"), terms(m, "SEX tonight?"))
        assertTrue(terms(m, "Essex and Sussex, a sextant").isEmpty())
    }

    @Test
    fun matchesPhrasesTokenByToken() {
        val m = matcher("send nudes")
        assertEquals(listOf("send nudes"), terms(m, "pls SEND   nudes!!"))
        assertTrue(terms(m, "send the nudes").isEmpty())
    }

    @Test
    fun reportsOriginalPositionsForSnippets() {
        val text = "hey, you up? send nudes pls"
        val match = matcher("send nudes").find(text).single()
        assertEquals("send nudes", match.matchedText)
        assertEquals("hey, you up? send nudes pls", TextSnippet.around(text, match))
        val long = "x".repeat(100) + " send nudes " + "y".repeat(100)
        val snippet = TextSnippet.around(long, matcher("send nudes").find(long).single(), radius = 10)
        assertTrue(snippet, snippet.startsWith("…") && snippet.endsWith("…") && "send nudes" in snippet)
    }

    // ---- English inflections and evasion ----

    @Test
    fun englishInflections() {
        val m = matcher("boob", "nude", "orgy", "grope")
        assertEquals(listOf("boob"), terms(m, "boobs"))
        assertEquals(listOf("nude"), terms(m, "nudes"))
        assertEquals(listOf("orgy"), terms(m, "orgies"))
        assertEquals(listOf("grope"), terms(m, "groped"))
    }

    @Test
    fun leetspeakRepeatsAndSpacedLetters() {
        val m = matcher("sex", "porn", "boobs")
        for (evasion in listOf("s3x", "\$ex", "p0rn", "sexxxx", "SEEEX", "boooobs",
                               "s e x", "s.e.x", "s-e-x", "S_E_X", "b00bs")) {
            assertTrue("should match: $evasion", m.containsMatch(evasion))
        }
    }

    @Test
    fun invisibleCharactersAndAccentsDoNotHideWords() {
        val m = matcher("porn")
        assertTrue(m.containsMatch("p​o‌rn"))
        assertTrue(m.containsMatch("pórn"))
        assertTrue(m.containsMatch("ＰＯＲＮ")) // full-width
    }

    // ---- Arabic ----

    @Test
    fun arabicDiacriticsTatweelAndLetterFormsAreNormalised() {
        val m = matcher("سكس", "عاهرة")
        assertTrue(m.containsMatch("سَكْس"))
        assertTrue(m.containsMatch("ســـكـــس"))
        assertTrue(m.containsMatch("س ك س"))
        assertTrue(m.containsMatch("سكسسسس"))
        assertTrue("ة/ه unified", m.containsMatch("عاهره"))
    }

    @Test
    fun arabicClitics() {
        val m = matcher("نيك", "طيز", "كس", "زب")
        assertTrue("prefix+suffix", m.containsMatch("هنيكك"))
        assertTrue("verb prefix", m.containsMatch("بينيك"))
        assertTrue("article", m.containsMatch("الطيز"))
        assertTrue("pronoun suffix", m.containsMatch("طيزها"))
        assertTrue("short entry + safe suffix", m.containsMatch("كسك"))
        assertTrue(m.containsMatch("زبي"))
    }

    @Test
    fun shortArabicEntriesDoNotMatchOrdinaryWordsThatContainThem() {
        val m = matcher("كس", "زب", "بز")
        for (word in listOf("كسر", "كسل", "مكسرات", "بكس", "زبادي", "زبون", "زبده", "بزنس", "كسوف", "اكسسوار")) {
            assertFalse("should not match: $word", m.containsMatch(word))
        }
    }

    @Test
    fun shortLatinEntriesMatchExactlyOnly() {
        val m = matcher("sm")
        assertTrue(m.containsMatch("S&M"))
        assertFalse(m.containsMatch("SMD parts"))
    }

    @Test
    fun exceptionsApplyBehindArabicPrefixes() {
        val m = matcher("نيك", "!نيكي")
        assertTrue("sanity: would match without the exception", matcher("نيك").containsMatch("ونيكي"))
        assertFalse(m.containsMatch("ونيكي جام"))
    }

    @Test
    fun exceptionsNeverMatch() {
        val m = matcher("نيك", "!نيكون", "cock", "!cocky")
        assertFalse(m.containsMatch("كاميرا نيكون"))
        assertFalse(m.containsMatch("he is so cocky"))
        assertTrue(m.containsMatch("cocks"))
    }

    // ---- Franco-Arabic (Arabizi) ----

    @Test
    fun arabiziDigitsAreKeptAsLetters() {
        val m = matcher("a7ba", "kos", "neek")
        assertTrue(m.containsMatch("ya a7ba"))
        assertTrue(m.containsMatch("kosak"))
        assertTrue(m.containsMatch("neekny"))
        assertFalse("digit entry needs its digit", m.containsMatch("ahba"))
        assertFalse("kosa = zucchini", m.containsMatch("kosa w koshary"))
    }

    // ---- The real list ----

    @Test
    fun bundledListCatchesRepresentativeTermsAndEvasions() {
        val samples = listOf(
            "send nudes", "wanna see my b00bs?", "p0rn", "pr0n", "pornhub link", "s e x", "sexting",
            "onlyfans", "horny af", "ابعتلي صور عريانه", "هنيكك", "سكس", "متناكة", "شرموطه",
            "افلام اباحيه", "a7ba", "sharmoota", "kosak", "e2la3y", "ابعتيلي نودز", "بيتناك"
        )
        for (s in samples) assertTrue("should match: $s", bundled.containsMatch(s))
    }

    /**
     * Definition of Done spot-check: ordinary, unrelated conversation and browsing text
     * (English, Egyptian Arabic, Franco-Arabic) must produce no matches.
     */
    @Test
    fun ordinaryConversationHasNoFalseMatches() {
        val ordinary = listOf(
            // English chat
            "Hey! Are we still on for dinner tonight?", "Running 10 min late, traffic is crazy",
            "Can you pick up milk and eggs on the way home?", "Happy birthday!! Have an amazing day",
            "The meeting got moved to 3pm, conference room B", "Did you watch the match last night? What a goal",
            "I'm tied up at work, call you later", "That exam was brutal lol", "Send me the document when it's ready",
            "Let's grab coffee at the new place near the office", "Can you send the pics from the trip?",
            "My cat knocked the plant over again", "He's so cocky about his new car", "The cocktail bar was fun",
            "We drove through Essex and Sussex", "Scunthorpe United won 2-1", "The assassin's creed trailer is out",
            "Add a pinch of cumin and paprika", "Dickens wrote Great Expectations", "Therapist appointment moved to Monday",
            "The analysis is attached, see page 4", "Buttons on the new jacket are gold", "Grape juice or orange?",
            "Class starts at 8, don't be late", "Bring your swimsuit, we're going to the beach", "Nikon or Canon for travel?",
            "I love you, see you soon xx", "Great game, thanks for the pass", "The shuttlecock went over the net",
            "Please review the pull request", "She's the best teacher in the school", "Kids are asleep finally",
            "Don't forget mum's medicine at 9", "Gym at 6? Leg day", "The baby is teething, no sleep",
            "Traffic on the ring road again", "Salary came in, let's go shopping", "Weather is hot today, 38 degrees",
            // Egyptian Arabic chat
            "يا جماعة الاجتماع بكرة الساعة ٨", "ربنا يفرجها عليك يا صاحبي", "كسرت الموبايل وانا نازل",
            "انا كسلان النهاردة مش هنزل", "هات مكسرات وزبادي وانت جاي", "الزبون اتصل تاني", "ابعتلي الصور بتاعة الرحلة",
            "حلمه انه يسافر برة", "مصر كسبت الماتش", "فرج جه النهاردة الشغل", "الواد بيذاكر للامتحان",
            "الجو حر جدا النهاردة", "عاملين ايه يا شباب", "اتغديت كشري ومحشي", "هنروح البحر الصيف ده",
            "الكاميرا نيكون احسن ولا كانون", "عندي بزنس صغير", "كسوف الشمس بكرة", "لبست اكسسوار جديد",
            "الحمد لله على كل حال", "ماما عاملة ملوخية", "صباح الخير يا حبيبي", "كل سنة وانت طيب",
            "المدرس شرح الدرس كويس", "هنتقابل عند المترو", "العربية عطلت تاني",
            // Franco-Arabic chat
            "ezayak ya 7abibi", "enta fein?", "3amel eh?", "yalla bina", "ana gay fel sekka", "kosa w koshary",
            "el ganw 7ar awy", "tamam ya basha", "5alas ashoofak bokra", "mabrook 3al shoghl el gedid",
            // False positives found by the large-corpus check (regression guard)
            "إسحاق نيوتن اكتشف الجاذبية", "واسحاق كمان", "استخدم قضيب تنظيف للفوهة", "نيك فيوري في فيلم مارفل",
            "ونيكي جام غنى الأغنية", "نظام حزبي قوي", "الزبرة حيوان مخطط", "جيسون بورن فيلم أكشن", "دراجون بورن بطل",
            "الشبكة فيها كذا نودز", "وصل النودز ببعض", "الألياف العارية", "CHAPTER XXX.", "phone format XXX-XXX-XXXX",
            "SMD components on the board", "their social intercourse was pleasant",
            // Browser / news text
            "Breaking news: parliament passes new budget", "Top 10 recipes for Ramadan", "How to fix a leaking tap",
            "Football: Al Ahly beat Zamalek 2-0", "Download the PDF of the syllabus", "Weather forecast for Cairo"
        )
        val falseMatches = ordinary.flatMap { line -> bundled.find(line).map { "$line → ${it.term}" } }
        assertEquals("false matches: $falseMatches", emptyList<String>(), falseMatches)
    }
}
