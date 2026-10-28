package com.lingyue.layerbridge.state

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.lingyue.layerbridge.core.LbPaths
import com.lingyue.layerbridge.core.PathGuard

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

/** 安装事务：安装开始即落盘，安装中途崩溃时 `lb uninstall` 仍能据此清理残留。 */
data class InstallTransaction(
    val packageName: String,
    val version: String,
    val phase: String,
    val files: List<String> = emptyList(),
    val wrappers: List<String> = emptyList(),
)

/**
 * 状态库：`installed.tsv` + `info/<package>.list`，路径固定在前缀数据库目录。
 * 前 9 列固定，第 10 列是包关系/卸载钩子的 JSON，旧文件缺该列时按空值解析。
 * 实际读写由 [StateStorage] 决定（App=su，CLI=直接文件）。
 */
class StatusDb(
    private val storage: StateStorage,
    private val rootDir: String = LbPaths.DB_DIR,
) {

    private val statusPath: String get() = "$rootDir/installed.tsv"

    suspend fun load(): List<InstalledPackage> =
        storage.readText(statusPath)?.lineSequence()?.mapNotNull(::parseLine)?.toList().orEmpty()

    suspend fun save(packages: List<InstalledPackage>) {
        storage.writeText(statusPath, serialize(packages))
    }

    fun serialize(packages: List<InstalledPackage>): String =
        packages.sortedBy { it.name }.joinToString(separator = "\n", postfix = "\n") { encode(it) }

    suspend fun upsert(pkg: InstalledPackage) {
        save(load().filterNot { it.name == pkg.name } + pkg)
    }

    suspend fun remove(name: String) {
        save(load().filterNot { it.name == name })
        storage.delete(listPath(name))
    }

    suspend fun find(name: String): InstalledPackage? = load().firstOrNull { it.name == name }

    suspend fun versionOf(name: String): String? = find(name)?.version

    suspend fun writeFileList(name: String, paths: List<String>) {
        require(PathGuard.isPackageName(name)) { "非法包名：$name" }
        val valid = paths.filter { PathGuard.isRelativePath(it) }.distinct()
        storage.writeText(listPath(name), valid.joinToString("\n", postfix = "\n"))
    }

    suspend fun readFileList(name: String): List<String> =
        storage.readText(listPath(name))?.lines().orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() && PathGuard.isRelativePath(it) }

    /** 卸载默认保留的数据文件清单；`lb remove --force` 才会连带删除。 */
    suspend fun readPreservedData(name: String): List<String> =
        storage.readText(dataPath(name))?.lines().orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() && PathGuard.isRelativePath(it) }

    suspend fun writePreservedData(name: String, paths: List<String>) {
        require(PathGuard.isPackageName(name)) { "非法包名：$name" }
        if (paths.isEmpty()) {
            storage.delete(dataPath(name))
            return
        }
        storage.writeText(dataPath(name), paths.distinct().joinToString("\n", postfix = "\n"))
    }

    suspend fun deletePreservedData(name: String) {
        storage.delete(dataPath(name))
    }

    suspend fun beginTransaction(name: String, version: String) {
        require(PathGuard.isPackageName(name)) { "非法包名：$name" }
        writeTransaction(InstallTransaction(name, version, PHASE_BEGIN))
    }

    suspend fun recordTransaction(transaction: InstallTransaction) {
        require(PathGuard.isPackageName(transaction.packageName)) { "非法包名：${transaction.packageName}" }
        writeTransaction(transaction)
    }

    suspend fun readTransaction(name: String): InstallTransaction? =
        storage.readText(transactionPath(name))
            ?.let { runCatching { gson.fromJson(it, InstallTransaction::class.java) }.getOrNull() }
            ?.takeIf { it.packageName == name }

    suspend fun endTransaction(name: String) {
        storage.delete(transactionPath(name))
    }

    private suspend fun writeTransaction(transaction: InstallTransaction) {
        storage.writeText(transactionPath(transaction.packageName), gson.toJson(transaction))
    }

    private fun transactionPath(name: String): String = "$rootDir/transactions/$name.json"

    private fun listPath(name: String): String = "$rootDir/info/$name.list"

    private fun dataPath(name: String): String = "$rootDir/data/$name.list"

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
        return gson.toJson(
            Meta(
                relations = pkg.relations.takeUnless { it.isEmpty },
                removeHooks = pkg.removeHooks.ifEmpty { null },
            ),
        )
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
        const val PHASE_BEGIN = "begin"
        val gson = Gson()
    }
}
