package com.personal.guardian.blocklist

import android.content.Context
import com.personal.guardian.util.GuardianLog
import java.io.BufferedReader
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

/**
 * Stage 2 — blocklist source & lookup.
 *
 * Instead of a hardcoded list, the app relies on a category-based, open-source DNS
 * blocklist (oisd NSFW) that is fetched periodically and cached on device. Nothing
 * about the user's browsing is uploaded; the only network call is an outbound GET
 * for the public list file itself.
 *
 * Responsibilities:
 *  - Download the latest list and cache it locally (atomic replace).
 *  - Parse it tolerantly: hosts-file (`0.0.0.0 domain`), ABP (`||domain^`) and
 *    plain one-domain-per-line formats are all accepted.
 *  - Answer [isBlocked] for a queried domain, matching the domain itself and any
 *    parent domain so that subdomains of a blocked domain are also blocked.
 *
 * The in-memory set is held behind an [AtomicReference] so the VPN thread can read
 * it lock-free while a background refresh swaps in a new set.
 */
object BlocklistManager {

    /**
     * oisd NSFW list, ABP/domain format. oisd publishes a curated NSFW category
     * that is updated by its maintainers; we refresh our local copy periodically.
     */
    const val DEFAULT_BLOCKLIST_URL = "https://nsfw.oisd.nl/"

    private const val CACHE_FILE_NAME = "blocklist.txt"
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val MAX_BYTES = 32L * 1024 * 1024 // guard against a runaway download

    /** Immutable snapshot of blocked domains, swapped atomically on refresh. */
    private val domains = AtomicReference<Set<String>>(emptySet())

    @Volatile
    private var loadedFromCache = false

    val size: Int get() = domains.get().size

    fun cacheFile(context: Context): File = File(context.filesDir, CACHE_FILE_NAME)

    /**
     * Ensures the in-memory set is populated, loading the cached file if present.
     * Cheap to call repeatedly; only does work the first time. Safe to call from
     * the VPN service before it starts filtering.
     */
    @Synchronized
    fun ensureLoaded(context: Context) {
        if (loadedFromCache && domains.get().isNotEmpty()) return
        val file = cacheFile(context)
        if (file.exists()) {
            runCatching {
                val parsed = file.bufferedReader().use { parse(it) }
                domains.set(parsed)
                loadedFromCache = true
                GuardianLog.i(context, "Blocklist loaded from cache: ${parsed.size} domains.")
            }.onFailure {
                GuardianLog.e(context, "Failed to load cached blocklist.", it)
            }
        } else {
            GuardianLog.w(context, "No cached blocklist yet; awaiting first refresh.")
        }
    }

    /**
     * Returns true if [host] (or one of its parent domains) is on the blocklist.
     * Matching walks up the labels: a list entry of `example.com` blocks
     * `example.com`, `www.example.com`, `cdn.ads.example.com`, etc.
     */
    fun isBlocked(host: String): Boolean = matches(domains.get(), host)

    /**
     * Pure matching function (no shared state), extracted so it can be unit-tested
     * directly. Walks the labels of [host] up to the root, matching against [set].
     */
    fun matches(set: Set<String>, host: String): Boolean {
        if (set.isEmpty()) return false
        var name = host.trim().lowercase().trimEnd('.')
        if (name.isEmpty()) return false

        while (true) {
            if (set.contains(name)) return true
            val dot = name.indexOf('.')
            if (dot < 0) return false
            name = name.substring(dot + 1)
        }
    }

    /**
     * Downloads the list from [url] and atomically replaces the cache, then swaps
     * the in-memory set. Returns the number of domains on success.
     *
     * Runs on a background thread (called from a WorkManager worker). Throws on
     * network/IO failure so the worker can apply its retry policy.
     */
    fun refreshNow(context: Context, url: String = DEFAULT_BLOCKLIST_URL): Int {
        GuardianLog.i(context, "Blocklist refresh starting from $url")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("Accept", "text/plain")
            setRequestProperty("User-Agent", "Guardian-Android/1.0 (personal)")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("Unexpected HTTP status $code fetching blocklist")
            }

            val tmp = File(context.filesDir, "$CACHE_FILE_NAME.tmp")
            var total = 0L
            connection.inputStream.buffered().use { input ->
                tmp.outputStream().buffered().use { output ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw IllegalStateException("Blocklist exceeds size cap")
                        output.write(buf, 0, n)
                    }
                }
            }

            // Parse before committing so a corrupt/empty download never clobbers a
            // good cache with something unusable.
            val parsed = tmp.bufferedReader().use { parse(it) }
            if (parsed.isEmpty()) {
                tmp.delete()
                throw IllegalStateException("Downloaded blocklist parsed to zero domains; keeping previous cache")
            }

            val cache = cacheFile(context)
            if (!tmp.renameTo(cache)) {
                // Fallback to copy if rename across the same dir somehow fails.
                tmp.copyTo(cache, overwrite = true)
                tmp.delete()
            }

            domains.set(parsed)
            loadedFromCache = true
            GuardianLog.i(context, "Blocklist refresh OK: ${parsed.size} domains (${total} bytes).")
            return parsed.size
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Tolerant parser. Accepts, per line:
     *  - comments starting with `#` or `!` (ignored)
     *  - hosts format: `0.0.0.0 domain` / `127.0.0.1 domain`
     *  - ABP format:   `||domain^`
     *  - plain domain: `domain`
     * Localhost/loopback and obviously invalid tokens are skipped.
     */
    fun parse(reader: BufferedReader): Set<String> {
        val out = HashSet<String>(1 shl 16)
        reader.forEachLine { raw ->
            var line = raw.trim()
            if (line.isEmpty()) return@forEachLine
            val first = line[0]
            if (first == '#' || first == '!') return@forEachLine

            // ABP style: ||example.com^  (optionally with modifiers after '^')
            if (line.startsWith("||")) {
                line = line.removePrefix("||")
                val caret = line.indexOf('^')
                if (caret >= 0) line = line.substring(0, caret)
            } else if (line.contains(' ') || line.contains('\t')) {
                // hosts format: take the token after the IP address.
                val parts = line.split(Regex("\\s+"))
                if (parts.size < 2) return@forEachLine
                line = parts[1]
            }

            val domain = line.lowercase().trimEnd('.')
            if (isPlausibleDomain(domain)) out.add(domain)
        }
        return out
    }

    private fun isPlausibleDomain(d: String): Boolean {
        if (d.isEmpty() || d.length > 253) return false
        if (d == "localhost" || d == "localhost.localdomain" || d == "local") return false
        if (!d.contains('.')) return false
        // Reject IP addresses and anything with characters outside a hostname.
        for (c in d) {
            if (!(c.isLetterOrDigit() || c == '.' || c == '-' || c == '_')) return false
        }
        // A bare IPv4 like 0.0.0.0 would pass the char test; reject all-numeric labels.
        val labels = d.split('.')
        if (labels.all { it.isNotEmpty() && it.all { ch -> ch.isDigit() } }) return false
        return labels.none { it.isEmpty() }
    }
}
