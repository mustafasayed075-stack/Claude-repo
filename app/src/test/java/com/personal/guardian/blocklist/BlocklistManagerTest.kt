package com.personal.guardian.blocklist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the tolerant blocklist parser and the subdomain-aware matcher.
 * These let the blocking logic be verified independently of an Android device.
 */
class BlocklistManagerTest {

    private fun parse(text: String): Set<String> =
        BlocklistManager.parse(text.reader().buffered())

    @Test
    fun parsesHostsFormat() {
        val set = parse(
            """
            # comment
            0.0.0.0 bad-one.example
            127.0.0.1 bad-two.example
            0.0.0.0 localhost
            """.trimIndent()
        )
        assertTrue(set.contains("bad-one.example"))
        assertTrue(set.contains("bad-two.example"))
        assertFalse("localhost must be skipped", set.contains("localhost"))
    }

    @Test
    fun parsesAbpFormat() {
        val set = parse(
            """
            ! Title: test
            ||adult-site.example^
            ||tracker.example^third-party
            """.trimIndent()
        )
        assertTrue(set.contains("adult-site.example"))
        assertTrue(set.contains("tracker.example"))
    }

    @Test
    fun parsesPlainDomainFormat() {
        val set = parse(
            """
            plain-one.example
            plain-two.example
            """.trimIndent()
        )
        assertEquals(2, set.size)
        assertTrue(set.contains("plain-one.example"))
    }

    @Test
    fun matchesDomainAndSubdomainsButNotUnrelated() {
        val set = setOf("adult-site.example", "ads.tracker.example")
        // exact match
        assertTrue(BlocklistManager.matches(set, "adult-site.example"))
        // subdomains of a blocked domain are blocked
        assertTrue(BlocklistManager.matches(set, "www.adult-site.example"))
        assertTrue(BlocklistManager.matches(set, "cdn.media.adult-site.example"))
        // trailing dot / case handled
        assertTrue(BlocklistManager.matches(set, "WWW.Adult-Site.Example."))
        // unrelated domains pass (no over-blocking)
        assertFalse(BlocklistManager.matches(set, "example.com"))
        assertFalse(BlocklistManager.matches(set, "notadult-site.example"))
        // parent of a blocked subdomain entry is NOT itself blocked
        assertFalse(BlocklistManager.matches(set, "tracker.example"))
    }

    @Test
    fun emptySetBlocksNothing() {
        assertFalse(BlocklistManager.matches(emptySet(), "anything.example"))
    }

    @Test
    fun skipsIpAddressesAndJunk() {
        val set = parse(
            """
            8.8.8.8
            not_a_domain
            good.example
            """.trimIndent()
        )
        assertTrue(set.contains("good.example"))
        assertFalse(set.contains("8.8.8.8"))
        assertFalse(set.contains("not_a_domain"))
    }

    @Test
    fun matchingStaysFastAtRealListSize() {
        // Same size as the live oisd NSFW list (49,288 domains), in ABP format.
        val listSize = 49_288
        val rnd = java.util.Random(42)
        fun label() = (1..(4 + rnd.nextInt(10))).map { ('a' + rnd.nextInt(26)) }.joinToString("")
        val listText = (0 until listSize).joinToString("\n") { "||${label()}.${label()}.example^" }

        var t0 = System.nanoTime()
        val set = parse(listText)
        val parseMs = (System.nanoTime() - t0) / 1_000_000
        assertEquals(listSize, set.size)

        // Mix of misses (typical browsing) and subdomain hits, 4-label names so the
        // matcher walks several parent domains per lookup.
        val entries = set.toList()
        val hosts = (0 until 10_000).map { i ->
            if (i % 10 == 0) "cdn.img.${entries[rnd.nextInt(entries.size)]}"
            else "www.${label()}.${label()}.com"
        }
        repeat(3) { hosts.forEach { BlocklistManager.matches(set, it) } } // JIT warm-up

        val rounds = 10
        var hits = 0
        t0 = System.nanoTime()
        repeat(rounds) { hosts.forEach { if (BlocklistManager.matches(set, it)) hits++ } }
        val totalNs = System.nanoTime() - t0
        val lookups = rounds * hosts.size
        val avgUs = totalNs / 1_000.0 / lookups

        println(
            "BlocklistManagerTest: parsed $listSize domains in $parseMs ms; " +
                "$lookups lookups in ${totalNs / 1_000_000} ms (avg ${"%.3f".format(avgUs)} µs/lookup)"
        )
        assertEquals("every subdomain of a listed domain is blocked", rounds * 1_000, hits)
        // Generous bound so the test isn't flaky on slow CI; real cost is ~1 µs.
        assertTrue("lookup too slow: avg $avgUs µs", avgUs < 50.0)
    }
}
