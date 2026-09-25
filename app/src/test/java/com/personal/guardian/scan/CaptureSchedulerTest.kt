package com.personal.guardian.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for capture timing: baseline cadence, immediate fast mode for a
 * watched foreground app, and reverting when it leaves the foreground.
 */
class CaptureSchedulerTest {

    private fun scheduler() = CaptureScheduler(
        watchedPackages = setOf("com.whatsapp", "com.android.chrome"),
        baselineIntervalMs = 7_000,
        fastIntervalMs = 1_500,
        minCaptureGapMs = 1_000,
        overlayPackages = setOf("com.android.systemui")
    )

    @Test
    fun configuredIntervalsAreWithinSpecRanges() {
        assertTrue(ScanConfig.BASELINE_INTERVAL_MS in 6_000L..8_000L)
        assertTrue(ScanConfig.FAST_INTERVAL_MS in 1_000L..2_000L)
        assertTrue("WhatsApp watched", "com.whatsapp" in ScanConfig.WATCHED_PACKAGES)
        assertTrue("Telegram watched", "org.telegram.messenger" in ScanConfig.WATCHED_PACKAGES)
        assertTrue("Chrome watched", "com.android.chrome" in ScanConfig.WATCHED_PACKAGES)
    }

    @Test
    fun startsInBaselineModeAndCapturesImmediately() {
        val s = scheduler()
        assertFalse(s.isFastMode)
        assertEquals(TriggerSource.PERIODIC, s.currentSource)
        assertEquals(0, s.delayUntilNextCapture(nowMs = 50_000))
    }

    @Test
    fun baselineCadenceIsTheConfiguredInterval() {
        val s = scheduler()
        s.onCaptured(10_000)
        assertEquals(7_000, s.delayUntilNextCapture(10_000))
        assertEquals(2_000, s.delayUntilNextCapture(15_000))
        assertEquals(0, s.delayUntilNextCapture(18_000))
    }

    @Test
    fun watchedAppEnteringForegroundSwitchesToFastModeImmediately() {
        val s = scheduler()
        s.onCaptured(10_000)
        assertTrue("mode changed", s.onForegroundChanged("com.whatsapp"))
        assertTrue(s.isFastMode)
        assertEquals(TriggerSource.EVENT, s.currentSource)
        assertEquals(1_500, s.currentIntervalMs)
        // Next capture is immediate (last capture was long enough ago)…
        assertEquals(0, s.delayAfterModeChange(nowMs = 13_000))
        // …or waits only for the platform's minimum gap if one was just taken.
        assertEquals(600, s.delayAfterModeChange(nowMs = 10_400))
        s.onCaptured(13_000)
        assertEquals(1_500, s.delayUntilNextCapture(13_000))
    }

    @Test
    fun leavingWatchedAppRevertsToBaseline() {
        val s = scheduler()
        s.onForegroundChanged("com.whatsapp")
        s.onCaptured(20_000)
        assertTrue("mode changed", s.onForegroundChanged("com.google.android.apps.nexuslauncher"))
        assertFalse(s.isFastMode)
        assertEquals(TriggerSource.PERIODIC, s.currentSource)
        assertEquals(7_000, s.currentIntervalMs)
        assertEquals(6_000, s.delayAfterModeChange(nowMs = 21_000))
    }

    @Test
    fun overlayWindowsDoNotEndFastMode() {
        val s = scheduler()
        s.onForegroundChanged("com.whatsapp")
        assertFalse(s.onForegroundChanged("com.android.systemui")) // notification shade
        assertTrue(s.isFastMode)
        assertEquals("com.whatsapp", s.foregroundPackage)

        s.overlayPackages = setOf("com.android.systemui", "com.google.android.inputmethod.latin")
        assertFalse(s.onForegroundChanged("com.google.android.inputmethod.latin")) // keyboard
        assertTrue(s.isFastMode)
    }

    @Test
    fun switchingBetweenWatchedAppsKeepsFastMode() {
        val s = scheduler()
        assertTrue(s.onForegroundChanged("com.whatsapp"))
        assertFalse("no mode change", s.onForegroundChanged("com.android.chrome"))
        assertTrue(s.isFastMode)
        assertEquals("com.android.chrome", s.foregroundPackage)
    }

    @Test
    fun nullOrEmptyPackageIsIgnored() {
        val s = scheduler()
        s.onForegroundChanged("com.whatsapp")
        assertFalse(s.onForegroundChanged(null))
        assertFalse(s.onForegroundChanged(""))
        assertTrue(s.isFastMode)
    }

    /**
     * Simulates the service loop against a fake clock: 70 s in an unwatched app,
     * then 15 s in WhatsApp, then back. Checks capture counts and sources.
     */
    @Test
    fun simulatedTimelineUsesBaselineThenFastThenBaseline() {
        val s = scheduler()
        val captures = mutableListOf<Pair<Long, TriggerSource>>()
        var now = 0L
        var nextAt = 0L
        /** Runs captures due before [endMs] (exclusive), then advances the clock to it. */
        fun runUntil(endMs: Long) {
            while (nextAt < endMs) {
                now = nextAt
                captures += now to s.currentSource
                s.onCaptured(now)
                nextAt = now + s.delayUntilNextCapture(now)
            }
            now = endMs
        }
        fun switchTo(pkg: String) {
            if (s.onForegroundChanged(pkg)) nextAt = now + s.delayAfterModeChange(now)
        }

        s.onForegroundChanged("com.example.notes")
        runUntil(70_000)
        val baseline = captures.toList()
        assertEquals("captures at 0,7,…,63 s", 10, baseline.size)
        assertTrue(baseline.all { it.second == TriggerSource.PERIODIC })

        switchTo("com.whatsapp")
        assertEquals("fast mode starts immediately", 70_000L, nextAt)
        runUntil(85_000)
        val fast = captures.drop(baseline.size)
        assertEquals("captures at 70.0, 71.5, …, 83.5 s", 10, fast.size)
        assertTrue(fast.all { it.second == TriggerSource.EVENT })
        assertEquals(1_500L, fast[1].first - fast[0].first)

        switchTo("com.example.notes")
        assertEquals("next baseline capture 7 s after the last one", 83_500L + 7_000L, nextAt)
        runUntil(105_000)
        val after = captures.drop(baseline.size + fast.size)
        assertEquals(listOf(90_500L, 97_500L, 104_500L), after.map { it.first })
        assertTrue(after.all { it.second == TriggerSource.PERIODIC })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsFastIntervalBelowPlatformGap() {
        CaptureScheduler(fastIntervalMs = 500, minCaptureGapMs = 1_000)
    }
}
