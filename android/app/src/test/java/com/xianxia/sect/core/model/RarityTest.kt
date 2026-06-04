package com.xianxia.sect.core.model

import org.junit.Assert.*
import org.junit.Test

class RarityTest {

    // ==================== Companion Object 常量 ====================

    @Test
    fun commonValueIs1() {
        assertEquals(1, Rarity.COMMON.value)
    }

    @Test
    fun uncommonValueIs2() {
        assertEquals(2, Rarity.UNCOMMON.value)
    }

    @Test
    fun rareValueIs3() {
        assertEquals(3, Rarity.RARE.value)
    }

    @Test
    fun epicValueIs4() {
        assertEquals(4, Rarity.EPIC.value)
    }

    @Test
    fun legendaryValueIs5() {
        assertEquals(5, Rarity.LEGENDARY.value)
    }

    @Test
    fun mythicValueIs6() {
        assertEquals(6, Rarity.MYTHIC.value)
    }

    // ==================== color 属性 ====================

    @Test
    fun colorCommon() {
        assertEquals("#9E9E9E", Rarity.COMMON.color)
    }

    @Test
    fun colorUncommon() {
        assertEquals("#4CAF50", Rarity.UNCOMMON.color)
    }

    @Test
    fun colorRare() {
        assertEquals("#2196F3", Rarity.RARE.color)
    }

    @Test
    fun colorEpic() {
        assertEquals("#9C27B0", Rarity.EPIC.color)
    }

    @Test
    fun colorLegendary() {
        assertEquals("#FF9800", Rarity.LEGENDARY.color)
    }

    @Test
    fun colorMythic() {
        assertEquals("#E91E63", Rarity.MYTHIC.color)
    }

    // ==================== name 属性 ====================

    @Test
    fun nameCommon() {
        assertEquals("普通", Rarity.COMMON.name)
    }

    @Test
    fun nameUncommon() {
        assertEquals("优秀", Rarity.UNCOMMON.name)
    }

    @Test
    fun nameRare() {
        assertEquals("稀有", Rarity.RARE.name)
    }

    @Test
    fun nameEpic() {
        assertEquals("史诗", Rarity.EPIC.name)
    }

    @Test
    fun nameLegendary() {
        assertEquals("传说", Rarity.LEGENDARY.name)
    }

    @Test
    fun nameMythic() {
        assertEquals("神话", Rarity.MYTHIC.name)
    }

    // ==================== 布尔属性 ====================

    @Test
    fun isCommon() {
        assertTrue(Rarity.COMMON.isCommon)
        assertFalse(Rarity.UNCOMMON.isCommon)
        assertFalse(Rarity.RARE.isCommon)
        assertFalse(Rarity.EPIC.isCommon)
        assertFalse(Rarity.LEGENDARY.isCommon)
        assertFalse(Rarity.MYTHIC.isCommon)
    }

    @Test
    fun isUncommon() {
        assertTrue(Rarity.UNCOMMON.isUncommon)
        assertFalse(Rarity.COMMON.isUncommon)
        assertFalse(Rarity.RARE.isUncommon)
    }

    @Test
    fun isRare() {
        assertTrue(Rarity.RARE.isRare)
        assertFalse(Rarity.COMMON.isRare)
        assertFalse(Rarity.EPIC.isRare)
    }

    @Test
    fun isEpic() {
        assertTrue(Rarity.EPIC.isEpic)
        assertFalse(Rarity.COMMON.isEpic)
        assertFalse(Rarity.LEGENDARY.isEpic)
    }

    @Test
    fun isLegendary() {
        assertTrue(Rarity.LEGENDARY.isLegendary)
        assertFalse(Rarity.COMMON.isLegendary)
        assertFalse(Rarity.MYTHIC.isLegendary)
    }

    @Test
    fun isMythic() {
        assertTrue(Rarity.MYTHIC.isMythic)
        assertFalse(Rarity.COMMON.isMythic)
        assertFalse(Rarity.LEGENDARY.isMythic)
    }

    @Test
    fun isAtLeastRare() {
        assertTrue(Rarity.RARE.isAtLeastRare)
        assertTrue(Rarity.EPIC.isAtLeastRare)
        assertTrue(Rarity.LEGENDARY.isAtLeastRare)
        assertTrue(Rarity.MYTHIC.isAtLeastRare)
        assertFalse(Rarity.COMMON.isAtLeastRare)
        assertFalse(Rarity.UNCOMMON.isAtLeastRare)
    }

