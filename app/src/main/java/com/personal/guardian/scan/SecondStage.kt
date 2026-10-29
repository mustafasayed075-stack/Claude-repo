package com.personal.guardian.scan

import java.util.Locale

/**
 * NudeNet's 18 body-part classes, in the model's output order
 * (third_party/nudenet/class_labels.txt).
 */
enum class BodyPart {
    FEMALE_GENITALIA_COVERED, FACE_FEMALE, BUTTOCKS_EXPOSED, FEMALE_BREAST_EXPOSED, FEMALE_GENITALIA_EXPOSED,
    MALE_BREAST_EXPOSED, ANUS_EXPOSED, FEET_EXPOSED, BELLY_COVERED, FEET_COVERED, ARMPITS_COVERED,
    ARMPITS_EXPOSED, FACE_MALE, BELLY_EXPOSED, MALE_GENITALIA_EXPOSED, ANUS_COVERED, FEMALE_BREAST_COVERED,
    BUTTOCKS_COVERED
}

/** One detection, in the model's 320×320 input space (left/top corner, size). */
data class BodyPartDetection(
    val part: BodyPart,
    val score: Float,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float
)

/**
 * Turns the second-stage model's raw outputs into detections, exactly as NudeNet's
 * `nudenet.py` does (third_party/nudenet/README.md): YOLOv8 anchors + DFL distances →
 * boxes, keep anchors whose best class ≥ [MIN_SCORE], then **class-agnostic** NMS
 * ([NMS_SCORE], [NMS_IOU]) like `cv2.dnn.NMSBoxes`. Pure Kotlin; checked against
 * NudeNet's own post-processing in `BodyPartDecoderTest`.
 */
object BodyPartDecoder {

    const val INPUT_DIM = 320
    const val CLASSES = 18
    const val MIN_SCORE = 0.2f
    const val NMS_SCORE = 0.25f
    const val NMS_IOU = 0.45f

    private val GRIDS = listOf(8 to 40, 16 to 20, 32 to 10) // stride to grid size at 320
    val ANCHORS = GRIDS.sumOf { it.second * it.second } // 2100

    private val anchorX = FloatArray(ANCHORS)
    private val anchorY = FloatArray(ANCHORS)
    private val stride = FloatArray(ANCHORS)

    init {
        var i = 0
        for ((s, g) in GRIDS) for (y in 0 until g) for (x in 0 until g) {
            anchorX[i] = x + 0.5f; anchorY[i] = y + 0.5f; stride[i] = s.toFloat(); i++
        }
    }

    /**
     * [dist]: `[4][ANCHORS]` row-major (left, top, right, bottom distances in grid units);
     * [scores]: `[CLASSES][ANCHORS]` row-major class scores. Highest score first.
     */
    fun decode(dist: FloatArray, scores: FloatArray): List<BodyPartDetection> {
        require(dist.size == 4 * ANCHORS) { "dist must be 4x$ANCHORS" }
        require(scores.size == CLASSES * ANCHORS) { "scores must be ${CLASSES}x$ANCHORS" }
        val candidates = ArrayList<BodyPartDetection>()
        for (a in 0 until ANCHORS) {
            var best = 0
            var bestScore = scores[a]
            for (c in 1 until CLASSES) {
                val v = scores[c * ANCHORS + a]
                if (v > bestScore) { best = c; bestScore = v }
            }
            if (bestScore < MIN_SCORE) continue
            val s = stride[a]
            val x1 = anchorX[a] - dist[a]
            val y1 = anchorY[a] - dist[ANCHORS + a]
            val x2 = anchorX[a] + dist[2 * ANCHORS + a]
            val y2 = anchorY[a] + dist[3 * ANCHORS + a]
            val w = (x2 - x1) * s
            val h = (y2 - y1) * s
            candidates += BodyPartDetection(BodyPart.entries[best], bestScore, (x1 + x2) / 2 * s - w / 2, (y1 + y2) / 2 * s - h / 2, w, h)
        }
        return nms(candidates)
    }

