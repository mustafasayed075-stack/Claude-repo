package com.personal.guardian.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the capture watchdog (lost screenshot callbacks). */
class CaptureWatchdogTest {

    private fun watchdog() = CaptureWatchdog(timeoutMs = 3_000)

    @Test
    fun aCallbackInTimeCompletesTheRequestAndTheTimeoutIsThenANoOp() {
        val w = watchdog()
        val t = w.start(1_000)
        assertTrue(w.isPending)
        assertTrue(w.complete(t))
        assertFalse(w.isPending)
        assertFalse("already completed", w.expire(t, 10_000))
        assertEquals(0, w.timeouts)
    }

    @Test
    fun aRequestWithNoCallbackExpiresAfterTheTimeout() {
        val w = watchdog()
        val t = w.start(1_000)
        assertFalse("not yet", w.expire(t, 3_999))
        assertTrue(w.isPending)
        assertTrue(w.expire(t, 4_000))
        assertFalse(w.isPending)
        assertEquals(1, w.timeouts)
    }

    @Test
    fun aLateCallbackAfterTimeoutIsIgnored() {
        val w = watchdog()
        val t = w.start(0)
        assertTrue(w.expire(t, 3_000))
        assertFalse("late callback must not count", w.complete(t))
    }

    @Test
    fun aLateCallbackCannotClearANewerRequest() {
        val w = watchdog()
        val old = w.start(0)
        assertTrue(w.expire(old, 3_000))
        val fresh = w.start(3_500)
        assertFalse("old token is stale", w.complete(old))
        assertTrue("newer request still pending", w.isPending)
        assertFalse("old timeout can't expire the newer request", w.expire(old, 10_000))
        assertTrue(w.complete(fresh))
    }

    @Test
    fun pendingDurationIsReported() {
        val w = watchdog()
        assertNull(w.pendingForMs(0))
        w.start(1_000)
        assertEquals(1_500L, w.pendingForMs(2_500))
    }
}
