package com.lingyue.layerbridge.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PayloadPathTest {

    @Test
    fun stripsTermuxPrefix() {
        assertEquals(
            "bin/7z",
            PayloadPath.normalize("./data/data/com.termux/files/usr/bin/7z"),
        )
        assertEquals(
            "share/doc/7zip",
            PayloadPath.normalize("data/data/com.termux/files/usr/share/doc/7zip/"),
        )
    }

    @Test
    fun keepsOrdinaryPaths() {
        assertEquals("bin/hello", PayloadPath.normalize("./bin/hello"))
        assertEquals("lib", PayloadPath.normalize("./lib/"))
        assertNull(PayloadPath.normalize("./"))
        assertNull(PayloadPath.normalize("data/data/com.termux/files/usr"))
        assertNull(PayloadPath.normalize("./data/data/com.termux/files/usr/"))
    }

    @Test
    fun rewritesAbsoluteTermuxLinks() {
        assertEquals(
            "/data/adb/lb/usr/bin/sh",
            PayloadPath.mapLink("/data/data/com.termux/files/usr/bin/sh", "/data/adb/lb/usr"),
        )
        assertEquals("lib/libfoo.so", PayloadPath.mapLink("lib/libfoo.so", "/data/adb/lb/usr"))
    }
}
