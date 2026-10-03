package com.personal.guardian.lock

import com.personal.guardian.scan.DetectionBus
import com.personal.guardian.scan.DetectionEvent
import com.personal.guardian.scan.DetectionKind
import com.personal.guardian.scan.DetectionListener
import com.personal.guardian.scan.ScanConfig
import com.personal.guardian.scan.TriggerSource
import com.personal.guardian.text.KeywordTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Stage 5 lock decisions with a fake clock, a fake device lock and fake image-check
 * results — no model, no real locking (pure JVM).
 */
class LockControllerTest {

    private class FakeDevice(var adminActive: Boolean = true, var refuse: Boolean = false) : DeviceLock {
        var locks = 0
        override fun isAdminActive() = adminActive
        override fun lockNow() {
            if (refuse) throw SecurityException("No active admin owned by uid with policy force-lock")
            locks++
        }
    }

    private var now = 100_000L
    private val device = FakeDevice()
    private val lines = ArrayList<Pair<LockLog.Level, String>>()
    private val controller = LockController(device, { level, msg -> lines += level to msg }, { now })

    private fun image(id: String = "image-1") = DetectionEvent(
        timestampMs = 1L, confidence = 0.9f, source = TriggerSource.EVENT, foregroundPackage = "com.whatsapp",
        thumbnailFile = null, id = id
    )

    private fun text(tier: KeywordTier, terms: List<String>, id: String = "text-1") = DetectionEvent(
        timestampMs = 1L, confidence = 1f, source = TriggerSource.TEXT, foregroundPackage = "com.android.chrome",
        thumbnailFile = null, kind = DetectionKind.TEXT, matchedTerms = terms, textTier = tier, id = id
    )

    private fun logged(fragment: String) = lines.any { it.second.contains(fragment) }

    @Test
    fun defaultsAreTheTestPhaseValues() {
        assertEquals(10_000L, ScanConfig.LOCK_DURATION_MS)
        assertEquals(3_000L, ScanConfig.CORROBORATION_WINDOW_MS)
        assertEquals(10_000L, controller.lockDurationMs)
        assertEquals(3_000L, controller.corroborationWindowMs)
    }

    // ---- immediate locks ----

    @Test
    fun imageDetectionLocksImmediately() {
        val out = controller.onDetection(image("image-42"))
        assertEquals(LockOutcome.Locked(LockSource.IMAGE, "image-42", now + 10_000), out)
        assertEquals(1, device.locks)
        assertTrue(controller.isLocked())
        assertTrue(logged("LOCK triggered, duration=10000ms, source=image, detectionId=image-42"))
    }

    @Test
    fun explicitTierTextMatchLocksImmediately() {
        val out = controller.onDetection(text(KeywordTier.EXPLICIT, listOf("porn"), "text-7"))
        assertEquals(LockOutcome.Locked(LockSource.TEXT_EXPLICIT, "text-7", now + 10_000), out)
        assertEquals(1, device.locks)
        assertFalse("no corroboration needed", controller.isCorroborating())
        assertTrue(logged("LOCK triggered, duration=10000ms, source=text-explicit, detectionId=text-7"))
    }

    @Test
    fun aTextEventWithoutATierIsTreatedAsExplicit() {
        val e = text(KeywordTier.EXPLICIT, listOf("porn")).copy(textTier = null)
        assertTrue(controller.onDetection(e) is LockOutcome.Locked)
    }

    // ---- borderline corroboration ----

    @Test
    fun borderlineTextDoesNotLockImmediately() {
        val out = controller.onDetection(text(KeywordTier.BORDERLINE, listOf("lingerie"), "text-9"))
        assertEquals(LockOutcome.CorroborationStarted("text-9", now + 3_000), out)
        assertEquals("device stays usable", 0, device.locks)
        assertFalse(controller.isLocked())
        assertTrue(controller.isCorroborating())
    }

