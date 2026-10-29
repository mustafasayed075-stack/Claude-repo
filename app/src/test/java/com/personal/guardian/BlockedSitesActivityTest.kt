package com.personal.guardian

import android.content.ComponentName
import android.os.Looper
import android.view.View
import android.widget.ListView
import android.widget.TextView
import androidx.work.Configuration
import androidx.work.WorkManager
import com.personal.guardian.vpn.BlockedDomainLogFormat
import com.personal.guardian.vpn.BlockedDomainLogSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

/**
 * Opens the "المواقع المحظورة مؤخرًا" screen for real (Robolectric) with the log file
 * missing, empty, normal and with a damaged line. Build 45 crashed on open: the
 * adapter, created as a field, asked the not-yet-attached Activity for its time format.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BlockedSitesActivityTest {

    private val context get() = RuntimeEnvironment.getApplication()
    private val file get() = BlockedDomainLogSettings.file(context)

    @Before
    fun setUp() {
        BlockedDomainLogSettings.resetForTests()
        file.delete()
    }

    @After
    fun tearDown() {
        BlockedDomainLogSettings.resetForTests()
        file.delete()
    }

    private fun open(): BlockedSitesActivity =
        Robolectric.buildActivity(BlockedSitesActivity::class.java).setup().get()

    private fun BlockedSitesActivity.list() = findViewById<ListView>(R.id.listBlockedSites)
    private fun BlockedSitesActivity.empty() = findViewById<TextView>(R.id.txtBlockedSitesEmpty)
    private fun BlockedSitesActivity.clear() = findViewById<View>(R.id.btnClearLog)

    private fun BlockedSitesActivity.rowTexts(position: Int): List<String> {
        val list = list()
        val row = list.adapter.getView(position, null, list)
        return listOf(R.id.txtDomain, R.id.txtApp, R.id.txtTime, R.id.txtCount)
            .map { row.findViewById<TextView>(it).text.toString() }
    }

    private fun writeLog(vararg lines: String) {
        file.parentFile?.mkdirs()
        file.writeText(BlockedDomainLogFormat.HEADER + "\n" + lines.joinToString("\n") + "\n")
    }

    private fun assertEmptyState(a: BlockedSitesActivity) {
        assertEquals(0, a.list().adapter.count)
        assertEquals(View.VISIBLE, a.empty().visibility)
        assertEquals("مفيش مواقع اتحظرت في آخر ساعة", a.empty().text.toString())
        assertFalse(a.clear().isEnabled)
    }

    @Test
    fun theScreenIsDeclaredInTheManifest() {
        val info = context.packageManager.getActivityInfo(ComponentName(context, BlockedSitesActivity::class.java), 0)
        assertEquals("com.personal.guardian.BlockedSitesActivity", info.name)
    }

    @Test
    fun opensWithNoLogFile() {
        assertFalse(file.exists())
        assertEmptyState(open())
    }

    @Test
    fun opensWithAnEmptyLogFile() {
        file.parentFile?.mkdirs()
        file.writeText("")
        assertEmptyState(open())
    }

    @Test
    fun opensWithNormalEntriesNewestFirst() {
        val now = System.currentTimeMillis()
        writeLog(
            "older.test\t${now - 120_000}\t2\tcom.example.browser\tBrowser",
            "newer.test\t${now - 10_000}\t5\tcom.example.chat\t"
        )
        val a = open()
        assertEquals(2, a.list().adapter.count)
        assertEquals(View.GONE, a.empty().visibility)
        assertTrue(a.clear().isEnabled)
        val first = a.rowTexts(0)
        assertEquals("newer.test", first[0])
        assertEquals("com.example.chat", first[1]) // no label stored → the package
        assertTrue(first[2].isNotBlank())
        assertEquals("5 مرة", first[3])
        assertEquals(listOf("older.test", "Browser"), a.rowTexts(1).take(2))
    }

    @Test
    fun opensWithADamagedLineAndShowsTheGoodOnes() {
        val now = System.currentTimeMillis()
        writeLog(
            "good.test\t${now - 5_000}\t1\t\t",
            "broken line without tabs",
            "bad.test\tnot-a-time\t1\t\t",
            "\u0000\u0001garbage\t\t\t"
        )
        val a = open()
        assertEquals(1, a.list().adapter.count)
        assertEquals("good.test", a.rowTexts(0)[0])
        assertEquals(context.getString(R.string.blocked_sites_unknown_app), a.rowTexts(0)[1])
    }

    @Test
    fun opensWithAFileThatIsNotTheLogFormat() {
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, -1, -2))
        assertEmptyState(open())
    }

    @Test
    fun hourOldEntriesAreNotShownAndThePeriodicRefreshDoesNotCrash() {
        val now = System.currentTimeMillis()
        writeLog(
            "fresh.test\t${now - 1_000}\t1\t\t",
            "stale.test\t${now - 61 * 60_000L}\t9\t\t"
        )
        val a = open()
        assertEquals(listOf("fresh.test"), (0 until a.list().adapter.count).map { a.rowTexts(it)[0] })
        // Run a few refresh ticks (every 15 s while the screen is open).
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(46))
        assertEquals(1, a.list().adapter.count)
    }

    @Test
    fun theSettingsRowOpensTheScreen() {
        // On a device androidx.startup initialises WorkManager; Robolectric doesn't.
        runCatching { WorkManager.initialize(context, Configuration.Builder().build()) }
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        main.findViewById<View>(R.id.rowBlockedSites).performClick()
        val started = shadowOf(main).nextStartedActivity
        assertNotNull(started)
        assertEquals(BlockedSitesActivity::class.java.name, started.component?.className)
    }

    @Test
    fun clearEmptiesTheScreenAndTheFile() {
        val now = System.currentTimeMillis()
        writeLog("a.test\t${now - 1_000}\t1\t\t")
        val a = open()
        assertEquals(1, a.list().adapter.count)
        BlockedDomainLogSettings.clear(a)
        assertEquals(0, BlockedDomainLogSettings.recent(a).size)
        assertTrue(File(file.path).readText().startsWith(BlockedDomainLogFormat.HEADER))
    }
}
