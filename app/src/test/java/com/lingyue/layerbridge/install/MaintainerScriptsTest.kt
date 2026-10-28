package com.lingyue.layerbridge.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MaintainerScriptsTest {

    private val scripts = MaintainerScripts()

    @Test
    fun stripsLbpaControlPrefix() {
        assertEquals("preinst", scripts.scriptName("control/preinst"))
        assertEquals("preinst", scripts.scriptName("./control/preinst"))
        assertEquals("postinst", scripts.scriptName("postinst"))
    }

    @Test
    fun rejectsPathTraversalAndNestedNames() {
        assertThrows(IllegalArgumentException::class.java) { scripts.scriptName("../../etc/passwd") }
        assertThrows(IllegalArgumentException::class.java) { scripts.scriptName("control/../x") }
        assertThrows(IllegalArgumentException::class.java) { scripts.scriptName("") }
    }
}
