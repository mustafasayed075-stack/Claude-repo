package com.personal.guardian.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** A crash lands in the event log and in the last-crash file that "نسخ اللوج" includes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashLoggerTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        CrashLogger.lastCrashFile(context).delete()
        GuardianLog.logFile(context).delete()
    }

    @Test
    fun noCrashYet() {
        assertNull(CrashLogger.lastCrash(context))
    }

    @Test
    fun aCrashIsWrittenToTheEventLogAndTheLastCrashFileWithoutItsMessage() {
        val error = RuntimeException("Unable to start: private text", NullPointerException("more private text"))
        CrashLogger.record(context, "main", error)

        val log = GuardianLog.readAll(context)
        assertTrue(log.contains("ERROR CRASH on thread \"main\": java.lang.RuntimeException"))
        assertTrue(log.contains("Caused by: java.lang.NullPointerException"))
        assertFalse(log.contains("private text"))

        val last = CrashLogger.lastCrash(context)
        assertNotNull(last)
        assertTrue(last!!.contains("java.lang.RuntimeException"))
        assertFalse(last.contains("private text"))
    }

    @Test
    fun theAppInstallsTheHandlerAndItChainsToThePreviousOne() {
        // RafiqApp.onCreate (run by Robolectric) installed it.
        val installed = Thread.getDefaultUncaughtExceptionHandler()
        assertEquals("com.personal.guardian.util.CrashLogger\$Handler", installed?.javaClass?.name)
        // Installing again keeps a single handler.
        CrashLogger.install(context)
        assertEquals(installed, Thread.getDefaultUncaughtExceptionHandler())
    }

    @Test
    fun theHandlerRecordsThenHandsOver() {
        var handedOver: Throwable? = null
        val saved = Thread.getDefaultUncaughtExceptionHandler()
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, e -> handedOver = e }
            CrashLogger.install(context)
            val error = IllegalStateException("x")
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), error)
            assertEquals(error, handedOver)
            assertTrue(CrashLogger.lastCrash(context)!!.contains("java.lang.IllegalStateException"))
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(saved)
        }
    }
}
