package com.lingyue.layerbridge.repo

/** Debian 控制文件中的一段（RFC822 风格）。 */
data class Stanza(val fields: Map<String, String>) {

    fun field(name: String): String? = fields.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }
        ?.value

    fun fieldsStartingWith(prefix: String): Map<String, String> = fields.filterKeys {
        it.startsWith(prefix, ignoreCase = true)
    }
}

object ControlParser {

    fun parse(text: String): List<Stanza> {
        val stanzas = mutableListOf<Stanza>()
        var current = LinkedHashMap<String, StringBuilder>()
        var lastKey: String? = null

        fun flush() {
            if (current.isNotEmpty()) {
                stanzas += Stanza(current.mapValues { it.value.toString() })
                current = LinkedHashMap()
                lastKey = null
            }
        }

        text.replace("\r\n", "\n").split('\n').forEach { rawLine ->
            val line = rawLine.trimEnd()
            when {
                line.isBlank() -> flush()

                (line.startsWith(' ') || line.startsWith('\t')) && lastKey != null -> {
                    current.getValue(lastKey!!).append('\n').append(line.trim())
                }

                else -> {
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        val key = line.substring(0, colon).trim()
                        val value = line.substring(colon + 1).trim()
                        current[key] = StringBuilder(value)
                        lastKey = key
                    }
                }
            }
        }
        flush()
        return stanzas
    }
}
