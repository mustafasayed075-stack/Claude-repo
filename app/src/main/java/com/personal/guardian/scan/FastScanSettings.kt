package com.personal.guardian.scan

import android.content.Context
import com.personal.guardian.util.GuardianLog
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Process-wide access to the persisted Fast Scan Apps list ([FastScanList], stored
 * by [FastScanStore] in the app's private files dir). The list screen edits it
 * through [update]; the scanning service reads it once and then follows edits live
 * through a listener, so a change applies without restarting the service.
 *
 * Every change is written to the event log: the list decides which apps get fast
 * scanning, so removing one is worth a trace.
 */
object FastScanSettings {

    const val FILE_NAME = "fast_scan_apps.tsv"

    fun interface Listener {
        fun onChanged(list: FastScanList)
    }

    private val listeners = CopyOnWriteArraySet<Listener>()

    @Volatile
    private var cached: FastScanList? = null

    private fun store(context: Context) =
        FastScanStore(File(context.applicationContext.filesDir, FILE_NAME))

    /** The current list, read from disk on first use (seeding the defaults on first run). */
    fun get(context: Context): FastScanList {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val ctx = context.applicationContext
            val loaded = try {
                store(ctx).load()
            } catch (t: Throwable) {
                GuardianLog.e(ctx, "Fast Scan Apps: could not read or create the list; using the defaults.", t)
                FastScanStore.Loaded(FastScanDefaults.list(), seeded = false, damaged = true)
            }
            when {
                loaded.seeded -> GuardianLog.i(
                    ctx, "Fast Scan Apps: first run, list created with the ${loaded.list.size} default apps (text and image on)."
                )
                loaded.damaged -> GuardianLog.w(ctx, "Fast Scan Apps: stored list unreadable; using the defaults until the next edit.")
            }
            cached = loaded.list
            return loaded.list
        }
    }

    /**
     * Applies [change] to the list, saves it and notifies listeners. [description]
     * (e.g. "added WhatsApp (com.whatsapp)") goes to the event log. Returns the new list.
     */
    fun update(context: Context, description: String, change: (FastScanList) -> FastScanList): FastScanList {
        val ctx = context.applicationContext
        val updated = synchronized(this) {
            val current = get(ctx)
            val next = change(current)
            if (next == current) return current
            store(ctx).save(next)
            cached = next
            next
        }
        GuardianLog.i(
            ctx,
            "Fast Scan Apps: $description. Now ${updated.size} apps " +
                "(text: ${updated.textPackages.size}, image: ${updated.imagePackages.size})."
        )
        listeners.forEach { it.onChanged(updated) }
        return updated
    }

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }
}
