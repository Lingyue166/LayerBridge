package com.lingyue.layerbridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PathGuardTest {

    @Test
    fun packageNames() {
        assertTrue(PathGuard.isPackageName("hello"))
        assertTrue(PathGuard.isPackageName("libc++"))
        assertFalse(PathGuard.isPackageName("Hello"))
        assertFalse(PathGuard.isPackageName("../evil"))
        assertFalse(PathGuard.isPackageName("a b"))
    }

    @Test
    fun relativePaths() {
        assertTrue(PathGuard.isRelativePath("bin/hello"))
        assertTrue(PathGuard.isRelativePath("share/doc/a.txt"))
        assertFalse(PathGuard.isRelativePath("/etc/passwd"))
        assertFalse(PathGuard.isRelativePath("../x"))
        assertFalse(PathGuard.isRelativePath("a/../../x"))
        assertFalse(PathGuard.isRelativePath("a//b"))
        assertFalse(PathGuard.isRelativePath(""))
    }

    @Test
    fun resolveStaysInsideRoot() {
        val root = File("/tmp/lb-root")
        assertEquals(File("/tmp/lb-root/bin/hello"), PathGuard.resolveUnder(root, "bin/hello"))
        assertNull(PathGuard.resolveUnder(root, "../outside"))
    }

    @Test
    fun ancestorsAreDeduplicatedAndDeepestFirst() {
        val paths = listOf("bin/foo", "share/doc/a.txt", "share/doc/b.txt", "share/readme")
        assertEquals(listOf("share/doc", "share", "bin"), PathGuard.ancestors(paths))
        assertEquals(emptyList<String>(), PathGuard.ancestors(listOf("top")))
    }

    @Test
    fun quoting() {
        assertEquals("'abc'", PathGuard.quote("abc"))
        assertEquals("'a'\\''b'", PathGuard.quote("a'b"))
    }
}