    @Test
    fun isAtLeastEpic() {
        assertTrue(Rarity.EPIC.isAtLeastEpic)
        assertTrue(Rarity.LEGENDARY.isAtLeastEpic)
        assertTrue(Rarity.MYTHIC.isAtLeastEpic)
        assertFalse(Rarity.COMMON.isAtLeastEpic)
        assertFalse(Rarity.UNCOMMON.isAtLeastEpic)
        assertFalse(Rarity.RARE.isAtLeastEpic)
    }

    @Test
    fun isAtLeastLegendary() {
        assertTrue(Rarity.LEGENDARY.isAtLeastLegendary)
        assertTrue(Rarity.MYTHIC.isAtLeastLegendary)
        assertFalse(Rarity.COMMON.isAtLeastLegendary)
        assertFalse(Rarity.UNCOMMON.isAtLeastLegendary)
        assertFalse(Rarity.RARE.isAtLeastLegendary)
        assertFalse(Rarity.EPIC.isAtLeastLegendary)
    }

    // ==================== fromInt ====================

    @Test
    fun fromInt_validValues_returnSuccess() {
        for (i in 1..6) {
            val result = Rarity.fromInt(i)
            assertTrue("fromInt($i) should succeed", result.isSuccess)
            assertEquals(i, result.getOrNull()!!.value)
        }
    }

    @Test
    fun fromInt_0_returnsFailure() {
        val result = Rarity.fromInt(0)
        assertTrue("fromInt(0) should fail", result.isFailure)
    }

    @Test
    fun fromInt_7_returnsFailure() {
        val result = Rarity.fromInt(7)
        assertTrue("fromInt(7) should fail", result.isFailure)
    }

    @Test
    fun fromInt_negative1_returnsFailure() {
        val result = Rarity.fromInt(-1)
        assertTrue("fromInt(-1) should fail", result.isFailure)
    }

    @Test
    fun fromInt_100_returnsFailure() {
        val result = Rarity.fromInt(100)
        assertTrue("fromInt(100) should fail", result.isFailure)
    }

    @Test
    fun fromInt_failureContainsException() {
        val result = Rarity.fromInt(0)
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(exception is IllegalArgumentException)
        assertTrue(exception!!.message!!.contains("0"))
    }

    // ==================== unsafe ====================

    @Test
    fun unsafe_validValues_returnSame() {
        for (i in 1..6) {
            assertEquals(i, Rarity.unsafe(i).value)
        }
    }

    @Test
    fun unsafe_0_coercedTo1() {
        assertEquals(1, Rarity.unsafe(0).value)
    }

    @Test
    fun unsafe_100_coercedTo6() {
        assertEquals(6, Rarity.unsafe(100).value)
    }

    @Test
    fun unsafe_negative1_coercedTo1() {
        assertEquals(1, Rarity.unsafe(-1).value)
    }

    @Test
    fun unsafe_7_coercedTo6() {
        assertEquals(6, Rarity.unsafe(7).value)
    }

    // ==================== isValid ====================

    @Test
    fun isValid_1through6_returnsTrue() {
        for (i in 1..6) {
            assertTrue("isValid($i) should be true", Rarity.isValid(i))
        }
    }

    @Test
    fun isValid_0_returnsFalse() {
        assertFalse(Rarity.isValid(0))
    }

    @Test
    fun isValid_7_returnsFalse() {
        assertFalse(Rarity.isValid(7))
    }

    @Test
    fun isValid_negative1_returnsFalse() {
        assertFalse(Rarity.isValid(-1))
    }

    // ==================== all ====================

    @Test
    fun all_returns6Rarities() {
        val all = Rarity.all()
        assertEquals(6, all.size)
    }

    @Test
    fun all_returnsRaritiesInOrder() {
        val all = Rarity.all()
        for (i in all.indices) {
            assertEquals(i + 1, all[i].value)
        }
    }

    @Test
    fun all_containsAllConstants() {
        val all = Rarity.all()
        assertEquals(Rarity.COMMON.value, all[0].value)
        assertEquals(Rarity.UNCOMMON.value, all[1].value)
        assertEquals(Rarity.RARE.value, all[2].value)
        assertEquals(Rarity.EPIC.value, all[3].value)
        assertEquals(Rarity.LEGENDARY.value, all[4].value)
        assertEquals(Rarity.MYTHIC.value, all[5].value)
    }

