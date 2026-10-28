package com.lingyue.layerbridge.install

import com.lingyue.layerbridge.core.ExecResult
import com.lingyue.layerbridge.core.LbPaths
import com.lingyue.layerbridge.core.PathGuard
import com.lingyue.layerbridge.core.Shell

/**
 * 维护脚本（preinst/postinst/prerm/postrm 与 LBPA 钩子）的持久化与执行。
 * 安装时先落盘再执行，卸载时从同一位置读回，两阶段看到的是同一份脚本。
 */
class MaintainerScripts(private val rootDir: String = DB_SCRIPTS) {

    /** 保存包内全部 control 脚本；名称不合规的直接视为坏包（失败即停）。 */
    suspend fun save(packageName: String, scripts: Map<String, ByteArray>) {
        require(PathGuard.isPackageName(packageName)) { "非法包名：$packageName" }
        scripts.forEach { (rawName, bytes) ->
            val name = scriptName(rawName)
            val result = Shell.writeFile(scriptPath(packageName, name), bytes.toString(Charsets.UTF_8), "644")
            if (!result.isSuccess) error("维护脚本写入失败：$name：${result.output}")
        }
    }

    /** 脚本不存在时返回 null（未声明该脚本是正常情况）。 */
    suspend fun run(
        packageName: String,
        version: String,
        script: String,
        args: List<String>,
        onLog: (String) -> Unit,
    ): ExecResult? {
        val path = scriptPath(packageName, scriptName(script))
        if (!Shell.exec("test -f ${PathGuard.quote(path)}").isSuccess) return null

        val body = buildString {
            if (args.isNotEmpty()) appendLine("set -- " + args.joinToString(" ") { PathGuard.quote(it) })
            appendLine("exec sh ${PathGuard.quote(path)} \"\$@\"")
        }
        val result = Shell.script(body, env(packageName, version))
        if (result.output.isNotBlank()) onLog(result.output)
        return result
    }

    suspend fun clear(packageName: String) {
        Shell.exec("rm -rf ${PathGuard.quote("$rootDir/$packageName")}")
    }

    private fun env(packageName: String, version: String): Map<String, String> = mapOf(
        "PREFIX" to LbPaths.PREFIX,
        "PATH" to "${LbPaths.PREFIX}/bin:/system/bin:/system/xbin",
        "LD_LIBRARY_PATH" to "${LbPaths.PREFIX}/lib",
        "HOME" to LbPaths.HOME,
        "TMPDIR" to "$LbPaths.PREFIX/tmp",
        "DEBIAN_FRONTEND" to "noninteractive",
        "DPKG_MAINTSCRIPT_PACKAGE" to packageName,
        "DPKG_MAINTSCRIPT_VERSION" to version,
    )

    private fun scriptPath(packageName: String, script: String): String =
        "$rootDir/$packageName/$script"

    /** LBPA 的脚本键形如 `control/preinst`，统一成裸文件名。 */
    internal fun scriptName(raw: String): String {
        val name = raw.removePrefix("./").removePrefix("control/")
        require(SCRIPT_NAME.matches(name)) { "非法维护脚本名：$raw" }
        return name
    }

    private companion object {
        const val DB_SCRIPTS = LbPaths.DB_DIR + "/scripts"
        val SCRIPT_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
    }
}
