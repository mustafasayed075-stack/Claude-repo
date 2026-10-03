package com.personal.guardian.text

import com.personal.guardian.scan.DetectionCooldown
import com.personal.guardian.scan.FastScanApp
import com.personal.guardian.scan.FastScanDefaults
import com.personal.guardian.scan.ScanConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure-JVM tests for Stage 4's text extraction, trigger/debounce, fingerprints, and
 * an end-to-end simulation (node tree → matcher → cooldown) of a lingering chat.
 */
class TextScanTest {

    /** Fake accessibility node. */
    private class Node(
        override val text: CharSequence? = null,
        override val contentDescription: CharSequence? = null,
        override val isVisibleToUser: Boolean = true,
        val children: List<Node> = emptyList()
    ) : TextNode {
        var released = false
        override val childCount get() = children.size
        override fun child(index: Int): TextNode = children[index]
        override fun release() { released = true }
    }

    // ---- TextExtractor ----

    @Test
    fun collectsVisibleTextAndDescriptionsInScreenOrderWithoutDuplicates() {
        val tree = Node(children = listOf(
            Node(text = "Ahmed", contentDescription = "Ahmed"),        // duplicate description
            Node(children = listOf(Node(text = "first message"), Node(text = "second message"))),
            Node(contentDescription = "Photo"),
            Node(text = "  ")                                           // blank
        ))
        assertEquals(listOf("Ahmed", "first message", "second message", "Photo"), TextExtractor.collect(tree, 100, 1000))
    }

    @Test
    fun skipsInvisibleSubtrees() {
        val tree = Node(children = listOf(
            Node(text = "on screen"),
            Node(isVisibleToUser = false, text = "off screen", children = listOf(Node(text = "hidden child")))
        ))
        assertEquals(listOf("on screen"), TextExtractor.collect(tree, 100, 1000))
    }

    @Test
    fun respectsNodeAndCharacterLimitsAndReleasesNodes() {
        val kids = (1..50).map { Node(text = "message number $it") }
        val tree = Node(children = kids)
        assertEquals(9, TextExtractor.collect(tree, maxNodes = 10, maxChars = 10_000).size) // root + 9 children
        val clipped = TextExtractor.collect(tree, maxNodes = 100, maxChars = 30)
        assertEquals(30, clipped.sumOf { it.length })
        assertTrue("every child node released", kids.all { it.released })
    }

    // ---- TextScanTrigger ----

    private val trigger get() = TextScanTrigger(FastScanDefaults.list().textPackages)

    @Test
    fun triggerFollowsTheListsTextTogglesLive() {
        var list = FastScanDefaults.list()
        val t = TextScanTrigger { list.textPackages }
        val content = TextScanTrigger.TYPE_WINDOW_CONTENT_CHANGED
        assertTrue(t.isWatched("com.whatsapp"))
        list = list.setText("com.whatsapp", false)
        assertFalse("text off → no text checks", t.isWatched("com.whatsapp"))
        assertEquals(TextScanTrigger.Decision.IGNORED, t.onEvent(content, "com.whatsapp"))
        list = list.setImage("com.android.chrome", false)
        assertTrue("image off leaves text on", t.isWatched("com.android.chrome"))
        list = list.add(FastScanApp("com.example.chat", "Example chat", text = true, image = false))
        assertEquals("a newly added app triggers", TextScanTrigger.Decision.SCHEDULED, t.onEvent(content, "com.example.chat"))
        list = list.remove("org.telegram.messenger")
        assertFalse(t.isWatched("org.telegram.messenger"))
    }

    private val scheduled = TextScanTrigger.Decision.SCHEDULED
    private val coalesced = TextScanTrigger.Decision.COALESCED
    private val ignored = TextScanTrigger.Decision.IGNORED

    @Test
    fun contentStateAndTextFieldChangesInWatchedAppsTrigger() {
        val content = TextScanTrigger.TYPE_WINDOW_CONTENT_CHANGED
        val state = TextScanTrigger.TYPE_WINDOW_STATE_CHANGED
        val typed = TextScanTrigger.TYPE_VIEW_TEXT_CHANGED
        assertEquals(ignored, trigger.onEvent(content, "com.example.notes"))
        assertEquals(ignored, trigger.onEvent(typed, "com.example.notes", "porn"))
        assertEquals(ignored, trigger.onEvent(content, null))
        assertEquals("view-clicked is not a trigger", ignored, trigger.onEvent(0x00000001, "com.whatsapp"))
        assertEquals(scheduled, trigger.onEvent(content, "com.whatsapp"))
        assertEquals(scheduled, trigger.onEvent(state, "org.telegram.messenger"))
        assertEquals("typing in a composer triggers", scheduled, trigger.onEvent(typed, "com.android.chrome", "p"))
    }

