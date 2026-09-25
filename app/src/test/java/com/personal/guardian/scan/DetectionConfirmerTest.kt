package com.personal.guardian.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the confirmation rule — "at least N positive frames within the
 * rolling window, negatives in between allowed" — and the threshold logic.
 */
class DetectionConfirmerTest {

    private val p = TriggerSource.PERIODIC
    private val e = TriggerSource.EVENT

    /** Explicit config so these tests don't depend on tunable defaults. */
    private fun confirmer() = DetectionConfirmer(threshold = 0.8f, requiredPositives = 2, windowMs = 7_000)

    @Test
    fun defaultsComeFromScanConfig() {
        val c = DetectionConfirmer()
        assertEquals(ScanConfig.NSFW_THRESHOLD, c.threshold)
        assertEquals(2, c.requiredPositives)
        assertEquals(7_000L, c.windowMs)
        assertEquals(7_000L, ScanConfig.CONFIRMATION_WINDOW_MS)
    }

    // ---- Threshold ----

    @Test
    fun thresholdIsInclusive() {
        val c = confirmer()
        assertTrue(c.isPositive(0.8f))
        assertTrue(c.isPositive(0.99f))
        assertFalse(c.isPositive(0.7999f))
        assertFalse(c.isPositive(0f))
    }

    @Test
    fun defaultThresholdIsPointThreeOnTheSexyPornHentaiSignal() {
        assertEquals(0.3f, ScanConfig.NSFW_THRESHOLD)
        val c = DetectionConfirmer()
        assertTrue(c.isPositive(0.3f))
        assertTrue(c.isPositive(0.35f))
        assertFalse(c.isPositive(0.2999f))
        // Highest signal seen on ordinary photos/screens in model testing (~0.19) stays negative.
        assertFalse(c.isPositive(0.19f))
        assertFalse(c.isPositive(0.05f))
    }

    // ---- New rule: non-consecutive positives within the window ----

    @Test
    fun positiveNegativePositiveWithinWindowConfirms() {
        // Under the old "strictly consecutive" rule the middle frame reset the streak
        // and this did NOT confirm.
        val c = confirmer()
        assertNull(c.onFrame(0.9f, 0, e))
        assertNull(c.onFrame(0.2f, 1_500, e))
        assertEquals("negative frame no longer resets", 1, c.pendingPositives)
        val confirmation = c.onFrame(0.9f, 3_000, e)
        assertNotNull(confirmation)
        assertEquals("only positives are part of the confirmation", listOf(0L, 3_000L), confirmation!!.frames.map { it.atMs })
    }

    @Test
    fun severalNegativesBetweenPositivesWithinWindowStillConfirm() {
        val c = confirmer()
        assertNull(c.onFrame(0.9f, 0, e))
        assertNull(c.onFrame(0.1f, 1_500, e))
        assertNull(c.onFrame(0.5f, 3_000, e))
        assertNull(c.onFrame(0.79f, 4_500, e))
        assertNotNull(c.onFrame(0.85f, 6_000, e))
    }

    @Test
    fun atDefaultThresholdPositiveDipPositiveConfirms() {
        // Shipped config: 0.3 threshold, 2 positives, 7 s window. A 0.29 dip between
        // two 0.31 frames used to reset; now it confirms.
        val c = DetectionConfirmer()
        assertNull(c.onFrame(0.31f, 0, e))
        assertNull(c.onFrame(0.29f, ScanConfig.FAST_INTERVAL_MS, e))
        assertNotNull(c.onFrame(0.31f, 2 * ScanConfig.FAST_INTERVAL_MS, e))
    }

    @Test
    fun alternatingScoresInFastModeNowConfirm() {
        // Fast mode, scores flickering 0.35 / 0.25 around the 0.3 threshold for 30 s.
        // Old rule: never confirmed. New rule: positives every 3 s → one confirmation
        // per two positives → 5 confirmations.
        val c = DetectionConfirmer()
        var t = 0L
        val confirmedAt = mutableListOf<Long>()
        repeat(20) { i ->
            val score = if (i % 2 == 0) 0.35f else 0.25f
            if (c.onFrame(score, t, e) != null) confirmedAt += t
            t += ScanConfig.FAST_INTERVAL_MS
        }
        assertEquals(listOf(3_000L, 9_000L, 15_000L, 21_000L, 27_000L), confirmedAt)
    }

    @Test
    fun positivesWithNegativesBetweenButFurtherApartThanWindowDoNotConfirm() {
        val c = confirmer()
        assertNull(c.onFrame(0.9f, 0, e))
        var t = 1_500L
        while (t < 7_001) { assertNull(c.onFrame(0.1f, t, e)); t += 1_500 }
        assertNull("7.001 s after the first positive: outside the window", c.onFrame(0.9f, 7_001, e))
        assertEquals("the stale positive aged out; the new one counts", 1, c.pendingPositives)
    }

    @Test
    fun negativeFramesAlsoAgeOutStalePositives() {
        val c = confirmer()
        c.onFrame(0.9f, 0, p)
        assertEquals(1, c.pendingPositives)
        c.onFrame(0.1f, 8_000, p)
        assertEquals(0, c.pendingPositives)
    }

    @Test
    fun sparsePositivesMoreThanWindowApartNeverConfirm() {
        val c = confirmer()
        for (i in 0 until 10) {
            assertNull("positive $i", c.onFrame(0.95f, i * 8_000L, p))
            assertNull(c.onFrame(0.1f, i * 8_000L + 4_000, p))
        }
    }

