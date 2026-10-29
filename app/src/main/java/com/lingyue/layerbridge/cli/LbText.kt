package com.lingyue.layerbridge.cli

import java.io.InputStreamReader
import java.util.Locale
import java.util.Properties

internal enum class T {
    HELP,
    USAGE_INSTALL,
    USAGE_REMOVE,
    USAGE_INFO,
    USAGE_VERIFY,
    USAGE_INSPECT,
    USAGE_TRUST,
    USAGE_EXPOSE,
    USAGE_GENERIC,
    ERR_ROOT,
    ERR_NOT_FOUND,
    ERR_UNKNOWN_TYPE,
    ERR_BAD_NAME,
    ERR_PREFIX_INIT,
    ERR_INSTALL_FAILED,
    ERR_UNINSTALL_FAILED,
    ERR_PARSE,
    ERR_TRUST_INVALID,
    ERR_TRUST_WRITE,
    ERR_TRUST_DELETE,
    ERR_NO_TRUST_ROOT,
    SIGN_VALID,
    SIGN_VALIDITY,
    SIGN_INVALID,
    SIGN_UNSIGNED_DEB,
    SIGN_UNSIGNED_LBPA,
    TRUST_EMPTY,
    TRUST_SUBJECT,
    TRUST_KEYID,
    TRUST_VALID,
    TRUST_CA,
    TRUST_IMPORTED,
    TRUST_DELETED,
    PLAN_HEADER,
    PLAN_ITEM,
    MISSING_HEADER,
    CONFLICT_HEADER,
    CYCLE,
    CONFIRM,
    CANCELLED,
    INSTALLING,
    INSTALL_DONE,
    INSTALL_FAILED_ITEM,
    REMOVED,
    UNKNOWN_COMMAND,
    PENDING,
    EXPOSE_ON,
    EXPOSE_OFF,
    NOT_INSTALLED,
    NO_PACKAGES,
    UPGRADE_SINGLE,
    DIR_EMPTY,
    CONTROL_MISSING,
    OPERATION_FAILED,
    INTERACTIVE_HINT,
    UNKNOWN_ARG,
    OVERLAY_MOUNTED,
    OVERLAY_NONE,
    SIGNED_YES,
    SIGNED_NO,
    USAGE_TRUST_IMPORT,
}

/**
 * CLI 文案：语言文件在 app/src/main/resources/i18n/cli_<lang>.properties，
 * 打包进 APK 根目录，由 classloader 读取；缺文件或缺键直接失败。
 */
internal object LbText {

    private const val DIR = "i18n"
    private val cache = mutableMapOf<String, Map<T, String>>()

    private fun languageOf(language: String): String =
        if (language.startsWith("zh")) "zh" else "en"

    fun table(language: String): Map<T, String> {
        val lang = languageOf(language)
        cache[lang]?.let { return it }

        val path = "$DIR/cli_$lang.properties"
        val stream = LbText::class.java.classLoader?.getResourceAsStream(path)
            ?: error("缺少 CLI 语言文件：$path")
        val props = Properties()
        stream.use { props.load(InputStreamReader(it, Charsets.UTF_8)) }

        val map = T.entries.mapNotNull { key -> props.getProperty(key.name)?.let { key to it } }.toMap()
        if (map.size != T.entries.size) {
            val missing = T.entries.filter { it !in map.keys }.joinToString(", ")
            error("CLI 语言文件缺键（$path）：$missing")
        }
        cache[lang] = map
        return map
    }

    fun get(language: String, key: T, vararg args: Any): String {
        val template = table(language)[key] ?: key.name
        return if (args.isEmpty()) template else String.format(template, *args)
    }

    fun current(key: T, vararg args: Any): String = get(Locale.getDefault().language, key, *args)
}
