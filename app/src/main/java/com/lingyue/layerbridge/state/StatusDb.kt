package com.lingyue.layerbridge.state

import com.lingyue.layerbridge.core.PathGuard
import java.io.File

data class InstalledPackage(
    val name: String,
    val version: String,
    val architecture: String,
    val format: String,
    val repo: String,
    val installedAt: String,
    val signer: String?,
    val scan: String,
    val scriptsStatus: String,
)

/**
 * 简单的 TSV 状态库：installed.tsv + info/<package>.list。
 * 路径均为相对前缀的 POSIX 路径。
 */
class StatusDb(private val rootDir: File) {

    private val statusFile = File(rootDir, "installed.tsv")
    private val infoDir = File(rootDir, "info")

    fun load(): List<InstalledPackage> {
        if (!statusFile.isFile) return emptyList()
        return statusFile.readLines(Charsets.UTF_8).mapNotNull(::parseLine)
    }

    fun save(packages: List<InstalledPackage>) {
        rootDir.mkdirs()
        val text = serialize(packages)
        val temp = File(rootDir, "installed.tsv.tmp")
        temp.writeText(text, Charsets.UTF_8)
        if (!temp.renameTo(statusFile)) {
            statusFile.writeText(text, Charsets.UTF_8)
            temp.delete()
        }
    }

    fun serialize(packages: List<InstalledPackage>): String =
        packages.sortedBy { it.name }.joinToString(separator = "\n", postfix = "\n") { encode(it) }

    fun upsert(pkg: InstalledPackage) {
        val packages = load().filterNot { it.name == pkg.name } + pkg
        save(packages)
    }

    fun remove(name: String) {
        save(load().filterNot { it.name == name })
        File(infoDir, "$name.list").delete()
    }

    fun isInstalled(name: String): Boolean = load().any { it.name == name }

    fun versionOf(name: String): String? = load().firstOrNull { it.name == name }?.version

    fun writeFileList(name: String, paths: List<String>) {
        if (!PathGuard.isPackageName(name)) throw IllegalArgumentException("非法包名：$name")
        val valid = paths.filter { PathGuard.isRelativePath(it) }.distinct()
        infoDir.mkdirs()
        File(infoDir, "$name.list").writeText(valid.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
    }

    fun readFileList(name: String): List<String> {
        val file = File(infoDir, "$name.list")
        if (!file.isFile) return emptyList()
        return file.readLines(Charsets.UTF_8)
            .map { it.trim() }
            .filter { it.isNotEmpty() && PathGuard.isRelativePath(it) }
    }

    private fun encode(pkg: InstalledPackage): String = listOf(
        pkg.name,
        pkg.version,
        pkg.architecture,
        pkg.format,
        pkg.repo,
        pkg.installedAt,
        pkg.signer.orEmpty(),
        pkg.scan,
        pkg.scriptsStatus,
    ).joinToString("\t", transform = ::sanitize)

    private fun parseLine(line: String): InstalledPackage? {
        if (line.isBlank()) return null
        val parts = line.split('\t')
        if (parts.size < 9) return null
        val name = parts[0]
        if (!PathGuard.isPackageName(name)) return null
        return InstalledPackage(
            name = name,
            version = parts[1],
            architecture = parts[2],
            format = parts[3].ifEmpty { "deb" },
            repo = parts[4],
            installedAt = parts[5],
            signer = parts[6].ifEmpty { null },
            scan = parts[7].ifEmpty { "unknown" },
            scriptsStatus = parts[8].ifEmpty { "unknown" },
        )
    }

    private fun sanitize(value: String): String = value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
}