    // ---- Interaction with the capture intervals ----

    @Test
    fun baselineIntervalLeavesMarginInsideWindow() {
        // Two consecutive normal-mode frames are BASELINE_INTERVAL_MS apart plus
        // timer and classification jitter (~100–300 ms); they must still fit.
        assertTrue(ScanConfig.BASELINE_INTERVAL_MS + 500 <= ScanConfig.CONFIRMATION_WINDOW_MS)
    }

    @Test
    fun twoNormalModeFramesWithJitterConfirm() {
        val c = DetectionConfirmer()
        assertNull(c.onFrame(0.3f, 10_000, p))
        val jitter = 400L
        assertNotNull(c.onFrame(0.3f, 10_000 + ScanConfig.BASELINE_INTERVAL_MS + jitter, p))
    }

    @Test
    fun normalModePositiveNegativePositiveDoesNotConfirmAcrossTwoIntervals() {
        // In normal mode, positive → negative → positive spans two intervals (12 s),
        // which exceeds the 7 s window. Fast mode is where dips get bridged.
        val c = DetectionConfirmer()
        val step = ScanConfig.BASELINE_INTERVAL_MS
        assertNull(c.onFrame(0.3f, 0, p))
        assertNull(c.onFrame(0.1f, step, p))
        assertNull(c.onFrame(0.3f, 2 * step, p))
    }

    // ---- Unchanged behaviour ----

    @Test
    fun singlePositiveFrameDoesNotConfirm() {
        val c = confirmer()
        assertNull(c.onFrame(0.99f, 0, p))
        assertNull(c.onFrame(0.1f, 1_500, p))
        assertNull(c.onFrame(0.1f, 3_000, p))
        assertEquals(1, c.pendingPositives)
    }

    @Test
    fun atDefaultThresholdASingleLowPositiveDoesNotConfirm() {
        val c = DetectionConfirmer()
        assertNull(c.onFrame(0.31f, 0, e))
        var t = ScanConfig.FAST_INTERVAL_MS
        while (t <= ScanConfig.CONFIRMATION_WINDOW_MS) {
            assertNull(c.onFrame(0.05f, t, e))
            t += ScanConfig.FAST_INTERVAL_MS
        }
    }

    @Test
    fun twoPositivesInARowStillConfirm() {
        val c = confirmer()
        assertNull(c.onFrame(0.85f, 1_000, e))
        val confirmation = c.onFrame(0.93f, 2_500, e)
        assertNotNull(confirmation)
        confirmation!!
        assertEquals(2, confirmation.frames.size)
        assertEquals(0.93f, confirmation.latest.score)
        assertEquals(e, confirmation.latest.source)
    }

    @Test
    fun positivesExactlyAtWindowEdgeConfirm() {
        val c = confirmer()
        assertNull(c.onFrame(0.95f, 0, p))
        assertNotNull(c.onFrame(0.95f, 7_000, p))
    }

    @Test
    fun positivesJustBeyondWindowDoNotConfirm() {
        val c = confirmer()
        assertNull(c.onFrame(0.95f, 0, p))
        assertNull(c.onFrame(0.95f, 7_001, p))
        assertEquals(1, c.pendingPositives)
        assertNotNull(c.onFrame(0.95f, 14_000, p))
    }

    @Test
    fun continuousPositivesConfirmOncePerTwoFrames() {
        // 30 s of the same content in fast mode (1.5 s): 20 positive frames → 10
        // confirmations, not 19 (positives are cleared after each confirmation).
        val c = DetectionConfirmer()
        var t = 0L
        var confirmations = 0
        repeat(20) {
            if (c.onFrame(0.35f, t, e) != null) confirmations++
            t += ScanConfig.FAST_INTERVAL_MS
        }
        assertEquals(10, confirmations)
    }

    @Test
    fun positivesAreClearedAfterConfirmation() {
        val c = confirmer()
        c.onFrame(0.9f, 0, e)
        assertNotNull(c.onFrame(0.9f, 1_500, e))
        assertEquals(0, c.pendingPositives)
        assertNull("needs two fresh positives after confirming", c.onFrame(0.9f, 3_000, e))
        assertNull(c.onFrame(0.1f, 4_500, e))
        assertNotNull(c.onFrame(0.9f, 6_000, e))
    }

    @Test
    fun requiredCountIsConfigurableAndNegativesDoNotReset() {
        val c = DetectionConfirmer(threshold = 0.5f, requiredPositives = 3, windowMs = 5_000)
        assertNull(c.onFrame(0.6f, 0, e))
        assertNull(c.onFrame(0.1f, 1_000, e))
        assertNull(c.onFrame(0.6f, 2_000, e))
        assertNull(c.onFrame(0.1f, 3_000, e))
        assertNotNull(c.onFrame(0.6f, 4_000, e))
    }

    @Test
    fun resetClearsCollectedPositives() {
        val c = confirmer()
        c.onFrame(0.9f, 0, p)
        c.reset()
        assertNull(c.onFrame(0.9f, 1_000, p))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsThresholdOutsideUnitRange() {
        DetectionConfirmer(threshold = 1.5f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsZeroRequiredCount() {
        DetectionConfirmer(requiredPositives = 0)
    }
}
