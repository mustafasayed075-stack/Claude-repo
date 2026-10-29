package com.personal.guardian.vpn

import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.VisibleForTesting
import com.personal.guardian.util.GuardianLog
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Process-wide access to the blocked-domains log ([BlockedDomainLog], saved by
 * [BlockedDomainLogStore]). The DNS filter records each block; the "المواقع المحظورة مؤخرًا"
 * screen reads and clears it.
 *
 * The file lives in [Context.getNoBackupFilesDir] and is also excluded in
 * `res/xml/data_extraction_rules.xml` / `full_backup_content.xml`, so it never leaves
 * the device through backup or device transfer. Nothing here touches the network.
 */
object BlockedDomainLogSettings {

    const val FILE_NAME = "blocked_domains.tsv"

    /** Saves are batched: a burst of blocked queries writes the file once. */
    private const val SAVE_DELAY_MS = 2_000L

    private val lock = Any()
    private var log: BlockedDomainLog? = null
    private var saveScheduled = false
    private val labels = HashMap<String, String?>()

    // One background thread: label lookups and file writes never run on the DNS loop.
    private val io = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "rafiq-blocked-log").apply { isDaemon = true }
    }

    fun file(context: Context): File = File(context.applicationContext.noBackupFilesDir, FILE_NAME)

    private fun store(context: Context) = BlockedDomainLogStore(file(context))

    private fun loaded(ctx: Context): BlockedDomainLog {
        log?.let { return it }
        val entries = try {
            store(ctx).load()
        } catch (t: Throwable) {
            GuardianLog.w(ctx, "Blocked-sites log: could not read the file; starting empty.", t)
            emptyList()
        }
        return BlockedDomainLog(entries).also { log = it }
    }

    /**
     * Records a block of the list entry [domain] while [appPackage] was in front. Called
     * on the DNS loop; returns at once (the work runs on a background thread).
     */
    fun record(context: Context, domain: String, appPackage: String?) {
        val ctx = context.applicationContext
        val now = System.currentTimeMillis()
        io.execute {
            try {
                val label = appPackage?.let { labelFor(ctx, it) }
                synchronized(lock) {
                    loaded(ctx).record(domain, appPackage, label, now)
                }
                scheduleSave(ctx)
            } catch (t: Throwable) {
                GuardianLog.w(ctx, "Blocked-sites log: could not record a block.", t)
            }
        }
    }

    /** The newest entries of the last hour, newest first (at most [BlockedDomainLog.MAX_SHOWN]). */
    fun recent(context: Context): List<BlockedDomainLog.Entry> {
        val ctx = context.applicationContext
        val now = System.currentTimeMillis()
        val (entries, pruned) = synchronized(lock) {
            val l = loaded(ctx)
            val pruned = l.prune(now)
            l.recent(now) to pruned
        }
        if (pruned) scheduleSave(ctx)
        return entries
    }

    /** "مسح السجل": empties the log and the file. */
    fun clear(context: Context) {
        val ctx = context.applicationContext
        synchronized(lock) {
            loaded(ctx).clear()
            store(ctx).save(emptyList())
        }
        GuardianLog.i(ctx, "Blocked-sites log: cleared by the user.")
    }

    /** Tests only: forget the in-memory log so the next read loads the file again. */
    @VisibleForTesting
    internal fun resetForTests() {
        synchronized(lock) { log = null }
    }

    private fun scheduleSave(ctx: Context) {
        synchronized(lock) {
            if (saveScheduled) return
            saveScheduled = true
        }
        io.schedule({
            try {
                val entries = synchronized(lock) {
                    saveScheduled = false
                    val l = loaded(ctx)
                    l.prune(System.currentTimeMillis())
                    l.entries()
                }
                store(ctx).save(entries)
            } catch (t: Throwable) {
                GuardianLog.w(ctx, "Blocked-sites log: could not save.", t)
            }
        }, SAVE_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    /** The app's name, cached; null if the package isn't visible (the screen then shows the package). */
    private fun labelFor(ctx: Context, pkg: String): String? = labels.getOrPut(pkg) {
        try {
            val pm = ctx.packageManager
            pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString().ifBlank { null }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }
}
