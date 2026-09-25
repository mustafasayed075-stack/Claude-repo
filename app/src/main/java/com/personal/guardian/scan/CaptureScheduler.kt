package com.personal.guardian.scan

/**
 * Decides *when* the next screenshot should be taken.
 *
 *  - Baseline mode: one capture every [baselineIntervalMs], whatever is on screen.
 *  - Fast mode: while a package in [watchedPackages] is in the foreground, one
 *    capture every [fastIntervalMs]. Entering fast mode requests a capture right
 *    away (subject to [minCaptureGapMs]); leaving it reverts to the baseline rate.
 *
 * Foreground changes come from accessibility window-state events. Windows from
 * [overlayPackages] (system UI, keyboards) are drawn over the real app, so their
 * events are ignored rather than treated as the app leaving the foreground.
 *
 * Pure Kotlin with injected timestamps (monotonic ms), so it is unit-testable
 * without Android. Not thread-safe: use from the scanner's worker thread.
 */
class CaptureScheduler(
    private val watchedPackages: Set<String> = ScanConfig.WATCHED_PACKAGES,
    val baselineIntervalMs: Long = ScanConfig.BASELINE_INTERVAL_MS,
    val fastIntervalMs: Long = ScanConfig.FAST_INTERVAL_MS,
    private val minCaptureGapMs: Long = ScanConfig.MIN_CAPTURE_GAP_MS,
    overlayPackages: Set<String> = ScanConfig.OVERLAY_PACKAGES
) {
    init {
        require(fastIntervalMs in minCaptureGapMs..baselineIntervalMs) {
            "need minCaptureGap <= fastInterval <= baselineInterval"
        }
    }

    @Volatile
    var overlayPackages: Set<String> = overlayPackages

    /** Package currently believed to be in the foreground (null until first seen). */
    var foregroundPackage: String? = null
        private set

    val isFastMode: Boolean get() = foregroundPackage?.let { it in watchedPackages } == true

    val currentIntervalMs: Long get() = if (isFastMode) fastIntervalMs else baselineIntervalMs

    /** What the spec's metadata calls the trigger source of a capture taken now. */
    val currentSource: TriggerSource get() = if (isFastMode) TriggerSource.EVENT else TriggerSource.PERIODIC

    private var lastCaptureAtMs: Long? = null

    /**
     * Records a window-state change to [pkg]. Returns true if this switched between
     * baseline and fast mode — the caller should then reschedule using
     * [delayAfterModeChange].
     */
    fun onForegroundChanged(pkg: String?): Boolean {
        if (pkg.isNullOrEmpty() || pkg in overlayPackages) return false
        val wasFast = isFastMode
        foregroundPackage = pkg
        return wasFast != isFastMode
    }

    /** Must be called whenever a capture is started, with the current time. */
    fun onCaptured(nowMs: Long) {
        lastCaptureAtMs = nowMs
    }

    /** Delay from [nowMs] until the next capture is due at the current rate. */
    fun delayUntilNextCapture(nowMs: Long): Long {
        val last = lastCaptureAtMs ?: return 0
        return (last + currentIntervalMs - nowMs).coerceAtLeast(0)
    }

    /**
     * Delay to use right after a mode change. Entering fast mode captures
     * immediately (only waiting out the platform's minimum gap); leaving it simply
     * continues at the baseline rate.
     */
    fun delayAfterModeChange(nowMs: Long): Long {
        if (!isFastMode) return delayUntilNextCapture(nowMs)
        val last = lastCaptureAtMs ?: return 0
        return (last + minCaptureGapMs - nowMs).coerceAtLeast(0)
    }
}
