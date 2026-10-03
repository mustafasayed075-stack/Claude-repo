package com.personal.guardian.scan

import java.io.File
import java.io.IOException

/**
 * How aggressively the suggestive tier reacts (README "Screen scanning → suggestive tier").
 * The explicit path is fixed; this only sets the suggestive signal threshold that
 * [SuggestiveConfirmer] frames are judged against — lower means more images count as
 * suggestive, so two of them within the window lock sooner.
 */
enum class ScanSensitivity(val id: String, val suggestiveThreshold: Float) {
    NORMAL("normal", ScanConfig.SUGGESTIVE_THRESHOLD_NORMAL),
    HIGH("high", ScanConfig.SUGGESTIVE_THRESHOLD_HIGH);

    companion object {
        val DEFAULT = NORMAL

        /** Parses a stored id (case/whitespace-insensitive); unknown or null → [DEFAULT]. */
        fun fromId(id: String?): ScanSensitivity =
            entries.firstOrNull { it.id == id?.trim()?.lowercase() } ?: DEFAULT
    }
}

/**
 * Persists the chosen [ScanSensitivity] in one small file in the app's private files
 * dir, so it survives app restarts and reboots. Writes go to a temp file that is
 * renamed over the real one, so a crash mid-write can't leave a half-written file.
 * Pure java.io, so it is tested on the JVM. Missing or unreadable → the default.
 */
class ScanSensitivityStore(private val file: File) {

    private val tmp: File get() = File(file.parentFile, file.name + ".tmp")

    fun load(): ScanSensitivity {
        if (!file.exists() && tmp.exists()) tmp.renameTo(file) // finish an interrupted save
        val raw = try {
            if (file.exists()) file.readText(Charsets.UTF_8) else null
        } catch (e: IOException) {
            null
        }
        return ScanSensitivity.fromId(raw)
    }

    fun save(sensitivity: ScanSensitivity) {
        file.parentFile?.mkdirs()
        val t = tmp
        t.writeText(sensitivity.id, Charsets.UTF_8)
        if (!t.renameTo(file)) {
            file.delete()
            if (!t.renameTo(file)) throw IOException("could not replace $file")
        }
    }
}
