package com.lingyue.layerbridge.cli

import com.lingyue.layerbridge.state.InstalledPackage
import com.lingyue.layerbridge.state.PackageRelations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CliParsingTest {

    @Test
    fun tokenizesOnWhitespace() {
        assertEquals(listOf("install", "/sdcard/a.deb"), tokenize("  install   /sdcard/a.deb  "))
        assertEquals(emptyList<String>(), tokenize("   "))
    }

    @Test
    fun describeShowsMetadataAndRelations() {
        val pkg = InstalledPackage(
            name = "hello",
            version = "2.12.1-1",
            architecture = "aarch64",
            format = "lbpa",
            repo = "local",
            installedAt = "2026-10-28T00:00:00Z",
            signer = "Community A",
            scan = "pass",
            scriptsStatus = "ok",
            relations = PackageRelations(depends = "libc", provides = "virtual-hello"),
            removeHooks = listOf("on-remove"),
        )

        val text = describe(pkg)

        assertTrue(text.contains("Package: hello"))
        assertTrue(text.contains("Version: 2.12.1-1"))
        assertTrue(text.contains("Provides: virtual-hello"))
        assertTrue(text.contains("RemoveHooks: on-remove"))
        assertTrue(text.trimEnd().lines().none { it.isBlank() })
    }

    @Test
    fun removeKeepsDataUnlessForced() {
        assertFalse(parseRemove(listOf("hello")).getOrThrow().force)
        assertTrue(parseRemove(listOf("--force", "hello")).getOrThrow().force)
        assertTrue(parseRemove(listOf("hello", "-f")).getOrThrow().force)
        assertTrue(parseRemove(listOf("--purge", "hello")).getOrThrow().force)
    }

    @Test
    fun removeRejectsUnknownFlagsAndWrongArity() {
        assertTrue(parseRemove(listOf("--nope", "hello")).isFailure)
        assertTrue(parseRemove(emptyList()).isFailure)
        assertTrue(parseRemove(listOf("a", "b")).isFailure)
    }
}
