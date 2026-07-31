package com.xianxia.sect.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortraitPoolTest {

    @Test
    fun `allPortraitNames - returns 37 portraits total`() {
        val names = PortraitPool.allPortraitNames()
        assertEquals(37, names.size)
    }

    @Test
    fun `allPortraitNames - includes all 20 male portraits`() {
        val names = PortraitPool.allPortraitNames()
        for (i in 1..20) {
            assertTrue("male_disciple_$i", "male_disciple_$i" in names)
        }
    }

    @Test
    fun `allPortraitNames - includes all 17 female portraits`() {
        val names = PortraitPool.allPortraitNames()
        for (i in 1..17) {
            assertTrue("female_disciple_$i", "female_disciple_$i" in names)
        }
    }

    @Test
    fun `allPortraitNames - male portraits come before female`() {
        val names = PortraitPool.allPortraitNames()
        val lastMaleIndex = names.indexOfLast { it.startsWith("male_") }
        val firstFemaleIndex = names.indexOfFirst { it.startsWith("female_") }
        assertTrue("male before female", lastMaleIndex < firstFemaleIndex)
    }

    @Test
    fun `allPortraitNames - returns distinct names`() {
        val names = PortraitPool.allPortraitNames()
        assertEquals(names.size, names.toSet().size)
    }

    // ==================== getRandomPortrait（注入式 RNG）====================

    @Test
    fun `getRandomPortrait - 注入nextInt 返回值在池范围内`() {
        val name = PortraitPool.getRandomPortrait("male") { bound ->
            bound - 1
        }
        assertEquals("male_disciple_20", name)
    }

    @Test
    fun `getRandomPortrait - nextInt返回0 取池首`() {
        val name = PortraitPool.getRandomPortrait("male") { 0 }
        assertEquals("male_disciple_1", name)
    }

    @Test
    fun `getRandomPortrait - 女性池边界`() {
        val first = PortraitPool.getRandomPortrait("female") { 0 }
        val last = PortraitPool.getRandomPortrait("female") { bound -> bound - 1 }
        assertEquals("female_disciple_1", first)
        assertEquals("female_disciple_17", last)
    }

    @Test
    fun `getRandomPortrait - 未知性别回退女性池`() {
        val name = PortraitPool.getRandomPortrait("unknown") { 0 }
        assertEquals("female_disciple_1", name)
    }
}
