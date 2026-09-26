package com.personal.guardian.text

import java.io.BufferedReader

/**
 * The Stage 4 keyword/phrase list, parsed from the bundled asset
 * (`assets/text/keywords.txt`, see README "Stage 4").
 *
 * File format (one directive per line, `#` starts a comment):
 *  - `word` or `a phrase` — an entry.
 *  - `word ~ c1 c2 "two words"` — an entry with a **context rule**: the match is
 *    suppressed when one of the innocent-context companions appears near it in the
 *    same sentence (see [KeywordMatcher]). Multi-word companions go in quotes.
 *  - `@root ن ي ك` — an Arabic root; its standard derived forms are generated as
 *    entries ([ArabicMorphology.derive]). A root line may carry `~ companions` too,
 *    which then apply to every generated form. Forms also listed explicitly keep the
 *    explicit line's context rule.
 *  - `=word` — an Arabic **noun-mode** entry: only noun affixes (و ف ب ل ال بال…
 *    prefixes and pronoun endings ي ك ه ها هم نا كم), no verb prefixes/endings —
 *    for nouns whose letters are also a common verb root (فرج: اتفرج "watch").
 *    Combine with a context rule: `=word ~ companions`.
 *  - `?word` — a **corroboration-only** entry: it matches only when an unambiguous
 *    explicit term (no context rule) is elsewhere in the same sentence — for words
 *    whose innocent sense no companion list can capture (e.g. فرج is also a common
 *    first name). May be combined with `=`: `?=word`.
 *  - `!word` — an exception: an innocent word that must never match, even if it looks
 *    like an entry plus affixes (e.g. "زبون" customer); it also applies behind an
 *    Arabic prefix.
 * Entries are normalised with [TextNormalizer], so they can be written in any
 * case/letter form.
 */
class KeywordList private constructor(
    val entries: List<Entry>,
    val exceptions: Set<String>
) {
    /**
     * One list entry: its text as written, normalised tokens, innocent-context
     * companions (each a list of normalised tokens), and where it came from
     * ("list", or "root ن ي ك" for generated forms).
     */
    class Entry(
        val term: String,
        val tokens: List<String>,
        val companions: List<List<String>> = emptyList(),
        val origin: String = "list",
        /** Arabic noun-mode: noun affixes only (see `=word` in the file format). */
        val nounOnly: Boolean = false,
        /** Corroboration-only (see `?word` in the file format). */
        val weak: Boolean = false
    ) {
        /** True if this entry can be suppressed by context (companions or corroboration-only). */
        val hasContextRule: Boolean get() = companions.isNotEmpty() || weak
    }

    companion object {
        fun parse(reader: BufferedReader): KeywordList = parse(reader.lineSequence())

        fun parse(lines: Sequence<String>): KeywordList {
            val explicit = LinkedHashMap<List<String>, Entry>()
            val generated = LinkedHashMap<List<String>, Entry>()
            val exceptions = HashSet<String>()
            for (rawLine in lines) {
                val line = rawLine.substringBefore('#').trim()
                if (line.isEmpty()) continue
                when {
                    line.startsWith("!") ->
                        TextNormalizer.tokenize(line.substring(1)).forEach { exceptions += it.raw }
                    line.startsWith("@root") -> {
                        val (head, companions) = splitContext(line.removePrefix("@root"))
                        // Letters one by one: tokenize() would join "ن ي ك" into one token.
                        val letters = head.trim().split(Regex("\\s+")).flatMap { l -> TextNormalizer.tokenize(l).map { it.raw } }
                        val rootLabel = "root " + letters.joinToString(" ")
                        for (form in ArabicMorphology.derive(letters)) {
                            val tokens = listOf(form)
                            if (tokens !in generated) generated[tokens] = Entry(form, tokens, companions, rootLabel)
                        }
                    }
                    else -> {
                        val weak = line.startsWith("?")
                        val rest = line.removePrefix("?")
                        val nounOnly = rest.startsWith("=")
                        val (head, companions) = splitContext(rest.removePrefix("="))
                        val tokens = TextNormalizer.tokenize(head).map { it.raw }
                        if (tokens.isNotEmpty()) {
                            if (tokens !in explicit) explicit[tokens] = Entry(head.trim(), tokens, companions, nounOnly = nounOnly, weak = weak)
                        }
                    }
                }
            }
            // Explicit lines win over root-generated forms (their context rule applies).
            val entries = explicit.values + generated.filterKeys { it !in explicit }.values
            return KeywordList(entries.toList(), exceptions)
        }

        /** Splits `head ~ c1 c2 "c 3"` into the head and its normalised companions. */
        private fun splitContext(line: String): Pair<String, List<List<String>>> {
            val tilde = line.indexOf('~')
            if (tilde < 0) return line to emptyList()
            val companions = Regex("\"([^\"]+)\"|(\\S+)").findAll(line.substring(tilde + 1))
                .map { m -> TextNormalizer.tokenize(m.groupValues[1].ifEmpty { m.groupValues[2] }).map { it.raw } }
                .filter { it.isNotEmpty() }
                .toList()
            return line.substring(0, tilde) to companions
        }
    }
}

