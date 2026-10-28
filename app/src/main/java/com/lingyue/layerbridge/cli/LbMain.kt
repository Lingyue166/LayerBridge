package com.lingyue.layerbridge.cli

import com.lingyue.layerbridge.core.EnvFile
import com.lingyue.layerbridge.core.LbPaths
import com.lingyue.layerbridge.core.PathGuard
import com.lingyue.layerbridge.crypto.TrustStore
import com.lingyue.layerbridge.install.InstallPolicy
import com.lingyue.layerbridge.install.LinuxInstaller
import com.lingyue.layerbridge.state.LocalStateStorage
import com.lingyue.layerbridge.state.StatusDb
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

/** `lb` 命令入口；由模块的 /system/bin/lb 通过 app_process 调用。 */
object LbMain {

    @JvmStatic
    fun main(args: Array<String>) {
        val code = when (args.firstOrNull()) {
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
            "doctor" -> doctor()
            "shell" -> shell()
            "install" -> install(args.drop(1))
            "remove" -> remove(args.drop(1))
            "update", "upgrade" -> pending(args.first())
            else -> {
                System.err.println("未知命令：${args.first()}")
                help()
                1
            }
        }
        if (code != 0) exitProcess(code)
    }

    private fun help(): Int {
        println(
            """
            lb - LayerBridge package manager

            用法：
              lb env                            打印环境变量
              lb list                           列出已安装包
              lb doctor                         自检（root、前缀、数据库、信任根）
              lb shell                          进入带环境的 shell
              lb install [选项] <包文件>         安装本地 .deb / .lbpa
                  --allow-unsigned              允许无签名包（仍需结构校验）
                  --skip-scan                   跳过维护脚本静态检测
              lb remove <包名>                  卸载已安装包
            """.trimIndent(),
        )
        return 0
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

    private fun doctor(): Int {
        println("uid: ${uid()}（0=root）")
        println("前缀: ${LbPaths.PREFIX} exists=${File(LbPaths.PREFIX).isDirectory}")
        println("env.sh: ${File(LbPaths.ENV_FILE).isFile}")
        println("db: ${File("${LbPaths.DB_DIR}/installed.tsv").isFile}")
        println("trust: ${File("${LbPaths.TRUST_DIR}/root.json").isFile}")
        println("module: ${File(LbPaths.MODULE_DIR).isDirectory}")
        println("apk: ${File("/data/local/lb/lb.apk").isFile}")
        return 0
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

    private fun install(args: List<String>): Int {
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
                    installer.installDeb(file, LOCAL_REPO, false, policy) { println(it) }
                file.name.endsWith(".lbpa", ignoreCase = true) ->
                    installer.installLbpa(file, LOCAL_REPO, false, policy) { println(it) }
                else -> return@runBlocking fail("无法识别包类型（需要 .deb 或 .lbpa）：${file.name}")
            }
            result.fold(
                onSuccess = { println("[lb] 安装完成：${it.name} ${it.version}"); 0 },
                onFailure = { println("[lb] 安装失败：${it.message}"); 1 },
            )
        }
    }

    private fun remove(args: List<String>): Int {
        if (args.size != 1) return usageError("用法：lb remove <包名>")
        val name = args.single()
        if (!PathGuard.isPackageName(name)) return fail("非法包名：$name")
        if (!isRoot()) return fail("需要 root：请使用 su 运行 lb")

        return runBlocking {
            createInstaller().uninstall(name) { println(it) }.fold(
                onSuccess = { println("[lb] 已卸载 $name"); 0 },
                onFailure = { println("[lb] 卸载失败：${it.message}"); 1 },
            )
        }
    }

    private fun createInstaller(): LinuxInstaller =
        LinuxInstaller(StatusDb(LocalStateStorage()), TrustStore(File(LbPaths.TRUST_DIR)))

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

    private const val LOCAL_REPO = "local"
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
