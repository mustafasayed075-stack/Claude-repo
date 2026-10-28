package com.personal.guardian.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the per-capture blind-spot path, including the Build 41 bug: the
 * countdown overlay made Rafiq the "app in front", so captures stopped counting for the
 * listed app and the grace never locked.
 */
class BlindSpotTrackerTest {

    private var now = 0L
    private fun tracker() = BlindSpotTracker(
        BlindSpotPolicy(clock = { now }, graceScheduleMs = listOf(60_000, 30_000, 10_000), resetAfterVisibleMs = 3_600_000),
        confirmFrames = 2
    )

    @Test
    fun twoUnreadableCapturesInAListedAppStartTheGraceAndItLocksWhenItElapses() {
        val t = tracker()
        assertEquals("1/2: guardrail", null, t.onUnreadable(listed = true).outcome)
        val started = t.onUnreadable(listed = true).outcome as BlindSpotPolicy.Outcome.GraceStarted
        assertEquals(60_000L, started.graceMs)
        now += 60_000
        assertTrue(t.onUnreadable(listed = true).outcome is BlindSpotPolicy.Outcome.Act)
    }

    @Test
    fun readableFrameEndsTheEpisodeAndTheStateAfterwardsIsNotBlind() {
        val t = tracker()
        t.onUnreadable(true); t.onUnreadable(true)
        assertTrue(t.isBlind)
        val r = t.onReadable()
        assertTrue("grace cancelled by a readable frame", r.graceCancelled)
        // State read after the call — what the log line prints now.
        assertFalse(t.isBlind)
        assertEquals(0, t.consecutiveUnreadable)
    }

    @Test
    fun aReadableFrameWithNoEpisodeCancelsNothing() {
        val r = tracker().onReadable()
        assertFalse(r.graceCancelled)
        assertFalse(r.levelReset)
    }

    @Test
    fun unreadableCapturesOutsideTheListAreSkippedWithAReason() {
        val t = tracker()
        t.onUnreadable(true); t.onUnreadable(true) // grace running
        val r = t.onUnreadable(listed = false)
        assertNull(r.outcome)
        assertEquals(BlindSpotTracker.SKIP_NOT_LISTED, r.skipReason)
        assertTrue("an unlisted capture doesn't end the episode", t.isBlind)
    }

    @Test
    fun theGraceStillLocksWhenCapturesKeepCountingForTheListedApp() {
        // With the fix, Rafiq's overlay doesn't change the app in front, so captures under
        // the overlay still count for the listed app and the grace reaches its lock.
        val t = tracker()
        t.onUnreadable(true); t.onUnreadable(true) // grace 60 s started, overlay shown
        repeat(39) { now += 1_500; assertTrue(t.onUnreadable(listed = true).outcome is BlindSpotPolicy.Outcome.Waiting) }
        now += 1_500 // 60 s since the grace started
        assertTrue(t.onUnreadable(listed = true).outcome is BlindSpotPolicy.Outcome.Act)
    }

    @Test
    fun screenOffRestartsTheGuardrailButKeepsTheEscalation() {
        val t = tracker()
        t.onUnreadable(true); t.onUnreadable(true)
        assertEquals(1, t.level)
        t.onScreenOff()
        assertEquals(0, t.consecutiveUnreadable)
        assertEquals(1, t.level)
    }

    @Test
    fun resetGoesBackToSixtySeconds() {
        val t = tracker()
        repeat(4) { t.onUnreadable(true); t.onUnreadable(true); t.onReadable() }
        assertEquals(0L, t.nextGraceMs())
        t.reset()
        assertEquals(0, t.level)
        assertFalse(t.isBlind)
        assertEquals(60_000L, t.nextGraceMs())
    }
}