/**
 * Finds [KeywordList] entries in plain text (Stage 4). Pure Kotlin — no Android —
 * so it is tested with plain strings.
 *
 * Matching is on whole normalised tokens (never raw substrings, so "Essex",
 * "cocktail", "كسر" or "زبادي" don't match), case-insensitive, and tolerant of:
 *  - diacritics, tatweel, letter variants, invisible characters ([TextNormalizer]);
 *  - leetspeak (p0rn, s3x, $ex), repeated letters (sexxx, boooobs), spaced-out
 *    letters (s e x, س ك س) and '*'-masked letters (p*rn, s*xy);
 *  - English morphology on Latin entries of 3+ letters ([EnglishMorphology]):
 *    plurals, -ing/-ed/-er with e-dropping and consonant doubling, -y/-ie/-ies,
 *    -in', Arabizi pronoun endings, and spelling variants (ph/f, ck/c/k, z/s);
 *  - Arabic morphology ([ArabicMorphology]): prefixes (و ف ال ب ل ي ت ن ا ه ح بي هي…),
 *    suffixes (ي ك ه ها هم و وا ت ين ني وها…), and `@root` verb-form derivations.
 *    Entries of 1–2 letters (e.g. كس) only take "ال"/"وال" and a short, safe suffix
 *    set, because longer affixes produce ordinary words (زب+ون = زبون "customer").
 * Multi-word entries match word by word; only their last word takes affixes.
 *
 * **Context rules:** an entry with innocent-context companions (`قضيب ~ حديد معدن…`)
 * is suppressed when a companion occurs in the same sentence within
 * [CONTEXT_WINDOW] words of it — unless an unambiguous explicit term (one without a
 * context rule) appears elsewhere in that sentence, in which case it matches normally.
 */
class KeywordMatcher(private val list: KeywordList) {

    /**
     * A match: the list [term], where it was found in the original text, and — for
     * [analyze] — the companion that suppressed it (null = active match).
     */
    data class Match(
        val term: String,
        val start: Int,
        val end: Int,
        val matchedText: String,
        val suppressedBy: String? = null
    )

    val entryCount: Int get() = list.entries.size

    /** Entries indexed by every key a text token could be looked up by (see [candidateKeys]). */
    private val index: Map<String, List<KeywordList.Entry>> = buildIndex()

    /** Single-token Latin entries by length, for '*'-masked tokens. */
    private val latinByLength: Map<Int, List<KeywordList.Entry>> = list.entries
        .filter { it.tokens.size == 1 && !isArabic(it.tokens[0]) && it.tokens[0].length >= 3 }
        .groupBy { it.tokens[0].length }

    /** Active matches in [text], in order of appearance (at most [limit]). */
    fun find(text: CharSequence, limit: Int = 50): List<Match> =
        analyze(text).filter { it.suppressedBy == null }.take(limit)

    fun containsMatch(text: CharSequence): Boolean = find(text, limit = 1).isNotEmpty()

