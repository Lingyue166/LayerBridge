package com.lingyue.layerbridge.install

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkSyncTest {

    @Test
    fun keepsLinksForStillProvidedCommands() {
        val existing = listOf("/data/adb/ksu/bin/git", "/data/adb/ksu/bin/hello")
        assertEquals(emptyList<String>(), LinkSync.stale(existing, setOf("git", "hello")))
    }

    @Test
    fun removesLinksNoLongerProvided() {
        val existing = listOf(
            "/data/adb/ksu/bin/git",
            "/data/adb/ksu/bin/hello",
            "   ",
        )
        assertEquals(
            listOf("/data/adb/ksu/bin/hello"),
            LinkSync.stale(existing, setOf("git")),
        )
    }
}
