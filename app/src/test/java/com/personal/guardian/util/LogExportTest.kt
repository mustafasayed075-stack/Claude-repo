package com.personal.guardian.util

import org.junit.Assert.assertEquals
import org.junit.Test

class LogExportTest {

    @Test
    fun tailReturnsTheLastNLines() {
        val text = (1..10).joinToString("\n") { "line$it" }
        assertEquals("line8\nline9\nline10", LogExport.tail(text, 3))
    }

    @Test
    fun fewerLinesThanLimitReturnsAll() {
        assertEquals("a\nb", LogExport.tail("a\nb", 10))
    }

    @Test
    fun aTrailingNewlineIsNotCountedAsAnEmptyLine() {
        assertEquals("line2\nline3", LogExport.tail("line1\nline2\nline3\n", 2))
    }

    @Test
    fun zeroLinesIsEmpty() {
        assertEquals("", LogExport.tail("a\nb\nc", 0))
    }

    @Test
    fun emptyTextIsEmpty() {
        assertEquals("", LogExport.tail("", 5))
    }
}
