package com.personal.guardian.scan

import java.util.Locale

/** Which first-stage rule locks a frame: the same order the scanner applies them. */
object FirstStageLock {
    fun lockedBy(confirmed: Boolean, pornImmediate: Boolean, suggestiveConfirmed: Boolean): String? = when {
        confirmed -> "confirmed"
        pornImmediate -> "per-class porn"
        suggestiveConfirmed -> "suggestive"
        else -> null
    }
}

/**
 * Shadow comparison for the letterbox crop (Build 49, README "Letterbox crop"). The real
 * first stage classifies the crop whenever [LetterboxCrop] finds one. The variant
 * simulated here keeps the crop **only** when the part it removes is really black
 * (≥ [ScanConfig.STRICT_CROP_BLACK_FRACTION] of its cells); otherwise it uses the
 * uncropped frame. It runs the same rules with **its own** confirmers (two frames in 7 s
 * for the combined path, two suggestive frames in 3 s), fed every frame so their history
 * matches the variant's, and reports whether the variant would have locked. It never
 * locks anything. Pure Kotlin; reset it wherever the real confirmers are reset.
 */
class StrictCropShadow(
    private val confirmer: DetectionConfirmer = DetectionConfirmer(),
    private val suggestiveConfirmer: SuggestiveConfirmer = SuggestiveConfirmer()
) {
    /** The variant's whole-screen scores and its lock decision for one frame. */
    data class Outcome(val keepsCrop: Boolean, val whole: NsfwScores, val lockedBy: String?)

    /**
     * [cropped]: the whole-screen scores the app used (the crop when there was one);
     * [uncropped]: the uncropped frame's scores, needed only when the crop's trimmed part
     * isn't black ([trimmedBlack] null = no crop this frame).
     */
    fun onFrame(
        nowMs: Long,
        source: TriggerSource,
        trimmedBlack: Double?,
        cropped: NsfwScores,
        uncropped: () -> NsfwScores,
        regions: List<RegionScore>,
        suggestiveThreshold: Float
    ): Outcome {
        val keepsCrop = trimmedBlack == null || trimmedBlack >= ScanConfig.STRICT_CROP_BLACK_FRACTION
        val whole = if (keepsCrop) cropped else uncropped()
        val verdict = FrameVerdict.combine(whole, regions)
        val confirmation = confirmer.onFrame(verdict.score, nowMs, source, verdict.positive)
        val perClass = PerClassLayer.evaluate(whole, regions.map { it.scores }, suggestiveThreshold)
        val suggestive = (!verdict.positive && verdict.score >= suggestiveThreshold) || perClass.suggestive
        val suggestiveConfirmed = suggestiveConfirmer.onFrame(nowMs, suggestive)
        return Outcome(keepsCrop, whole, FirstStageLock.lockedBy(confirmation != null, perClass.pornImmediate, suggestiveConfirmed))
    }

    fun reset() {
        confirmer.reset()
        suggestiveConfirmer.reset()
    }

    companion object {
        /**
         * e.g. `Strict-crop shadow: crop=[0,1155,1080,2110] of 1080x2412 trimmed black=0.31 (< 0.70: no crop)
         * now[porn=0.978 …] → lock=per-class porn; strict[porn=0.007 …] → no lock — lock unchanged (shadow).`
         */
        fun line(cropLabel: String, trimmedBlack: Double?, now: NsfwScores, nowLockedBy: String?, strict: Outcome, foregroundPackage: String?): String {
            val black = trimmedBlack?.let {
                String.format(
                    Locale.US, " trimmed black=%.2f (%s %.2f: %s)", it,
                    if (strict.keepsCrop) ">=" else "<", ScanConfig.STRICT_CROP_BLACK_FRACTION,
                    if (strict.keepsCrop) "crop kept" else "no crop"
                )
            } ?: ""
            return "Strict-crop shadow: $cropLabel$black now[${now.breakdown()}] → ${nowLockedBy?.let { "lock=$it" } ?: "no lock"}; " +
                "strict[${strict.whole.breakdown()}] → ${strict.lockedBy?.let { "lock=$it" } ?: "no lock"} " +
                "app=${foregroundPackage ?: "unknown"} — lock unchanged (shadow)."
        }
    }
}
