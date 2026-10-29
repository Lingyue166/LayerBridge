package com.lingyue.layerbridge.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LbTextTest {

    @Test
    fun zhAndEnHaveSameKeys() {
        assertEquals(T.entries.toSet(), LbText.table("zh").keys)
        assertEquals(T.entries.toSet(), LbText.table("en").keys)
    }

    @Test
    fun selectsLanguageByPrefix() {
        assertTrue(LbText.get("zh", T.USAGE_INSTALL).startsWith("用法"))
        assertTrue(LbText.get("zh-CN", T.USAGE_INSTALL).startsWith("用法"))
        assertTrue(LbText.get("en", T.USAGE_INSTALL).startsWith("usage"))
        assertTrue(LbText.get("ja", T.USAGE_INSTALL).startsWith("usage"))
    }

    @Test
    fun loadsLanguageFiles() {
        assertTrue(LbText.table("zh")[T.HELP]!!.contains("包管理器"))
        assertTrue(LbText.table("en")[T.HELP]!!.contains("package manager"))
    }

    @Test
    fun formatsPlaceholders() {
        assertEquals("  1. jq 1.0 (deb)", LbText.get("en", T.PLAN_ITEM, 1, "jq", "1.0", "deb"))
        assertEquals("  2. lua 5.4 (lbpa)", LbText.get("en", T.PLAN_ITEM, 2, "lua", "5.4", "lbpa"))
    }
}
