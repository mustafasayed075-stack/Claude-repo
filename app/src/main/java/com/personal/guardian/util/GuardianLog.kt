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
 * The logger is intentionally dependency-free and synchronized so it can be called
 * safely from the main thread, background services, the VPN thread and WorkManager
 * workers alike. Writes are best-effort: a logging failure must never crash a
 * caller (least of all the persistent service or the VPN loop).
 */
object GuardianLog {

    private const val TAG = "Guardian"
    private const val LOG_FILE_NAME = "guardian-events.log"

    /** Cap the log so it can never grow without bound on a long-lived device. */
    private const val MAX_LOG_BYTES = 1L * 1024 * 1024 // 1 MiB
    private const val TRIM_TO_BYTES = 512L * 1024      // keep newest ~512 KiB on rotation

    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    /** Absolute path to the log file, for surfacing in the UI. */
    fun logFile(context: Context): File = File(context.filesDir, LOG_FILE_NAME)

    fun i(context: Context, message: String) = write(context, "INFO", message, null)

    fun w(context: Context, message: String, t: Throwable? = null) = write(context, "WARN", message, t)

    fun e(context: Context, message: String, t: Throwable? = null) = write(context, "ERROR", message, t)

    private fun write(context: Context, level: String, message: String, t: Throwable?) {
        // Mirror to logcat for live debugging during development.
        when (level) {
            "ERROR" -> Log.e(TAG, message, t)
            "WARN" -> Log.w(TAG, message, t)
            else -> Log.i(TAG, message)
        }

        synchronized(lock) {
            try {
                val file = logFile(context)
                rotateIfNeeded(file)
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
                file.appendText(line)
            } catch (io: Throwable) {
                // Never let logging take down the caller.
                Log.e(TAG, "Failed to write event log", io)
            }
        }
    }

    /** Reads the whole log back (newest content included) for display in the UI. */
    fun readAll(context: Context): String = synchronized(lock) {
        val file = logFile(context)
        if (file.exists()) runCatching { file.readText() }.getOrDefault("") else ""
    }

    private fun rotateIfNeeded(file: File) {
        if (!file.exists() || file.length() < MAX_LOG_BYTES) return
        runCatching {
            val bytes = file.readBytes()
            val start = (bytes.size - TRIM_TO_BYTES.toInt()).coerceAtLeast(0)
            val trimmed = bytes.copyOfRange(start, bytes.size)
            file.writeBytes(trimmed)
            file.appendText("\n--- log rotated ${timestampFormat.format(Date())} ---\n")
        }
    }
}
