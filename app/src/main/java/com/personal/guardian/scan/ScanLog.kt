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
     */
    fun frameLine(
        scores: NsfwScores,
        threshold: Float,
        source: TriggerSource,
        foregroundPackage: String?,
        positives: Int,
        required: Int
    ): String {
        val signal = scores.signal
        val cmp = if (signal >= threshold) ">=" else "<"
        return String.format(
            Locale.US,
            "Scan frame: signal=%.4f [%s %.2f] %s trigger=%s app=%s positives=%d/%d",
            signal, cmp, threshold, scores.breakdown(), source.label,
            foregroundPackage ?: "unknown", positives, required
        )
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
