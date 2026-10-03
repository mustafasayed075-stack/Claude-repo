package com.personal.guardian.scan

/**
 * Additive per-class detection layer (README "Screen scanning → per-class layer").
 *
 * The combined-signal paths ([FrameVerdict]: whole-screen `screenSignal`, region
 * `signal`) stay exactly as they are. This adds independent per-class rules on top, so
 * a frame with one moderate class but a low *sum* is still caught:
 *
 *  - **porn** on any surface (the letterbox-cropped whole screen or any region) at or
 *    above [ScanConfig.PORN_IMMEDIATE_THRESHOLD] → [pornImmediate]: lock now, one frame.
 *  - **sexy** on any surface at or above the sensitivity threshold → [suggestive]: goes
 *    through [SuggestiveConfirmer] (two frames), never an immediate lock.
 *  - **hentai** on an **image region only** (never the whole UI screen, which the model
 *    floods with hentai) at or above [ScanConfig.HENTAI_SUGGESTIVE_THRESHOLD] →
 *    [suggestive].
 *  - a low floor ([ScanConfig.SUSPECT_FLOOR]) on sexy/porn on any surface, or hentai on
 *    a region, marks the frame [suspect] — used **only for the diagnostic log**, never
 *    to lock.
 *
 * Pure Kotlin so it is unit-tested without Android.
 */
object PerClassLayer {

    /**
     * @param whole the whole-screen (letterbox-cropped) class scores.
     * @param regions each classified region's class scores.
     * @param sexyThreshold the current sensitivity's sexy threshold ([ScanSensitivity]).
     */
    data class Verdict(val pornImmediate: Boolean, val suggestive: Boolean, val suspect: Boolean)

    fun evaluate(
        whole: NsfwScores,
        regions: List<NsfwScores>,
        sexyThreshold: Float,
        pornThreshold: Float = ScanConfig.PORN_IMMEDIATE_THRESHOLD,
        hentaiRegionThreshold: Float = ScanConfig.HENTAI_SUGGESTIVE_THRESHOLD,
        suspectFloor: Float = ScanConfig.SUSPECT_FLOOR
    ): Verdict {
        val surfaces = regions + whole
        // porn: whole screen + regions, immediate.
        val pornImmediate = surfaces.any { it.porn >= pornThreshold }
        // sexy: whole screen + regions, suggestive.
        val sexySuggestive = surfaces.any { it.sexy >= sexyThreshold }
        // hentai: regions only (the whole UI screen floods hentai), suggestive.
        val hentaiSuggestive = regions.any { it.hentai >= hentaiRegionThreshold }
        // suspect (log only): any sexy/porn on any surface, or hentai on a region.
        val suspect = surfaces.any { it.sexy >= suspectFloor || it.porn >= suspectFloor } ||
            regions.any { it.hentai >= suspectFloor }
        return Verdict(
            pornImmediate = pornImmediate,
            suggestive = sexySuggestive || hentaiSuggestive,
            suspect = suspect
        )
    }
}
