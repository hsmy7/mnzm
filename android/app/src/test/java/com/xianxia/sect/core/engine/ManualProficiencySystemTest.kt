package com.xianxia.sect.core.engine

import org.junit.Assert.*
import org.junit.Test

class ManualProficiencySystemTest {

    @Test
    fun `MasteryLevel - 枚举值完整`() {
        assertEquals(4, ManualProficiencySystem.MasteryLevel.values().size)
        assertNotNull(ManualProficiencySystem.MasteryLevel.NOVICE)
        assertNotNull(ManualProficiencySystem.MasteryLevel.SMALL_SUCCESS)
        assertNotNull(ManualProficiencySystem.MasteryLevel.GREAT_SUCCESS)
        assertNotNull(ManualProficiencySystem.MasteryLevel.PERFECTION)
    }

    @Test
    fun `MasteryLevel - 显示名称正确`() {
        assertEquals("入门", ManualProficiencySystem.MasteryLevel.NOVICE.displayName)
        assertEquals("小成", ManualProficiencySystem.MasteryLevel.SMALL_SUCCESS.displayName)
        assertEquals("大成", ManualProficiencySystem.MasteryLevel.GREAT_SUCCESS.displayName)
        assertEquals("圆满", ManualProficiencySystem.MasteryLevel.PERFECTION.displayName)
    }

    @Test
    fun `MasteryLevel - 加成递增`() {
        val levels = ManualProficiencySystem.MasteryLevel.values()
        for (i in 0 until levels.size - 1) {
            assertTrue(levels[i].bonus < levels[i + 1].bonus)
        }
    }

    @Test
    fun `MasteryLevel - fromProficiency 入门`() {
        assertEquals(
            ManualProficiencySystem.MasteryLevel.NOVICE,
            ManualProficiencySystem.MasteryLevel.fromProficiency(0.0)
        )
    }

    @Test
    fun `MasteryLevel - fromProficiency 小成`() {
        assertEquals(
            ManualProficiencySystem.MasteryLevel.SMALL_SUCCESS,
            ManualProficiencySystem.MasteryLevel.fromProficiency(100.0)
        )
    }

    @Test
    fun `MasteryLevel - fromProficiency 大成`() {
        assertEquals(
            ManualProficiencySystem.MasteryLevel.GREAT_SUCCESS,
            ManualProficiencySystem.MasteryLevel.fromProficiency(200.0)
        )
    }

    @Test
    fun `MasteryLevel - fromProficiency 圆满`() {
        assertEquals(
            ManualProficiencySystem.MasteryLevel.PERFECTION,
            ManualProficiencySystem.MasteryLevel.fromProficiency(300.0)
        )
    }

    @Test
    fun `MasteryLevel - fromLevel 正确映射`() {
        assertEquals(
            ManualProficiencySystem.MasteryLevel.NOVICE,
            ManualProficiencySystem.MasteryLevel.fromLevel(0)
        )
        assertEquals(
            ManualProficiencySystem.MasteryLevel.SMALL_SUCCESS,
            ManualProficiencySystem.MasteryLevel.fromLevel(1)
        )
        assertEquals(
            ManualProficiencySystem.MasteryLevel.GREAT_SUCCESS,
            ManualProficiencySystem.MasteryLevel.fromLevel(2)
        )
        assertEquals(
            ManualProficiencySystem.MasteryLevel.PERFECTION,
            ManualProficiencySystem.MasteryLevel.fromLevel(3)
        )
    }

    @Test
    fun `MasteryLevel - fromLevel 无效等级返回入门`() {
        assertEquals(
            ManualProficiencySystem.MasteryLevel.NOVICE,
            ManualProficiencySystem.MasteryLevel.fromLevel(99)
        )
    }

    @Test
    fun `getProficiencyThresholds - 凡品阈值`() {
        val thresholds = ManualProficiencySystem.getProficiencyThresholds(1)
        assertEquals(0.0, thresholds[ManualProficiencySystem.MasteryLevel.NOVICE]!!, 0.01)
        assertEquals(100.0, thresholds[ManualProficiencySystem.MasteryLevel.SMALL_SUCCESS]!!, 0.01)
        assertEquals(200.0, thresholds[ManualProficiencySystem.MasteryLevel.GREAT_SUCCESS]!!, 0.01)
        assertEquals(300.0, thresholds[ManualProficiencySystem.MasteryLevel.PERFECTION]!!, 0.01)
    }

    @Test
    fun `getProficiencyThresholds - 品阶越高阈值越高`() {
        val lowThresholds = ManualProficiencySystem.getProficiencyThresholds(1)
        val highThresholds = ManualProficiencySystem.getProficiencyThresholds(6)
        assertTrue(
            highThresholds[ManualProficiencySystem.MasteryLevel.PERFECTION]!! >
            lowThresholds[ManualProficiencySystem.MasteryLevel.PERFECTION]!!
        )
    }

    @Test
    fun `getMaxProficiency - 凡品为400`() {
        assertEquals(400.0, ManualProficiencySystem.getMaxProficiency(1), 0.01)
    }

    @Test
    fun `getMaxProficiency - 品阶越高上限越高`() {
        for (rarity in 1..5) {
            assertTrue(
                ManualProficiencySystem.getMaxProficiency(rarity) <
                ManualProficiencySystem.getMaxProficiency(rarity + 1)
            )
        }
    }

    @Test
    fun `BASE_PROFICIENCY_GAIN 为1`() {
        assertEquals(1.0, ManualProficiencySystem.BASE_PROFICIENCY_GAIN, 0.01)
    }

    @Test
    fun `MASTERY_THRESHOLD 为100`() {
        assertEquals(100.0, ManualProficiencySystem.MASTERY_THRESHOLD, 0.01)
    }

    @Test
    fun `LIBRARY_PROFICIENCY_BONUS_RATE 为0_5`() {
        assertEquals(0.5, ManualProficiencySystem.LIBRARY_PROFICIENCY_BONUS_RATE, 0.01)
    }

    @Test
    fun `calculateSkillDamageMultiplier - 入门无加成`() {
        val multiplier = ManualProficiencySystem.calculateSkillDamageMultiplier(1.0, 0)
        assertEquals(1.0, multiplier, 0.01)
    }

    @Test
    fun `calculateSkillDamageMultiplier - 高熟练度有加成`() {
        val baseMultiplier = 1.0
        val multiplier = ManualProficiencySystem.calculateSkillDamageMultiplier(baseMultiplier, 3)
        assertTrue(multiplier > baseMultiplier)
    }
}
