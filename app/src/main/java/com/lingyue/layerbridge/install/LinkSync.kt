package com.lingyue.layerbridge.install

/** root shell 软链的差量计算：只删不再提供的命令，先建后删。 */
object LinkSync {

    fun stale(linkPaths: List<String>, commands: Set<String>): List<String> =
        linkPaths.filter { path ->
            val name = path.trim().substringAfterLast('/')
            name.isNotEmpty() && name !in commands
        }
}
