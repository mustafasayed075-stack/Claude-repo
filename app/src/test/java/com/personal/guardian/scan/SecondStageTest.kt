package com.personal.guardian.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * The second stage (README "Second stage"): NudeNet's post-processing reproduced in
 * Kotlin, the decision table, the shadow log line, and the bundled model and licences.
 */
class SecondStageTest {

    private fun det(part: BodyPart, score: Float) = BodyPartDetection(part, score, 10f, 10f, 50f, 50f)
    private fun surface(name: String, vararg d: BodyPartDetection) = SecondStage.Surface(name, d.toList())
    private fun decide(trigger: SecondStage.Trigger, vararg s: SecondStage.Surface) = SecondStage.decide(trigger, s.toList())

    // ---- decoder: same detections as nudenet.py on the same raw outputs ----

    private fun floats(file: File, from: Int, count: Int): FloatArray {
        val buf = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(count) { buf.getFloat((from + it) * 4) }
    }

    @Test
    fun decoderMatchesNudeNetsOwnPostProcessing() {
        val dir = File("src/test/resources/stage2")
        val expected = File(dir, "expected.tsv").readLines().filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t') }.groupBy { it[0] }
        assertEquals(3, expected.size)
        val a = BodyPartDecoder.ANCHORS
        for ((case, rows) in expected) {
            val bin = File(dir, "$case.bin")
            val got = BodyPartDecoder.decode(floats(bin, 0, 4 * a), floats(bin, 4 * a, BodyPartDecoder.CLASSES * a))
            assertEquals("$case: detection count", rows.size, got.size)
            for ((d, r) in got.zip(rows)) {
                assertEquals(case, r[1], d.part.name)
                assertEquals(case, r[2].toFloat(), d.score, 1e-5f)
                assertEquals(case, r[3].toFloat(), d.left, 0.01f)
                assertEquals(case, r[4].toFloat(), d.top, 0.01f)
                assertEquals(case, r[5].toFloat(), d.width, 0.01f)
                assertEquals(case, r[6].toFloat(), d.height, 0.01f)
            }
        }
    }

    @Test
    fun decoderAnchorsAndThresholds() {
        assertEquals(2100, BodyPartDecoder.ANCHORS)
        val a = BodyPartDecoder.ANCHORS
        val dist = FloatArray(4 * a) { 2f } // 2 grid units each way: a 4x4-cell box around each anchor
        val cls = FloatArray(BodyPartDecoder.CLASSES * a)
        // Anchor 0 (stride 8, centre 4,4): FACE_MALE 0.9 → box 32x32 at (-12,-12).
        cls[BodyPart.FACE_MALE.ordinal * a + 0] = 0.9f
        // Anchor 1 (centre 12,4) overlaps it with IoU 0.6 > 0.45 → suppressed, though
        // another class (NMS is class-agnostic, like NudeNet's).
        cls[BodyPart.FEET_COVERED.ordinal * a + 1] = 0.8f
        // Last anchor (stride 32, centre 304,304): below the 0.2 floor → dropped.
        cls[BodyPart.BELLY_EXPOSED.ordinal * a + a - 1] = 0.19f
        // An isolated anchor at 0.22: kept by the 0.2 floor, dropped by NMS's 0.25.
        cls[BodyPart.FEET_EXPOSED.ordinal * a + 1000] = 0.22f
        val out = BodyPartDecoder.decode(dist, cls)
        assertEquals(1, out.size)
        val d = out.single()
        assertEquals(BodyPart.FACE_MALE, d.part)
        assertEquals(-12f, d.left, 1e-4f); assertEquals(-12f, d.top, 1e-4f)
        assertEquals(32f, d.width, 1e-4f); assertEquals(32f, d.height, 1e-4f)
    }

    // ---- decision table ----

