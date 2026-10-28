package com.lingyue.layerbridge.install

import com.lingyue.layerbridge.core.ExecResult
import com.lingyue.layerbridge.core.LbPaths
import com.lingyue.layerbridge.core.PathGuard
import com.lingyue.layerbridge.core.Shell
import com.lingyue.layerbridge.crypto.Certificate
import com.lingyue.layerbridge.crypto.PackageSigner
import com.lingyue.layerbridge.crypto.TrustStore
import com.lingyue.layerbridge.deb.DebFile
import com.lingyue.layerbridge.expose.Wrapper
import com.lingyue.layerbridge.format.LbpaFile
import com.lingyue.layerbridge.scan.PackageScanner
import com.lingyue.layerbridge.state.InstalledPackage
import com.lingyue.layerbridge.state.StatusDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.zip.GZIPInputStream
import org.tukaani.xz.XZInputStream

data class InstallPolicy(
    val requireSignature: Boolean = true,
    val malwareScan: Boolean = true,
)

class LinuxInstaller(
    private val statusDb: StatusDb,
    private val trustStore: TrustStore,
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

            val signer = verifyDebSignature(deb, name, repoId, repoVerified, policy)
            scanScripts("$name:deb", control.scripts, policy, onLog)

            control.scripts["preinst"]?.let {
                val result = runScript(it, listOf("install"), name, version, onLog)
                if (!result.isSuccess) error("preinst 失败")
            }

            val paths = extractDeb(parsed, onLog)
            var scriptsStatus = "ok"
            control.scripts["postinst"]?.let {
                val result = runScript(it, listOf("configure", version), name, version, onLog)
                if (!result.isSuccess) scriptsStatus = "postinst-failed"
            }

            val pkg = InstalledPackage(
                name = name,
                version = version,
                architecture = arch,
                format = "deb",
                repo = repoId,
                installedAt = Instant.now().toString(),
                signer = signer,
                scan = if (policy.malwareScan) "pass" else "skipped",
                scriptsStatus = scriptsStatus,
            )
            finishInstall(pkg, paths, onLog)
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

            val signer = verifyLbpaSignature(parsed, repoId, repoVerified, policy)
            val controlScripts = readLbpaControl(file)
            scanScripts("$name:lbpa", controlScripts, policy, onLog)

            val scripts = parsed.manifest.scripts
            val preinst = scripts?.preinst?.let { controlScripts[it] }
            if (preinst != null) {
                val result = runScript(preinst, listOf("install"), name, version, onLog)
                if (!result.isSuccess) error("preinst 失败")
            }

            val paths = extractLbpa(parsed, onLog)
            var scriptsStatus = "ok"
            val postinst = scripts?.postinst?.let { controlScripts[it] }
            if (postinst != null) {
                val result = runScript(postinst, listOf("configure", version), name, version, onLog)
                if (!result.isSuccess) scriptsStatus = "postinst-failed"
            }

            parsed.manifest.hooks?.install.orEmpty().forEach { hook ->
                val body = controlScripts[hook] ?: return@forEach
                val result = runScript(body, emptyList(), name, version, onLog)
                if (!result.isSuccess) onLog("[lb] 钩子失败：$hook")
            }

            val pkg = InstalledPackage(
                name = name,
                version = version,
                architecture = arch,
                format = "lbpa",
                repo = repoId,
                installedAt = Instant.now().toString(),
                signer = signer,
                scan = if (policy.malwareScan) "pass" else "skipped",
                scriptsStatus = scriptsStatus,
            )
            finishInstall(pkg, paths, onLog)
            pkg
        }
    }

    suspend fun uninstall(name: String, onLog: (String) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val files = statusDb.readFileList(name)
                if (files.isNotEmpty()) {
                    files.chunked(100).forEach { chunk ->
                        val command = chunk.joinToString(" ") { "rm -f -- ${PathGuard.quote("${LbPaths.PREFIX}/$it")}" }
                        val result = Shell.exec(command)
                        if (!result.isSuccess) onLog(result.output)
                    }
                }
                removeWrappers(name, files)
                statusDb.remove(name)
                syncPrefixDb()
                writeEnvFile()
                onLog("[lb] 已卸载 $name")
            }
        }

    suspend fun refreshEnvironment(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            writeEnvFile()
            syncPrefixDb()
        }
    }

    private suspend fun finishInstall(pkg: InstalledPackage, paths: List<String>, onLog: (String) -> Unit) {
        statusDb.upsert(pkg)
        statusDb.writeFileList(pkg.name, paths)
        syncPrefixDb()
        writeEnvFile()
        writeWrappers(paths)
        onLog("[lb] 已安装 ${pkg.name} ${pkg.version}")
    }

    private suspend fun syncPrefixDb() {
        val text = statusDb.serialize(statusDb.load())
        Shell.writeFile("${LbPaths.DB_DIR}/installed.tsv", text, "644")
        statusDb.load().forEach { pkg ->
            val list = statusDb.readFileList(pkg.name).joinToString("\n", postfix = "\n")
            Shell.writeFile("${LbPaths.DB_DIR}/info/${pkg.name}.list", list, "644")
        }
    }

    private suspend fun writeEnvFile() {
        val script = buildString {
            appendLine("# LayerBridge environment (generated)")
            appendLine("LB_PREFIX=${PathGuard.quote(LbPaths.PREFIX)}")
            appendLine("export LB_PREFIX")
            appendLine("export PREFIX=\"\$LB_PREFIX\"")
            appendLine("case \":\$PATH:\" in *\":\$LB_PREFIX/bin:\"*) ;; *) export PATH=\"\$LB_PREFIX/bin:\$LB_PREFIX/sbin:\$PATH\" ;; esac")
            appendLine("export LD_LIBRARY_PATH=\"\$LB_PREFIX/lib\${LD_LIBRARY_PATH:+:\$LD_LIBRARY_PATH}\"")
            appendLine("export HOME=\"\${HOME:-${LbPaths.HOME}}\"")
            appendLine("export TMPDIR=\"\$LB_PREFIX/tmp\"")
        }
        Shell.writeFile(LbPaths.ENV_FILE, script, "644")
    }

    private suspend fun writeWrappers(paths: List<String>) {
        val moduleReady = Shell.exec("test -d ${PathGuard.quote(LbPaths.MODULE_DIR)}").isSuccess
        paths.asSequence()
            .filter { it.startsWith("bin/") || it.startsWith("sbin/") }
            .map { it to it.substringAfterLast('/') }
            .filter { (_, command) -> Wrapper.isExposableName(command) && command !in BLOCKED_COMMANDS }
            .forEach { (path, command) ->
                val script = Wrapper.script(path)
                val targets = mutableListOf(
                    "${LbPaths.SYSTEM_BIN}/$command",
                    "${LbPaths.OVERLAY_UPPER}/$command",
                )
                if (moduleReady) targets += "${LbPaths.MODULE_SYSTEM_BIN}/$command"
                targets.forEach { target ->
                    Shell.writeFile(target, script, "755")
                }
            }
    }

    private suspend fun removeWrappers(name: String, paths: List<String>) {
        paths.asSequence()
            .filter { it.startsWith("bin/") || it.startsWith("sbin/") }
            .map { it.substringAfterLast('/') }
            .filter { Wrapper.isExposableName(it) }
            .forEach { command ->
                val target = listOf(
                    "${LbPaths.SYSTEM_BIN}/$command",
                    "${LbPaths.OVERLAY_UPPER}/$command",
                    "${LbPaths.MODULE_SYSTEM_BIN}/$command",
                ).joinToString(" ") { "rm -f -- ${PathGuard.quote(it)}" }
                Shell.exec(target)
            }
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
                val finding = report.findings.first { it.severity == com.lingyue.layerbridge.scan.Severity.BLOCK }
                error("恶意检测拦截：${finding.rule} @ ${finding.file}:${finding.line}")
            }
            report.findings.forEach { onLog("[scan] ${it.severity} ${it.rule} @ ${it.file}:${it.line}") }
        }
    }

    private suspend fun extractDeb(parsed: DebFile, onLog: (String) -> Unit): List<String> {
        val paths = mutableListOf<String>()
        val result = Shell.extractTar(LbPaths.PREFIX) { output ->
            TarArchiveOutputStream(output).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                parsed.dataTarInputStream().use { input ->
                    var entry = input.nextEntry
                    while (entry != null) {
                        val path = normalize(entry.name)
                        if (path != null) {
                            copyEntry(tar, input, entry, path, paths)
                        }
                        entry = input.nextEntry
                    }
                }
            }
        }
        if (!result.isSuccess) error("解包失败：${result.output}")
        onLog("[lb] 解包完成：${paths.size} 个文件")
        return paths
    }

    private suspend fun extractLbpa(parsed: LbpaFile, onLog: (String) -> Unit): List<String> {
        val paths = mutableListOf<String>()
        val result = Shell.extractTar(LbPaths.PREFIX) { output ->
            TarArchiveOutputStream(output).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                payloadTar(parsed).use { input ->
                    var entry = input.nextEntry
                    while (entry != null) {
                        val raw = entry.name.removePrefix("./")
                        when {
                            raw.startsWith("control/") || raw == "control" -> Unit
                            raw.startsWith("files/") -> {
                                val path = normalize(raw.removePrefix("files/"))
                                if (path != null) copyEntry(tar, input, entry, path, paths)
                            }
                            else -> error("未知 payload 路径：$raw")
                        }
                        entry = input.nextEntry
                    }
                }
            }
        }
        if (!result.isSuccess) error("解包失败：${result.output}")
        onLog("[lb] 解包完成：${paths.size} 个文件")
        return paths
    }

    private fun copyEntry(
        tar: TarArchiveOutputStream,
        input: TarArchiveInputStream,
        entry: TarArchiveEntry,
        path: String,
        paths: MutableList<String>,
    ) {
        val mode = entry.mode and 0x1FF
        when {
            entry.isDirectory -> {
                val out = TarArchiveEntry("$path/")
                out.mode = mode
                out.userId = 0
                out.groupId = 0
                tar.putArchiveEntry(out)
                tar.closeArchiveEntry()
            }
            entry.isSymbolicLink -> {
                val out = TarArchiveEntry(path, TarConstants.LF_SYMLINK)
                out.linkName = entry.linkName
                out.mode = mode
                out.userId = 0
                out.groupId = 0
                tar.putArchiveEntry(out)
                tar.closeArchiveEntry()
                paths += path
            }
            entry.isLink -> {
                val target = normalize(entry.linkName)
                if (target != null) {
                    val out = TarArchiveEntry(path, TarConstants.LF_LINK)
                    out.linkName = target
                    out.mode = mode
                    out.userId = 0
                    out.groupId = 0
                    tar.putArchiveEntry(out)
                    tar.closeArchiveEntry()
                    paths += path
                }
            }
            entry.isFile -> {
                if (entry.size in 0..MAX_REWRITE) {
                    val bytes = input.readEntry(entry.size.toInt())
                    val rewritten = com.lingyue.layerbridge.deb.ShebangRelocator.rewrite(bytes, LbPaths.PREFIX)
                    val out = TarArchiveEntry(path)
                    out.size = rewritten.size.toLong()
                    out.mode = mode
                    out.userId = 0
                    out.groupId = 0
                    tar.putArchiveEntry(out)
                    tar.write(rewritten)
                    tar.closeArchiveEntry()
                } else {
                    val out = TarArchiveEntry(path)
                    out.size = entry.size
                    out.mode = mode
                    out.userId = 0
                    out.groupId = 0
                    tar.putArchiveEntry(out)
                    input.copyTo(tar)
                    tar.closeArchiveEntry()
                }
                paths += path
            }
        }
    }

    private fun normalize(raw: String): String? {
        var name = raw.removePrefix("./")
        if (name.isEmpty() || name == ".") return null
        if (name.endsWith('/')) name = name.dropLast(1)
        if (!PathGuard.isRelativePath(name)) error("非法包路径：$raw")
        return name
    }

    private suspend fun runScript(
        bytes: ByteArray,
        args: List<String>,
        packageName: String,
        version: String,
        onLog: (String) -> Unit,
    ): ExecResult {
        val body = buildString {
            if (args.isNotEmpty()) {
                appendLine("set -- " + args.joinToString(" ") { PathGuard.quote(it) })
            }
            appendLine(bytes.toString(Charsets.UTF_8))
        }
        val env = mapOf(
            "PREFIX" to LbPaths.PREFIX,
            "PATH" to "${LbPaths.PREFIX}/bin:/system/bin:/system/xbin",
            "LD_LIBRARY_PATH" to "${LbPaths.PREFIX}/lib",
            "HOME" to LbPaths.HOME,
            "TMPDIR" to "$LbPaths.PREFIX/tmp",
            "DEBIAN_FRONTEND" to "noninteractive",
            "DPKG_MAINTSCRIPT_PACKAGE" to packageName,
            "DPKG_MAINTSCRIPT_VERSION" to version,
        )
        val result = Shell.script(body, env)
        if (result.output.isNotBlank()) onLog(result.output)
        return result
    }

    private fun payloadTar(parsed: LbpaFile): TarArchiveInputStream {
        val compression = parsed.manifest.payload?.compression ?: "xz"
        val input = when (compression) {
            "xz" -> XZInputStream(parsed.payloadStream())
            "gzip" -> GZIPInputStream(parsed.payloadStream())
            else -> error("不支持的压缩：$compression")
        }
        return TarArchiveInputStream(input)
    }

    private fun readLbpaControl(file: File): Map<String, ByteArray> {
        val parsed = LbpaFile.parse(file)
        val result = mutableMapOf<String, ByteArray>()
        payloadTar(parsed).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                val name = entry.name.removePrefix("./")
                if (!entry.isDirectory && name.startsWith("control/") && entry.size in 0..MAX_REWRITE) {
                    result[name] = tar.readEntry(entry.size.toInt())
                }
                entry = tar.nextEntry
            }
        }
        return result
    }

    private fun TarArchiveInputStream.readEntry(size: Int): ByteArray {
        val buffer = ByteArray(size)
        var read = 0
        while (read < size) {
            val count = read(buffer, read, size - read)
            if (count < 0) break
            read += count
        }
        return buffer.copyOf(read)
    }

    companion object {
        private const val MAX_REWRITE = 1L * 1024 * 1024
        private val BLOCKED_COMMANDS = setOf(
            "sh", "bash", "su", "ls", "cat", "cp", "mv", "rm", "mount", "umount",
            "pm", "am", "id", "echo", "test", "mkdir", "chmod", "chown", "tar",
        )
    }
}

private fun PackageSigner.sha256File(file: File): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
