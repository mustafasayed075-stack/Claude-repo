package com.personal.guardian.scan

import java.io.File
import java.io.IOException

/**
 * One app on the **blind-spot lock** list (README "Stage 5 — blind-spot escalation"):
 * an app where Guardian locks (after the escalating grace) if it stays unable to read
 * the screen. [label] is cached when the entry is added, so neither the list screen nor
 * the scanner has to ask PackageManager for it.
 */
data class BlindSpotApp(val packageName: String, val label: String)

/**
 * The blind-spot lock list. Immutable: every edit returns a new list; a package appears
 * at most once (first wins); order is preserved. Pure Kotlin, so the logic is
 * unit-testable without Android.
 */
class BlindSpotList(entries: List<BlindSpotApp>) {

    val entries: List<BlindSpotApp> = entries.distinctBy { it.packageName }

    /** Packages where the blind-spot lock applies. */
    val packages: Set<String> = this.entries.mapTo(LinkedHashSet()) { it.packageName }

    val size: Int get() = entries.size

    operator fun contains(packageName: String): Boolean = packageName in packages

    operator fun get(packageName: String): BlindSpotApp? = entries.firstOrNull { it.packageName == packageName }

    fun add(app: BlindSpotApp): BlindSpotList =
        if (app.packageName in this) this else BlindSpotList(entries + app)

    fun remove(packageName: String): BlindSpotList =
        if (packageName in this) BlindSpotList(entries.filter { it.packageName != packageName }) else this

    /** Launchable apps not already listed, other than [ownPackage], matching [query], sorted by label. */
    fun pickerCandidates(installed: List<InstalledApp>, ownPackage: String, query: String = ""): List<InstalledApp> {
        val q = query.trim().lowercase()
        return installed
            .distinctBy { it.packageName }
            .filter { it.packageName != ownPackage && it.packageName !in this }
            .filter { q.isEmpty() || it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    override fun equals(other: Any?): Boolean = other is BlindSpotList && other.entries == entries
    override fun hashCode(): Int = entries.hashCode()
    override fun toString(): String = "BlindSpotList($entries)"

    companion object {
        val EMPTY = BlindSpotList(emptyList())
    }
}

/**
 * First-run contents: apps people commonly hide content in (and that have a
 * screenshot-protected / incognito mode or media viewer). Banks, password managers and
 * wallets are deliberately **left out** — the user adds them only if they choose to.
 */
object BlindSpotDefaults {
    val APPS: List<BlindSpotApp> = listOf(
        BlindSpotApp("org.telegram.messenger", "Telegram"),
        BlindSpotApp("org.telegram.messenger.web", "Telegram (direct download)"),
        BlindSpotApp("org.thunderdog.challegram", "Telegram X"),
        BlindSpotApp("org.telegram.plus", "Plus Messenger"),
        BlindSpotApp("tw.nekomimi.nekogram", "Nekogram"),
        BlindSpotApp("ir.ilmili.telegraph", "Telegraph"),
        BlindSpotApp("com.whatsapp", "WhatsApp"),
        BlindSpotApp("com.android.chrome", "Chrome"),
        BlindSpotApp("com.instagram.android", "Instagram"),
        BlindSpotApp("com.facebook.katana", "Facebook"),
    )

    fun list(): BlindSpotList = BlindSpotList(APPS)
}

/** Text file format: a header line, then one `package<TAB>label` line per app. */
object BlindSpotFormat {

    const val HEADER = "# Guardian Blind-spot Apps v1"

    private val PACKAGE = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*")

    fun encode(list: BlindSpotList): String = buildString {
        append(HEADER).append('\n')
        for (app in list.entries) {
            append(app.packageName).append('\t').append(cleanLabel(app.label)).append('\n')
        }
    }

    /** Parses [encode]'s output. Null if [text] isn't this format (no header); bad lines skipped. */
    fun decode(text: String): BlindSpotList? {
        val lines = text.lines()
        if (lines.firstOrNull()?.trim() != HEADER) return null
        val apps = lines.drop(1).mapNotNull { line ->
            if (line.isBlank() || line.startsWith("#")) return@mapNotNull null
            val f = line.split('\t')
            val pkg = f[0].trim()
            if (!PACKAGE.matches(pkg)) return@mapNotNull null
            val label = f.getOrNull(1)?.trim().orEmpty().ifEmpty { pkg }
            BlindSpotApp(pkg, label)
        }
        return BlindSpotList(apps)
    }

    private fun cleanLabel(label: String): String = label.replace(Regex("[\\t\\r\\n]+"), " ").trim()
}

/**
 * Stores the list in one small file in the app's private files dir (survives restarts
 * and reboots). Writes go to a temp file renamed over the real one. Pure java.io.
 */
class BlindSpotStore(private val file: File, private val defaults: () -> BlindSpotList = BlindSpotDefaults::list) {

    private val tmp: File get() = File(file.parentFile, file.name + ".tmp")

    class Loaded(val list: BlindSpotList, val seeded: Boolean, val damaged: Boolean)

    fun load(): Loaded {
        if (!file.exists() && tmp.exists()) tmp.renameTo(file) // finish an interrupted save
        if (!file.exists()) {
            val list = defaults()
            save(list)
            return Loaded(list, seeded = true, damaged = false)
        }
        val parsed = try {
            BlindSpotFormat.decode(file.readText(Charsets.UTF_8))
        } catch (e: IOException) {
            null
        }
        return if (parsed != null) Loaded(parsed, seeded = false, damaged = false)
        else Loaded(defaults(), seeded = false, damaged = true)
    }

    fun save(list: BlindSpotList) {
        file.parentFile?.mkdirs()
        val t = tmp
        t.writeText(BlindSpotFormat.encode(list), Charsets.UTF_8)
        if (!t.renameTo(file)) {
            file.delete()
            if (!t.renameTo(file)) throw IOException("could not replace $file")
        }
    }
}
