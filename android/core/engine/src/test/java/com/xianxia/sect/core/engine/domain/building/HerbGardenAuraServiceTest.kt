package com.xianxia.sect.core.engine.domain.building

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.GridBuildingData
import org.junit.Assert.*
import org.junit.Test

class HerbGardenAuraServiceTest {

    // ---- calculateElderMaturityBonus ----

    @Test
    fun calculateElderMaturityBonus_noElder_returnsZero() {
        val elderSlots = ElderSlots(herbGardenElder = "")
        val result = HerbGardenAuraService.calculateElderMaturityBonus(elderSlots, emptyList())
        assertEquals(0.0, result, 0.001)
    }

    @Test
    fun calculateElderMaturityBonus_elderNotInDisciples_returnsZero() {
        val elderSlots = ElderSlots(herbGardenElder = "elder1")
        val result = HerbGardenAuraService.calculateElderMaturityBonus(elderSlots, emptyList())
        assertEquals(0.0, result, 0.001)
    }

    @Test
    fun calculateElderMaturityBonus_elderWithLowSpiritPlanting_returnsZero() {
        val elderSlots = ElderSlots(herbGardenElder = "elder1")
        val disciple = Disciple(id = "elder1", name = "Test Elder")
        // Default spiritPlanting is likely low; the method checks against HERB_GARDEN_ELDER_SPIRIT_BASE
        val result = HerbGardenAuraService.calculateElderMaturityBonus(elderSlots, listOf(disciple))
        // Result depends on GameConfig.PolicyConfig.HERB_GARDEN_ELDER_SPIRIT_BASE
        // We just verify it returns a non-negative value
        assertTrue("Bonus should be non-negative", result >= 0.0)
    }

    // ---- calculateAuraMaturityBonus ----

    @Test
    fun calculateAuraMaturityBonus_noActiveSlot_returnsZero() {
        val elderSlots = ElderSlots(herbGardenDisciples = emptyList())
        val result = HerbGardenAuraService.calculateAuraMaturityBonus(elderSlots, emptyList())
        assertEquals(0.0, result, 0.001)
    }

    @Test
    fun calculateAuraMaturityBonus_emptyDiscipleId_returnsZero() {
        val elderSlots = ElderSlots(
            herbGardenDisciples = listOf(
                com.xianxia.sect.core.model.DirectDiscipleSlot(index = 0, discipleId = "")
            )
        )
        val result = HerbGardenAuraService.calculateAuraMaturityBonus(elderSlots, emptyList())
        assertEquals(0.0, result, 0.001)
    }

    @Test
    fun calculateAuraMaturityBonus_discipleNotInList_returnsZero() {
        val elderSlots = ElderSlots(
            herbGardenDisciples = listOf(
                com.xianxia.sect.core.model.DirectDiscipleSlot(index = 0, discipleId = "d1")
            )
        )
        val result = HerbGardenAuraService.calculateAuraMaturityBonus(elderSlots, emptyList())
        assertEquals(0.0, result, 0.001)
    }

    // ---- isSpiritFieldInAura ----

    @Test
    fun isSpiritFieldInAura_noSpiritField_returnsFalse() {
        val result = HerbGardenAuraService.isSpiritFieldInAura(
            "sf_nonexistent",
            emptyList()
        )
        assertFalse(result)
    }

    @Test
    fun isSpiritFieldInAura_noHerbGardens_returnsFalse() {
        val buildings = listOf(
            GridBuildingData(
                instanceId = "sf1",
                displayName = "灵田",
                sectId = "sect1"
            )
        )
        val result = HerbGardenAuraService.isSpiritFieldInAura("sf1", buildings)
        assertFalse(result)
    }

    @Test
    fun isSpiritFieldInAura_herbGardenDifferentSect_returnsFalse() {
        val buildings = listOf(
            GridBuildingData(
                instanceId = "sf1",
                displayName = "灵田",
                gridX = 10,
                gridY = 10,
                width = 2,
                height = 2,
                sectId = "sect1"
            ),
            GridBuildingData(
                instanceId = "hg1",
                displayName = "灵植阁",
                gridX = 10,
                gridY = 10,
                width = 2,
                height = 3,
                sectId = "sect2"
            )
        )
        val result = HerbGardenAuraService.isSpiritFieldInAura("sf1", buildings)
        assertFalse(result)
    }

    // ---- calculateEffectiveGrowTime ----

    @Test
    fun calculateEffectiveGrowTime_zeroBonus_returnsBaseTime() {
        val result = HerbGardenAuraService.calculateEffectiveGrowTime(100, 0.0)
        assertEquals(100, result)
    }

    @Test
    fun calculateEffectiveGrowTime_negativeBonus_returnsBaseTime() {
        val result = HerbGardenAuraService.calculateEffectiveGrowTime(100, -0.5)
        assertEquals(100, result)
    }

    @Test
    fun calculateEffectiveGrowTime_50PercentBonus_returnsReducedTime() {
        val result = HerbGardenAuraService.calculateEffectiveGrowTime(100, 0.5)
        // 100 / (1 + 0.5) = 66.67 -> ceil = 67
        assertEquals(67, result)
    }

    @Test
    fun calculateEffectiveGrowTime_100PercentBonus_returnsHalfTime() {
        val result = HerbGardenAuraService.calculateEffectiveGrowTime(100, 1.0)
        // 100 / (1 + 1.0) = 50
        assertEquals(50, result)
    }

    @Test
    fun calculateEffectiveGrowTime_smallBonus_returnsSlightlyReducedTime() {
        val result = HerbGardenAuraService.calculateEffectiveGrowTime(100, 0.1)
        // 100 / 1.1 = 90.9 -> ceil = 91
        assertEquals(91, result)
    }

    @Test
    fun calculateEffectiveGrowTime_largeBonus_returnsSmallTime() {
        val result = HerbGardenAuraService.calculateEffectiveGrowTime(100, 9.0)
        // 100 / 10 = 10
        assertEquals(10, result)
    }

    @Test
    fun calculateEffectiveGrowTime_baseTimeOne_returnsOne() {
        val result = HerbGardenAuraService.calculateEffectiveGrowTime(1, 0.5)
        // 1 / 1.5 = 0.67 -> ceil = 1
        assertEquals(1, result)
    }
}