    // ==================== compareTo ====================

    @Test
    fun compareTo_equal() {
        assertEquals(0, Rarity.COMMON.compareTo(Rarity.COMMON))
        assertEquals(0, Rarity.MYTHIC.compareTo(Rarity.MYTHIC))
    }

    @Test
    fun compareTo_lessThan() {
        assertTrue(Rarity.COMMON.compareTo(Rarity.UNCOMMON) < 0)
        assertTrue(Rarity.RARE.compareTo(Rarity.MYTHIC) < 0)
    }

    @Test
    fun compareTo_greaterThan() {
        assertTrue(Rarity.UNCOMMON.compareTo(Rarity.COMMON) > 0)
        assertTrue(Rarity.MYTHIC.compareTo(Rarity.RARE) > 0)
    }

    // ==================== plus / minus ====================

    @Test
    fun plus_normalIncrement() {
        assertEquals(2, (Rarity.COMMON + 1).value)
        assertEquals(4, (Rarity.RARE + 1).value)
        assertEquals(5, (Rarity.EPIC + 1).value)
    }

    @Test
    fun plus_overflowCoercedTo6() {
        assertEquals(6, (Rarity.MYTHIC + 1).value)
        assertEquals(6, (Rarity.MYTHIC + 100).value)
    }

    @Test
    fun minus_normalDecrement() {
        assertEquals(1, (Rarity.UNCOMMON - 1).value)
        assertEquals(3, (Rarity.EPIC - 1).value)
        assertEquals(5, (Rarity.MYTHIC - 1).value)
    }

    @Test
    fun minus_underflowCoercedTo1() {
        assertEquals(1, (Rarity.COMMON - 1).value)
        assertEquals(1, (Rarity.COMMON - 100).value)
    }

    @Test
    fun plusZero_noChange() {
        assertEquals(3, (Rarity.RARE + 0).value)
    }

    @Test
    fun minusZero_noChange() {
        assertEquals(3, (Rarity.RARE - 0).value)
    }

    @Test
    fun plus_largeDelta_coercedTo6() {
        assertEquals(6, (Rarity.COMMON + 999).value)
    }

    @Test
    fun minus_largeDelta_coercedTo1() {
        assertEquals(1, (Rarity.MYTHIC - 999).value)
    }

    // ==================== rangeTo / RarityRange ====================

    @Test
    fun rangeTo_createsRarityRange() {
        val range = Rarity.COMMON..Rarity.RARE
        assertTrue(range is RarityRange)
    }

    @Test
    fun rangeTo_startAndEnd() {
        val range = Rarity.UNCOMMON..Rarity.EPIC
        assertEquals(Rarity.UNCOMMON.value, range.start.value)
        assertEquals(Rarity.EPIC.value, range.endInclusive.value)
    }

    @Test
    fun rarityRange_toList() {
        val range = (Rarity.COMMON..Rarity.RARE) as RarityRange
        val list = range.toList()
        assertEquals(3, list.size)
        assertEquals(1, list[0].value)
        assertEquals(2, list[1].value)
        assertEquals(3, list[2].value)
    }

    @Test
    fun rarityRange_toList_singleElement() {
        val range = (Rarity.EPIC..Rarity.EPIC) as RarityRange
        val list = range.toList()
        assertEquals(1, list.size)
        assertEquals(4, list[0].value)
    }

    @Test
    fun rarityRange_toList_fullRange() {
        val range = (Rarity.COMMON..Rarity.MYTHIC) as RarityRange
        val list = range.toList()
        assertEquals(6, list.size)
    }

    @Test
    fun rarityRange_containsInt_inRange() {
        val range = (Rarity.UNCOMMON..Rarity.LEGENDARY) as RarityRange
        assertTrue(range.contains(2))
        assertTrue(range.contains(3))
        assertTrue(range.contains(4))
        assertTrue(range.contains(5))
    }

    @Test
    fun rarityRange_containsInt_outOfRange() {
        val range = (Rarity.UNCOMMON..Rarity.LEGENDARY) as RarityRange
        assertFalse(range.contains(1))
        assertFalse(range.contains(6))
        assertFalse(range.contains(0))
        assertFalse(range.contains(100))
    }

