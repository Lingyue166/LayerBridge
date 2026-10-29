package com.lingyue.layerbridge.install

import com.lingyue.layerbridge.core.PathGuard

/**
 * deb / LBPA 载荷路径到前缀内相对路径的映射。
 * Termux 的包把文件放在 data/data/com.termux/files/usr/ 下，需要剥掉这段。
 */
object PayloadPath {

    const val TERMUX_PREFIX = "data/data/com.termux/files/usr"

    fun normalize(raw: String): String? {
        var name = raw.removePrefix("./")
        if (name.endsWith('/')) name = name.dropLast(1)
        if (name.isEmpty() || name == ".") return null
        if (name == TERMUX_PREFIX) return null
        if (name.startsWith("$TERMUX_PREFIX/")) name = name.removePrefix("$TERMUX_PREFIX/")
        if (!PathGuard.isRelativePath(name)) error("非法包路径：$raw")
        return name
    }

    /** 绝对链接目标若指向 Termux 前缀，改指当前前缀。 */
    fun mapLink(target: String, prefix: String): String =
        if (target.startsWith("/$TERMUX_PREFIX/")) prefix + target.removePrefix("/$TERMUX_PREFIX")
        else target
}
