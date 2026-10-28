package com.lingyue.layerbridge.cli

import com.lingyue.layerbridge.core.EnvFile
import com.lingyue.layerbridge.core.LbPaths
import com.lingyue.layerbridge.core.PathGuard
import com.lingyue.layerbridge.crypto.TrustStore
import com.lingyue.layerbridge.install.InstallPolicy
import com.lingyue.layerbridge.install.LinuxInstaller
import com.lingyue.layerbridge.state.InstalledPackage
import com.lingyue.layerbridge.state.LocalStateStorage
import com.lingyue.layerbridge.state.StatusDb
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

/** `lb` 命令入口；由模块的 /system/bin/lb 通过 app_process 调用。 */
object LbMain {

    @JvmStatic
    fun main(args: Array<String>) {
        val code = dispatch(args.toList())
        if (code != 0) exitProcess(code)
    }

    /** 子命令分发；交互模式复用同一入口，因此不在这里退出进程。 */
    internal fun dispatch(args: List<String>): Int = when (args.firstOrNull()) {
        null, "help", "--help", "-h" -> help()
        "version", "--version" -> {
            println("lb 0.1.0")
            0
        }
        "env" -> {
            env()
            0
        }
        "list" -> list()
        "info" -> info(args.drop(1))
        "doctor" -> doctor()
        "shell" -> shell()
        "exec" -> exec(args.drop(1))
        "cli" -> commandLoop()
        "install" -> install(args.drop(1), requireInstalled = false)
        "upgrade" -> install(args.drop(1), requireInstalled = true)
        "remove", "uninstall" -> remove(args.drop(1), forceByCommand = false)
        "purge" -> remove(args.drop(1), forceByCommand = true)
        "update" -> pending(args.first())
        else -> {
            System.err.println("未知命令：${args.first()}")
            help()
            1
        }
    }

    private fun help(): Int {
        println(
            """
            lb - LayerBridge package manager

            用法：
              lb                         同 lb help
              lb cli                     进入交互模式（子命令与直接调用一致）
              lb env                     打印环境变量
              lb list                    列出已安装包
              lb info <包名>             查看已安装包的元数据与关系
              lb doctor                  自检（root、前缀、数据库、信任根）
              lb shell                   进入带环境的 shell
              lb exec <命令> [参数...]   以 root 用前缀环境执行一条命令
              lb install [选项] <包文件>  安装本地 .deb / .lbpa（输出实时流式打印）
              lb upgrade [选项] <包文件>  升级/降级已安装包到任意版本（不要求相邻版本）
                  --allow-unsigned        允许无签名包（仍需结构校验）
                  --skip-scan             跳过维护脚本静态检测
              lb remove [--force] <包名> 卸载；默认保留数据/配置
                  --force / -f            连同数据、残留一起清除（同 lb purge）
              lb purge <包名>            强制卸载（= lb remove --force）

            示例：
              su -c 'lb install /sdcard/hello.deb'
              su -c 'lb cli'
            """.trimIndent(),
        )
        return 0
    }

    private fun commandLoop(): Int {
        println("lb 交互模式：直接输入子命令（install/remove/list/info/doctor/env/shell），exit 退出")
        while (true) {
            print("lb> ")
            System.out.flush()
            val line = readlnOrNull() ?: return 0
            val tokens = tokenize(line)
            if (tokens.isEmpty()) continue
            if (tokens.first() in EXIT_COMMANDS) return 0
            dispatch(tokens)
        }
    }

    private fun env() {
        val file = File(LbPaths.ENV_FILE)
        if (file.isFile) print(file.readText()) else print(EnvFile.render())
    }

    private fun list(): Int = runBlocking {
        val packages = StatusDb(LocalStateStorage()).load()
        if (packages.isEmpty()) {
            println("尚无已安装包")
            return@runBlocking 0
        }
        packages.forEach { println("${it.name}\t${it.version}\t${it.format}") }
        0
    }

    private fun info(args: List<String>): Int {
        if (args.size != 1) return usageError("用法：lb info <包名>")
        val name = args.single()
        if (!PathGuard.isPackageName(name)) return fail("非法包名：$name")
        return runBlocking {
            val pkg = StatusDb(LocalStateStorage()).find(name) ?: return@runBlocking fail("未安装：$name")
            print(describe(pkg))
            0
        }
    }

