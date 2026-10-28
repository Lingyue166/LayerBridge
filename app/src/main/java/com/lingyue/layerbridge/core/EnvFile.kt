package com.lingyue.layerbridge.core

/**
 * 前缀环境变量块的唯一来源：`env.sh`、`/system/bin` wrapper、`lb env` 的兜底
 * 都渲染同一段脚本，避免三处各写一份而逐渐分叉。
 */
object EnvFile {

    const val HEADER = "# LayerBridge environment (generated)"

    fun render(): String = buildString {
        appendLine("LB_PREFIX=${PathGuard.quote(LbPaths.PREFIX)}")
        appendLine("export LB_PREFIX")
        appendLine("export PREFIX=\"\$LB_PREFIX\"")
        appendLine("case \":\$PATH:\" in *\":\$LB_PREFIX/bin:\"*) ;; *) export PATH=\"\$LB_PREFIX/bin:\$LB_PREFIX/sbin:\$PATH\" ;; esac")
        appendLine("export LD_LIBRARY_PATH=\"\$LB_PREFIX/lib\${LD_LIBRARY_PATH:+:\$LD_LIBRARY_PATH}\"")
        appendLine("export HOME=\"\${HOME:-${LbPaths.HOME}}\"")
        appendLine("export TMPDIR=\"\$LB_PREFIX/tmp\"")
    }
}
