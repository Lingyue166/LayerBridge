package com.lingyue.layerbridge.install

import com.lingyue.layerbridge.core.EnvFile
import com.lingyue.layerbridge.core.LbPaths
import com.lingyue.layerbridge.core.PathGuard
import com.lingyue.layerbridge.core.Shell
import com.lingyue.layerbridge.crypto.PackageSigner
import com.lingyue.layerbridge.crypto.TrustStore
import com.lingyue.layerbridge.deb.DebFile
import com.lingyue.layerbridge.expose.ExposeCommands
import com.lingyue.layerbridge.expose.ExposeMode
import com.lingyue.layerbridge.expose.ExposeTargets
import com.lingyue.layerbridge.expose.Wrapper
import com.lingyue.layerbridge.format.LbpaFile
import com.lingyue.layerbridge.scan.PackageScanner
import com.lingyue.layerbridge.scan.Severity
import com.lingyue.layerbridge.state.InstallTransaction
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
            val baseMode = if (exposeMode() == ExposeMode.SYSTEM) "755" else "700"
            val migrate = "if [ ! -d ${PathGuard.quote(LbPaths.BASE)} ] && [ -d /data/local/lb ];" +
                " then mv /data/local/lb ${PathGuard.quote(LbPaths.BASE)}; fi; "
            val result = Shell.exec(
                migrate + "mkdir -p $dirs && chmod $baseMode ${PathGuard.quote(LbPaths.BASE)}" +
                    " && chmod 755 ${PathGuard.quote(LbPaths.PREFIX)}",
            )
            if (!result.isSuccess) error("prefix init failed: ${result.output}")
            writeEnvFile()
            syncRootLinks()
        }
    }

    suspend fun installDeb(
        deb: File,
        repoId: String,
        repoVerified: Boolean,
        policy: InstallPolicy,
        requireInstalled: Boolean = false,
        updateLinks: Boolean = true,
        onLog: (String) -> Unit,
    ): Result<InstalledPackage> = withContext(Dispatchers.IO) {
        runCatching {
            val tStart = System.nanoTime()
            val parsed = DebFile(deb)
            val control = parsed.readControl()
            val name = control.packageName?.takeIf { PathGuard.isPackageName(it) }
                ?: error("invalid or missing package name")
            val version = control.version?.takeIf { it.isNotBlank() } ?: error("missing version")
            val arch = control.architecture ?: "aarch64"
            if (arch != "aarch64" && arch != "all") error("unsupported architecture: $arch")

            val relations = PackageRelations(
                depends = control.depends,
                provides = control.provides,
                conflicts = control.conflicts,
                breaks = control.breaks,
            )
            onLog("[lb]   package: $name $version $arch (${elapsedMs(tStart)} ms)")
            val tSign = System.nanoTime()
            val signer = verifyDebSignature(deb, name, repoId, repoVerified, policy)
            ConflictCheck.find(name, version, relations, statusDb.load())?.let { error(it.message) }
            val tScan = System.nanoTime()
            scanScripts("$name:deb", control.scripts, policy, onLog)
            onLog("[lb]   signature: $signer (${elapsedMs(tSign)} ms)")
            onLog("[lb]   scan: ${elapsedMs(tScan)} ms")

            val existing = statusDb.find(name)
            if (requireInstalled && existing == null) error("not installed: $name (use install)")
            logUpgrade(existing, version, onLog)

            val oldFiles = statusDb.readFileList(name)
            val dataFiles = control.conffiles
            val keep = existing?.let { dataFiles }.orEmpty()

            // 旧包的 prerm 必须在覆盖 db/scripts 之前执行
            existing?.let { old ->
                val prerm = maintainerScripts.run(name, old.version, "prerm", listOf("upgrade", version), onLog)
                if (prerm != null && !prerm.isSuccess) error("prerm upgrade failed")
            }
            backupPaths(name, keep)
            maintainerScripts.clear(name)
            maintainerScripts.save(name, control.scripts)

            val pkg = try {
                val preArgs = if (existing != null) listOf("upgrade", existing.version) else listOf("install")
                val preinst = maintainerScripts.run(name, version, "preinst", preArgs, onLog)
                if (preinst != null && !preinst.isSuccess) error("preinst failed")

                statusDb.beginTransaction(name, version)
                val tExtract = System.nanoTime()
                val paths = PayloadExtractor.extractDeb(parsed, stageDir(name), onLog)
                statusDb.recordTransaction(InstallTransaction(name, version, PHASE_MERGE, paths, wrapperCommands(paths)))
                onLog("[lb]   extract: ${paths.size} files (${elapsedMs(tExtract)} ms)")
                val tMerge = System.nanoTime()
                mergeStaged(name, paths, onLog)
                onLog("[lb]   merge: ${elapsedMs(tMerge)} ms")
                restorePaths(name, keep)
                if (existing != null) removeObsolete(oldFiles, paths, keep, emptyList(), onLog)

                var scriptsStatus = "ok"
                val postArgs = if (existing != null) listOf("configure", existing.version) else listOf("configure", version)
                val postinst = maintainerScripts.run(name, version, "postinst", postArgs, onLog)
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
                ).also { finishInstall(it, paths, dataFiles, emptyMap(), updateLinks, onLog) }
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
        requireInstalled: Boolean = false,
        updateLinks: Boolean = true,
        onLog: (String) -> Unit,
    ): Result<InstalledPackage> = withContext(Dispatchers.IO) {
        runCatching {
            val tStart = System.nanoTime()
            val parsed = LbpaFile.parse(file)
            val info = parsed.manifest.packageInfo ?: error("missing package metadata")
            val name = info.name?.takeIf { PathGuard.isPackageName(it) } ?: error("invalid package name")
            val version = info.version ?: error("missing version")
            val arch = info.architecture ?: "aarch64"
            if (arch != "aarch64" && arch != "all") error("unsupported architecture: $arch")

            val lbpaRelations = parsed.manifest.relations
            val relations = PackageRelations(
                depends = lbpaRelations?.depends.raw(),
                provides = lbpaRelations?.provides.raw(),
                conflicts = lbpaRelations?.conflicts.raw(),
                breaks = lbpaRelations?.breaks.raw(),
            )
            val removeHooks = parsed.manifest.hooks?.remove.orEmpty()

            onLog("[lb]   package: $name $version $arch (${elapsedMs(tStart)} ms)")
            val tSign = System.nanoTime()
            val signer = verifyLbpaSignature(parsed, repoId, repoVerified, policy)
            ConflictCheck.find(name, version, relations, statusDb.load())?.let { error(it.message) }
            val controlScripts = PayloadExtractor.readLbpaControl(parsed)
            val tScan = System.nanoTime()
            scanScripts("$name:lbpa", controlScripts, policy, onLog)
            onLog("[lb]   signature: $signer (${elapsedMs(tSign)} ms)")
            onLog("[lb]   scan: ${elapsedMs(tScan)} ms")

            val upgrade = parsed.manifest.upgrade
            val existing = statusDb.find(name)
            if (requireInstalled && existing == null) error("not installed: $name (use install)")
            logUpgrade(existing, version, onLog)

            val oldFiles = statusDb.readFileList(name)
            val dataFiles = parsed.manifest.data.orEmpty()
            val keep = if (existing != null) (upgrade?.keep.orEmpty() + dataFiles).distinct() else emptyList()
            val extraRemove = upgrade?.remove.orEmpty()

            // 旧包的 prerm 必须在覆盖 db/scripts 之前执行
            existing?.let { old ->
                val prerm = maintainerScripts.run(name, old.version, "prerm", listOf("upgrade", version), onLog)
                if (prerm != null && !prerm.isSuccess) error("prerm upgrade failed")
            }
            backupPaths(name, keep)
            maintainerScripts.clear(name)
            maintainerScripts.save(name, controlScripts)

            val pkg = try {
                val scripts = parsed.manifest.scripts
                val preArgs = if (existing != null) listOf(existing.version) else listOf("install")
                val preScript = if (existing != null) upgrade?.scripts?.pre ?: scripts?.preinst else scripts?.preinst
                val preinst = preScript?.let { maintainerScripts.run(name, version, it, preArgs, onLog) }
                if (preinst != null && !preinst.isSuccess) error("preinst failed")

                statusDb.beginTransaction(name, version)
                val tExtract = System.nanoTime()
                val paths = PayloadExtractor.extractLbpa(parsed, stageDir(name), onLog)
                statusDb.recordTransaction(InstallTransaction(name, version, PHASE_MERGE, paths, wrapperCommands(paths)))
                onLog("[lb]   extract: ${paths.size} files (${elapsedMs(tExtract)} ms)")
                val tMerge = System.nanoTime()
                mergeStaged(name, paths, onLog)
                onLog("[lb]   merge: ${elapsedMs(tMerge)} ms")
                restorePaths(name, keep)
                if (existing != null) removeObsolete(oldFiles, paths, keep, extraRemove, onLog)

                var scriptsStatus = "ok"
                val postArgs = if (existing != null) listOf(existing.version) else listOf("configure", version)
                val postScript = if (existing != null) upgrade?.scripts?.post ?: scripts?.postinst else scripts?.postinst
                val postinst = postScript?.let { maintainerScripts.run(name, version, it, postArgs, onLog) }
                if (postinst != null && !postinst.isSuccess) scriptsStatus = "postinst-failed"

                parsed.manifest.hooks?.install.orEmpty().forEach { hook ->
                    val result = maintainerScripts.run(name, version, hook, emptyList(), onLog)
                    if (result != null && !result.isSuccess) onLog("[lb] hook failed: $hook")
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
                ).also {
                    finishInstall(it, paths, dataFiles, parsed.manifest.env.orEmpty(), updateLinks, onLog)
                }
            } catch (t: Throwable) {
                maintainerScripts.clear(name)
                throw t
            }
            pkg
        }
    }

    /**
     * 卸载以「状态库 ∪ 安装事务」为准，因此安装任意阶段崩溃/被杀后仍能清干净。
     * 默认保留包声明的数据/配置（deb Conffiles、LBPA data），`force=true` 才全清。
     */
    suspend fun uninstall(name: String, force: Boolean = false, onLog: (String) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(PathGuard.isPackageName(name)) { "invalid package name: $name" }
                val pkg = statusDb.find(name)
                val transaction = statusDb.readTransaction(name)
                val recorded = (statusDb.readFileList(name) + transaction?.files.orEmpty())
                    .distinct()
                    .filter { PathGuard.isRelativePath(it) }
                val preserved = statusDb.readPreservedData(name)
                val keep = if (force) emptySet() else preserved.toSet()
                val removal = recorded.filterNot { it in keep }

                if (pkg == null && transaction == null) {
                    if (force && preserved.isNotEmpty()) {
                        removeFiles(preserved)
                        removeEmptyDirs(preserved)
                        statusDb.deletePreservedData(name)
                        onLog("[lb] removed ${preserved.size} data files: $name")
                    } else {
                        maintainerScripts.clear(name)
                        purgePackageDirs(name)
                        if (preserved.isNotEmpty()) {
                            onLog("[lb] removed; kept ${preserved.size} data files (lb remove --force $name to purge)")
                        } else {
                            onLog("[lb] not installed and no leftovers: $name")
                        }
                    }
                    return@runCatching
                }

                val version = pkg?.version ?: transaction?.version ?: "unknown"
                val prerm = maintainerScripts.run(name, version, "prerm", listOf("remove"), onLog)
                if (prerm != null && !prerm.isSuccess) error("prerm failed; uninstall aborted: $name")

                pkg?.removeHooks.orEmpty().forEach { hook ->
                    val result = maintainerScripts.run(name, version, hook, emptyList(), onLog)
                    if (result != null && !result.isSuccess) onLog("[lb] remove hook failed: $hook")
                }

                if (removal.isNotEmpty()) {
                    removeFiles(removal)
                    removeEmptyDirs(removal)
                }
                removeWrappers(name, removal)

                val postrm = maintainerScripts.run(name, version, "postrm", listOf("remove"), onLog)
                if (postrm != null && !postrm.isSuccess) onLog("[lb] postrm failed: $name")

                // 状态最后删：中途失败可重跑，残留仍可被下一次 uninstall 找到
                statusDb.remove(name)
                statusDb.endTransaction(name)
                if (force) {
                    statusDb.deletePreservedData(name)
                } else {
                    val kept = preserved.filter { it in recorded }
                    if (kept.isNotEmpty()) {
                        onLog("[lb] kept ${kept.size} data files (lb remove --force $name to purge)")
                    }
                }
                maintainerScripts.clear(name)
                purgePackageDirs(name)
                clearPackageEnv(name)
                syncRootLinks()
                writeEnvFile()
                onLog("[lb] removed $name (${removal.size} files)")
            }
        }

    suspend fun refreshEnvironment(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { writeEnvFile() }
    }

    /**
     * 扫描已安装包补写缺失的环境文件 并重写对应 wrapper。
     * 旧版本安装的包没有 env 文件 这里会尝试在下载目录里找同名包重新解析 manifest。
     */
    suspend fun repairEnvironment(
        searchDirs: List<File>? = null,
        onLog: (String) -> Unit = {},
    ): Int = withContext(Dispatchers.IO) {
        val dirs = searchDirs ?: defaultPackageDirs()
        val packages = statusDb.load()
        var repaired = 0
        packages.forEach { pkg ->
            val envFile = File(LbPaths.ENV_DIR, "${pkg.name}.env")
            var env = runCatching { EnvFile.parsePackageEnv(envFile.readText()) }.getOrDefault(emptyMap())
            if (env.isEmpty()) {
                env = findPackageEnv(pkg.name, dirs)
                if (env.isNotEmpty()) {
                    writePackageEnv(pkg.name, env)
                    onLog("[lb] repaired env: ${pkg.name} (${env.size})")
                }
            }
            if (env.isNotEmpty()) {
                writeWrappers(pkg.name, statusDb.readFileList(pkg.name), env)
                repaired++
            }
        }
        writeEnvFile()
        onLog("[lb] environment repair done: $repaired packages")
        repaired
    }

    private fun defaultPackageDirs(): List<File> = listOf(
        File("/sdcard/Download"),
        File("/sdcard/Download/lb-repo/lbpa"),
        File("/sdcard/Download/lb-repo-lbpa"),
    )

    private fun findPackageEnv(name: String, dirs: List<File>): Map<String, String> {
        val candidates = dirs.flatMap { dir ->
            dir.listFiles().orEmpty().filter {
                it.isFile && it.name.endsWith(".lbpa") && it.name.startsWith("${name}_")
            }
        }
        candidates.forEach { file ->
            val env = runCatching {
                LbpaFile.parse(file).manifest.env.orEmpty()
            }.getOrDefault(emptyMap())
            if (env.isNotEmpty()) return env
        }
        return emptyMap()
    }

    private suspend fun writePackageEnv(name: String, env: Map<String, String>) {
        val file = File(LbPaths.ENV_DIR, "$name.env")
        if (env.isEmpty()) {
            clearPackageEnv(name)
            return
        }
        Shell.writeFile(file.path, EnvFile.renderPackageEnv(env), "644")
    }

    private suspend fun clearPackageEnv(name: String) {
        Shell.exec("rm -f ${PathGuard.quote("${LbPaths.ENV_DIR}/$name.env")}")
    }

    /**
     * root shell 直接可用：把包命令软链到 root 管理器的 bin 目录（/data/adb/ksu/bin 等）。
     * 目录 700，普通应用不可见；不产生 /system 改动。
     */
    suspend fun setRootExposure(enabled: Boolean, onLog: (String) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (enabled) {
                    Shell.exec("rm -f ${PathGuard.quote(LbPaths.NO_EXPOSE_ROOT_FLAG)}")
                } else {
                    Shell.writeFile(LbPaths.NO_EXPOSE_ROOT_FLAG, "disabled\n", "600")
                }
                syncRootLinks()
                onLog(
                    if (enabled) "[lb] root shell links enabled; maintained on install/remove"
                    else "[lb] root shell links disabled; existing links removed",
                )
            }
        }

    private suspend fun finishInstall(
        pkg: InstalledPackage,
        paths: List<String>,
        dataFiles: List<String>,
        env: Map<String, String>,
        updateLinks: Boolean,
        onLog: (String) -> Unit,
    ) {
        val tStatus = System.nanoTime()
        statusDb.upsert(pkg)
        statusDb.writeFileList(pkg.name, paths)
        statusDb.writePreservedData(pkg.name, dataFiles)
        statusDb.endTransaction(pkg.name)
        onLog("[lb]   state: ${elapsedMs(tStatus)} ms")

        val tEnv = System.nanoTime()
        writePackageEnv(pkg.name, env)
        writeEnvFile()
        onLog("[lb]   env: ${elapsedMs(tEnv)} ms")

        val tWrap = System.nanoTime()
        writeWrappers(pkg.name, paths, env)
        onLog("[lb]   wrappers: ${wrapperCommands(paths).size} (${elapsedMs(tWrap)} ms)")

        if (updateLinks) {
            val tLinks = System.nanoTime()
            syncRootLinks()
            onLog("[lb]   links: ${elapsedMs(tLinks)} ms")
        }
        onLog("[lb] installed ${pkg.name} ${pkg.version}")
    }

    /** 重建 root 管理器 bin 目录里的软链：只动指向我们 wrapper 的链接，从不覆盖已有命令。 */
    suspend fun refreshRootLinks() = syncRootLinks()

    private suspend fun syncRootLinks() {
        val dirs = ROOT_BIN_DIRS.filter { Shell.exec("test -d ${PathGuard.quote(it)}").isSuccess }
        if (dirs.isEmpty()) return

        // 显式关闭时清掉自己的链接
        if (Shell.exec("test -f ${PathGuard.quote(LbPaths.NO_EXPOSE_ROOT_FLAG)}").isSuccess) {
            dirs.forEach { dir ->
                Shell.exec(
                    "find ${PathGuard.quote(dir)} -maxdepth 1 -type l " +
                        "-lname ${PathGuard.quote("${LbPaths.SYSTEM_BIN}/*")} -delete",
                )
            }
            return
        }

        // 直接看 wrapper 目录：它就是当前命令集合，避免逐包读状态文件
        val listed = Shell.exec("ls -1 ${PathGuard.quote(LbPaths.SYSTEM_BIN)} 2>/dev/null").output
        val commands = listed.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && isExposableCommand(it) && it !in BLOCKED_COMMANDS }
            .toSet()

        // 每个目录一条脚本，一次 su 完成补建与清理
        dirs.forEach { dir ->
            Shell.exec(LinkScript.build(dir, commands, LbPaths.SYSTEM_BIN))
        }
    }

    private fun elapsedMs(start: Long): Long = (System.nanoTime() - start) / 1_000_000

    private fun stageDir(name: String): String = "${LbPaths.CACHE}/stage/$name"

    private fun wrapperCommands(paths: List<String>): List<String> = paths.asSequence()
        .filter { it.startsWith("bin/") || it.startsWith("sbin/") }
        .map { it.substringAfterLast('/') }
        .filter { isExposableCommand(it) }
        .distinct()
        .toList()

    /** 暂存目录整体并入前缀；失败时事务文件仍在，卸载据此清理。 */
    private suspend fun mergeStaged(name: String, paths: List<String>, onLog: (String) -> Unit) {
        val stage = stageDir(name)
        if (paths.isEmpty()) {
            Shell.exec("rm -rf ${PathGuard.quote(stage)}")
            return
        }
        val script = "tar -C ${PathGuard.quote(stage)} -cf - . | tar -C ${PathGuard.quote(LbPaths.PREFIX)} -xf -" +
            " && rm -rf ${PathGuard.quote(stage)}"
        val result = Shell.script(script)
        if (!result.isSuccess) error("merge into prefix failed: ${result.output}")
        onLog("[lb] wrote ${paths.size} entries")
    }

    /** 清掉按包名约定的缓存/暂存目录，覆盖安装中途被杀留下的半成品。 */
    private suspend fun purgePackageDirs(name: String) {
        val dirs = listOf(stageDir(name), "${LbPaths.CACHE}/upgrade/$name")
            .joinToString(" ") { PathGuard.quote(it) }
        Shell.exec("rm -rf $dirs")
    }

    private fun logUpgrade(existing: InstalledPackage?, version: String, onLog: (String) -> Unit) {
        if (existing == null) return
        when (UpgradeKind.of(existing.version, version)) {
            UpgradeKind.UPGRADE -> onLog("[lb] upgrade: ${existing.version} -> $version")
            UpgradeKind.DOWNGRADE -> onLog("[lb] downgrade: ${existing.version} -> $version")
            UpgradeKind.REINSTALL -> onLog("[lb] reinstall same version: $version")
            UpgradeKind.FRESH -> Unit
        }
    }

    /** 升级前把旧配置（deb 的 Conffiles / LBPA 的 upgrade.keep）备份到缓存目录。 */
    private suspend fun backupPaths(name: String, paths: List<String>) {
        if (paths.isEmpty()) return
        val dir = "${LbPaths.CACHE}/upgrade/$name"
        val script = buildString {
            appendLine("rm -rf ${PathGuard.quote(dir)}")
            paths.forEach { path ->
                val parent = path.substringBeforeLast('/', "")
                if (parent.isNotEmpty()) appendLine("mkdir -p ${PathGuard.quote("$dir/$parent")}")
                appendLine("cp -a ${PathGuard.quote("${LbPaths.PREFIX}/$path")} ${PathGuard.quote("$dir/$path")} 2>/dev/null || true")
            }
        }
        Shell.script(script)
    }

    private suspend fun restorePaths(name: String, paths: List<String>) {
        if (paths.isEmpty()) return
        val dir = "${LbPaths.CACHE}/upgrade/$name"
        val script = buildString {
            paths.forEach { path ->
                val parent = path.substringBeforeLast('/', "")
                if (parent.isNotEmpty()) appendLine("mkdir -p ${PathGuard.quote("${LbPaths.PREFIX}/$parent")}")
                appendLine("cp -a ${PathGuard.quote("$dir/$path")} ${PathGuard.quote("${LbPaths.PREFIX}/$path")} 2>/dev/null || true")
            }
            appendLine("rm -rf ${PathGuard.quote(dir)}")
        }
        Shell.script(script)
    }

    private suspend fun removeObsolete(
        oldFiles: List<String>,
        newFiles: List<String>,
        keep: List<String>,
        extraRemove: List<String>,
        onLog: (String) -> Unit,
    ) {
        val obsolete = UpgradePlan.obsolete(oldFiles, newFiles, keep, extraRemove)
        if (obsolete.isEmpty()) return
        onLog("[lb] removing obsolete files: ${obsolete.size}")
        removeFiles(obsolete)
        removeEmptyDirs(obsolete)
    }

    private suspend fun removeFiles(files: List<String>) {
        files.chunked(100).forEach { chunk ->
            val command = chunk.joinToString(" ") { "rm -f -- ${PathGuard.quote("${LbPaths.PREFIX}/$it")}" }
            val result = Shell.exec(command)
            if (!result.isSuccess) error("delete files failed: ${result.output}")
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
        val packageEnv = loadPackageEnv()
        Shell.writeFile(
            LbPaths.ENV_FILE,
            "${EnvFile.HEADER}\n${EnvFile.render(packageEnv)}",
            "644",
        )
    }

    /** 读取 env.d 下的包环境 逐行解析 旧版本缺失目录时返回空。 */
    private suspend fun loadPackageEnv(): Map<String, Map<String, String>> {
        val listing = Shell.exec("ls -1 ${PathGuard.quote(LbPaths.ENV_DIR)} 2>/dev/null").output
        val result = mutableMapOf<String, Map<String, String>>()
        listing.lineSequence()
            .map { it.trim() }
            .filter { it.endsWith(".env") }
            .sorted()
            .forEach { file ->
                val name = file.removeSuffix(".env")
                val text = Shell.exec(
                    "cat ${PathGuard.quote("${LbPaths.ENV_DIR}/$file")} 2>/dev/null",
                ).output
                val env = EnvFile.parsePackageEnv(text)
                if (env.isNotEmpty()) result[name] = env
            }
        return result
    }

    private suspend fun writeWrappers(
        owner: String,
        paths: List<String>,
        env: Map<String, String> = emptyMap(),
    ) {
        val mode = exposeMode()
        val moduleReady = Shell.exec("test -d ${PathGuard.quote(LbPaths.MODULE_DIR)}").isSuccess
        paths.asSequence()
            .filter { it.startsWith("bin/") || it.startsWith("sbin/") }
            .map { it to it.substringAfterLast('/') }
            .filter { (_, command) -> isExposableCommand(command) }
            .forEach { (path, command) ->
                val script = Wrapper.script(path, extraEnv = env, owner = owner)
                ExposeTargets.of(mode, command, moduleReady).forEach { target ->
                    Shell.writeFile(target, script, "755")
                }
            }
    }

    /** 默认 STEALTH：不写系统分区、不挂 overlay，包只对 root 可见。 */
    private suspend fun exposeMode(): ExposeMode =
        if (Shell.exec("test -f ${PathGuard.quote(LbPaths.EXPOSE_FLAG)}").isSuccess) ExposeMode.SYSTEM
        else ExposeMode.STEALTH

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

    /** 清理时始终覆盖三处历史落点，避免此前 SYSTEM 模式留下的 wrapper 残留。 */
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
            val root = trustStore.loadRoot() ?: error("trust root not configured; cannot verify package signature")
            val block = PackageSigner.parseBlock(sidecar.readText(Charsets.UTF_8))
            val hash = PackageSigner.sha256File(deb)
            return PackageSigner.verifyDeb(block, hash, root, packageName).getOrElse { error(it.message ?: "signature is invalid") }.subject
        }

        if (policy.requireSignature) error("signature not authorized: $packageName")
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
            if (policy.requireSignature) error("signature not authorized: ${parsed.packageName}")
            return null
        }
        val root = trustStore.loadRoot() ?: error("trust root not configured; cannot verify package signature")
        val block = PackageSigner.parseBlock(signatureJson)
        return PackageSigner.verifyLbpa(
            block = block,
            manifestBytes = parsed.manifestBytes,
            payloadHashHex = parsed.payloadSha256(),
            root = root,
            packageName = parsed.packageName,
        ).getOrElse { error(it.message ?: "signature is invalid") }.subject
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
                error("malware blocked: ${finding.rule} @ ${finding.file}:${finding.line}")
            }
            report.findings.forEach { onLog("[scan] ${it.severity} ${it.rule} @ ${it.file}:${it.line}") }
        }
    }

    private fun List<String>?.raw(): String? =
        this?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }?.joinToString(", ")

    private companion object {
        const val PHASE_MERGE = "merge"
        val ROOT_BIN_DIRS = listOf("/data/adb/ksu/bin", "/data/adb/ap/bin", "/data/adb/magisk")
        private val BLOCKED_COMMANDS = setOf(
            "sh", "bash", "su", "ls", "cat", "cp", "mv", "rm", "mount", "umount",
            "pm", "am", "id", "echo", "test", "mkdir", "chmod", "chown", "tar",
        )
    }
}
