package com.personal.guardian.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the content-band black-fraction readability check. */
class FrameReadabilityTest {

    private val grid = 48
    private fun gray(l: Int): Int = (0xFF shl 24) or (l shl 16) or (l shl 8) or l

    /** 48×48 ARGB grid; [cell] returns luma 0..255 for (row, col). */
    private fun argb(cell: (r: Int, c: Int) -> Int): IntArray =
        IntArray(grid * grid) { i -> gray(cell(i / grid, i % grid).coerceIn(0, 255)) }

    @Test
    fun fullyBlackScreenIsMostlyBlack() {
        val g = argb { _, _ -> 0 }
        assertEquals(1.0, FrameReadability.contentBlackFraction(g, grid, grid), 0.001)
        assertTrue(FrameReadability.isContentMostlyBlack(g, grid, grid))
    }

    @Test
    fun blackContentUnderBrightBarsIsStillMostlyBlack() {
        // Rows 0-2 and 45-47 are a bright status bar / nav bar; the content (3..44) is black.
        val g = argb { r, _ -> if (r < 3 || r >= grid - 3) 220 else 0 }
        // The whole-frame average would be lifted by the bars, but the content band is black.
        assertTrue(FrameReadability.isContentMostlyBlack(g, grid, grid))
    }

    @Test
    fun aBrightScreenIsNotMostlyBlack() {
        val g = argb { _, _ -> 210 }
        assertEquals(0.0, FrameReadability.contentBlackFraction(g, grid, grid), 0.001)
        assertFalse(FrameReadability.isContentMostlyBlack(g, grid, grid))
    }

    @Test
    fun aDarkThemeScreenAboveBlackIsNotMostlyBlack() {
        // Dark grey (luma 30) is above BLACK_LUMA (16), so a dark-mode chat is not "black".
        val g = argb { _, _ -> 30 }
        assertFalse(FrameReadability.isContentMostlyBlack(g, grid, grid))
    }

    @Test
    fun aMostlyBlackPhotoWithSomeContentIsBelowThreshold() {
        // ~30% of the content lit → 70% black, under the 85% threshold.
        val g = argb { _, c -> if (c < 14) 180 else 0 }
        assertFalse(FrameReadability.isContentMostlyBlack(g, grid, grid))
    }
}
