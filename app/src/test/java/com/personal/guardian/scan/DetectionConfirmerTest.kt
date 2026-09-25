package com.personal.guardian.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the confirmation rule (spec §4) and the threshold logic.
 */
class DetectionConfirmerTest {

    private val p = TriggerSource.PERIODIC
    private val e = TriggerSource.EVENT

    private fun confirmer() = DetectionConfirmer(threshold = 0.8f, requiredConsecutive = 2, windowMs = 10_000)

    @Test
    fun defaultsComeFromScanConfig() {
        val c = DetectionConfirmer()
        assertEquals(ScanConfig.NSFW_THRESHOLD, c.threshold)
        assertEquals(2, c.requiredConsecutive)
        assertEquals(10_000L, c.windowMs)
    }

    // ---- Default config (threshold 0.2, 2 frames, 10 s): the rule as shipped ----

    @Test
    fun defaultThresholdIsPointTwoForMaximumSensitivity() {
        assertEquals(0.2f, ScanConfig.NSFW_THRESHOLD)
        val c = DetectionConfirmer()
        assertTrue(c.isPositive(0.2f))
        assertTrue(c.isPositive(0.25f))
        assertFalse(c.isPositive(0.1999f))
        assertFalse(c.isPositive(0.05f))
    }

    @Test
    fun atDefaultThresholdASingleLowPositiveStillDoesNotConfirm() {
        val c = DetectionConfirmer()
        assertNull(c.onFrame(0.21f, 0, p))
        assertEquals(1, c.pendingPositives)
        // A frame just under 0.2 is negative and clears the streak.
        assertNull(c.onFrame(0.19f, 1_500, p))
        assertEquals(0, c.pendingPositives)
        assertNull(c.onFrame(0.21f, 3_000, p))
    }

    @Test
    fun atDefaultThresholdTwoConsecutiveLowPositivesWithin10sConfirm() {
        val c = DetectionConfirmer()
        assertNull(c.onFrame(0.2f, 0, e))
        val confirmation = c.onFrame(0.22f, ScanConfig.FAST_INTERVAL_MS, e)
        assertNotNull(confirmation)
        assertEquals(0.22f, confirmation!!.latest.score)
    }

    @Test
    fun atDefaultThresholdTwoBaselineFramesConfirmButStalePositiveDoesNot() {
        val c = DetectionConfirmer()
        assertNull(c.onFrame(0.3f, 0, p))
        assertNotNull("7 s apart is inside the 10 s window", c.onFrame(0.3f, ScanConfig.BASELINE_INTERVAL_MS, p))

        assertNull(c.onFrame(0.3f, 20_000, p))
        assertNull("10.001 s apart is outside the window", c.onFrame(0.3f, 30_001, p))
        assertEquals(1, c.pendingPositives)
    }

    @Test
    fun atDefaultThresholdAlternatingScoresAroundPointTwoNeverConfirm() {
        // A frame just under threshold between every positive keeps resetting the streak.
        val c = DetectionConfirmer()
        var t = 0L
        repeat(20) { i ->
            val score = if (i % 2 == 0) 0.25f else 0.15f
            assertNull("frame $i", c.onFrame(score, t, e))
            t += ScanConfig.FAST_INTERVAL_MS
        }
    }

    @Test
    fun atDefaultThresholdContinuousPositivesConfirmOncePerTwoFrames() {
        // E.g. 30 s of a beach photo in fast mode (1.5 s): 20 frames, all >= 0.2.
        // The streak restarts after each confirmation, so this yields 10 confirmed
        // detections, not 19 — one every 3 s while the content stays on screen.
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
    fun thresholdIsInclusive() {
        val c = confirmer()
        assertTrue(c.isPositive(0.8f))
        assertTrue(c.isPositive(0.99f))
        assertFalse(c.isPositive(0.7999f))
        assertFalse(c.isPositive(0f))
    }

    @Test
    fun singlePositiveFrameDoesNotConfirm() {
        val c = confirmer()
        assertNull(c.onFrame(0.99f, 0, p))
        assertEquals(1, c.pendingPositives)
        // …and a following negative frame clears it.
        assertNull(c.onFrame(0.1f, 1_500, p))
        assertEquals(0, c.pendingPositives)
    }

    @Test
    fun twoConsecutivePositivesWithinWindowConfirm() {
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
    fun negativeFrameBetweenPositivesResetsStreak() {
        val c = confirmer()
        assertNull(c.onFrame(0.9f, 0, e))
        assertNull(c.onFrame(0.2f, 1_500, e))
        assertNull(c.onFrame(0.9f, 3_000, e))
        assertNotNull(c.onFrame(0.9f, 4_500, e))
    }

    @Test
    fun belowThresholdScoreCountsAsNegative() {
        val c = confirmer()
        assertNull(c.onFrame(0.9f, 0, p))
        assertNull(c.onFrame(0.79f, 1_000, p)) // just under threshold → resets
        assertNull(c.onFrame(0.9f, 2_000, p))
        assertEquals(1, c.pendingPositives)
    }

    @Test
    fun positivesFurtherApartThanWindowDoNotConfirm() {
        val c = confirmer()
        assertNull(c.onFrame(0.95f, 0, p))
        assertNull("10.001 s apart is outside the 10 s window", c.onFrame(0.95f, 10_001, p))
        // The stale frame was dropped; the newer one still counts.
        assertEquals(1, c.pendingPositives)
        assertNotNull(c.onFrame(0.95f, 17_000, p))
    }

    @Test
    fun positivesExactlyAtWindowEdgeConfirm() {
        val c = confirmer()
        assertNull(c.onFrame(0.95f, 0, p))
        assertNotNull(c.onFrame(0.95f, 10_000, p))
    }

    @Test
    fun periodicCadenceOf7sConfirmsTwoBaselineFramesInARow() {
        // Baseline interval (7 s) fits within the 10 s window, so two consecutive
        // periodic positives confirm without needing fast mode.
        val c = confirmer()
        assertNull(c.onFrame(0.9f, 0, p))
        assertNotNull(c.onFrame(0.9f, ScanConfig.BASELINE_INTERVAL_MS, p))
    }

    @Test
    fun streakRestartsAfterConfirmation() {
        val c = confirmer()
        c.onFrame(0.9f, 0, e)
        assertNotNull(c.onFrame(0.9f, 1_500, e))
        assertEquals(0, c.pendingPositives)
        assertNull("needs a fresh pair after confirming", c.onFrame(0.9f, 3_000, e))
        assertNotNull(c.onFrame(0.9f, 4_500, e))
    }

    @Test
    fun requiredCountIsConfigurable() {
        val c = DetectionConfirmer(threshold = 0.5f, requiredConsecutive = 3, windowMs = 5_000)
        assertNull(c.onFrame(0.6f, 0, e))
        assertNull(c.onFrame(0.6f, 1_000, e))
        assertNotNull(c.onFrame(0.6f, 2_000, e))
    }

    @Test
    fun resetClearsPartialStreak() {
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
        DetectionConfirmer(requiredConsecutive = 0)
    }
}