    @Test
    fun eventConstantsMatchTheAndroidApi() {
        assertEquals(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, TextScanTrigger.TYPE_WINDOW_CONTENT_CHANGED)
        assertEquals(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, TextScanTrigger.TYPE_WINDOW_STATE_CHANGED)
        assertEquals(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, TextScanTrigger.TYPE_VIEW_TEXT_CHANGED)
        assertEquals(android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD, 2)
    }

    @Test
    fun burstsOfEventsScheduleOneCheckUntilItStarts() {
        val t = trigger
        val content = TextScanTrigger.TYPE_WINDOW_CONTENT_CHANGED
        assertEquals(scheduled, t.onEvent(content, "com.whatsapp"))
        repeat(20) { assertEquals("absorbed while a check is pending", coalesced, t.onEvent(content, "com.whatsapp")) }
        val p = t.onCheckStarted()!!
        assertEquals(21, p.events)
        assertNull("nothing pending once started", t.onCheckStarted())
        assertEquals("next change schedules again", scheduled, t.onEvent(content, "com.whatsapp"))
    }

    @Test
    fun thePendingCheckCarriesTheLatestFieldTextOfItsApp() {
        val t = trigger
        val typed = TextScanTrigger.TYPE_VIEW_TEXT_CHANGED
        val content = TextScanTrigger.TYPE_WINDOW_CONTENT_CHANGED
        t.onEvent(typed, "com.whatsapp", "p")
        t.onEvent(typed, "com.whatsapp", "po")
        t.onEvent(content, "com.whatsapp") // e.g. the send button enabling: keeps the field text
        t.onEvent(typed, "com.whatsapp", "porn")
        val p = t.onCheckStarted()!!
        assertEquals("com.whatsapp", p.packageName)
        assertEquals("porn", p.fieldText)
        assertEquals(4, p.events)
        assertEquals(3, p.fieldEvents)

        t.onEvent(typed, "com.whatsapp", "hello")
        t.onEvent(content, "com.android.chrome")
        val q = t.onCheckStarted()!!
        assertEquals("the latest app is checked", "com.android.chrome", q.packageName)
        assertNull("another app's field text is not carried over", q.fieldText)
    }

    @Test
    fun fieldEventsAreTextChangesOrEditTextSources() {
        assertTrue(TextScanTrigger.isFieldEvent(TextScanTrigger.TYPE_VIEW_TEXT_CHANGED, null))
        assertTrue(TextScanTrigger.isFieldEvent(TextScanTrigger.TYPE_WINDOW_CONTENT_CHANGED, "android.widget.EditText"))
        assertTrue(TextScanTrigger.isFieldEvent(TextScanTrigger.TYPE_WINDOW_CONTENT_CHANGED, "androidx.appcompat.widget.AppCompatEditText"))
        assertFalse(TextScanTrigger.isFieldEvent(TextScanTrigger.TYPE_WINDOW_CONTENT_CHANGED, "android.widget.FrameLayout"))
    }

    /**
     * Reproduces typing into a composer: one text-changed event per keystroke, 120 ms
     * apart, on the same field, with a fake clock standing in for the worker's
     * postDelayed. The debounce must neither starve while typing continues nor miss
     * the final text once typing stops.
     */
    @Test
    fun rapidTypingInAFieldEndsInACheckOfTheFinalText() {
        val t = trigger
        val debounce = ScanConfig.TEXT_CHECK_DEBOUNCE_MS
        val word = "so I typed porn here"
        val keystrokeMs = 120L
        var checkDueAt: Long? = null
        val checks = ArrayList<Pair<Long, String?>>() // (time, field text read)
        fun runDueCheck(now: Long) {
            val due = checkDueAt ?: return
            if (due <= now) {
                checkDueAt = null
                checks += due to t.onCheckStarted()!!.fieldText
            }
        }
        var now = 0L
        for (i in 1..word.length) {
            now = i * keystrokeMs
            runDueCheck(now)
            val d = t.onEvent(TextScanTrigger.TYPE_VIEW_TEXT_CHANGED, "com.whatsapp", word.take(i))
            if (d == scheduled) checkDueAt = now + debounce
        }
        val lastKeystroke = now
        runDueCheck(Long.MAX_VALUE)

        // While typing (2.4 s), checks keep firing at the debounce interval, never starved.
        val during = checks.filter { it.first <= lastKeystroke }
        assertTrue("checks fire while typing: $checks", during.size >= (lastKeystroke / (debounce + keystrokeMs)).toInt())
        // Once typing settles, one check fires within one debounce delay and reads the final text.
        val final = checks.last()
        assertTrue("final check after the last keystroke", final.first >= lastKeystroke)
        assertTrue("final check within ${debounce} ms", final.first - lastKeystroke <= debounce)
        assertEquals(word, final.second)
        val matcher = KeywordMatcher(File("src/main/assets/text/keywords.txt").bufferedReader().use { KeywordList.parse(it) })
        assertTrue("the final check's text matches", matcher.containsMatch(final.second!!))
    }

