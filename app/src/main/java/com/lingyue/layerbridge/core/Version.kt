package com.lingyue.layerbridge.core

/**
 * dpkg 版本比较：`[epoch:]upstream[-revision]`。
 * 规则来自 dpkg 的 verrevcmp：`~` 最小，字母 < 非字母，数字段按数值比较。
 */
object DpkgVersion {

    fun compare(left: String, right: String): Int {
        val a = split(left)
        val b = split(right)

        if (a.epoch != b.epoch) return a.epoch.compareTo(b.epoch)

        val upstream = verrevcmp(a.upstream, b.upstream)
        if (upstream != 0) return upstream

        return verrevcmp(a.revision, b.revision)
    }

    fun satisfies(version: String, op: String?, required: String?): Boolean {
        if (op == null || required == null) return true
        val cmp = compare(version, required)
        return when (op) {
            "<<" -> cmp < 0
            "<=" -> cmp <= 0
            "=" -> cmp == 0
            ">=" -> cmp >= 0
            ">>" -> cmp > 0
            "<" -> cmp < 0
            ">" -> cmp > 0
            else -> false
        }
    }

    fun isValid(version: String): Boolean {
        if (version.isBlank()) return false
        if (version.any { it.isWhitespace() }) return false
        val epochPart = version.substringBefore(':', "")
        if (version.contains(':')) {
            if (epochPart.isEmpty() || epochPart.any { !it.isDigit() }) return false
        }
        return true
    }

    private data class Parts(val epoch: Long, val upstream: String, val revision: String)

    private fun split(version: String): Parts {
        var rest = version.trim()
        var epoch = 0L
        val colon = rest.indexOf(':')
        if (colon > 0) {
            val epochText = rest.substring(0, colon)
            if (epochText.all { it.isDigit() }) {
                epoch = epochText.toLongOrNull() ?: 0L
                rest = rest.substring(colon + 1)
            }
        }
        val dash = rest.lastIndexOf('-')
        val upstream = if (dash >= 0) rest.substring(0, dash) else rest
        val revision = if (dash >= 0) rest.substring(dash + 1) else ""
        return Parts(epoch, upstream, revision)
    }

    private fun order(ch: Char): Int = when {
        ch == '~' -> -1
        ch == '\u0000' -> 0
        ch.isLetter() -> ch.code
        else -> ch.code + 256
    }

    private fun verrevcmp(left: String, right: String): Int {
        var i = 0
        var j = 0
        while (i < left.length || j < right.length) {
            while ((i < left.length && !left[i].isDigit()) ||
                (j < right.length && !right[j].isDigit())
            ) {
                val c1 = order(if (i < left.length) left[i] else '\u0000')
                val c2 = order(if (j < right.length) right[j] else '\u0000')
                if (c1 != c2) return c1 - c2
                if (i < left.length) i++
                if (j < right.length) j++
            }
            while (i < left.length && left[i] == '0') i++
            while (j < right.length && right[j] == '0') j++

            var firstDiff = 0
            while (i < left.length && j < right.length &&
                left[i].isDigit() && right[j].isDigit()
            ) {
                if (firstDiff == 0) firstDiff = left[i] - right[j]
                i++
                j++
            }
            if (i < left.length && left[i].isDigit()) return 1
            if (j < right.length && right[j].isDigit()) return -1
            if (firstDiff != 0) return firstDiff
        }
        return 0
    }
}
