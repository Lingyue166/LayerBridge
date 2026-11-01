package com.lingyue.layerbridge.install

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkScriptTest {

    @Test
    fun buildsCreateAndDeleteLoops() {
        val script = LinkScript.build("/data/adb/ksu/bin", setOf("git", "node"), "/data/adb/lb/system/bin")
        assertTrue(script.contains("for c in git node; do"))
        assertTrue(script.contains("ln -s '/data/adb/lb/system/bin'"))
        assertTrue(script.contains("case ' git node ' in"))
        assertTrue(script.contains("rm -f"))
    }

    @Test
    fun emptyCommandsOnlyPrunes() {
        val script = LinkScript.build("/data/adb/ksu/bin", emptySet(), "/data/adb/lb/system/bin")
        assertFalse(script.contains("for c in"))
        assertTrue(script.contains("case ' ' in"))
    }
}
