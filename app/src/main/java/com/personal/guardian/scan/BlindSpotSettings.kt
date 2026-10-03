package com.personal.guardian.scan

import android.content.Context
import com.personal.guardian.util.GuardianLog
import java.io.File

/**
 * Process-wide access to the persisted blind-spot lock list ([BlindSpotList], stored by
 * [BlindSpotStore] in the app's private files dir). The settings screen edits it; the
 * scanning service reads it to decide whether an unreadable screen in the foreground app
 * should start the blind-spot grace.
 */
object BlindSpotSettings {

    const val FILE_NAME = "blind_spot_apps.tsv"

    @Volatile
    private var cached: BlindSpotList? = null

    private fun store(context: Context) =
        BlindSpotStore(File(context.applicationContext.filesDir, FILE_NAME))

    /** The current list, read from disk on first use (seeding the defaults on first run). */
    fun get(context: Context): BlindSpotList {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val ctx = context.applicationContext
            val loaded = try {
                store(ctx).load()
            } catch (t: Throwable) {
                GuardianLog.e(ctx, "Blind-spot apps: could not read or create the list; using the defaults.", t)
                BlindSpotStore.Loaded(BlindSpotDefaults.list(), seeded = false, damaged = true)
            }
            when {
                loaded.seeded -> GuardianLog.i(ctx, "Blind-spot apps: first run, list created with ${loaded.list.size} default apps.")
                loaded.damaged -> GuardianLog.w(ctx, "Blind-spot apps: stored list unreadable; using the defaults until the next edit.")
            }
            cached = loaded.list
            return loaded.list
        }
    }

    /** Applies [change], saves it and logs it. Returns the new list. */
    fun update(context: Context, description: String, change: (BlindSpotList) -> BlindSpotList): BlindSpotList {
        val ctx = context.applicationContext
        val updated = synchronized(this) {
            val current = get(ctx)
            val next = change(current)
            if (next == current) return current
            store(ctx).save(next)
            cached = next
            next
        }
        GuardianLog.i(ctx, "Blind-spot apps: $description. Now ${updated.size} apps.")
        return updated
    }
}
