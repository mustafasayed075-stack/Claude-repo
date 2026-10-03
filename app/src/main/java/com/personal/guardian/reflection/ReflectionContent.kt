package com.personal.guardian.reflection

import java.util.Random

/**
 * Stage 6 — Reflection Mode content (README "Stage 6 — Reflection Mode").
 *
 * A reminder shown full-screen on every lock trigger. The user builds a library of
 * these; one is picked at random each time, so the same reminder doesn't repeat and
 * lose its effect. Pure-Kotlin model, file format, library, duration rule and
 * countdown — all unit-tested without Android.
 */
enum class ReflectionType(val label: String) {
    /** A typed message, shown large and centred. */
    TEXT("text"),
    /** An image shown full-screen (a `content://` URI the app holds a persisted read grant for). */
    IMAGE("image"),
    /** An audio clip, looped (URI). */
    AUDIO("audio"),
    /** A video, looped and muted-autoplay-safe (URI). */
    VIDEO("video");

    val isMedia: Boolean get() = this != TEXT

    companion object {
        fun fromLabel(label: String): ReflectionType? = entries.firstOrNull { it.label == label }
    }
}

/**
 * One piece of reminder content. [value] is the typed text for [ReflectionType.TEXT],
 * otherwise the content URI as a string. [id] is unique within a library (the URI
 * for media, so the same file can't be added twice; a generated id for text).
 * [label] is an optional display name (a file name, or the first line of text).
 */
data class ReflectionItem(
    val type: ReflectionType,
    val value: String,
    val id: String,
    val label: String = ""
) {
    /** A short label for the list and logs: the given label, else a trimmed preview. */
    fun displayLabel(): String = label.ifBlank {
        if (type == ReflectionType.TEXT) value.trim().take(40).ifBlank { "(empty)" } else value
    }

    companion object {
        /** Media content keyed by its URI (adding the same URI twice is a no-op). */
        fun media(type: ReflectionType, uri: String, label: String = ""): ReflectionItem {
            require(type.isMedia) { "media() is not for TEXT" }
            return ReflectionItem(type, uri, uri, label)
        }

        /** A typed message with a caller-supplied unique id. */
        fun text(value: String, id: String, label: String = ""): ReflectionItem =
            ReflectionItem(ReflectionType.TEXT, value, id, label)
    }
}

/**
 * The reminder library. Immutable: every edit returns a new library. Items keep the
 * order they were added; an id appears at most once (first wins).
 */
class ReflectionLibrary(items: List<ReflectionItem>) {

    val items: List<ReflectionItem> = items.distinctBy { it.id }

    val size: Int get() = items.size
    val isEmpty: Boolean get() = items.isEmpty()

    operator fun contains(id: String): Boolean = items.any { it.id == id }

    fun countOf(type: ReflectionType): Int = items.count { it.type == type }

    fun add(item: ReflectionItem): ReflectionLibrary =
        if (item.id in this) this else ReflectionLibrary(items + item)

    fun remove(id: String): ReflectionLibrary =
        if (id in this) ReflectionLibrary(items.filter { it.id != id }) else this

    /**
     * One item chosen uniformly at random (injected [random] for deterministic tests),
     * or null if the library is empty.
     */
    fun pick(random: Random): ReflectionItem? =
        if (items.isEmpty()) null else items[random.nextInt(items.size)]

    override fun equals(other: Any?): Boolean = other is ReflectionLibrary && other.items == items
    override fun hashCode(): Int = items.hashCode()
    override fun toString(): String = "ReflectionLibrary($items)"

    companion object {
        val EMPTY = ReflectionLibrary(emptyList())
    }
}

/**
 * Text file format for the library: a header line, then one tab-separated line per
 * item: `type<TAB>id<TAB>label<TAB>value`. Tabs and newlines in text/label/value are
 * escaped so one item is always one line.
 */
object ReflectionFormat {

    const val HEADER = "# Guardian Reflection Mode content v1"

    fun encode(library: ReflectionLibrary): String = buildString {
        append(HEADER).append('\n')
        for (item in library.items) {
            append(item.type.label).append('\t')
                .append(esc(item.id)).append('\t')
                .append(esc(item.label)).append('\t')
                .append(esc(item.value)).append('\n')
        }
    }

    /** Parses [encode]'s output; null if [text] isn't this format (no header). Bad lines are skipped. */
    fun decode(text: String): ReflectionLibrary? {
        val lines = text.lines()
        if (lines.firstOrNull()?.trim() != HEADER) return null
        val items = lines.drop(1).mapNotNull { line ->
            if (line.isBlank() || line.startsWith("#")) return@mapNotNull null
            val f = line.split('\t')
            if (f.size < 4) return@mapNotNull null
            val type = ReflectionType.fromLabel(f[0].trim()) ?: return@mapNotNull null
            val id = unesc(f[1])
            val label = unesc(f[2])
            val value = unesc(f.drop(3).joinToString("\t")) // a stray tab in value survives
            if (id.isBlank() || value.isBlank()) return@mapNotNull null
            ReflectionItem(type, value, id, label)
        }
        return ReflectionLibrary(items)
    }

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

    private fun unesc(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '\\' -> out.append('\\')
                    't' -> out.append('\t')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    else -> out.append(s[i + 1])
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}

/**
 * The Reflection Mode duration rule. The **30-second minimum is enforced here, in
 * code** — not only in the UI — so a value typed below it is raised, never stored as
 * given. [clampSeconds] is the single gate the settings store runs every input
 * through.
 */
object ReflectionDuration {

    const val MIN_SECONDS = 30L
    const val DEFAULT_SECONDS = 30L

    /** A sanity cap so a typo can't pin the device for days. */
    const val MAX_SECONDS = 86_400L // 24 h

    /** [seconds] after clamping, whether it was [adjusted], and a [reason] if so. */
    data class Clamp(val seconds: Long, val adjusted: Boolean, val reason: String?)

    fun clampSeconds(requested: Long): Clamp = when {
        requested < MIN_SECONDS -> Clamp(
            MIN_SECONDS, true,
            "Minimum is $MIN_SECONDS seconds; using $MIN_SECONDS."
        )
        requested > MAX_SECONDS -> Clamp(MAX_SECONDS, true, "Maximum is $MAX_SECONDS seconds; using $MAX_SECONDS.")
        else -> Clamp(requested, false, null)
    }

    fun clampMillis(requestedSeconds: Long): Long = clampSeconds(requestedSeconds).seconds * 1000
}

/**
 * The countdown for [ReflectionActivity], with an injected monotonic clock so timing
 * and the back-button rule are tested without Android.
 *
 * The device stays pinned until [durationMs] has elapsed. The back button is a no-op
 * while the countdown runs ([onBackPressed] returns true = consumed); once elapsed
 * the activity is finishing anyway.
 */
class ReflectionCountdown(val durationMs: Long, private val clock: () -> Long) {

    private var startMs: Long? = null

    fun start() {
        if (startMs == null) startMs = clock()
    }

    val started: Boolean get() = startMs != null

    fun elapsedMs(): Long = startMs?.let { (clock() - it).coerceAtLeast(0) } ?: 0

    fun remainingMs(): Long = (durationMs - elapsedMs()).coerceAtLeast(0)

    /** Whole seconds remaining, rounded up, so the display reaches 0 only at the end. */
    fun remainingSeconds(): Long = (remainingMs() + 999) / 1000

    fun isElapsed(): Boolean = started && remainingMs() == 0L

    /** True = the press was consumed (ignored). The back button does nothing until the countdown ends. */
    fun onBackPressed(): Boolean = !isElapsed()
}
