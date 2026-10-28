package com.lingyue.layerbridge.expose

import org.junit.Assert.assertEquals
import org.junit.Test

class ExposeCommandsTest {

    @Test
    fun picksBinAndSbinAndSkipsBlocked() {
        val commands = ExposeCommands.of(
            listOf("bin/a", "sbin/b", "share/c", "bin/a", "bin/sh", "bin/../x"),
            blocked = setOf("sh"),
        )

        assertEquals(listOf("a", "b"), commands)
    }
}
