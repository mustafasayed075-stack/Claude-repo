package com.personal.guardian.reflection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Random

/**
 * The built-in reminder video: always in the library (added on read, never stored),
 * never removable, counted, and what a lock shows when the user's library is empty.
 */
class ReflectionBuiltInsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val builtInId = ReflectionBuiltIns.REMINDER_VIDEO_ID
    private val userText = ReflectionItem.text("Stop and think.", "text-1")
    private val userVideo = ReflectionItem.media(ReflectionType.VIDEO, "content://media/video/7")

    private fun store() = ReflectionStore(File(tmp.root, "reflection_content.tsv"), File(tmp.root, "reflection_duration.txt"))

    @Test
    fun theBuiltInVideoIsAlwaysThereEvenWithAnEmptyLibrary() {
        val lib = ReflectionLibrary.EMPTY.withBuiltIns()
        assertTrue(builtInId in lib)
        val item = lib.items.single()
        assertEquals(ReflectionType.VIDEO, item.type)
        assertTrue(item.isBuiltIn)
    }

    @Test
    fun anEmptyUserLibraryStillPicksTheBuiltInSoALockIsNeverBlank() {
        val lib = ReflectionLibrary.EMPTY.withBuiltIns()
        val picked = lib.pick(Random(1))
        assertNotNull(picked)
        assertEquals(builtInId, picked!!.id)
    }

    @Test
    fun theBuiltInIsPartOfTheRandomPickAlongsideUserContent() {
        val lib = ReflectionLibrary(listOf(userText, userVideo)).withBuiltIns()
        val seen = (0 until 300).map { lib.pick(Random(it.toLong()))!!.id }.toSet()
        assertEquals(setOf(builtInId, userText.id, userVideo.id), seen)
    }

    @Test
    fun removingTheBuiltInIsRefusedInTheDataLayer() {
        val lib = ReflectionLibrary(listOf(userText)).withBuiltIns()
        assertSame("unchanged", lib, lib.remove(builtInId))
        assertTrue(builtInId in lib.remove(builtInId))
        // User content is still removable.
        assertFalse(userText.id in lib.remove(userText.id))
        assertTrue(builtInId in lib.remove(userText.id))
    }

    @Test
    fun theSettingsCountIncludesTheBuiltIn() {
        assertEquals(1, ReflectionLibrary.EMPTY.withBuiltIns().size)
        assertEquals(3, ReflectionLibrary(listOf(userText, userVideo)).withBuiltIns().size)
        assertEquals(2, ReflectionLibrary(listOf(userText, userVideo)).withBuiltIns().countOf(ReflectionType.VIDEO))
    }

    @Test
    fun withBuiltInsIsIdempotent() {
        val once = ReflectionLibrary(listOf(userText)).withBuiltIns()
        assertEquals(once, once.withBuiltIns())
        assertEquals(1, once.withBuiltIns().items.count { it.isBuiltIn })
    }

    @Test
    fun theBuiltInIsNeverStoredSoAnExistingInstallGetsItOnRead() {
        // Saving a library that contains the built-in writes only the user's items…
        store().saveLibrary(ReflectionLibrary(listOf(userText)).withBuiltIns())
        val stored = File(tmp.root, "reflection_content.tsv").readText()
        assertFalse(stored.contains(builtInId))
        val loaded = store().loadLibrary().library
        assertEquals(listOf(userText), loaded.items)
        // …and the built-in comes back when the library is read, like on an existing install.
        assertTrue(builtInId in loaded.withBuiltIns())
    }

    @Test
    fun aFileCannotShadowOrAddABuiltIn() {
        val text = ReflectionFormat.HEADER + "\n" + "text\t$builtInId\tfake\tnot the video\n" + "text\ttext-1\t\tStop and think.\n"
        val lib = ReflectionFormat.decode(text)!!
        assertEquals(listOf("text-1"), lib.items.map { it.id })
    }

    @Test
    fun addingAnItemWithABuiltInIdIsRefused() {
        val lib = ReflectionLibrary(listOf(userText))
        assertSame(lib, lib.add(ReflectionItem.text("x", builtInId)))
    }

    @Test
    fun theVideoShipsInResRaw() {
        val f = File("src/main/res/raw/rafiq_builtin_reminder.mp4")
        assertTrue("res/raw/rafiq_builtin_reminder.mp4 is packaged", f.isFile && f.length() > 1_000_000)
    }
}
