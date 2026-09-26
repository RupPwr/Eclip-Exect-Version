package com.eclip.rup.leb

import com.eclip.rup.leb.utils.FontManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FontManagerTest {

    @Test
    fun testFontCategoriesDefined() {
        val categories = FontManager.CATEGORIES
        assertTrue(categories.contains(FontManager.CATEGORY_ALL))
        assertTrue(categories.contains(FontManager.CATEGORY_SANS))
        assertTrue(categories.contains(FontManager.CATEGORY_SERIF))
        assertTrue(categories.contains(FontManager.CATEGORY_SCRIPT))
        assertTrue(categories.contains(FontManager.CATEGORY_DISPLAY))
        assertTrue(categories.contains(FontManager.CATEGORY_CUSTOM))
    }
}
