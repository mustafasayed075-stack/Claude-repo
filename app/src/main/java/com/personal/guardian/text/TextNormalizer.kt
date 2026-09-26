package com.personal.guardian.text

import java.text.Normalizer

/**
 * Turns raw on-screen text into normalised tokens for keyword matching (Stage 4).
 *
 * Per character: Unicode compatibility-normalised (NFKC: full-width letters,
 * Arabic presentation forms…), decomposed with combining marks dropped (Latin
 * accents, Arabic harakat, hamza/madda on alef/waw/ya), invisible format characters
 * and tatweel removed, lower-cased, and Arabic letter variants unified
 * (ة→ه, ى→ي, ٱ→ا, Persian ک/ی/گ → ك/ي/ك). Dropped characters don't split a word,
 * so "ســكْــس" and "s​ex" stay one token.
 *
 * Tokens are runs of letters/digits (plus '@' and '$', common leetspeak letters).
 * Runs of 2+ single-character tokens are joined, so "s e x", "s.e.x" and "س ك س"
 * become one token. Each token also has variants for repeated letters and
 * leetspeak (see [Token.forms]).
 *
 * Pure Kotlin (java.text only), unit-testable without Android.
 */
object TextNormalizer {

    /**
     * A normalised token. [start]/[end] are indices in the original text (for
     * snippets); [raw] is the normalised spelling.
     */
    class Token(val start: Int, val end: Int, val raw: String) {
        /**
         * Spellings to compare against list entries: [raw]; with repeated-letter runs
         * collapsed to one ("sexxx"→"sex") and to two ("boooobs"→"boobs"); and the
         * same three for the leetspeak reading ("p0rn"→"porn", "$3x"→"sex").
         * Entries themselves are never collapsed or leet-decoded, so "xxx" still
         * needs "xxx" and "a7ba" still needs its digit.
         */
        val forms: Set<String> by lazy {
            val leet = leetDecode(raw)
            linkedSetOf(raw, collapse(raw, 1), collapse(raw, 2), leet, collapse(leet, 1), collapse(leet, 2))
        }

        override fun toString() = "Token($raw@$start..$end)"
    }

    /** Splits [text] into normalised tokens. */
    fun tokenize(text: CharSequence): List<Token> {
        val tokens = ArrayList<Token>()
        val buf = StringBuilder()
        var tokStart = -1
        var tokEnd = -1
        fun flush() {
            if (buf.isNotEmpty()) tokens += Token(tokStart, tokEnd, buf.toString())
            buf.setLength(0)
            tokStart = -1
        }

        var i = 0
        val s = text.toString()
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val next = i + Character.charCount(cp)
            val mapped = mapCodePoint(cp)
            if (mapped == null) {
                // Ignorable (diacritic, tatweel, zero-width…): neither text nor separator.
            } else {
                for (c in mapped) {
                    if (isWordChar(c)) {
                        if (tokStart < 0) tokStart = i
                        buf.append(c)
                        tokEnd = next
                    } else {
                        flush()
                    }
                }
            }
            i = next
        }
        flush()
        return joinSpacedLetters(tokens)
    }

    /** Normalises a single code point; null means "drop it". */
    internal fun mapCodePoint(cp: Int): String? {
        if (cp == TATWEEL) return null
        val type = Character.getType(cp)
        if (type == Character.FORMAT.toInt()) return null // zero-width, bidi marks, BOM, soft hyphen
        val decomposed = Normalizer.normalize(
            Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFKC),
            Normalizer.Form.NFD
        )
        val out = StringBuilder(decomposed.length)
        for (c in decomposed) {
            val t = Character.getType(c)
            if (t == Character.NON_SPACING_MARK.toInt() || t == Character.ENCLOSING_MARK.toInt()) continue
            if (c.code == TATWEEL) continue
            out.append(unifyLetter(c.lowercaseChar()))
        }
        return if (out.isEmpty()) null else out.toString()
    }

    private fun unifyLetter(c: Char): Char = when (c) {
        'ة' -> 'ه'
        'ى', 'ی' -> 'ي'
        'ٱ' -> 'ا'
        'ک', 'گ' -> 'ك'
        else -> c
    }

    private fun isWordChar(c: Char) = Character.isLetterOrDigit(c) || c == '@' || c == '$'

    /** Joins runs of 2+ consecutive one-character tokens ("s e x" → "sex"). */
    private fun joinSpacedLetters(tokens: List<Token>): List<Token> {
        val out = ArrayList<Token>(tokens.size)
        var i = 0
        while (i < tokens.size) {
            var j = i
            while (j < tokens.size && tokens[j].raw.length == 1) j++
            if (j - i >= 2) {
                out += Token(tokens[i].start, tokens[j - 1].end, tokens.subList(i, j).joinToString("") { it.raw })
                i = j
            } else {
                out += tokens[i]
                i++
            }
        }
        return out
    }

    /** Collapses runs of a repeated character longer than [keep] down to [keep]. */
    internal fun collapse(s: String, keep: Int): String {
        val sb = StringBuilder(s.length)
        var run = 0
        for (k in s.indices) {
            run = if (k > 0 && s[k] == s[k - 1]) run + 1 else 1
            if (run <= keep) sb.append(s[k])
        }
        return sb.toString()
    }

    /** Leetspeak reading of a token ("p0rn" → "porn", "$3x" → "sex"). */
    internal fun leetDecode(s: String): String {
        if (s.none { it in LEET }) return s
        return buildString(s.length) { for (c in s) append(LEET[c] ?: c) }
    }

    private const val TATWEEL = 0x0640

    private val LEET = mapOf(
        '0' to 'o', '1' to 'i', '3' to 'e', '4' to 'a', '5' to 's',
        '7' to 't', '8' to 'b', '@' to 'a', '$' to 's'
    )
}
