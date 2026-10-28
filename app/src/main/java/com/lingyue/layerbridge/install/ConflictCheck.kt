package com.lingyue.layerbridge.install

import com.lingyue.layerbridge.repo.RelationParser
import com.lingyue.layerbridge.state.InstalledPackage
import com.lingyue.layerbridge.state.PackageRelations

/**
 * 安装前与已安装包做双向冲突检查：新包的 Conflicts/Breaks 打到已装包，
 * 已装包的 Conflicts/Breaks 打回新包；Provides 视作别名参与匹配。
 * 版本按关系式判断，缺版本时按包版本比对。
 */
object ConflictCheck {

    data class Conflict(val packageName: String, val other: String, val reason: String) {
        val message: String get() = "$packageName 与 $other 冲突：$reason"
    }

    fun find(
        name: String,
        version: String,
        relations: PackageRelations,
        installed: List<InstalledPackage>,
    ): Conflict? {
        val incoming = aliases(name, version, relations)
        installed.filter { it.name != name }.forEach { other ->
            val existing = aliases(other.name, other.version, other.relations)

            matches(relations.conflicts, existing)?.let { return Conflict(name, other.name, "Conflicts: $it") }
            matches(relations.breaks, existing)?.let { return Conflict(name, other.name, "Breaks: $it") }
            matches(other.relations.conflicts, incoming)?.let { return Conflict(other.name, name, "Conflicts: $it") }
            matches(other.relations.breaks, incoming)?.let { return Conflict(other.name, name, "Breaks: $it") }
        }
        return null
    }

    private fun aliases(name: String, version: String, relations: PackageRelations): Map<String, String> =
        buildMap {
            put(name, version)
            RelationParser.parseProvides(relations.provides).forEach { putIfAbsent(it.name, version) }
        }

    private fun matches(spec: String?, targets: Map<String, String>): String? =
        RelationParser.parse(spec).asSequence()
            .flatMap { it.alternatives.asSequence() }
            .firstOrNull { term -> targets[term.name]?.let { term.accepts(it) } == true }
            ?.toString()
}
