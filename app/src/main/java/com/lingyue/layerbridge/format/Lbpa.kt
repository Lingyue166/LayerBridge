package com.lingyue.layerbridge.format

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.lingyue.layerbridge.core.DpkgVersion
import com.lingyue.layerbridge.core.PathGuard
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest

data class LbpaPayload(
    val compression: String? = null,
    val size: Long = 0,
    val sha256: String? = null,
    val uncompressedSize: Long? = null,
    val sha256Uncompressed: String? = null,
)

data class LbpaPackageInfo(
    val name: String? = null,
    val version: String? = null,
    val architecture: String? = null,
    val description: String? = null,
)

data class LbpaRelations(
    val depends: List<String>? = null,
    val preDepends: List<String>? = null,
    val provides: List<String>? = null,
    val conflicts: List<String>? = null,
    val breaks: List<String>? = null,
    val replaces: List<String>? = null,
    val recommends: List<String>? = null,
    val suggests: List<String>? = null,
)

data class LbpaScripts(
    val preinst: String? = null,
    val postinst: String? = null,
    val prerm: String? = null,
    val postrm: String? = null,
)

data class LbpaUpgradeScripts(
    val pre: String? = null,
    val post: String? = null,
)

/** 升级策略：任意版本 → 任意版本，keep 保护旧配置，remove 清理旧残留。 */
data class LbpaUpgrade(
    val keep: List<String>? = null,
    val remove: List<String>? = null,
    val scripts: LbpaUpgradeScripts? = null,
)

data class LbpaHooks(
    val boot: List<String>? = null,
    val install: List<String>? = null,
    val remove: List<String>? = null,
)

data class LbpaManifest(
    val format: String? = null,
    val formatVersion: Int = 0,
    val minLbVersion: String? = null,
    val createdBy: String? = null,
    val createdAt: String? = null,
    @com.google.gson.annotations.SerializedName("package")
    val packageInfo: LbpaPackageInfo? = null,
    val relations: LbpaRelations? = null,
    val payload: LbpaPayload? = null,
    val scripts: LbpaScripts? = null,
    val hooks: LbpaHooks? = null,
    val upgrade: LbpaUpgrade? = null,
    val env: Map<String, String>? = null,
    val expose: List<String>? = null,
    val gui: Boolean? = null,
    val installedSize: Long? = null,
    val description: String? = null,
    val homepage: String? = null,
    val maintainer: String? = null,
    val license: String? = null,
)

class LbpaFormatException(message: String) : Exception(message)

