package com.lingyue.layerbridge.deb

import com.lingyue.layerbridge.repo.ControlParser
import com.lingyue.layerbridge.repo.Stanza
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream
import org.tukaani.xz.XZInputStream

/** .deb 的 control 与 data 访问。 */
class DebFile(val file: File) {

    val archive: ArArchive = ArArchive.parse(file)

    private val controlEntry = archive.entries.firstOrNull { it.name.startsWith("control.tar") }
        ?: throw IllegalArgumentException(".deb 缺少 control.tar")
    private val dataEntry = archive.entries.firstOrNull { it.name.startsWith("data.tar") }
        ?: throw IllegalArgumentException(".deb 缺少 data.tar")

    fun readControl(): DebControl {
        val scripts = mutableMapOf<String, ByteArray>()
        var controlBytes: ByteArray? = null

        controlTarInputStream().use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                val name = entry.name.removePrefix("./")
                if (!entry.isDirectory && entry.size in 0..MAX_CONTROL_FILE) {
                    val bytes = readEntry(tar, entry.size.toInt())
                    when (name) {
                        "control" -> controlBytes = bytes
                        "preinst", "postinst", "prerm", "postrm" -> scripts[name] = bytes
                        else -> if (name.startsWith("lb-hooks/")) scripts[name] = bytes
                    }
                }
                entry = tar.nextEntry
            }
        }

        val stanza = controlBytes
            ?.toString(Charsets.UTF_8)
            ?.let { ControlParser.parse(it).firstOrNull() }
            ?: Stanza(emptyMap())
        return DebControl(stanza, scripts)
    }

    fun controlTarInputStream(): TarArchiveInputStream =
        TarArchiveInputStream(decompress(controlEntry))

    fun dataTarInputStream(): TarArchiveInputStream =
        TarArchiveInputStream(decompress(dataEntry))

    fun dataStream(): InputStream = decompress(dataEntry)

    private fun decompress(entry: ArEntry): InputStream {
        val raw = BufferedInputStream(archive.open(entry))
        return when {
            entry.name.endsWith(".gz") -> GZIPInputStream(raw)
            entry.name.endsWith(".xz") -> XZInputStream(raw)
            entry.name.endsWith(".tar") -> raw
            else -> throw IllegalArgumentException("不支持的压缩格式：${entry.name}")
        }
    }

    private fun parseControl(bytes: ByteArray): Map<String, StringBuilder> {
        val stanza = ControlParser.parse(bytes.toString(Charsets.UTF_8)).firstOrNull()
            ?: return emptyMap()
        return stanza.fields.mapValues { StringBuilder(it.value) }
    }

    private fun readEntry(stream: InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var read = 0
        while (read < size) {
            val count = stream.read(buffer, read, size - read)
            if (count < 0) break
            read += count
        }
        return if (read == size) buffer else buffer.copyOf(read)
    }

    companion object {
        private const val MAX_CONTROL_FILE = 4L * 1024 * 1024

        fun isDeb(file: File): Boolean {
            if (!file.isFile || file.length() < 8) return false
            val head = ByteArray(8)
            file.inputStream().use { it.read(head) }
            return String(head, Charsets.US_ASCII) == "!<arch>\n"
        }

        fun fromBytes(bytes: ByteArray): DebFile {
            val temp = File.createTempFile("lb-deb-", ".deb")
            temp.writeBytes(bytes)
            return DebFile(temp)
        }
    }
}

data class DebControl(
    val stanza: Stanza,
    val scripts: Map<String, ByteArray>,
) {
    val packageName: String? get() = stanza.field("Package")
    val version: String? get() = stanza.field("Version")
    val architecture: String? get() = stanza.field("Architecture")
    val depends: String? get() = stanza.field("Depends")
    val provides: String? get() = stanza.field("Provides")
    val conflicts: String? get() = stanza.field("Conflicts")
    val breaks: String? get() = stanza.field("Breaks")
}
