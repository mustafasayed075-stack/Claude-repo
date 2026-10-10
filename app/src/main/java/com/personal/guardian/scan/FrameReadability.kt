package com.personal.guardian.scan

/**
 * Whether a whole screenshot is "readable" for the blind-spot path, judged on the
 * **content area** so a thin bright status bar or gesture/nav bar can't make an
 * otherwise-black screen look readable (README "Stage 5 — blind-spot escalation").
 *
 * Some apps that block screenshots (WhatsApp view-once, Telegram's no-screenshot mode)
 * don't always fail the capture — on some devices the protected media renders **black**
 * while the status bar and header stay drawn. [RegionContent.assess] over the whole
 * frame can then read OK (the bright chrome lifts the average), so we also check the
 * fraction of near-black pixels in the content band (the rows between the top and bottom
 * chrome). Pure Kotlin on a small luma grid, so it is unit-tested.
 */
object FrameReadability {

    /** A pixel counts as black at or below this luma (0..255). */
    const val BLACK_LUMA = 16

    /** Grid rows skipped top and bottom as status / nav / gesture chrome. */
    const val MARGIN_ROWS = 3

    /** The content band is "mostly black" at or above this fraction of black pixels. */
    const val MOSTLY_BLACK_FRACTION = 0.85

    /**
     * Fraction (0..1) of near-black pixels in the content band of an ARGB [w]×[h] grid
     * (row-major), skipping [marginRows] rows at the top and bottom (the chrome).
     */
    fun contentBlackFraction(argb: IntArray, w: Int, h: Int, marginRows: Int = MARGIN_ROWS): Double {
        require(argb.size == w * h) { "argb must be $w*$h" }
        val m = marginRows.coerceIn(0, (h - 1) / 2)
        val top = m
        val bottom = h - m
        var black = 0
        var total = 0
        for (r in top until bottom) {
            for (c in 0 until w) {
                val p = argb[r * w + c]
                val lum = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                if (lum <= BLACK_LUMA) black++
                total++
            }
        }
        return if (total == 0) 0.0 else black.toDouble() / total
    }

    fun isContentMostlyBlack(argb: IntArray, w: Int, h: Int): Boolean =
        contentBlackFraction(argb, w, h) >= MOSTLY_BLACK_FRACTION
}
