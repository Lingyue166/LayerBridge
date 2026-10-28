package com.lingyue.layerbridge.scan

import java.io.File

enum class Severity { WARN, BLOCK }

data class ScanFinding(
    val rule: String,
    val severity: Severity,
    val file: String,
    val line: Int?,
    val snippet: String,
)

data class ScanReport(val findings: List<ScanFinding>) {
    val blocked: Boolean get() = findings.any { it.severity == Severity.BLOCK }
    val hasWarnings: Boolean get() = findings.any { it.severity == Severity.WARN }
}

object PackageScanner {

    private data class Rule(val name: String, val severity: Severity, val pattern: Regex)

    private val RULES = listOf(
        Rule(
            "recursive-root-delete",
            Severity.BLOCK,
            Regex("""rm\s+-[a-zA-Z]*[rR][a-zA-Z]*\s+/(\s|$|\*)""", RegexOption.IGNORE_CASE),
        ),
        Rule(
            "system-write",
            Severity.BLOCK,
            Regex("""(>\s*/system|cp\s+[^;|&]*\s+/system|rm\s+-[a-zA-Z]*\s+/system)""", RegexOption.IGNORE_CASE),
        ),
        Rule(
            "block-device-write",
            Severity.BLOCK,
            Regex("""dd\s+[^;|&]*of=/dev/block""", RegexOption.IGNORE_CASE),
        ),
        Rule(
            "filesystem-format",
            Severity.BLOCK,
            Regex("""\b(mkfs(\.\w+)?|mke2fs)\b""", RegexOption.IGNORE_CASE),
        ),
        Rule(
            "wipe-data",
            Severity.BLOCK,
            Regex("""\b(wipe\s+data|fastboot\s+erase|reboot\s+recovery)\b""", RegexOption.IGNORE_CASE),
        ),
        Rule(
            "selinux-disable",
            Severity.BLOCK,
            Regex("""\bsetenforce\s+0\b|\bsetprop\s+ro\.secure\s+0\b""", RegexOption.IGNORE_CASE),
        ),
        Rule(
            "root-manager-tamper",
            Severity.BLOCK,
            Regex("""/data/adb/(modules|ksu|magisk)\b""", RegexOption.IGNORE_CASE),
        ),
        Rule(
            "proc-sysrq",
            Severity.BLOCK,
            Regex("""/proc/sysrq-trigger""", RegexOption.IGNORE_CASE),
        ),
        Rule(
            "download-Execute",
            Severity.WARN,
            Regex("""(curl|wget)[^;|&]*\|\s*(sh|bash)""", RegexOption.IGNORE_CASE),
        ),
        Rule(
            "base64-shell",
            Severity.WARN,
            Regex("""base64\s+(-d|--decode)[^;|&]*\|\s*(sh|bash)""", RegexOption.IGNORE_CASE),
        ),
        Rule(
            "eval-obfuscation",
            Severity.WARN,
            Regex("""\beval\b""", RegexOption.IGNORE_CASE),
        ),
        Rule(
            "world-writable-root",
            Severity.WARN,
            Regex("""chmod\s+-R\s+777\s+/(\s|$)""", RegexOption.IGNORE_CASE),
        ),
        Rule(
            "su-in-script",
            Severity.WARN,
            Regex("""(^|\s)su(\s|-c)""", RegexOption.IGNORE_CASE),
        ),
        Rule(
            "remount-rw",
            Severity.WARN,
            Regex("""mount[^;|&]*remount[^;|&]*rw""", RegexOption.IGNORE_CASE),
        ),
    )

    private val TEXT_EXTENSIONS = setOf("sh", "bash", "py", "pl", "rb", "js")

    fun scanDirectory(root: File, maxFileSize: Long = 2L * 1024 * 1024): ScanReport {
        val findings = mutableListOf<ScanFinding>()
        root.walkTopDown()
            .filter { it.isFile }
            .take(20_000)
            .forEach { file ->
                if (file.length() > maxFileSize) return@forEach
                val relative = file.relativeTo(root).path.replace('\\', '/')
                val extension = file.extension.lowercase()
                val bytes = file.readBytes()
                val textual = extension in TEXT_EXTENSIONS || startsWithShebang(bytes)
                if (textual) {
                    scanText(relative, bytes.toString(Charsets.UTF_8), findings)
                } else {
                    scanStrings(relative, bytes, findings)
                }
            }
        return ScanReport(findings)
    }

    fun scanText(fileName: String, text: String, findings: MutableList<ScanFinding> = mutableListOf()): ScanReport {
        text.replace("\r\n", "\n").split('\n').forEachIndexed { index, line ->
            RULES.forEach { rule ->
                if (rule.pattern.containsMatchIn(line)) {
                    findings += ScanFinding(
                        rule = rule.name,
                        severity = rule.severity,
                        file = fileName,
                        line = index + 1,
                        snippet = line.trim().take(200),
                    )
                }
            }
        }
        return ScanReport(findings)
    }

    private fun scanStrings(fileName: String, bytes: ByteArray, findings: MutableList<ScanFinding>) {
        val text = bytes.toString(Charsets.ISO_8859_1)
        val dangerous = listOf("/dev/block", "/proc/sysrq-trigger", "/data/adb/modules", "/data/adb/ksu")
        dangerous.forEach { needle ->
            if (text.contains(needle)) {
                findings += ScanFinding(
                    rule = "dangerous-binary-string",
                    severity = Severity.WARN,
                    file = fileName,
                    line = null,
                    snippet = needle,
                )
            }
        }
        if (text.contains("rm -rf /") || text.contains("mkfs.")) {
            findings += ScanFinding(
                rule = "dangerous-binary-string",
                severity = Severity.BLOCK,
                file = fileName,
                line = null,
                snippet = "binary contains destructive string",
            )
        }
    }

    private fun startsWithShebang(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == '#'.code.toByte() && bytes[1] == '!'.code.toByte()
}
