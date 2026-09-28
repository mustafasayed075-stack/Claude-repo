package com.personal.guardian.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Pure-JVM tests for the viddexa-nano model's input conversion (RGB, 0..1, nearest
 * resize), output parsing (class order safe/hentai/porn/sexy/drawing), the two
 * signals (regions: sexy+porn+hentai; whole screen: hentai gated at 0.95), the
 * filtered downscale plan still used by the blank-crop check, and the bundled model
 * file and its licence.
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
        // Model order: safe, hentai, porn, sexy, drawing.
        val s = NsfwPreprocessor.scoresFromOutput(floatArrayOf(0.30f, 0.02f, 0.07f, 0.60f, 0.01f))
        assertEquals(0.30f, s.safe)
        assertEquals(0.02f, s.hentai)
        assertEquals(0.07f, s.porn)
        assertEquals(0.60f, s.sexy)
        assertEquals(0.01f, s.drawing)
    }

    @Test
    fun regionSignalIsSexyPlusPornPlusHentai() {
        // Suggestive photo: driven by "sexy".
        val suggestive = NsfwScores(safe = 0.39f, hentai = 0.00f, porn = 0.02f, sexy = 0.58f, drawing = 0.01f)
        assertEquals(0.60f, suggestive.signal, 1e-6f)
        // Explicit photo: "sexy" is low because the probability went to "porn" — still counts.
        val explicit = NsfwScores(safe = 0.01f, hentai = 0.01f, porn = 0.95f, sexy = 0.03f, drawing = 0.00f)
        assertEquals(0.99f, explicit.signal, 1e-6f)
        assertTrue(explicit.signal >= ScanConfig.REGION_THRESHOLD)
        // Ordinary photo: safe dominates.
        val ordinary = NsfwScores(safe = 0.984f, hentai = 0.001f, porn = 0.000f, sexy = 0.006f, drawing = 0.009f)
        assertTrue(ordinary.signal < ScanConfig.REGION_THRESHOLD)
        // Drawings (non-explicit) don't count; hentai does, in regions.
        assertEquals(0f, NsfwScores(safe = 0f, hentai = 0f, porn = 0f, sexy = 0f, drawing = 1f).signal)
        assertEquals(0.8f, NsfwScores(safe = 0.1f, hentai = 0.8f, porn = 0f, sexy = 0f, drawing = 0.1f).signal, 1e-6f)
    }

    @Test
    fun screenSignalCountsHentaiOnlyWhenConfident() {
        assertEquals(0.95f, ScanConfig.SCREEN_HENTAI_GATE)
        // A UI/text screen read as a drawing leaking into hentai: not counted.
        val uiScreen = NsfwScores(safe = 0.13f, hentai = 0.73f, porn = 0.00f, sexy = 0.00f, drawing = 0.14f)
        assertEquals(0f, uiScreen.screenSignal, 1e-6f)
        assertTrue(uiScreen.screenSignal < ScanConfig.NSFW_THRESHOLD)
        // Confident drawn explicit content: counted.
        val hentai = NsfwScores(safe = 0.01f, hentai = 0.97f, porn = 0.01f, sexy = 0.00f, drawing = 0.01f)
        assertEquals(0.98f, hentai.screenSignal, 1e-6f)
        // Photos: sexy + porn as usual.
        val suggestive = NsfwScores(safe = 0.60f, hentai = 0.02f, porn = 0.10f, sexy = 0.26f, drawing = 0.02f)
        assertEquals(0.36f, suggestive.screenSignal, 1e-6f)
        assertTrue(suggestive.screenSignal >= ScanConfig.NSFW_THRESHOLD)
    }

    @Test
    fun breakdownListsEveryClass() {
        val s = NsfwScores(safe = 0.34f, hentai = 0f, porn = 0.031f, sexy = 0.612f, drawing = 0.017f)
        assertEquals("sexy=0.612 porn=0.031 hentai=0.000 safe=0.340 drawing=0.017", s.breakdown())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTwoClassOutputFromOldModel() {
        NsfwPreprocessor.scoresFromOutput(floatArrayOf(0.1f, 0.9f))
    }

    // ---- Resize ----

    @Test
    fun modelInputIsResizedNearestNeighbour() {
        // One unfiltered createScaledBitmap straight to 224x224, as the model was trained.
        assertEquals(false, NsfwPreprocessor.RESIZE_FILTER)
        assertEquals(224, NsfwPreprocessor.INPUT_DIM)
        val classifier = File("src/main/java/com/personal/guardian/scan/NsfwClassifier.kt").readText()
        assertTrue(classifier.contains("Bitmap.createScaledBitmap(src, dim, dim, NsfwPreprocessor.RESIZE_FILTER)"))
        assertFalse("no stepwise filtered downscale for the model input", classifier.contains("downscaleSteps"))
    }

    // ---- Filtered downscale plan (blank-crop check) ----

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
    fun bundledModelIsTheVerifiedViddexaNanoConversion() {
        assertEquals("models/viddexa_nsfw_detection_2_nano_224.tflite", ScanConfig.MODEL_ASSET)
        val model = File("src/main/assets/" + ScanConfig.MODEL_ASSET)
        assertTrue("missing ${model.absolutePath}", model.isFile)
        assertEquals(16_339_368L, model.length())
        val sha = MessageDigest.getInstance("SHA-256").digest(model.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals("87b4701b8a69d771b90d635ef5145df8c92e1c4632da47b270029a15ef4b7fa2", sha)
    }

    @Test
    fun previousModelAndItsLicencesAreGone() {
        assertFalse(File("src/main/assets/models/nsfw_mobilenet_v2_140_224.tflite").exists())
        assertFalse(File("../third_party/nsfw_model").exists())
        assertEquals(listOf("viddexa_nsfw_detection_2_nano_224.tflite"), File("src/main/assets/models").list()!!.sorted())
    }

    @Test
    fun classOrderMatchesTheModelsLabelFileAndLicenceIsBundled() {
        val dir = File("../third_party/viddexa_nsfw_detection_2_nano")
        val labels = File(dir, "class_labels.txt").readLines().filter { it.isNotBlank() }
        assertEquals(labels, NsfwPreprocessor.CLASS_LABELS)
        assertEquals(listOf("safe", "hentai", "porn", "sexy", "drawing"), NsfwPreprocessor.CLASS_LABELS)
        assertTrue(File(dir, "LICENSE-Apache-2.0.txt").readText().contains("Apache License"))
        assertTrue(File(dir, "README.md").readText().contains("87b4701b8a69d771b90d635ef5145df8c92e1c4632da47b270029a15ef4b7fa2"))
    }
}
