package com.personal.guardian.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Pure-JVM tests for the suggestive-tier sensitivity: the enum and its persistence. */
class ScanSensitivityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---- enum ----

    @Test
    fun thresholdsComeFromScanConfigAndHighIsStricter() {
        assertEquals(ScanConfig.SUGGESTIVE_THRESHOLD_NORMAL, ScanSensitivity.NORMAL.suggestiveThreshold)
        assertEquals(ScanConfig.SUGGESTIVE_THRESHOLD_HIGH, ScanSensitivity.HIGH.suggestiveThreshold)
        assertTrue(ScanSensitivity.HIGH.suggestiveThreshold < ScanSensitivity.NORMAL.suggestiveThreshold)
    }

    @Test
    fun fromIdParsesKnownIdsAndFallsBackToDefault() {
        assertEquals(ScanSensitivity.NORMAL, ScanSensitivity.fromId("normal"))
        assertEquals(ScanSensitivity.HIGH, ScanSensitivity.fromId("high"))
        assertEquals(ScanSensitivity.HIGH, ScanSensitivity.fromId("  HIGH \n")) // trimmed, case-insensitive
        assertEquals(ScanSensitivity.DEFAULT, ScanSensitivity.fromId(null))
        assertEquals(ScanSensitivity.DEFAULT, ScanSensitivity.fromId("whatever"))
        assertEquals(ScanSensitivity.NORMAL, ScanSensitivity.DEFAULT)
    }

    // ---- store ----

    @Test
    fun missingFileReadsAsTheDefaultWithoutCreatingIt() {
        val file = File(tmp.root, "scan_sensitivity.txt")
        assertEquals(ScanSensitivity.DEFAULT, ScanSensitivityStore(file).load())
        assertFalse("load never writes", file.exists())
    }

    @Test
    fun savedValueRoundTripsAcrossANewStore() {
        val file = File(tmp.root, "scan_sensitivity.txt")
        ScanSensitivityStore(file).save(ScanSensitivity.HIGH)
        assertEquals(ScanSensitivity.HIGH, ScanSensitivityStore(file).load())
        ScanSensitivityStore(file).save(ScanSensitivity.NORMAL)
        assertEquals(ScanSensitivity.NORMAL, ScanSensitivityStore(file).load())
    }

    @Test
    fun aGarbledFileFallsBackToTheDefault() {
        val file = File(tmp.root, "scan_sensitivity.txt").apply { writeText("corrupted") }
        assertEquals(ScanSensitivity.DEFAULT, ScanSensitivityStore(file).load())
    }

    @Test
    fun anInterruptedSaveIsRecoveredFromTheTempFile() {
        val file = File(tmp.root, "scan_sensitivity.txt")
        File(tmp.root, "scan_sensitivity.txt.tmp").writeText(ScanSensitivity.HIGH.id)
        assertEquals(ScanSensitivity.HIGH, ScanSensitivityStore(file).load())
    }

    @Test
    fun theStoreCreatesItsDirectory() {
        val file = File(tmp.root, "nested/dir/scan_sensitivity.txt")
        ScanSensitivityStore(file).save(ScanSensitivity.HIGH)
        assertEquals(ScanSensitivity.HIGH, ScanSensitivityStore(file).load())
    }
}