    @Test
    fun borderlineTextLocksWhenAnImageCheckCorroboratesWithinTheWindow() {
        controller.onDetection(text(KeywordTier.BORDERLINE, listOf("lingerie"), "text-9"))
        now += 1_000
        assertEquals(LockOutcome.None, controller.onCorroborationFrame(positive = false, signal = 0.05f))
        assertEquals(0, device.locks)
        now += 1_000
        val out = controller.onCorroborationFrame(positive = true, signal = 0.41f)
        assertEquals(LockOutcome.Locked(LockSource.TEXT_CORROBORATED, "text-9", now + 10_000), out)
        assertEquals(1, device.locks)
        assertFalse(controller.isCorroborating())
        assertTrue(logged("corroborated by image check #2"))
        assertTrue(logged("LOCK triggered, duration=10000ms, source=text-borderline-corroborated, detectionId=text-9"))
        // The deadline passing afterwards changes nothing.
        now += 5_000
        assertEquals(LockOutcome.None, controller.onCorroborationDeadline())
    }

    @Test
    fun borderlineTextDoesNotLockAfterTheWindowExpires() {
        controller.onDetection(text(KeywordTier.BORDERLINE, listOf("thong"), "text-3"))
        now += 1_000
        controller.onCorroborationFrame(positive = false, signal = 0.02f)
        now += 1_000
        controller.onCorroborationFrame(positive = false, signal = 0.03f)
        now += 999
        assertEquals("not yet expired", LockOutcome.None, controller.onCorroborationDeadline())
        now += 1
        assertEquals(LockOutcome.Uncorroborated("text-3"), controller.onCorroborationDeadline())
        assertTrue(logged("text match uncorroborated, no lock (borderline tier, window expired)"))
        // A positive image check after the window doesn't lock for that text match.
        now += 500
        assertEquals(LockOutcome.None, controller.onCorroborationFrame(positive = true, signal = 0.9f))
        assertEquals(0, device.locks)
        assertFalse(controller.isLocked())
    }

    @Test
    fun aPositiveFrameArrivingAfterTheDeadlineExpiresTheWindowInsteadOfLocking() {
        controller.onDetection(text(KeywordTier.BORDERLINE, listOf("corset"), "text-4"))
        now += 3_000 // the deadline timer hasn't run yet
        assertEquals(LockOutcome.Uncorroborated("text-4"), controller.onCorroborationFrame(positive = true, signal = 0.8f))
        assertEquals(0, device.locks)
    }

    @Test
    fun anImageDetectionDuringTheWindowLocksAsImageAndClosesTheWindow() {
        controller.onDetection(text(KeywordTier.BORDERLINE, listOf("lube"), "text-5"))
        now += 500
        val out = controller.onDetection(image("image-6"))
        assertTrue(out is LockOutcome.Locked && out.source == LockSource.IMAGE)
        assertFalse(controller.isCorroborating())
        now += 3_000
        assertEquals("no 'uncorroborated' line for a closed window", LockOutcome.None, controller.onCorroborationDeadline())
        assertFalse(logged("uncorroborated"))
    }

    @Test
    fun aNewBorderlineMatchRestartsTheWindow() {
        controller.onDetection(text(KeywordTier.BORDERLINE, listOf("lingerie"), "text-1"))
        now += 2_000
        val out = controller.onDetection(text(KeywordTier.BORDERLINE, listOf("thong"), "text-2"))
        assertEquals(LockOutcome.CorroborationStarted("text-2", now + 3_000), out)
        now += 2_000
        assertTrue("still within the new window", controller.isCorroborating())
        assertTrue(controller.onCorroborationFrame(true, 0.5f) is LockOutcome.Locked)
    }

    // ---- lock duration and unlock attempts ----

    @Test
    fun unlockingDuringTheLockLocksAgainUntilTheDurationHasElapsed() {
        controller.onDetection(image("image-1"))
        now += 4_000
        assertEquals(LockOutcome.Relocked("image-1", 6_000), controller.onUserPresent())
        assertEquals(2, device.locks)
        assertTrue(logged("LOCK re-applied: unlock 4000ms into the lock, remaining=6000ms, detectionId=image-1"))
        now += 5_999
        assertTrue(controller.onUserPresent() is LockOutcome.Relocked)
        assertEquals(3, device.locks)
        now += 1
        assertEquals("duration elapsed: the unlock stands", LockOutcome.LockEnded, controller.onUserPresent())
        assertEquals(3, device.locks)
        assertFalse(controller.isLocked())
        assertEquals(LockOutcome.None, controller.onUserPresent())
    }

