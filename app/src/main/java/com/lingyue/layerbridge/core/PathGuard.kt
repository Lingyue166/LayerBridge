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

    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
