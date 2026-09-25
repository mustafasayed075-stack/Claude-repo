package com.personal.guardian.scan

/**
 * Turns a stream of per-frame classifier scores into confirmed detections.
 *
 * Rules (spec §4):
 *  - A frame is *positive* when its score is at or above [threshold].
 *  - A confirmed detection needs [requiredConsecutive] positive frames in a row, all
 *    within [windowMs] of each other. A negative frame resets the streak, and
 *    positives that have fallen out of the window no longer count.
 *  - A single flagged frame never confirms anything by itself (unless the count is
 *    configured as 1).
 *  - After a confirmation the streak starts over, so continuous content produces one
 *    confirmation per [requiredConsecutive] frames rather than one per frame.
 *
 * Pure Kotlin with an injected timestamp, so it is unit-testable without Android.
 * Not thread-safe: call it from a single thread (the scanner's worker thread).
 */
class DetectionConfirmer(
    val threshold: Float = ScanConfig.NSFW_THRESHOLD,
    val requiredConsecutive: Int = ScanConfig.CONFIRMATION_COUNT,
    val windowMs: Long = ScanConfig.CONFIRMATION_WINDOW_MS
) {
    init {
        require(threshold in 0f..1f) { "threshold must be within 0..1, was $threshold" }
        require(requiredConsecutive >= 1) { "requiredConsecutive must be >= 1" }
        require(windowMs > 0) { "windowMs must be > 0" }
    }

    /** One positive frame in the current streak. */
    data class Frame(val atMs: Long, val score: Float, val source: TriggerSource)

    /** Result of a confirmation: the frames that satisfied the rule, oldest first. */
    data class Confirmation(val frames: List<Frame>) {
        /** The frame that completed the streak (most recent). */
        val latest: Frame get() = frames.last()
    }

    private val streak = ArrayDeque<Frame>()

    /** Number of positives currently counting towards a confirmation. */
    val pendingPositives: Int get() = streak.size

    /** Threshold rule on its own, so it can be tested and reused independently. */
    fun isPositive(score: Float): Boolean = score >= threshold

    /**
     * Feeds one classified frame. [atMs] must come from a monotonic clock (e.g.
     * `SystemClock.elapsedRealtime()`). Returns a [Confirmation] when this frame
     * completes the rule, otherwise null.
     */
    fun onFrame(score: Float, atMs: Long, source: TriggerSource): Confirmation? {
        if (!isPositive(score)) {
            streak.clear()
            return null
        }
        streak.addLast(Frame(atMs, score, source))
        while (streak.isNotEmpty() && atMs - streak.first().atMs > windowMs) {
            streak.removeFirst()
        }
        if (streak.size < requiredConsecutive) return null

        val confirmation = Confirmation(streak.toList())
        streak.clear()
        return confirmation
    }

    /** Forgets any partial streak (e.g. when the screen turns off). */
    fun reset() = streak.clear()
}
