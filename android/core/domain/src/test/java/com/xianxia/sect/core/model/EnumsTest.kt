package com.xianxia.sect.core.model

import org.junit.Assert.*
import org.junit.Test

class EnumsTest {

    // ---- GiftPreferenceType ----

    @Test
    fun giftPreferenceType_hasFiveValues() {
        assertEquals(5, GiftPreferenceType.entries.size)
    }

    @Test
    fun giftPreferenceType_values() {
        val expected = arrayOf(
            GiftPreferenceType.NONE,
            GiftPreferenceType.EQUIPMENT,
            GiftPreferenceType.MANUAL,
            GiftPreferenceType.PILL,
            GiftPreferenceType.SPIRIT_STONE
        )
        assertArrayEquals(expected, GiftPreferenceType.entries.toTypedArray())
    }

    @Test
    fun giftPreferenceType_displayNames() {
        assertEquals("无", GiftPreferenceType.NONE.displayName)
        assertEquals("装备", GiftPreferenceType.EQUIPMENT.displayName)
        assertEquals("功法", GiftPreferenceType.MANUAL.displayName)
        assertEquals("丹药", GiftPreferenceType.PILL.displayName)
        assertEquals("灵石", GiftPreferenceType.SPIRIT_STONE.displayName)
    }

    // ---- MaterialChecker ----

    @Test
    fun materialChecker_hasEnoughMaterials_returnsTrueWhenSufficient() {
        val checker = object : MaterialChecker {
            override val requiredMaterials = mapOf("Bone" to 3, "Hide" to 1)
        }
        val materials = listOf(
            Material(name = "Bone", quantity = 5),
            Material(name = "Hide", quantity = 2)
        )
        assertTrue(checker.hasEnoughMaterials(materials))
    }

    @Test
    fun materialChecker_hasEnoughMaterials_returnsFalseWhenInsufficient() {
        val checker = object : MaterialChecker {
            override val requiredMaterials = mapOf("Bone" to 5)
        }
        val materials = listOf(
            Material(name = "Bone", quantity = 3)
        )
        assertFalse(checker.hasEnoughMaterials(materials))
    }

    @Test
    fun materialChecker_hasEnoughMaterials_returnsFalseWhenMissing() {
        val checker = object : MaterialChecker {
            override val requiredMaterials = mapOf("Bone" to 1, "Hide" to 1)
        }
        val materials = listOf(
            Material(name = "Bone", quantity = 5)
        )
        assertFalse(checker.hasEnoughMaterials(materials))
    }

    @Test
    fun materialChecker_hasEnoughMaterials_returnsTrueWhenEmpty() {
        val checker = object : MaterialChecker {
            override val requiredMaterials = emptyMap<String, Int>()
        }
        assertTrue(checker.hasEnoughMaterials(emptyList()))
    }

    @Test
    fun materialChecker_hasEnoughMaterials_aggregatesMultipleStacks() {
        val checker = object : MaterialChecker {
            override val requiredMaterials = mapOf("Bone" to 5)
        }
        val materials = listOf(
            Material(name = "Bone", quantity = 3),
            Material(name = "Bone", quantity = 3)
        )
        assertTrue(checker.hasEnoughMaterials(materials))
    }

    @Test
    fun materialChecker_getMissingMaterials_returnsEmptyWhenSufficient() {
        val checker = object : MaterialChecker {
            override val requiredMaterials = mapOf("Bone" to 2)
        }
        val materials = listOf(Material(name = "Bone", quantity = 5))
        assertTrue(checker.getMissingMaterials(materials).isEmpty())
    }

    @Test
    fun materialChecker_getMissingMaterials_returnsCorrectDeficit() {
        val checker = object : MaterialChecker {
            override val requiredMaterials = mapOf("Bone" to 5, "Hide" to 3)
        }
        val materials = listOf(
            Material(name = "Bone", quantity = 2),
            Material(name = "Hide", quantity = 1)
        )
        val missing = checker.getMissingMaterials(materials)
        assertEquals(2, missing.size)
        assertEquals("Bone" to 3, missing[0])
        assertEquals("Hide" to 2, missing[1])
    }
}
