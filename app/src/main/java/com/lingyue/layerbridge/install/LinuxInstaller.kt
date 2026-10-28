package com.lingyue.layerbridge.install

import com.lingyue.layerbridge.core.EnvFile
import com.lingyue.layerbridge.core.LbPaths
import com.lingyue.layerbridge.core.PathGuard
import com.lingyue.layerbridge.core.Shell
import com.lingyue.layerbridge.crypto.PackageSigner
import com.lingyue.layerbridge.crypto.TrustStore
import com.lingyue.layerbridge.deb.DebFile
import com.lingyue.layerbridge.expose.Wrapper
import com.lingyue.layerbridge.format.LbpaFile
import com.lingyue.layerbridge.scan.PackageScanner
import com.lingyue.layerbridge.scan.Severity
import com.lingyue.layerbridge.state.InstalledPackage
import com.lingyue.layerbridge.state.PackageRelations
import com.lingyue.layerbridge.state.StatusDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

data class InstallPolicy(
    val requireSignature: Boolean = true,
    val malwareScan: Boolean = true,
)

class LinuxInstaller(
    private val statusDb: StatusDb,
    private val trustStore: TrustStore,
    private val maintainerScripts: MaintainerScripts = MaintainerScripts(),
) {

    suspend fun bootstrap(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val dirs = LbPaths.bootstrapDirs.joinToString(" ") { PathGuard.quote(it) }
            val result = Shell.exec("mkdir -p $dirs && chmod 755 ${PathGuard.quote(LbPaths.BASE)} ${PathGuard.quote(LbPaths.PREFIX)}")
            if (!result.isSuccess) error("初始化前缀失败：${result.output}")
            writeEnvFile()
        }
    }

    suspend fun installDeb(
        deb: File,
        repoId: String,
        repoVerified: Boolean,
        policy: InstallPolicy,
        onLog: (String) -> Unit,
    ): Result<InstalledPackage> = withContext(Dispatchers.IO) {
        runCatching {
            val parsed = DebFile(deb)
            val control = parsed.readControl()
            val name = control.packageName?.takeIf { PathGuard.isPackageName(it) }
                ?: error("非法或缺失包名")
            val version = control.version?.takeIf { it.isNotBlank() } ?: error("缺失版本")
            val arch = control.architecture ?: "aarch64"
            if (arch != "aarch64" && arch != "all") error("不支持的架构：$arch")

            val relations = PackageRelations(
                depends = control.depends,
                provides = control.provides,
                conflicts = control.conflicts,
                breaks = control.breaks,
            )
            val signer = verifyDebSignature(deb, name, repoId, repoVerified, policy)
            ConflictCheck.find(name, version, relations, statusDb.load())?.let { error(it.message) }
            scanScripts("$name:deb", control.scripts, policy, onLog)
            maintainerScripts.save(name, control.scripts)

            val pkg = try {
                val preinst = maintainerScripts.run(name, version, "preinst", listOf("install"), onLog)
                if (preinst != null && !preinst.isSuccess) error("preinst 失败")

                val paths = PayloadExtractor.extractDeb(parsed, onLog)
                var scriptsStatus = "ok"
                val postinst = maintainerScripts.run(name, version, "postinst", listOf("configure", version), onLog)
                if (postinst != null && !postinst.isSuccess) scriptsStatus = "postinst-failed"

                InstalledPackage(
                    name = name,
                    version = version,
                    architecture = arch,
                    format = "deb",
                    repo = repoId,
                    installedAt = Instant.now().toString(),
                    signer = signer,
                    scan = if (policy.malwareScan) "pass" else "skipped",
                    scriptsStatus = scriptsStatus,
                    relations = relations,
                ).also { finishInstall(it, paths, onLog) }
            } catch (t: Throwable) {
                maintainerScripts.clear(name)
                throw t
            }
            pkg
        }
    }

    suspend fun installLbpa(
        file: File,
        repoId: String,
        repoVerified: Boolean,
        policy: InstallPolicy,
        onLog: (String) -> Unit,
    ): Result<InstalledPackage> = withContext(Dispatchers.IO) {
        runCatching {
            val parsed = LbpaFile.parse(file)
            val info = parsed.manifest.packageInfo ?: error("缺少 package 元数据")
            val name = info.name?.takeIf { PathGuard.isPackageName(it) } ?: error("非法包名")
            val version = info.version ?: error("缺失版本")
            val arch = info.architecture ?: "aarch64"
            if (arch != "aarch64" && arch != "all") error("不支持的架构：$arch")

            val lbpaRelations = parsed.manifest.relations
            val relations = PackageRelations(
                depends = lbpaRelations?.depends.raw(),
                provides = lbpaRelations?.provides.raw(),
                conflicts = lbpaRelations?.conflicts.raw(),
                breaks = lbpaRelations?.breaks.raw(),
            )
            val removeHooks = parsed.manifest.hooks?.remove.orEmpty()

            val signer = verifyLbpaSignature(parsed, repoId, repoVerified, policy)
            ConflictCheck.find(name, version, relations, statusDb.load())?.let { error(it.message) }
            val controlScripts = PayloadExtractor.readLbpaControl(parsed)
            scanScripts("$name:lbpa", controlScripts, policy, onLog)
            maintainerScripts.save(name, controlScripts)

            val pkg = try {
                val scripts = parsed.manifest.scripts
                val preinst = scripts?.preinst?.let {
                    maintainerScripts.run(name, version, it, listOf("install"), onLog)
                }
                if (preinst != null && !preinst.isSuccess) error("preinst 失败")

                val paths = PayloadExtractor.extractLbpa(parsed, onLog)
                var scriptsStatus = "ok"
                val postinst = scripts?.postinst?.let {
                    maintainerScripts.run(name, version, it, listOf("configure", version), onLog)
                }
                if (postinst != null && !postinst.isSuccess) scriptsStatus = "postinst-failed"

                parsed.manifest.hooks?.install.orEmpty().forEach { hook ->
                    val result = maintainerScripts.run(name, version, hook, emptyList(), onLog)
                    if (result != null && !result.isSuccess) onLog("[lb] 钩子失败：$hook")
                }

                InstalledPackage(
                    name = name,
                    version = version,
                    architecture = arch,
                    format = "lbpa",
                    repo = repoId,
                    installedAt = Instant.now().toString(),
                    signer = signer,
                    scan = if (policy.malwareScan) "pass" else "skipped",
                    scriptsStatus = scriptsStatus,
                    relations = relations,
                    removeHooks = removeHooks,
                ).also { finishInstall(it, paths, onLog) }
            } catch (t: Throwable) {
                maintainerScripts.clear(name)
                throw t
            }
            pkg
        }
    }

    suspend fun uninstall(name: String, onLog: (String) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(PathGuard.isPackageName(name)) { "非法包名：$name" }
                val files = statusDb.readFileList(name)
                val pkg = statusDb.find(name)
                val version = pkg?.version ?: "unknown"

                val prerm = maintainerScripts.run(name, version, "prerm", listOf("remove"), onLog)
                if (prerm != null && !prerm.isSuccess) error("prerm 失败，已取消卸载 $name")

                pkg?.removeHooks.orEmpty().forEach { hook ->
                    val result = maintainerScripts.run(name, version, hook, emptyList(), onLog)
                    if (result != null && !result.isSuccess) onLog("[lb] 卸载钩子失败：$hook")
                }

                removeFiles(files)
                removeEmptyDirs(files)
                removeWrappers(name, files)
                statusDb.remove(name)

                val postrm = maintainerScripts.run(name, version, "postrm", listOf("remove"), onLog)
                if (postrm != null && !postrm.isSuccess) onLog("[lb] postrm 失败：$name")
                maintainerScripts.clear(name)

                writeEnvFile()
                onLog("[lb] 已卸载 $name")
            }
        }

    suspend fun refreshEnvironment(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { writeEnvFile() }
    }

    private suspend fun finishInstall(pkg: InstalledPackage, paths: List<String>, onLog: (String) -> Unit) {
        statusDb.upsert(pkg)
        statusDb.writeFileList(pkg.name, paths)
        writeEnvFile()
        writeWrappers(pkg.name, paths)
        onLog("[lb] 已安装 ${pkg.name} ${pkg.version}")
    }

    private suspend fun removeFiles(files: List<String>) {
        files.chunked(100).forEach { chunk ->
            val command = chunk.joinToString(" ") { "rm -f -- ${PathGuard.quote("${LbPaths.PREFIX}/$it")}" }
            val result = Shell.exec(command)
            if (!result.isSuccess) error("删除文件失败：${result.output}")
        }
    }

    /** 只清理被删文件的父链（先深后浅）；非空目录 rmdir 会失败，忽略即可。 */
    private suspend fun removeEmptyDirs(files: List<String>) {
        val dirs = PathGuard.ancestors(files)
        if (dirs.isEmpty()) return

        dirs.chunked(50).forEach { chunk ->
            val script = chunk.joinToString("\n") {
                "rmdir ${PathGuard.quote("${LbPaths.PREFIX}/$it")} 2>/dev/null || true"
            }
            Shell.script(script)
        }
    }

    private suspend fun writeEnvFile() {
        Shell.writeFile(LbPaths.ENV_FILE, "${EnvFile.HEADER}\n${EnvFile.render()}", "644")
    }

    private suspend fun writeWrappers(owner: String, paths: List<String>) {
        val moduleReady = Shell.exec("test -d ${PathGuard.quote(LbPaths.MODULE_DIR)}").isSuccess
        paths.asSequence()
            .filter { it.startsWith("bin/") || it.startsWith("sbin/") }
            .map { it to it.substringAfterLast('/') }
            .filter { (_, command) -> isExposableCommand(command) }
            .forEach { (path, command) ->
                val script = Wrapper.script(path, owner = owner)
                wrapperTargets(command, moduleReady).forEach { target ->
                    Shell.writeFile(target, script, "755")
                }
            }
    }

    /**
     * 卸载时不能按包名直接删 wrapper：另一个包可能提供同名命令。
     * 以状态库为准重算提供者，有接手者就改写，没有才删除。
     */
    private suspend fun removeWrappers(name: String, paths: List<String>) {
        val commands = paths.asSequence()
            .filter { it.startsWith("bin/") || it.startsWith("sbin/") }
            .map { it.substringAfterLast('/') }
            .filter { isExposableCommand(it) }
            .distinct()
            .toList()
        if (commands.isEmpty()) return

        val moduleReady = Shell.exec("test -d ${PathGuard.quote(LbPaths.MODULE_DIR)}").isSuccess
        val filesByPackage = statusDb.load()
            .filter { it.name != name }
            .associate { it.name to statusDb.readFileList(it.name) }

        commands.forEach { command ->
            val targets = wrapperTargets(command, moduleReady)
            val provider = Wrapper.providers(filesByPackage, command).firstOrNull()
            if (provider == null) {
                val remove = targets.joinToString(" ") { "rm -f -- ${PathGuard.quote(it)}" }
                Shell.exec(remove)
            } else {
                val script = Wrapper.script(provider.target, owner = provider.packageName)
                targets.forEach { target -> Shell.writeFile(target, script, "755") }
            }
        }
    }

    private fun isExposableCommand(command: String): Boolean =
        Wrapper.isExposableName(command) && command !in BLOCKED_COMMANDS

    private fun wrapperTargets(command: String, moduleReady: Boolean): List<String> = buildList {
        add("${LbPaths.SYSTEM_BIN}/$command")
        add("${LbPaths.OVERLAY_UPPER}/$command")
        if (moduleReady) add("${LbPaths.MODULE_SYSTEM_BIN}/$command")
    }

    private fun verifyDebSignature(
        deb: File,
        packageName: String,
        repoId: String,
        repoVerified: Boolean,
        policy: InstallPolicy,
    ): String? {
        if (repoVerified) return "repo:$repoId"

        val sidecar = File(deb.parentFile, deb.name + ".sig")
        if (sidecar.isFile) {
            val root = trustStore.loadRoot() ?: error("未配置信任根，无法验证包签名")
            val block = PackageSigner.parseBlock(sidecar.readText(Charsets.UTF_8))
            val hash = PackageSigner.sha256File(deb)
            return PackageSigner.verifyDeb(block, hash, root, packageName).getOrElse { error(it.message ?: "签名无效") }.subject
        }

        if (policy.requireSignature) error("未授权签名：$packageName")
        return null
    }

    private fun verifyLbpaSignature(
        parsed: LbpaFile,
        repoId: String,
        repoVerified: Boolean,
        policy: InstallPolicy,
    ): String? {
        val signatureJson = parsed.signatureJson
        if (signatureJson == null) {
            if (repoVerified) return "repo:$repoId"
            if (policy.requireSignature) error("未授权签名：${parsed.packageName}")
            return null
        }
        val root = trustStore.loadRoot() ?: error("未配置信任根，无法验证包签名")
        val block = PackageSigner.parseBlock(signatureJson)
        return PackageSigner.verifyLbpa(
            block = block,
            manifestBytes = parsed.manifestBytes,
            payloadHashHex = parsed.payloadSha256(),
            root = root,
            packageName = parsed.packageName,
        ).getOrElse { error(it.message ?: "签名无效") }.subject
    }

    private fun scanScripts(
        label: String,
        scripts: Map<String, ByteArray>,
        policy: InstallPolicy,
        onLog: (String) -> Unit,
    ) {
        if (!policy.malwareScan) return
        scripts.forEach { (name, bytes) ->
            val report = PackageScanner.scanText("$label/$name", bytes.toString(Charsets.UTF_8))
            if (report.blocked) {
                val finding = report.findings.first { it.severity == Severity.BLOCK }
                error("恶意检测拦截：${finding.rule} @ ${finding.file}:${finding.line}")
            }
            report.findings.forEach { onLog("[scan] ${it.severity} ${it.rule} @ ${it.file}:${it.line}") }
        }
    }

    private fun List<String>?.raw(): String? =
        this?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }?.joinToString(", ")

    private companion object {
        private val BLOCKED_COMMANDS = setOf(
            "sh", "bash", "su", "ls", "cat", "cp", "mv", "rm", "mount", "umount",
            "pm", "am", "id", "echo", "test", "mkdir", "chmod", "chown", "tar",
        )
    }
}