    private fun doctor(): Int {
        println("uid: ${uid()}（0=root）")
        println("前缀: ${LbPaths.PREFIX} exists=${File(LbPaths.PREFIX).isDirectory}")
        println("env.sh: ${File(LbPaths.ENV_FILE).isFile}")
        println("db: ${File("${LbPaths.DB_DIR}/installed.tsv").isFile}")
        println("trust: ${File("${LbPaths.TRUST_DIR}/root.json").isFile}")
        println("expose: ${if (File(LbPaths.EXPOSE_FLAG).isFile) "system（命令暴露到 /system/bin，可被检测）" else "stealth（仅 root 可见）"}")
        println("前缀权限: ${lsLine(LbPaths.BASE)}")
        println("overlay: ${if (overlayMounted()) "已挂载（可被检测）" else "未挂载"}")
        println("module: ${File(LbPaths.MODULE_DIR).isDirectory}")
        println("apk: ${File("/data/local/lb/lb.apk").isFile}")
        return 0
    }

    private fun exec(args: List<String>): Int {
        if (args.isEmpty()) return usageError("用法：lb exec <命令> [参数...]")
        if (!isRoot()) return fail("需要 root：请使用 su 运行 lb")
        val body = "exec " + args.joinToString(" ") { PathGuard.quote(it) }
        val env = mapOf(
            "PREFIX" to LbPaths.PREFIX,
            "PATH" to "${LbPaths.PREFIX}/bin:${LbPaths.PREFIX}/sbin:/system/bin:/system/xbin",
            "LD_LIBRARY_PATH" to "${LbPaths.PREFIX}/lib",
            "HOME" to LbPaths.HOME,
            "TMPDIR" to "${LbPaths.PREFIX}/tmp",
        )
        return runBlocking {
            val result = com.lingyue.layerbridge.core.Shell.script(body, env, onLine = ::println)
            result.exitCode
        }
    }

    private fun shell(): Int {
        val shell = ProcessBuilder("sh", "-i")
            .apply {
                environment()["PREFIX"] = LbPaths.PREFIX
                environment()["PATH"] = "${LbPaths.PREFIX}/bin:${LbPaths.PREFIX}/sbin:" +
                    environment()["PATH"].orEmpty()
                environment()["LD_LIBRARY_PATH"] = "${LbPaths.PREFIX}/lib"
            }
            .inheritIO()
            .start()
        return shell.waitFor()
    }

    private fun install(args: List<String>, requireInstalled: Boolean): Int {
        val options = parseInstall(args).getOrElse { return usageError(it.message.orEmpty()) }
        if (!isRoot()) return fail("需要 root：请使用 su 运行 lb")
        val file = File(options.path)
        if (!file.isFile) return fail("文件不存在：${file.path}")

        val installer = createInstaller()
        return runBlocking {
            val policy = InstallPolicy(requireSignature = !options.allowUnsigned, malwareScan = !options.skipScan)
            installer.bootstrap()
                .onFailure { return@runBlocking fail("初始化前缀失败：${it.message}") }
            val result = when {
                file.name.endsWith(".deb", ignoreCase = true) ->
                    installer.installDeb(file, LOCAL_REPO, false, policy, requireInstalled) { println(it) }
                file.name.endsWith(".lbpa", ignoreCase = true) ->
                    installer.installLbpa(file, LOCAL_REPO, false, policy, requireInstalled) { println(it) }
                else -> return@runBlocking fail("无法识别包类型（需要 .deb 或 .lbpa）：${file.name}")
            }
            result.fold(
                onSuccess = { println("[lb] 安装完成：${it.name} ${it.version}"); 0 },
                onFailure = { println("[lb] 安装失败：${it.message}"); 1 },
            )
        }
    }

    private fun remove(args: List<String>, forceByCommand: Boolean): Int {
        val options = parseRemove(args).getOrElse { return usageError(it.message.orEmpty()) }
        val name = options.name
        if (!PathGuard.isPackageName(name)) return fail("非法包名：$name")
        if (!isRoot()) return fail("需要 root：请使用 su 运行 lb")
        val force = forceByCommand || options.force

        return runBlocking {
            createInstaller().uninstall(name, force) { println(it) }.fold(
                onSuccess = { println("[lb] 已卸载 $name"); 0 },
                onFailure = { println("[lb] 卸载失败：${it.message}"); 1 },
            )
        }
    }

