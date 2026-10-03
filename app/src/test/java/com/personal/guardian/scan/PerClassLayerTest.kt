package com.personal.guardian.scan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the additive per-class layer. */
class PerClassLayerTest {

    private fun scores(safe: Float = 0f, hentai: Float = 0f, porn: Float = 0f, sexy: Float = 0f, drawing: Float = 0f) =
        NsfwScores(safe = safe, hentai = hentai, porn = porn, sexy = sexy, drawing = drawing)

    private val safeFrame = scores(safe = 1f)
    private val sexyThr = 0.40f

    @Test
    fun pornAtOrAboveThresholdOnWholeScreenIsImmediate() {
        val v = PerClassLayer.evaluate(scores(porn = 0.41f), emptyList(), sexyThr)
        assertTrue(v.pornImmediate)
        assertFalse(v.suggestive)
    }

    @Test
    fun pornOnARegionIsImmediateToo() {
        val v = PerClassLayer.evaluate(safeFrame, listOf(scores(porn = 0.55f)), sexyThr)
        assertTrue(v.pornImmediate)
    }

    @Test
    fun pornBelowThresholdIsNotImmediate() {
        val v = PerClassLayer.evaluate(scores(porn = 0.39f), listOf(scores(porn = 0.30f)), sexyThr)
        assertFalse(v.pornImmediate)
    }

    @Test
    fun sexyAtOrAboveSensitivityIsSuggestiveNotImmediate() {
        val v = PerClassLayer.evaluate(scores(sexy = 0.40f), emptyList(), sexyThr)
        assertTrue(v.suggestive)
        assertFalse(v.pornImmediate)
        // High sensitivity (0.30) catches a lower sexy score.
        assertTrue(PerClassLayer.evaluate(scores(sexy = 0.31f), emptyList(), sexyThreshold = 0.30f).suggestive)
        assertFalse(PerClassLayer.evaluate(scores(sexy = 0.31f), emptyList(), sexyThreshold = 0.40f).suggestive)
    }

    @Test
    fun hentaiIsSuggestiveOnlyOnRegionsNeverTheWholeScreen() {
        // Whole-screen hentai (the model floods UI with hentai) must NOT trigger.
        assertFalse(PerClassLayer.evaluate(scores(hentai = 0.99f), emptyList(), sexyThr).suggestive)
        // A region at/above the region hentai threshold is suggestive.
        assertTrue(PerClassLayer.evaluate(safeFrame, listOf(scores(hentai = 0.70f)), sexyThr).suggestive)
        assertFalse(PerClassLayer.evaluate(safeFrame, listOf(scores(hentai = 0.69f)), sexyThr).suggestive)
    }

    @Test
    fun suspectFloorIsReportedButDoesNotLock() {
        val v = PerClassLayer.evaluate(scores(sexy = 0.26f), emptyList(), sexyThr)
        assertTrue(v.suspect)
        assertFalse(v.pornImmediate)
        assertFalse(v.suggestive)
        // Whole-screen hentai does not even count as suspect (UI noise), but a region does.
        assertFalse(PerClassLayer.evaluate(scores(hentai = 0.90f), emptyList(), sexyThr).suspect)
        assertTrue(PerClassLayer.evaluate(safeFrame, listOf(scores(hentai = 0.26f)), sexyThr).suspect)
    }

    @Test
    fun aSafeFrameTriggersNothing() {
        val v = PerClassLayer.evaluate(safeFrame, listOf(scores(safe = 0.98f)), sexyThr)
        assertFalse(v.pornImmediate)
        assertFalse(v.suggestive)
        assertFalse(v.suspect)
    }

    @Test
    fun defaultsComeFromScanConfig() {
        // porn immediate default 0.40, hentai region default 0.70.
        assertTrue(PerClassLayer.evaluate(scores(porn = ScanConfig.PORN_IMMEDIATE_THRESHOLD), emptyList(), sexyThr).pornImmediate)
        assertTrue(PerClassLayer.evaluate(safeFrame, listOf(scores(hentai = ScanConfig.HENTAI_SUGGESTIVE_THRESHOLD)), sexyThr).suggestive)
    }
}