    @Test
    fun anExposedPartConfirmsEitherTrigger() {
        for (t in SecondStage.Trigger.entries) {
            val r = decide(t, surface("crop", det(BodyPart.FACE_FEMALE, 0.9f), det(BodyPart.FEMALE_BREAST_EXPOSED, 0.62f)))
            assertEquals(SecondStage.Decision.CONFIRM, r.decision)
            assertEquals(BodyPart.FEMALE_BREAST_EXPOSED, r.exposed!!.detection.part)
            assertEquals("crop", r.exposed!!.surface)
        }
        for (p in SecondStage.EXPOSED) {
            assertEquals(p.name, SecondStage.Decision.CONFIRM, decide(SecondStage.Trigger.EXPLICIT, surface("full", det(p, 0.45f))).decision)
        }
    }

    @Test
    fun aFaceAloneIsVetoed() {
        // The TikTok case: a close-up face (and a 0.34 false part, below the threshold).
        val r = decide(
            SecondStage.Trigger.EXPLICIT,
            surface("crop", det(BodyPart.FACE_MALE, 0.84f)),
            surface("full", det(BodyPart.FACE_FEMALE, 0.81f), det(BodyPart.MALE_GENITALIA_EXPOSED, 0.34f))
        )
        assertEquals(SecondStage.Decision.VETO, r.decision)
        assertEquals(0.34f, r.exposed!!.detection.score, 0f)
        assertEquals(SecondStage.Decision.VETO, decide(SecondStage.Trigger.EXPLICIT, surface("full")).decision)
    }

    @Test
    fun anExposedMaleChestIsNotExplicit() {
        assertFalse(BodyPart.MALE_BREAST_EXPOSED in SecondStage.EXPOSED)
        assertFalse(BodyPart.MALE_BREAST_EXPOSED in SecondStage.COVERED)
        assertEquals(SecondStage.Decision.VETO, decide(SecondStage.Trigger.EXPLICIT, surface("full", det(BodyPart.MALE_BREAST_EXPOSED, 0.95f))).decision)
        assertEquals(SecondStage.Decision.VETO, decide(SecondStage.Trigger.SUGGESTIVE, surface("full", det(BodyPart.MALE_BREAST_EXPOSED, 0.95f))).decision)
    }

    @Test
    fun coveredIntimatePartsDowngradeAnExplicitTriggerAndConfirmASuggestiveOne() {
        val s = surface("full", det(BodyPart.FEMALE_BREAST_COVERED, 0.7f))
        assertEquals(SecondStage.Decision.DOWNGRADE, decide(SecondStage.Trigger.EXPLICIT, s).decision)
        assertEquals(SecondStage.Decision.CONFIRM, decide(SecondStage.Trigger.SUGGESTIVE, s).decision)
        for (p in SecondStage.COVERED) {
            assertEquals(SecondStage.Decision.CONFIRM, decide(SecondStage.Trigger.SUGGESTIVE, surface("full", det(p, 0.5f))).decision)
        }
    }

    @Test
    fun facesFeetArmpitsAndBelliesNeverCount() {
        val ordinary = listOf(
            BodyPart.FACE_FEMALE, BodyPart.FACE_MALE, BodyPart.FEET_EXPOSED, BodyPart.FEET_COVERED, BodyPart.ARMPITS_EXPOSED,
            BodyPart.ARMPITS_COVERED, BodyPart.BELLY_EXPOSED, BodyPart.BELLY_COVERED, BodyPart.ANUS_COVERED
        )
        val r = decide(SecondStage.Trigger.SUGGESTIVE, surface("full", *ordinary.map { det(it, 0.99f) }.toTypedArray()))
        assertEquals(SecondStage.Decision.VETO, r.decision)
    }