    @Test
    fun rarityRange_containsInt_boundaryValues() {
        val range = (Rarity.RARE..Rarity.EPIC) as RarityRange
        assertTrue(range.contains(3))
        assertTrue(range.contains(4))
        assertFalse(range.contains(2))
        assertFalse(range.contains(5))
    }

    // ==================== isBetterThan / isWorseThan ====================

    @Test
    fun isBetterThan_higherThanLower() {
        assertTrue(Rarity.MYTHIC isBetterThan Rarity.COMMON)
        assertTrue(Rarity.EPIC isBetterThan Rarity.RARE)
        assertTrue(Rarity.UNCOMMON isBetterThan Rarity.COMMON)
    }

    @Test
    fun isBetterThan_sameLevel_returnsFalse() {
        assertFalse(Rarity.RARE isBetterThan Rarity.RARE)
        assertFalse(Rarity.COMMON isBetterThan Rarity.COMMON)
    }

    @Test
    fun isBetterThan_lowerThanHigher_returnsFalse() {
        assertFalse(Rarity.COMMON isBetterThan Rarity.UNCOMMON)
        assertFalse(Rarity.RARE isBetterThan Rarity.MYTHIC)
    }

    @Test
    fun isWorseThan_lowerThanHigher() {
        assertTrue(Rarity.COMMON isWorseThan Rarity.MYTHIC)
        assertTrue(Rarity.RARE isWorseThan Rarity.EPIC)
        assertTrue(Rarity.COMMON isWorseThan Rarity.UNCOMMON)
    }

    @Test
    fun isWorseThan_sameLevel_returnsFalse() {
        assertFalse(Rarity.RARE isWorseThan Rarity.RARE)
        assertFalse(Rarity.MYTHIC isWorseThan Rarity.MYTHIC)
    }

    @Test
    fun isWorseThan_higherThanLower_returnsFalse() {
        assertFalse(Rarity.MYTHIC isWorseThan Rarity.COMMON)
        assertFalse(Rarity.EPIC isWorseThan Rarity.RARE)
    }

    // ==================== toInt ====================

    @Test
    fun toInt_returnsUnderlyingValue() {
        assertEquals(1, Rarity.COMMON.toInt())
        assertEquals(2, Rarity.UNCOMMON.toInt())
        assertEquals(3, Rarity.RARE.toInt())
        assertEquals(4, Rarity.EPIC.toInt())
        assertEquals(5, Rarity.LEGENDARY.toInt())
        assertEquals(6, Rarity.MYTHIC.toInt())
    }

    // ==================== toString ====================

    @Test
    fun toString_format() {
        assertEquals("Rarity(1, '普通')", Rarity.COMMON.toString())
        assertEquals("Rarity(2, '优秀')", Rarity.UNCOMMON.toString())
        assertEquals("Rarity(3, '稀有')", Rarity.RARE.toString())
        assertEquals("Rarity(4, '史诗')", Rarity.EPIC.toString())
        assertEquals("Rarity(5, '传说')", Rarity.LEGENDARY.toString())
        assertEquals("Rarity(6, '神话')", Rarity.MYTHIC.toString())
    }

    // ==================== Int.toRarity() ====================

    @Test
    fun intToRarity_validValues() {
        for (i in 1..6) {
            val result = i.toRarity()
            assertTrue("$i.toRarity() should succeed", result.isSuccess)
            assertEquals(i, result.getOrNull()!!.value)
        }
    }

    @Test
    fun intToRarity_invalidValue_returnsFailure() {
        assertTrue(0.toRarity().isFailure)
        assertTrue(7.toRarity().isFailure)
        assertTrue((-1).toRarity().isFailure)
    }

    // ==================== Int.toRarityOrCommon() ====================

    @Test
    fun intToRarityOrCommon_validValues() {
        for (i in 1..6) {
            assertEquals(i, i.toRarityOrCommon().value)
        }
    }

    @Test
    fun intToRarityOrCommon_invalidValue0_returnsCommon() {
        assertEquals(1, 0.toRarityOrCommon().value)
    }

    @Test
    fun intToRarityOrCommon_invalidValue7_returnsMythic() {
        assertEquals(6, 7.toRarityOrCommon().value)
    }

    @Test
    fun intToRarityOrCommon_negativeValue_returnsCommon() {
        assertEquals(1, (-5).toRarityOrCommon().value)
    }

    @Test
    fun intToRarityOrCommon_largeValue_returnsMythic() {
        assertEquals(6, 999.toRarityOrCommon().value)
    }
}
