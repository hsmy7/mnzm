package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSlot
import org.junit.Assert.*
import org.junit.Test

class EquipmentNurtureSystemTest {

    @Test
    fun `getMaxNurtureLevel - 凡品最高5级`() {
        assertEquals(5, EquipmentNurtureSystem.getMaxNurtureLevel(1))
    }

    @Test
    fun `getMaxNurtureLevel - 天品最高25级`() {
        assertEquals(25, EquipmentNurtureSystem.getMaxNurtureLevel(6))
    }

    @Test
    fun `getMaxNurtureLevel - 品阶越高等级上限越高`() {
        for (rarity in 1..5) {
            assertTrue(
                EquipmentNurtureSystem.getMaxNurtureLevel(rarity) < EquipmentNurtureSystem.getMaxNurtureLevel(rarity + 1)
            )
        }
    }

    @Test
    fun `getMaxNurtureLevel - 无效品阶返回5`() {
        assertEquals(5, EquipmentNurtureSystem.getMaxNurtureLevel(0))
        assertEquals(5, EquipmentNurtureSystem.getMaxNurtureLevel(99))
    }

    @Test
    fun `getExpRequiredForLevelUp - 等级越高经验需求越多`() {
        val rarity = 1
        for (level in 0..3) {
            assertTrue(
                EquipmentNurtureSystem.getExpRequiredForLevelUp(level, rarity) <
                EquipmentNurtureSystem.getExpRequiredForLevelUp(level + 1, rarity)
            )
        }
    }

    @Test
    fun `getExpRequiredForLevelUp - 品阶越高经验需求越多`() {
        val level = 1
        for (rarity in 1..5) {
            assertTrue(
                EquipmentNurtureSystem.getExpRequiredForLevelUp(level, rarity) <=
                EquipmentNurtureSystem.getExpRequiredForLevelUp(level, rarity + 1)
            )
        }
    }

    @Test
    fun `getExpRequiredForLevelUp - 满级返回MAX_VALUE`() {
        val maxLevel = EquipmentNurtureSystem.getMaxNurtureLevel(1)
        assertEquals(Double.MAX_VALUE, EquipmentNurtureSystem.getExpRequiredForLevelUp(maxLevel, 1), 0.01)
    }

    @Test
    fun `calculateExpGain - 胜利时获得经验`() {
        val equipment = EquipmentInstance(id = "e1", name = "铁剑", rarity = 1, slot = EquipmentSlot.WEAPON)
        val exp = EquipmentNurtureSystem.calculateExpGain(equipment, isVictory = true)
        assertTrue(exp > 0)
    }

    @Test
    fun `calculateExpGain - 失败时不获得经验`() {
        val equipment = EquipmentInstance(id = "e1", name = "铁剑", rarity = 1, slot = EquipmentSlot.WEAPON)
        val exp = EquipmentNurtureSystem.calculateExpGain(equipment, isVictory = false)
        assertEquals(0.0, exp, 0.01)
    }

    @Test
    fun `calculateAutoExpGain - 返回正数`() {
        for (rarity in 1..6) {
            assertTrue(EquipmentNurtureSystem.calculateAutoExpGain(rarity) > 0)
        }
    }

    @Test
    fun `calculateAutoExpGain - 品阶越高自动经验越快`() {
        for (rarity in 1..5) {
            assertTrue(
                EquipmentNurtureSystem.calculateAutoExpGain(rarity) <=
                EquipmentNurtureSystem.calculateAutoExpGain(rarity + 1)
            )
        }
    }

    @Test
    fun `updateNurtureExp - 满级不再升级`() {
        val maxLevel = EquipmentNurtureSystem.getMaxNurtureLevel(1)
        val equipment = EquipmentInstance(
            id = "e1",
            name = "铁剑",
            rarity = 1,
            slot = EquipmentSlot.WEAPON,
            nurtureLevel = maxLevel
        )
        val result = EquipmentNurtureSystem.updateNurtureExp(equipment, 1000.0)
        assertEquals(maxLevel, result.equipment.nurtureLevel)
        assertFalse(result.leveledUp)
    }

    @Test
    fun `updateNurtureExp - 经验不足不升级`() {
        val equipment = EquipmentInstance(
            id = "e1",
            name = "铁剑",
            rarity = 1,
            slot = EquipmentSlot.WEAPON,
            nurtureLevel = 0,
            nurtureProgress = 0.0
        )
        val smallExp = 1.0
        val result = EquipmentNurtureSystem.updateNurtureExp(equipment, smallExp)
        assertTrue(result.equipment.nurtureProgress > 0)
    }

    @Test
    fun `BASE_EXP_GAIN 为10`() {
        assertEquals(10.0, EquipmentNurtureSystem.BASE_EXP_GAIN, 0.01)
    }

    @Test
    fun `AUTO_EXP_PER_SECOND 为1`() {
        assertEquals(1.0, EquipmentNurtureSystem.AUTO_EXP_PER_SECOND, 0.01)
    }

    @Test
    fun `NURTURE_BONUS_PER_LEVEL 为5%`() {
        assertEquals(0.05, EquipmentNurtureSystem.NURTURE_BONUS_PER_LEVEL, 0.001)
    }
}
