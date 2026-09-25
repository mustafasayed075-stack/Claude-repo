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
