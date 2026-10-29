package com.personal.guardian.vpn

import java.io.File
import java.io.IOException

/**
 * The on-device log of recently blocked domains, shown under "المواقع المحظورة مؤخرًا".
 *
 * One entry per **blocklist domain** — the list entry that matched, not the full host
 * (DNS never sees URLs anyway): `cdn.example.com` and `www.example.com` both count for
 * `example.com`. Each entry keeps the app that was in front at the latest block (package
 * and label), the last time, and how many times it was blocked since it entered the log.
 * An entry is dropped once it hasn't been blocked for [WINDOW_MS]. Local only: nothing
 * here is ever sent anywhere.
 *
 * Pure Kotlin with injected timestamps; callers serialise access ([BlockedDomainLogSettings]).
 */
class BlockedDomainLog(entries: Collection<Entry> = emptyList()) {

    data class Entry(
        val domain: String,
        val appPackage: String?,
        val appLabel: String?,
        val lastBlockedAtMs: Long,
        val count: Int
    )

    private val byDomain = LinkedHashMap<String, Entry>().apply { entries.forEach { put(it.domain, it) } }

    val size: Int get() = byDomain.size

    /** A block of [domain] at [nowMs] while [appPackage] / [appLabel] was in front. */
    fun record(domain: String, appPackage: String?, appLabel: String?, nowMs: Long) {
        val key = normalise(domain)
        if (key.isEmpty()) return
        prune(nowMs)
        val prev = byDomain.remove(key)
        byDomain[key] = Entry(key, appPackage, appLabel, nowMs, (prev?.count ?: 0) + 1)
        // Bound what is kept (only the newest [MAX_SHOWN] are displayed anyway).
        while (byDomain.size > MAX_KEPT) byDomain.remove(byDomain.keys.first())
    }

    /** Drops entries not blocked within the last [WINDOW_MS]. Returns whether any were dropped. */
    fun prune(nowMs: Long): Boolean {
        val cutoff = nowMs - WINDOW_MS
        val before = byDomain.size
        byDomain.values.removeAll { it.lastBlockedAtMs <= cutoff }
        return byDomain.size != before
    }

    /** The newest [MAX_SHOWN] entries of the last hour, newest first. */
    fun recent(nowMs: Long): List<Entry> {
        val cutoff = nowMs - WINDOW_MS
        return byDomain.values.filter { it.lastBlockedAtMs > cutoff }
            .sortedByDescending { it.lastBlockedAtMs }
            .take(MAX_SHOWN)
    }

    fun clear() = byDomain.clear()

    /** Every entry kept (for saving), oldest first. */
    fun entries(): List<Entry> = byDomain.values.sortedBy { it.lastBlockedAtMs }

    companion object {
        /** How long a domain stays in the log after its last block. */
        const val BLOCKED_LOG_WINDOW_MINUTES = 60

        /** How many entries the screen shows (newest first). */
        const val BLOCKED_LOG_MAX_SHOWN = 20

        const val WINDOW_MS = BLOCKED_LOG_WINDOW_MINUTES * 60_000L
        const val MAX_SHOWN = BLOCKED_LOG_MAX_SHOWN

        /** Upper bound on entries kept between prunes. */
        const val MAX_KEPT = 200

        fun normalise(domain: String): String = domain.trim().lowercase().trimEnd('.')
    }
}

/** Text file format: a header line, then `domain<TAB>lastMs<TAB>count<TAB>package<TAB>label`. */
object BlockedDomainLogFormat {

    const val HEADER = "# Rafiq blocked domains v1"

    fun encode(entries: List<BlockedDomainLog.Entry>): String = buildString {
        append(HEADER).append('\n')
        for (e in entries) {
            append(clean(e.domain)).append('\t')
                .append(e.lastBlockedAtMs).append('\t')
                .append(e.count).append('\t')
                .append(clean(e.appPackage.orEmpty())).append('\t')
                .append(clean(e.appLabel.orEmpty())).append('\n')
        }
    }

    /** Parses [encode]'s output; null if [text] isn't this format. Bad lines are skipped. */
    fun decode(text: String): List<BlockedDomainLog.Entry>? {
        val lines = text.lines()
        if (lines.firstOrNull()?.trim() != HEADER) return null
        return lines.drop(1).mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val f = line.split('\t')
            if (f.size < 3) return@mapNotNull null
            val domain = BlockedDomainLog.normalise(f[0])
            val last = f[1].toLongOrNull() ?: return@mapNotNull null
            val count = f[2].toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            if (domain.isEmpty()) return@mapNotNull null
            BlockedDomainLog.Entry(domain, f.getOrNull(3)?.ifBlank { null }, f.getOrNull(4)?.ifBlank { null }, last, count)
        }
    }

    private fun clean(s: String) = s.replace(Regex("[\\t\\r\\n]+"), " ").trim()
}

/** Saves the log to one small file (temp file + rename). Pure java.io. */
class BlockedDomainLogStore(private val file: File) {

    private val tmp: File get() = File(file.parentFile, file.name + ".tmp")

    fun load(): List<BlockedDomainLog.Entry> {
        if (!file.exists() && tmp.exists()) tmp.renameTo(file)
        if (!file.exists()) return emptyList()
        return try {
            BlockedDomainLogFormat.decode(file.readText(Charsets.UTF_8)).orEmpty()
        } catch (e: IOException) {
            emptyList()
        }
    }

    fun save(entries: List<BlockedDomainLog.Entry>) {
        file.parentFile?.mkdirs()
        val t = tmp
        t.writeText(BlockedDomainLogFormat.encode(entries), Charsets.UTF_8)
        if (!t.renameTo(file)) {
            file.delete()
            if (!t.renameTo(file)) throw IOException("could not replace $file")
        }
    }
}