class LbpaFile private constructor(
    val file: File,
    val manifest: LbpaManifest,
    val manifestBytes: ByteArray,
    val payloadOffset: Long,
    val payloadSize: Long,
    val signatureJson: String?,
) {

    val packageName: String get() = manifest.packageInfo?.name.orEmpty()

    fun payloadStream(): InputStream {
        val raw = FileInputStream(file)
        var skipped = 0L
        while (skipped < payloadOffset) {
            val step = raw.skip(payloadOffset - skipped)
            if (step <= 0) throw LbpaFormatException("payload 定位失败")
            skipped += step
        }
        return LimitedStream(raw, payloadSize)
    }

    fun payloadSha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        payloadStream().use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    companion object {
        const val MAGIC_STRING = "LBPA\r\n\u001A\n"
        val MAGIC: ByteArray = MAGIC_STRING.toByteArray(Charsets.US_ASCII)
        private const val MAX_MANIFEST = 1024 * 1024
        private const val MAX_SIGNATURE = 64 * 1024
        private val gson = Gson()

        fun isLbpa(file: File): Boolean {
            if (!file.isFile || file.length() < 16) return false
            val head = ByteArray(MAGIC.size)
            file.inputStream().use { stream ->
                var read = 0
                while (read < head.size) {
                    val count = stream.read(head, read, head.size - read)
                    if (count < 0) return false
                    read += count
                }
            }
            return head.contentEquals(MAGIC)
        }

        fun parse(file: File, currentLbVersion: String = "0.1.0"): LbpaFile {
            if (!isLbpa(file)) throw LbpaFormatException("魔数不匹配")
            DataInputStream(file.inputStream().buffered()).use { input ->
                val magic = ByteArray(MAGIC.size)
                input.readFully(magic)
                if (!magic.contentEquals(MAGIC)) throw LbpaFormatException("魔数不匹配")
                val major = input.readUnsignedShort()
                val flags = input.readUnsignedShort()
                if (major != 1) throw LbpaFormatException("不支持的 formatVersion：$major")
                if (flags and 0xFFFE != 0) throw LbpaFormatException("未知 flags：$flags")
                val manifestLength = input.readInt()
                if (manifestLength <= 0 || manifestLength > MAX_MANIFEST) {
                    throw LbpaFormatException("manifest 长度非法：$manifestLength")
                }
                val manifestBytes = ByteArray(manifestLength)
                input.readFully(manifestBytes)

                val manifest = try {
                    gson.fromJson(manifestBytes.toString(Charsets.UTF_8), LbpaManifest::class.java)
                } catch (e: JsonSyntaxException) {
                    throw LbpaFormatException("manifest JSON 解析失败：${e.message}")
                } ?: throw LbpaFormatException("manifest 为空")

                validateManifest(manifest, currentLbVersion)
                val payloadSize = manifest.payload?.size ?: 0
                val payloadOffset = 16L + manifestLength
                if (payloadSize <= 0 || payloadOffset + payloadSize > file.length()) {
                    throw LbpaFormatException("payload 长度越界")
                }

                val signed = flags and 0x0001 != 0
                val signatureJson = readSignature(file, payloadOffset, payloadSize, signed)

                return LbpaFile(
                    file = file,
                    manifest = manifest,
                    manifestBytes = manifestBytes,
                    payloadOffset = payloadOffset,
                    payloadSize = payloadSize,
                    signatureJson = signatureJson,
                )
            }
        }

        private fun readSignature(
            file: File,
            payloadOffset: Long,
            payloadSize: Long,
            signed: Boolean,
        ): String? {
            val footerOffset = payloadOffset + payloadSize
            if (!signed) {
                if (footerOffset != file.length()) {
                    throw LbpaFormatException("未签名包尾部存在多余字节")
                }
                return null
            }
            if (footerOffset + 4 > file.length()) throw LbpaFormatException("缺少签名 footer")
            FileInputStream(file).use { stream ->
                skipFully(stream, footerOffset)
                DataInputStream(stream).use { input ->
                    val length = input.readInt()
                    if (length <= 0 || length > MAX_SIGNATURE) {
                        throw LbpaFormatException("签名长度非法：$length")
                    }
                    if (footerOffset + 4 + length != file.length()) {
                        throw LbpaFormatException("签名 footer 长度不匹配")
                    }
                    val bytes = ByteArray(length)
                    input.readFully(bytes)
                    return bytes.toString(Charsets.UTF_8)
                }
            }
        }

        private fun skipFully(stream: InputStream, count: Long) {
            var remaining = count
            while (remaining > 0) {
                val skipped = stream.skip(remaining)
                if (skipped <= 0) throw EOFException("无法定位")
                remaining -= skipped
            }
        }

        fun validateManifest(manifest: LbpaManifest, currentLbVersion: String) {
            if (manifest.format != "LAYERBRIDGEPACKONANDROID") {
                throw LbpaFormatException("format 不匹配")
            }
            if (manifest.formatVersion != 1) {
                throw LbpaFormatException("不支持的格式版本：${manifest.formatVersion}")
            }
            val minVersion = manifest.minLbVersion
            if (!minVersion.isNullOrBlank() && DpkgVersion.compare(currentLbVersion, minVersion) < 0) {
                throw LbpaFormatException("需要 lb >= $minVersion")
            }
            val pkg = manifest.packageInfo ?: throw LbpaFormatException("缺少 package")
            val name = pkg.name.orEmpty()
            if (!PathGuard.isPackageName(name)) throw LbpaFormatException("非法包名：$name")
            if (!DpkgVersion.isValid(pkg.version.orEmpty())) throw LbpaFormatException("非法版本")
            if (pkg.architecture != "aarch64" && pkg.architecture != "all") {
                throw LbpaFormatException("不支持的架构：${pkg.architecture}")
            }
            val payload = manifest.payload ?: throw LbpaFormatException("缺少 payload")
            if (payload.compression != "xz" && payload.compression != "gzip") {
                throw LbpaFormatException("不支持的压缩：${payload.compression}")
            }
            val payloadHash = payload.sha256.orEmpty()
            if (!SHA256_HEX.matches(payloadHash)) throw LbpaFormatException("payload 哈希缺失或非法")
            manifest.scripts?.let { scripts ->
                listOfNotNull(scripts.preinst, scripts.postinst, scripts.prerm, scripts.postrm)
                    .forEach { requireControlPath(it) }
            }
            manifest.hooks?.let { hooks ->
                (hooks.boot.orEmpty() + hooks.install.orEmpty() + hooks.remove.orEmpty())
                    .forEach { requireControlPath(it) }
            }
            manifest.upgrade?.let { upgrade ->
                (upgrade.keep.orEmpty() + upgrade.remove.orEmpty()).forEach {
                    if (!PathGuard.isRelativePath(it)) throw LbpaFormatException("非法升级路径：$it")
                }
                listOfNotNull(upgrade.scripts?.pre, upgrade.scripts?.post).forEach { requireControlPath(it) }
            }
            manifest.expose.orEmpty().forEach {
                if (!PathGuard.isRelativePath(it)) throw LbpaFormatException("非法 expose 路径：$it")
            }
            manifest.env?.forEach { (key, value) ->
                if (!ENV_KEY.matches(key)) throw LbpaFormatException("非法环境变量名：$key")
                if (key in RESERVED_ENV) throw LbpaFormatException("禁止覆盖环境变量：$key")
                if (value.contains('\u0000') || value.contains('\n')) {
                    throw LbpaFormatException("非法环境变量值：$key")
                }
            }
        }

        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
        private val RESERVED_ENV = setOf("PATH", "LD_LIBRARY_PATH", "PREFIX", "HOME", "TMPDIR")

        private fun requireControlPath(path: String) {
            if (!PathGuard.isRelativePath(path) || !path.startsWith("control/")) {
                throw LbpaFormatException("脚本路径必须在 control/ 下：$path")
            }
        }

        private val ENV_KEY = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
    }
}

private class LimitedStream(
    private val delegate: InputStream,
    private val limit: Long,
) : InputStream() {
    private var remaining = limit

    override fun read(): Int {
        if (remaining <= 0) return -1
        val value = delegate.read()
        if (value >= 0) remaining--
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (remaining <= 0) return -1
        val count = delegate.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (count > 0) remaining -= count
        return count
    }

    override fun close() = delegate.close()
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
