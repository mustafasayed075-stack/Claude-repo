package com.personal.guardian.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Pure-JVM tests for the GantMan model's input conversion (RGB, 0..1), output
 * parsing (class order, signal), the anti-aliasing downscale plan, and the bundled
 * model file itself.
 */
class NsfwPreprocessorTest {

    private val dim = NsfwPreprocessor.INPUT_DIM

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    // ---- Input ----

    @Test
    fun convertsToRgbScaledToUnitRange() {
        val out = NsfwPreprocessor.toInput(IntArray(dim * dim) { argb(255, 102, 0) })
        assertEquals(NsfwPreprocessor.INPUT_FLOATS, out.size)
        assertEquals(1f, out[0], 1e-6f)          // R first (not BGR)
        assertEquals(0.4f, out[1], 1e-6f)        // G
        assertEquals(0f, out[2], 1e-6f)          // B
        assertTrue("no mean subtraction", out.all { it in 0f..1f })
    }

    @Test
    fun keepsRowMajorPixelOrderWithoutCropping() {
        // Colour encodes position: R = x, G = y (full 224x224 frame is used, no crop).
        val out = NsfwPreprocessor.toInput(IntArray(dim * dim) { i -> argb(i % dim, i / dim, 0) })
        fun rAt(x: Int, y: Int) = out[(y * dim + x) * 3] * 255f
        fun gAt(x: Int, y: Int) = out[(y * dim + x) * 3 + 1] * 255f
        assertEquals(0f, rAt(0, 0), 1e-3f)
        assertEquals(223f, rAt(223, 5), 1e-3f)
        assertEquals(5f, gAt(223, 5), 1e-3f)
        assertEquals(223f, gAt(0, 223), 1e-3f)
    }

    @Test
    fun ignoresAlpha() {
        val opaque = NsfwPreprocessor.toInput(IntArray(dim * dim) { argb(10, 20, 30) })
        val clear = NsfwPreprocessor.toInput(IntArray(dim * dim) { argb(10, 20, 30) and 0x00FFFFFF })
        assertEquals(opaque.toList(), clear.toList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWrongInputSize() {
        NsfwPreprocessor.toInput(IntArray(256 * 256))
    }

    // ---- Output ----

    @Test
    fun parsesClassesInModelOrder() {
        val s = NsfwPreprocessor.scoresFromOutput(floatArrayOf(0.01f, 0.02f, 0.30f, 0.07f, 0.60f))
        assertEquals(0.01f, s.drawings)
        assertEquals(0.02f, s.hentai)
        assertEquals(0.30f, s.neutral)
        assertEquals(0.07f, s.porn)
        assertEquals(0.60f, s.sexy)
    }

    @Test
    fun signalIsSexyPlusPornPlusHentai() {
        // Suggestive photo: driven by "sexy".
        val suggestive = NsfwScores(drawings = 0.01f, hentai = 0.00f, neutral = 0.39f, porn = 0.02f, sexy = 0.58f)
        assertEquals(0.60f, suggestive.signal, 1e-6f)
        // Explicit photo: "sexy" is low because the probability went to "porn" — still counts.
        val explicit = NsfwScores(drawings = 0.00f, hentai = 0.01f, neutral = 0.01f, porn = 0.95f, sexy = 0.03f)
        assertEquals(0.99f, explicit.signal, 1e-6f)
        assertTrue(explicit.signal >= ScanConfig.NSFW_THRESHOLD)
        // Ordinary photo: neutral dominates.
        val ordinary = NsfwScores(drawings = 0.008f, hentai = 0.001f, neutral = 0.984f, porn = 0.000f, sexy = 0.006f)
        assertTrue(ordinary.signal < ScanConfig.NSFW_THRESHOLD)
        // Drawings don't count.
        assertEquals(0f, NsfwScores(drawings = 1f, hentai = 0f, neutral = 0f, porn = 0f, sexy = 0f).signal)
    }

    @Test
    fun breakdownListsEveryClass() {
        val s = NsfwScores(drawings = 0.017f, hentai = 0f, neutral = 0.34f, porn = 0.031f, sexy = 0.612f)
        assertEquals("sexy=0.612 porn=0.031 hentai=0.000 neutral=0.340 drawings=0.017", s.breakdown())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTwoClassOutputFromOldModel() {
        NsfwPreprocessor.scoresFromOutput(floatArrayOf(0.1f, 0.9f))
    }

    // ---- Downscale plan ----

    @Test
    fun phoneScreenIsHalvedStepwiseBeforeFinalResize() {
        assertEquals(
            listOf(540 to 1200, 270 to 600, 270 to 300, 224 to 224),
            NsfwPreprocessor.downscaleSteps(1080, 2400)
        )
    }

    @Test
    fun everyStepShrinksByAtMostHalf() {
        for ((w0, h0) in listOf(1080 to 2400, 1440 to 3200, 720 to 1600, 2400 to 1080, 300 to 300, 100 to 50)) {
            var w = w0
            var h = h0
            for ((nw, nh) in NsfwPreprocessor.downscaleSteps(w0, h0)) {
                assertTrue("${w0}x$h0: $w->$nw", nw * 2 >= w || nw == dim)
                assertTrue("${w0}x$h0: $h->$nh", nh * 2 >= h || nh == dim)
                w = nw; h = nh
            }
            assertEquals(dim to dim, w to h)
        }
    }

    @Test
    fun exactSizeNeedsNoSteps() {
        assertEquals(emptyList<Pair<Int, Int>>(), NsfwPreprocessor.downscaleSteps(224, 224))
    }

    // ---- Bundled model ----

    @Test
    fun bundledModelIsTheVerifiedGantManFile() {
        val model = File("src/main/assets/" + ScanConfig.MODEL_ASSET)
        assertTrue("missing ${model.absolutePath}", model.isFile)
        assertEquals(17_355_548L, model.length())
        val sha = MessageDigest.getInstance("SHA-256").digest(model.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals("380f98f7685f9d8a386f8cc595b6dfcb972989aae3d1b8b270d3a4a5b96fab40", sha)
    }

    @Test
    fun classOrderMatchesTheModelsLabelFile() {
        val labels = File("../third_party/nsfw_model/class_labels.txt").readLines().filter { it.isNotBlank() }
        assertEquals(labels, NsfwPreprocessor.CLASS_LABELS)
    }
}
