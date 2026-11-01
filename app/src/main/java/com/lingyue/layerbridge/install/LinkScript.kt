package com.lingyue.layerbridge.install

/**
 * 一条 shell 内完成某个 root 目录的软链同步：
 * 先补建缺失命令，再删除不再提供的链接。避免每条命令一次 su。
 */
object LinkScript {

    fun build(dir: String, commands: Set<String>, wrapperRoot: String): String {
        val list = commands.joinToString(" ")
        val casePattern = if (list.isEmpty()) "' '" else "' $list '"
        return buildString {
            appendLine("cd ${q(dir)} || exit 0")
            if (list.isNotEmpty()) {
                appendLine("for c in $list; do")
                appendLine("  [ -e \"\$c\" ] || [ -L \"\$c\" ] || ln -s ${q(wrapperRoot)}/\"\$c\" \"\$c\"")
                appendLine("done")
            }
            appendLine("for p in \$(find . -maxdepth 1 -type l -lname ${q("$wrapperRoot/*")}); do")
            appendLine("  n=\${p##*/}")
            appendLine("  case $casePattern in *\" \$n \"*) ;; *) rm -f \"\$p\" ;; esac")
            appendLine("done")
            append("exit 0")
        }
    }

    private fun q(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
