package com.personal.guardian.reflection

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.personal.guardian.util.GuardianLog
import java.io.File
import java.util.Random

/**
 * Process-wide access to the Reflection Mode library and duration (README "Stage 6 —
 * Reflection Mode"), persisted by [ReflectionStore] in the app's private files dir.
 *
 * Media items are `content://` URIs; the app keeps a **persisted read grant** for
 * each so it can still open them after a reboot, and releases the grant when the item
 * is removed. The 30-second duration minimum is enforced in [ReflectionStore] /
 * [ReflectionDuration], not just in the UI.
 */
object ReflectionSettings {

    const val CONTENT_FILE = "reflection_content.tsv"
    const val DURATION_FILE = "reflection_duration.txt"

    @Volatile
    private var cachedLibrary: ReflectionLibrary? = null

    @Volatile
    private var cachedDurationSeconds: Long? = null

    private var textIdSeq = 0L

    private fun store(context: Context): ReflectionStore {
        val dir = context.applicationContext.filesDir
        return ReflectionStore(File(dir, CONTENT_FILE), File(dir, DURATION_FILE))
    }

    // ---- library ----

    fun library(context: Context): ReflectionLibrary {
        cachedLibrary?.let { return it }
        synchronized(this) {
            cachedLibrary?.let { return it }
            val ctx = context.applicationContext
            val loaded = try {
                store(ctx).loadLibrary()
            } catch (t: Throwable) {
                GuardianLog.e(ctx, "Reflection Mode: could not read the content library; starting empty.", t)
                ReflectionStore.LoadedLibrary(ReflectionLibrary.EMPTY, damaged = true)
            }
            if (loaded.damaged) GuardianLog.w(ctx, "Reflection Mode: stored content library unreadable; starting empty until the next edit.")
            cachedLibrary = loaded.library
            return loaded.library
        }
    }

    /**
     * Adds a media item, taking a persistable read grant for [uri] first. [flags] are
     * the picker result's flags (so we only persist a grant the picker actually gave).
     */
    fun addMedia(context: Context, type: ReflectionType, uri: Uri, flags: Int, label: String): ReflectionLibrary {
        val ctx = context.applicationContext
        if (flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                .onFailure { GuardianLog.w(ctx, "Reflection Mode: could not persist read access to $uri; it may fail to open later.", it) }
        }
        return updateLibrary(ctx, "added ${type.label} content") { it.add(ReflectionItem.media(type, uri.toString(), label)) }
    }

    fun addText(context: Context, text: String): ReflectionLibrary {
        val ctx = context.applicationContext
        val id = "text-${System.currentTimeMillis()}-${synchronized(this) { ++textIdSeq }}"
        return updateLibrary(ctx, "added text content") { it.add(ReflectionItem.text(text, id)) }
    }

    fun remove(context: Context, id: String): ReflectionLibrary {
        val ctx = context.applicationContext
        val item = library(ctx).items.firstOrNull { it.id == id }
        if (item != null && item.type.isMedia) {
            runCatching {
                ctx.contentResolver.releasePersistableUriPermission(Uri.parse(item.value), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        return updateLibrary(ctx, "removed ${item?.type?.label ?: "?"} content") { it.remove(id) }
    }

    private fun updateLibrary(ctx: Context, description: String, change: (ReflectionLibrary) -> ReflectionLibrary): ReflectionLibrary {
        val updated = synchronized(this) {
            val next = change(library(ctx))
            store(ctx).saveLibrary(next)
            cachedLibrary = next
            next
        }
        GuardianLog.i(
            ctx,
            "Reflection Mode: $description. Library now ${updated.size} item(s) " +
                "(text ${updated.countOf(ReflectionType.TEXT)}, image ${updated.countOf(ReflectionType.IMAGE)}, " +
                "audio ${updated.countOf(ReflectionType.AUDIO)}, video ${updated.countOf(ReflectionType.VIDEO)})."
        )
        return updated
    }

    /** One random item for a lock trigger, or null if the library is empty. */
    fun pick(context: Context, random: Random = Random()): ReflectionItem? = library(context).pick(random)

    // ---- duration ----

    fun durationSeconds(context: Context): Long {
        cachedDurationSeconds?.let { return it }
        synchronized(this) {
            cachedDurationSeconds?.let { return it }
            val seconds = try {
                store(context.applicationContext).loadDurationSeconds()
            } catch (t: Throwable) {
                GuardianLog.e(context.applicationContext, "Reflection Mode: could not read the duration; using the default.", t)
                ReflectionDuration.DEFAULT_SECONDS
            }
            cachedDurationSeconds = seconds
            return seconds
        }
    }

    fun durationMs(context: Context): Long = durationSeconds(context) * 1000

    /**
     * Persists [requestedSeconds] after the code-level 30-second clamp; returns the
     * clamp so the UI can report that a too-low value was raised.
     */
    fun setDurationSeconds(context: Context, requestedSeconds: Long): ReflectionDuration.Clamp {
        val ctx = context.applicationContext
        val clamp = synchronized(this) {
            val c = store(ctx).saveDurationSeconds(requestedSeconds)
            cachedDurationSeconds = c.seconds
            c
        }
        GuardianLog.i(
            ctx,
            "Reflection Mode: duration set to ${clamp.seconds}s" +
                (if (clamp.adjusted) " (requested ${requestedSeconds}s; ${clamp.reason})" else "") + "."
        )
        return clamp
    }
}
