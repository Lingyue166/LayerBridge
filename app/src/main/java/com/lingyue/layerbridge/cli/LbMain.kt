package com.lingyue.layerbridge.cli

import com.lingyue.layerbridge.core.EnvFile
import com.lingyue.layerbridge.core.LbPaths
import java.io.File

/** `lb` 命令入口；由模块的 /system/bin/lb 通过 app_process 调用。 */
object LbMain {

    @JvmStatic
    fun main(args: Array<String>) {
        when (args.firstOrNull()) {
            null, "help", "--help", "-h" -> help()
            "version", "--version" -> println("lb 0.1.0")
            "env" -> env()
            "list" -> list()
            "doctor" -> doctor()
            "shell" -> shell()
            "install", "remove", "update" -> pending(args.first())
            else -> {
                System.err.println("未知命令：${args.first()}")
                help()
            }
        }
    }

    private fun help() {
        println(
            """
            lb - LayerBridge package manager

            用法：
              lb env              打印环境变量
              lb list             列出已安装包
              lb doctor           自检（root、前缀、数据库）
              lb shell            进入带环境的 shell
              lb install <包>     （待实现，使用 App 安装）
              lb remove <包>      （待实现，使用 App 卸载）
            """.trimIndent(),
        )
    }

    private fun env() {
        val file = File(LbPaths.ENV_FILE)
        if (file.isFile) print(file.readText()) else print(EnvFile.render())
    }

    private fun list() {
        val file = File("${LbPaths.DB_DIR}/installed.tsv")
        if (!file.isFile) {
            println("尚无已安装包")
            return
        }
        file.readLines().filter { it.isNotBlank() }.forEach { line ->
            val parts = line.split('\t')
            if (parts.size >= 3) {
                println("${parts[0]}\t${parts[1]}\t${parts.getOrElse(3) { "deb" }}")
            }
        }
    }

    private fun doctor() {
        val uid = runCatching {
            ProcessBuilder("id", "-u").start().inputStream.bufferedReader().readText().trim()
        }.getOrDefault("?")
        println("uid: $uid（0=root）")
        println("前缀: ${LbPaths.PREFIX} exists=${File(LbPaths.PREFIX).isDirectory}")
        println("env.sh: ${File(LbPaths.ENV_FILE).isFile}")
        println("db: ${File("${LbPaths.DB_DIR}/installed.tsv").isFile}")
        println("module: ${File(LbPaths.MODULE_DIR).isDirectory}")
        println("apk: ${File("/data/local/lb/lb.apk").isFile}")
    }

    private fun shell() {
        val shell = ProcessBuilder("sh", "-i")
            .apply {
                environment()["PREFIX"] = LbPaths.PREFIX
                environment()["PATH"] = "${LbPaths.PREFIX}/bin:${LbPaths.PREFIX}/sbin:" +
                    environment()["PATH"].orEmpty()
                environment()["LD_LIBRARY_PATH"] = "${LbPaths.PREFIX}/lib"
            }
            .inheritIO()
            .start()
        shell.waitFor()
    }

    private fun pending(command: String) {
        System.err.println("lb $command：当前版本请通过 App 操作，CLI 安装/卸载将在后续版本提供。")
    }
}