    @Test
    fun thresholdsAreInclusiveAndTheBestSurfaceWins() {
        assertEquals(0.45f, ScanConfig.STAGE2_EXPOSED_THRESHOLD, 0f)
        assertEquals(0.50f, ScanConfig.STAGE2_COVERED_THRESHOLD, 0f)
        assertEquals(SecondStage.Decision.VETO, decide(SecondStage.Trigger.EXPLICIT, surface("full", det(BodyPart.BUTTOCKS_EXPOSED, 0.449f))).decision)
        assertEquals(SecondStage.Decision.VETO, decide(SecondStage.Trigger.SUGGESTIVE, surface("full", det(BodyPart.BUTTOCKS_COVERED, 0.499f))).decision)
        val r = decide(
            SecondStage.Trigger.EXPLICIT,
            surface("crop", det(BodyPart.BUTTOCKS_EXPOSED, 0.30f)),
            surface("full", det(BodyPart.BUTTOCKS_EXPOSED, 0.20f)),
            surface("region", det(BodyPart.BUTTOCKS_EXPOSED, 0.70f))
        )
        assertEquals(SecondStage.Decision.CONFIRM, r.decision)
        assertEquals("region", r.exposed!!.surface)
    }

    // ---- shadow log line ----

    @Test
    fun shadowLineCarriesDecisionScoresTimesAndSaysTheLockIsUnchanged() {
        val crop = surface("crop", det(BodyPart.FACE_MALE, 0.84f))
        val full = surface("full", det(BodyPart.FACE_FEMALE, 0.81f), det(BodyPart.MALE_GENITALIA_EXPOSED, 0.34f))
        val result = SecondStage.decide(SecondStage.Trigger.EXPLICIT, listOf(crop, full))
        val line = ScanLog.stage2Line("per-class porn", SecondStage.Trigger.EXPLICIT, result, listOf(crop to 74L, full to 69L), 150L, "com.zhiliaoapp.musically")
        assertEquals(
            "STAGE2 shadow: would VETO first-stage lock=per-class porn (explicit) exposed=MALE_GENITALIA_EXPOSED 0.340@full " +
                "covered=none ms=150 [crop 74 ms: FACE_MALE 0.840; full 69 ms: FACE_FEMALE 0.810, MALE_GENITALIA_EXPOSED 0.340] " +
                "app=com.zhiliaoapp.musically — lock unchanged (shadow).",
            line
        )
        val slow = ScanLog.stage2Line("confirmed", SecondStage.Trigger.EXPLICIT, result, listOf(full to 1600L), 1600L, null)
        assertTrue(slow.contains("ms=1600 (over the 1500 ms budget: enforcement would keep the first-stage lock)"))
        assertTrue(slow.contains("[full 1600 ms: "))
    }

    // ---- Build 48 is shadow only; model and licences ----

    @Test
    fun build48DoesNotEnforce() {
        assertFalse("Build 48 is shadow mode: locks must not change", ScanConfig.STAGE2_ENFORCE)
    }

    @Test
    fun theBundledModelIsTheConvertedNudeNet320nFp16() {
        val f = File("src/main/assets/models/nudenet_320n_fp16.tflite")
        assertEquals(6_084_680L, f.length())
        val sha = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals("ce1befc5d38137a1cf5a5dab4964c79aca688c8ff3ba4d0aee83b7fac8be9c52", sha)
        assertEquals("models/nudenet_320n_fp16.tflite", ScanConfig.STAGE2_MODEL_ASSET)
    }

    @Test
    fun classOrderMatchesNudeNetAndLicencesAreThere() {
        val labels = File("../third_party/nudenet/class_labels.txt").readLines().filter { it.isNotBlank() }
        assertEquals(BodyPart.entries.map { it.name }, labels)
        assertTrue(File("../third_party/nudenet/LICENSE-AGPL-3.0.txt").readText().contains("GNU AFFERO GENERAL PUBLIC LICENSE"))
        val root = File("../LICENSE").readText()
        assertTrue(root.contains("GNU AFFERO GENERAL PUBLIC LICENSE") && root.contains("Version 3, 19 November 2007"))
    }
}
