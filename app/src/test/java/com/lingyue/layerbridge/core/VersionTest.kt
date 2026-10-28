package com.lingyue.layerbridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTest {

    @Test
    fun basicOrdering() {
        assertTrue(DpkgVersion.compare("1.0", "1.1") < 0)
        assertEquals(0, DpkgVersion.compare("1.0", "1.0"))
        assertTrue(DpkgVersion.compare("2.0", "1.9") > 0)
        assertTrue(DpkgVersion.compare("1.0-1", "1.0-2") < 0)
        assertTrue(DpkgVersion.compare("1.0", "1.0-1") < 0)
    }

    @Test
    fun epochWins() {
        assertTrue(DpkgVersion.compare("1:1.0", "2.0") > 0)
        assertTrue(DpkgVersion.compare("0:9.9", "1:0.1") < 0)
    }

    @Test
    fun tildeSortsBeforeEverything() {
        assertTrue(DpkgVersion.compare("1.0~rc1", "1.0") < 0)
        assertTrue(DpkgVersion.compare("1.0~~", "1.0~") < 0)
        assertTrue(DpkgVersion.compare("1.0~rc1", "1.0~rc2") < 0)
    }

    @Test
    fun lettersBeforePunctuation() {
        assertTrue(DpkgVersion.compare("1.0a", "1.0b") < 0)
        assertTrue(DpkgVersion.compare("1.0", "1.0+git") < 0)
        assertTrue(DpkgVersion.compare("1.0.1", "1.0") > 0)
    }

    @Test
    fun zeroPaddedNumbers() {
        assertEquals(0, DpkgVersion.compare("1.01", "1.1"))
        assertTrue(DpkgVersion.compare("1.001", "1.01") == 0)
    }

    @Test
    fun constraints() {
        assertTrue(DpkgVersion.satisfies("1.2", ">=", "1.0"))
        assertFalse(DpkgVersion.satisfies("1.2", ">>", "1.2"))
        assertTrue(DpkgVersion.satisfies("1.2", "=", "1.2"))
        assertTrue(DpkgVersion.satisfies("1.2", ">", "1.2~rc1"))
        assertTrue(DpkgVersion.satisfies("1.2", null, null))
    }

    @Test
    fun validity() {
        assertTrue(DpkgVersion.isValid("1:2.3-4"))
        assertFalse(DpkgVersion.isValid(""))
        assertFalse(DpkgVersion.isValid("1.0 beta"))
        assertFalse(DpkgVersion.isValid("x:1.0"))
    }
}