    @Test
    fun textChecksReadEveryWindowOfTheAppButNeverTheKeyboard() {
        val c = TextWindows::Candidate
        // The user is touching the keyboard: it's the active window.
        val typing = listOf(c("com.example.app", false, false), c("com.google.android.inputmethod.latin", true, true))
        assertEquals(listOf(0), TextWindows.select(typing, "com.example.app"))
        // A composer in its own dialog window, plus the main window behind it.
        val dialog = listOf(c("com.example.app", false, false), c("com.android.systemui", false, false), c("com.example.app", false, true))
        assertEquals("active window first", listOf(2, 0), TextWindows.select(dialog, "com.example.app"))
        // The app left the screen within the debounce.
        assertTrue(TextWindows.select(listOf(c("com.other", false, true)), "com.example.app").isEmpty())
        assertTrue("an IME is never read even if its package is listed",
            TextWindows.select(listOf(c("com.example.app", true, true)), "com.example.app").isEmpty())
    }

    @Test
    fun accessibilityConfigRequestsTextEventsAndAllWindows() {
        val xml = File("src/main/res/xml/accessibility_service_config.xml").readText()
        assertTrue(xml.contains("typeWindowContentChanged"))
        assertTrue("composer typing events", xml.contains("typeViewTextChanged"))
        assertTrue("every on-screen window, not just the active one", xml.contains("flagRetrieveInteractiveWindows"))
        assertTrue(xml.contains("canRetrieveWindowContent=\"true\""))
    }

    // ---- TextFingerprint ----

    @Test
    fun fingerprintIsStablePerAppAndTerm() {
        assertEquals(TextFingerprint.of("com.whatsapp", "sex"), TextFingerprint.of("com.whatsapp", "sex"))
        assertNotEquals(TextFingerprint.of("com.whatsapp", "sex"), TextFingerprint.of("com.whatsapp", "nudes"))
        assertNotEquals(TextFingerprint.of("com.whatsapp", "sex"), TextFingerprint.of("org.telegram.messenger", "sex"))
    }

    // ---- End to end: node tree → matcher → cooldown ----

    @Test
    fun lingeringConversationIsReportedOnceButNewTermsAreReported() {
        val matcher = KeywordMatcher(File("src/main/assets/text/keywords.txt").bufferedReader().use { KeywordList.parse(it) })
        val cooldown = DetectionCooldown(cooldownMs = ScanConfig.TEXT_COOLDOWN_MS, maxDistance = 0)
        val pkg = "com.whatsapp"
        fun check(nowMs: Long, vararg messages: String): Boolean? {
            val texts = TextExtractor.collect(Node(children = messages.map { Node(text = it) }), 2000, 50_000)
            val terms = texts.flatMap { matcher.find(it) }.map { it.term }.distinct()
            if (terms.isEmpty()) return null // no match
            return cooldown.shouldReportAny(nowMs, terms.map { TextFingerprint.of(pkg, it) })
        }

        assertEquals("ordinary chat: nothing", null, check(0, "hey", "how was work?"))
        assertEquals("first match reported", true, check(1_000, "hey", "send nudes"))
        // The chat stays open: content changes (typing, scrolling) keep re-checking it.
        for (t in 2_000L..30_000L step 750) {
            assertEquals("same conversation suppressed at $t", false, check(t, "hey", "send nudes", "lol typing…"))
        }
        assertEquals("scrolling the term off and on again: still suppressed", false, check(31_000, "send nudes"))
        assertEquals("a new term is new content", true, check(32_000, "send nudes", "you horny?"))
        assertEquals("after the cooldown the same term reports again", true, check(1_000 + ScanConfig.TEXT_COOLDOWN_MS, "send nudes"))
    }
}
