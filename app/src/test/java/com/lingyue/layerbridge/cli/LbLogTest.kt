package com.lingyue.layerbridge.cli

import org.junit.Assert.assertTrue
import org.junit.Test

class LbLogTest {

    @Test
    fun formatMatchesStandardShape() {
        val line = LbLog.format("INFO", "[lb] installed tree 2.2.1", "2026-01-01T00:00:00Z")
        assertTrue(line.matches(Regex("""^\[\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z] \[INFO] \[lb] installed tree 2\.2\.1$""")))
    }

    @Test
    fun formatIsAscii() {
        val line = LbLog.format("ERROR", "install failed", "2026-01-01T00:00:00Z")
        assertTrue(line.all { it.code < 128 })
    }
}
