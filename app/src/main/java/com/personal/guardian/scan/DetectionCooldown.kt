package com.personal.guardian.scan

/**
 * Suppresses repeat detections of the *same on-screen content*.
 *
 * With a low threshold, content that stays on screen is re-confirmed every couple
 * of frames. Each confirmed detection carries a [ScreenFingerprint] of the frame; if
 * it matches (Hamming distance ≤ [maxDistance]) a detection already *reported*
 * within the last [cooldownMs], it is suppressed. Different content is reported
 * immediately, and the same content is reported again once the cooldown has passed
 * since it was last reported.
 *
 * Pure Kotlin with injected monotonic timestamps; single-threaded use.
 */
class DetectionCooldown(
    val cooldownMs: Long = ScanConfig.DETECTION_COOLDOWN_MS,
    val maxDistance: Int = ScanConfig.SAME_CONTENT_MAX_DISTANCE
) {
    init {
        require(cooldownMs >= 0) { "cooldownMs must be >= 0" }
        require(maxDistance in 0..64) { "maxDistance must be within 0..64" }
    }

    private data class Reported(val atMs: Long, val fingerprint: Long)

    private val recent = ArrayDeque<Reported>()

    /** Repeats suppressed since the last reported detection. */
    var suppressedSinceLastReport = 0
        private set

    /**
     * Returns true if a detection with [fingerprint] at [nowMs] should be reported
     * (and records it), false if it repeats recently reported content.
     */
    fun shouldReport(nowMs: Long, fingerprint: Long): Boolean {
        while (recent.isNotEmpty() && nowMs - recent.first().atMs >= cooldownMs) recent.removeFirst()
        val repeat = recent.any { ScreenFingerprint.distance(it.fingerprint, fingerprint) <= maxDistance }
        if (repeat) {
            suppressedSinceLastReport++
            return false
        }
        recent.addLast(Reported(nowMs, fingerprint))
        return true
    }

    /**
     * Multi-fingerprint variant for text detections (Stage 4, one fingerprint per
     * matched term): reports if *any* fingerprint is new — i.e. not reported within
     * the cooldown — and records the new ones. If all are repeats it counts one
     * suppressed detection.
     */
    fun shouldReportAny(nowMs: Long, fingerprints: Collection<Long>): Boolean {
        while (recent.isNotEmpty() && nowMs - recent.first().atMs >= cooldownMs) recent.removeFirst()
        val fresh = fingerprints.distinct().filter { fp ->
            recent.none { ScreenFingerprint.distance(it.fingerprint, fp) <= maxDistance }
        }
        if (fresh.isEmpty()) {
            suppressedSinceLastReport++
            return false
        }
        fresh.forEach { recent.addLast(Reported(nowMs, it)) }
        return true
    }

    /** Call after logging a reported detection, to restart the suppressed count. */
    fun resetSuppressedCount() {
        suppressedSinceLastReport = 0
    }
}

/**
 * 64-bit difference hash ("dHash") of a frame: shrink to 9×8, grayscale, and set one
 * bit per horizontally adjacent pair (left brighter than right). Near-identical
 * screens (same image, clock ticked, small scroll) differ in few bits; different
 * content differs in many. Pure Kotlin; the caller does the 9×8 bitmap scale.
 */
object ScreenFingerprint {

    const val WIDTH = 9
    const val HEIGHT = 8

    /** [pixels]: ARGB ints, [WIDTH]×[HEIGHT], row-major (as from `Bitmap.getPixels`). */
    fun dHash(pixels: IntArray): Long {
        require(pixels.size == WIDTH * HEIGHT) { "expected ${WIDTH}x$HEIGHT pixels" }
        var hash = 0L
        var bit = 0
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH - 1) {
                if (luma(pixels[y * WIDTH + x]) > luma(pixels[y * WIDTH + x + 1])) {
                    hash = hash or (1L shl bit)
                }
                bit++
            }
        }
        return hash
    }

    /** Number of differing bits (0 = identical, 64 = opposite). */
    fun distance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    private fun luma(p: Int): Int {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}
