package com.personal.guardian.reflection

import java.io.File
import java.io.IOException

/**
 * Persists the Reflection Mode library and duration in the app's private files dir,
 * so both survive app restarts and reboots. Writes go to a temp file that is renamed
 * over the real one, so a crash mid-write can't leave a half-written file. Pure
 * java.io, so it is tested on the JVM.
 */
class ReflectionStore(private val contentFile: File, private val durationFile: File) {

    private fun tmp(f: File) = File(f.parentFile, f.name + ".tmp")

    class LoadedLibrary(val library: ReflectionLibrary, val damaged: Boolean)

    /** Reads the library; an unreadable or damaged file yields an empty library (`damaged`), left in place. */
    fun loadLibrary(): LoadedLibrary {
        val f = contentFile
        if (!f.exists() && tmp(f).exists()) tmp(f).renameTo(f) // finish an interrupted save
        if (!f.exists()) return LoadedLibrary(ReflectionLibrary.EMPTY, damaged = false)
        val parsed = try {
            ReflectionFormat.decode(f.readText(Charsets.UTF_8))
        } catch (e: IOException) {
            null
        }
        return if (parsed != null) LoadedLibrary(parsed, damaged = false)
        else LoadedLibrary(ReflectionLibrary.EMPTY, damaged = true)
    }

    fun saveLibrary(library: ReflectionLibrary) = write(contentFile, ReflectionFormat.encode(library))

    /**
     * Reads the duration in seconds, always through [ReflectionDuration.clampSeconds]
     * so a hand-edited file below the minimum still can't lower it. Missing or
     * unparseable → the default.
     */
    fun loadDurationSeconds(): Long {
        val f = durationFile
        if (!f.exists() && tmp(f).exists()) tmp(f).renameTo(f)
        val raw = try {
            if (f.exists()) f.readText(Charsets.UTF_8).trim().toLongOrNull() else null
        } catch (e: IOException) {
            null
        }
        return ReflectionDuration.clampSeconds(raw ?: ReflectionDuration.DEFAULT_SECONDS).seconds
    }

    /** Clamps [requestedSeconds] (30 s minimum enforced here) and persists it; returns the clamp result. */
    fun saveDurationSeconds(requestedSeconds: Long): ReflectionDuration.Clamp {
        val clamp = ReflectionDuration.clampSeconds(requestedSeconds)
        write(durationFile, clamp.seconds.toString())
        return clamp
    }

    private fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        val t = tmp(file)
        t.writeText(text, Charsets.UTF_8)
        if (!t.renameTo(file)) {
            file.delete()
            if (!t.renameTo(file)) throw IOException("could not replace $file")
        }
    }
}
