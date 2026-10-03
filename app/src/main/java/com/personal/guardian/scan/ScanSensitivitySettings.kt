package com.personal.guardian.scan

import android.content.Context
import com.personal.guardian.util.GuardianLog
import java.io.File

/**
 * Process-wide access to the persisted suggestive-tier sensitivity ([ScanSensitivity],
 * stored by [ScanSensitivityStore] in the app's private files dir). The settings screen
 * sets it; the scanning service reads it per frame to judge the suggestive tier against
 * the current threshold, so a change applies without restarting the service.
 */
object ScanSensitivitySettings {

    const val FILE_NAME = "scan_sensitivity.txt"

    @Volatile
    private var cached: ScanSensitivity? = null

    private fun store(context: Context) =
        ScanSensitivityStore(File(context.applicationContext.filesDir, FILE_NAME))

    /** The current sensitivity, read from disk on first use (the default on first run). */
    fun get(context: Context): ScanSensitivity {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val ctx = context.applicationContext
            val loaded = try {
                store(ctx).load()
            } catch (t: Throwable) {
                GuardianLog.e(ctx, "Scan sensitivity: could not read the setting; using ${ScanSensitivity.DEFAULT.id}.", t)
                ScanSensitivity.DEFAULT
            }
            cached = loaded
            return loaded
        }
    }

    /** The current suggestive-tier signal threshold. */
    fun suggestiveThreshold(context: Context): Float = get(context).suggestiveThreshold

    /** Persists [sensitivity] and logs the change. Returns it. */
    fun set(context: Context, sensitivity: ScanSensitivity): ScanSensitivity {
        val ctx = context.applicationContext
        synchronized(this) {
            if (cached == sensitivity) return sensitivity
            store(ctx).save(sensitivity)
            cached = sensitivity
        }
        GuardianLog.i(
            ctx,
            "Scan sensitivity: set to ${sensitivity.id} (suggestive threshold ${sensitivity.suggestiveThreshold})."
        )
        return sensitivity
    }
}
