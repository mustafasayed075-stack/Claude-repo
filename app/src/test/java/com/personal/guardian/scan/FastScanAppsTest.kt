package com.personal.guardian.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pure-JVM tests for the Fast Scan Apps list: defaults, add/remove, the per-app
 * text/image toggles, the picker filter, the file format and persistence.
 */
class FastScanAppsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** The current first-run default packages (messaging apps + common browsers). */
    private val defaultPackages = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.telegram.messenger.web",
        "org.thunderdog.challegram", "org.telegram.plus", "tw.nekomimi.nekogram", "ir.ilmili.telegraph",
        "com.android.chrome", "com.chrome.beta", "org.mozilla.firefox",
        "org.mozilla.firefox_beta", "org.mozilla.focus", "com.sec.android.app.sbrowser", "com.microsoft.emmx",
        "com.opera.browser", "com.opera.mini.native", "com.brave.browser", "com.duckduckgo.mobile.android",
        "com.UCMobile.intl", "com.mi.globalbrowser", "com.android.browser",
        "com.google.android.googlequicksearchbox",
    )

    private val wa = FastScanApp("com.whatsapp", "WhatsApp")
    private val chrome = FastScanApp("com.android.chrome", "Chrome")

    // ---- defaults ----

    @Test
    fun defaultsAreExactlyThePreviousHardCodedAppsWithTextAndImageOn() {
        val d = FastScanDefaults.list()
        assertEquals(defaultPackages, d.entries.map { it.packageName }.toSet())
        assertEquals(defaultPackages.size, d.size)
        assertTrue(d.entries.all { it.text && it.image && it.label.isNotBlank() })
        assertEquals(defaultPackages, d.textPackages)
        assertEquals(defaultPackages, d.imagePackages)
    }

    @Test
    fun theHardCodedListIsGoneFromScanConfig() {
        val src = File("src/main/java/com/personal/guardian/scan/ScanConfig.kt").readText()
        assertFalse(src.contains("val WATCHED_PACKAGES"))
    }

    // ---- add / remove ----

    @Test
    fun addAppendsInOrderAndIgnoresDuplicates() {
        val list = FastScanList.EMPTY.add(wa).add(chrome)
        assertEquals(listOf("com.whatsapp", "com.android.chrome"), list.entries.map { it.packageName })
        val withTextOff = list.setText("com.whatsapp", false)
        val again = withTextOff.add(FastScanApp("com.whatsapp", "WhatsApp", text = true, image = true))
        assertSame("already listed → unchanged, toggles kept", withTextOff, again)
        assertFalse(again["com.whatsapp"]!!.text)
    }

    @Test
    fun removeDropsOnlyThatApp() {
        val list = FastScanList(listOf(wa, chrome)).remove("com.whatsapp")
        assertEquals(listOf(chrome), list.entries)
        assertFalse("com.whatsapp" in list)
        assertFalse("com.whatsapp" in list.textPackages)
        assertFalse("com.whatsapp" in list.imagePackages)
        assertSame("removing an unlisted app is a no-op", list, list.remove("com.example.none"))
    }

    @Test
    fun aDefaultAppCanBeRemovedLikeAnyOther() {
        val list = FastScanDefaults.list().remove("com.whatsapp")
        assertEquals(defaultPackages.size - 1, list.size)
        assertFalse("com.whatsapp" in list.textPackages || "com.whatsapp" in list.imagePackages)
    }

    @Test
    fun constructorKeepsTheFirstOfRepeatedPackages() {
        val list = FastScanList(listOf(wa, wa.copy(text = false), chrome))
        assertEquals(2, list.size)
        assertTrue(list["com.whatsapp"]!!.text)
    }

    // ---- per-app toggles ----

    @Test
    fun textAndImageTogglesAreIndependent() {
        var list = FastScanList(listOf(wa, chrome))
        list = list.setText("com.whatsapp", false)
        assertEquals(setOf("com.android.chrome"), list.textPackages)
        assertEquals(setOf("com.whatsapp", "com.android.chrome"), list.imagePackages)

        list = list.setImage("com.android.chrome", false)
        assertEquals(setOf("com.android.chrome"), list.textPackages)
        assertEquals(setOf("com.whatsapp"), list.imagePackages)

        assertEquals(FastScanMode.IMAGE_ONLY, list["com.whatsapp"]!!.mode)
        assertEquals(FastScanMode.TEXT_ONLY, list["com.android.chrome"]!!.mode)
    }

    @Test
    fun bothTogglesOffKeepsTheAppListedButScannedByNeither() {
        val list = FastScanList(listOf(wa)).setText("com.whatsapp", false).setImage("com.whatsapp", false)
        assertTrue("com.whatsapp" in list)
        assertEquals(FastScanMode.OFF, list["com.whatsapp"]!!.mode)
        assertTrue(list.textPackages.isEmpty())
        assertTrue(list.imagePackages.isEmpty())
        assertEquals(FastScanMode.BOTH, list.setText("com.whatsapp", true).setImage("com.whatsapp", true)["com.whatsapp"]!!.mode)
    }

    @Test
    fun togglingAnUnlistedAppChangesNothing() {
        val list = FastScanList(listOf(wa))
        assertSame(list, list.setText("com.example.none", false))
        assertSame(list, list.setImage("com.example.none", true))
    }

    // ---- picker ----

    @Test
    fun pickerOffersUnlistedLaunchableAppsSortedAndFiltered() {
        val installed = listOf(
            InstalledApp("com.whatsapp", "WhatsApp"),
            InstalledApp("com.instagram.android", "Instagram"),
            InstalledApp("com.personal.guardian", "Guardian"),
            InstalledApp("com.example.gallery", "gallery"),
            InstalledApp("com.example.gallery", "gallery"),
            InstalledApp("org.videolan.vlc", "VLC"),
        )
        val list = FastScanList(listOf(wa))
        val all = list.pickerCandidates(installed, ownPackage = "com.personal.guardian")
        assertEquals(listOf("gallery", "Instagram", "VLC"), all.map { it.label })
        assertEquals(listOf("Instagram"), list.pickerCandidates(installed, "com.personal.guardian", " INSTA ").map { it.label })
        assertEquals("matches the package too", listOf("VLC"), list.pickerCandidates(installed, "com.personal.guardian", "videolan").map { it.label })
    }

    // ---- file format ----

    @Test
    fun formatRoundTripsEntriesTogglesOrderAndLabels() {
        val list = FastScanList(
            listOf(
                wa.copy(text = false),
                chrome.copy(image = false),
                FastScanApp("org.example.app_1", "Name with\ttab and\nnewline"),
            )
        )
        val decoded = FastScanFormat.decode(FastScanFormat.encode(list))!!
        assertEquals(list.entries.take(2), decoded.entries.take(2))
        assertEquals("Name with tab and newline", decoded.entries[2].label)
        assertEquals(list.textPackages, decoded.textPackages)
        assertEquals(list.imagePackages, decoded.imagePackages)
    }

    @Test
    fun anEmptyListIsStoredAsEmptyNotAsMissing() {
        assertEquals(FastScanList.EMPTY, FastScanFormat.decode(FastScanFormat.encode(FastScanList.EMPTY)))
    }

    @Test
    fun decodeRejectsAFileWithoutTheHeaderAndSkipsBadLines() {
        assertNull(FastScanFormat.decode(""))
        assertNull(FastScanFormat.decode("com.whatsapp\t1\t1\tWhatsApp\n"))
        val text = FastScanFormat.HEADER + "\n" +
            "com.whatsapp\t1\t0\tWhatsApp\n" +
            "\n# comment\n" +
            "bad line\n" +
            "com.example\tyes\t1\tBad flag\n" +
            "not a package!\t1\t1\tBad name\n" +
            "com.whatsapp\t0\t0\tDuplicate\n" +
            "org.example\t0\t1\n"
        val list = FastScanFormat.decode(text)!!
        assertEquals(listOf("com.whatsapp", "org.example"), list.entries.map { it.packageName })
        assertEquals(FastScanApp("com.whatsapp", "WhatsApp", text = true, image = false), list["com.whatsapp"])
        assertEquals("missing label → package name", "org.example", list["org.example"]!!.label)
    }

    // ---- persistence ----

    @Test
    fun firstRunSeedsAndWritesTheDefaults() {
        val file = File(tmp.root, "fast_scan_apps.tsv")
        val loaded = FastScanStore(file).load()
        assertTrue(loaded.seeded)
        assertEquals(FastScanDefaults.list(), loaded.list)
        assertTrue("defaults are written to disk", file.exists())
        val second = FastScanStore(file).load()
        assertFalse("seeded only once", second.seeded)
        assertEquals(FastScanDefaults.list(), second.list)
    }

    @Test
    fun editsSurviveANewStoreInstanceLikeARestart() {
        val file = File(tmp.root, "fast_scan_apps.tsv")
        val store = FastScanStore(file)
        val edited = store.load().list
            .remove("com.whatsapp")
            .setImage("com.android.chrome", false)
            .add(FastScanApp("com.instagram.android", "Instagram", text = false, image = true))
        store.save(edited)

        val reloaded = FastScanStore(file).load()
        assertFalse(reloaded.seeded)
        assertEquals(edited, reloaded.list)
        assertFalse("com.whatsapp" in reloaded.list)
        assertEquals(FastScanMode.TEXT_ONLY, reloaded.list["com.android.chrome"]!!.mode)
        assertEquals(FastScanMode.IMAGE_ONLY, reloaded.list["com.instagram.android"]!!.mode)
        assertFalse("no temp file left behind", File(tmp.root, "fast_scan_apps.tsv.tmp").exists())
    }

    @Test
    fun removingEveryAppIsRememberedAndDoesNotReseed() {
        val file = File(tmp.root, "fast_scan_apps.tsv")
        val store = FastScanStore(file)
        store.save(FastScanList.EMPTY)
        val reloaded = FastScanStore(file).load()
        assertFalse(reloaded.seeded)
        assertEquals(0, reloaded.list.size)
    }

    @Test
    fun aDamagedFileFallsBackToDefaultsWithoutBeingOverwritten() {
        val file = File(tmp.root, "fast_scan_apps.tsv").apply { writeText("garbage") }
        val loaded = FastScanStore(file).load()
        assertTrue(loaded.damaged)
        assertEquals(FastScanDefaults.list(), loaded.list)
        assertEquals("garbage", file.readText())
    }

    @Test
    fun anInterruptedSaveIsRecoveredFromTheTempFile() {
        val file = File(tmp.root, "fast_scan_apps.tsv")
        val saved = FastScanList(listOf(chrome))
        File(tmp.root, "fast_scan_apps.tsv.tmp").writeText(FastScanFormat.encode(saved))
        val loaded = FastScanStore(file).load()
        assertFalse(loaded.seeded)
        assertEquals(saved, loaded.list)
    }

    @Test
    fun theStoreCreatesItsDirectory() {
        val file = File(tmp.root, "nested/dir/fast_scan_apps.tsv")
        FastScanStore(file).save(FastScanList(listOf(wa)))
        assertEquals(FastScanList(listOf(wa)), FastScanStore(file).load().list)
    }

    @Test
    fun settingsKeepTheFileInPrivateStorage() {
        // filesDir survives app restarts and reboots (unlike cacheDir).
        val src = File("src/main/java/com/personal/guardian/scan/FastScanSettings.kt").readText()
        assertTrue(src.contains("filesDir, FILE_NAME"))
        assertFalse(src.contains("cacheDir"))
    }
}
