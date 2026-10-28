package com.lingyue.layerbridge.deb

object ShebangRelocator {

    const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
    private const val MAX_SCAN = 1 shl 20

    /**
     * 只处理首行 shebang；把 Termux 前缀替换为目标前缀。
     * 文件过大或不是脚本时原样返回。
     */
    fun rewrite(bytes: ByteArray, targetPrefix: String): ByteArray {
        if (bytes.size < 2 || bytes[0] != '#'.code.toByte() || bytes[1] != '!'.code.toByte()) {
            return bytes
        }
        if (bytes.size > MAX_SCAN) return bytes
        val newline = bytes.indexOf('\n'.code.toByte()).let { if (it < 0) bytes.size else it }
        val firstLine = String(bytes, 0, newline, Charsets.UTF_8)
        if (!firstLine.contains(TERMUX_PREFIX)) return bytes
        val replaced = firstLine.replace(TERMUX_PREFIX, targetPrefix).toByteArray(Charsets.UTF_8)
        return replaced + bytes.copyOfRange(newline, bytes.size)
    }

    private fun ByteArray.indexOf(value: Byte): Int {
        for (i in indices) if (this[i] == value) return i
        return -1
    }
}