    /** All matches including context-suppressed ones (for tests and corpus evaluation). */
    fun analyze(text: CharSequence): List<Match> {
        val tokens = TextNormalizer.tokenize(text)
        if (tokens.isEmpty()) return emptyList()
        val s = text.toString()

        class Raw(val entry: KeywordList.Entry, val i: Int, val n: Int) {
            var suppressedBy: String? = null
        }
        val raws = ArrayList<Raw>()
        val seen = HashSet<Pair<KeywordList.Entry, Int>>()
        for (i in tokens.indices) {
            val token = tokens[i]
            if (isException(token)) continue
            for (entry in candidates(token)) {
                val n = entry.tokens.size
                if (i + n > tokens.size || !seen.add(entry to i)) continue
                if (matchesAt(tokens, i, entry)) raws += Raw(entry, i, n)
            }
        }
        if (raws.isEmpty()) return emptyList()

        // Context rules, pass 1: an innocent-context companion nearby suppresses;
        // corroboration-only entries start suppressed.
        for (r in raws) {
            if (!r.entry.hasContextRule) continue
            r.suppressedBy = if (r.entry.weak) NEEDS_CORROBORATION
            else findCompanion(tokens, r.i, r.n, r.entry.companions)
        }
        // Pass 2: an unambiguous explicit term (no context rule of its own) elsewhere in
        // the same sentence overrides the suppression. Ambiguous terms can't vouch for
        // each other ("XXX-XXX" is not evidence for "xx").
        val explicitBySentence = raws.filter { !it.entry.hasContextRule }.groupBy { tokens[it.i].sentence }
        for (r in raws) {
            if (r.suppressedBy == null) continue
            val others = explicitBySentence[tokens[r.i].sentence].orEmpty()
            if (others.any { o -> o.i >= r.i + r.n || o.i + o.n <= r.i }) r.suppressedBy = null
        }

        return raws.sortedBy { it.i }.map { r ->
            val start = tokens[r.i].start
            val end = tokens[r.i + r.n - 1].end
            Match(r.entry.term, start, end, s.substring(start, end), r.suppressedBy)
        }.distinctBy { it.term to it.start }
    }

    // ---- matching ----

    private fun candidates(token: TextNormalizer.Token): List<KeywordList.Entry> {
        val out = LinkedHashSet<KeywordList.Entry>()
        for (key in candidateKeys(token)) index[key]?.let { out += it }
        if (token.masked) {
            for (f in token.forms) latinByLength[f.length]?.forEach { e -> if (maskedMatch(f, e.tokens[0])) out += e }
        }
        return out.toList()
    }

    private fun matchesAt(tokens: List<TextNormalizer.Token>, i: Int, entry: KeywordList.Entry): Boolean {
        val n = entry.tokens.size
        for (k in 0 until n) {
            val tok = tokens[i + k]
            if (k > 0 && (isException(tok) || tok.sentence != tokens[i].sentence)) return false
            val ok = if (k == n - 1) morphMatch(tok, entry.tokens[k], entry.nounOnly) else tok.forms.contains(entry.tokens[k])
            if (!ok) return false
        }
        return true
    }

    /** True if some form of [token] is [entry] or a derived/affixed form of it. */
    private fun morphMatch(token: TextNormalizer.Token, entry: String, nounOnly: Boolean = false): Boolean {
        if (isArabic(entry)) {
            // Collapsed spellings ("سكسسس"→"سكس") only count as exact matches: collapsing
            // across an affix boundary invents words (بتتفرج → بتفرج = بت + فرج).
            if (entry in token.forms) return true
            return ArabicMorphology.matches(token.raw, entry, nounOnly) ||
                ArabicMorphology.matches(TextNormalizer.leetDecode(token.raw), entry, nounOnly)
        }
        if (entry.length <= 2) return entry in token.forms // "sm" + "d" = "smd"
        val derived = EnglishMorphology.derivations(entry)
        for (f in token.forms) {
            if (f in derived) return true
            if (entry.length >= 4 && EnglishMorphology.spellingKey(f) in EnglishMorphology.spellingKeys(entry)) return true
            if (token.masked && maskedMatch(f, entry)) return true
        }
        return false
    }

    /** "p*rn" matches "porn": same length, '*' for any letter, at least 2 real letters kept. */
    private fun maskedMatch(masked: String, entry: String): Boolean {
        if (masked.length != entry.length || '*' !in masked) return false
        val stars = masked.count { it == '*' }
        if (masked.length - stars < 2 || stars * 2 > masked.length) return false
        return masked.indices.all { masked[it] == '*' || masked[it] == entry[it] }
    }

    /** Exceptions also apply behind an Arabic prefix (ونيكي = و + exception نيكي). */
    private fun isException(token: TextNormalizer.Token): Boolean {
        if (list.exceptions.isEmpty()) return false
        for (f in token.forms) {
            if (f in list.exceptions) return true
            if (isArabic(f) && ArabicMorphology.prefixStripped(f).any { it in list.exceptions }) return true
        }
        return false
    }

