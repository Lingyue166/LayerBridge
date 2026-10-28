package com.lingyue.layerbridge.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DependencyTest {

    private fun pkg(
        name: String,
        version: String = "1.0",
        depends: String? = null,
        preDepends: String? = null,
        provides: String? = null,
        conflicts: String? = null,
    ) = RepoPackage(
        repoId = "test",
        name = name,
        version = version,
        architecture = "aarch64",
        filename = "pool/$name.deb",
        size = 1,
        sha256 = null,
        installedSize = 1,
        depends = depends,
        preDepends = preDepends,
        provides = provides,
        conflicts = conflicts,
        breaks = null,
        replaces = null,
        description = null,
        format = "deb",
        signature = null,
    )

    @Test
    fun parsesRelations() {
        val deps = RelationParser.parse("libc (>= 1.0), a | b (<< 2), z")
        assertEquals(3, deps.size)
        assertEquals("libc", deps[0].alternatives.first().name)
        assertEquals(">=", deps[0].alternatives.first().constraint?.op)
        assertEquals(2, deps[1].alternatives.size)
        assertEquals("<<", deps[1].alternatives[1].constraint?.op)
    }

    @Test
    fun resolvesDependenciesFirst() {
        val resolver = DependencyResolver(
            listOf(
                pkg("hello", depends = "libc"),
                pkg("libc", version = "1.2"),
            ),
            installed = emptyMap(),
        )
        val outcome = resolver.resolve(listOf("hello"))
        assertTrue(outcome is ResolveOutcome.Success)
        val order = (outcome as ResolveOutcome.Success).order.map { it.name }
        assertEquals(listOf("libc", "hello"), order)
    }

    @Test
    fun picksAlternativeAndBestVersion() {
        val resolver = DependencyResolver(
            listOf(
                pkg("app", depends = "missing | libb (>= 2.0)"),
                pkg("libb", version = "1.0"),
                pkg("libb", version = "2.1"),
            ),
            installed = emptyMap(),
        )
        val outcome = resolver.resolve(listOf("app")) as ResolveOutcome.Success
        assertEquals(listOf("libb", "app"), outcome.order.map { it.name })
        assertEquals("2.1", outcome.order.first().version)
    }

    @Test
    fun installedSatisfiesDependency() {
        val resolver = DependencyResolver(
            listOf(pkg("app", depends = "libc (>= 1.0)")),
            installed = mapOf("libc" to "1.5"),
        )
        val outcome = resolver.resolve(listOf("app")) as ResolveOutcome.Success
        assertEquals(listOf("app"), outcome.order.map { it.name })
    }

    @Test
    fun missingDependencyFails() {
        val resolver = DependencyResolver(
            listOf(pkg("app", depends = "ghost")),
            installed = emptyMap(),
        )
        val outcome = resolver.resolve(listOf("app"))
        assertTrue(outcome is ResolveOutcome.Failure)
    }

    @Test
    fun conflictFails() {
        val resolver = DependencyResolver(
            listOf(pkg("app", conflicts = "bad"), pkg("bad")),
            installed = emptyMap(),
        )
        val outcome = resolver.resolve(listOf("app", "bad"))
        assertTrue(outcome is ResolveOutcome.Failure)
    }

    @Test
    fun providesSatisfiesDependency() {
        val resolver = DependencyResolver(
            listOf(pkg("app", depends = "virtual-mail"), pkg("postfix", provides = "virtual-mail")),
            installed = emptyMap(),
        )
        val outcome = resolver.resolve(listOf("app")) as ResolveOutcome.Success
        assertEquals(listOf("postfix", "app"), outcome.order.map { it.name })
    }

    @Test
    fun cycleFails() {
        val resolver = DependencyResolver(
            listOf(pkg("a", depends = "b"), pkg("b", depends = "a")),
            installed = emptyMap(),
        )
        val outcome = resolver.resolve(listOf("a"))
        assertTrue(outcome is ResolveOutcome.Failure)
    }

    @Test
    fun parsesPackagesIndex() {
        val text = """
            Package: hello
            Version: 2.12.1-1
            Architecture: aarch64
            Depends: libc
            Filename: pool/hello.deb
            Size: 123
            SHA256: ABCDEF
            Description: GNU hello
             long description

            Package: arm-only
            Version: 1.0
            Architecture: arm
            Filename: pool/arm.deb
        """.trimIndent()

        val packages = PackagesParser.parse(text, "termux-main")
        assertEquals(1, packages.size)
        val hello = packages.first()
        assertEquals("hello", hello.name)
        assertEquals("2.12.1-1", hello.version)
        assertEquals("abcdef", hello.sha256)
        assertEquals("GNU hello\nlong description", hello.description)
    }

    @Test
    fun installedProvidesSatisfiesDependency() {
        val resolver = DependencyResolver(
            available = listOf(pkg("app", depends = "virtual-api")),
            installed = emptyMap(),
            installedProvides = mapOf("virtual-api" to "1.2"),
        )

        assertTrue(resolver.resolve(listOf("app")) is ResolveOutcome.Success)
    }

    @Test
    fun conflictsWithInstalledProvidesAreDetected() {
        val resolver = DependencyResolver(
            available = listOf(pkg("app", conflicts = "virtual-api")),
            installed = emptyMap(),
            installedProvides = mapOf("virtual-api" to "1.0"),
        )

        assertTrue(resolver.resolve(listOf("app")) is ResolveOutcome.Failure)
    }
}
