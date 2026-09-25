package com.personal.guardian.util

import java.io.File

/**
 * Size-capped append-only text files (plain java.io, no Android), used by
 * [GuardianLog] for both the main event log and the separate diagnostics log.
 * Each file rotates independently, so heavy traffic in one never trims the other.
 */
object LogFiles {

    /**
     * Appends [line] (which should end in '\n') to [file]. If the file has reached
     * [maxBytes], it is first trimmed to roughly its newest [trimToBytes], cut at a
     * line boundary, with a marker line noting the rotation.
     */
    fun append(file: File, line: String, maxBytes: Long, trimToBytes: Long, rotationMarker: String) {
        if (file.exists() && file.length() >= maxBytes) {
            val bytes = file.readBytes()
            var start = (bytes.size - trimToBytes.toInt()).coerceAtLeast(0)
            // Don't keep a partial first line (or a split multi-byte character).
            if (start > 0) {
                while (start < bytes.size && bytes[start - 1] != '\n'.code.toByte()) start++
            }
            file.writeBytes(bytes.copyOfRange(start, bytes.size))
            file.appendText(rotationMarker)
        }
        file.appendText(line)
    }
}
