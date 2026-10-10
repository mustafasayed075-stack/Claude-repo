package com.personal.guardian.text

/**
 * Minimal view of an accessibility node for text extraction. The Android adapter
 * wraps `AccessibilityNodeInfo`; tests use plain objects.
 */
interface TextNode {
    val text: CharSequence?
    val contentDescription: CharSequence?
    val isVisibleToUser: Boolean
    val childCount: Int
    fun child(index: Int): TextNode?
    /** Releases the underlying node (no-op where not needed). */
    fun release() {}
}

/**
 * Collects the visible text of a window's node tree (Stage 4): each visible node's
 * text and content description, depth-first, de-duplicated. Invisible subtrees are
 * skipped. Bounded by [maxNodes] and [maxChars] so a huge web page can't stall the
 * scanner. Pure Kotlin.
 */
object TextExtractor {

    /** Extracted text plus cheap counts for the diagnostic log (no raw content needed). */
    data class Extraction(val texts: List<String>, val nodesVisited: Int, val chars: Int)

    fun collect(root: TextNode, maxNodes: Int, maxChars: Int): List<String> =
        collectDetailed(root, maxNodes, maxChars).texts

    fun collectDetailed(root: TextNode, maxNodes: Int, maxChars: Int): Extraction {
        val out = LinkedHashSet<String>()
        var chars = 0
        var visited = 0
        val stack = ArrayDeque<TextNode>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            try {
                if (visited >= maxNodes || chars >= maxChars) continue
                visited++
                if (!node.isVisibleToUser) continue
                for (value in listOf(node.text, node.contentDescription)) {
                    val s = value?.toString()?.trim()
                    if (s.isNullOrEmpty() || chars >= maxChars) continue
                    val clipped = if (chars + s.length > maxChars) s.take(maxChars - chars) else s
                    if (out.add(clipped)) chars += clipped.length
                }
                // Push children in reverse so they are visited in on-screen order.
                for (i in node.childCount - 1 downTo 0) node.child(i)?.let { stack.addLast(it) }
            } finally {
                if (node !== root) node.release()
            }
        }
        // Anything left on the stack (limits reached) still needs releasing.
        while (stack.isNotEmpty()) stack.removeLast().let { if (it !== root) it.release() }
        return Extraction(out.toList(), visited, chars)
    }
}

/**
 * Decides which accessibility events trigger a text check (Stage 4), and coalesces
 * bursts of them.
 *
 * Triggers: window content or window state changes, and **view text changes** (a
 * text field being edited: typing into a composer sends `TYPE_VIEW_TEXT_CHANGED`,
 * often with no window-content change at all), from a watched package only — the
 * Fast Scan Apps with text scanning on
 * ([com.personal.guardian.scan.FastScanList.textPackages]), read through
 * [watchedPackages] on every event so list edits apply at once.
 *
 * Coalescing is a **non-resetting** debounce: the first event schedules one check
 * after the debounce delay and later events are absorbed until that check starts.
 * Continuous typing therefore can't postpone the check indefinitely, and because
 * any event after a check has started schedules a new one, the last change is
 * always checked within one debounce delay (`TextScanTest.rapidTypingInAFieldEndsInACheckOfTheFinalText`
 * in the tests).
 *
 * A text-changed event carries the field's live text (including uncommitted IME
 * composing text); the latest one is kept with the pending check ([Pending.fieldText])
 * so the check sees it even if the field is not where the node-tree walk looks.
 * Thread-safe (events arrive on the main thread, checks run on the worker).
 */
class TextScanTrigger(private val watchedPackages: () -> Set<String>) {

    constructor(packages: Set<String>) : this({ packages })

    /** What an event did. */
    enum class Decision {
        /** Not a trigger (other event type, or not a text-scanned app). */
        IGNORED,
        /** No check was pending: the caller schedules one after the debounce delay. */
        SCHEDULED,
        /** A check is already pending; this event is absorbed into it. */
        COALESCED
    }