    /** Keys to look a text token up by: its forms, Arabic affix-stripped stems, spelling keys. */
    private fun candidateKeys(token: TextNormalizer.Token): Set<String> {
        val keys = LinkedHashSet<String>()
        for (f in token.forms) {
            keys += f
            if (isArabic(f)) keys += ArabicMorphology.stems(f)
            else if (f.length >= 4) keys += SPELLING_KEY_PREFIX + EnglishMorphology.spellingKey(f)
        }
        return keys
    }

    private fun buildIndex(): Map<String, List<KeywordList.Entry>> {
        val idx = HashMap<String, MutableList<KeywordList.Entry>>()
        fun put(key: String, e: KeywordList.Entry) = idx.getOrPut(key) { ArrayList() }.let { if (e !in it) it += e }
        for (e in list.entries) {
            val first = e.tokens.first()
            put(first, e)
            if (e.tokens.size == 1 && !isArabic(first) && first.length >= 3) {
                EnglishMorphology.derivations(first).forEach { put(it, e) }
                if (first.length >= 4) EnglishMorphology.spellingKeys(first).forEach { put(SPELLING_KEY_PREFIX + it, e) }
            }
        }
        return idx
    }

    // ---- context rules ----

    /** The first companion found near tokens [i, i+n) in the same sentence, or null. */
    private fun findCompanion(
        tokens: List<TextNormalizer.Token>, i: Int, n: Int, companions: List<List<String>>
    ): String? {
        val sentence = tokens[i].sentence
        val from = (i - CONTEXT_WINDOW).coerceAtLeast(0)
        val to = (i + n - 1 + CONTEXT_WINDOW).coerceAtMost(tokens.size - 1)
        for (companion in companions) {
            for (j in from..to) {
                if (j in i until i + n) continue
                if (j + companion.size - 1 > to) break
                val ok = companion.indices.all { k ->
                    val t = tokens[j + k]
                    t.sentence == sentence && companionTokenMatches(t, companion[k])
                }
                if (ok) return companion.joinToString(" ")
            }
        }
        return null
    }

    private fun companionTokenMatches(t: TextNormalizer.Token, c: String): Boolean =
        if (isArabic(c)) t.forms.any { ArabicMorphology.matches(it, c) }
        else t.forms.any { it == c || it in EnglishMorphology.derivations(c) }

    companion object {
        /** Words either side of a match that count as "near" for context rules. */
        const val CONTEXT_WINDOW = 12

        /** [Match.suppressedBy] value for a corroboration-only entry with no explicit term nearby. */
        const val NEEDS_CORROBORATION = "(needs another explicit term)"

        private const val SPELLING_KEY_PREFIX = "\u0001"

        internal fun isArabic(s: String) = s.any { it in '؀'..'ۿ' }
    }
}

/**
 * Arabic affixes and root derivations for keyword matching. Pure Kotlin; works on
 * normalised text (ة→ه, أ/إ/آ→ا, ى→ي).
 */
object ArabicMorphology {

    /** Pronoun, plural and verb-ending suffixes accepted on entries of 3+ letters. */
    val SUFFIXES: Set<String> = setOf(
        "ي", "ك", "ه", "ها", "هم", "هن", "كم", "كو", "نا", "ني", "ات", "ين", "وا", "و", "ت",
        "تي", "تك", "ته", "تها", "تو", "تني", "يه", "اتك", "اتها", "وه", "وها", "وهم", "وني", "وك"
        // (Egyptian attached datives لي/لك/لها… were tried and rejected on the corpus:
        //  العقلي "mental" = ا + لعق + لي, بناكلها "we eat it" = ب + ناك + لها.)
    )

    /** Suffixes safe on 1–2 letter entries (no ون/و/ت: زب+ون = زبون "customer"). */
    val SHORT_SUFFIXES: Set<String> = setOf("ي", "ك", "ه", "ها", "هم", "كم", "نا", "ات")

    /** "" plus conjunction (و/ف) × article/preposition/verb-prefix combinations, longest first. */
    val PREFIXES: List<String> = run {
        val outer = listOf("", "و", "ف")
        val inner = listOf(
            "", "ال", "لل", "بال", "ب", "ل", "ه", "ح", "بي", "بت", "بن",
            "هي", "هت", "هن", "حي", "حت", "حن", "ي", "ت", "ن", "ا"
        )
        outer.flatMap { o -> inner.map { o + it } }.distinct().sortedByDescending { it.length }
    }
    private val SHORT_PREFIXES = listOf("وال", "ال", "")

