package com.personal.guardian.text

import java.util.concurrent.atomic.AtomicBoolean

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

    fun collect(root: TextNode, maxNodes: Int, maxChars: Int): List<String> {
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
        return out.toList()
    }
}

/**
 * Decides which accessibility events trigger a text check (Stage 4): window content
 * or window state changes from a watched package only. Bursts of events (typing,
 * scrolling) are coalesced: the first event schedules one check after the debounce
 * delay, later events are absorbed until that check starts. Thread-safe (events
 * arrive on the main thread, checks run on the worker).
 */
class TextScanTrigger(private val watchedPackages: Set<String>) {

    private val pending = AtomicBoolean(false)

    /** True if this event should schedule a (debounced) text check now. */
    fun onEvent(eventType: Int, packageName: String?): Boolean {
        if (eventType != TYPE_WINDOW_CONTENT_CHANGED && eventType != TYPE_WINDOW_STATE_CHANGED) return false
        if (packageName == null || packageName !in watchedPackages) return false
        return pending.compareAndSet(false, true)
    }

    /** Call when the scheduled check starts (or is abandoned), so later events schedule again. */
    fun onCheckStarted() {
        pending.set(false)
    }

    fun isWatched(packageName: String?) = packageName != null && packageName in watchedPackages

    companion object {
        /** `AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED` (stable API constant). */
        const val TYPE_WINDOW_STATE_CHANGED = 0x00000020
        /** `AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED` (stable API constant). */
        const val TYPE_WINDOW_CONTENT_CHANGED = 0x00000800
    }
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