    private fun createInstaller(): LinuxInstaller =
        LinuxInstaller(StatusDb(LocalStateStorage()), TrustStore(File(LbPaths.TRUST_DIR)))

    private fun lsLine(path: String): String = runCatching {
        ProcessBuilder("ls", "-ld", path).start().inputStream.bufferedReader().readText().trim()
    }.getOrDefault("-")

    private fun overlayMounted(): Boolean = runCatching {
        ProcessBuilder("grep", "-q", "${LbPaths.OVERLAY_UPPER}", "/proc/mounts").start().waitFor() == 0
    }.getOrDefault(false)

    private fun isRoot(): Boolean = uid() == "0"

    private fun uid(): String = runCatching {
        ProcessBuilder("id", "-u").start().inputStream.bufferedReader().readText().trim()
    }.getOrDefault("?")

    private fun usageError(message: String): Int {
        System.err.println(message)
        return 2
    }

    private fun fail(message: String): Int {
        System.err.println("lb: $message")
        return 1
    }

    private fun pending(command: String): Int {
        System.err.println("lb $command：暂未实现。")
        return 1
    }

    private val EXIT_COMMANDS = setOf("exit", "quit", ":q")
    private const val LOCAL_REPO = "local"
}

/** 交互模式的分词：空白分隔，不支持引号（路径含空格请直接调用子命令）。 */
internal fun tokenize(line: String): List<String> =
    line.trim().split(WHITESPACE).filter { it.isNotEmpty() }

private val WHITESPACE = Regex("\\s+")

/** `lb info` 的输出；保持 apt show 风格的键值行。 */
internal fun describe(pkg: InstalledPackage): String = buildString {
    appendLine("Package: ${pkg.name}")
    appendLine("Version: ${pkg.version}")
    appendLine("Architecture: ${pkg.architecture}")
    appendLine("Format: ${pkg.format}")
    appendLine("Repo: ${pkg.repo}")
    appendLine("Installed: ${pkg.installedAt}")
    appendLine("Signer: ${pkg.signer ?: "-"}")
    appendLine("Scan: ${pkg.scan}")
    appendLine("Scripts: ${pkg.scriptsStatus}")
    pkg.relations.depends?.let { appendLine("Depends: $it") }
    pkg.relations.provides?.let { appendLine("Provides: $it") }
    pkg.relations.conflicts?.let { appendLine("Conflicts: $it") }
    pkg.relations.breaks?.let { appendLine("Breaks: $it") }
    if (pkg.removeHooks.isNotEmpty()) appendLine("RemoveHooks: ${pkg.removeHooks.joinToString(", ")}")
}

/** `lb remove` 的参数解析：默认保留数据，`--force`/`-f`/`--purge` 才全清。 */
internal data class RemoveOptions(val name: String, val force: Boolean)

internal fun parseRemove(args: List<String>, forceByDefault: Boolean = false): Result<RemoveOptions> {
    var force = forceByDefault
    val targets = mutableListOf<String>()
    args.forEach { arg ->
        when (arg) {
            "--force", "-f", "--purge" -> force = true
            else -> if (arg.startsWith("-")) {
                return Result.failure(IllegalArgumentException("未知参数：$arg"))
            } else {
                targets += arg
            }
        }
    }
    if (targets.size != 1) {
        return Result.failure(IllegalArgumentException("用法：lb remove [--force] <包名>"))
    }
    return Result.success(RemoveOptions(targets.single(), force))
}

/** `lb install` 的参数解析；不合法时返回 failure，由调用方决定退出码。 */
internal data class InstallOptions(
    val path: String,
    val allowUnsigned: Boolean,
    val skipScan: Boolean,
)

internal fun parseInstall(args: List<String>): Result<InstallOptions> {
    var allowUnsigned = false
    var skipScan = false
    val targets = mutableListOf<String>()
    args.forEach { arg ->
        when (arg) {
            "--allow-unsigned" -> allowUnsigned = true
            "--skip-scan" -> skipScan = true
            else -> if (arg.startsWith("-")) {
                return Result.failure(IllegalArgumentException("未知参数：$arg"))
            } else {
                targets += arg
            }
        }
    }
    if (targets.size != 1) {
        return Result.failure(IllegalArgumentException("用法：lb install [--allow-unsigned] [--skip-scan] <包文件>"))
    }
    return Result.success(InstallOptions(targets.single(), allowUnsigned, skipScan))
}