    /** The pending check, handed to it when it starts. */
    class Pending(
        /** Package of the latest triggering event: the app to check. */
        val packageName: String,
        /** Latest live text of an edited field in [packageName], if a text-changed event carried one. */
        val fieldText: String?,
        /** Triggering events coalesced into this check, and how many were text-field edits. */
        val events: Int,
        val fieldEvents: Int
    )

    private val lock = Any()
    private var pending: Pending? = null

    /**
     * Records one accessibility event. [fieldText] is the edited field's text for a
     * `TYPE_VIEW_TEXT_CHANGED` event (null otherwise, or for password fields).
     */
    fun onEvent(eventType: Int, packageName: String?, fieldText: CharSequence? = null): Decision {
        if (eventType !in TRIGGER_TYPES) return Decision.IGNORED
        if (packageName == null || packageName !in watchedPackages()) return Decision.IGNORED
        val isField = eventType == TYPE_VIEW_TEXT_CHANGED
        val text = if (isField) fieldText?.toString() else null
        synchronized(lock) {
            val p = pending
            return if (p == null) {
                pending = Pending(packageName, text, 1, if (isField) 1 else 0)
                Decision.SCHEDULED
            } else {
                // The latest event decides the app; a field text only stays with its own app.
                val keptField = text ?: p.fieldText.takeIf { p.packageName == packageName }
                pending = Pending(packageName, keptField, p.events + 1, p.fieldEvents + if (isField) 1 else 0)
                Decision.COALESCED
            }
        }
    }

    /**
     * Call when the scheduled check starts (or is abandoned): returns what it should
     * check and clears it, so later events schedule a new check.
     */
    fun onCheckStarted(): Pending? = synchronized(lock) { pending.also { pending = null } }

    fun isWatched(packageName: String?) = packageName != null && packageName in watchedPackages()

    companion object {
        /** `AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED` (stable API constant). */
        const val TYPE_WINDOW_STATE_CHANGED = 0x00000020
        /** `AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED` (stable API constant). */
        const val TYPE_WINDOW_CONTENT_CHANGED = 0x00000800
        /** `AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED` (stable API constant). */
        const val TYPE_VIEW_TEXT_CHANGED = 0x00000010

        private val TRIGGER_TYPES = setOf(TYPE_WINDOW_STATE_CHANGED, TYPE_WINDOW_CONTENT_CHANGED, TYPE_VIEW_TEXT_CHANGED)

        /** True for events from a text field: a text-changed event, or a change whose source is an EditText. */
        fun isFieldEvent(eventType: Int, className: CharSequence?): Boolean =
            eventType == TYPE_VIEW_TEXT_CHANGED || className?.contains("EditText") == true
    }
}

/**
 * Which on-screen windows a text check reads for an app (pure, unit-tested).
 *
 * The check used to read only the *active* window (`rootInActiveWindow`). While the
 * user is touching the keyboard the active window is the keyboard's, and a composer
 * can live in its own dialog or popup window — either way the field being typed in
 * wasn't read. Now every on-screen window of the app is read (the active one first),
 * input-method windows never are, and an app with no window on screen isn't checked.
 */
object TextWindows {

    class Candidate(val packageName: String?, val isInputMethod: Boolean, val isActive: Boolean)

    /** Indices into [windows] to read for [packageName], active window first; empty if the app isn't on screen. */
    fun select(windows: List<Candidate>, packageName: String): List<Int> =
        windows.indices
            .filter { !windows[it].isInputMethod && windows[it].packageName == packageName }
            .sortedByDescending { windows[it].isActive }
}

/**
 * Fingerprints for the text-detection cooldown: one 64-bit FNV-1a hash per
 * (app, matched term). A lingering conversation keeps matching the same terms in the
 * same app, so it is suppressed; a new term (or another app) is new content. The
 * snippet itself is not hashed because it changes with every keystroke and scroll.
 */
object TextFingerprint {
    fun of(packageName: String, term: String): Long {
        var h = -0x340d631b7bdddcdbL // FNV-1a 64 offset basis (0xcbf29ce484222325)
        for (c in "$packageName\u0000$term") {
            h = h xor c.code.toLong()
            h *= 0x100000001b3L
        }
        return h
    }
}
