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
     * `Scan frame: signal=0.6430 [>= 0.30] sexy=0.612 porn=0.031 hentai=0.000 neutral=0.340 drawings=0.017 trigger=event app=com.whatsapp positives=1/2`,
     * where `signal` = sexy + porn + hentai (what the threshold applies to) and
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
        regions: String? = null
    ): String {
        val signal = scores.signal
        val cmp = if (signal >= threshold) ">=" else "<"
        return String.format(
            Locale.US,
            "Scan frame: signal=%.4f [%s %.2f] %s trigger=%s app=%s positives=%d/%d",
            signal, cmp, threshold, scores.breakdown(), source.label,
            foregroundPackage ?: "unknown", positives, required
        ) + (regions?.let { " regions=$it" } ?: "")
    }

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
