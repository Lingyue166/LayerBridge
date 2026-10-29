package com.lingyue.layerbridge.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import java.util.Locale

/**
 * 应用内语言：存 SharedPreferences，切换后重建 Activity。
 * "system" 表示跟随系统，其余为 BCP 47 标签（zh / en）。
 */
object LocaleHelper {

    private const val PREFS = "lb_settings"
    private const val KEY_LANGUAGE = "language"
    const val SYSTEM = "system"

    fun storedTag(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, SYSTEM) ?: SYSTEM

    fun save(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANGUAGE, tag).apply()
    }

    fun localeFor(tag: String): Locale? =
        if (tag.isBlank() || tag == SYSTEM) null
        else Locale.forLanguageTag(tag).takeIf { it.language.isNotEmpty() }

    /** 从包装链里找 Activity，用于 recreate。 */
    fun activityOf(context: Context): Activity? {
        var current: Context = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    /** 按偏好包装 Context；跟随系统时原样返回。 */
    fun wrap(base: Context): Context {
        val locale = localeFor(storedTag(base)) ?: return base
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}
