package com.lingyue.layerbridge.core

/**
 * 前缀环境变量块的唯一来源：`env.sh`、`/system/bin` wrapper、`lb env` 的兜底
 * 都渲染同一段脚本，避免三处各写一份而逐渐分叉。
 */
object EnvFile {

    const val HEADER = "# LayerBridge environment (generated)"

    /** 单个包的环境文件内容：每行 KEY=VALUE 解析时按首个等号切分。 */
    fun renderPackageEnv(env: Map<String, String>): String = buildString {
        env.toSortedMap().forEach { (key, value) -> appendLine("$key=$value") }
    }

    /** 解析包环境文件 兼容旧版本缺文件或格式异常。 */
    fun parsePackageEnv(text: String): Map<String, String> = text.lineSequence()
        .mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
            val index = trimmed.indexOf('=')
            if (index <= 0) return@mapNotNull null
            trimmed.substring(0, index) to trimmed.substring(index + 1)
        }
        .toMap()

    fun render(packageEnv: Map<String, Map<String, String>> = emptyMap()): String = buildString {
        appendLine("LB_PREFIX=${PathGuard.quote(LbPaths.PREFIX)}")
        appendLine("export LB_PREFIX")
        appendLine("export PREFIX=\"\$LB_PREFIX\"")
        appendLine("case \":\$PATH:\" in *\":\$LB_PREFIX/bin:\"*) ;; *) export PATH=\"\$LB_PREFIX/bin:\$LB_PREFIX/sbin:\$PATH\" ;; esac")
        appendLine("export LD_LIBRARY_PATH=\"\$LB_PREFIX/lib\${LD_LIBRARY_PATH:+:\$LD_LIBRARY_PATH}\"")
        // root shell 的 HOME 常常是 "/"（不可写），这种情况下回落到前缀 home
        appendLine("if [ -z \"\${HOME:-}\" ] || [ \"\$HOME\" = \"/\" ]; then export HOME=${PathGuard.quote(LbPaths.HOME)}; fi")
        appendLine("export TMPDIR=\"\$LB_PREFIX/tmp\"")
        // 兼容包自带的 profile.d 脚本
        appendLine("if [ -d \"\$LB_PREFIX/etc/profile.d\" ]; then for f in \"\$LB_PREFIX/etc/profile.d\"/*.sh; do [ -r \"\$f\" ] && . \"\$f\"; done; fi")
        packageEnv.toSortedMap().forEach { (name, env) ->
            if (env.isEmpty()) return@forEach
            appendLine("# package: $name")
            env.toSortedMap().forEach { (key, value) ->
                appendLine("export $key=${PathGuard.quote(value)}")
            }
        }
    }
}
