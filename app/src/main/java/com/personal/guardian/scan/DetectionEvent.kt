package com.personal.guardian.scan

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArraySet

/**
 * What caused a detection: the baseline capture timer, fast-mode capture for a
 * watched foreground app, or (Stage 4) a text check on a content-changed event.
 */
enum class TriggerSource(val label: String) {
    PERIODIC("periodic"),
    EVENT("event"),
    TEXT("text"),
}

/** Which detection path produced an event. */
enum class DetectionKind(val label: String) {
    /** Stage 3: on-screen image classification. */
    IMAGE("image"),
    /** Stage 4: keyword/phrase match in on-screen text. */
    TEXT("text"),
}

/**
 * A confirmed detection — from image scanning (Stage 3) or text scanning (Stage 4).
 * This is the single event type later stages (e.g. the lock mechanism) consume via
 * [DetectionBus]; [kind] tells the two paths apart.
 */
data class DetectionEvent(
    /** Wall-clock time of the confirming frame, epoch millis. */
    val timestampMs: Long,
    /**
     * Image: signal (sexy + porn + hentai) of the confirming frame, 0..1.
     * Text: 1.0 (a keyword match is binary).
     */
    val confidence: Float,
    val source: TriggerSource,
    /** Package in the foreground when confirmed, if known. */
    val foregroundPackage: String?,
    /** File name of the saved review thumbnail (inside the detections dir), if saved. */
    val thumbnailFile: String?,
    /** Per-class probabilities of the confirming frame, if known. */
    val classScores: NsfwScores? = null,
    val kind: DetectionKind = DetectionKind.IMAGE,
    /** Text detections: the list terms that matched. */
    val matchedTerms: List<String> = emptyList(),
    /** Text detections: a short excerpt around the first match (local review log only). */
    val textSnippet: String? = null
) {
    /** One JSON object per line for the local review log (no Android JSON dependency). */
    fun toJsonLine(): String = buildString {
        append("{\"timestamp\":").append(timestampMs)
        append(",\"time\":\"").append(isoUtc(timestampMs)).append('"')
        append(",\"confidence\":").append(num(confidence))
        append(",\"kind\":\"").append(kind.label).append('"')
        append(",\"trigger\":\"").append(source.label).append('"')
        append(",\"foreground\":").append(jsonString(foregroundPackage))
        append(",\"thumbnail\":").append(jsonString(thumbnailFile))
        if (kind == DetectionKind.TEXT) {
            append(",\"terms\":[").append(matchedTerms.joinToString(",") { jsonString(it) }).append(']')
            append(",\"snippet\":").append(jsonString(textSnippet))
        }
        classScores?.let { s ->
            append(",\"classes\":{")
            append("\"sexy\":").append(num(s.sexy))
            append(",\"porn\":").append(num(s.porn))
            append(",\"hentai\":").append(num(s.hentai))
            append(",\"neutral\":").append(num(s.neutral))
            append(",\"drawings\":").append(num(s.drawings))
            append('}')
        }
        append('}')
    }

    private fun num(v: Float): String = String.format(Locale.US, "%.4f", v)

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
