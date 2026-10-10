package com.personal.guardian.util

/** Pure helpers for exporting the event log (so the slicing is unit-tested). */
object LogExport {

    /** The last [maxLines] lines of [text] (trailing newline ignored), joined by "\n". */
    fun tail(text: String, maxLines: Int): String {
        require(maxLines >= 0) { "maxLines must be >= 0" }
        if (maxLines == 0) return ""
        val lines = text.split('\n')
        // A trailing newline produces a final empty element; drop it so it isn't a "line".
        val effective = if (lines.isNotEmpty() && lines.last().isEmpty()) lines.dropLast(1) else lines
        return effective.takeLast(maxLines).joinToString("\n")
    }
}
