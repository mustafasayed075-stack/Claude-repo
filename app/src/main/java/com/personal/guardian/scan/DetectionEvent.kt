package com.personal.guardian.scan

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArraySet

/** What caused a capture: the baseline timer, or fast mode for a watched foreground app. */
enum class TriggerSource(val label: String) {
    PERIODIC("periodic"),
    EVENT("event"),
}

/**
 * A confirmed detection (spec §5). This is the event later stages (e.g. the lock
 * mechanism) consume via [DetectionBus].
 */
data class DetectionEvent(
    /** Wall-clock time of the confirming frame, epoch millis. */
    val timestampMs: Long,
    /** Classifier NSFW score of the confirming frame, 0..1. */
    val confidence: Float,
    val source: TriggerSource,
    /** Package in the foreground when confirmed, if known. */
    val foregroundPackage: String?,
    /** File name of the saved review thumbnail (inside the detections dir), if saved. */
    val thumbnailFile: String?
) {
    /** One JSON object per line for the local review log (no Android JSON dependency). */
    fun toJsonLine(): String = buildString {
        append("{\"timestamp\":").append(timestampMs)
        append(",\"time\":\"").append(isoUtc(timestampMs)).append('"')
        append(",\"confidence\":").append(String.format(Locale.US, "%.4f", confidence))
        append(",\"trigger\":\"").append(source.label).append('"')
        append(",\"foreground\":").append(jsonString(foregroundPackage))
        append(",\"thumbnail\":").append(jsonString(thumbnailFile))
        append('}')
    }

    private fun isoUtc(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(ms))

    private fun jsonString(s: String?): String {
        if (s == null) return "null"
        val escaped = buildString {
            for (c in s) when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c < ' ' -> append(String.format(Locale.US, "\\u%04x", c.code))
                else -> append(c)
            }
        }
        return "\"$escaped\""
    }
}

/** Receives confirmed detections. Called on the scanner's worker thread. */
fun interface DetectionListener {
    fun onDetectionConfirmed(event: DetectionEvent)
}

/**
 * In-process publish/subscribe for confirmed detections. Stage 3 publishes; a later
 * stage (lock mechanism) registers a [DetectionListener]. A failing listener never
 * affects the scanner or other listeners.
 */
object DetectionBus {
    private val listeners = CopyOnWriteArraySet<DetectionListener>()

    fun register(listener: DetectionListener) {
        listeners.add(listener)
    }

    fun unregister(listener: DetectionListener) {
        listeners.remove(listener)
    }

    /** Delivers [event] to every listener; returns the listener errors, if any. */
    fun publish(event: DetectionEvent): List<Throwable> =
        listeners.mapNotNull { l -> runCatching { l.onDetectionConfirmed(event) }.exceptionOrNull() }
}
