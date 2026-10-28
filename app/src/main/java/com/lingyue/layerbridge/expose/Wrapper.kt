package com.lingyue.layerbridge.expose

import com.lingyue.layerbridge.core.PathGuard

/** 某个包对某条命令的提供关系；target 是前缀内的相对路径。 */
data class WrapperProvider(val packageName: String, val target: String)

object Wrapper {

    private val RESERVED_ENV = setOf("PATH", "LD_LIBRARY_PATH", "PREFIX", "HOME", "TMPDIR")
    private const val OWNER_PREFIX = "# lb-owner: "
    private const val TARGET_PREFIX = "# lb-target: "

    /** 生成 /system/bin 下的转发脚本。commandPath 必须是前缀内的相对路径。 */
    fun script(
        commandPath: String,
        prefix: String = "/data/local/lb/usr",
        extraEnv: Map<String, String> = emptyMap(),
        owner: String? = null,
    ): String {
        require(PathGuard.isRelativePath(commandPath)) { "非法命令路径：$commandPath" }
        require(owner == null || PathGuard.isPackageName(owner)) { "非法包名：$owner" }
        val envLines = extraEnv
            .filterKeys { it !in RESERVED_ENV && it.matches(Regex("^[A-Za-z_][A-Za-z0-9_]*$")) }
            .map { (key, value) -> "export $key=${PathGuard.quote(value)}" }

        return buildString {
            appendLine("#!/system/bin/sh")
            if (owner != null) appendLine("$OWNER_PREFIX$owner")
            appendLine("$TARGET_PREFIX$commandPath")
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

    /** 解析 wrapper 的属主；用于诊断与冲突排查。 */
    fun ownerOf(script: String): String? = script.lineSequence()
        .firstOrNull { it.startsWith(OWNER_PREFIX) }
        ?.removePrefix(OWNER_PREFIX)
        ?.trim()
        ?.takeIf { PathGuard.isPackageName(it) }

    /**
     * 按命令名列出所有提供者：bin/ 优先于 sbin/，同类按包名排序。
     * filesByPackage 的 value 为前缀内相对路径列表。
     */
    fun providers(filesByPackage: Map<String, List<String>>, command: String): List<WrapperProvider> =
        filesByPackage.mapNotNull { (packageName, files) ->
            val target = files.firstOrNull { it == "bin/$command" }
                ?: files.firstOrNull { it == "sbin/$command" }
            target?.let { WrapperProvider(packageName, it) }
        }.sortedWith(
            compareBy({ if (it.target.startsWith("bin/")) 0 else 1 }, { it.packageName }),
        )

    fun isExposableName(name: String): Boolean =
        name.matches(Regex("^[A-Za-z0-9_+.-]{1,128}$")) && !name.contains('/')
}
