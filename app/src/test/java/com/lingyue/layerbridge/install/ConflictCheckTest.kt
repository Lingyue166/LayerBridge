package com.lingyue.layerbridge.install

import com.lingyue.layerbridge.state.InstalledPackage
import com.lingyue.layerbridge.state.PackageRelations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConflictCheckTest {

    private fun installed(
        name: String,
        version: String = "1.0",
        relations: PackageRelations = PackageRelations(),
    ) = InstalledPackage(
        name = name,
        version = version,
        architecture = "aarch64",
        format = "deb",
        repo = "test",
        installedAt = "2026-01-01T00:00:00Z",
        signer = null,
        scan = "pass",
        scriptsStatus = "ok",
        relations = relations,
    )

    @Test
    fun unrelatedPackagesDoNotConflict() {
        val conflict = ConflictCheck.find(
            "newpkg",
            "1.0",
            PackageRelations(conflicts = "oldpkg"),
            listOf(installed("other")),
        )

        assertNull(conflict)
    }

    @Test
    fun incomingConflictsInstalled() {
        val conflict = ConflictCheck.find(
            "newpkg",
            "1.0",
            PackageRelations(conflicts = "oldpkg"),
            listOf(installed("oldpkg")),
        )

        assertEquals("newpkg 与 oldpkg 冲突：Conflicts: oldpkg", conflict?.message)
    }

    @Test
    fun installedConflictsIncoming() {
        val conflict = ConflictCheck.find(
            "newpkg",
            "1.0",
            PackageRelations(),
            listOf(installed("oldpkg", relations = PackageRelations(conflicts = "newpkg"))),
        )

        assertEquals("oldpkg 与 newpkg 冲突：Conflicts: newpkg", conflict?.message)
    }

    @Test
    fun providesAliasesParticipate() {
        val conflict = ConflictCheck.find(
            "newpkg",
            "1.0",
            PackageRelations(),
            listOf(installed("oldpkg", relations = PackageRelations(provides = "virtual-api"))),
        )
        assertNull(conflict)

        val viaProvides = ConflictCheck.find(
            "newpkg",
            "1.0",
            PackageRelations(breaks = "virtual-api"),
            listOf(installed("oldpkg", relations = PackageRelations(provides = "virtual-api"))),
        )
        assertEquals("newpkg 与 oldpkg 冲突：Breaks: virtual-api", viaProvides?.message)
    }

    @Test
    fun versionConstraintsAreHonoured() {
        val conflict = ConflictCheck.find(
            "newpkg",
            "1.0",
            PackageRelations(conflicts = "oldpkg (>= 2.0)"),
            listOf(installed("oldpkg", version = "1.5")),
        )

        assertNull(conflict)
    }

    @Test
    fun reinstallingSameNameIsNotAConflict() {
        val conflict = ConflictCheck.find(
            "oldpkg",
            "2.0",
            PackageRelations(conflicts = "oldpkg"),
            listOf(installed("oldpkg", version = "1.0")),
        )

        assertNull(conflict)
    }
}
