package com.personal.guardian.scan

/**
 * Pixel → model-input conversion for the OpenNSFW model, matching the reference
 * "Yahoo" preprocessing (bhky/opennsfw2):
 *   1. resize the image to 256×256 (done by the caller with a filtered bitmap scale),
 *   2. centre-crop 224×224,
 *   3. RGB → BGR, float, subtract the VGG channel means (104, 117, 123).
 *
 * Output layout is NHWC (row-major, 3 floats per pixel), ready to copy into the
 * interpreter's 1×224×224×3 float32 input. Pure Kotlin, so it is unit-testable.
 */
object NsfwPreprocessor {

    const val RESIZE_DIM = 256
    const val INPUT_DIM = 224
    const val INPUT_FLOATS = INPUT_DIM * INPUT_DIM * 3

    private const val MEAN_B = 104f
    private const val MEAN_G = 117f
    private const val MEAN_R = 123f

    /**
     * Converts [pixels] (ARGB ints, `RESIZE_DIM`×`RESIZE_DIM`, row-major — as from
     * `Bitmap.getPixels`) into [out], which must hold [INPUT_FLOATS] floats.
     */
    fun toInput(pixels: IntArray, out: FloatArray = FloatArray(INPUT_FLOATS)): FloatArray {
        require(pixels.size == RESIZE_DIM * RESIZE_DIM) { "expected ${RESIZE_DIM}x$RESIZE_DIM pixels" }
        require(out.size == INPUT_FLOATS) { "output must hold $INPUT_FLOATS floats" }

        val offset = (RESIZE_DIM - INPUT_DIM) / 2
        var o = 0
        for (y in 0 until INPUT_DIM) {
            val row = (y + offset) * RESIZE_DIM + offset
            for (x in 0 until INPUT_DIM) {
                val p = pixels[row + x]
                out[o++] = (p and 0xFF) - MEAN_B
                out[o++] = ((p shr 8) and 0xFF) - MEAN_G
                out[o++] = ((p shr 16) and 0xFF) - MEAN_R
            }
        }
        return out
    }

    /** The model outputs softmax [sfw, nsfw]; the NSFW probability is the score. */
    fun scoreFromOutput(output: FloatArray): Float {
        require(output.size == 2) { "expected [sfw, nsfw] output, got ${output.size} values" }
        return output[1].coerceIn(0f, 1f)
    }
}
