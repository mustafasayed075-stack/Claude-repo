package com.personal.guardian.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The blocked-sites log: one entry per list domain, kept for an hour after its last
 * block, at most 20 shown, newest first, clearable, and stored where backup can't reach.
 */
class BlockedDomainLogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val minute = 60_000L
    private val t0 = 1_700_000_000_000L

    @Test
    fun theConstantsAreAnHourAndTwenty() {
        assertEquals(60, BlockedDomainLog.BLOCKED_LOG_WINDOW_MINUTES)
        assertEquals(20, BlockedDomainLog.BLOCKED_LOG_MAX_SHOWN)
        assertEquals(60 * minute, BlockedDomainLog.WINDOW_MS)
    }

    @Test
    fun anEntryStaysForAnHourAfterItsLastBlockThenIsDropped() {
        val log = BlockedDomainLog()
        log.record("example.test", "com.app", "App", t0)
        assertEquals(1, log.recent(t0 + 59 * minute).size)
        assertTrue(log.recent(t0 + 60 * minute).isEmpty())
        assertTrue(log.prune(t0 + 60 * minute))
        assertEquals(0, log.size)
    }

    @Test
    fun aNewBlockRestartsTheHour() {
        val log = BlockedDomainLog()
        log.record("example.test", "com.app", "App", t0)
        log.record("example.test", "com.app", "App", t0 + 50 * minute)
        assertEquals(1, log.recent(t0 + 100 * minute).size)
        assertTrue(log.recent(t0 + 110 * minute).isEmpty())
    }

    @Test
    fun recordingAnotherDomainPrunesHourOldEntries() {
        val log = BlockedDomainLog()
        log.record("old.test", null, null, t0)
        log.record("new.test", null, null, t0 + 61 * minute)
        assertEquals(listOf("new.test"), log.entries().map { it.domain })
    }

    @Test
    fun eachDomainAppearsOnceWithCountLastTimeAndLatestApp() {
        val log = BlockedDomainLog()
        log.record("example.test", "com.a", "A", t0)
        log.record("EXAMPLE.test.", "com.a", "A", t0 + minute)
        log.record("example.test", "com.b", "B", t0 + 2 * minute)
        val e = log.recent(t0 + 3 * minute).single()
        assertEquals("example.test", e.domain)
        assertEquals(3, e.count)
        assertEquals(t0 + 2 * minute, e.lastBlockedAtMs)
        assertEquals("com.b", e.appPackage)
        assertEquals("B", e.appLabel)
    }

    @Test
    fun atMostTwentyAreShownTheNewest() {
        val log = BlockedDomainLog()
        for (i in 0 until 30) log.record("d$i.test", null, null, t0 + i * 1_000L)
        val shown = log.recent(t0 + 30_000L)
        assertEquals(20, shown.size)
        assertEquals((29 downTo 10).map { "d$it.test" }, shown.map { it.domain })
    }

    @Test
    fun newestIsFirstAndAReblockMovesADomainToTheTop() {
        val log = BlockedDomainLog()
        log.record("a.test", null, null, t0)
        log.record("b.test", null, null, t0 + 1_000)
        log.record("c.test", null, null, t0 + 2_000)
        assertEquals(listOf("c.test", "b.test", "a.test"), log.recent(t0 + 3_000).map { it.domain })
        log.record("a.test", null, null, t0 + 4_000)
        assertEquals(listOf("a.test", "c.test", "b.test"), log.recent(t0 + 5_000).map { it.domain })
    }

    @Test
    fun clearEmptiesTheLog() {
        val log = BlockedDomainLog()
        log.record("a.test", null, null, t0)
        log.record("b.test", null, null, t0)
        log.clear()
        assertEquals(0, log.size)
        assertTrue(log.recent(t0).isEmpty())
    }

    @Test
    fun clearIsSavedSoTheLogStaysEmptyAfterARestart() {
        val store = BlockedDomainLogStore(File(tmp.root, "no_backup/blocked_domains.tsv"))
        val log = BlockedDomainLog()
        log.record("a.test", "com.a", "A", t0)
        store.save(log.entries())
        assertEquals(1, BlockedDomainLog(store.load()).size)
        log.clear()
        store.save(log.entries())
        assertEquals(0, BlockedDomainLog(store.load()).size)
    }

    @Test
    fun whatIsKeptIsBounded() {
        val log = BlockedDomainLog()
        for (i in 0 until BlockedDomainLog.MAX_KEPT + 50) log.record("d$i.test", null, null, t0 + i)
        assertEquals(BlockedDomainLog.MAX_KEPT, log.size)
    }

    @Test
    fun blankDomainsAreIgnored() {
        val log = BlockedDomainLog()
        log.record("  ", null, null, t0)
        log.record(".", null, null, t0)
        assertEquals(0, log.size)
    }

    @Test
    fun storeRoundTripsEntriesAndSurvivesARestart() {
        val file = File(tmp.root, "no_backup/blocked_domains.tsv")
        val log = BlockedDomainLog()
        log.record("a.test", "com.a", "App\tWith\nOdd chars", t0)
        log.record("b.test", null, null, t0 + 1_000)
        BlockedDomainLogStore(file).save(log.entries())
        val reloaded = BlockedDomainLog(BlockedDomainLogStore(file).load())
        val shown = reloaded.recent(t0 + 2_000)
        assertEquals(listOf("b.test", "a.test"), shown.map { it.domain })
        assertEquals("App With Odd chars", shown[1].appLabel)
        assertNull(shown[0].appPackage)
        assertFalse(File(file.parentFile, file.name + ".tmp").exists())
    }

    @Test
    fun aMissingOrForeignFileLoadsEmpty() {
        val file = File(tmp.root, "blocked_domains.tsv")
        assertTrue(BlockedDomainLogStore(file).load().isEmpty())
        file.writeText("something else\n")
        assertTrue(BlockedDomainLogStore(file).load().isEmpty())
        assertNull(BlockedDomainLogFormat.decode("nope"))
    }

    // ---- backup: the log stays on the device ----

    private fun src(path: String) = File("src/main/$path").readText()

    @Test
    fun theLogFileLivesInNoBackupFilesDir() {
        val settings = src("java/com/personal/guardian/vpn/BlockedDomainLogSettings.kt")
        assertTrue(settings.contains("noBackupFilesDir, FILE_NAME"))
        assertEquals("blocked_domains.tsv", BlockedDomainLogSettings.FILE_NAME)
    }

    @Test
    fun backupAndDeviceTransferExcludeTheLog() {
        val manifest = src("AndroidManifest.xml")
        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/full_backup_content\""))

        val exclude = "<exclude domain=\"root\" path=\"no_backup/blocked_domains.tsv\" />"
        val rules = src("res/xml/data_extraction_rules.xml")
        val cloud = rules.substringAfter("<cloud-backup>").substringBefore("</cloud-backup>")
        val transfer = rules.substringAfter("<device-transfer>").substringBefore("</device-transfer>")
        assertTrue("cloud backup excludes the log", cloud.contains(exclude))
        assertTrue("device transfer excludes the log", transfer.contains(exclude))
        assertTrue("Android ≤ 11 backup excludes the log", src("res/xml/full_backup_content.xml").contains(exclude))
    }

    @Test
    fun theLogNeverTouchesTheNetwork() {
        for (f in listOf(
            "java/com/personal/guardian/vpn/BlockedDomainLog.kt",
            "java/com/personal/guardian/vpn/BlockedDomainLogSettings.kt",
            "java/com/personal/guardian/BlockedSitesActivity.kt"
        )) {
            val text = src(f)
            for (net in listOf("java.net", "okhttp", "HttpURLConnection", "Socket", "Intent.ACTION_VIEW", "CustomTabs")) {
                assertFalse("$f must not use $net", text.contains(net))
            }
        }
    }

    // ---- count window (one page load queries a domain several times) ----

    private val sec = 1_000L

    @Test
    fun oneReloadCountsOnceThreeSpacedReloadsCountThree() {
        assertEquals(15_000L, com.personal.guardian.scan.ScanConfig.BLOCKED_LOG_COUNT_WINDOW_MS)
        val log = BlockedDomainLog()
        repeat(6) { log.record("x.test", "com.browser", "Browser", t0 + it * 150L) } // one reload: 6 queries
        assertEquals(1, log.recent(t0 + sec).single().count)
        repeat(6) { log.record("x.test", "com.browser", "Browser", t0 + 20 * sec + it * 150L) }
        repeat(6) { log.record("x.test", "com.browser", "Browser", t0 + 40 * sec + it * 150L) }
        assertEquals(3, log.recent(t0 + 41 * sec).single().count)
    }

    @Test
    fun theWindowIsMeasuredFromTheLastCountedBlock() {
        val log = BlockedDomainLog()
        log.record("x.test", null, null, t0)
        log.record("x.test", null, null, t0 + 14_999)
        assertEquals(1, log.entries().single().count)
        log.record("x.test", null, null, t0 + 15_000)
        assertEquals(2, log.entries().single().count)
        // Blocks every 10 s still count every other time (not swallowed forever).
        log.record("x.test", null, null, t0 + 25_000)
        log.record("x.test", null, null, t0 + 35_000)
        assertEquals(3, log.entries().single().count)
    }

    @Test
    fun timeAndAppUpdateEvenWhenTheCountDoesNot() {
        val log = BlockedDomainLog()
        log.record("x.test", "com.a", "A", t0)
        log.record("x.test", "com.b", "B", t0 + 2 * sec)
        val e = log.entries().single()
        assertEquals(1, e.count)
        assertEquals(t0 + 2 * sec, e.lastBlockedAtMs)
        assertEquals("com.b", e.appPackage)
    }

    @Test
    fun theCountWindowSurvivesSaveAndOldFilesStillLoad() {
        val file = File(tmp.root, "no_backup/blocked_domains.tsv")
        val log = BlockedDomainLog()
        log.record("x.test", null, null, t0)
        log.record("x.test", null, null, t0 + 5 * sec)
        BlockedDomainLogStore(file).save(log.entries())
        val reloaded = BlockedDomainLog(BlockedDomainLogStore(file).load())
        reloaded.record("x.test", null, null, t0 + 10 * sec) // still inside the window of t0
        assertEquals(1, reloaded.entries().single().count)
        // A Build 45/46 line (5 fields) reads lastCounted as the last time.
        val old = BlockedDomainLogFormat.decode(BlockedDomainLogFormat.HEADER + "\nold.test\t$t0\t4\tcom.a\tA\n")!!
        assertEquals(t0, old.single().lastCountedAtMs)
        assertEquals(4, old.single().count)
    }
}
