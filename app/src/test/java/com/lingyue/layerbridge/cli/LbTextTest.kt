package com.lingyue.layerbridge.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LbTextTest {

    @Test
    fun hasEveryKey() {
        assertEquals(T.entries.toSet(), LbText.table("en").keys)
    }

    @Test
    fun alwaysResolvesToEnglish() {
        assertTrue(LbText.get("zh", T.USAGE_INSTALL).startsWith("usage"))
        assertTrue(LbText.get("ja", T.USAGE_INSTALL).startsWith("usage"))
    }

    @Test
    fun valuesAreAscii() {
        LbText.table("en").forEach { (key, value) ->
            assertTrue("non-ASCII in $key", value.all { it.code < 128 })
        }
    }

    @Test
    fun loadsLanguageFile() {
        assertTrue(LbText.table("en")[T.HELP]!!.contains("package manager"))
    }

    @Test
    fun formatsPlaceholders() {
        assertEquals("  1. jq 1.0 (deb)", LbText.get("en", T.PLAN_ITEM, 1, "jq", "1.0", "deb"))
        assertEquals("  2. lua 5.4 (lbpa)", LbText.get("en", T.PLAN_ITEM, 2, "lua", "5.4", "lbpa"))
    }
}
