package com.personal.guardian.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the same-content cooldown and the screen fingerprint.
 */
class DetectionCooldownTest {

    private val a = 0x0F0F_0F0F_0F0F_0F0FL
    private val b = a.inv() // 64 bits different: clearly different content

    /** [base] with its lowest [n] bits flipped (distance exactly n). */
    private fun near(base: Long, n: Int) = base xor ((1L shl n) - 1)

    @Test
    fun defaultsComeFromScanConfig() {
        val c = DetectionCooldown()
        assertEquals(60_000L, c.cooldownMs)
        assertEquals(10, c.maxDistance)
    }

    @Test
    fun sameContentWithinCooldownIsSuppressed() {
        val c = DetectionCooldown(cooldownMs = 60_000, maxDistance = 10)
        assertTrue(c.shouldReport(0, a))
        assertFalse(c.shouldReport(3_000, a))
        assertFalse("clock tick / small scroll", c.shouldReport(6_000, near(a, 10)))
        assertEquals(2, c.suppressedSinceLastReport)
    }

    @Test
    fun differentContentIsReportedImmediately() {
        val c = DetectionCooldown(cooldownMs = 60_000, maxDistance = 10)
        assertTrue(c.shouldReport(0, a))
        assertTrue(c.shouldReport(1_500, b))
        assertTrue("11 bits apart is different content", c.shouldReport(3_000, near(a, 11)))
    }

    @Test
    fun sameContentIsReportedAgainOnceCooldownHasPassed() {
        val c = DetectionCooldown(cooldownMs = 60_000, maxDistance = 10)
        assertTrue(c.shouldReport(0, a))
        assertFalse(c.shouldReport(59_999, a))
        assertTrue(c.shouldReport(60_000, a))
        assertFalse(c.shouldReport(61_000, a))
    }

    @Test
    fun remembersEveryRecentlyReportedContentNotJustTheLast() {
        val c = DetectionCooldown(cooldownMs = 60_000, maxDistance = 10)
        assertTrue(c.shouldReport(0, a))
        assertTrue(c.shouldReport(5_000, b))
        assertFalse("A again, still within its cooldown", c.shouldReport(10_000, a))
    }

    @Test
    fun suppressedCountResets() {
        val c = DetectionCooldown(cooldownMs = 60_000, maxDistance = 10)
        c.shouldReport(0, a)
        c.shouldReport(1, a)
        assertEquals(1, c.suppressedSinceLastReport)
        c.resetSuppressedCount()
        assertEquals(0, c.suppressedSinceLastReport)
    }

    /**
     * With the shipped config (threshold 0.2, 2 frames/10 s, 60 s cooldown): 2 min of
     * the same image in fast mode confirms 40 times but is reported only twice.
     */
    @Test
    fun withShippedConfigContinuousSameContentIsReportedOncePerMinute() {
        val confirmer = DetectionConfirmer()
        val cooldown = DetectionCooldown()
        var t = 0L
        var confirmed = 0
        val reportedAt = mutableListOf<Long>()
        while (t < 120_000) {
            if (confirmer.onFrame(0.35f, t, TriggerSource.EVENT) != null) {
                confirmed++
                if (cooldown.shouldReport(t, a)) reportedAt += t
            }
            t += ScanConfig.FAST_INTERVAL_MS
        }
        assertEquals(40, confirmed)
        assertEquals(listOf(1_500L, 61_500L), reportedAt)
    }

    // ---- ScreenFingerprint ----

    private fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    private fun image(f: (x: Int, y: Int) -> Int) =
        IntArray(ScreenFingerprint.WIDTH * ScreenFingerprint.HEIGHT) { i ->
            gray(f(i % ScreenFingerprint.WIDTH, i / ScreenFingerprint.WIDTH))
        }

    @Test
    fun identicalFramesHaveDistanceZero() {
        val img = image { x, y -> (x * 23 + y * 7) % 256 }
        assertEquals(0, ScreenFingerprint.distance(ScreenFingerprint.dHash(img), ScreenFingerprint.dHash(img.copyOf())))
    }

    @Test
    fun oppositeGradientsAreMaximallyDifferent() {
        val darkening = ScreenFingerprint.dHash(image { x, _ -> 250 - x * 20 })
        val brightening = ScreenFingerprint.dHash(image { x, _ -> 10 + x * 20 })
        assertEquals(-1L, darkening) // every left pixel brighter than its right neighbour
        assertEquals(0L, brightening)
        assertEquals(64, ScreenFingerprint.distance(darkening, brightening))
    }

    @Test
    fun smallLocalChangeIsWithinSameContentDistance() {
        val base = image { x, y -> (x * 37 + y * 53) % 256 }
        val tweaked = base.copyOf().also { it[0] = gray(255) } // e.g. status-bar clock
        val d = ScreenFingerprint.distance(ScreenFingerprint.dHash(base), ScreenFingerprint.dHash(tweaked))
        assertTrue("distance $d", d <= ScanConfig.SAME_CONTENT_MAX_DISTANCE)
    }
}
