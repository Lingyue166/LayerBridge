package com.lingyue.layerbridge.state

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.lingyue.layerbridge.core.PathGuard
import java.io.File

/** 安装时记录的包关系，保持 Debian 控制文件原文，交由 RelationParser 解析。 */
data class PackageRelations(
    val depends: String? = null,
    val provides: String? = null,
    val conflicts: String? = null,
    val breaks: String? = null,
) {
    val isEmpty: Boolean
        get() = depends == null && provides == null && conflicts == null && breaks == null
}

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
    val relations: PackageRelations = PackageRelations(),
    val removeHooks: List<String> = emptyList(),
)

/**
 * 简单的 TSV 状态库：installed.tsv + info/<package>.list。
 * 前 9 列固定，第 10 列是包关系/卸载钩子的 JSON，旧文件缺该列时按空值解析。
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

    fun find(name: String): InstalledPackage? = load().firstOrNull { it.name == name }

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

    private fun encode(pkg: InstalledPackage): String {
        val columns = mutableListOf(
            pkg.name,
            pkg.version,
            pkg.architecture,
            pkg.format,
            pkg.repo,
            pkg.installedAt,
            pkg.signer.orEmpty(),
            pkg.scan,
            pkg.scriptsStatus,
        )
        encodeMeta(pkg).takeIf { it.isNotEmpty() }?.let { columns += it }
        return columns.joinToString("\t", transform = ::sanitize)
    }

    private fun parseLine(line: String): InstalledPackage? {
        if (line.isBlank()) return null
        val parts = line.split('\t')
        if (parts.size < 9) return null
        val name = parts[0]
        if (!PathGuard.isPackageName(name)) return null
        val meta = parts.getOrNull(9)?.let(::decodeMeta)
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
            relations = meta?.relations ?: PackageRelations(),
            removeHooks = meta?.removeHooks.orEmpty(),
        )
    }

    private fun encodeMeta(pkg: InstalledPackage): String {
        if (pkg.relations.isEmpty && pkg.removeHooks.isEmpty()) return ""
        return gson.toJson(Meta(relations = pkg.relations.takeUnless { it.isEmpty }, removeHooks = pkg.removeHooks.ifEmpty { null }))
    }

    private fun decodeMeta(raw: String): Meta? {
        if (raw.isBlank()) return null
        return runCatching { gson.fromJson(raw, Meta::class.java) }.getOrNull()
    }

    private fun sanitize(value: String): String = value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    /** 第 10 列的磁盘格式；字段缺失按 null 处理，保证旧版本写入的行也能读。 */
    private data class Meta(
        @SerializedName("relations") val relations: PackageRelations? = null,
        @SerializedName("removeHooks") val removeHooks: List<String>? = null,
    )

    private companion object {
        val gson = Gson()
    }
}
