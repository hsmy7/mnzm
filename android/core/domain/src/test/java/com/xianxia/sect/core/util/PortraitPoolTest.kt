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
}
