package com.lingyue.layerbridge.expose

import com.lingyue.layerbridge.core.LbPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExposeTargetsTest {

    @Test
    fun stealthOnlyWritesInsidePrefix() {
        val targets = ExposeTargets.of(ExposeMode.STEALTH, "hello", moduleReady = true)

        assertEquals(listOf("${LbPaths.SYSTEM_BIN}/hello"), targets)
        assertFalse(targets.any { it.startsWith(LbPaths.OVERLAY_UPPER) })
        assertFalse(targets.any { it.startsWith(LbPaths.MODULE_SYSTEM_BIN) })
    }

    @Test
    fun systemModeAddsOverlayAndModulePaths() {
        val targets = ExposeTargets.of(ExposeMode.SYSTEM, "hello", moduleReady = true)

        assertTrue(targets.contains("${LbPaths.OVERLAY_UPPER}/hello"))
        assertTrue(targets.contains("${LbPaths.MODULE_SYSTEM_BIN}/hello"))
        assertTrue(targets.contains("${LbPaths.SYSTEM_BIN}/hello"))
    }

    @Test
    fun systemModeSkipsModulePathWhenModuleMissing() {
        val targets = ExposeTargets.of(ExposeMode.SYSTEM, "hello", moduleReady = false)

        assertEquals(
            listOf("${LbPaths.OVERLAY_UPPER}/hello", "${LbPaths.SYSTEM_BIN}/hello"),
            targets,
        )
    }
}
