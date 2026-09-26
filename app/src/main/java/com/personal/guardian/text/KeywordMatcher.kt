package com.personal.guardian.text

import java.io.BufferedReader

/**
 * The Stage 4 keyword/phrase list, parsed from the bundled asset
 * (`assets/text/keywords.txt`, see README "Stage 4").
 *
 * File format: one word or phrase per line; `#` starts a comment; a line
 * `!token` declares an exception — an innocent word that must never match even if
 * it looks like an entry plus affixes (e.g. "زبون" customer); it also applies
 * behind an Arabic prefix. Entries are normalised
 * with [TextNormalizer], so they can be written in any case/letter form.
 */
class KeywordList private constructor(
    val entries: List<Entry>,
    val exceptions: Set<String>
) {
    /** One list entry: its text as written, and its normalised tokens. */
    class Entry(val term: String, val tokens: List<String>)

    companion object {
        fun parse(reader: BufferedReader): KeywordList = parse(reader.lineSequence())

        fun parse(lines: Sequence<String>): KeywordList {
            val entries = ArrayList<Entry>()
            val exceptions = HashSet<String>()
            val seen = HashSet<List<String>>()
            for (rawLine in lines) {
                val line = rawLine.substringBefore('#').trim()
                if (line.isEmpty()) continue
                if (line.startsWith("!")) {
                    TextNormalizer.tokenize(line.substring(1)).forEach { exceptions += it.raw }
                    continue
                }
                val tokens = TextNormalizer.tokenize(line).map { it.raw }
                if (tokens.isEmpty() || !seen.add(tokens)) continue
                entries += Entry(line, tokens)
            }
            return KeywordList(entries, exceptions)
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
 *  - leetspeak (p0rn, s3x, $ex), repeated letters (sexxx, boooobs) and spaced-out
 *    letters (s e x, س ك س);
 *  - English inflections (s, es, ies, ed, d, er, ers, ing, z) and Arabizi pronoun
 *    endings (ak, ek, ik, ha, y, i, ny, ni) on Latin-script entries of 3+ letters;
 *  - Arabic clitics: prefixes (و ف ال لل ب ل and verb prefixes ي ت ن ا ه ح بي هي…)
 *    and pronoun/plural suffixes (ي ك ه ها هم كم نا ني ات ين…). Entries of 1–2
 *    letters (e.g. كس) only take "ال"/"وال" and a short, safe suffix set, because
 *    longer affixes produce ordinary words (زب+ون = زبون "customer").
 * Multi-word entries must match token by token; only their last word takes suffixes.
 */
class KeywordMatcher(private val list: KeywordList) {

    /** A match: the list [term], and where it was found in the original text. */
    data class Match(val term: String, val start: Int, val end: Int, val matchedText: String)

    val entryCount: Int get() = list.entries.size

    /** Entries indexed by their first normalised token. */
    private val index: Map<String, List<KeywordList.Entry>> =
        list.entries.groupBy { it.tokens.first() }

    /** All matches in [text], in order of appearance (at most [limit]). */
    fun find(text: CharSequence, limit: Int = 50): List<Match> {
        val tokens = TextNormalizer.tokenize(text)
        if (tokens.isEmpty()) return emptyList()
        val s = text.toString()
        val out = ArrayList<Match>()
        for (i in tokens.indices) {
            val token = tokens[i]
            if (isException(token)) continue
            for (key in candidateKeys(token)) {
                val entries = index[key] ?: continue
                for (entry in entries) {
                    val n = entry.tokens.size
                    if (i + n > tokens.size) continue
                    if (matchesAt(tokens, i, entry)) {
                        val start = token.start
                        val end = tokens[i + n - 1].end
                        out += Match(entry.term, start, end, s.substring(start, end))
                        if (out.size >= limit) return out
                    }
                }
            }
        }
        return out.distinctBy { it.term to it.start }
    }

    fun containsMatch(text: CharSequence): Boolean = find(text, limit = 1).isNotEmpty()

    private fun matchesAt(tokens: List<TextNormalizer.Token>, i: Int, entry: KeywordList.Entry): Boolean {
        val n = entry.tokens.size
        for (k in 0 until n) {
            val tok = tokens[i + k]
            if (k > 0 && isException(tok)) return false
            val ok = if (k == n - 1) affixMatch(tok, entry.tokens[k]) else tok.forms.contains(entry.tokens[k])
            if (!ok) return false
        }
        return true
    }

    /** Exceptions also apply behind an Arabic prefix (ونيكي = و + exception نيكي). */
    private fun isException(token: TextNormalizer.Token): Boolean {
        if (list.exceptions.isEmpty()) return false
        for (f in token.forms) {
            if (f in list.exceptions) return true
            if (isArabic(f)) {
                for (p in ARABIC_PREFIXES) {
                    if (p.isNotEmpty() && f.startsWith(p) && f.substring(p.length) in list.exceptions) return true
                }
            }
        }
        return false
    }

    /** Possible first-token keys for [token]: its forms, and forms with affixes removed. */
    private fun candidateKeys(token: TextNormalizer.Token): Set<String> {
        val keys = LinkedHashSet<String>()
        for (f in token.forms) {
            keys += f
            if (isArabic(f)) {
                for (p in ARABIC_PREFIXES) {
                    if (!f.startsWith(p)) continue
                    val rest = f.substring(p.length)
                    if (rest.length < 2) continue
                    keys += rest
                    for (sfx in ARABIC_SUFFIXES) {
                        if (rest.endsWith(sfx) && rest.length - sfx.length >= 2) keys += rest.dropLast(sfx.length)
                    }
                }
            } else {
                for (sfx in LATIN_SUFFIXES) {
                    if (f.endsWith(sfx) && f.length - sfx.length >= 2) keys += f.dropLast(sfx.length)
                }
                if (f.endsWith("ies") && f.length > 4) keys += f.dropLast(3) + "y"
            }
        }
        return keys
    }

    /** True if some form of [token] is [entry] itself or [entry] with allowed affixes. */
    private fun affixMatch(token: TextNormalizer.Token, entry: String): Boolean {
        for (f in token.forms) {
            if (f == entry) return true
            if (isArabic(entry)) {
                val short = entry.length <= 2
                val prefixes = if (short) SHORT_ARABIC_PREFIXES else ARABIC_PREFIXES
                val suffixes = if (short) SHORT_ARABIC_SUFFIXES else ARABIC_SUFFIXES
                for (p in prefixes) {
                    if (!f.startsWith(p)) continue
                    val rest = f.substring(p.length)
                    if (rest == entry) return true
                    if (rest.startsWith(entry) && rest.substring(entry.length) in suffixes) return true
                }
            } else if (entry.length > 2) {
                // 1–2 letter Latin entries match exactly only ("sm" + "d" = "smd").
                if (f.startsWith(entry) && f.substring(entry.length) in LATIN_SUFFIXES) return true
                if (entry.endsWith("y") && f == entry.dropLast(1) + "ies") return true
            }
        }
        return false
    }

    companion object {
        private fun isArabic(s: String) = s.any { it in '؀'..'ۿ' }

        private val LATIN_SUFFIXES = setOf(
            "s", "es", "ed", "d", "er", "ers", "ing", "z", "y",
            // Arabizi pronoun endings: kos-ak, zeb-y, neek-ny …
            "ak", "ek", "ik", "ha", "i", "ny", "ni"
        )

        private val ARABIC_SUFFIXES = setOf(
            "ي", "ك", "ه", "ها", "هم", "هن", "كم", "كو", "نا", "ني", "ات", "ين", "وا",
            "تي", "تك", "ته", "تها", "يه", "اتك", "اتها"
        )
        private val SHORT_ARABIC_SUFFIXES = setOf("ي", "ك", "ه", "ها", "هم", "كم", "نا", "ات")

        /** "" plus conjunction (و/ف) × article/preposition/verb prefix combinations. */
        private val ARABIC_PREFIXES: List<String> = run {
            val outer = listOf("", "و", "ف")
            val inner = listOf(
                "", "ال", "لل", "بال", "ب", "ل", "ه", "ح", "بي", "بت", "بن",
                "هي", "هت", "هن", "حي", "حت", "حن", "ي", "ت", "ن", "ا"
            )
            outer.flatMap { o -> inner.map { o + it } }.distinct().sortedByDescending { it.length }
        }
        private val SHORT_ARABIC_PREFIXES = listOf("وال", "ال", "")
    }
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
