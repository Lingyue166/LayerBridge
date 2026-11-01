package com.lingyue.layerbridge.cli

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Standard-style log lines: [2026-01-01T00:00:00Z] [LEVEL] message
 * ASCII only. Levels: INFO / WARN / ERROR.
 */
internal object LbLog {

    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    fun info(message: String) = println(Ansi.line(format("INFO", message, STAMP.format(Instant.now()))))
    fun warn(message: String) = println(Ansi.yellow(format("WARN", message, STAMP.format(Instant.now()))))
    fun error(message: String) = System.err.println(Ansi.red(format("ERROR", message, STAMP.format(Instant.now()))))

    /** Installer progress lines have no explicit level; classify by keyword. */
    fun installer(message: String) {
        val level = when {
            message.contains("failed") || message.contains("error") -> "ERROR"
            message.contains("skipped") || message.contains("unsigned") -> "WARN"
            else -> "INFO"
        }
        val stamped = format(level, message, STAMP.format(Instant.now()))
        when (level) {
            "ERROR" -> System.err.println(Ansi.red(stamped))
            "WARN" -> println(Ansi.yellow(stamped))
            else -> println(Ansi.line(stamped))
        }
    }

    internal fun format(level: String, message: String, timestamp: String = STAMP.format(Instant.now())): String =
        "[$timestamp] [$level] $message"
}
