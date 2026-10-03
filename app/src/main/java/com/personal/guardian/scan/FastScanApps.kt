package com.personal.guardian.scan

import java.io.File
import java.io.IOException

/**
 * One app in the user's **Fast Scan Apps** list (README "Fast Scan Apps"), with two
 * independent toggles that apply while the app is in the foreground:
 *  - [text]: Stage 4 text checks on window-content changes;
 *  - [image]: Stage 3 fast capture ([ScanConfig.FAST_INTERVAL_MS] instead of the
 *    baseline [ScanConfig.BASELINE_INTERVAL_MS]).
 *
 * [label] is the name shown in the app, cached when the entry is added so neither
 * the list screen nor the scanner has to query PackageManager for it.
 */
data class FastScanApp(
    val packageName: String,
    val label: String,
    val text: Boolean = true,
    val image: Boolean = true
) {
    val mode: FastScanMode
        get() = when {
            text && image -> FastScanMode.BOTH
            text -> FastScanMode.TEXT_ONLY
            image -> FastScanMode.IMAGE_ONLY
            else -> FastScanMode.OFF
        }
}

/** What an entry's toggles add on top of the baseline scan. */
enum class FastScanMode { BOTH, TEXT_ONLY, IMAGE_ONLY, OFF }

/** A launchable installed app, as offered by the "+" picker. */
data class InstalledApp(val packageName: String, val label: String)

/**
 * The Fast Scan Apps list. Immutable: every edit returns a new list. Entries keep
 * the order they were added in; a package appears at most once (first wins).
 * Pure Kotlin, so the list logic is unit-testable without Android.
 */
class FastScanList(entries: List<FastScanApp>) {

    val entries: List<FastScanApp> = entries.distinctBy { it.packageName }

    /** Packages whose text is checked on content changes (Stage 4 trigger). */
    val textPackages: Set<String> = this.entries.filter { it.text }.mapTo(LinkedHashSet()) { it.packageName }

    /** Packages that switch screen capture to the fast interval (Stage 3 trigger). */
    val imagePackages: Set<String> = this.entries.filter { it.image }.mapTo(LinkedHashSet()) { it.packageName }

    val size: Int get() = entries.size

    operator fun contains(packageName: String): Boolean = entries.any { it.packageName == packageName }

    operator fun get(packageName: String): FastScanApp? = entries.firstOrNull { it.packageName == packageName }

    /** Adds [app] at the end. Already listed → unchanged (its toggles are kept). */
    fun add(app: FastScanApp): FastScanList =
        if (app.packageName in this) this else FastScanList(entries + app)

    /** Removes [packageName]; not listed → unchanged. */
    fun remove(packageName: String): FastScanList =
        if (packageName in this) FastScanList(entries.filter { it.packageName != packageName }) else this

    fun setText(packageName: String, on: Boolean): FastScanList = edit(packageName) { it.copy(text = on) }

    fun setImage(packageName: String, on: Boolean): FastScanList = edit(packageName) { it.copy(image = on) }

    private fun edit(packageName: String, change: (FastScanApp) -> FastScanApp): FastScanList =
        if (packageName in this) FastScanList(entries.map { if (it.packageName == packageName) change(it) else it })
        else this

