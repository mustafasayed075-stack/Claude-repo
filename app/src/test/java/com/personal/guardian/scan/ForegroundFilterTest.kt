package com.personal.guardian.scan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rafiq's own windows (the countdown overlay) must not change the app in front. */
class ForegroundFilterTest {

    private val own = "com.personal.guardian"

    @Test
    fun otherAppsAlwaysCount() {
        assertTrue(ForegroundFilter.counts("com.whatsapp", own, lockActive = false, reflectionShowing = false))
        assertTrue(ForegroundFilter.counts("com.whatsapp", own, lockActive = true, reflectionShowing = true))
    }

    @Test
    fun rafiqsOwnWindowIsIgnoredWhenNoLockAndNoReflection() {
        // The Build 41 case: the countdown overlay appeared during a grace in WhatsApp.
        assertFalse(ForegroundFilter.counts(own, own, lockActive = false, reflectionShowing = false))
    }

    @Test
    fun rafiqCountsWhileALockOrReflectionIsShowing() {
        assertTrue(ForegroundFilter.counts(own, own, lockActive = true, reflectionShowing = false))
        assertTrue(ForegroundFilter.counts(own, own, lockActive = false, reflectionShowing = true))
    }
}
