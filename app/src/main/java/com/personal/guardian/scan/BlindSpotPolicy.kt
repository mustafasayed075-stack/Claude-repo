package com.personal.guardian.scan

/**
 * Escalating grace for "blind spots" — stretches of time Guardian can't read the
 * screen (a screenshot-protected / secure window, incognito, etc.), which a user
 * could hide in (README "Stage 5 — blind-spot escalation").
 *
 * Each time the user *enters* a blind state, Guardian waits a grace period before it
 * locks; that grace shrinks on each repeat so the trick stops working:
 *  1st entry → 60 s, 2nd → 30 s, 3rd → 10 s, 4th and after → lock immediately.
 * If the user stays in the blind state past the grace, the lock fires.
 *
 * The escalation resets to the start (60 s again) once the screen has been
 * **continuously readable for a full hour** ([resetAfterVisibleMs]).
 *
 * Pure Kotlin with an injected monotonic clock, so every path is unit-tested. The
 * service drives it from its capture loop: a readable frame → [onVisible], a
 * secure/unreadable frame → [onBlind]; it acts on [Outcome.Act].
 */
class BlindSpotPolicy(
    private val clock: () -> Long,
    private val graceScheduleMs: List<Long> = ScanConfig.BLIND_SPOT_GRACE_MS.toList(),
    private val resetAfterVisibleMs: Long = ScanConfig.BLIND_SPOT_RESET_AFTER_VISIBLE_MS
) {
    sealed class Outcome {
        object None : Outcome()
        /** Entered a blind state; wait this long before acting (0 = act now). */
        data class GraceStarted(val graceMs: Long, val episode: Int) : Outcome()
        /** Still blind and the grace has elapsed — lock now. */
        data class Act(val episode: Int) : Outcome()
        /** Still blind, grace not elapsed yet. */
        object Waiting : Outcome()
        /** The blind state ended (screen readable again). */
        object EpisodeEnded : Outcome()
        /** An hour of continuous visibility passed — escalation reset to the start. */
        object LevelReset : Outcome()
    }

    /** How many blind episodes have started since the last reset. */
    var level = 0
        private set

    private var blind = false
    private var acted = false
    private var deadlineMs = 0L
    private var visibleSinceMs: Long? = null

    val isBlind: Boolean get() = blind

    /** Grace the next *new* blind entry would get (0 once the schedule is exhausted). */
    fun graceForNextEntryMs(): Long = graceScheduleMs.getOrElse(level) { 0L }

    /** A frame Guardian could read. Ends any blind episode and drives the 1-hour reset. */
    fun onVisible(nowMs: Long = clock()): Outcome {
        if (blind) {
            blind = false
            acted = false
            visibleSinceMs = nowMs
            return Outcome.EpisodeEnded
        }
        val since = visibleSinceMs
        if (since == null) {
            visibleSinceMs = nowMs
        } else if (level > 0 && nowMs - since >= resetAfterVisibleMs) {
            level = 0
            visibleSinceMs = nowMs
            return Outcome.LevelReset
        }
        return Outcome.None
    }

    /** A frame Guardian could not read (secure window etc.). */
    fun onBlind(nowMs: Long = clock()): Outcome {
        if (!blind) {
            // New entry into a blind state: consume this level's grace.
            val grace = graceForNextEntryMs()
            level++
            blind = true
            visibleSinceMs = null
            deadlineMs = nowMs + grace
            return if (grace <= 0L) {
                acted = true
                Outcome.Act(level)
            } else {
                acted = false
                Outcome.GraceStarted(grace, level)
            }
        }
        // Already blind: act once the grace has elapsed.
        if (!acted && nowMs >= deadlineMs) {
            acted = true
            return Outcome.Act(level)
        }
        return Outcome.Waiting
    }
}
