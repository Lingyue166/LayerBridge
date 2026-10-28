package com.lingyue.layerbridge.repo

import com.lingyue.layerbridge.core.DpkgVersion

sealed interface ResolveOutcome {
    data class Success(val order: List<RepoPackage>) : ResolveOutcome
    data class Failure(val message: String, val packageName: String? = null) : ResolveOutcome
}

/**
 * 最小可用的依赖解析器：Depends / Pre-Depends / Provides / Conflicts / Breaks。
 * 已安装包按包名与 Provides 别名判断满足。
 */
class DependencyResolver(
    available: List<RepoPackage>,
    private val installed: Map<String, String>,
    private val installedProvides: Map<String, String> = emptyMap(),
) {

    private val byName: Map<String, List<RepoPackage>> = available
        .filter { it.isArchitectureSupported }
        .filter { DpkgVersion.isValid(it.version) }
        .groupBy { it.name }

    private val byProvide: Map<String, List<RepoPackage>> = available
        .filter { it.isArchitectureSupported }
        .filter { DpkgVersion.isValid(it.version) }
        .flatMap { pkg ->
            RelationParser.parseProvides(pkg.provides).map { term -> term.name to pkg }
        }
        .groupBy({ it.first }, { it.second })

    fun resolve(targetNames: List<String>): ResolveOutcome {
        val selected = LinkedHashMap<String, RepoPackage>()

        targetNames.forEach { raw ->
            val term = RelationParser.parseTerm(raw)
                ?: return ResolveOutcome.Failure("无法解析包名：$raw")
            if (satisfiedByInstalled(term) || satisfiedBySelected(term, selected)) return@forEach
            val candidate = pick(term, selected)
                ?: return ResolveOutcome.Failure("找不到满足 $term 的包")
            select(candidate, selected)
        }

        val queue = ArrayDeque(selected.values)
        while (queue.isNotEmpty()) {
            val pkg = queue.removeFirst()
            val deps = RelationParser.parse(pkg.preDepends) + RelationParser.parse(pkg.depends)
            deps.forEach { dependency ->
                if (satisfiedByInstalledOrSelected(dependency, selected)) return@forEach
                val chosen = dependency.alternatives.firstNotNullOfOrNull { pick(it, selected) }
                    ?: return ResolveOutcome.Failure(
                        "包 ${pkg.name} 依赖无法满足：$dependency",
                        pkg.name,
                    )
                select(chosen, selected)
                queue.addLast(chosen)
            }
        }

        val conflictError = checkConflicts(selected)
        if (conflictError != null) return ResolveOutcome.Failure(conflictError)

        val order = topologicalOrder(selected)
            ?: return ResolveOutcome.Failure("依赖存在环，无法确定安装顺序")

        return ResolveOutcome.Success(order)
    }

    private fun select(pkg: RepoPackage, selected: LinkedHashMap<String, RepoPackage>) {
        val existing = selected[pkg.name]
        if (existing == null || DpkgVersion.compare(pkg.version, existing.version) > 0) {
            selected[pkg.name] = pkg
        }
    }

    private fun installedVersion(name: String): String? = installed[name] ?: installedProvides[name]

    private fun satisfiedByInstalled(term: DependencyTerm): Boolean {
        val version = installedVersion(term.name) ?: return false
        return term.accepts(version)
    }

    private fun satisfiedBySelected(
        term: DependencyTerm,
        selected: Map<String, RepoPackage>,
    ): Boolean {
        selected[term.name]?.let { if (term.accepts(it.version)) return true }
        return selected.values.any { pkg ->
            RelationParser.parseProvides(pkg.provides).any { provided ->
                provided.name == term.name && term.accepts(pkg.version)
            }
        }
    }

    private fun satisfiedByInstalledOrSelected(
        dependency: Dependency,
        selected: Map<String, RepoPackage>,
    ): Boolean = dependency.alternatives.any { term ->
        satisfiedByInstalled(term) || satisfiedBySelected(term, selected)
    }

    private fun pick(term: DependencyTerm, selected: Map<String, RepoPackage>): RepoPackage? {
        if (term.arch != null && term.arch != "aarch64" && term.arch != "all") return null
        val candidates = byName[term.name].orEmpty() + byProvide[term.name].orEmpty()
        return candidates
            .filter { term.accepts(it.version) }
            .filter { !selected.containsKey(it.name) || selected[it.name]?.version == it.version }
            .maxWithOrNull { a, b -> DpkgVersion.compare(a.version, b.version) }
    }

    private fun checkConflicts(selected: Map<String, RepoPackage>): String? {
        selected.values.forEach { pkg ->
            val terms = RelationParser.parse(pkg.conflicts) + RelationParser.parse(pkg.breaks)
            terms.forEach { dependency ->
                dependency.alternatives.forEach { term ->
                    val targetVersion = selected[term.name]?.version ?: installedVersion(term.name)
                    if (targetVersion != null && term.accepts(targetVersion) && term.name != pkg.name) {
                        return "${pkg.name} 与 ${term.name} 冲突：$dependency"
                    }
                }
            }
        }
        return null
    }

    private fun topologicalOrder(selected: Map<String, RepoPackage>): List<RepoPackage>? {
        val state = mutableMapOf<String, Int>() // 1 visiting, 2 done
        val result = mutableListOf<RepoPackage>()

        fun visit(name: String): Boolean {
            when (state[name]) {
                2 -> return true
                1 -> return false
            }
            val pkg = selected[name] ?: return true
            state[name] = 1
            val deps = RelationParser.parse(pkg.preDepends) + RelationParser.parse(pkg.depends)
            deps.forEach { dependency ->
                dependency.alternatives.forEach { term ->
                    val depName = when {
                        selected.containsKey(term.name) -> term.name
                        else -> selected.values.firstOrNull { candidate ->
                            RelationParser.parseProvides(candidate.provides).any { it.name == term.name }
                        }?.name
                    }
                    if (depName != null && !visit(depName)) return false
                }
            }
            state[name] = 2
            result += pkg
            return true
        }

        selected.keys.forEach { if (!visit(it)) return null }
        return result
    }
}
