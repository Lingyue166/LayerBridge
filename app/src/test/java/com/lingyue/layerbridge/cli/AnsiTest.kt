package com.lingyue.layerbridge.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnsiTest {

    @Test
    fun disabledPassesThrough() {
        assertEquals("x", Ansi.wrap(false, "x", 31))
    }

    @Test
    fun enabledWrapsWithEscape() {
        val out = Ansi.wrap(true, "x", 31)
        assertTrue(out.startsWith("\u001B[31m"))
        assertTrue(out.endsWith("\u001B[0m"))
    }
}
