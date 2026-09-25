package com.personal.guardian.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the calibration frame log line and exit-reason names. */
class ScanLogTest {

    private val suggestive = NsfwScores(drawings = 0.017f, hentai = 0f, neutral = 0.34f, porn = 0.031f, sexy = 0.612f)
    private val ordinary = NsfwScores(drawings = 0.008f, hentai = 0.001f, neutral = 0.984f, porn = 0f, sexy = 0.006f)

    @Test
    fun frameLineShowsSignalThresholdClassBreakdownSourceAppAndPositives() {
        assertEquals(
            "Scan frame: signal=0.6430 [>= 0.30] sexy=0.612 porn=0.031 hentai=0.000 neutral=0.340 drawings=0.017 " +
                "trigger=event app=com.whatsapp positives=1/2",
            ScanLog.frameLine(suggestive, 0.3f, TriggerSource.EVENT, "com.whatsapp", 1, 2)
        )
        assertEquals(
            "Scan frame: signal=0.0070 [< 0.30] sexy=0.006 porn=0.000 hentai=0.001 neutral=0.984 drawings=0.008 " +
                "trigger=periodic app=unknown positives=0/2",
            ScanLog.frameLine(ordinary, 0.3f, TriggerSource.PERIODIC, null, 0, 2)
        )
    }

    @Test
    fun frameLineAtExactThresholdCountsAsPositive() {
        val atThreshold = NsfwScores(drawings = 0f, hentai = 0f, neutral = 0.7f, porn = 0f, sexy = 0.3f)
        assertTrue(ScanLog.frameLine(atThreshold, 0.3f, TriggerSource.PERIODIC, "a.b", 1, 2).startsWith("Scan frame: signal=0.3000 [>= 0.30]"))
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
