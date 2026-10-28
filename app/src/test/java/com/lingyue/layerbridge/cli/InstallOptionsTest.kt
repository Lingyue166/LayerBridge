package com.lingyue.layerbridge.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallOptionsTest {

    @Test
    fun parsesPathAndFlags() {
        val options = parseInstall(listOf("--allow-unsigned", "/sdcard/hello.deb", "--skip-scan")).getOrThrow()

        assertEquals("/sdcard/hello.deb", options.path)
        assertTrue(options.allowUnsigned)
        assertTrue(options.skipScan)
    }

    @Test
    fun defaultsToStrictPolicy() {
        val options = parseInstall(listOf("hello.deb")).getOrThrow()

        assertFalse(options.allowUnsigned)
        assertFalse(options.skipScan)
    }

    @Test
    fun rejectsUnknownFlagsAndWrongArity() {
        assertTrue(parseInstall(listOf("--nope", "hello.deb")).isFailure)
        assertTrue(parseInstall(emptyList()).isFailure)
        assertTrue(parseInstall(listOf("a.deb", "b.deb")).isFailure)
    }
}
