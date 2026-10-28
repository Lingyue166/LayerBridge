package com.lingyue.layerbridge.install

import org.junit.Assert.assertEquals
import org.junit.Test

class UpgradePlanTest {

    @Test
    fun classifiesDirectionFromAnyVersion() {
        assertEquals(UpgradeKind.FRESH, UpgradeKind.of(null, "1.0"))
        assertEquals(UpgradeKind.UPGRADE, UpgradeKind.of("1.0", "2.0"))
        assertEquals(UpgradeKind.DOWNGRADE, UpgradeKind.of("2.0", "1.0"))
        assertEquals(UpgradeKind.REINSTALL, UpgradeKind.of("1.0", "1.0"))
    }

    @Test
    fun removesFilesMissingFromNewPackageButKeepsProtectedOnes() {
        val obsolete = UpgradePlan.obsolete(
            oldFiles = listOf("bin/hello", "etc/hello.conf", "share/old.txt"),
            newFiles = listOf("bin/hello", "etc/hello.conf"),
            keep = listOf("etc/hello.conf"),
            extraRemove = listOf("share/legacy"),
        )

        assertEquals(listOf("share/old.txt", "share/legacy"), obsolete)
    }

    @Test
    fun ignoresUnsafePaths() {
        assertEquals(
            emptyList<String>(),
            UpgradePlan.obsolete(listOf("../evil", "/abs"), emptyList(), emptyList(), listOf("../x")),
        )
    }
}
