package com.personal.guardian.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build 49 shadow comparison: the first stage if the letterbox crop applied only when the
 * trimmed part is really black (≥ 70 % of its cells). Never locks; only reports.
 */
class StrictCropShadowTest {

    private fun s(safe: Float = 0f, porn: Float = 0f, sexy: Float = 0f, hentai: Float = 0f, drawing: Float = 0f) =
        NsfwScores(safe = safe, hentai = hentai, porn = porn, sexy = sexy, drawing = drawing)

    private val thr = ScanConfig.SUGGESTIVE_THRESHOLD_NORMAL

    // The Build 47 log: a TikTok screenshot in the gallery (com.coloros.gallery3d).
    private val galleryCropped = s(safe = 0.02f, porn = 0.978f)
    private val galleryUncropped = s(safe = 0.947f, porn = 0.007f)

    @Test
    fun galleryTikTokScreenshotRegression() {
        // Now: the crop's porn 0.978 is a one-frame per-class lock.
        val now = PerClassLayer.evaluate(galleryCropped, emptyList(), thr)
        assertTrue(now.pornImmediate)
        // Strict: the trimmed part (a dark graphic, gallery chrome) isn't black → no crop → no lock.
        val shadow = StrictCropShadow()
        val o = shadow.onFrame(1_000, TriggerSource.EVENT, trimmedBlack = 0.31, cropped = galleryCropped, uncropped = { galleryUncropped }, regions = emptyList(), suggestiveThreshold = thr)
        assertFalse(o.keepsCrop)
        assertEquals(galleryUncropped, o.whole)
        assertNull(o.lockedBy)
        // The same frame again 1.5 s later still doesn't lock (confirmers stay empty).
        assertNull(shadow.onFrame(2_500, TriggerSource.PERIODIC, 0.31, galleryCropped, { galleryUncropped }, emptyList(), thr).lockedBy)
    }

    @Test
    fun aRealLetterboxKeepsTheCropAndTheSameDecision() {
        var uncroppedAsked = false
        val o = StrictCropShadow().onFrame(1_000, TriggerSource.EVENT, 0.92, s(porn = 0.90f), { uncroppedAsked = true; s(safe = 0.9f) }, emptyList(), thr)
        assertTrue(o.keepsCrop)
        assertEquals("per-class porn", o.lockedBy)
        assertFalse("no extra inference when the crop is kept", uncroppedAsked)
        assertTrue(StrictCropShadow().onFrame(0, TriggerSource.EVENT, 0.70, s(porn = 0.9f), { s() }, emptyList(), thr).keepsCrop)
        assertFalse(StrictCropShadow().onFrame(0, TriggerSource.EVENT, 0.699, s(porn = 0.9f), { s() }, emptyList(), thr).keepsCrop)
    }

    @Test
    fun noCropMeansTheSameSurfaceAndNoExtraInference() {
        var uncroppedAsked = false
        val o = StrictCropShadow().onFrame(1_000, TriggerSource.EVENT, null, s(safe = 0.99f), { uncroppedAsked = true; s() }, emptyList(), thr)
        assertTrue(o.keepsCrop)
        assertNull(o.lockedBy)
        assertFalse(uncroppedAsked)
    }

    @Test
    fun itHasItsOwnTwoFrameConfirmation() {
        val shadow = StrictCropShadow()
        val weak = s(safe = 0.8f, porn = 0.16f) // screen signal 0.16 ≥ 0.15, below every one-frame rule
        assertNull(shadow.onFrame(1_000, TriggerSource.EVENT, null, weak, { weak }, emptyList(), thr).lockedBy)
        assertEquals("confirmed", shadow.onFrame(2_500, TriggerSource.PERIODIC, null, weak, { weak }, emptyList(), thr).lockedBy)
        shadow.reset()
        assertNull(shadow.onFrame(3_000, TriggerSource.PERIODIC, null, weak, { weak }, emptyList(), thr).lockedBy)
    }

    @Test
    fun theLogLineShowsBothDecisionsAndSaysTheLockIsUnchanged() {
        val o = StrictCropShadow().onFrame(1_000, TriggerSource.EVENT, 0.31, galleryCropped, { galleryUncropped }, emptyList(), thr)
        val line = StrictCropShadow.line("crop=[0,1155,1080,2110] of 1080x2412", 0.31, galleryCropped, "per-class porn", o, "com.coloros.gallery3d")
        assertEquals(
            "Strict-crop shadow: crop=[0,1155,1080,2110] of 1080x2412 trimmed black=0.31 (< 0.70: no crop) " +
                "now[${galleryCropped.breakdown()}] → lock=per-class porn; strict[${galleryUncropped.breakdown()}] → no lock " +
                "app=com.coloros.gallery3d — lock unchanged (shadow).",
            line
        )
    }

    @Test
    fun lockedByFollowsTheScannersOrder() {
        assertEquals("confirmed", FirstStageLock.lockedBy(true, true, true))
        assertEquals("per-class porn", FirstStageLock.lockedBy(false, true, true))
        assertEquals("suggestive", FirstStageLock.lockedBy(false, false, true))
        assertNull(FirstStageLock.lockedBy(false, false, false))
    }

    // ---- LetterboxCrop.trimmedBlackFraction ----

    @Test
    fun trimmedBlackFractionTellsALetterboxFromADarkGraphic() {
        val w = 10; val h = 10
        val rect = LetterboxCrop.GridRect(0, 4, 10, 6) // the lower 6 rows kept
        val letterbox = IntArray(w * h) { if (it / w < 4) 0 else 150 } // black bands above
        assertEquals(1.0, LetterboxCrop.trimmedBlackFraction(letterbox, w, h, rect), 1e-9)
        val darkGraphic = IntArray(w * h) { if (it / w < 4) 25 else 150 } // dark brown (luma 25 > 16), not black
        assertEquals(0.0, LetterboxCrop.trimmedBlackFraction(darkGraphic, w, h, rect), 1e-9)
        val mixed = IntArray(w * h) { if (it / w < 3) 5 else if (it / w < 4) 200 else 150 } // 3 black rows, 1 UI row
        assertEquals(0.75, LetterboxCrop.trimmedBlackFraction(mixed, w, h, rect), 1e-9)
        assertEquals(0.0, LetterboxCrop.trimmedBlackFraction(mixed, w, h, LetterboxCrop.GridRect(0, 0, 10, 10)), 1e-9)
    }
}
