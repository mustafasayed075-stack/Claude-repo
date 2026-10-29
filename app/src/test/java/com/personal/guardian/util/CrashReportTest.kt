package com.personal.guardian.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The crash summary keeps exception class names and frames only — never messages. */
class CrashReportTest {

    private fun deep(n: Int): Throwable = if (n == 0) IllegalStateException("secret user text") else deep(n - 1)

    @Test
    fun keepsTheClassNameAndAtMostFifteenFrames() {
        val e = deep(40)
        val report = CrashReport.format(e, "main")
        val lines = report.lines()
        assertEquals("CRASH on thread \"main\": java.lang.IllegalStateException", lines[0])
        assertEquals(15, lines.count { it.startsWith("    at ") })
        assertTrue(lines.last().startsWith("    … ") && lines.last().endsWith(" more"))
        assertTrue(lines[1].contains("CrashReportTest.deep"))
    }

    @Test
    fun neverIncludesExceptionMessages() {
        val e = RuntimeException("Unable to open content://user/private/photo.jpg", NullPointerException("chat text here"))
        val report = CrashReport.format(e, "main")
        assertFalse(report.contains("content://"))
        assertFalse(report.contains("chat text"))
        assertFalse(report.contains("Unable to open"))
    }

    @Test
    fun includesTheCauseChainBecauseAndroidWrapsLaunchFailures() {
        val npe = NullPointerException("x")
        val e = RuntimeException("Unable to instantiate activity", npe)
        val report = CrashReport.format(e, "main")
        assertTrue(report.contains("\n  Caused by: java.lang.NullPointerException"))
        val causeFrames = report.substringAfter("Caused by:").lines().count { it.startsWith("    at ") }
        assertTrue(causeFrames in 1..CrashReport.MAX_FRAMES)
    }

    @Test
    fun aCyclicOrDeepCauseChainStaysBounded() {
        val a = RuntimeException("a")
        val b = IllegalArgumentException("b", a)
        a.initCause(b)
        val report = CrashReport.format(a, "worker")
        assertTrue(report.split("Caused by:").size - 1 <= CrashReport.MAX_CAUSES)

        var chain: Throwable = Error("root")
        repeat(10) { chain = RuntimeException("level", chain) }
        assertEquals(CrashReport.MAX_CAUSES, CrashReport.format(chain, "t").split("Caused by:").size - 1)
    }

    @Test
    fun aThreadNameCannotBreakTheLogLine() {
        val report = CrashReport.format(Error(), "evil\nINFO fake line")
        assertTrue(report.lines()[0].startsWith("CRASH on thread \"evil INFO fake line\""))
    }
}