    /** Noun-mode affixes: article/preposition/conjunction prefixes and pronoun endings only. */
    val NOUN_PREFIXES: List<String> = listOf("", "و", "ف", "ب", "ل", "ال", "لل", "بال", "وال", "فال", "وب", "ول", "ولل", "وبال")
        .sortedByDescending { it.length }
    val NOUN_SUFFIXES: Set<String> = setOf("ي", "ك", "ه", "ها", "هم", "كم", "نا")

    /**
     * True if [word] is [entry] with allowed prefixes/suffixes. [nounOnly] restricts
     * to noun affixes (no verb prefixes such as ا/ت/ي, no verb endings such as ت/و).
     */
    fun matches(word: String, entry: String, nounOnly: Boolean = false): Boolean {
        if (word == entry) return true
        val short = entry.length <= 2
        val prefixes = when { short -> SHORT_PREFIXES; nounOnly -> NOUN_PREFIXES; else -> PREFIXES }
        val suffixes = when { short -> SHORT_SUFFIXES; nounOnly -> NOUN_SUFFIXES; else -> SUFFIXES }
        // A final ة (normalised ه) becomes ت before a suffix: شرموطه + ك = شرموطتك.
        val tStem = if (entry.length >= 3 && entry.endsWith("ه")) entry.dropLast(1) + "ت" else null
        for (p in prefixes) {
            if (!word.startsWith(p)) continue
            val rest = word.substring(p.length)
            if (rest == entry) return true
            if (rest.startsWith(entry) && rest.substring(entry.length) in suffixes) return true
            if (tStem != null && rest.startsWith(tStem) && rest.substring(tStem.length) in suffixes) return true
        }
        return false
    }

    /** [word] with each possible prefix removed (for exception checks). */
    fun prefixStripped(word: String): List<String> =
        PREFIXES.filter { it.isNotEmpty() && word.startsWith(it) && word.length - it.length >= 2 }
            .map { word.substring(it.length) }

    /** Candidate stems of [word]: prefix and/or suffix removed (index lookup keys). */
    fun stems(word: String): Set<String> {
        val out = LinkedHashSet<String>()
        for (p in PREFIXES) {
            if (!word.startsWith(p)) continue
            val rest = word.substring(p.length)
            if (rest.length < 2) continue
            out += rest
            for (sfx in SUFFIXES) if (rest.endsWith(sfx) && rest.length - sfx.length >= 2) out += rest.dropLast(sfx.length)
        }
        return out
    }

    /**
     * Standard derived stems of a root (letters already normalised), Egyptian-aware.
     * Imperfect/imperative forms (يـ تـ نـ ا بيـ هيـ…) and pronoun endings come from
     * [PREFIXES]/[SUFFIXES] at match time, so only stems are generated here:
     *  - sound  (ل ح س): فعل لحس · فاعل لاحس · مفعول ملحوس · اتفعل اتلحس · متفعل متلحس ·
     *                   تفعل تلحس (imperfect stem: بيتلحس) · فعّال لحاس
     *  - hollow (ن ي ك): فيل نيك · فال ناك · فايل نايك · مفيول منيوك · اتفال اتناك · متفال متناك ·
     *                   تفال تناك · فيّال نياك
     *  - doubled (م ص ص): فع مص · فعّال مصاص · مفعوع ممصوص · اتفع اتمص · متفع متمص, plus the
     *                   imperfect stems يمص تمص نمص بيمص بتمص هيمص… (a 2-letter stem only
     *                   takes the short, safe affix set, so these are listed in full)
     *  - four-letter (ش ر م ط): فعلل شرمط · فعلول شرموط · اتفعلل اتشرمط · متفعلل متشرمط ·
     *                   تفعلل تشرمط (بيتشرمط) · فعاليل شراميط
     */
    fun derive(letters: List<String>): List<String> {
        require(letters.all { it.length == 1 }) { "root letters must be single characters: $letters" }
        val r = letters.joinToString("")
        return when {
            letters.size == 4 -> {
                val (a, b, c, d) = letters
                listOf(r, a + b + c + "و" + d, "ات$r", "مت$r", "ت$r", a + b + "ا" + c + "ي" + d)
            }
            letters.size == 3 && (letters[1] == "ي" || letters[1] == "و") -> {
                val (f, w, l) = letters
                listOf(f + w + l, f + "ا" + l, f + "اي" + l, "م" + f + "يو" + l, "ات" + f + "ا" + l,
                    "مت" + f + "ا" + l, "ت" + f + "ا" + l, f + "يا" + l)
            }
            letters.size == 3 && letters[1] == letters[2] -> {
                val (f, a, _) = letters
                val base = f + a
                listOf(base, base + "ا" + a, "م" + base + "و" + a, "ات$base", "مت$base") +
                    listOf("ي", "ت", "ن", "بي", "بت", "بن", "هي", "هت", "هن", "حي", "حت").map { it + base }
            }
            letters.size == 3 -> {
                val (f, a, l) = letters
                listOf(r, f + "ا" + a + l, "م" + f + a + "و" + l, "ات$r", "مت$r", "ت$r", f + a + "ا" + l)
            }
            else -> throw IllegalArgumentException("unsupported root length: $letters")
        }.distinct()
    }
}

