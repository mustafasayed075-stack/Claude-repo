package com.personal.guardian.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the re-arm gate (system packages ignored, minimum interval). */
class RearmGateTest {

    private val own = "com.personal.guardian"
    private val ignored = { p: String -> RearmGate.isIgnored(p, own, homePackages = setOf("com.sec.android.app.launcher")) }

    @Test
    fun systemUiLaunchersAndRafiqItselfAreIgnored() {
        assertTrue(RearmGate.isIgnored("com.android.systemui", own))
        assertTrue(RearmGate.isIgnored("com.android.launcher", own))
        assertTrue(RearmGate.isIgnored("com.android.launcher3", own))
        assertTrue(RearmGate.isIgnored(own, own))
        assertTrue("device home app", RearmGate.isIgnored("com.sec.android.app.launcher", own, setOf("com.sec.android.app.launcher")))
        assertTrue("keyboard overlay", RearmGate.isIgnored("com.google.android.inputmethod.latin", own, overlayPackages = setOf("com.google.android.inputmethod.latin")))
        assertFalse(RearmGate.isIgnored("org.telegram.messenger", own))
        assertFalse(RearmGate.isIgnored("com.whatsapp", own))
    }

    @Test
    fun aRealAppDuringALockReArms() {
        val g = RearmGate(minIntervalMs = 2_000)
        assertEquals(RearmGate.Decision.REARM, g.decide("org.telegram.messenger", locked = true, ignored, nowMs = 0))
    }

    @Test
    fun nothingHappensWhenNotLocked() {
        val g = RearmGate(minIntervalMs = 2_000)
        assertEquals(RearmGate.Decision.NOT_LOCKED, g.decide("org.telegram.messenger", locked = false, ignored, nowMs = 0))
        assertEquals("not counted as skipped", 0, g.takeSkipped())
    }

    @Test
    fun aStormOfSystemEventsIsIgnoredAndOnlyCounted() {
        val g = RearmGate(minIntervalMs = 2_000)
        repeat(100) { i ->
            val pkg = if (i % 2 == 0) "com.android.systemui" else "com.android.launcher"
            assertEquals(RearmGate.Decision.IGNORED_PACKAGE, g.decide(pkg, locked = true, ignored, nowMs = i * 250L))
        }
        assertEquals(100, g.takeSkipped())
        assertEquals("count resets once taken", 0, g.takeSkipped())
        // The storm didn't consume the interval: the real app still re-arms.
        assertEquals(RearmGate.Decision.REARM, g.decide("com.whatsapp", locked = true, ignored, nowMs = 25_000))
    }

    @Test
    fun reArmsAreThrottledToTheMinimumInterval() {
        val g = RearmGate(minIntervalMs = 2_000)
        assertEquals(RearmGate.Decision.REARM, g.decide("com.whatsapp", true, ignored, 10_000))
        assertEquals(RearmGate.Decision.THROTTLED, g.decide("org.telegram.messenger", true, ignored, 11_999))
        assertEquals(RearmGate.Decision.REARM, g.decide("org.telegram.messenger", true, ignored, 12_000))
        assertEquals(1, g.takeSkipped())
    }
}
