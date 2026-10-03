package com.personal.guardian.scan

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import com.personal.guardian.text.KeywordTier
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong

/**
 * What caused a detection: the baseline capture timer, fast-mode capture for a
 * watched foreground app, or (Stage 4) a text check on a content-changed event.
 */
enum class TriggerSource(val label: String) {
    PERIODIC("periodic"),
    EVENT("event"),
    TEXT("text"),
    /** Stage 5: an image check made to corroborate a borderline text match. */
    CORROBORATION("corroboration"),
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
     * Image: signal of the confirming frame, 0..1 — the whole-screen signal
     * ([NsfwScores.screenSignal]) or, when a region scored, the region's
     * ([NsfwScores.signal]).
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
    val textSnippet: String? = null,
    /**
     * Image detections from region scanning: the on-screen element that scored
     * (e.g. `image 540x540@480,900 (ImageView)`); null when the whole screen did.
     */
    val region: String? = null,
    /** Text detections: lock tier — explicit (locks now) or borderline (needs image corroboration). */
    val textTier: KeywordTier? = null,
    /** Unique id, also in the LOCK log line, linking the event log to the review log. */
    val id: String = newId(kind, timestampMs)
) {
    companion object {
        private val sequence = AtomicLong()

        /** e.g. `image-1730000000000-3`: kind, wall-clock time, per-process sequence. */
        fun newId(kind: DetectionKind, timestampMs: Long): String =
            "${kind.label}-$timestampMs-${sequence.incrementAndGet()}"
    }

    /** One JSON object per line for the local review log (no Android JSON dependency). */
    fun toJsonLine(): String = buildString {
        append("{\"id\":").append(jsonString(id))
        append(",\"timestamp\":").append(timestampMs)
        append(",\"time\":\"").append(isoUtc(timestampMs)).append('"')
        append(",\"confidence\":").append(num(confidence))
        append(",\"kind\":\"").append(kind.label).append('"')
        append(",\"trigger\":\"").append(source.label).append('"')
        append(",\"foreground\":").append(jsonString(foregroundPackage))
        append(",\"thumbnail\":").append(jsonString(thumbnailFile))
        if (kind == DetectionKind.TEXT) {
            append(",\"terms\":[").append(matchedTerms.joinToString(",") { jsonString(it) }).append(']')
            append(",\"snippet\":").append(jsonString(textSnippet))
            textTier?.let { append(",\"tier\":\"").append(it.label).append('"') }
        }
        region?.let { append(",\"region\":").append(jsonString(it)) }
        classScores?.let { s ->
            append(",\"classes\":{")
            append("\"sexy\":").append(num(s.sexy))
            append(",\"porn\":").append(num(s.porn))
            append(",\"hentai\":").append(num(s.hentai))
            append(",\"safe\":").append(num(s.safe))
            append(",\"drawing\":").append(num(s.drawing))
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
