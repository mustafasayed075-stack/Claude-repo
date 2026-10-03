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

/** Pure-JVM tests for the blind-spot lock list: defaults, add/remove, format, persistence. */
class BlindSpotAppsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val tg = BlindSpotApp("org.telegram.messenger", "Telegram")
    private val wa = BlindSpotApp("com.whatsapp", "WhatsApp")

    @Test
    fun defaultsAreTheExpectedAppsAndExcludeBanks() {
        val d = BlindSpotDefaults.list()
        val pkgs = d.entries.map { it.packageName }.toSet()
        assertTrue("org.telegram.messenger" in pkgs)
        assertTrue("com.whatsapp" in pkgs)
        assertTrue("com.android.chrome" in pkgs)
        assertTrue("com.instagram.android" in pkgs)
        assertTrue("com.facebook.katana" in pkgs)
        // No banking / password-manager / wallet packages are seeded.
        assertFalse(pkgs.any { it.contains("bank") || it.contains("wallet") || it.contains("password") })
    }

    @Test
    fun addAppendsInOrderAndIgnoresDuplicates() {
        val list = BlindSpotList.EMPTY.add(tg).add(wa)
        assertEquals(listOf("org.telegram.messenger", "com.whatsapp"), list.entries.map { it.packageName })
        assertSame(list, list.add(BlindSpotApp("org.telegram.messenger", "Telegram again")))
        assertEquals("Telegram", list["org.telegram.messenger"]!!.label)
    }

    @Test
    fun removeDropsOnlyThatAppAndContainsWorks() {
        val list = BlindSpotList(listOf(tg, wa)).remove("org.telegram.messenger")
        assertEquals(listOf(wa), list.entries)
        assertFalse("org.telegram.messenger" in list)
        assertTrue("com.whatsapp" in list)
        assertSame(list, list.remove("com.example.none"))
    }

    @Test
    fun packagesReflectEntries() {
        assertEquals(setOf("org.telegram.messenger", "com.whatsapp"), BlindSpotList(listOf(tg, wa)).packages)
    }

    @Test
    fun pickerOffersUnlistedLaunchableAppsSortedAndFiltered() {
        val installed = listOf(
            InstalledApp("org.telegram.messenger", "Telegram"),
            InstalledApp("com.instagram.android", "Instagram"),
            InstalledApp("com.personal.guardian", "Guardian"),
            InstalledApp("com.example.gallery", "gallery"),
        )
        val list = BlindSpotList(listOf(tg))
        assertEquals(
            listOf("gallery", "Instagram"),
            list.pickerCandidates(installed, ownPackage = "com.personal.guardian").map { it.label }
        )
        assertEquals(listOf("Instagram"), list.pickerCandidates(installed, "com.personal.guardian", "insta").map { it.label })
    }

    @Test
    fun formatRoundTripsEntriesOrderAndLabels() {
        val list = BlindSpotList(listOf(tg, BlindSpotApp("org.example.app_1", "Name with\ttab and\nnewline")))
        val decoded = BlindSpotFormat.decode(BlindSpotFormat.encode(list))!!
        assertEquals(listOf("org.telegram.messenger", "org.example.app_1"), decoded.entries.map { it.packageName })
        assertEquals("Name with tab and newline", decoded.entries[1].label)
    }

    @Test
    fun decodeRejectsAFileWithoutTheHeaderAndSkipsBadLines() {
        assertNull(BlindSpotFormat.decode(""))
        assertNull(BlindSpotFormat.decode("com.whatsapp\tWhatsApp\n"))
        val text = BlindSpotFormat.HEADER + "\n" +
            "com.whatsapp\tWhatsApp\n" +
            "\n# comment\n" +
            "not a package!\tBad\n" +
            "org.example\n"
        val list = BlindSpotFormat.decode(text)!!
        assertEquals(listOf("com.whatsapp", "org.example"), list.entries.map { it.packageName })
        assertEquals("missing label → package name", "org.example", list["org.example"]!!.label)
    }

    @Test
    fun firstRunSeedsAndWritesTheDefaults() {
        val file = File(tmp.root, "blind_spot_apps.tsv")
        val loaded = BlindSpotStore(file).load()
        assertTrue(loaded.seeded)
        assertEquals(BlindSpotDefaults.list(), loaded.list)
        assertTrue(file.exists())
        assertFalse("seeded only once", BlindSpotStore(file).load().seeded)
    }

    @Test
    fun editsSurviveANewStoreInstance() {
        val file = File(tmp.root, "blind_spot_apps.tsv")
        BlindSpotStore(file).save(BlindSpotList(listOf(tg)))
        val reloaded = BlindSpotStore(file).load()
        assertFalse(reloaded.seeded)
        assertEquals(BlindSpotList(listOf(tg)), reloaded.list)
    }

    @Test
    fun aDamagedFileFallsBackToDefaultsWithoutBeingOverwritten() {
        val file = File(tmp.root, "blind_spot_apps.tsv").apply { writeText("garbage") }
        val loaded = BlindSpotStore(file).load()
        assertTrue(loaded.damaged)
        assertEquals(BlindSpotDefaults.list(), loaded.list)
        assertEquals("garbage", file.readText())
    }

    @Test
    fun anInterruptedSaveIsRecoveredFromTheTempFile() {
        val file = File(tmp.root, "blind_spot_apps.tsv")
        val saved = BlindSpotList(listOf(wa))
        File(tmp.root, "blind_spot_apps.tsv.tmp").writeText(BlindSpotFormat.encode(saved))
        assertEquals(saved, BlindSpotStore(file).load().list)
    }
}
