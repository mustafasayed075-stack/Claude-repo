package com.personal.guardian.scan

/**
 * Finds the real content rectangle inside a letterboxed frame — the large image an
 * in-app media viewer shows on black bands, with a status bar / header / thumbnail
 * strip around it (README "Screen scanning → letterbox crop"). Cropping to it before
 * whole-screen classification stops the black bands and UI chrome from diluting the
 * signal.
 *
 * It does **not** trim uniform rows from the edges (that would stop at the first
 * header row with text). Instead it keeps the **largest contiguous band** of *rich*
 * rows — rows where at least [RICH_FRACTION] of the width is non-black and the row
 * has real variety — then, within that band, the largest contiguous band of rich
 * columns. Sparse text/icons in a header don't make a row rich; a short thumbnail
 * strip loses to the taller image band.
 *
 * Pure: it works on a small luma grid, so the geometry is unit-tested. Returns null
 * when there's nothing worth cropping (no bands, too small, or the crop would be
 * trivial) — then the caller classifies the whole frame unchanged.
 */
object LetterboxCrop {

    /** A cell counts as non-black above this luma (0..255). */
    const val BLACK_LUMA = 16

    /** A rich line's lightest and darkest cells must differ by at least this. */
    const val VARIETY_TOL = 40

    /** A line is rich when at least this fraction of its cells are non-black. */
    const val RICH_FRACTION = 0.5

    /** The content rect must be at least this fraction of each dimension, else no crop. */
    const val MIN_CONTENT_FRACTION = 0.35

    /** Crop only if it removes at least this fraction of one axis, else classify whole. */
    const val MIN_TRIM_FRACTION = 0.06

    /** Content rect in grid cells: [left]..[left]+[width]-1, [top]..[top]+[height]-1. */
    data class GridRect(val left: Int, val top: Int, val width: Int, val height: Int)

    /**
     * [luma] is a row-major [w]×[h] grid of 0..255 luma. Returns the content rect in
     * grid coords, or null to classify the whole frame.
     */
    fun contentRect(luma: IntArray, w: Int, h: Int): GridRect? {
        require(luma.size == w * h) { "luma must be $w*$h" }
        if (w < 3 || h < 3) return null

        val richRow = BooleanArray(h) { r -> richRow(luma, w, r, 0, w) }
        val rows = longestRun(richRow) ?: return null
        val (top, bottom) = rows // inclusive

        val richCol = BooleanArray(w) { c -> richColumn(luma, w, c, top, bottom) }
        val cols = longestRun(richCol) ?: return null
        val (left, right) = cols

        val rectW = right - left + 1
        val rectH = bottom - top + 1
        // Too small to trust as "the content".
        if (rectW < MIN_CONTENT_FRACTION * w || rectH < MIN_CONTENT_FRACTION * h) return null
        // Nothing meaningful trimmed on either axis → classify the whole frame.
        val trimmedW = (w - rectW).toDouble() / w
        val trimmedH = (h - rectH).toDouble() / h
        if (trimmedW < MIN_TRIM_FRACTION && trimmedH < MIN_TRIM_FRACTION) return null

        return GridRect(left, top, rectW, rectH)
    }

    /** A row of cells `r`, over columns [from, until). */
    private fun richRow(luma: IntArray, w: Int, r: Int, from: Int, until: Int): Boolean {
        var nonBlack = 0
        var min = 255
        var max = 0
        for (c in from until until) {
            val v = luma[r * w + c]
            if (v > BLACK_LUMA) nonBlack++
            if (v < min) min = v
            if (v > max) max = v
        }
        val width = until - from
        return nonBlack >= RICH_FRACTION * width && (max - min) >= VARIETY_TOL
    }

    /** A column `c`, over rows [top, bottom] inclusive. */
    private fun richColumn(luma: IntArray, w: Int, c: Int, top: Int, bottom: Int): Boolean {
        var nonBlack = 0
        var min = 255
        var max = 0
        for (r in top..bottom) {
            val v = luma[r * w + c]
            if (v > BLACK_LUMA) nonBlack++
            if (v < min) min = v
            if (v > max) max = v
        }
        val height = bottom - top + 1
        return nonBlack >= RICH_FRACTION * height && (max - min) >= VARIETY_TOL
    }

    /** Longest contiguous run of true in [flags]; (start, endInclusive) or null if none. */
    private fun longestRun(flags: BooleanArray): Pair<Int, Int>? {
        var bestStart = -1
        var bestLen = 0
        var curStart = -1
        for (i in flags.indices) {
            if (flags[i]) {
                if (curStart < 0) curStart = i
                val len = i - curStart + 1
                if (len > bestLen) {
                    bestLen = len
                    bestStart = curStart
                }
            } else {
                curStart = -1
            }
        }
        return if (bestStart < 0) null else bestStart to (bestStart + bestLen - 1)
    }
}
