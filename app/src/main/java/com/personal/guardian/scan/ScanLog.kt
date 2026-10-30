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
     * One line for a "suspect" frame (per-class floor reached but not locked), so the
     * per-class thresholds can be tuned from the log, e.g.
     * `Scan suspect: whole[sexy=0.312 porn=0.028 hentai=0.004] region[sexy=0.455 porn=0.010 hentai=0.002 image …] app=…`.
     */
    /** The whole-screen pass's letterbox crop for the frame line: `crop=[l,t,r,b] of WxH`, or `crop=none`. */
    fun cropLabel(crop: Box?, frameWidth: Int, frameHeight: Int): String =
        if (crop == null) "crop=none" else "crop=[${crop.left},${crop.top},${crop.right},${crop.bottom}] of ${frameWidth}x$frameHeight"

    /**
     * A suspect whole-screen frame that was letterbox-cropped, classified a second time
     * uncropped (diagnostics only; the decision uses the cropped scores, as before), e.g.
     * `Crop check: crop=[0,1105,1080,2160] of 1080x2412 cropped[sexy=… porn=0.809 …] uncropped[… porn=0.004 …] app=…`.
     */
    fun cropCheckLine(crop: Box, frameWidth: Int, frameHeight: Int, cropped: NsfwScores, uncropped: NsfwScores, foregroundPackage: String?): String =
        "Crop check: ${cropLabel(crop, frameWidth, frameHeight)} cropped[${cropped.breakdown()}] " +
            "uncropped[${uncropped.breakdown()}] app=${foregroundPackage ?: "unknown"}"

    /** Names the surface a per-class rule fired on: `whole screen (crop=…)` or `region <label>`. */
    fun surfaceLabel(pornSurface: Int, regions: List<RegionScore>, wholeCrop: String): String =
        if (pornSurface == PerClassLayer.WHOLE) "whole screen ($wholeCrop)" else "region ${regions[pornSurface].region.label}"

    /** One second-stage check of one surface, with its pre-processing and inference times. */
    class Stage2Check(val surface: SecondStage.Surface, val prepMs: Long, val inferMs: Long)

    /**
     * The second stage's verdict on one first-stage lock decision (shadow mode, Build 50):
     * the three options under comparison, then every detection on every checked surface
     * with its box (x,y,w,h in that surface's pixels), and the device state, e.g.
     * `STAGE2 shadow: first-stage lock=per-class porn (explicit) app=… screen=on reflection=shown lock=active cpu2 ms=640
     * | crop+full@0.45=CONFIRM (MALE_GENITALIA_EXPOSED 0.526@crop) · full@0.45=VETO (MALE_GENITALIA_EXPOSED 0.405@full) · full,genital@0.60=VETO (…)
     * | crop 1080x955 prep 9 ms infer 420 ms: FACE_FEMALE 0.833 [229,189,643,582], MALE_GENITALIA_EXPOSED 0.526 [334,647,337,220]; full …
     * | #face-mic crop,full — lock unchanged (shadow).`
     */
    fun stage2Line(
        lockedBy: String,
        trigger: SecondStage.Trigger,
        variants: List<SecondStage.Variant>,
        checks: List<Stage2Check>,
        totalMs: Long,
        state: String,
        faceMic: List<String>,
        foregroundPackage: String?
    ): String {
        val budget = if (totalMs > ScanConfig.STAGE2_TIMEOUT_MS) {
            " (over the ${ScanConfig.STAGE2_TIMEOUT_MS} ms budget: enforcement would keep the first-stage lock)"
        } else ""
        val options = variants.joinToString(" · ") { v ->
            "${v.name}=${v.result.decision.label} (${SecondStage.hitLabel(v.result.exposed)}" +
                (v.result.covered?.let { ", covered ${SecondStage.hitLabel(it)}" } ?: "") + ")"
        }
        val surfaces = checks.joinToString("; ") { c ->
            "${c.surface.name} ${c.surface.width}x${c.surface.height} prep ${c.prepMs} ms infer ${c.inferMs} ms: " +
                c.surface.detections.joinToString(", ") { SecondStage.detectionLabel(it) }.ifEmpty { "nothing" }
        }
        val tag = if (faceMic.isEmpty()) "" else " | #face-mic ${faceMic.joinToString(",")}"
        return "STAGE2 shadow: first-stage lock=$lockedBy (${trigger.label}) app=${foregroundPackage ?: "unknown"} $state " +
            "ms=$totalMs$budget | $options | $surfaces$tag — lock unchanged (shadow)."
    }

    fun perClassLine(whole: NsfwScores, regions: List<RegionScore>, foregroundPackage: String?): String {
        val best = regions.maxByOrNull { maxOf(it.scores.sexy, it.scores.porn, it.scores.hentai) }
        val w = String.format(Locale.US, "whole[sexy=%.3f porn=%.3f hentai=%.3f]", whole.sexy, whole.porn, whole.hentai)
        val r = best?.let {
            String.format(
                Locale.US, " region[sexy=%.3f porn=%.3f hentai=%.3f %s]",
                it.scores.sexy, it.scores.porn, it.scores.hentai, it.region.label
            )
        } ?: ""
        return "Scan suspect: $w$r app=${foregroundPackage ?: "unknown"}"
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
