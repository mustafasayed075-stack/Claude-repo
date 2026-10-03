package com.personal.guardian.scan

import java.util.Locale

/**
 * Formatting for scan-related log lines. Pure Kotlin so the exact text is
 * unit-tested (and stays greppable in the device log).
 */
object ScanLog {

    /**
     * One line per classified frame (calibration aid, see
     * [ScanConfig.LOG_EVERY_FRAME_SCORE]), e.g.
     * `Scan frame: signal=0.6430 [>= 0.15] sexy=0.612 porn=0.031 hentai=0.000 safe=0.340 drawing=0.017 trigger=event app=com.whatsapp positives=1/2`,
     * where `signal` is what the threshold applies to — [signal], by default
     * [NsfwScores.signal]; the scanner passes the verdict's own signal (the whole
     * screen's [NsfwScores.screenSignal], or the region's) — and
     * `positives` counts positive frames currently inside the confirmation window.
     * With region scanning, [regions] summarises the regions of this capture (see
     * [regionSummary]) and is appended as ` regions=…`.
     */
    fun frameLine(
        scores: NsfwScores,
        threshold: Float,
        source: TriggerSource,
        foregroundPackage: String?,
        positives: Int,
        required: Int,
        regions: String? = null,
        signal: Float = scores.signal,
        /** safe / suggestive / explicit for this frame (see [NsfwLevel]); null to omit. */
        level: String? = null,
        /** Fast-scan membership of the foreground app (e.g. "text+image", "no"); null to omit. */
        fastScan: String? = null
    ): String {
        val cmp = if (signal >= threshold) ">=" else "<"
        val (topName, topVal) = scores.top()
        val head = String.format(
            Locale.US, "Scan frame: signal=%.4f [%s %.2f] top=%s %.3f", signal, cmp, threshold, topName, topVal
        )
        val mid = (level?.let { " level=$it" } ?: "") + (fastScan?.let { " fast=$it" } ?: "")
        val tail = String.format(
            Locale.US, " %s trigger=%s app=%s positives=%d/%d",
            scores.breakdown(), source.label, foregroundPackage ?: "unknown", positives, required
        )
        return head + mid + tail + (regions?.let { " regions=$it" } ?: "")
    }

    /**
     * One line for a capture that produced no usable frame — a failure (screenshot
     * error, incl. secure window) or a blank/near-black frame — with the app and its
     * fast-scan membership, e.g.
     * `Scan capture: FAILED (secure window …) app=org.telegram.messenger fast=text+image`.
     */
    fun captureIssueLine(issue: String, foregroundPackage: String?, fastScan: String? = null): String =
        "Scan capture: $issue app=${foregroundPackage ?: "unknown"}" + (fastScan?.let { " fast=$it" } ?: "")

    /**
     * Compact per-capture region summary: count, and each region's signal, kind and
     * size (`*` = score served from the cache), e.g.
     * `2 [0.912 image 540x540@480,900 (ImageView)*, 0.004 video 1080x608@0,300 (TextureView)]`.
     */
    fun regionSummary(regions: List<RegionScore>): String =
        "${regions.size}" + if (regions.isEmpty()) "" else regions.joinToString(prefix = " [", postfix = "]") {
            String.format(Locale.US, "%.3f %s%s", it.scores.signal, it.region.label, if (it.cached) "*" else "")
        }

    /**
     * Name for an `ApplicationExitInfo.REASON_*` code (values are stable API
     * constants; listed here so older code paths don't reference newer fields).
     */
    fun exitReasonName(reason: Int): String = when (reason) {
        0 -> "UNKNOWN"
        1 -> "EXIT_SELF"
        2 -> "SIGNALED"
        3 -> "LOW_MEMORY"
        4 -> "CRASH"
        5 -> "CRASH_NATIVE"
        6 -> "ANR"
        7 -> "INITIALIZATION_FAILURE"
        8 -> "PERMISSION_CHANGE"
        9 -> "EXCESSIVE_RESOURCE_USAGE"
        10 -> "USER_REQUESTED"
        11 -> "USER_STOPPED"
        12 -> "DEPENDENCY_DIED"
        13 -> "OTHER"
        14 -> "FREEZER"
        15 -> "PACKAGE_STATE_CHANGE"
        16 -> "PACKAGE_UPDATED"
        else -> "REASON_$reason"
    }
}
