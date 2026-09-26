package com.personal.guardian.text

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Ordinary-text corpus evaluation of the bundled list (README "Stage 4"). Skipped
 * unless the environment variable GUARDIAN_CORPUS_DIR points at a directory of
 * UTF-8 text files (one message/sentence per line), e.g. `dev/` and `test/`
 * sub-directories. Writes every match — active or context-suppressed — to
 * `$GUARDIAN_CORPUS_DIR/matches.tsv`:
 *   split  file  term  suppressedBy(or "-")  matchedText  snippet
 */
class CorpusEvaluationTest {

    @Test
    fun evaluateOrdinaryTextCorpora() {
        val dir = System.getenv("GUARDIAN_CORPUS_DIR")?.let(::File)
        assumeTrue("GUARDIAN_CORPUS_DIR not set", dir != null && dir.isDirectory)
        val matcher = KeywordMatcher(File("src/main/assets/text/keywords.txt").bufferedReader().use { KeywordList.parse(it) })
        val files = dir!!.walkTopDown().filter { it.isFile && it.extension == "txt" }.sortedBy { it.path }.toList()
        File(dir, "matches.tsv").printWriter().use { out ->
            for (file in files) {
                val split = file.parentFile.name
                file.forEachLine { line ->
                    for (m in matcher.analyze(line)) {
                        val snippet = TextSnippet.around(line, m, 45).replace('\t', ' ')
                        out.println(listOf(split, file.name, m.term, m.suppressedBy ?: "-", m.matchedText, snippet).joinToString("\t"))
                    }
                }
            }
        }
    }
}
