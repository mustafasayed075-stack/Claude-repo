package com.personal.guardian.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.personal.guardian.scan.ScanLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records *why* Guardian's previous process ended, using the system's own
 * ApplicationExitInfo records (Android 11+). A service that keeps "reconnecting"
 * is usually the process being killed and restarted (crash, native crash, low
 * memory, ANR…); this puts the exact reason in the event log.
 *
 * Safe to call from several components on each process start: every exit record
 * is logged once (tracked by timestamp in private prefs). Lines go to the separate
 * diagnostics log as well, so high-volume logging can't rotate them away.
 */
object ProcessDiagnostics {

    private const val PREFS = "guardian_diagnostics"
    private const val KEY_LAST_EXIT_TS = "last_logged_exit_ts"
    private const val MAX_RECORDS = 5

    /** Seconds since this process started (API 24+), or null if unknown. */
    fun processAgeSeconds(): Long? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) / 1000
        } else null

    @Synchronized
    fun logPreviousExitsIfNew(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val am = ContextCompat.getSystemService(context, ActivityManager::class.java) ?: return
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val lastLogged = prefs.getLong(KEY_LAST_EXIT_TS, 0L)
            val fresh = am.getHistoricalProcessExitReasons(context.packageName, 0, MAX_RECORDS)
                .filter { it.timestamp > lastLogged }
                .sortedBy { it.timestamp }
            if (fresh.isEmpty()) return

            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            for (info in fresh) {
                GuardianLog.w(
                    context,
                    "Previous Guardian process (pid ${info.pid}) ended at ${fmt.format(Date(info.timestamp))}: " +
                        "reason=${ScanLog.exitReasonName(info.reason)}" +
                        (info.description?.let { " ($it)" } ?: "") +
                        ", status=${info.status}, importance=${info.importance}, pss=${info.pss} KB",
                    diagnostic = true
                )
            }
            prefs.edit().putLong(KEY_LAST_EXIT_TS, fresh.last().timestamp).apply()
        } catch (t: Throwable) {
            GuardianLog.w(context, "Could not read previous process exit reasons.", t, diagnostic = true)
        }
    }
}