    @Test
    fun theLockTimerEndsTheLockOnlyOnceItHasElapsed() {
        controller.onDetection(image())
        now += 9_999
        assertEquals(LockOutcome.None, controller.onLockTimer())
        now += 1
        assertEquals(LockOutcome.LockEnded, controller.onLockTimer())
        assertTrue(logged("LOCK period over: duration=10000ms"))
    }

    @Test
    fun aDetectionDuringALockRestartsTheDuration() {
        controller.onDetection(image("image-1"))
        now += 8_000
        val out = controller.onDetection(text(KeywordTier.EXPLICIT, listOf("nudes"), "text-2"))
        assertEquals(LockOutcome.Locked(LockSource.TEXT_EXPLICIT, "text-2", now + 10_000), out)
        now += 5_000
        assertTrue(controller.onUserPresent() is LockOutcome.Relocked)
    }

    // ---- device admin not active ----

    @Test
    fun withoutDeviceAdminLockingIsSkippedWithAWarningAndNothingCrashes() {
        device.adminActive = false
        val out = controller.onDetection(image("image-8"))
        assertEquals(LockOutcome.Skipped("image-8", "device admin is not active"), out)
        assertEquals(0, device.locks)
        assertFalse(controller.isLocked())
        assertTrue(lines.any { it.first == LockLog.Level.WARN && it.second.contains("LOCK skipped: device admin is not active") })
        assertFalse(logged("LOCK triggered"))
        // Explicit text and a corroborated borderline match are skipped the same way.
        assertTrue(controller.onDetection(text(KeywordTier.EXPLICIT, listOf("porn"))) is LockOutcome.Skipped)
        controller.onDetection(text(KeywordTier.BORDERLINE, listOf("lingerie"), "text-9"))
        assertTrue(controller.onCorroborationFrame(true, 0.6f) is LockOutcome.Skipped)
        assertEquals(LockOutcome.None, controller.onUserPresent())
        assertEquals(0, device.locks)
    }

    @Test
    fun aRefusedLockIsLoggedAndSkipped() {
        device.refuse = true
        val out = controller.onDetection(image("image-2"))
        assertTrue(out is LockOutcome.Skipped && out.reason.contains("SecurityException"))
        assertTrue(lines.any { it.first == LockLog.Level.ERROR && it.second.contains("LOCK failed") })
        assertFalse(controller.isLocked())
    }

    @Test
    fun adminRevokedDuringALockSkipsTheRelock() {
        controller.onDetection(image("image-1"))
        device.adminActive = false
        now += 1_000
        assertTrue(controller.onUserPresent() is LockOutcome.Skipped)
        assertEquals(1, device.locks)
    }

    // ---- wiring ----

    @Test
    fun theControllerReceivesDetectionsThroughTheSharedBus() {
        val listener = DetectionListener { controller.onDetection(it) }
        DetectionBus.register(listener)
        try {
            assertTrue(DetectionBus.publish(image("image-bus")).isEmpty())
        } finally {
            DetectionBus.unregister(listener)
        }
        assertTrue(logged("detectionId=image-bus"))
    }

    @Test
    fun theServiceSubscribesToTheBusAndUnlockBroadcasts() {
        val src = File("src/main/java/com/personal/guardian/scan/GuardianAccessibilityService.kt").readText()
        assertTrue(src.contains("DetectionBus.register(lockListener)"))
        assertTrue(src.contains("DetectionBus.unregister(lockListener)"))
        assertTrue(src.contains("Intent.ACTION_USER_PRESENT"))
        val admin = File("src/main/res/xml/device_admin_policies.xml").readText()
        assertTrue("lockNow needs the force-lock policy", admin.contains("<force-lock />"))
    }
}
