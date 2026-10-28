package com.lingyue.layerbridge.expose

import org.junit.Assert.assertTrue
import org.junit.Test

class WrapperTest {

    @Test
    fun generatesWrapperWithEnvironment() {
        val script = Wrapper.script("bin/hello", extraEnv = mapOf("HELLO_HOME" to "/data/local/lb/usr/share/hello"))
        assertTrue(script.startsWith("#!/system/bin/sh"))
        assertTrue(script.contains("LB_PREFIX='/data/local/lb/usr'"))
        assertTrue(script.contains("export PATH=\"\$LB_PREFIX/bin:\$PATH\""))
        assertTrue(script.contains("HELLO_HOME='/data/local/lb/usr/share/hello'"))
        assertTrue(script.contains("exec \"\$LB_PREFIX/bin/hello\" \"\$@\""))
    }
}
