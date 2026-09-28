package com.personal.guardian.scan

import java.util.Locale

/**
 * Per-class probabilities from the bundled viddexa/nsfw-detection-2-nano model
 * (softmax, sum ≈ 1; field order = the model's output order).
 *
 * Two signals are derived:
 *  - [signal] = sexy + porn + hentai, the combined probability of the three
 *    not-safe classes. Used for **regions** (an image element classified at its
 *    own resolution), where hentai behaves: on real app regions it barely changes
 *    the false-positive rate (README "Model").
 *  - [screenSignal] = sexy + porn, plus hentai only when the model is at least
 *    [ScanConfig.SCREEN_HENTAI_GATE] confident. Used for the **whole screen**: the
 *    model reads plain UI/text screenshots as *drawings of* something, and they
 *    leak into hentai (43% of chat-app screens scored ≥ 0.7 on [signal]), while
 *    innocent screens almost never reach 0.95 hentai (0.1%).
 */
data class NsfwScores(
    val safe: Float,
    val hentai: Float,
    val porn: Float,
    val sexy: Float,
    val drawing: Float
) {
    val signal: Float get() = (sexy + porn + hentai).coerceIn(0f, 1f)

    val screenSignal: Float
        get() = (sexy + porn + if (hentai >= ScanConfig.SCREEN_HENTAI_GATE) hentai else 0f).coerceIn(0f, 1f)

    /** Compact breakdown for logs, e.g. `sexy=0.612 porn=0.031 hentai=0.002 safe=0.340 drawing=0.015`. */
    fun breakdown(): String = String.format(
        Locale.US, "sexy=%.3f porn=%.3f hentai=%.3f safe=%.3f drawing=%.3f",
        sexy, porn, hentai, safe, drawing
    )
}

/**
 * Pixel → model-input conversion and output parsing for the bundled
 * viddexa/nsfw-detection-2-nano TFLite model (EfficientNet-B0; see
 * third_party/viddexa_nsfw_detection_2_nano/README.md): RGB, 224×224, each channel
 * scaled to 0..1. The model's own normalisation is part of the graph.
 *
 * Pure Kotlin, so it is unit-testable.
 */
object NsfwPreprocessor {

    const val INPUT_DIM = 224
    const val INPUT_FLOATS = INPUT_DIM * INPUT_DIM * 3

    /** Output order of the model (see third_party/viddexa_nsfw_detection_2_nano/class_labels.txt). */
    val CLASS_LABELS = listOf("safe", "hentai", "porn", "sexy", "drawing")

    /**
     * How a frame or crop is resized to the model input: **one nearest-neighbour
     * step** straight to [INPUT_DIM]×[INPUT_DIM] (`Bitmap.createScaledBitmap(…,
     * filter = false)`). This model was trained on nearest-neighbour resizes: with
     * it, it separates ordinary photos of people from revealing ones at AUROC 0.995
     * versus 0.985 with a smooth (area-averaged) downscale, and fires on 0.5% vs 2.8%
     * of ordinary people photos (README "Model").
     */
    const val RESIZE_FILTER = false

    /**
     * Converts [pixels] (ARGB ints, [INPUT_DIM]×[INPUT_DIM], row-major — as from
     * `Bitmap.getPixels`) into [out] as NHWC floats: R, G, B each in 0..1.
     */
    fun toInput(pixels: IntArray, out: FloatArray = FloatArray(INPUT_FLOATS)): FloatArray {
        require(pixels.size == INPUT_DIM * INPUT_DIM) { "expected ${INPUT_DIM}x$INPUT_DIM pixels" }
        require(out.size == INPUT_FLOATS) { "output must hold $INPUT_FLOATS floats" }
        var o = 0
        for (p in pixels) {
            out[o++] = ((p shr 16) and 0xFF) / 255f
            out[o++] = ((p shr 8) and 0xFF) / 255f
            out[o++] = (p and 0xFF) / 255f
        }
        return out
    }

    /** Parses the model's 5-way softmax output (order: [CLASS_LABELS]). */
    fun scoresFromOutput(output: FloatArray): NsfwScores {
        require(output.size == CLASS_LABELS.size) { "expected ${CLASS_LABELS.size} class scores, got ${output.size}" }
        return NsfwScores(
            safe = output[0],
            hentai = output[1],
            porn = output[2],
            sexy = output[3],
            drawing = output[4]
        )
    }

    /**
     * Sizes to step through when shrinking a [width]×[height] image to
     * [target]×[target] with filtering: halve each dimension while it is more than
     * twice the target, then one final step — an approximate area average. No longer
     * used for the model input (see [RESIZE_FILTER]); used for the small, filtered
     * downsample of the blank-crop check ([RegionContent]).
     */
    fun downscaleSteps(width: Int, height: Int, target: Int = INPUT_DIM): List<Pair<Int, Int>> {
        require(width > 0 && height > 0 && target > 0)
        val steps = mutableListOf<Pair<Int, Int>>()
        var w = width
        var h = height
        while (w > 2 * target || h > 2 * target) {
            if (w > 2 * target) w /= 2
            if (h > 2 * target) h /= 2
            steps += w to h
        }
        if (w != target || h != target) steps += target to target
        return steps
    }
}
