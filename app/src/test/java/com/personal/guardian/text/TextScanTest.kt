package com.personal.guardian.text

import com.personal.guardian.scan.DetectionCooldown
import com.personal.guardian.scan.ScanConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    private val trigger get() = TextScanTrigger(ScanConfig.WATCHED_PACKAGES)

    @Test
    fun onlyContentOrStateChangesInWatchedAppsTrigger() {
        val content = TextScanTrigger.TYPE_WINDOW_CONTENT_CHANGED
        val state = TextScanTrigger.TYPE_WINDOW_STATE_CHANGED
        assertFalse(trigger.onEvent(content, "com.example.notes"))
        assertFalse(trigger.onEvent(content, null))
        assertFalse("view-clicked is not a trigger", trigger.onEvent(0x00000001, "com.whatsapp"))
        assertTrue(trigger.onEvent(content, "com.whatsapp"))
        assertTrue(trigger.onEvent(state, "org.telegram.messenger"))
        assertTrue(trigger.onEvent(content, "com.android.chrome"))
    }

    @Test
    fun eventConstantsMatchTheAndroidApi() {
        assertEquals(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, TextScanTrigger.TYPE_WINDOW_CONTENT_CHANGED)
        assertEquals(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, TextScanTrigger.TYPE_WINDOW_STATE_CHANGED)
    }

    @Test
    fun burstsOfEventsScheduleOneCheckUntilItStarts() {
        val t = trigger
        val content = TextScanTrigger.TYPE_WINDOW_CONTENT_CHANGED
        assertTrue(t.onEvent(content, "com.whatsapp"))
        repeat(20) { assertFalse("absorbed while a check is pending", t.onEvent(content, "com.whatsapp")) }
        t.onCheckStarted()
        assertTrue("next change schedules again", t.onEvent(content, "com.whatsapp"))
    }

    @Test
    fun accessibilityConfigRequestsContentChangedEventsAndWindowContent() {
        val xml = File("src/main/res/xml/accessibility_service_config.xml").readText()
        assertTrue(xml.contains("typeWindowContentChanged"))
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
