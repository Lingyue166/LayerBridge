package com.lingyue.layerbridge.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocaleHelperTest {

    @Test
    fun systemAndBlankMeanNoOverride() {
        assertNull(LocaleHelper.localeFor(LocaleHelper.SYSTEM))
        assertNull(LocaleHelper.localeFor(""))
    }

    @Test
    fun parsesLanguageTags() {
        assertEquals("zh", LocaleHelper.localeFor("zh")?.language)
        assertEquals("zh", LocaleHelper.localeFor("zh-CN")?.language)
        assertEquals("en", LocaleHelper.localeFor("en")?.language)
        assertNull(LocaleHelper.localeFor("---"))
    }
}
