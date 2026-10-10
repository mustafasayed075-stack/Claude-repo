package com.personal.guardian.scan

import org.junit.Assert.assertEquals
import org.junit.Test

class BlindSpotCountdownTest {

    @Test
    fun roundsUpAndneverGoesNegative() {
        assertEquals(60, BlindSpotCountdown.secondsLeft(60_000, 0))
        assertEquals(60, BlindSpotCountdown.secondsLeft(60_000, 1))     // 59.999 s left → "60"
        assertEquals(59, BlindSpotCountdown.secondsLeft(60_000, 1_000))
        assertEquals(1, BlindSpotCountdown.secondsLeft(60_000, 59_500))
        assertEquals(0, BlindSpotCountdown.secondsLeft(60_000, 60_000))
        assertEquals(0, BlindSpotCountdown.secondsLeft(60_000, 70_000))
    }
}
