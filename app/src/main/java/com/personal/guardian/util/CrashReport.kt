package com.personal.guardian.util

import android.content.Context
import android.os.Process
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * A crash summary for the event log, so "نسخ اللوج" shows why the app closed (the user
 * can't read logcat). Only the exception **class names** and stack frames
 * (`class.method(File.kt:line)`) are kept — never `Throwable.message`, which can carry
 * arbitrary text. Pure Kotlin, so the format is unit-tested.
 */
object CrashReport {

    /** Stack frames kept per exception. */
    const val MAX_FRAMES = 15

    /** "Caused by" levels kept: Android wraps launch failures, the real one is a cause. */
    const val MAX_CAUSES = 3

    fun format(error: Throwable, threadName: String, maxFrames: Int = MAX_FRAMES): String = buildString {
        append("CRASH on thread \"").append(clean(threadName)).append("\": ").append(error.javaClass.name)
        appendFrames(error, maxFrames)
        var cause = error.cause
        var depth = 0
        val seen = HashSet<Throwable>().apply { add(error) }
        while (cause != null && depth < MAX_CAUSES && seen.add(cause)) {
            append("\n  Caused by: ").append(cause.javaClass.name)
            appendFrames(cause, maxFrames)
            cause = cause.cause
            depth++
        }
    }

    private fun StringBuilder.appendFrames(t: Throwable, maxFrames: Int) {
        val frames = t.stackTrace
        frames.take(maxFrames).forEach { append("\n    at ").append(it.toString()) }
        if (frames.size > maxFrames) append("\n    … ").append(frames.size - maxFrames).append(" more")
    }

    private fun clean(s: String) = s.replace(Regex("[\\r\\n\\t]+"), " ").take(80)
}

/**
 * Records an uncaught exception ([CrashReport]) in the event log and in a small
 * "last crash" file before the process dies, then hands over to the previous handler
 * (Android's, which shows the crash and kills the process).
 */
object CrashLogger {

    const val LAST_CRASH_FILE = "last_crash.txt"

    fun lastCrashFile(context: Context) = File(context.applicationContext.filesDir, LAST_CRASH_FILE)

    /** Installs the handler once per process (from the Application). */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Handler) return
        Thread.setDefaultUncaughtExceptionHandler(Handler(app, previous))
    }

    /** Writes [error] to the log and the last-crash file. Never throws. */
    fun record(context: Context, threadName: String, error: Throwable) {
        val app = context.applicationContext
        val report = try {
            CrashReport.format(error, threadName)
        } catch (t: Throwable) {
            "CRASH on thread \"$threadName\": ${error.javaClass.name} (stack unavailable)"
        }
        runCatching {
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            lastCrashFile(app).writeText("$stamp $report\n")
        }
        // No Throwable passed: GuardianLog would append its message.
        runCatching { GuardianLog.e(app, report, diagnostic = true) }
    }

    /** The last recorded crash, or null if there is none. */
    fun lastCrash(context: Context): String? =
        runCatching { lastCrashFile(context).takeIf { it.isFile }?.readText()?.trim() }.getOrNull()?.ifBlank { null }

    private class Handler(
        private val app: Context,
        private val previous: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            record(app, thread.name, error)
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }
}
