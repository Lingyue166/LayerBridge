package com.lingyue.layerbridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellTest {

    private val sh = listOf("sh", "-c")

    @Test
    fun capturesExitCodeAndOutput() {
        val result = Shell.runProcess(sh, "printf hi; exit 3", 5_000) { }

        assertEquals(3, result.exitCode)
        assertEquals("hi", result.output)
    }

    @Test
    fun feedsStdin() {
        val result = Shell.runProcess(sh, "cat", 5_000) { it.write("hello".toByteArray()) }

        assertEquals(0, result.exitCode)
        assertEquals("hello", result.output)
    }

    @Test
    fun timesOutAndKillsHangingProcess() {
        val startedAt = System.currentTimeMillis()
        val result = Shell.runProcess(sh, "sleep 30", 300) { }
        val elapsed = System.currentTimeMillis() - startedAt

        assertEquals(-1, result.exitCode)
        assertTrue(result.output, result.output.contains("命令超时"))
        assertTrue("实际耗时 ${elapsed}ms", elapsed < 5_000)
    }

    /** 输出远超管道缓冲（约 2.7MB）时不能死锁，且总量截断到上限。 */
    @Test
    fun drainsLargeOutputWithoutDeadlock() {
        val result = Shell.runProcess(sh, "seq 1 400000", 30_000) { }

        assertEquals(0, result.exitCode)
        assertTrue(result.output.length <= Shell.MAX_OUTPUT)
        assertTrue(result.output.startsWith("1\n"))
    }
}
