package com.personal.guardian.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the calibration frame log line and exit-reason names. */
class ScanLogTest {

    private val suggestive = NsfwScores(safe = 0.34f, hentai = 0f, porn = 0.031f, sexy = 0.612f, drawing = 0.017f)
    private val ordinary = NsfwScores(safe = 0.984f, hentai = 0.001f, porn = 0f, sexy = 0.006f, drawing = 0.008f)

    @Test
    fun frameLineShowsSignalThresholdClassBreakdownSourceAppAndPositives() {
        assertEquals(
            "Scan frame: signal=0.6430 [>= 0.30] sexy=0.612 porn=0.031 hentai=0.000 safe=0.340 drawing=0.017 " +
                "trigger=event app=com.whatsapp positives=1/2",
            ScanLog.frameLine(suggestive, 0.3f, TriggerSource.EVENT, "com.whatsapp", 1, 2)
        )
        assertEquals(
            "Scan frame: signal=0.0070 [< 0.30] sexy=0.006 porn=0.000 hentai=0.001 safe=0.984 drawing=0.008 " +
                "trigger=periodic app=unknown positives=0/2",
            ScanLog.frameLine(ordinary, 0.3f, TriggerSource.PERIODIC, null, 0, 2)
        )
    }

    @Test
    fun frameLineAtExactThresholdCountsAsPositive() {
        val atThreshold = NsfwScores(safe = 0.7f, hentai = 0f, porn = 0f, sexy = 0.3f, drawing = 0f)
        assertTrue(ScanLog.frameLine(atThreshold, 0.3f, TriggerSource.PERIODIC, "a.b", 1, 2).startsWith("Scan frame: signal=0.3000 [>= 0.30]"))
    }

    @Test
    fun frameLineShowsTheSignalThatWasJudged() {
        // Whole screen: a UI screen's hentai leak (0.73) isn't counted in the screen signal (0.0).
        val ui = NsfwScores(safe = 0.13f, hentai = 0.73f, porn = 0f, sexy = 0f, drawing = 0.14f)
        val line = ScanLog.frameLine(ui, ScanConfig.NSFW_THRESHOLD, TriggerSource.EVENT, "com.anthropic.claude", 0, 2, signal = ui.screenSignal)
        assertTrue(line, line.startsWith("Scan frame: signal=0.0000 [< 0.15] sexy=0.000 porn=0.000 hentai=0.730 safe=0.130 drawing=0.140"))
    }

    @Test
    fun exitReasonNamesMatchApplicationExitInfoConstants() {
        assertEquals("LOW_MEMORY", ScanLog.exitReasonName(3))
        assertEquals("CRASH", ScanLog.exitReasonName(4))
        assertEquals("CRASH_NATIVE", ScanLog.exitReasonName(5))
        assertEquals("ANR", ScanLog.exitReasonName(6))
        assertEquals("USER_REQUESTED", ScanLog.exitReasonName(10))
        assertEquals("REASON_99", ScanLog.exitReasonName(99))
    }

    @Test
    fun perFrameLoggingFlagIsOnForCalibration() {
        assertEquals(true, ScanConfig.LOG_EVERY_FRAME_SCORE)
    }
}
