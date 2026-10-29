package com.lingyue.layerbridge.cli

import com.google.gson.Gson
import com.lingyue.layerbridge.core.EnvFile
import com.lingyue.layerbridge.core.LbPaths
import com.lingyue.layerbridge.core.PathGuard
import com.lingyue.layerbridge.core.Shell
import com.lingyue.layerbridge.crypto.Certificate
import com.lingyue.layerbridge.crypto.Certificates
import com.lingyue.layerbridge.crypto.PackageSigner
import com.lingyue.layerbridge.crypto.TrustStore
import com.lingyue.layerbridge.install.InstallPolicy
import com.lingyue.layerbridge.install.LocalPackage
import com.lingyue.layerbridge.install.LocalPlanner
import com.lingyue.layerbridge.deb.DebFile
import com.lingyue.layerbridge.format.LbpaFile
import com.lingyue.layerbridge.install.LinuxInstaller
import com.lingyue.layerbridge.state.InstalledPackage
import com.lingyue.layerbridge.state.LocalStateStorage
import com.lingyue.layerbridge.state.StatusDb
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.Instant
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
        "expose-root" -> exposeRoot(args.drop(1))
        "verify" -> verifyPackage(args.drop(1))
        "inspect" -> inspectPackage(args.drop(1))
        "trust" -> trust(args.drop(1))
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
              lb install [选项] <文件|目录> 安装本地 .deb / .lbpa；目录会解析依赖并排序
                  --yes / -y              目录安装时跳过确认
              lb upgrade [选项] <包文件>  升级/降级已安装包到任意版本（不要求相邻版本）
                  --allow-unsigned        允许无签名包（仍需结构校验）
                  --skip-scan             跳过维护脚本静态检测
              lb expose-root [--remove] root shell 软链（默认开启，--remove 关闭）
              lb verify <包>             校验签名（deb / LBPA）
              lb inspect <包>            查看格式、元数据、脚本、关系
              lb trust show             显示信任根
              lb trust import <root.json> 导入信任根
              lb trust remove           删除信任根
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
        println("root-links: ${rootLinkCount()} 个（lb expose-root --remove 可关闭）")
        println("module: ${File(LbPaths.MODULE_DIR).isDirectory}")
        println("apk: ${File("${LbPaths.BASE}/lb.apk").isFile}")
        println("entry: ${lsLine("/system/bin/lb")}")
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
        if (file.isDirectory) return installDirectory(file, options, requireInstalled)
        if (!file.isFile) return fail("文件不存在：${file.path}")

        return runBlocking {
            val policy = policyOf(options)
            val prepared = prepareInstaller(policy) ?: return@runBlocking 1
            installOne(prepared, file, policy, requireInstalled)
        }
    }

    /** 目录安装：解析目录内包、算依赖与冲突、排序，确认后逐个安装。 */
    private fun installDirectory(dir: File, options: InstallOptions, requireInstalled: Boolean): Int {
        if (requireInstalled) return fail("upgrade 只接受单个包文件")
        val files = dir.listFiles { f -> f.isFile && (f.name.endsWith(".deb", true) || f.name.endsWith(".lbpa", true)) }
            ?.sortedBy { it.name }
            .orEmpty()
        if (files.isEmpty()) return fail("目录内没有 .deb / .lbpa：${dir.path}")

        val packages = mutableListOf<LocalPackage>()
        for (f in files) {
            val pkg = readLocalPackage(f) ?: return fail("无法解析：${f.name}")
            packages += pkg
        }

        return runBlocking {
            val installed = StatusDb(LocalStateStorage()).load().associate { it.name to it.version }
            val plan = LocalPlanner.plan(packages, installed)
            plan.cycle?.let { return@runBlocking fail("依赖存在环：$it") }
            if (plan.missing.isNotEmpty()) {
                println("缺失依赖：")
                plan.missing.forEach { println("  $it") }
                return@runBlocking 1
            }
            if (plan.conflicts.isNotEmpty()) {
                println("存在冲突：")
                plan.conflicts.forEach { println("  $it") }
                return@runBlocking 1
            }

            println("安装计划（${plan.order.size} 个包）：")
            plan.order.forEachIndexed { index, pkg ->
                println("  ${index + 1}. ${pkg.name} ${pkg.version} (${pkg.format})")
            }
            if (!options.assumeYes && !confirm("继续安装？")) {
                println("已取消")
                return@runBlocking 1
            }

            val policy = policyOf(options)
            val prepared = prepareInstaller(policy) ?: return@runBlocking 1
            for (pkg in plan.order) {
                println("[lb] 安装 ${pkg.name} ${pkg.version}")
                val code = installOne(prepared, pkg.path, policy, requireInstalled = false)
                if (code != 0) return@runBlocking code
            }
            0
        }
    }

    private fun policyOf(options: InstallOptions): InstallPolicy =
        InstallPolicy(requireSignature = !options.allowUnsigned, malwareScan = !options.skipScan)

    private suspend fun prepareInstaller(policy: InstallPolicy): LinuxInstaller? {
        val installer = createInstaller()
        installer.bootstrap().onFailure {
            System.err.println("lb: 初始化前缀失败：${it.message}")
            return null
        }
        return installer
    }

    private suspend fun installOne(
        installer: LinuxInstaller,
        file: File,
        policy: InstallPolicy,
        requireInstalled: Boolean,
    ): Int {
        val result = when {
            file.name.endsWith(".deb", ignoreCase = true) ->
                installer.installDeb(file, LOCAL_REPO, false, policy, requireInstalled) { println(it) }
            file.name.endsWith(".lbpa", ignoreCase = true) ->
                installer.installLbpa(file, LOCAL_REPO, false, policy, requireInstalled) { println(it) }
            else -> return fail("无法识别包类型（需要 .deb 或 .lbpa）：${file.name}")
        }
        return result.fold(
            onSuccess = { println("[lb] 安装完成：${it.name} ${it.version}"); 0 },
            onFailure = { println("[lb] 安装失败：${it.message}"); 1 },
        )
    }

    private fun readLocalPackage(file: File): LocalPackage? = runCatching {
        when {
            LbpaFile.isLbpa(file) -> {
                val manifest = LbpaFile.parse(file).manifest
                val info = manifest.packageInfo ?: error("缺少 package")
                LocalPackage(
                    name = info.name ?: error("缺少包名"),
                    version = info.version ?: error("缺少版本"),
                    architecture = info.architecture ?: "aarch64",
                    format = "lbpa",
                    path = file,
                    depends = manifest.relations?.depends?.joinToString(", "),
                    preDepends = manifest.relations?.preDepends?.joinToString(", "),
                    provides = manifest.relations?.provides?.joinToString(", "),
                    conflicts = manifest.relations?.conflicts?.joinToString(", "),
                    breaks = manifest.relations?.breaks?.joinToString(", "),
                )
            }
            DebFile.isDeb(file) -> {
                val control = DebFile(file).readControl()
                LocalPackage(
                    name = control.packageName ?: error("缺少 Package"),
                    version = control.version ?: error("缺少 Version"),
                    architecture = control.architecture ?: "aarch64",
                    format = "deb",
                    path = file,
                    depends = control.depends,
                    preDepends = control.preDepends,
                    provides = control.provides,
                    conflicts = control.conflicts,
                    breaks = control.breaks,
                )
            }
            else -> null
        }
    }.getOrNull()

    private fun confirm(prompt: String): Boolean {
        print("$prompt [y/N] ")
        System.out.flush()
        val line = readlnOrNull()?.trim()?.lowercase() ?: return false
        return line == "y" || line == "yes"
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

    private fun exposeRoot(args: List<String>): Int {
        if (!isRoot()) return fail("需要 root：请使用 su 运行 lb")
        val remove = args.firstOrNull() == "--remove"
        if (args.isNotEmpty() && !remove) return usageError("用法：lb expose-root [--remove]")
        return runBlocking {
            createInstaller().setRootExposure(!remove) { println(it) }.fold(
                onSuccess = { 0 },
                onFailure = { fail("操作失败：${it.message}") },
            )
        }
    }

    private fun verifyPackage(args: List<String>): Int {
        val path = args.singleOrNull() ?: return usageError("用法：lb verify <包>")
        val file = File(path)
        if (!file.isFile) return fail("文件不存在：$path")
        val trust = TrustStore(File(LbPaths.TRUST_DIR))
        return when {
            LbpaFile.isLbpa(file) -> verifyLbpa(file, trust)
            DebFile.isDeb(file) -> verifyDeb(file, trust)
            else -> fail("无法识别包格式：${file.name}")
        }
    }

    private fun verifyDeb(file: File, trust: TrustStore): Int {
        val sidecar = File(file.parentFile, file.name + ".sig")
        if (!sidecar.isFile) {
            println("未签名：没有 ${sidecar.name}")
            return 1
        }
        val root = trust.loadRoot() ?: return fail("未配置信任根：${LbPaths.TRUST_DIR}/root.json")
        val name = DebFile(file).readControl().packageName ?: return fail("control 缺少 Package")
        val block = PackageSigner.parseBlock(sidecar.readText(Charsets.UTF_8))
        return PackageSigner.verifyDeb(block, PackageSigner.sha256File(file), root, name).fold(
            onSuccess = { println("签名有效：${it.subject}"); println("有效期：${it.notBefore} → ${it.notAfter}"); 0 },
            onFailure = { println("签名无效：${it.message}"); 1 },
        )
    }

    private fun verifyLbpa(file: File, trust: TrustStore): Int {
        val parsed = LbpaFile.parse(file)
        val signature = parsed.signatureJson
        if (signature == null) {
            println("未签名：LBPA 无签名 footer")
            return 1
        }
        val root = trust.loadRoot() ?: return fail("未配置信任根：${LbPaths.TRUST_DIR}/root.json")
        val block = PackageSigner.parseBlock(signature)
        return PackageSigner.verifyLbpa(block, parsed.manifestBytes, parsed.payloadSha256(), root, parsed.packageName).fold(
            onSuccess = { println("签名有效：${it.subject}"); println("有效期：${it.notBefore} → ${it.notAfter}"); 0 },
            onFailure = { println("签名无效：${it.message}"); 1 },
        )
    }

    private fun inspectPackage(args: List<String>): Int {
        val path = args.singleOrNull() ?: return usageError("用法：lb inspect <包>")
        val file = File(path)
        if (!file.isFile) return fail("文件不存在：$path")
        return when {
            LbpaFile.isLbpa(file) -> inspectLbpa(file)
            DebFile.isDeb(file) -> inspectDeb(file)
            else -> fail("无法识别包格式：${file.name}")
        }
    }

    private fun inspectDeb(file: File): Int {
        val control = DebFile(file).readControl()
        println("Format: deb")
        println("File: ${file.name}")
        println("Size: ${file.length()} B")
        println("Package: ${control.packageName ?: "-"}")
        println("Version: ${control.version ?: "-"}")
        println("Architecture: ${control.architecture ?: "-"}")
        control.description?.let { println("Description: $it") }
        control.depends?.let { println("Depends: $it") }
        control.provides?.let { println("Provides: $it") }
        control.conflicts?.let { println("Conflicts: $it") }
        control.breaks?.let { println("Breaks: $it") }
        println("Scripts: ${control.scripts.keys.sorted().joinToString(", ").ifEmpty { "-" }}")
        control.conffiles.takeIf { it.isNotEmpty() }?.let { println("Conffiles: ${it.joinToString(", ")}") }
        println("Signature: ${if (File(file.parentFile, file.name + ".sig").isFile) "有 .sig" else "无 .sig"}")
        return 0
    }

    private fun inspectLbpa(file: File): Int {
        val parsed = LbpaFile.parse(file)
        val manifest = parsed.manifest
        println("Format: lbpa")
        println("File: ${file.name}")
        println("Size: ${file.length()} B")
        println("Package: ${manifest.packageInfo?.name ?: "-"}")
        println("Version: ${manifest.packageInfo?.version ?: "-"}")
        println("Architecture: ${manifest.packageInfo?.architecture ?: "-"}")
        manifest.packageInfo?.description?.let { println("Description: $it") }
        println("Payload: ${manifest.payload?.compression} ${manifest.payload?.size} B sha256=${manifest.payload?.sha256}")
        manifest.relations?.let { rel ->
            listOf("depends" to rel.depends, "provides" to rel.provides, "conflicts" to rel.conflicts, "breaks" to rel.breaks)
                .forEach { (k, v) -> if (!v.isNullOrEmpty()) println("$k: ${v.joinToString(", ")}") }
        }
        manifest.scripts?.let { sc ->
            val names = listOfNotNull(sc.preinst, sc.postinst, sc.prerm, sc.postrm)
            if (names.isNotEmpty()) println("Scripts: ${names.joinToString(", ")}")
        }
        manifest.upgrade?.let { up ->
            up.keep?.takeIf { it.isNotEmpty() }?.let { println("UpgradeKeep: ${it.joinToString(", ")}") }
            up.remove?.takeIf { it.isNotEmpty() }?.let { println("UpgradeRemove: ${it.joinToString(", ")}") }
        }
        manifest.data?.takeIf { it.isNotEmpty() }?.let { println("Data: ${it.joinToString(", ")}") }
        println("Signature: ${if (parsed.signatureJson != null) "有" else "无"}")
        return 0
    }

    private fun trust(args: List<String>): Int {
        return when (args.firstOrNull()) {
            "show" -> trustShow()
            "import" -> trustImport(args.drop(1))
            "remove" -> trustRemove()
            else -> usageError("用法：lb trust show | import <root.json> | remove")
        }
    }

    private fun trustShow(): Int {
        val root = TrustStore(File(LbPaths.TRUST_DIR)).loadRoot()
        if (root == null) {
            println("未配置信任根：${LbPaths.TRUST_DIR}/root.json")
            return 1
        }
        println("Subject: ${root.subject}")
        println("KeyId: ${root.keyId}")
        println("Valid: ${root.notBefore} → ${root.notAfter}")
        println("CA: ${root.isCA}  Usages: ${root.usages.joinToString(", ")}")
        return 0
    }

    private fun trustImport(args: List<String>): Int {
        val path = args.singleOrNull() ?: return usageError("用法：lb trust import <root.json>")
        if (!isRoot()) return fail("需要 root：请使用 su 运行 lb")
        val file = File(path)
        if (!file.isFile) return fail("文件不存在：$path")
        val cert = runCatching { Gson().fromJson(file.readText(Charsets.UTF_8), Certificate::class.java) }
            .getOrElse { return fail("解析失败：${it.message}") }
        Certificates.validateRoot(cert).getOrElse { return fail("不是可用的信任根：${it.message}") }
        val result = runBlocking {
            Shell.writeFile("${LbPaths.TRUST_DIR}/root.json", Gson().toJson(cert), "600")
        }
        if (!result.isSuccess) return fail("写入失败：${result.output}")
        println("已导入信任根：${cert.subject}")
        println("KeyId: ${cert.keyId}")
        return 0
    }

    private fun trustRemove(): Int {
        if (!isRoot()) return fail("需要 root：请使用 su 运行 lb")
        val result = runBlocking { Shell.exec("rm -f ${PathGuard.quote("${LbPaths.TRUST_DIR}/root.json")}") }
        if (!result.isSuccess) return fail("删除失败：${result.output}")
        println("已删除信任根")
        return 0
    }

    private fun createInstaller(): LinuxInstaller =
        LinuxInstaller(StatusDb(LocalStateStorage()), TrustStore(File(LbPaths.TRUST_DIR)))

    private fun lsLine(path: String): String = runCatching {
        ProcessBuilder("ls", "-ld", path).start().inputStream.bufferedReader().readText().trim()
    }.getOrDefault("-")

    private fun rootLinkCount(): Int = runCatching {
        ProcessBuilder("sh", "-c", "find /data/adb/ksu/bin /data/adb/ap/bin /data/adb/magisk -maxdepth 1 -type l -lname '${LbPaths.SYSTEM_BIN}/*' 2>/dev/null | wc -l")
            .start().inputStream.bufferedReader().readText().trim().toInt()
    }.getOrDefault(0)

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
    val assumeYes: Boolean,
)

internal fun parseInstall(args: List<String>): Result<InstallOptions> {
    var allowUnsigned = false
    var skipScan = false
    var assumeYes = false
    val targets = mutableListOf<String>()
    args.forEach { arg ->
        when (arg) {
            "--allow-unsigned" -> allowUnsigned = true
            "--skip-scan" -> skipScan = true
            "--yes", "-y" -> assumeYes = true
            else -> if (arg.startsWith("-")) {
                return Result.failure(IllegalArgumentException("未知参数：$arg"))
            } else {
                targets += arg
            }
        }
    }
    if (targets.size != 1) {
        return Result.failure(IllegalArgumentException("用法：lb install [选项] <文件|目录>"))
    }
    return Result.success(InstallOptions(targets.single(), allowUnsigned, skipScan, assumeYes))
}
