package com.personal.guardian.scan

/**
 * Decides whether a foreground change during a lock should re-arm detection
 * ([GuardianAccessibilityService] re-evaluates still-visible content after the user
 * leaves Reflection Mode early).
 *
 * Window-state events also come from things that are not "returning to an app": the
 * status bar / notification shade (`com.android.systemui`), the home screen / launcher,
 * keyboards, and Rafiq itself (Reflection Mode). Those are ignored. A real return is
 * re-armed at most once per [minIntervalMs]. Ignored and throttled events are only
 * counted, not logged one by one; the count goes into the next re-arm's log line.
 *
 * Pure Kotlin with injected timestamps; single-threaded use (scanner worker thread).
 */
class RearmGate(val minIntervalMs: Long = ScanConfig.REARM_MIN_INTERVAL_MS) {

    enum class Decision { REARM, NOT_LOCKED, IGNORED_PACKAGE, THROTTLED }

    private var lastRearmAtMs: Long? = null
    private var skipped = 0

    /** [pkg] came to the foreground at [nowMs]; [locked]: a lock is still active. */
    fun decide(pkg: String, locked: Boolean, ignored: (String) -> Boolean, nowMs: Long): Decision {
        if (!locked) return Decision.NOT_LOCKED
        if (ignored(pkg)) {
            skipped++
            return Decision.IGNORED_PACKAGE
        }
        val last = lastRearmAtMs
        if (last != null && nowMs - last < minIntervalMs) {
            skipped++
            return Decision.THROTTLED
        }
        lastRearmAtMs = nowMs
        return Decision.REARM
    }

    /** Ignored + throttled events since the last call; resets the count. */
    fun takeSkipped(): Int = skipped.also { skipped = 0 }

    companion object {
        const val SYSTEM_UI = "com.android.systemui"
        private const val AOSP_LAUNCHER_PREFIX = "com.android.launcher" // launcher, launcher2, launcher3

        /**
         * True if [pkg] is not a real "return to an app": Rafiq itself ([ownPackage]),
         * the system UI, an AOSP launcher, the device's home app(s) ([homePackages]), or
         * an overlay such as a keyboard ([overlayPackages]).
         */
        fun isIgnored(
            pkg: String,
            ownPackage: String,
            homePackages: Set<String> = emptySet(),
            overlayPackages: Set<String> = emptySet()
        ): Boolean =
            pkg == ownPackage || pkg == SYSTEM_UI || pkg.startsWith(AOSP_LAUNCHER_PREFIX) ||
                pkg in homePackages || pkg in overlayPackages
    }
}
