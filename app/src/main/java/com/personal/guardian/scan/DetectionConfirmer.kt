package com.personal.guardian.scan

/**
 * Turns a stream of per-frame classifier scores into confirmed detections.
 *
 * Rule: a detection is confirmed when **at least [requiredPositives] frames scoring
 * >= [threshold] fall within the last [windowMs]** — whether or not negative frames
 * occurred between them. (An earlier version required the positives to be strictly
 * consecutive; a single dip below threshold reset it. That no longer happens.)
 *
 *  - A frame is *positive* when its score is at or above [threshold].
 *  - Negative frames don't count and don't reset anything; positives simply age out
 *    once they are more than [windowMs] older than the newest frame.
 *  - A single flagged frame never confirms anything by itself (unless the count is
 *    configured as 1).
 *  - After a confirmation the collected positives are cleared, so content that stays
 *    on screen produces one confirmation per [requiredPositives] positive frames
 *    rather than one per frame.
 *
 * Pure Kotlin with an injected timestamp, so it is unit-testable without Android.
 * Not thread-safe: call it from a single thread (the scanner's worker thread).
 */
class DetectionConfirmer(
    val threshold: Float = ScanConfig.NSFW_THRESHOLD,
    val requiredPositives: Int = ScanConfig.CONFIRMATION_COUNT,
    val windowMs: Long = ScanConfig.CONFIRMATION_WINDOW_MS
) {
    init {
        require(threshold in 0f..1f) { "threshold must be within 0..1, was $threshold" }
        require(requiredPositives >= 1) { "requiredPositives must be >= 1" }
        require(windowMs > 0) { "windowMs must be > 0" }
    }

    /** One positive frame inside the current window. */
    data class Frame(val atMs: Long, val score: Float, val source: TriggerSource)

    /** Result of a confirmation: the positive frames that satisfied the rule, oldest first. */
    data class Confirmation(val frames: List<Frame>) {
        /** The frame that completed the rule (most recent). */
        val latest: Frame get() = frames.last()
    }

    private val positives = ArrayDeque<Frame>()

    /** Number of positives currently inside the window, counting towards a confirmation. */
    val pendingPositives: Int get() = positives.size

    /** Threshold rule on its own, so it can be tested and reused independently. */
    fun isPositive(score: Float): Boolean = score >= threshold

    /**
     * Feeds one classified frame. [atMs] must come from a monotonic clock (e.g.
     * `SystemClock.elapsedRealtime()`). Returns a [Confirmation] when this frame
     * completes the rule, otherwise null. [positive] defaults to the [threshold] rule;
     * callers that judged the frame themselves (a region over its own threshold, see
     * [FrameVerdict]) pass it explicitly.
     */
    fun onFrame(score: Float, atMs: Long, source: TriggerSource, positive: Boolean = isPositive(score)): Confirmation? {
        // Age out positives older than the window, relative to this frame.
        while (positives.isNotEmpty() && atMs - positives.first().atMs > windowMs) {
            positives.removeFirst()
        }
        if (!positive) return null

        positives.addLast(Frame(atMs, score, source))
        if (positives.size < requiredPositives) return null

        val confirmation = Confirmation(positives.toList())
        positives.clear()
        return confirmation
    }

    /** Forgets any positives collected so far (e.g. when the screen turns off). */
    fun reset() = positives.clear()
}
