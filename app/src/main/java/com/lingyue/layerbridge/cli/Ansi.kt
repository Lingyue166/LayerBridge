package com.lingyue.layerbridge.cli

import java.nio.file.Files
import java.nio.file.Paths

/**
 * 终端颜色。默认只在 stdout 指向 pty 时开启；
 * LB_COLOR=always/never 可强制，LB_COLOR=auto 或不设走探测。
 */
internal object Ansi {

    private val enabled: Boolean by lazy { detect() }

    fun red(text: String) = wrap(enabled, text, 31)
    fun green(text: String) = wrap(enabled, text, 32)
    fun yellow(text: String) = wrap(enabled, text, 33)
    fun cyan(text: String) = wrap(enabled, text, 36)
    fun dim(text: String) = wrap(enabled, text, 2)

    /** 按关键字给一行日志上色。 */
    fun line(text: String): String = when {
        text.contains("failed") || text.contains("error") -> red(text)
        text.contains("installed") || text.contains("done") || text.contains("complete") -> green(text)
        text.contains("skipped") || text.contains("unsigned") || text.contains("warning") -> yellow(text)
        text.startsWith("[lb]   ") -> dim(text)
        else -> text
    }

    internal fun wrap(on: Boolean, text: String, code: Int): String =
        if (on) "\u001B[${code}m$text\u001B[0m" else text

    private fun detect(): Boolean {
        val mode = System.getenv("LB_COLOR")?.lowercase()
        if (mode == "always" || mode == "1") return true
        if (mode == "never" || mode == "0") return false
        val term = System.getenv("TERM") ?: return false
        if (term == "dumb") return false
        val fd1 = runCatching {
            Files.readSymbolicLink(Paths.get("/proc/self/fd/1")).toString()
        }.getOrNull() ?: return false
        return fd1.startsWith("/dev/pts") || fd1.startsWith("/dev/tty")
    }
}
