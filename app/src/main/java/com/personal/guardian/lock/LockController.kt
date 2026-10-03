package com.personal.guardian.lock

import com.personal.guardian.scan.DetectionEvent
import com.personal.guardian.scan.DetectionKind
import com.personal.guardian.scan.ScanConfig
import com.personal.guardian.text.KeywordTier
import java.util.Locale

/** The device lock as the controller sees it; the app's adapter is [DevicePolicyLock]. */
interface DeviceLock {
    /** True if Guardian is an active device admin (needed for [lockNow]). */
    fun isAdminActive(): Boolean

    /** Locks the screen now; throws if the platform refuses. */
    fun lockNow()
}

/** Where the controller's log lines go (GuardianLog in the app, a list in tests). */
fun interface LockLog {
    fun log(level: Level, message: String)

    enum class Level { INFO, WARN, ERROR }
}

/** What caused a lock; [label] is the `source=` value of the LOCK log line. */
enum class LockSource(val label: String) {
    /** A confirmed image detection. */
    IMAGE("image"),
    /** A text detection with at least one explicit-tier term. */
    TEXT_EXPLICIT("text-explicit"),
    /** A borderline-only text detection that an image check corroborated within the window. */
    TEXT_CORROBORATED("text-borderline-corroborated"),
}

/** What a [LockController] call did, for the caller to act on (timers, captures). */
sealed class LockOutcome {
    object None : LockOutcome()

    /** The device was locked until [untilMs]. */
    data class Locked(val source: LockSource, val detectionId: String, val untilMs: Long) : LockOutcome()

    /** An unlock during the lock period was answered with another lock. */
    data class Relocked(val detectionId: String, val remainingMs: Long) : LockOutcome()

    /** A lock was due but not applied (device admin not active, or the platform refused). */
    data class Skipped(val detectionId: String, val reason: String) : LockOutcome()

    /** A borderline text detection: check the screen's images until [deadlineMs]. */
    data class CorroborationStarted(val detectionId: String, val deadlineMs: Long) : LockOutcome()

    /** The corroboration window expired without a positive image check: no lock. */
    data class Uncorroborated(val detectionId: String) : LockOutcome()

    /** The lock period is over. */
    object LockEnded : LockOutcome()
}

/**
 * Stage 5 — the lock decision (README "Stage 5 — lock"). Pure Kotlin with an
 * injected monotonic clock and [DeviceLock], so every path is unit-tested without a
 * device. Not thread-safe: the service calls it from its worker thread only.
 *
 *  - **Image detection** (confirmed: sexy/porn/hentai over the image thresholds) →
 *    lock now.
 *  - **Text detection, explicit tier** (any explicit-tier term) → lock now.
 *  - **Text detection, borderline tier only** → no lock yet: the device stays usable
 *    while the caller checks the screen's images for up to [corroborationWindowMs]
 *    ([onCorroborationFrame]). The first check over the image thresholds locks; if
 *    the window expires first, there is no lock for that detection.
 *  - A lock lasts [lockDurationMs]. An unlock during it ([onUserPresent]) locks again.
 *  - Device admin not active → the lock is skipped with a warning; detection
 *    logging and notifications (done before the controller sees the event) are
 *    unaffected.
 */
