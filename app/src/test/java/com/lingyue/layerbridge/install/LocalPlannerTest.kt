package com.lingyue.layerbridge.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocalPlannerTest {

    private fun pkg(
        name: String,
        version: String = "1.0",
        depends: String? = null,
        preDepends: String? = null,
        provides: String? = null,
        conflicts: String? = null,
        breaks: String? = null,
    ) = LocalPackage(
        name = name,
        version = version,
        architecture = "aarch64",
        format = "deb",
        path = File("/tmp/$name.deb"),
        depends = depends,
        preDepends = preDepends,
        provides = provides,
        conflicts = conflicts,
        breaks = breaks,
    )

    @Test
    fun ordersDependenciesFirst() {
        val plan = LocalPlanner.plan(
            listOf(pkg("app", depends = "lib"), pkg("lib")),
            installed = emptyMap(),
        )

        assertEquals(listOf("lib", "app"), plan.order.map { it.name })
        assertTrue(plan.missing.isEmpty())
        assertNull(plan.cycle)
    }

    @Test
    fun providesSatisfiesDependency() {
        val plan = LocalPlanner.plan(
            listOf(pkg("app", depends = "virtual-api"), pkg("provider", provides = "virtual-api")),
            installed = emptyMap(),
        )

        assertEquals(listOf("provider", "app"), plan.order.map { it.name })
        assertTrue(plan.missing.isEmpty())
    }

    @Test
    fun installedPackageSatisfiesDependency() {
        val plan = LocalPlanner.plan(
            listOf(pkg("app", depends = "libc (>= 1.0)")),
            installed = mapOf("libc" to "2.0"),
        )

        assertEquals(listOf("app"), plan.order.map { it.name })
        assertTrue(plan.missing.isEmpty())
    }

    @Test
    fun reportsMissingDependency() {
        val plan = LocalPlanner.plan(listOf(pkg("app", depends = "libfoo")), installed = emptyMap())

        assertEquals(listOf("libfoo"), plan.missing)
    }

    @Test
    fun detectsDependencyCycle() {
        val plan = LocalPlanner.plan(
            listOf(pkg("a", depends = "b"), pkg("b", depends = "a")),
            installed = emptyMap(),
        )

        assertNotNull(plan.cycle)
    }

    @Test
    fun reportsConflictsBetweenLocalPackages() {
        val plan = LocalPlanner.plan(
            listOf(pkg("a", conflicts = "b"), pkg("b")),
            installed = emptyMap(),
        )

        assertTrue(plan.conflicts.any { it.contains("Conflicts") })
    }
}
