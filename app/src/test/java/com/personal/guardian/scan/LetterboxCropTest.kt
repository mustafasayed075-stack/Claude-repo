package com.personal.guardian.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM tests for [LetterboxCrop] on synthetic luma grids that mimic the screens
 * the crop has to tell apart: an in-app media viewer with black bands + chrome, an
 * ordinary chat, an all-black screen, and a dark night photo in the viewer.
 */
class LetterboxCropTest {

    private val grid = RegionContent.GRID // 48

    /** A [grid]×[grid] luma grid; [cell] gives 0..255 for (row, col). */
    private fun lumaGrid(cell: (r: Int, c: Int) -> Int): IntArray =
        IntArray(grid * grid) { i -> cell(i / grid, i % grid).coerceIn(0, 255) }

    /** A row where only [count] cells (spread out) are lit — sparse text/icons, never "rich". */
    private fun sparseRow(r: Int, c: Int, count: Int): Int =
        if (c % (grid / count) == 0) 200 else 0

    // (1) Telegram-style viewer: status/header text, black gap, full-width image, gap,
    //     thumbnail strip. Expected: the rect is the image band only.
    @Test
    fun telegramViewerCropsToTheImageBandOnly() {
        val luma = lumaGrid { r, c ->
            when (r) {
                in 0..5 -> sparseRow(r, c, 6)          // status bar + "4 of 13" header: sparse text
                in 6..9 -> 0                            // black gap
                in 10..37 -> 30 + ((r * 7 + c * 5) % 200) // full-width image: dense, varied
                in 38..40 -> 0                          // black gap
                else -> 40 + ((r * 3 + c * 9) % 180)    // rows 41..47: thumbnail strip (shorter)
            }
        }

        val rect = LetterboxCrop.contentRect(luma, grid, grid)
        assertNotNull("the image band should be found", rect)
        rect!!
        assertEquals("top of the image band", 10, rect.top)
        assertEquals("height of the image band", 28, rect.height)
        assertEquals("image spans the full width", 0, rect.left)
        assertEquals("image spans the full width", grid, rect.width)
    }

    // (2) Ordinary chat: light background with text everywhere, no black bands.
    //     Expected: null (nothing to trim → classify the whole frame).
    @Test
    fun normalChatScreenIsNotCropped() {
        val luma = lumaGrid { r, c ->
            // Light background (~210) with evenly scattered darker text: rich on every row,
            // so the band fills the screen and the trim is trivial.
            if ((r * 5 + c) % 4 == 0) 40 else 210
        }
        assertNull("no black bands → no crop", LetterboxCrop.contentRect(luma, grid, grid))
    }

    // (3) Fully black screen. Expected: null (no rich rows at all).
    @Test
    fun allBlackScreenIsNotCropped() {
        assertNull(LetterboxCrop.contentRect(lumaGrid { _, _ -> 0 }, grid, grid))
    }

    // (4) Dark "night" photo in the viewer: low luma but above pure black, with real
    //     variety, between black bands. Expected: STILL cropped to the image band —
    //     the non-black bar is luma 16, well under any visible content, so a dark photo
    //     is detected; only a photo as black as the bars themselves would be missed
    //     (and then classifying the whole frame is harmless, since there's no dilution).
    @Test
    fun darkNightImageInViewerIsStillCropped() {
        val luma = lumaGrid { r, c ->
            when (r) {
                in 0..5 -> sparseRow(r, c, 6) // header text
                in 6..9 -> 0                   // black gap
                in 10..37 -> 18 + ((r + c) % 3) * 20 // dark photo: 18/38/58, varied, all > 16
                else -> 0                      // black below
            }
        }

        val rect = LetterboxCrop.contentRect(luma, grid, grid)
        assertNotNull("a dark but visible photo is still found", rect)
        rect!!
        assertEquals(10, rect.top)
        assertEquals(28, rect.height)
        assertEquals(grid, rect.width)
    }
}
