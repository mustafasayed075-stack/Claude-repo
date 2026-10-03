package com.personal.guardian.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Escalating blind-spot grace with a fake clock: 60 s → 30 s → 10 s → immediate,
 * acting when the user stays blind past the grace, and resetting after an hour of
 * continuous visibility.
 */
class BlindSpotPolicyTest {

    private var now = 0L
    private fun policy() = BlindSpotPolicy(
        clock = { now },
        graceScheduleMs = listOf(60_000, 30_000, 10_000),
        resetAfterVisibleMs = 3_600_000
    )

    @Test
    fun scheduleMatchesTheSpec() {
        assertEquals(listOf(60_000L, 30_000L, 10_000L), ScanConfig.BLIND_SPOT_GRACE_MS.toList())
        assertEquals(3_600_000L, ScanConfig.BLIND_SPOT_RESET_AFTER_VISIBLE_MS)
    }

    @Test
    fun firstEntryGetsSixtySecondsThenLocksWhenItElapses() {
        val p = policy()
        val started = p.onBlind() as BlindSpotPolicy.Outcome.GraceStarted
        assertEquals(60_000L, started.graceMs)
        assertEquals(1, started.episode)
        now += 59_999
        assertTrue(p.onBlind() is BlindSpotPolicy.Outcome.Waiting)
        now += 1
        val act = p.onBlind() as BlindSpotPolicy.Outcome.Act
        assertEquals(1, act.episode)
        // Doesn't act twice in the same episode.
        now += 5_000
        assertTrue(p.onBlind() is BlindSpotPolicy.Outcome.Waiting)
    }

    @Test
    fun graceShrinksOnEachReentry() {
        val p = policy()
        // 1st: 60 s
        assertEquals(60_000L, (p.onBlind() as BlindSpotPolicy.Outcome.GraceStarted).graceMs)
        now += 100; assertTrue(p.onVisible() is BlindSpotPolicy.Outcome.EpisodeEnded)
        // 2nd: 30 s
        assertEquals(30_000L, (p.onBlind() as BlindSpotPolicy.Outcome.GraceStarted).graceMs)
        now += 100; p.onVisible()
        // 3rd: 10 s
        assertEquals(10_000L, (p.onBlind() as BlindSpotPolicy.Outcome.GraceStarted).graceMs)
        now += 100; p.onVisible()
        // 4th and after: act immediately, no grace
        val act = p.onBlind() as BlindSpotPolicy.Outcome.Act
        assertEquals(4, act.episode)
        now += 100; p.onVisible()
        assertTrue("5th entry still immediate", p.onBlind() is BlindSpotPolicy.Outcome.Act)
    }

    @Test
    fun leavingBeforeTheGraceElapsesStillCountsAsAnEntry() {
        val p = policy()
        p.onBlind() // 1st, 60 s grace
        now += 2_000
        p.onVisible() // left early, no lock
        assertEquals(30_000L, (p.onBlind() as BlindSpotPolicy.Outcome.GraceStarted).graceMs) // 2nd shrinks
    }

    @Test
    fun oneHourOfContinuousVisibilityResetsTheEscalation() {
        val p = policy()
        p.onBlind(); now += 100; p.onVisible() // level now 1
        p.onBlind(); now += 100; p.onVisible() // level now 2
        assertEquals(2, p.level)
        // Visible, but not yet an hour.
        now += 3_599_000
        assertTrue(p.onVisible() is BlindSpotPolicy.Outcome.None)
        assertEquals(2, p.level)
        // Crossing the hour resets.
        now += 1_000
        assertTrue(p.onVisible() is BlindSpotPolicy.Outcome.LevelReset)
        assertEquals(0, p.level)
        // Back to a 60 s grace.
        assertEquals(60_000L, (p.onBlind() as BlindSpotPolicy.Outcome.GraceStarted).graceMs)
    }

    @Test
    fun aBlindEpisodeInterruptsTheHourSoResetNeedsContinuousVisibility() {
        val p = policy()
        p.onBlind(); now += 100; p.onVisible() // level 1
        now += 1_800_000 // half an hour visible
        p.onVisible()
        // A blind blip resets the visible clock.
        p.onBlind(); now += 100; p.onVisible() // level 2, visibleSince restarts here
        now += 1_800_000 // another half hour — not a full continuous hour since the blip
        assertTrue(p.onVisible() is BlindSpotPolicy.Outcome.None)
        assertEquals(2, p.level)
        now += 1_800_000 // now a full hour since the last episode ended
        assertTrue(p.onVisible() is BlindSpotPolicy.Outcome.LevelReset)
        assertEquals(0, p.level)
    }

    @Test
    fun isBlindAndGracePreviewTrackState() {
        val p = policy()
        assertFalse(p.isBlind)
        assertEquals(60_000L, p.graceForNextEntryMs())
        p.onBlind()
        assertTrue(p.isBlind)
        assertEquals("next entry's grace after consuming the first", 30_000L, p.graceForNextEntryMs())
        p.onVisible()
        assertFalse(p.isBlind)
    }

    @Test
    fun staBlindWithZeroGraceActsOnEntryEveryReentryOnceExhausted() {
        val p = policy()
        repeat(3) { p.onBlind(); now += 10; p.onVisible(); now += 10 } // consume 60/30/10
        repeat(3) {
            assertTrue("immediate lock on entry", p.onBlind() is BlindSpotPolicy.Outcome.Act)
            now += 10; p.onVisible(); now += 10
        }
    }
}