class LockController(
    private val device: DeviceLock,
    private val log: LockLog,
    private val clock: () -> Long,
    val lockDurationMs: Long = ScanConfig.LOCK_DURATION_MS,
    val corroborationWindowMs: Long = ScanConfig.CORROBORATION_WINDOW_MS
) {
    private class Corroboration(val detectionId: String, val terms: List<String>, val startedMs: Long, val deadlineMs: Long) {
        var checks = 0
    }

    private var lockedUntilMs: Long? = null
    private var lockDetectionId: String? = null
    private var relocks = 0
    private var corroboration: Corroboration? = null

    /** Locks applied (first locks, not re-locks) since creation. */
    var lockCount = 0
        private set

    fun isLocked(): Boolean = lockedUntilMs?.let { clock() < it } == true

    /** True while a borderline text detection is waiting for image corroboration. */
    fun isCorroborating(): Boolean = corroboration?.let { clock() < it.deadlineMs } == true

    /** A confirmed detection (new, or a repeat the cooldown kept from the notifications). */
    fun onDetection(event: DetectionEvent): LockOutcome = when (event.kind) {
        DetectionKind.IMAGE -> lock(LockSource.IMAGE, event.id)
        DetectionKind.TEXT -> when (event.textTier ?: KeywordTier.EXPLICIT) {
            KeywordTier.EXPLICIT -> lock(LockSource.TEXT_EXPLICIT, event.id)
            KeywordTier.BORDERLINE -> startCorroboration(event)
        }
    }

    /**
     * One image check made during the corroboration window: [positive] is the
     * frame's verdict against the existing image thresholds, [signal] its score.
     */
    fun onCorroborationFrame(positive: Boolean, signal: Float): LockOutcome {
        val c = corroboration ?: return LockOutcome.None
        val now = clock()
        if (now >= c.deadlineMs) return onCorroborationDeadline()
        c.checks++
        if (!positive) return LockOutcome.None
        log.log(
            LockLog.Level.INFO,
            "Borderline text match corroborated by image check #${c.checks} " +
                "(signal=${String.format(Locale.US, "%.3f", signal)}, ${now - c.startedMs} ms after the match), detectionId=${c.detectionId}."
        )
        return lock(LockSource.TEXT_CORROBORATED, c.detectionId)
    }

    /** Call at (or after) the corroboration deadline. */
    fun onCorroborationDeadline(): LockOutcome {
        val c = corroboration ?: return LockOutcome.None
        if (clock() < c.deadlineMs) return LockOutcome.None
        corroboration = null
        log.log(
            LockLog.Level.INFO,
            "text match uncorroborated, no lock (borderline tier, window expired), detectionId=${c.detectionId}, " +
                "terms=[${c.terms.joinToString()}], window=${corroborationWindowMs}ms, image checks=${c.checks}."
        )
        return LockOutcome.Uncorroborated(c.detectionId)
    }

    /** The user unlocked the device (ACTION_USER_PRESENT). */
    fun onUserPresent(): LockOutcome {
        val until = lockedUntilMs ?: return LockOutcome.None
        val id = lockDetectionId ?: return LockOutcome.None
        val now = clock()
        if (now >= until) return onLockTimer()
        val remaining = until - now
        val failure = apply(id) ?: run {
            relocks++
            log.log(
                LockLog.Level.WARN,
                "LOCK re-applied: unlock ${lockDurationMs - remaining}ms into the lock, remaining=${remaining}ms, detectionId=$id."
            )
            return LockOutcome.Relocked(id, remaining)
        }
        return LockOutcome.Skipped(id, failure)
    }

    /** Call when the lock period should be over; ends it if it is. */
    fun onLockTimer(): LockOutcome {
        val until = lockedUntilMs ?: return LockOutcome.None
        if (clock() < until) return LockOutcome.None
        log.log(
            LockLog.Level.INFO,
            "LOCK period over: duration=${lockDurationMs}ms, detectionId=$lockDetectionId, re-locks after unlock attempts=$relocks."
        )
        lockedUntilMs = null
        lockDetectionId = null
        relocks = 0
        return LockOutcome.LockEnded
    }

    private fun startCorroboration(event: DetectionEvent): LockOutcome {
        val now = clock()
        val previous = corroboration
        val c = Corroboration(event.id, event.matchedTerms, now, now + corroborationWindowMs)
        corroboration = c
        log.log(
            LockLog.Level.INFO,
            "Text match borderline tier (terms=[${event.matchedTerms.joinToString()}]): no lock yet; checking on-screen " +
                "images for up to ${corroborationWindowMs}ms, detectionId=${event.id}" +
                (previous?.let { " (replaces pending ${it.detectionId})" } ?: "") + "."
        )
        return LockOutcome.CorroborationStarted(c.detectionId, c.deadlineMs)
    }

    private fun lock(source: LockSource, detectionId: String): LockOutcome {
        // A lock settles any pending corroboration: the screen is locked either way.
        corroboration?.let {
            if (it.detectionId != detectionId) {
                log.log(LockLog.Level.INFO, "Pending borderline text match ${it.detectionId} closed by a lock for $detectionId.")
            }
        }
        corroboration = null
        val failure = apply(detectionId)
        if (failure != null) return LockOutcome.Skipped(detectionId, failure)
        val until = clock() + lockDurationMs
        lockedUntilMs = until
        lockDetectionId = detectionId
        relocks = 0
        lockCount++
        log.log(LockLog.Level.WARN, "LOCK triggered, duration=${lockDurationMs}ms, source=${source.label}, detectionId=$detectionId")
        return LockOutcome.Locked(source, detectionId, until)
    }

    /** Locks the device; returns null on success, else why it didn't (already logged). */
    private fun apply(detectionId: String): String? {
        if (!device.isAdminActive()) {
            val reason = "device admin is not active"
            log.log(
                LockLog.Level.WARN,
                "LOCK skipped: $reason (press \"Activate device admin\" in Guardian to enable locking), " +
                    "detectionId=$detectionId. The detection is still logged and notified."
            )
            return reason
        }
        return try {
            device.lockNow()
            null
        } catch (t: Throwable) {
            val reason = "lockNow failed: ${t.javaClass.simpleName}: ${t.message}"
            log.log(LockLog.Level.ERROR, "LOCK failed: $reason, detectionId=$detectionId.")
            reason
        }
    }
}
