package com.lingyue.layerbridge.expose

import com.lingyue.layerbridge.core.PathGuard

/** 从文件清单里挑出可暴露的命令名（bin/、sbin/ 下的可执行名，去重排序）。 */
object ExposeCommands {

    fun of(paths: List<String>, blocked: Set<String> = emptySet()): List<String> =
        paths.asSequence()
            .filter { PathGuard.isRelativePath(it) }
            .filter { it.startsWith("bin/") || it.startsWith("sbin/") }
            .map { it.substringAfterLast('/') }
            .filter { Wrapper.isExposableName(it) && it !in blocked }
            .distinct()
            .sorted()
            .toList()
}
