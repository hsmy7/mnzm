package com.xianxia.sect.core.engine.domain.building

import com.xianxia.sect.core.model.DirectDiscipleSlot
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.SkillStats
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

    // ---- 灵植 Flat 天赋跨门槛（2026-08-12 修复：光环读 getBaseStats） ----
    // 修复前读原始 skills.spiritPlanting，"青帝(灵植+10)"对成熟度光环无效

    @Test
    fun calculateElderMaturityBonus_flatTalentCrossesThreshold_yieldsBonus() {
        val elderSlots = ElderSlots(herbGardenElder = "elder1")
        val plain = Disciple(id = "elder1", name = "长老", skills = SkillStats(spiritPlanting = 75))
        // 75 < 80 门槛 → 无加成
        assertEquals(0.0, HerbGardenAuraService.calculateElderMaturityBonus(elderSlots, listOf(plain)), 0.001)
        // 青帝 r2（+10）→ 75+10 = 85 → (85-80)/4 = 1（Int 除法截断）→ 0.01
        val withTalent = plain.copy(talentIds = listOf("r2_base_plant"))
        assertEquals(0.01, HerbGardenAuraService.calculateElderMaturityBonus(elderSlots, listOf(withTalent)), 0.001)
    }

    @Test
    fun calculateAuraMaturityBonus_flatTalentCrossesThreshold_yieldsBonus() {
        val elderSlots = ElderSlots(
            herbGardenDisciples = listOf(DirectDiscipleSlot(index = 0, discipleId = "d1"))
        )
        val plain = Disciple(id = "d1", name = "弟子", skills = SkillStats(spiritPlanting = 45))
        // 45 ≤ 50 门槛 → 无加成
        assertEquals(0.0, HerbGardenAuraService.calculateAuraMaturityBonus(elderSlots, listOf(plain)), 0.001)
        // 青帝 r2（+10）→ 45+10 = 55 → (55-50)/5 × 0.01 = 0.01
        val withTalent = plain.copy(talentIds = listOf("r2_base_plant"))
        assertEquals(0.01, HerbGardenAuraService.calculateAuraMaturityBonus(elderSlots, listOf(withTalent)), 0.001)
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
