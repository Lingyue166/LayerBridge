package com.lingyue.layerbridge.repo

import com.lingyue.layerbridge.core.DpkgVersion

data class VersionConstraint(val op: String, val version: String)

data class DependencyTerm(
    val name: String,
    val arch: String?,
    val constraint: VersionConstraint?,
) {
    fun accepts(version: String): Boolean =
        constraint == null || DpkgVersion.satisfies(version, constraint.op, constraint.version)

    override fun toString(): String = buildString {
        append(name)
        arch?.let { append(':').append(it) }
        constraint?.let { append(" (").append(it.op).append(' ').append(it.version).append(')') }
    }
}

data class Dependency(val alternatives: List<DependencyTerm>) {
    override fun toString(): String = alternatives.joinToString(" | ")
}

object RelationParser {

    private val TERM = Regex(
        "^([a-z0-9][a-z0-9+.-]*)(?::([A-Za-z0-9-]+))?" +
            "(?:\\s*\\(\\s*(<<|<=|=|>=|>>|<|>)\\s*([^)]+?)\\s*\\))?$",
    )

    fun parse(raw: String?): List<Dependency> {
        if (raw.isNullOrBlank()) return emptyList()
        return splitTopLevel(raw, ',').mapNotNull { group ->
            val terms = splitTopLevel(group, '|').mapNotNull(::parseTerm)
            if (terms.isEmpty()) null else Dependency(terms)
        }
    }

    fun parseTerm(raw: String): DependencyTerm? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val match = TERM.matchEntire(text) ?: return null
        val op = match.groupValues[3]
        val version = match.groupValues[4]
        val constraint = if (op.isNotEmpty() && version.isNotEmpty()) VersionConstraint(op, version) else null
        val arch = match.groupValues[2].ifEmpty { null }
        return DependencyTerm(match.groupValues[1], arch, constraint)
    }

    fun parseProvides(raw: String?): List<DependencyTerm> = parse(raw).flatMap { it.alternatives }

    private fun splitTopLevel(text: String, separator: Char): List<String> {
        val result = mutableListOf<String>()
        var depth = 0
        val current = StringBuilder()
        text.forEach { ch ->
            when (ch) {
                '(' -> {
                    depth++
                    current.append(ch)
                }
                ')' -> {
                    depth = (depth - 1).coerceAtLeast(0)
                    current.append(ch)
                }
                separator -> if (depth == 0) {
                    result += current.toString()
                    current.clear()
                } else {
                    current.append(ch)
                }
                else -> current.append(ch)
            }
        }
        result += current.toString()
        return result.map { it.trim() }.filter { it.isNotEmpty() }
    }
}
