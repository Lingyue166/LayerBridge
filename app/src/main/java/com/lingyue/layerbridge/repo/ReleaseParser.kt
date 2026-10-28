package com.lingyue.layerbridge.repo

import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ReleaseInfo(
    val date: Instant?,
    val validUntil: Instant?,
    val hashes: Map<String, String>,
) {
    fun hashFor(path: String): String? = hashes[path]
}

object ReleaseParser {

    private val DATE_FORMAT = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)

    fun parse(text: String): ReleaseInfo {
        val hashes = linkedMapOf<String, String>()
        var date: Instant? = null
        var validUntil: Instant? = null
        var inSha256 = false

        text.replace("\r\n", "\n").split('\n').forEach { line ->
            when {
                line.startsWith("Date:") -> date = parseDate(line.substringAfter(':').trim())
                line.startsWith("Valid-Until:") -> validUntil = parseDate(line.substringAfter(':').trim())
                line == "SHA256:" -> inSha256 = true
                line.isBlank() -> inSha256 = false
                inSha256 -> {
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size >= 3) {
                        val hash = parts[0]
                        val path = parts[2]
                        if (hash.matches(Regex("[0-9a-fA-F]{64}"))) {
                            hashes[path] = hash.lowercase()
                        }
                    }
                }
            }
        }
        return ReleaseInfo(date, validUntil, hashes)
    }

    fun isFresh(date: Instant?, now: Instant = Instant.now(), maxAgeDays: Long = 90): Boolean {
        if (date == null) return false
        return Duration.between(date, now).toDays() <= maxAgeDays
    }

    private fun parseDate(value: String): Instant? = runCatching {
        ZonedDateTime.parse(value, DATE_FORMAT).toInstant()
    }.getOrNull()
}

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