    /** Class-agnostic greedy NMS, as `cv2.dnn.NMSBoxes(boxes, scores, NMS_SCORE, NMS_IOU)`. */
    private fun nms(candidates: List<BodyPartDetection>): List<BodyPartDetection> {
        val sorted = candidates.filter { it.score > NMS_SCORE }.sortedByDescending { it.score }
        val kept = ArrayList<BodyPartDetection>()
        for (d in sorted) {
            if (kept.none { iou(it, d) > NMS_IOU }) kept += d
        }
        return kept
    }

    private fun iou(a: BodyPartDetection, b: BodyPartDetection): Float {
        val ix = minOf(a.left + a.width, b.left + b.width) - maxOf(a.left, b.left)
        val iy = minOf(a.top + a.height, b.top + b.height) - maxOf(a.top, b.top)
        if (ix <= 0f || iy <= 0f) return 0f
        val inter = ix * iy
        return inter / (a.width * a.height + b.width * b.height - inter)
    }
}

/**
 * The second-stage reviewer's decision (README "Second stage"): when the first stage
 * (viddexa-nano) decides to lock, does the frame really show an exposed body part?
 *
 *  - **exposed** ([EXPOSED] at or above [ScanConfig.STAGE2_EXPOSED_THRESHOLD]) on any
 *    checked surface → [Decision.CONFIRM].
 *  - only **covered intimate** parts ([COVERED] at or above
 *    [ScanConfig.STAGE2_COVERED_THRESHOLD]) → [Decision.DOWNGRADE] for an explicit
 *    trigger (it would go through the two-frame suggestive rule), [Decision.CONFIRM]
 *    for a suggestive one.
 *  - anything else — a face on its own, a clothed body — → [Decision.VETO].
 *
 * `MALE_BREAST_EXPOSED` is deliberately not "exposed" (owner decision: shirtless men at
 * the beach or gym are ordinary). Faces, feet, armpits and bellies never count.
 * Pure Kotlin, unit-tested; Build 48 only logs the decision (shadow mode).
 */
object SecondStage {

    enum class Trigger(val label: String) { EXPLICIT("explicit"), SUGGESTIVE("suggestive") }

    enum class Decision(val label: String) { CONFIRM("CONFIRM"), DOWNGRADE("DOWNGRADE to suggestive"), VETO("VETO") }

    val EXPOSED = setOf(
        BodyPart.FEMALE_BREAST_EXPOSED, BodyPart.FEMALE_GENITALIA_EXPOSED, BodyPart.MALE_GENITALIA_EXPOSED,
        BodyPart.BUTTOCKS_EXPOSED, BodyPart.ANUS_EXPOSED
    )
    val COVERED = setOf(BodyPart.FEMALE_BREAST_COVERED, BodyPart.FEMALE_GENITALIA_COVERED, BodyPart.BUTTOCKS_COVERED)

    /** One checked image: the cropped whole screen, the uncropped one, or the region that triggered. */
    data class Surface(val name: String, val detections: List<BodyPartDetection>)

    /** The best detection of a group, with the surface it was on. */
    data class Hit(val detection: BodyPartDetection, val surface: String)

    data class Result(val decision: Decision, val exposed: Hit?, val covered: Hit?)

    fun decide(
        trigger: Trigger,
        surfaces: List<Surface>,
        exposedThreshold: Float = ScanConfig.STAGE2_EXPOSED_THRESHOLD,
        coveredThreshold: Float = ScanConfig.STAGE2_COVERED_THRESHOLD
    ): Result {
        val exposed = best(surfaces, EXPOSED)
        val covered = best(surfaces, COVERED)
        val decision = when {
            exposed != null && exposed.detection.score >= exposedThreshold -> Decision.CONFIRM
            covered != null && covered.detection.score >= coveredThreshold ->
                if (trigger == Trigger.EXPLICIT) Decision.DOWNGRADE else Decision.CONFIRM
            else -> Decision.VETO
        }
        return Result(decision, exposed, covered)
    }

    private fun best(surfaces: List<Surface>, parts: Set<BodyPart>): Hit? =
        surfaces.flatMap { s -> s.detections.filter { it.part in parts }.map { Hit(it, s.name) } }
            .maxByOrNull { it.detection.score }

    /** `FEMALE_BREAST_EXPOSED 0.812@crop`, or `none`. */
    fun hitLabel(hit: Hit?): String =
        hit?.let { String.format(Locale.US, "%s %.3f@%s", it.detection.part.name, it.detection.score, it.surface) } ?: "none"
}
