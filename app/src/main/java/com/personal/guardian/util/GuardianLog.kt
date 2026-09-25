package com.personal.guardian.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Append-only local event log.
 *
 * Every important event — device-admin activation/deactivation attempts, service
 * lifecycle, blocklist refreshes and failures — is written here with a timestamp.
 * The file lives in the app's private storage and never leaves the device, which
 * satisfies the spec's "log to a local file for review" requirement while keeping
 * all data on-device.
 *
 * Messages logged with `diagnostic = true` (process-exit reasons, accessibility
 * service lifecycle) are additionally written to a separate, small diagnostics log
 * with its own rotation. High-volume lines (e.g. per-frame scan scores) only go to
 * the main log, so they can never trim the diagnostics out.
 *
 * The logger is intentionally dependency-free and synchronized so it can be called
 * safely from the main thread, background services, the VPN thread and WorkManager
 * workers alike. Writes are best-effort: a logging failure must never crash a
 * caller (least of all the persistent service or the VPN loop).
 */
object GuardianLog {

    private const val TAG = "Guardian"
    private const val LOG_FILE_NAME = "guardian-events.log"
    private const val DIAGNOSTICS_FILE_NAME = "guardian-diagnostics.log"

    /** Cap the log so it can never grow without bound on a long-lived device. */
    private const val MAX_LOG_BYTES = 1L * 1024 * 1024 // 1 MiB
    private const val TRIM_TO_BYTES = 512L * 1024      // keep newest ~512 KiB on rotation

    /** Diagnostics are rare (a few lines per process restart): ~256 KiB is weeks+ of history. */
    private const val MAX_DIAGNOSTICS_BYTES = 256L * 1024
    private const val DIAGNOSTICS_TRIM_TO_BYTES = 192L * 1024

    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    /** Absolute path to the log file, for surfacing in the UI. */
    fun logFile(context: Context): File = File(context.filesDir, LOG_FILE_NAME)

    /** Separate diagnostics log (process exits, accessibility service lifecycle). */
    fun diagnosticsFile(context: Context): File = File(context.filesDir, DIAGNOSTICS_FILE_NAME)

    fun i(context: Context, message: String, diagnostic: Boolean = false) =
        write(context, "INFO", message, null, diagnostic)

    fun w(context: Context, message: String, t: Throwable? = null, diagnostic: Boolean = false) =
        write(context, "WARN", message, t, diagnostic)

    fun e(context: Context, message: String, t: Throwable? = null, diagnostic: Boolean = false) =
        write(context, "ERROR", message, t, diagnostic)

    private fun write(context: Context, level: String, message: String, t: Throwable?, diagnostic: Boolean) {
        // Mirror to logcat for live debugging during development.
        when (level) {
            "ERROR" -> Log.e(TAG, message, t)
            "WARN" -> Log.w(TAG, message, t)
            else -> Log.i(TAG, message)
        }

        synchronized(lock) {
            try {
                val line = buildString {
                    append(timestampFormat.format(Date()))
                    append(' ')
                    append(level)
                    append(' ')
                    append(message)
                    if (t != null) {
                        append(" | ")
                        append(t.javaClass.simpleName)
                        append(": ")
                        append(t.message)
                    }
                    append('\n')
                }
                LogFiles.append(logFile(context), line, MAX_LOG_BYTES, TRIM_TO_BYTES, rotationMarker())
                if (diagnostic) {
                    LogFiles.append(
                        diagnosticsFile(context), line,
                        MAX_DIAGNOSTICS_BYTES, DIAGNOSTICS_TRIM_TO_BYTES, rotationMarker()
                    )
                }
            } catch (io: Throwable) {
                // Never let logging take down the caller.
                Log.e(TAG, "Failed to write event log", io)
            }
        }
    }

    /** Reads the whole log back (newest content included) for display in the UI. */
    fun readAll(context: Context): String = read(logFile(context))

    /** Reads the whole diagnostics log back for display in the UI. */
    fun readDiagnostics(context: Context): String = read(diagnosticsFile(context))

    private fun read(file: File): String = synchronized(lock) {
        if (file.exists()) runCatching { file.readText() }.getOrDefault("") else ""
    }

    private fun rotationMarker() = "--- log rotated ${timestampFormat.format(Date())} ---\n"
}
