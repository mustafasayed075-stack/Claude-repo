package com.personal.guardian.scan

/** Pure countdown maths for the blind-spot overlay, so it is unit-tested. */
object BlindSpotCountdown {

    /**
     * Whole seconds left until [deadlineMs] from [nowMs] (both a monotonic clock),
     * rounded **up** so a 60 000 ms grace shows "60" for its first second and never a
     * misleading "0" while time remains. Never negative.
     */
    fun secondsLeft(deadlineMs: Long, nowMs: Long): Int {
        val remaining = deadlineMs - nowMs
        if (remaining <= 0) return 0
        return ((remaining + 999) / 1000).toInt()
    }
}
