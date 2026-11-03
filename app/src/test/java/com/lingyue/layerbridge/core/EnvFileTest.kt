package com.lingyue.layerbridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvFileTest {

    @Test
    fun packageEnvRoundTrip() {
        val env = mapOf("FOO" to "bar", "BAZ" to "a b")
        val text = EnvFile.renderPackageEnv(env)
        assertEquals(env, EnvFile.parsePackageEnv(text))
    }

    @Test
    fun parseIgnoresCommentsAndBadLines() {
        val text = """
            # comment
            GOOD=1
            broken
            =nokey
            WITH=EQUALS=a=b
        """.trimIndent()
        val env = EnvFile.parsePackageEnv(text)
        assertEquals("1", env["GOOD"])
        assertEquals("EQUALS=a=b", env["WITH"])
    }

    @Test
    fun renderIncludesPackageEnv() {
        val text = EnvFile.render(mapOf("pkg" to mapOf("FOO" to "bar")))
        assertTrue(text.contains("# package: pkg"))
        assertTrue(text.contains("export FOO="))
        assertTrue(text.contains("bar"))
    }

    @Test
    fun baseEnvironmentHasPrefixExports() {
        val text = EnvFile.render()
        assertTrue(text.contains("LB_PREFIX="))
        assertTrue(text.contains("export PATH"))
    }
}
