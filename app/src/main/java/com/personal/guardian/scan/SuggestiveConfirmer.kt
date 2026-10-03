package com.personal.guardian.scan

/**
 * The suggestive tier (README "Screen scanning → suggestive tier").
 *
 * Explicit content locks on the first confirmed detection ([DetectionConfirmer], high
 * thresholds). Suggestive (non-explicit) content sits below that: a single suggestive
 * frame must **not** lock — it could be an incidental pose, a swimwear ad, a scroll
 * passing through — but [requiredPositives] suggestive frames within [windowMs] are
 * deliberate enough to act on.
 *
 * Rule: a lock is confirmed when at least [requiredPositives] suggestive frames fall
 * within the last [windowMs]. Negative frames don't count and don't reset anything;
 * positives age out once they are more than [windowMs] older than the newest frame.
 * After a confirmation the collected positives are cleared.
 *
 * This counter is fed **every** frame's suggestive judgement directly, before and
 * independent of the same-content cooldown ([DetectionCooldown]) that gates explicit
 * reporting. So two frames of the *same* suggestive image still confirm, even though
 * the cooldown would suppress a repeat report of it.
 *
 * Pure Kotlin with an injected timestamp; single-threaded use (scanner worker thread).
 */
class SuggestiveConfirmer(
    val requiredPositives: Int = ScanConfig.SUGGESTIVE_COUNT,
    val windowMs: Long = ScanConfig.SUGGESTIVE_WINDOW_MS
) {
    init {
        require(requiredPositives >= 1) { "requiredPositives must be >= 1" }
        require(windowMs > 0) { "windowMs must be > 0" }
    }

    private val positives = ArrayDeque<Long>()

    /** Suggestive frames currently inside the window, counting towards a lock. */
    val pendingPositives: Int get() = positives.size

    /**
     * Feeds one frame. [atMs] must come from a monotonic clock (e.g.
     * `SystemClock.elapsedRealtime()`). [suggestive] is whether the frame reached the
     * suggestive tier — the caller judges the signal against the current sensitivity
     * threshold ([ScanSensitivity]). Returns true when this frame completes the rule,
     * otherwise false.
     */
    fun onFrame(atMs: Long, suggestive: Boolean): Boolean {
        while (positives.isNotEmpty() && atMs - positives.first() > windowMs) positives.removeFirst()
        if (!suggestive) return false
        positives.addLast(atMs)
        if (positives.size < requiredPositives) return false
        positives.clear()
        return true
    }

    /** Forgets any positives collected so far (e.g. screen off, or an app switch). */
    fun reset() = positives.clear()
}
