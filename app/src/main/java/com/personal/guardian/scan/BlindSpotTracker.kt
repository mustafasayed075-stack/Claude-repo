package com.personal.guardian.scan

/**
 * The blind-spot path per capture: the consecutive-unreadable guardrail, the "is the app
 * in front on the blind-spot list?" gate, and the escalating [BlindSpotPolicy] behind
 * them. Each call updates the state first and then reports what happened, so a log line
 * written afterwards shows the state *after* this capture (it used to be written before
 * the policy saw a readable frame, so it could read `isBlind=yes consec=0`).
 *
 * Pure Kotlin; single-threaded use (scanner worker thread).
 */
class BlindSpotTracker(
    private val policy: BlindSpotPolicy,
    val confirmFrames: Int = ScanConfig.BLIND_SPOT_CONFIRM_FRAMES
) {
    init {
        require(confirmFrames >= 1) { "confirmFrames must be >= 1" }
    }

    /** Unreadable captures in a row (any app); a readable one or screen-off clears it. */
    var consecutiveUnreadable = 0
        private set

    val level: Int get() = policy.level
    val isBlind: Boolean get() = policy.isBlind
    fun nextGraceMs(): Long = policy.graceForNextEntryMs()

    /** A readable capture. */
    data class Readable(
        /** A blind episode (and its grace) ended because the screen became readable. */
        val graceCancelled: Boolean,
        /** An hour of continuous visibility reset the escalation to its first step. */
        val levelReset: Boolean
    )

    /** An unreadable capture. */
    data class Unreadable(
        /** What the policy decided, or null if the capture didn't reach the policy. */
        val outcome: BlindSpotPolicy.Outcome?,
        /** Why the capture didn't reach the policy (null when it did). */
        val skipReason: String?
    )

    fun onReadable(): Readable {
        consecutiveUnreadable = 0
        val o = policy.onVisible()
        return Readable(
            graceCancelled = o is BlindSpotPolicy.Outcome.EpisodeEnded,
            levelReset = o is BlindSpotPolicy.Outcome.LevelReset
        )
    }

    /** [listed]: the app in front is on the blind-spot list. */
    fun onUnreadable(listed: Boolean): Unreadable {
        consecutiveUnreadable++
        if (!listed) return Unreadable(null, SKIP_NOT_LISTED)
        if (consecutiveUnreadable < confirmFrames) {
            return Unreadable(null, "waiting for $confirmFrames consecutive unreadable captures ($consecutiveUnreadable/$confirmFrames)")
        }
        return Unreadable(policy.onBlind(), null)
    }

    /** The screen went off: the guardrail starts over (the escalation level is kept). */
    fun onScreenOff() {
        consecutiveUnreadable = 0
    }

    /** Back to the first step (60 s), out of any blind episode (developer "reset"). */
    fun reset() {
        policy.reset()
        consecutiveUnreadable = 0
    }

    companion object {
        const val SKIP_NOT_LISTED = "app in front is not on the blind-spot list"
    }
}
