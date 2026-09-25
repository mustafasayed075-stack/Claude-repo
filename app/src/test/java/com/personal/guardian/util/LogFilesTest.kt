package com.personal.guardian.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pure-JVM tests for size-capped log files, including that the separate
 * diagnostics log survives heavy per-frame logging in the main log.
 */
class LogFilesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // Same caps as GuardianLog.
    private val mainMax = 1L * 1024 * 1024
    private val mainTrim = 512L * 1024
    private val diagMax = 256L * 1024
    private val diagTrim = 192L * 1024

    @Test
    fun rotationKeepsNewestWholeLinesAndAddsMarker() {
        val f = tmp.newFile("log.txt")
        for (i in 0 until 100) LogFiles.append(f, "line-%03d\n".format(i), 500, 200, "--- rotated ---\n")
        val lines = f.readLines()
        assertTrue(f.length() < 500 + 20)
        assertTrue("no partial lines", lines.all { it.startsWith("line-") || it == "--- rotated ---" })
        assertEquals("line-099", lines.last())
        assertTrue(lines.contains("--- rotated ---"))
    }

    @Test
    fun diagnosticsSurviveHeavyPerFrameLoggingThatRotatesTheMainLog() {
        val main = tmp.newFile("guardian-events.log")
        val diag = tmp.newFile("guardian-diagnostics.log")
        fun log(line: String, diagnostic: Boolean) {
            LogFiles.append(main, line, mainMax, mainTrim, "--- log rotated ---\n")
            if (diagnostic) LogFiles.append(diag, line, diagMax, diagTrim, "--- log rotated ---\n")
        }

        val exitLine = "2026-09-25 14:00:00.000 WARN Previous Guardian process (pid 123) ended: reason=CRASH_NATIVE\n"
        log(exitLine, diagnostic = true)
        // ~2.3 MB of per-frame score lines: roughly 14 h of fast-mode scanning.
        val frame = "2026-09-25 14:00:01.500 INFO Scan frame: score=0.2311 [>= 0.20] trigger=event app=com.whatsapp streak=1/2\n"
        repeat(20_000) { log(frame, diagnostic = false) }

        assertFalse("main log has rotated the diagnostic line out", main.readText().contains("CRASH_NATIVE"))
        assertTrue(main.length() <= mainMax + frame.length + 64)
        assertEquals("diagnostics log kept it", exitLine, diag.readText())
    }
}
