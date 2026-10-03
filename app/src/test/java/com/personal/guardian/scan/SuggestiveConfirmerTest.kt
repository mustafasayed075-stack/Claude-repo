package com.personal.guardian.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the suggestive tier's 2-within-window confirmation. */
class SuggestiveConfirmerTest {

    private val window = 3_000L
    private fun confirmer() = SuggestiveConfirmer(requiredPositives = 2, windowMs = window)

    @Test
    fun oneSuggestiveFrameAloneDoesNotConfirm() {
        val c = confirmer()
        assertFalse(c.onFrame(1_000, suggestive = true))
        assertEquals(1, c.pendingPositives)
    }

    @Test
    fun twoSuggestiveFramesWithinTheWindowConfirm() {
        val c = confirmer()
        assertFalse(c.onFrame(1_000, suggestive = true))
        assertTrue(c.onFrame(1_000 + window, suggestive = true)) // exactly on the window edge
        // Cleared after confirming: it takes two fresh frames again.
        assertEquals(0, c.pendingPositives)
        assertFalse(c.onFrame(10_000, suggestive = true))
    }

    @Test
    fun theFirstPositiveAgesOutSoTwoTooFarApartDoNotConfirm() {
        val c = confirmer()
        assertFalse(c.onFrame(1_000, suggestive = true))
        // > window after the first: the first is dropped, this is only the 1st again.
        assertFalse(c.onFrame(1_000 + window + 1, suggestive = true))
        assertEquals(1, c.pendingPositives)
    }

    @Test
    fun nonSuggestiveFramesDoNotCountAndDoNotReset() {
        val c = confirmer()
        assertFalse(c.onFrame(1_000, suggestive = true))
        assertFalse(c.onFrame(1_500, suggestive = false)) // ignored, keeps the earlier positive
        assertEquals(1, c.pendingPositives)
        assertTrue(c.onFrame(2_000, suggestive = true))
    }

    @Test
    fun resetForgetsCollectedPositives() {
        val c = confirmer()
        c.onFrame(1_000, suggestive = true)
        c.reset()
        assertEquals(0, c.pendingPositives)
        assertFalse(c.onFrame(1_200, suggestive = true))
    }

    @Test
    fun defaultsComeFromScanConfig() {
        val c = SuggestiveConfirmer()
        assertEquals(ScanConfig.SUGGESTIVE_COUNT, c.requiredPositives)
        assertEquals(ScanConfig.SUGGESTIVE_WINDOW_MS, c.windowMs)
    }
}
