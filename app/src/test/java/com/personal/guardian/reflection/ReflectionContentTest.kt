package com.personal.guardian.reflection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Random

/**
 * Stage 6 Reflection Mode — content library, random selection, duration minimum,
 * countdown and persistence (pure JVM; fake/seeded Random and a fake clock).
 */
class ReflectionContentTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val text = ReflectionItem.text("Remember why you started.", "text-1")
    private val img = ReflectionItem.media(ReflectionType.IMAGE, "content://media/1", "family.jpg")
    private val audio = ReflectionItem.media(ReflectionType.AUDIO, "content://media/2", "note.m4a")
    private val video = ReflectionItem.media(ReflectionType.VIDEO, "content://media/3")

    // ---- library add/remove ----

    @Test
    fun addAppendsAndMediaUriDedupes() {
        val lib = ReflectionLibrary.EMPTY.add(text).add(img).add(audio)
        assertEquals(3, lib.size)
        assertEquals(listOf("text-1", "content://media/1", "content://media/2"), lib.items.map { it.id })
        val again = lib.add(ReflectionItem.media(ReflectionType.IMAGE, "content://media/1", "other-label.jpg"))
        assertSame("same URI is not added twice", lib, again)
    }

    @Test
    fun removeDropsOnlyThatItem() {
        val lib = ReflectionLibrary(listOf(text, img, video)).remove("content://media/1")
        assertEquals(listOf("text-1", "content://media/3"), lib.items.map { it.id })
        assertFalse("content://media/1" in lib)
        assertSame(lib, lib.remove("nope"))
    }

    @Test
    fun countsPerType() {
        val lib = ReflectionLibrary(listOf(text, img, audio, video))
        assertEquals(1, lib.countOf(ReflectionType.TEXT))
        assertEquals(1, lib.countOf(ReflectionType.VIDEO))
    }

    // ---- random selection ----

    @Test
    fun pickUsesTheInjectedRandomDeterministically() {
        val lib = ReflectionLibrary(listOf(text, img, audio, video))
        // Same seed → same sequence of picks.
        val a = Random(42).let { r -> List(6) { lib.pick(r)!!.id } }
        val b = Random(42).let { r -> List(6) { lib.pick(r)!!.id } }
        assertEquals(a, b)
        // A controllable Random reaches a specific index.
        val forced = object : Random() {
            override fun nextInt(bound: Int) = 2
        }
        assertEquals(audio, lib.pick(forced))
    }

    @Test
    fun pickOnEmptyLibraryIsNull() {
        assertNull(ReflectionLibrary.EMPTY.pick(Random(1)))
    }

    @Test
    fun pickCoversEveryItemOverManyDraws() {
        val lib = ReflectionLibrary(listOf(text, img, audio, video))
        val r = Random(7)
        val seen = (0 until 500).map { lib.pick(r)!!.id }.toSet()
        assertEquals(lib.items.map { it.id }.toSet(), seen)
    }

    // ---- file format ----

    @Test
    fun formatRoundTripsTypesIdsLabelsAndEscapedText() {
        val lib = ReflectionLibrary(
            listOf(
                ReflectionItem.text("line one\nline\ttwo \\ end", "text-9", "my note"),
                img, audio, video,
            )
        )
        val decoded = ReflectionFormat.decode(ReflectionFormat.encode(lib))!!
        assertEquals(lib.items, decoded.items)
        assertEquals("line one\nline\ttwo \\ end", decoded.items[0].value)
    }

    @Test
    fun decodeRejectsFilesWithoutTheHeaderAndSkipsBadLines() {
        assertNull(ReflectionFormat.decode(""))
        assertNull(ReflectionFormat.decode("text\tid\tlabel\tvalue\n"))
        val txt = ReflectionFormat.HEADER + "\n" +
            "text\ttext-1\t\tHello\n" +
            "bogus\tx\t\tv\n" +          // unknown type
            "image\t\t\tcontent://x\n" + // blank id
            "video\tvid\tlabel\t\n" +     // blank value
            "image\tcontent://ok\tpic\tcontent://ok\n"
        val lib = ReflectionFormat.decode(txt)!!
        assertEquals(listOf("text-1", "content://ok"), lib.items.map { it.id })
    }

    @Test
    fun emptyLibraryRoundTrips() {
        assertEquals(ReflectionLibrary.EMPTY, ReflectionFormat.decode(ReflectionFormat.encode(ReflectionLibrary.EMPTY)))
    }

    // ---- duration: 30-second minimum enforced in code ----

    @Test
    fun durationBelowThirtySecondsIsRaisedToThirty() {
        for (low in listOf(0L, 1L, 5L, 29L)) {
            val c = ReflectionDuration.clampSeconds(low)
            assertEquals("requested $low", 30L, c.seconds)
            assertTrue(c.adjusted)
            assertTrue(c.reason!!.contains("30"))
        }
    }

    @Test
    fun durationAtOrAboveThirtyIsKept() {
        for (ok in listOf(30L, 31L, 120L, 600L)) {
            val c = ReflectionDuration.clampSeconds(ok)
            assertEquals(ok, c.seconds)
            assertFalse(c.adjusted)
        }
        assertEquals(30_000L, ReflectionDuration.clampMillis(30))
    }

    @Test
    fun durationHasASanityCap() {
        assertEquals(ReflectionDuration.MAX_SECONDS, ReflectionDuration.clampSeconds(Long.MAX_VALUE).seconds)
    }

    // ---- persistence ----

    private fun store() = ReflectionStore(File(tmp.root, "reflection_content.tsv"), File(tmp.root, "reflection_duration.txt"))

    @Test
    fun libraryEditsSurviveANewStoreLikeARestart() {
        val s = store()
        val lib = ReflectionLibrary(listOf(text, img, audio))
        s.saveLibrary(lib)
        val reloaded = ReflectionStore(File(tmp.root, "reflection_content.tsv"), File(tmp.root, "reflection_duration.txt")).loadLibrary()
        assertFalse(reloaded.damaged)
        assertEquals(lib, reloaded.library)
        assertFalse(File(tmp.root, "reflection_content.tsv.tmp").exists())
    }

    @Test
    fun firstRunLibraryIsEmptyNotDamaged() {
        val loaded = store().loadLibrary()
        assertFalse(loaded.damaged)
        assertTrue(loaded.library.isEmpty)
    }

    @Test
    fun aDamagedLibraryFileFallsBackToEmptyWithoutOverwriting() {
        File(tmp.root, "reflection_content.tsv").writeText("garbage")
        val loaded = store().loadLibrary()
        assertTrue(loaded.damaged)
        assertTrue(loaded.library.isEmpty)
        assertEquals("garbage", File(tmp.root, "reflection_content.tsv").readText())
    }

    @Test
    fun durationPersistsAndIsAlwaysClampedWhenSavedOrLoaded() {
        val s = store()
        assertEquals("default before any save", 30L, s.loadDurationSeconds())
        val clamp = s.saveDurationSeconds(10) // below minimum from the UI
        assertEquals(30L, clamp.seconds)
        assertTrue(clamp.adjusted)
        assertEquals("stored value is the clamped one", 30L, store().loadDurationSeconds())

        s.saveDurationSeconds(90)
        assertEquals(90L, store().loadDurationSeconds())
    }

    @Test
    fun aHandEditedDurationBelowTheMinimumIsStillClampedOnRead() {
        File(tmp.root, "reflection_duration.txt").writeText("5")
        assertEquals("cannot be bypassed by editing the file", 30L, store().loadDurationSeconds())
    }

    @Test
    fun anUnparseableDurationFileFallsBackToDefault() {
        File(tmp.root, "reflection_duration.txt").writeText("soon")
        assertEquals(30L, store().loadDurationSeconds())
    }

    // ---- countdown + back no-op ----

    @Test
    fun countdownCountsDownAndAutoExitsOnAFakeClock() {
        var now = 1_000L
        val c = ReflectionCountdown(durationMs = 30_000L, clock = { now })
        assertEquals("before start, full duration shown", 30L, c.remainingSeconds())
        assertFalse(c.isElapsed())
        c.start()
        assertEquals(30_000L, c.remainingMs())
        now += 1L
        assertEquals("29.999s rounds up to 30 displayed", 30L, c.remainingSeconds())
        now += 9_999L // 10s elapsed
        assertEquals(20_000L, c.remainingMs())
        assertEquals(20L, c.remainingSeconds())
        assertFalse(c.isElapsed())
        now += 19_999L // 29.999s
        assertFalse(c.isElapsed())
        assertEquals(1L, c.remainingSeconds())
        now += 1L // exactly 30s
        assertTrue(c.isElapsed())
        assertEquals(0L, c.remainingMs())
        assertEquals(0L, c.remainingSeconds())
        now += 5_000L
        assertEquals("never goes negative", 0L, c.remainingMs())
    }

    @Test
    fun startIsIdempotent() {
        var now = 0L
        val c = ReflectionCountdown(10_000L, { now })
        c.start()
        now += 4_000L
        c.start() // must not restart
        assertEquals(6_000L, c.remainingMs())
    }

    @Test
    fun backIsConsumedUntilTheCountdownElapses() {
        var now = 0L
        val c = ReflectionCountdown(30_000L, { now })
        c.start()
        assertTrue("back is a no-op during the countdown", c.onBackPressed())
        now += 29_999L
        assertTrue(c.onBackPressed())
        now += 1L
        assertFalse("once elapsed, back is allowed (activity is finishing)", c.onBackPressed())
    }

    // ---- media playback policy ----

    @Test
    fun videoPlaysWithAudioAtFullTrackVolume() {
        assertTrue(ReflectionMedia.VIDEO_PLAYS_WITH_AUDIO)
        assertEquals(1f, ReflectionMedia.videoVolume(), 0f)
    }

    @Test
    fun audioFocusChangesMapToTheRightVolumeAndPlayState() {
        assertEquals(1f, ReflectionMedia.volumeForFocus(ReflectionMedia.AUDIOFOCUS_GAIN), 0f)
        assertEquals(0.2f, ReflectionMedia.volumeForFocus(ReflectionMedia.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK), 0f)
        assertEquals(0f, ReflectionMedia.volumeForFocus(ReflectionMedia.AUDIOFOCUS_LOSS), 0f)
        assertEquals(0f, ReflectionMedia.volumeForFocus(ReflectionMedia.AUDIOFOCUS_LOSS_TRANSIENT), 0f)

        assertTrue(ReflectionMedia.keepsPlaying(ReflectionMedia.AUDIOFOCUS_GAIN))
        assertTrue("ducking keeps playing, just quieter", ReflectionMedia.keepsPlaying(ReflectionMedia.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK))
        assertFalse(ReflectionMedia.keepsPlaying(ReflectionMedia.AUDIOFOCUS_LOSS))
        assertFalse(ReflectionMedia.keepsPlaying(ReflectionMedia.AUDIOFOCUS_LOSS_TRANSIENT))
    }

    @Test
    fun mediaFocusConstantsMatchTheAndroidApi() {
        // android.media.AudioManager's stable platform values (mirrored in ReflectionMedia
        // so the policy stays pure). Asserted as literals to keep this test independent of
        // the Android platform jar on the test classpath (Paparazzi swaps it per variant).
        assertEquals(1, ReflectionMedia.AUDIOFOCUS_GAIN)
        assertEquals(-1, ReflectionMedia.AUDIOFOCUS_LOSS)
        assertEquals(-2, ReflectionMedia.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(-3, ReflectionMedia.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
    }

    @Test
    fun theActivityPlaysVideoWithAudioAndRequestsFocus() {
        val src = File("src/main/java/com/personal/guardian/reflection/ReflectionActivity.kt").readText()
        assertFalse("no hard mute on video any more", src.contains("setVolume(0f, 0f)"))
        assertTrue("video uses the policy volume", src.contains("ReflectionMedia.videoVolume()"))
        assertTrue("requests audio focus", src.contains("requestAudioFocus"))
        assertTrue("abandons audio focus", src.contains("abandonAudioFocus"))
    }

    // ---- secret tap-to-reveal gate ----

    @Test
    fun defaultSecretThresholdIsFiftyTaps() {
        assertEquals(50, SecretTapUnlock.DEFAULT_THRESHOLD)
        assertEquals(50, SecretTapUnlock(clock = { 0L }).threshold)
    }

    @Test
    fun barsStayLockedUntilExactlyTheThresholdTap() {
        var now = 0L
        val gate = SecretTapUnlock(threshold = 50, clock = { now })
        repeat(49) {
            now += 100
            assertFalse("tap ${it + 1} must not unlock", gate.onTap())
            assertFalse(gate.unlocked)
        }
        assertEquals(1, gate.remaining)
        now += 100
        assertTrue("the 50th tap unlocks", gate.onTap())
        assertTrue(gate.unlocked)
        assertEquals(0, gate.remaining)
    }

    @Test
    fun theUnlockMomentFiresOnceThenFurtherTapsDoNothing() {
        var now = 0L
        val gate = SecretTapUnlock(threshold = 3, clock = { now })
        assertFalse(gate.onTap()); assertFalse(gate.onTap())
        assertTrue("crossing tap", gate.onTap())
        assertFalse("already unlocked", gate.onTap())
        assertFalse(gate.onTap())
        assertTrue(gate.unlocked)
    }

    @Test
    fun aLongGapBetweenTapsResetsTheCount() {
        var now = 0L
        val gate = SecretTapUnlock(threshold = 3, maxGapMs = 1_000, clock = { now })
        gate.onTap() // 1
        now += 500; gate.onTap() // 2
        now += 2_000 // gap too long → resets to 1
        assertFalse(gate.onTap())
        assertEquals("count restarted at 1", 2, gate.remaining)
        now += 100; assertFalse(gate.onTap()) // 2
        now += 100; assertTrue("now the third quick tap unlocks", gate.onTap())
    }

    @Test
    fun theActivityGatesTheBarsBehindTheSecretTaps() {
        val src = File("src/main/java/com/personal/guardian/reflection/ReflectionActivity.kt").readText()
        assertTrue("counts taps", src.contains("SecretTapUnlock"))
        assertTrue("taps counted on touch", src.contains("dispatchTouchEvent"))
        assertTrue("reveal on unlock", src.contains("revealSystemBars"))
        assertTrue("re-hide only while still locked", src.contains("!secretUnlock.unlocked"))
    }

    // ---- auditable logging and lock-task wiring (source checks; the rest needs a device) ----

    @Test
    fun theDistinctLogLinesAndLockTaskCallsArePresent() {
        val launcher = File("src/main/java/com/personal/guardian/reflection/ReflectionLauncher.kt").readText()
        assertTrue(launcher.contains("Reflection Mode shown (content="))
        assertTrue("logs the chosen type and duration", launcher.contains("duration=\${durationMs / 1000}s"))
        val activity = File("src/main/java/com/personal/guardian/reflection/ReflectionActivity.kt").readText()
        assertTrue(activity.contains("Reflection Mode ended (elapsed)"))
        assertTrue("escape is auditable", activity.contains("Reflection Mode ended (user escaped via pin-exit)"))
        assertTrue("pins on launch", activity.contains("startLockTask()"))
        assertTrue("unpins at the end", activity.contains("stopLockTask()"))
    }
}
