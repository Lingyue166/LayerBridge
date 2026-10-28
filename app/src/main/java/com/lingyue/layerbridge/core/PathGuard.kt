package com.lingyue.layerbridge.core

import java.io.File

object PathGuard {

    private val PACKAGE_NAME = Regex("^[a-z0-9][a-z0-9+.-]{0,127}$")
    private val SAFE_PATH_SEGMENT = Regex("^[^/\\\\\u0000]+$")

    fun isPackageName(value: String): Boolean = PACKAGE_NAME.matches(value)

    fun isRelativePath(path: String): Boolean {
        if (path.isBlank()) return false
        if (path.startsWith("/") || path.startsWith("\\")) return false
        if (path.contains('\\') || path.contains('\u0000')) return false
        val segments = path.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return false
        return segments.all { SAFE_PATH_SEGMENT.matches(it) }
    }

    fun resolveUnder(root: File, relativePath: String): File? {
        if (!isRelativePath(relativePath)) return null
        val rootPath = root.absoluteFile.toPath().normalize()
        val target = root.resolve(relativePath).absoluteFile.toPath().normalize()
        return if (target.startsWith(rootPath)) target.toFile() else null
    }

    /** 相对路径的父目录链，去重后按长度倒序（先深后浅），用于卸载后清理空目录。 */
    fun ancestors(paths: List<String>): List<String> = paths.asSequence()
        .map { it.substringBeforeLast('/', "") }
        .filter { it.isNotEmpty() }
        .flatMap { path -> generateSequence(path) { parent -> parent.substringBeforeLast('/', "").ifEmpty { null } } }
        .distinct()
        .sortedByDescending { it.length }
        .toList()

    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
