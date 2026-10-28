package com.personal.guardian.scan

/**
 * Whether a window-state event may change which app Guardian treats as "in front".
 *
 * Rafiq's own windows — above all the blind-spot countdown overlay — also produce
 * window-state events with Rafiq's package. Counting those made Rafiq the foreground app
 * the moment the countdown appeared, so every following capture was attributed to Rafiq
 * (not on the blind-spot list) and the grace never reached its lock. Rafiq's own windows
 * therefore don't count, except while a lock is active or Reflection Mode is showing —
 * then Rafiq really is what the user is looking at.
 *
 * Pure Kotlin, so it is unit-tested.
 */
object ForegroundFilter {

    /** True if a window-state event from [pkg] should update the foreground app. */
    fun counts(pkg: String, ownPackage: String, lockActive: Boolean, reflectionShowing: Boolean): Boolean =
        pkg != ownPackage || lockActive || reflectionShowing
}
