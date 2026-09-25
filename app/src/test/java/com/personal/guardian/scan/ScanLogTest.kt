package com.personal.guardian.scan

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure-JVM tests for the calibration frame log line and exit-reason names. */
class ScanLogTest {

    @Test
    fun frameLineShowsRawScoreThresholdSourceAppAndPositives() {
        assertEquals(
            "Scan frame: score=0.6312 [>= 0.50] trigger=event app=com.whatsapp positives=1/2",
            ScanLog.frameLine(0.63124f, 0.5f, TriggerSource.EVENT, "com.whatsapp", 1, 2)
        )
        assertEquals(
            "Scan frame: score=0.0421 [< 0.50] trigger=periodic app=unknown positives=0/2",
            ScanLog.frameLine(0.0421f, 0.5f, TriggerSource.PERIODIC, null, 0, 2)
        )
    }

    @Test
    fun frameLineAtExactThresholdCountsAsPositive() {
        val line = ScanLog.frameLine(0.5f, 0.5f, TriggerSource.PERIODIC, "a.b", 1, 2)
        assertEquals("Scan frame: score=0.5000 [>= 0.50] trigger=periodic app=a.b positives=1/2", line)
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
