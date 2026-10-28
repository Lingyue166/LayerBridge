package com.lingyue.layerbridge.expose

import com.lingyue.layerbridge.core.PathGuard

object Wrapper {

    private val RESERVED_ENV = setOf("PATH", "LD_LIBRARY_PATH", "PREFIX", "HOME", "TMPDIR")

    /** 生成 /system/bin 下的转发脚本。commandPath 必须是前缀内的相对路径。 */
    fun script(
        commandPath: String,
        prefix: String = "/data/local/lb/usr",
        extraEnv: Map<String, String> = emptyMap(),
    ): String {
        require(PathGuard.isRelativePath(commandPath)) { "非法命令路径：$commandPath" }
        val envLines = extraEnv
            .filterKeys { it !in RESERVED_ENV && it.matches(Regex("^[A-Za-z_][A-Za-z0-9_]*$")) }
            .map { (key, value) -> "export $key=${PathGuard.quote(value)}" }

        return buildString {
            appendLine("#!/system/bin/sh")
            appendLine("LB_PREFIX=${PathGuard.quote(prefix)}")
            appendLine("export LB_PREFIX")
            appendLine("export PREFIX=\"\$LB_PREFIX\"")
            appendLine("case \":\$PATH:\" in *\":\$LB_PREFIX/bin:\"*) ;; *) export PATH=\"\$LB_PREFIX/bin:\$PATH\" ;; esac")
            appendLine("export LD_LIBRARY_PATH=\"\$LB_PREFIX/lib\${LD_LIBRARY_PATH:+:\$LD_LIBRARY_PATH}\"")
            appendLine("export HOME=\"\${HOME:-/data/local/lb/home}\"")
            appendLine("export TMPDIR=\"\$LB_PREFIX/tmp\"")
            envLines.forEach { appendLine(it) }
            appendLine("exec \"\$LB_PREFIX/${commandPath}\" \"\$@\"")
        }
    }

    fun isExposableName(name: String): Boolean =
        name.matches(Regex("^[A-Za-z0-9_+.-]{1,128}$")) && !name.contains('/')
}
