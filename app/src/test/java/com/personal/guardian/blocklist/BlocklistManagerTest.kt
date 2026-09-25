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
}
