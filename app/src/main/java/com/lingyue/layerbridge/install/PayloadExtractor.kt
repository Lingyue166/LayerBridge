package com.lingyue.layerbridge.install

import com.lingyue.layerbridge.core.LbPaths
import com.lingyue.layerbridge.core.PathGuard
import com.lingyue.layerbridge.core.Shell
import com.lingyue.layerbridge.deb.DebFile
import com.lingyue.layerbridge.deb.ShebangRelocator
import com.lingyue.layerbridge.format.LbpaFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.tukaani.xz.XZInputStream
import java.util.zip.GZIPInputStream

/**
 * 包载荷解包：只负责把归档条目安全地写进前缀。
 * 不做签名、脚本、状态等决定，保留给 [LinuxInstaller] 编排。
 */
object PayloadExtractor {

    private const val MAX_REWRITE = 1L * 1024 * 1024

    suspend fun extractDeb(deb: DebFile, onLog: (String) -> Unit): List<String> =
        extract(onLog) { tar, paths ->
            deb.dataTarInputStream().use { input ->
                copyEntries(input, tar, paths) { normalize(it) }
            }
        }

    suspend fun extractLbpa(lbpa: LbpaFile, onLog: (String) -> Unit): List<String> =
        extract(onLog) { tar, paths ->
            payloadTar(lbpa).use { input ->
                copyEntries(input, tar, paths) { raw ->
                    val name = raw.removePrefix("./")
                    when {
                        name.startsWith("control/") || name == "control" -> null
                        name.startsWith("files/") -> normalize(name.removePrefix("files/"))
                        else -> error("未知 payload 路径：$raw")
                    }
                }
            }
        }

    /** 读出 LBPA 的 control/ 脚本，供签名扫描与维护脚本执行使用。 */
    fun readLbpaControl(lbpa: LbpaFile): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        payloadTar(lbpa).use { tar ->
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

    private suspend fun extract(
        onLog: (String) -> Unit,
        body: (TarArchiveOutputStream, MutableList<String>) -> Unit,
    ): List<String> {
        val paths = mutableListOf<String>()
        val result = Shell.extractTar(LbPaths.PREFIX) { output ->
            TarArchiveOutputStream(output).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                body(tar, paths)
            }
        }
        if (!result.isSuccess) error("解包失败：${result.output}")
        onLog("[lb] 解包完成：${paths.size} 个文件")
        return paths
    }

    private fun copyEntries(
        input: TarArchiveInputStream,
        tar: TarArchiveOutputStream,
        paths: MutableList<String>,
        pathOf: (String) -> String?,
    ) {
        var entry = input.nextEntry
        while (entry != null) {
            val path = pathOf(entry.name)
            if (path != null) copyEntry(tar, input, entry, path, paths)
            entry = input.nextEntry
        }
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
                    val rewritten = ShebangRelocator.rewrite(bytes, LbPaths.PREFIX)
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

    private fun payloadTar(lbpa: LbpaFile): TarArchiveInputStream {
        val compression = lbpa.manifest.payload?.compression ?: "xz"
        val input = when (compression) {
            "xz" -> XZInputStream(lbpa.payloadStream())
            "gzip" -> GZIPInputStream(lbpa.payloadStream())
            else -> error("不支持的压缩：$compression")
        }
        return TarArchiveInputStream(input)
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
}
