package com.personal.guardian.scan

import java.util.Locale

/**
 * Per-class probabilities from the GantMan nsfw_model (softmax, sum ≈ 1).
 *
 * [signal] is what the confirmation threshold is applied to: the combined
 * probability of the three not-safe classes. On suggestive photos (swimwear,
 * lingerie…) it is essentially [sexy], since porn/hentai stay near 0; on explicit
 * content the probability moves to [porn]/[hentai], which a sexy-only score would
 * miss, so those count too.
 */
data class NsfwScores(
    val drawings: Float,
    val hentai: Float,
    val neutral: Float,
    val porn: Float,
    val sexy: Float
) {
    val signal: Float get() = (sexy + porn + hentai).coerceIn(0f, 1f)

    /** Compact breakdown for logs, e.g. `sexy=0.612 porn=0.031 hentai=0.002 neutral=0.340 drawings=0.015`. */
    fun breakdown(): String = String.format(
        Locale.US, "sexy=%.3f porn=%.3f hentai=%.3f neutral=%.3f drawings=%.3f",
        sexy, porn, hentai, neutral, drawings
    )
}

/**
 * Pixel → model-input conversion and output parsing for the bundled GantMan
 * nsfw_model (MobileNetV2 140 224), matching its reference `predict.py`:
 * RGB, 224×224, each channel scaled to 0..1 (`/ 255`).
 *
 * Pure Kotlin, so it is unit-testable.
 */
object NsfwPreprocessor {

    const val INPUT_DIM = 224
    const val INPUT_FLOATS = INPUT_DIM * INPUT_DIM * 3

    /** Output order of the model (see third_party/nsfw_model/class_labels.txt). */
    val CLASS_LABELS = listOf("drawings", "hentai", "neutral", "porn", "sexy")

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
            drawings = output[0],
            hentai = output[1],
            neutral = output[2],
            porn = output[3],
            sexy = output[4]
        )
    }

    /**
     * Sizes to step through when shrinking a [width]×[height] frame to
     * [target]×[target]: halve each dimension while it is more than twice the target,
     * then one final step to the target. Each halving with bilinear filtering
     * averages 2×2 blocks, so the result approximates an area-averaged resize.
     * A single big filtered scale (e.g. 1080×2400 → 224) only samples a few pixels
     * per output pixel and aliases — which the model misreads (a plain logo scored
     * 0.65 "hentai" that way in testing).
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