    /**
     * Apps the picker offers: launchable apps not already listed, other than Guardian
     * itself ([ownPackage]), whose label or package contains [query] (any case),
     * sorted by label.
     */
    fun pickerCandidates(installed: List<InstalledApp>, ownPackage: String, query: String = ""): List<InstalledApp> {
        val q = query.trim().lowercase()
        return installed
            .distinctBy { it.packageName }
            .filter { it.packageName != ownPackage && it.packageName !in this }
            .filter { q.isEmpty() || it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    override fun equals(other: Any?): Boolean = other is FastScanList && other.entries == entries
    override fun hashCode(): Int = entries.hashCode()
    override fun toString(): String = "FastScanList($entries)"

    companion object {
        val EMPTY = FastScanList(emptyList())
    }
}

/**
 * The first-run contents of the list: exactly the apps that were hard-coded for fast
 * scanning before the list became editable (messaging apps and common browsers),
 * all with text and image on, so upgrading doesn't change what is scanned.
 */
object FastScanDefaults {
    val APPS: List<FastScanApp> = listOf(
        // Messaging
        FastScanApp("com.whatsapp", "WhatsApp"),
        FastScanApp("com.whatsapp.w4b", "WhatsApp Business"),
        FastScanApp("org.telegram.messenger", "Telegram"),
        FastScanApp("org.telegram.messenger.web", "Telegram (direct download)"),
        FastScanApp("org.thunderdog.challegram", "Telegram X"),
        FastScanApp("org.telegram.plus", "Plus Messenger"),
        FastScanApp("tw.nekomimi.nekogram", "Nekogram"),
        FastScanApp("ir.ilmili.telegraph", "Telegraph"),
        // Browsers
        FastScanApp("com.android.chrome", "Chrome"),
        FastScanApp("com.chrome.beta", "Chrome Beta"),
        FastScanApp("org.mozilla.firefox", "Firefox"),
        FastScanApp("org.mozilla.firefox_beta", "Firefox Beta"),
        FastScanApp("org.mozilla.focus", "Firefox Focus"),
        FastScanApp("com.sec.android.app.sbrowser", "Samsung Internet"),
        FastScanApp("com.microsoft.emmx", "Microsoft Edge"),
        FastScanApp("com.opera.browser", "Opera"),
        FastScanApp("com.opera.mini.native", "Opera Mini"),
        FastScanApp("com.brave.browser", "Brave"),
        FastScanApp("com.duckduckgo.mobile.android", "DuckDuckGo"),
        FastScanApp("com.UCMobile.intl", "UC Browser"),
        FastScanApp("com.mi.globalbrowser", "Mi Browser"),
        FastScanApp("com.android.browser", "Browser"),
    )

    fun list(): FastScanList = FastScanList(APPS)
}

/**
 * Text file format of the stored list: a header line, then one tab-separated line
 * per app: `package<TAB>text 0/1<TAB>image 0/1<TAB>label`.
 */
object FastScanFormat {

    const val HEADER = "# Guardian Fast Scan Apps v1"

    private val PACKAGE = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*")

    fun encode(list: FastScanList): String = buildString {
        append(HEADER).append('\n')
        for (app in list.entries) {
            append(app.packageName).append('\t')
                .append(if (app.text) '1' else '0').append('\t')
                .append(if (app.image) '1' else '0').append('\t')
                .append(cleanLabel(app.label)).append('\n')
        }
    }

    /**
     * Parses [encode]'s output. Returns null if [text] isn't this format (no header),
     * so a damaged file is never mistaken for an empty list. Malformed lines are
     * skipped; a repeated package keeps its first line.
     */
    fun decode(text: String): FastScanList? {
        val lines = text.lines()
        if (lines.firstOrNull()?.trim() != HEADER) return null
        val apps = lines.drop(1).mapNotNull { line ->
            if (line.isBlank() || line.startsWith("#")) return@mapNotNull null
            val f = line.split('\t')
            if (f.size < 3) return@mapNotNull null
            val pkg = f[0].trim()
            val textOn = flag(f[1]) ?: return@mapNotNull null
            val imageOn = flag(f[2]) ?: return@mapNotNull null
            if (!PACKAGE.matches(pkg)) return@mapNotNull null
            val label = f.getOrNull(3)?.trim().orEmpty().ifEmpty { pkg }
            FastScanApp(pkg, label, textOn, imageOn)
        }
        return FastScanList(apps)
    }

    private fun flag(s: String): Boolean? = when (s.trim()) {
        "1" -> true
        "0" -> false
        else -> null
    }

    private fun cleanLabel(label: String): String =
        label.replace(Regex("[\\t\\r\\n]+"), " ").trim()
}

/**
 * Stores the list in one small local file (the app's private files dir, so it
 * survives app restarts and reboots). Writes go to a temporary file that is then
 * renamed over the real one, so a crash mid-write can't leave a half-written list.
 * Pure java.io: testable on the JVM.
 */
class FastScanStore(private val file: File, private val defaults: () -> FastScanList = FastScanDefaults::list) {

    private val tmp: File get() = File(file.parentFile, file.name + ".tmp")

    class Loaded(val list: FastScanList, val seeded: Boolean, val damaged: Boolean)

    /**
     * Reads the list. On first run (no file yet) the defaults are written and
     * returned (`seeded`). An unreadable or damaged file also yields the defaults
     * (`damaged`) but is left in place, to be replaced by the next edit.
     */
    fun load(): Loaded {
        if (!file.exists() && tmp.exists()) tmp.renameTo(file) // finish an interrupted save
        if (!file.exists()) {
            val list = defaults()
            save(list)
            return Loaded(list, seeded = true, damaged = false)
        }
        val parsed = try {
            FastScanFormat.decode(file.readText(Charsets.UTF_8))
        } catch (e: IOException) {
            null
        }
        return if (parsed != null) Loaded(parsed, seeded = false, damaged = false)
        else Loaded(defaults(), seeded = false, damaged = true)
    }

    fun save(list: FastScanList) {
        file.parentFile?.mkdirs()
        val t = tmp
        t.writeText(FastScanFormat.encode(list), Charsets.UTF_8)
        if (!t.renameTo(file)) {
            file.delete()
            if (!t.renameTo(file)) throw IOException("could not replace $file")
        }
    }
}
