package com.lingyue.layerbridge.expose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.lingyue.layerbridge.core.LbPaths
import org.junit.Test

class WrapperTest {

    @Test
    fun generatesWrapperWithEnvironment() {
        val script = Wrapper.script("bin/hello", extraEnv = mapOf("HELLO_HOME" to "${LbPaths.PREFIX}/share/hello"))
        assertTrue(script.startsWith("#!/system/bin/sh"))
        assertTrue(script.contains("LB_PREFIX='${LbPaths.PREFIX}'"))
        assertTrue(script.contains("export PATH=\"\$LB_PREFIX/bin:\$LB_PREFIX/sbin:\$PATH\""))
        assertTrue(script.contains("HELLO_HOME='${LbPaths.PREFIX}/share/hello'"))
        assertTrue(script.contains("exec \"\$LB_PREFIX/bin/hello\" \"\$@\""))
        assertTrue(script.contains("GIT_EXEC_PATH"))
        assertTrue(script.contains("GIT_TEMPLATE_DIR"))
    }

    @Test
    fun recordsOwnerAndTarget() {
        val script = Wrapper.script("sbin/foo", owner = "busybox")

        assertTrue(script.contains("# lb-owner: busybox"))
        assertTrue(script.contains("# lb-target: sbin/foo"))
        assertEquals("busybox", Wrapper.ownerOf(script))
    }

    @Test
    fun ownerIsAbsentWithoutPackage() {
        val script = Wrapper.script("bin/foo")

        assertNull(Wrapper.ownerOf(script))
        assertTrue(script.contains("# lb-target: bin/foo"))
    }

    @Test
    fun providersPreferBinOverSbin() {
        val files = mapOf(
            "aaa" to listOf("sbin/foo", "share/aaa"),
            "bbb" to listOf("bin/foo"),
        )

        val providers = Wrapper.providers(files, "foo")

        assertEquals(listOf("bbb", "aaa"), providers.map { it.packageName })
        assertEquals("bin/foo", providers.first().target)
        assertEquals("sbin/foo", providers.last().target)
    }

    @Test
    fun providersIgnoreUnrelatedCommands() {
        val files = mapOf("aaa" to listOf("bin/bar", "bin/foo"))

        assertEquals(emptyList<WrapperProvider>(), Wrapper.providers(files, "baz"))
        assertEquals(listOf("aaa"), Wrapper.providers(files, "foo").map { it.packageName })
    }
}
