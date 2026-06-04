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
    fun `MasteryLevel - 效果倍率正确`() {
        assertEquals(1.5, ManualProficiencySystem.MasteryLevel.NOVICE.bonus, 0.01)
        assertEquals(2.0, ManualProficiencySystem.MasteryLevel.SMALL_SUCCESS.bonus, 0.01)
        assertEquals(3.0, ManualProficiencySystem.MasteryLevel.GREAT_SUCCESS.bonus, 0.01)
        assertEquals(4.0, ManualProficiencySystem.MasteryLevel.PERFECTION.bonus, 0.01)
    }

    @Test
    fun `MasteryLevel - fromProficiency 入门`() {
        assertEquals(
            ManualProficiencySystem.MasteryLevel.NOVICE,
            ManualProficiencySystem.MasteryLevel.fromProficiency(0.0)
        )
        assertEquals(
            ManualProficiencySystem.MasteryLevel.NOVICE,
            ManualProficiencySystem.MasteryLevel.fromProficiency(999.0)
        )
    }

    @Test
    fun `MasteryLevel - fromProficiency 小成`() {
        assertEquals(
            ManualProficiencySystem.MasteryLevel.SMALL_SUCCESS,
            ManualProficiencySystem.MasteryLevel.fromProficiency(1000.0)
        )
        assertEquals(
            ManualProficiencySystem.MasteryLevel.SMALL_SUCCESS,
            ManualProficiencySystem.MasteryLevel.fromProficiency(9999.0)
        )
    }

    @Test
    fun `MasteryLevel - fromProficiency 大成`() {
        assertEquals(
            ManualProficiencySystem.MasteryLevel.GREAT_SUCCESS,
            ManualProficiencySystem.MasteryLevel.fromProficiency(10000.0)
        )
        assertEquals(
            ManualProficiencySystem.MasteryLevel.GREAT_SUCCESS,
            ManualProficiencySystem.MasteryLevel.fromProficiency(29999.0)
        )
    }

    @Test
    fun `MasteryLevel - fromProficiency 圆满`() {
        assertEquals(
            ManualProficiencySystem.MasteryLevel.PERFECTION,
            ManualProficiencySystem.MasteryLevel.fromProficiency(30000.0)
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
    fun `PROFICIENCY_THRESHOLDS - 统一阈值`() {
        val thresholds = ManualProficiencySystem.PROFICIENCY_THRESHOLDS
        assertEquals(0.0, thresholds[ManualProficiencySystem.MasteryLevel.NOVICE]!!, 0.01)
        assertEquals(1000.0, thresholds[ManualProficiencySystem.MasteryLevel.SMALL_SUCCESS]!!, 0.01)
        assertEquals(10000.0, thresholds[ManualProficiencySystem.MasteryLevel.GREAT_SUCCESS]!!, 0.01)
        assertEquals(30000.0, thresholds[ManualProficiencySystem.MasteryLevel.PERFECTION]!!, 0.01)
    }

    @Test
    fun `MAX_PROFICIENCY 为30000`() {
        assertEquals(30000.0, ManualProficiencySystem.MAX_PROFICIENCY, 0.01)
    }

    @Test
    fun `BASE_PROFICIENCY_RATE 为6`() {
        assertEquals(6.0, ManualProficiencySystem.BASE_PROFICIENCY_RATE, 0.01)
    }

    @Test
    fun `COMPREHENSION_BASELINE 为70`() {
        assertEquals(70, ManualProficiencySystem.COMPREHENSION_BASELINE)
    }

    @Test
    fun `LIBRARY_PROFICIENCY_BONUS_RATE 为0_5`() {
        assertEquals(0.5, ManualProficiencySystem.LIBRARY_PROFICIENCY_BONUS_RATE, 0.01)
    }

    @Test
    fun `calculateProficiencyGainPerSecond - 悟性70无加成`() {
        val gain = ManualProficiencySystem.calculateProficiencyGainPerSecond(70)
        assertEquals(6.0, gain, 0.01)
    }

    @Test
    fun `calculateProficiencyGainPerSecond - 悟性低于基准无加成`() {
        val gain = ManualProficiencySystem.calculateProficiencyGainPerSecond(50)
        assertEquals(6.0, gain, 0.01)
    }

    @Test
    fun `calculateProficiencyGainPerSecond - 悟性80加成翻倍`() {
        val gain = ManualProficiencySystem.calculateProficiencyGainPerSecond(80)
        assertEquals(12.0, gain, 0.01)
    }

    @Test
    fun `calculateProficiencyGainPerSecond - 藏经阁加成50%`() {
        val gain = ManualProficiencySystem.calculateProficiencyGainPerSecond(70, 0.5)
        assertEquals(9.0, gain, 0.01)
    }

    @Test
    fun `calculateProficiencyGainPerSecond - 悟性加成与藏经阁加成叠加`() {
        val gain = ManualProficiencySystem.calculateProficiencyGainPerSecond(80, 0.5)
        assertEquals(18.0, gain, 0.01)
    }

    @Test
    fun `calculateSkillDamageMultiplier - 入门1_5倍`() {
        val multiplier = ManualProficiencySystem.calculateSkillDamageMultiplier(1.0, 0)
        assertEquals(1.5, multiplier, 0.01)
    }

    @Test
    fun `calculateSkillDamageMultiplier - 圆满4倍`() {
        val multiplier = ManualProficiencySystem.calculateSkillDamageMultiplier(1.0, 3)
        assertEquals(4.0, multiplier, 0.01)
    }

    @Test
    fun `calculateSkillDamageMultiplier - 高熟练度有加成`() {
        val baseMultiplier = 1.0
        val multiplier = ManualProficiencySystem.calculateSkillDamageMultiplier(baseMultiplier, 3)
        assertTrue(multiplier > baseMultiplier)
    }
}