/**
 * English (and Franco-Arabic) morphology for keyword matching. Pure Kotlin.
 */
object EnglishMorphology {

    private const val VOWELS = "aeiou"
    private val cache = HashMap<String, Set<String>>()
    private val keyCache = HashMap<String, Set<String>>()

    /**
     * Derived forms of a Latin entry of 3+ letters: itself; plurals (-s -es -z, y→-ies);
     * -ed/-d; -er/-ers; -ing and informal -in (e-dropping: grope → groping; consonant
     * doubling: cum → cumming, slut → slutty); diminutive -y/-ie/-ies (boob → boobie);
     * Arabizi pronoun endings (-ak -ek -ik -ha -i -ny -ni).
     */
    @Synchronized
    fun derivations(e: String): Set<String> = cache.getOrPut(e) {
        val out = LinkedHashSet<String>()
        out += e
        if (e.length < 3) return@getOrPut out
        val endings = listOf("s", "es", "z", "ed", "er", "ers", "ing", "y", "ie", "ies",
            "ak", "ek", "ik", "ha", "i", "ny", "ni")
        endings.forEach { out += e + it }
        if (e.length >= 4) out += e + "in"
        if (e.endsWith("e")) {
            out += e + "d" // grope → groped
            val stem = e.dropLast(1)
            listOf("ing", "in", "y", "ie", "ies").forEach { out += stem + it } // grope → groping
        }
        if (e.endsWith("y")) {
            val stem = e.dropLast(1)
            listOf("ies", "ied", "ie", "ier").forEach { out += stem + it }
        }
        // Consonant doubling for short consonant-vowel-consonant endings (cum → cumming,
        // slut → slutty). Not with -er: that makes ordinary words (scat → scatter, tit → titter).
        val n = e.length
        if (n in 3..5 && e[n - 1] !in VOWELS && e[n - 1] !in "wxy" && e[n - 2] in VOWELS && e[n - 3] !in VOWELS) {
            val dbl = e + e.last()
            listOf("ing", "in", "ed", "y", "ie", "ies").forEach { out += dbl + it }
        }
        out
    }

    /**
     * Spelling-variant key: ph→f, ck→k, z→s — so "phuk"/"fuk", "kok"/"cock",
     * "pussy"/"puzzy", "boobz"/"boobs" share a key. (Broader rules — c→k, collapsing
     * double letters — were tried and rejected on the corpus: "success" ~ "sucks",
     * "skates" ~ "scat", "pony" ~ "poon".)
     */
    fun spellingKey(s: String): String = s.replace("ph", "f").replace("ck", "k").replace('z', 's')

    /** Spelling keys of every derivation of [e]. */
    @Synchronized
    fun spellingKeys(e: String): Set<String> = keyCache.getOrPut(e) { derivations(e).map { spellingKey(it) }.toSet() }
}

/** Short, single-line excerpts of matched text for the local review log. */
object TextSnippet {
    /** Up to [radius] characters either side of [match], whitespace collapsed, capped at [maxLength]. */
    fun around(text: String, match: KeywordMatcher.Match, radius: Int = 40, maxLength: Int = 160): String {
        val from = (match.start - radius).coerceAtLeast(0)
        val to = (match.end + radius).coerceAtMost(text.length)
        val body = text.substring(from, to).replace(Regex("\\s+"), " ").trim()
        val clipped = if (body.length > maxLength) body.take(maxLength) else body
        return (if (from > 0) "…" else "") + clipped + (if (to < text.length) "…" else "")
    }
}
