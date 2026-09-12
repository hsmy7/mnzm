package com.xianxia.sect.core.config

import org.junit.Assert.*
import org.junit.Test

class SectResponseTextsTest {

    @Test
    fun getSectTitle_level0_is道友() {
        assertEquals("道友", SectResponseTexts.getSectTitle(0))
    }

    @Test
    fun getSectTitle_level1_is道友() {
        assertEquals("道友", SectResponseTexts.getSectTitle(1))
    }

    @Test
    fun getSectTitle_level2_is阁下() {
        assertEquals("阁下", SectResponseTexts.getSectTitle(2))
    }

    @Test
    fun getSectTitle_level3_is阁下() {
        assertEquals("阁下", SectResponseTexts.getSectTitle(3))
    }

    @Test
    fun getSectTitle_invalidLevel_defaultsTo道友() {
        assertEquals("道友", SectResponseTexts.getSectTitle(99))
    }

    @Test
    fun getSectSelfTitle_level0_is本门() {
        assertEquals("本门", SectResponseTexts.getSectSelfTitle(0))
    }

    @Test
    fun getSectSelfTitle_level1_is敝宗() {
        assertEquals("敝宗", SectResponseTexts.getSectSelfTitle(1))
    }

    @Test
    fun getSectSelfTitle_level2_is本宗() {
        assertEquals("本宗", SectResponseTexts.getSectSelfTitle(2))
    }

    @Test
    fun getSectSelfTitle_level3_is本宗() {
        assertEquals("本宗", SectResponseTexts.getSectSelfTitle(3))
    }

    @Test
    fun getSectSelfTitle_invalidLevel_defaultsTo本门() {
        assertEquals("本门", SectResponseTexts.getSectSelfTitle(99))
    }

    @Test
    fun getAcceptResponse_returnsNonEmptyString() {
        val result = SectResponseTexts.getAcceptResponse(0, "pill", "丹药", 5)
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun getAcceptResponse_containsSelfTitleReplacement() {
        val result = SectResponseTexts.getAcceptResponse(1, "pill", "丹药", 5)
        val selfTitle = SectResponseTexts.getSectSelfTitle(1)
        assertTrue(result.contains(selfTitle))
        assertFalse(result.contains("{SECT_SELF}"))
    }

    @Test
    fun getAcceptResponse_worksForAllSectLevels() {
        for (level in 0..3) {
            val result = SectResponseTexts.getAcceptResponse(level, "pill", "丹药", 5)
            assertTrue("Accept response empty for level $level", result.isNotEmpty())
            assertFalse("Accept response still has {SECT_SELF} for level $level", result.contains("{SECT_SELF}"))
        }
    }

    @Test
    fun getRejectResponse_returnsNonEmptyString() {
        val result = SectResponseTexts.getRejectResponse(0, "pill", "丹药")
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun getRejectResponse_containsSelfTitleReplacement() {
        val result = SectResponseTexts.getRejectResponse(2, "pill", "丹药")
        val selfTitle = SectResponseTexts.getSectSelfTitle(2)
        assertTrue(result.contains(selfTitle))
        assertFalse(result.contains("{SECT_SELF}"))
    }

    @Test
    fun getRejectResponse_worksForAllSectLevels() {
        for (level in 0..3) {
            val result = SectResponseTexts.getRejectResponse(level, "pill", "丹药")
            assertTrue("Reject response empty for level $level", result.isNotEmpty())
            assertFalse("Reject response still has {SECT_SELF} for level $level", result.contains("{SECT_SELF}"))
        }
    }

    @Test
    fun getAcceptResponse_invalidSectLevel_returnsFallbackString() {
        val result = SectResponseTexts.getAcceptResponse(99, "pill", "丹药", 5)
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun getRejectResponse_invalidSectLevel_returnsFallbackString() {
        val result = SectResponseTexts.getRejectResponse(99, "pill", "丹药")
        assertTrue(result.isNotEmpty())
    }
}
