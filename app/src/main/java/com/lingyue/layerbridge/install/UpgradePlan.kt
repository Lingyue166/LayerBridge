package com.lingyue.layerbridge.install

import com.lingyue.layerbridge.core.DpkgVersion
import com.lingyue.layerbridge.core.PathGuard

/** 安装方向：任意版本可升可降，同版本视为重装。 */
enum class UpgradeKind {
    FRESH,
    UPGRADE,
    DOWNGRADE,
    REINSTALL,
    ;

    companion object {
        fun of(current: String?, incoming: String): UpgradeKind = when {
            current == null -> FRESH
            DpkgVersion.compare(incoming, current) > 0 -> UPGRADE
            DpkgVersion.compare(incoming, current) < 0 -> DOWNGRADE
            else -> REINSTALL
        }
    }
}

/** 升级时的文件差异计算：只删旧清单里已不存在且未被保护的文件。 */
object UpgradePlan {

    fun obsolete(
        oldFiles: List<String>,
        newFiles: List<String>,
        keep: List<String>,
        extraRemove: List<String>,
    ): List<String> {
        val retained = newFiles.toSet() + keep.toSet()
        return (oldFiles.filterNot { it in retained } + extraRemove)
            .distinct()
            .filter { PathGuard.isRelativePath(it) }
    }
}
