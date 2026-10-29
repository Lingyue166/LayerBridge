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
            System.err.println(LbText.current(T.UNKNOWN_COMMAND, args.first() ?: ""))
            help()
            1
        }
    }

    private fun help(): Int {
        println(LbText.current(T.HELP))
        return 0
    }

    private fun commandLoop(): Int {
        println(LbText.current(T.INTERACTIVE_HINT))
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
            println(LbText.current(T.NO_PACKAGES))
            return@runBlocking 0
        }
        packages.forEach { println("${it.name}\t${it.version}\t${it.format}") }
        0
    }

    private fun info(args: List<String>): Int {
        if (args.size != 1) return usageError(LbText.current(T.USAGE_INFO))
        val name = args.single()
        if (!PathGuard.isPackageName(name)) return fail(LbText.current(T.ERR_BAD_NAME, name))
        return runBlocking {
            val pkg = StatusDb(LocalStateStorage()).find(name) ?: return@runBlocking fail(LbText.current(T.NOT_INSTALLED, name))
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
        println("overlay: ${if (overlayMounted()) LbText.current(T.OVERLAY_MOUNTED) else LbText.current(T.OVERLAY_NONE)}")
        println("root-links: ${rootLinkCount()} 个（lb expose-root --remove 可关闭）")
        println("module: ${File(LbPaths.MODULE_DIR).isDirectory}")
        println("apk: ${File("${LbPaths.BASE}/lb.apk").isFile}")
        println("entry: ${lsLine("/system/bin/lb")}")
        return 0
    }

    private fun exec(args: List<String>): Int {
        if (args.isEmpty()) return usageError(LbText.current(T.USAGE_GENERIC, "lb exec <command> [args...]"))
        if (!isRoot()) return fail(LbText.current(T.ERR_ROOT))
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
        if (!isRoot()) return fail(LbText.current(T.ERR_ROOT))
        val file = File(options.path)
        if (file.isDirectory) return installDirectory(file, options, requireInstalled)
        if (!file.isFile) return fail(LbText.current(T.ERR_NOT_FOUND, file.path))

        return runBlocking {
            val policy = policyOf(options)
            val prepared = prepareInstaller(policy) ?: return@runBlocking 1
            installOne(prepared, file, policy, requireInstalled)
        }
    }

    /** 目录安装：解析目录内包、算依赖与冲突、排序，确认后逐个安装。 */
    private fun installDirectory(dir: File, options: InstallOptions, requireInstalled: Boolean): Int {
        if (requireInstalled) return fail(LbText.current(T.UPGRADE_SINGLE))
        val files = dir.listFiles { f -> f.isFile && (f.name.endsWith(".deb", true) || f.name.endsWith(".lbpa", true)) }
            ?.sortedBy { it.name }
            .orEmpty()
        if (files.isEmpty()) return fail(LbText.current(T.DIR_EMPTY, dir.path))

        val packages = mutableListOf<LocalPackage>()
        for (f in files) {
            val pkg = readLocalPackage(f) ?: return fail(LbText.current(T.ERR_PARSE, f.name))
            packages += pkg
        }

        return runBlocking {
            val installed = StatusDb(LocalStateStorage()).load().associate { it.name to it.version }
            val plan = LocalPlanner.plan(packages, installed)
            plan.cycle?.let { return@runBlocking fail(LbText.current(T.CYCLE, it)) }
            if (plan.missing.isNotEmpty()) {
                println(LbText.current(T.MISSING_HEADER))
                plan.missing.forEach { println("  $it") }
                return@runBlocking 1
            }
            if (plan.conflicts.isNotEmpty()) {
                println(LbText.current(T.CONFLICT_HEADER))
                plan.conflicts.forEach { println("  $it") }
                return@runBlocking 1
            }

            println(LbText.current(T.PLAN_HEADER, plan.order.size))
            plan.order.forEachIndexed { index, pkg ->
                println(LbText.current(T.PLAN_ITEM, index + 1, pkg.name, pkg.version, pkg.format))
            }
            if (!options.assumeYes && !confirm("继续安装？")) {
                println(LbText.current(T.CANCELLED))
                return@runBlocking 1
            }

            val policy = policyOf(options)
            val prepared = prepareInstaller(policy) ?: return@runBlocking 1
            for (pkg in plan.order) {
                val existing = installed[pkg.name]
                if (existing != null) {
                    println("[lb] 跳过已安装：${pkg.name} ${existing}（升级用 lb upgrade）")
                    continue
                }
                println(LbText.current(T.INSTALLING, pkg.name, pkg.version))
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
            System.err.println("lb: " + LbText.current(T.ERR_PREFIX_INIT, it.message.orEmpty()))
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
            else -> return fail(LbText.current(T.ERR_UNKNOWN_TYPE, file.name))
        }
        return result.fold(
            onSuccess = { println(LbText.current(T.INSTALL_DONE, it.name, it.version)); 0 },
            onFailure = { println(LbText.current(T.INSTALL_FAILED_ITEM, it.message.orEmpty())); 1 },
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
        if (!PathGuard.isPackageName(name)) return fail(LbText.current(T.ERR_BAD_NAME, name))
        if (!isRoot()) return fail(LbText.current(T.ERR_ROOT))
        val force = forceByCommand || options.force

        return runBlocking {
            createInstaller().uninstall(name, force) { println(it) }.fold(
                onSuccess = { println(LbText.current(T.REMOVED, name)); 0 },
                onFailure = { println(LbText.current(T.ERR_UNINSTALL_FAILED, it.message.orEmpty())); 1 },
            )
        }
    }

    private fun exposeRoot(args: List<String>): Int {
        if (!isRoot()) return fail(LbText.current(T.ERR_ROOT))
        val remove = args.firstOrNull() == "--remove"
        if (args.isNotEmpty() && !remove) return usageError(LbText.current(T.USAGE_EXPOSE))
        return runBlocking {
            createInstaller().setRootExposure(!remove) { println(it) }.fold(
                onSuccess = { 0 },
                onFailure = { fail(LbText.current(T.OPERATION_FAILED, it.message.orEmpty())) },
            )
        }
    }

    private fun verifyPackage(args: List<String>): Int {
        val path = args.singleOrNull() ?: return usageError(LbText.current(T.USAGE_VERIFY))
        val file = File(path)
        if (!file.isFile) return fail(LbText.current(T.ERR_NOT_FOUND, path))
        val trust = TrustStore(File(LbPaths.TRUST_DIR))
        return when {
            LbpaFile.isLbpa(file) -> verifyLbpa(file, trust)
            DebFile.isDeb(file) -> verifyDeb(file, trust)
            else -> fail(LbText.current(T.ERR_UNKNOWN_TYPE, file.name))
        }
    }

    private fun verifyDeb(file: File, trust: TrustStore): Int {
        val sidecar = File(file.parentFile, file.name + ".sig")
        if (!sidecar.isFile) {
            println(LbText.current(T.SIGN_UNSIGNED_DEB, sidecar.name))
            return 1
        }
        val root = trust.loadRoot() ?: return fail(LbText.current(T.ERR_NO_TRUST_ROOT, "${LbPaths.TRUST_DIR}/root.json"))
        val name = DebFile(file).readControl().packageName ?: return fail(LbText.current(T.CONTROL_MISSING))
        val block = PackageSigner.parseBlock(sidecar.readText(Charsets.UTF_8))
        return PackageSigner.verifyDeb(block, PackageSigner.sha256File(file), root, name).fold(
            onSuccess = { println(LbText.current(T.SIGN_VALID, it.subject)); println(LbText.current(T.SIGN_VALIDITY, it.notBefore, it.notAfter)); 0 },
            onFailure = { println(LbText.current(T.SIGN_INVALID, it.message.orEmpty())); 1 },
        )
    }

    private fun verifyLbpa(file: File, trust: TrustStore): Int {
        val parsed = LbpaFile.parse(file)
        val signature = parsed.signatureJson
        if (signature == null) {
            println(LbText.current(T.SIGN_UNSIGNED_LBPA))
            return 1
        }
        val root = trust.loadRoot() ?: return fail(LbText.current(T.ERR_NO_TRUST_ROOT, "${LbPaths.TRUST_DIR}/root.json"))
        val block = PackageSigner.parseBlock(signature)
        return PackageSigner.verifyLbpa(block, parsed.manifestBytes, parsed.payloadSha256(), root, parsed.packageName).fold(
            onSuccess = { println(LbText.current(T.SIGN_VALID, it.subject)); println(LbText.current(T.SIGN_VALIDITY, it.notBefore, it.notAfter)); 0 },
            onFailure = { println(LbText.current(T.SIGN_INVALID, it.message.orEmpty())); 1 },
        )
    }

    private fun inspectPackage(args: List<String>): Int {
        val path = args.singleOrNull() ?: return usageError(LbText.current(T.USAGE_INSPECT))
        val file = File(path)
        if (!file.isFile) return fail(LbText.current(T.ERR_NOT_FOUND, path))
        return when {
            LbpaFile.isLbpa(file) -> inspectLbpa(file)
            DebFile.isDeb(file) -> inspectDeb(file)
            else -> fail(LbText.current(T.ERR_UNKNOWN_TYPE, file.name))
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
        println("Signature: ${if (File(file.parentFile, file.name + ".sig").isFile) LbText.current(T.SIGNED_YES) + " .sig" else LbText.current(T.SIGNED_NO) + " .sig"}")
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
        println("Signature: ${if (parsed.signatureJson != null) LbText.current(T.SIGNED_YES) else LbText.current(T.SIGNED_NO)}")
        return 0
    }

    private fun trust(args: List<String>): Int {
        return when (args.firstOrNull()) {
            "show" -> trustShow()
            "import" -> trustImport(args.drop(1))
            "remove" -> trustRemove()
            else -> usageError(LbText.current(T.USAGE_TRUST))
        }
    }

    private fun trustShow(): Int {
        val root = TrustStore(File(LbPaths.TRUST_DIR)).loadRoot()
        if (root == null) {
            println(LbText.current(T.TRUST_EMPTY, "${LbPaths.TRUST_DIR}/root.json"))
            return 1
        }
        println(LbText.current(T.TRUST_SUBJECT, root.subject))
        println(LbText.current(T.TRUST_KEYID, root.keyId))
        println(LbText.current(T.TRUST_VALID, root.notBefore, root.notAfter))
        println(LbText.current(T.TRUST_CA, root.isCA.toString(), root.usages.joinToString(", ")))
        return 0
    }

    private fun trustImport(args: List<String>): Int {
        val path = args.singleOrNull() ?: return usageError(LbText.current(T.USAGE_TRUST_IMPORT))
        if (!isRoot()) return fail(LbText.current(T.ERR_ROOT))
        val file = File(path)
        if (!file.isFile) return fail(LbText.current(T.ERR_NOT_FOUND, path))
        val cert = runCatching { Gson().fromJson(file.readText(Charsets.UTF_8), Certificate::class.java) }
            .getOrElse { return fail(LbText.current(T.ERR_PARSE, it.message.orEmpty())) }
        Certificates.validateRoot(cert).getOrElse { return fail(LbText.current(T.ERR_TRUST_INVALID, it.message.orEmpty())) }
        val result = runBlocking {
            Shell.writeFile("${LbPaths.TRUST_DIR}/root.json", Gson().toJson(cert), "600")
        }
        if (!result.isSuccess) return fail(LbText.current(T.ERR_TRUST_WRITE, result.output))
        println(LbText.current(T.TRUST_IMPORTED, cert.subject))
        println(LbText.current(T.TRUST_KEYID, cert.keyId))
        return 0
    }

    private fun trustRemove(): Int {
        if (!isRoot()) return fail(LbText.current(T.ERR_ROOT))
        val result = runBlocking { Shell.exec("rm -f ${PathGuard.quote("${LbPaths.TRUST_DIR}/root.json")}") }
        if (!result.isSuccess) return fail(LbText.current(T.ERR_TRUST_DELETE, result.output))
        println(LbText.current(T.TRUST_DELETED))
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
        System.err.println(LbText.current(T.PENDING, command))
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
                return Result.failure(IllegalArgumentException(LbText.current(T.UNKNOWN_ARG, arg)))
            } else {
                targets += arg
            }
        }
    }
    if (targets.size != 1) {
        return Result.failure(IllegalArgumentException(LbText.current(T.USAGE_REMOVE)))
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
                return Result.failure(IllegalArgumentException(LbText.current(T.UNKNOWN_ARG, arg)))
            } else {
                targets += arg
            }
        }
    }
    if (targets.size != 1) {
        return Result.failure(IllegalArgumentException(LbText.current(T.USAGE_INSTALL)))
    }
    return Result.success(InstallOptions(targets.single(), allowUnsigned, skipScan, assumeYes))
}
