package com.personal.guardian.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure-JVM tests for the detection event's metadata record, the in-process
 * [DetectionBus] later stages subscribe to, and a static guard that the scanning
 * code never touches the network.
 */
class DetectionEventTest {

    private val event = DetectionEvent(
        timestampMs = 1_790_000_000_123L,
        confidence = 0.93456f,
        source = TriggerSource.EVENT,
        foregroundPackage = "com.whatsapp",
        thumbnailFile = "detection-1790000000123.jpg"
    )

    @Test
    fun metadataLineHasTimestampConfidenceAndTrigger() {
        assertEquals(
            "{\"timestamp\":1790000000123,\"time\":\"2026-09-21T14:13:20.123Z\",\"confidence\":0.9346," +
                "\"trigger\":\"event\",\"foreground\":\"com.whatsapp\",\"thumbnail\":\"detection-1790000000123.jpg\"}",
            event.toJsonLine()
        )
    }

    @Test
    fun metadataLineIncludesPerClassScoresWhenKnown() {
        val withClasses = event.copy(
            confidence = 0.643f,
            classScores = NsfwScores(drawings = 0.017f, hentai = 0f, neutral = 0.34f, porn = 0.031f, sexy = 0.612f)
        ).toJsonLine()
        assertTrue(withClasses.contains("\"confidence\":0.6430"))
        assertTrue(
            withClasses.endsWith(
                ",\"classes\":{\"sexy\":0.6120,\"porn\":0.0310,\"hentai\":0.0000,\"neutral\":0.3400,\"drawings\":0.0170}}"
            )
        )
    }

    @Test
    fun metadataLineHandlesNullsAndEscaping() {
        val line = event.copy(source = TriggerSource.PERIODIC, foregroundPackage = null, thumbnailFile = "a\"b\\c").toJsonLine()
        assertTrue(line.contains("\"trigger\":\"periodic\""))
        assertTrue(line.contains("\"foreground\":null"))
        assertTrue(line.contains("\"thumbnail\":\"a\\\"b\\\\c\""))
    }

    @Test
    fun busDeliversToRegisteredListenersAndIsolatesFailures() {
        val received = mutableListOf<DetectionEvent>()
        val good = DetectionListener { received += it }
        val bad = DetectionListener { throw IllegalStateException("boom") }
        DetectionBus.register(good)
        DetectionBus.register(bad)
        try {
            val errors = DetectionBus.publish(event)
            assertEquals(listOf(event), received)
            assertEquals(1, errors.size)
        } finally {
            DetectionBus.unregister(good)
            DetectionBus.unregister(bad)
        }
        DetectionBus.publish(event)
        assertEquals("unregistered listener gets nothing more", 1, received.size)
    }

    /**
     * Static guard for "classified fully on-device, with zero network calls": the
     * scanning package must not reference any networking API.
     */
    @Test
    fun scanPackageUsesNoNetworkingApis() {
        val dir = File("src/main/java/com/personal/guardian/scan")
        assertTrue("scan sources not found from ${File(".").absolutePath}", dir.isDirectory)
        val forbidden = listOf("java.net", "HttpURLConnection", "okhttp", "URLConnection", "Socket(", "WorkManager")
        val sources = dir.listFiles { f -> f.extension == "kt" }!!.toList()
        assertTrue(sources.size >= 5)
        for (file in sources) {
            val text = file.readText()
            for (token in forbidden) {
                assertFalse("${file.name} references $token", text.contains(token))
            }
        }
    }
}
