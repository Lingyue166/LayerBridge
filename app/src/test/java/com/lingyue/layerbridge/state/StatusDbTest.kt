package com.lingyue.layerbridge.state

import org.junit.Assert.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StatusDbTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun pkg(
        name: String = "foo",
        relations: PackageRelations = PackageRelations(),
        removeHooks: List<String> = emptyList(),
    ) = InstalledPackage(
        name = name,
        version = "1.0",
        architecture = "aarch64",
        format = "deb",
        repo = "test",
        installedAt = "2026-01-01T00:00:00Z",
        signer = "repo:test",
        scan = "pass",
        scriptsStatus = "ok",
        relations = relations,
        removeHooks = removeHooks,
    )

    @Test
    fun roundTripsRelationsAndRemoveHooks() {
        val db = StatusDb(LocalStateStorage(), tmp.root.absolutePath)
        val original = pkg(
            relations = PackageRelations(
                depends = "libc",
                provides = "virtual-api",
                conflicts = "oldpkg",
                breaks = "olderpkg",
            ),
            removeHooks = listOf("on-remove"),
        )

        runBlocking {
            db.upsert(original)
        }
        val loaded = runBlocking { db.load().single() }

        assertEquals(original.relations, loaded.relations)
        assertEquals(original.removeHooks, loaded.removeHooks)
        assertEquals(original, loaded)
    }

    /** 旧版本写入的行只有 9 列，必须继续可读。 */
    @Test
    fun readsLegacyNineColumnRow() {
        tmp.root.resolve("installed.tsv").writeText("foo\t1.0\taarch64\tdeb\ttest\t2026-01-01T00:00:00Z\t\tpass\tok\n")

        val loaded = runBlocking { StatusDb(LocalStateStorage(), tmp.root.absolutePath).load().single() }

        assertEquals("foo", loaded.name)
        assertEquals(PackageRelations(), loaded.relations)
        assertTrue(loaded.removeHooks.isEmpty())
    }

    @Test
    fun omitsMetaColumnWhenEmpty() {
        val db = StatusDb(LocalStateStorage(), tmp.root.absolutePath)
        runBlocking { db.upsert(pkg()) }

        val line = tmp.root.resolve("installed.tsv").readText().trimEnd('\n')

        assertEquals(9, line.split('\t').size)
    }

    @Test
    fun transactionTracksInterruptedInstalls() = runBlocking {
        val db = StatusDb(LocalStateStorage(), tmp.root.absolutePath)

        db.beginTransaction("foo", "2.0")
        assertEquals("begin", db.readTransaction("foo")?.phase)

        db.recordTransaction(
            InstallTransaction("foo", "2.0", "merge", listOf("bin/foo"), listOf("foo")),
        )
        val transaction = db.readTransaction("foo")
        assertEquals(listOf("bin/foo"), transaction?.files)
        assertEquals(listOf("foo"), transaction?.wrappers)

        db.endTransaction("foo")
        assertNull(db.readTransaction("foo"))
    }

    @Test
    fun preservedDataSurvivesNormalUninstallRegistration() = runBlocking {
        val db = StatusDb(LocalStateStorage(), tmp.root.absolutePath)

        db.writePreservedData("foo", listOf("etc/foo.conf", "var/lib/foo/db"))
        assertEquals(listOf("etc/foo.conf", "var/lib/foo/db"), db.readPreservedData("foo"))

        db.writePreservedData("foo", emptyList())
        assertTrue(db.readPreservedData("foo").isEmpty())
    }
}
