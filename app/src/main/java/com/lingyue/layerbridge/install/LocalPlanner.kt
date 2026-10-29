package com.lingyue.layerbridge.install

import com.lingyue.layerbridge.core.DpkgVersion
import com.lingyue.layerbridge.repo.DependencyTerm
import com.lingyue.layerbridge.repo.RelationParser
import java.io.File

/** 本地包（目录内的 deb/LBPA），只保留规划所需字段。 */
data class LocalPackage(
    val name: String,
    val version: String,
    val architecture: String,
    val format: String,
    val path: File,
    val depends: String? = null,
    val preDepends: String? = null,
    val provides: String? = null,
    val conflicts: String? = null,
    val breaks: String? = null,
) {
    val providedNames: List<String> get() = RelationParser.parseProvides(provides).map { it.name }
}

data class LocalPlan(
    val order: List<LocalPackage>,
    val missing: List<String>,
    val conflicts: List<String>,
    val cycle: String?,
)

/**
 * 目录安装的依赖规划：只考虑目录内包与已安装包，拓扑排序后给出安装顺序。
 * 用户不需要手工排序；缺失依赖与冲突在安装前报出。
 */
object LocalPlanner {

    fun plan(packages: List<LocalPackage>, installed: Map<String, String>): LocalPlan {
        val byName = packages.associateBy { it.name }
        val byProvide = packages.flatMap { pkg -> pkg.providedNames.map { it to pkg } }.toMap()
        val state = mutableMapOf<String, Int>()
        val order = mutableListOf<LocalPackage>()
        val missing = linkedSetOf<String>()
        var cycle: String? = null

        fun localProvider(term: DependencyTerm): LocalPackage? =
            (byName[term.name] ?: byProvide[term.name])
                ?.takeIf { term.accepts(it.version) }

        fun installedSatisfies(term: DependencyTerm): Boolean =
            installed[term.name]?.let { term.accepts(it) } == true

        fun visit(pkg: LocalPackage) {
            when (state[pkg.name]) {
                2 -> return
                1 -> {
                    if (cycle == null) cycle = pkg.name
                    return
                }
            }
            state[pkg.name] = 1
            val deps = RelationParser.parse(pkg.preDepends) + RelationParser.parse(pkg.depends)
            deps.forEach { dep ->
                val local = dep.alternatives.firstNotNullOfOrNull { localProvider(it) }
                if (local != null) {
                    visit(local)
                } else if (!dep.alternatives.any { installedSatisfies(it) }) {
                    missing += dep.toString()
                }
            }
            state[pkg.name] = 2
            order += pkg
        }

        packages.forEach { visit(it) }

        val conflicts = checkConflicts(packages, installed)
        return LocalPlan(order, missing.toList(), conflicts, cycle)
    }

    private fun checkConflicts(packages: List<LocalPackage>, installed: Map<String, String>): List<String> {
        val aliases = packages.associate { it.name to it.providedNames }
        val found = mutableListOf<String>()

        fun hits(spec: String?, version: String, targetName: String, targetVersion: String?): String? {
            if (spec.isNullOrBlank() || targetVersion == null) return null
            return RelationParser.parse(spec).asSequence()
                .flatMap { it.alternatives.asSequence() }
                .firstOrNull { it.name == targetName && it.accepts(targetVersion) }
                ?.toString()
        }

        packages.forEach { pkg ->
            packages.filter { it.name != pkg.name }.forEach { other ->
                hits(pkg.conflicts, pkg.version, other.name, other.version)?.let {
                    found += "${pkg.name} 与 ${other.name} 冲突：Conflicts: $it"
                }
                hits(pkg.breaks, pkg.version, other.name, other.version)?.let {
                    found += "${pkg.name} 与 ${other.name} 冲突：Breaks: $it"
                }
            }
            installed.forEach { (name, version) ->
                hits(pkg.conflicts, pkg.version, name, version)?.let {
                    found += "${pkg.name} 与已安装 $name 冲突：Conflicts: $it"
                }
                hits(pkg.breaks, pkg.version, name, version)?.let {
                    found += "${pkg.name} 与已安装 $name 冲突：Breaks: $it"
                }
            }
            aliases.forEach { (owner, provided) ->
                provided.forEach { alias ->
                    hits(pkg.conflicts, pkg.version, alias, packages.first { it.name == owner }.version)?.let {
                        found += "${pkg.name} 与 $owner 提供的 $alias 冲突：Conflicts: $it"
                    }
                }
            }
        }
        return found.distinct()
    }
}
