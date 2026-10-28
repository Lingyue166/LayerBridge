package com.lingyue.layerbridge.deb

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

data class ArEntry(val name: String, val offset: Long, val size: Long)

/** 极简 GNU ar 解析器，用于 .deb。 */
class ArArchive private constructor(
    val file: File,
    val entries: List<ArEntry>,
) {

    fun entry(name: String): ArEntry? = entries.firstOrNull { it.name == name }

    fun open(entry: ArEntry): InputStream {
        val stream = FileInputStream(file)
        stream.skipFully(entry.offset)
        return LimitedInputStream(stream, entry.size)
    }

    companion object {
        private const val MAGIC = "!<arch>\n"
        private const val HEADER_SIZE = 60

        @Throws(IOException::class)
        fun parse(file: File): ArArchive {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 8) throw IOException("ar 文件过短")
                val magic = ByteArray(8)
                raf.readFully(magic)
                if (String(magic, Charsets.US_ASCII) != MAGIC) throw IOException("不是 ar 归档")

                val entries = mutableListOf<ArEntry>()
                var cursor = 8L
                val length = raf.length()
                while (cursor + HEADER_SIZE <= length) {
                    raf.seek(cursor)
                    val header = ByteArray(HEADER_SIZE)
                    raf.readFully(header)
                    val name = String(header, 0, 16, Charsets.US_ASCII).trim().trimEnd('/')
                    val sizeText = String(header, 48, 10, Charsets.US_ASCII).trim()
                    if (name.isEmpty()) break
                    val size = sizeText.toLongOrNull()
                        ?: throw IOException("ar 条目大小非法：$sizeText")
                    if (size < 0) throw IOException("ar 条目大小为负")
                    val dataOffset = cursor + HEADER_SIZE
                    if (dataOffset + size > length) throw IOException("ar 条目越界：$name")

                    entries += ArEntry(name, dataOffset, size)
                    cursor = dataOffset + size + (size % 2)
                }
                if (entries.isEmpty()) throw IOException("ar 归档为空")
                return ArArchive(file, entries)
            }
        }

        private fun FileInputStream.skipFully(count: Long) {
            var remaining = count
            while (remaining > 0) {
                val skipped = skip(remaining)
                if (skipped <= 0) throw IOException("无法定位到 ar 条目")
                remaining -= skipped
            }
        }
    }
}

private class LimitedInputStream(
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
        val toRead = minOf(length.toLong(), remaining).toInt()
        val count = delegate.read(buffer, offset, toRead)
        if (count > 0) remaining -= count
        return count
    }

    override fun close() = delegate.close()
}
