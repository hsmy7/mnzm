package com.xianxia.sect.core.model

import org.junit.Assert.*
import org.junit.Test

class AlchemySystemTest {

    // ==================== AlchemySlotStatus 枚举 ====================

    @Test
    fun alchemySlotStatus_displayName() {
        assertEquals("空闲", AlchemySlotStatus.IDLE.displayName)
        assertEquals("炼制中", AlchemySlotStatus.WORKING.displayName)
        assertEquals("已完成", AlchemySlotStatus.FINISHED.displayName)
    }

    @Test
    fun alchemySlotStatus_values() {
        assertEquals(3, AlchemySlotStatus.values().size)
    }

    // ==================== AlchemySlot 默认构造 ====================

    @Test
    fun alchemySlot_defaultConstruction() {
        val slot = AlchemySlot()
        assertNotNull(slot.id)
        assertEquals(0, slot.slotId)
        assertEquals(0, slot.slotIndex)
        assertNull(slot.recipeId)
        assertEquals("", slot.recipeName)
        assertEquals("", slot.pillName)
        assertEquals(1, slot.pillRarity)
        assertEquals(0, slot.startYear)
        assertEquals(0, slot.startMonth)
        assertEquals(0, slot.duration)
        assertEquals(AlchemySlotStatus.IDLE, slot.status)
        assertEquals(0.0, slot.successRate, 0.001)
        assertTrue(slot.requiredMaterials.isEmpty())
        assertFalse(slot.autoRestartEnabled)
        assertNull(slot.assignedDiscipleId)
        assertEquals("", slot.assignedDiscipleName)
    }

    // ==================== AlchemySlot 计算属性 ====================

    @Test
    fun alchemySlot_isIdle() {
        assertTrue(AlchemySlot(status = AlchemySlotStatus.IDLE).isIdle)
        assertFalse(AlchemySlot(status = AlchemySlotStatus.WORKING).isIdle)
        assertFalse(AlchemySlot(status = AlchemySlotStatus.FINISHED).isIdle)
    }

    @Test
    fun alchemySlot_isWorking() {
        assertTrue(AlchemySlot(status = AlchemySlotStatus.WORKING).isWorking)
        assertFalse(AlchemySlot(status = AlchemySlotStatus.IDLE).isWorking)
        assertFalse(AlchemySlot(status = AlchemySlotStatus.FINISHED).isWorking)
    }

    @Test
    fun alchemySlot_isFinished() {
        assertTrue(AlchemySlot(status = AlchemySlotStatus.FINISHED).isFinished)
        assertFalse(AlchemySlot(status = AlchemySlotStatus.IDLE).isFinished)
        assertFalse(AlchemySlot(status = AlchemySlotStatus.WORKING).isFinished)
    }

    @Test
    fun alchemySlot_getRemainingMonths_idle_returns0() {
        val slot = AlchemySlot(status = AlchemySlotStatus.IDLE, startYear = 1, startMonth = 1, duration = 3)
        assertEquals(0, slot.getRemainingMonths(1, 2))
    }

    @Test
    fun alchemySlot_getRemainingMonths_finished_returns0() {
        val slot = AlchemySlot(status = AlchemySlotStatus.FINISHED, startYear = 1, startMonth = 1, duration = 3)
        assertEquals(0, slot.getRemainingMonths(1, 2))
    }

    @Test
    fun alchemySlot_getProgressPercent_idle_returns0() {
        val slot = AlchemySlot(status = AlchemySlotStatus.IDLE, duration = 3)
        assertEquals(0, slot.getProgressPercent(1, 2))
    }

    @Test
    fun alchemySlot_getProgressPercent_zeroDuration_returns0() {
        val slot = AlchemySlot(status = AlchemySlotStatus.WORKING, duration = 0)
        assertEquals(0, slot.getProgressPercent(1, 2))
    }

    @Test
    fun alchemySlot_isFinished_idle_returnsFalse() {
        val slot = AlchemySlot(status = AlchemySlotStatus.IDLE)
        assertFalse(slot.isFinished(1, 2))
    }

    @Test
    fun alchemySlot_isFinished_finishedStatus_returnsTrue() {
        val slot = AlchemySlot(status = AlchemySlotStatus.FINISHED)
        assertTrue(slot.isFinished(1, 2))
    }

    // ==================== AlchemySlot copy ====================

    @Test
    fun alchemySlot_copy_changesStatus() {
        val slot = AlchemySlot(status = AlchemySlotStatus.IDLE)
        val copied = slot.copy(status = AlchemySlotStatus.WORKING)
        assertEquals(AlchemySlotStatus.WORKING, copied.status)
        assertEquals(AlchemySlotStatus.IDLE, slot.status)
    }

    // ==================== AlchemyRecipe ====================

    @Test
    fun alchemyRecipe_defaultConstruction() {
        val recipe = AlchemyRecipe(
            id = "r1",
            name = "丹方1",
            pillId = "p1",
            pillName = "丹药1",
            pillRarity = 2,
            tier = 1,
            category = PillCategory.CULTIVATION,
            description = "描述",
            materials = mapOf("m1" to 2),
            duration = 3,
            successRate = 0.8,
            effects = PillEffect()
        )
        assertEquals("r1", recipe.id)
        assertEquals("丹方1", recipe.name)
        assertEquals(PillCategory.CULTIVATION, recipe.category)
        assertEquals(mapOf("m1" to 2), recipe.requiredMaterials)
    }

    @Test
    fun alchemyRecipe_requiredMaterials_delegatesToMaterials() {
        val materials = mapOf("herb1" to 3, "herb2" to 1)
        val recipe = AlchemyRecipe(
            id = "r1", name = "n", pillId = "p1", pillName = "pn",
            pillRarity = 1, tier = 1, category = PillCategory.BATTLE,
            description = "", materials = materials, duration = 1,
            successRate = 1.0, effects = PillEffect()
        )
        assertEquals(materials, recipe.requiredMaterials)
    }

    // ==================== AlchemyResult ====================

    @Test
    fun alchemyResult_success() {
        val result = AlchemyResult(success = true, message = "成功")
        assertTrue(result.success)
        assertNull(result.pill)
        assertEquals("成功", result.message)
    }

    @Test
    fun alchemyResult_failure() {
        val result = AlchemyResult(success = false, message = "失败")
        assertFalse(result.success)
        assertNull(result.pill)
        assertEquals("失败", result.message)
    }

    @Test
    fun alchemyResult_defaultMessage() {
        val result = AlchemyResult(success = true)
        assertEquals("", result.message)
    }

    // ==================== ForgeSlotStatus 枚举 ====================

    @Test
    fun forgeSlotStatus_displayName() {
        assertEquals("空闲", ForgeSlotStatus.IDLE.displayName)
        assertEquals("炼制中", ForgeSlotStatus.WORKING.displayName)
        assertEquals("已完成", ForgeSlotStatus.FINISHED.displayName)
    }

    // ==================== ForgeSlot 默认构造 ====================

    @Test
    fun forgeSlot_defaultConstruction() {
        val slot = ForgeSlot()
        assertNotNull(slot.id)
        assertEquals(0, slot.slotId)
        assertEquals(0, slot.slotIndex)
        assertNull(slot.recipeId)
        assertEquals("", slot.recipeName)
        assertEquals("", slot.equipmentName)
        assertEquals(1, slot.equipmentRarity)
        assertEquals(EquipmentSlot.WEAPON, slot.equipmentSlot)
        assertEquals(0, slot.startYear)
        assertEquals(0, slot.startMonth)
        assertEquals(0, slot.duration)
        assertEquals(ForgeSlotStatus.IDLE, slot.status)
        assertEquals(0.0, slot.successRate, 0.001)
        assertTrue(slot.requiredMaterials.isEmpty())
        assertFalse(slot.autoRestartEnabled)
        assertNull(slot.assignedDiscipleId)
        assertEquals("", slot.assignedDiscipleName)
    }

    // ==================== ForgeSlot 计算属性 ====================

    @Test
    fun forgeSlot_isIdle() {
        assertTrue(ForgeSlot(status = ForgeSlotStatus.IDLE).isIdle)
        assertFalse(ForgeSlot(status = ForgeSlotStatus.WORKING).isIdle)
    }

    @Test
    fun forgeSlot_isWorking() {
        assertTrue(ForgeSlot(status = ForgeSlotStatus.WORKING).isWorking)
        assertFalse(ForgeSlot(status = ForgeSlotStatus.IDLE).isWorking)
    }

    @Test
    fun forgeSlot_isFinished() {
        assertTrue(ForgeSlot(status = ForgeSlotStatus.FINISHED).isFinished)
        assertFalse(ForgeSlot(status = ForgeSlotStatus.IDLE).isFinished)
    }

    @Test
    fun forgeSlot_getRemainingMonths_idle_returns0() {
        val slot = ForgeSlot(status = ForgeSlotStatus.IDLE, startYear = 1, startMonth = 1, duration = 3)
        assertEquals(0, slot.getRemainingMonths(1, 2))
    }

    @Test
    fun forgeSlot_getProgressPercent_idle_returns0() {
        val slot = ForgeSlot(status = ForgeSlotStatus.IDLE, duration = 3)
        assertEquals(0f, slot.getProgressPercent(1, 2), 0.001f)
    }

    @Test
    fun forgeSlot_getProgressPercent_zeroDuration_returns0() {
        val slot = ForgeSlot(status = ForgeSlotStatus.WORKING, duration = 0)
        assertEquals(0f, slot.getProgressPercent(1, 2), 0.001f)
    }

    @Test
    fun forgeSlot_isFinished_idle_returnsFalse() {
        val slot = ForgeSlot(status = ForgeSlotStatus.IDLE)
        assertFalse(slot.isFinished(1, 2))
    }

    @Test
    fun forgeSlot_isFinished_finishedStatus_returnsTrue() {
        val slot = ForgeSlot(status = ForgeSlotStatus.FINISHED)
        assertTrue(slot.isFinished(1, 2))
    }

    // ==================== ForgeRecipe ====================

    @Test
    fun forgeRecipe_defaultConstruction() {
        val recipe = ForgeRecipe(
            id = "fr1",
            name = "锻造方1",
            equipmentId = "e1",
            equipmentName = "武器1",
            equipmentRarity = 3,
            tier = 2,
            equipmentSlot = EquipmentSlot.WEAPON,
            description = "描述",
            materials = mapOf("m1" to 5),
            duration = 4,
            successRate = 0.7
        )
        assertEquals("fr1", recipe.id)
        assertEquals(EquipmentSlot.WEAPON, recipe.equipmentSlot)
        assertEquals(mapOf("m1" to 5), recipe.requiredMaterials)
    }

    @Test
    fun forgeRecipe_requiredMaterials_delegatesToMaterials() {
        val materials = mapOf("ore1" to 2)
        val recipe = ForgeRecipe(
            id = "fr1", name = "n", equipmentId = "e1", equipmentName = "en",
            equipmentRarity = 1, tier = 1, equipmentSlot = EquipmentSlot.ARMOR,
            description = "", materials = materials, duration = 1, successRate = 1.0
        )
        assertEquals(materials, recipe.requiredMaterials)
    }

    // ==================== ForgeResult ====================

    @Test
    fun forgeResult_success() {
        val result = ForgeResult(success = true, message = "锻造成功")
        assertTrue(result.success)
        assertNull(result.equipment)
        assertEquals("锻造成功", result.message)
    }

    @Test
    fun forgeResult_failure() {
        val result = ForgeResult(success = false)
        assertFalse(result.success)
        assertNull(result.equipment)
        assertEquals("", result.message)
    }

    // ==================== PillCategory 枚举 ====================

    @Test
    fun pillCategory_values() {
        assertEquals(3, PillCategory.values().size)
    }

    @Test
    fun pillCategory_displayName() {
        assertEquals("修炼丹药", PillCategory.CULTIVATION.displayName)
        assertEquals("战斗丹药", PillCategory.BATTLE.displayName)
        assertEquals("功能丹药", PillCategory.FUNCTIONAL.displayName)
    }

    // ==================== EquipmentSlot 枚举 ====================

    @Test
    fun equipmentSlot_displayName() {
        assertEquals("武器", EquipmentSlot.WEAPON.displayName)
        assertEquals("护甲", EquipmentSlot.ARMOR.displayName)
        assertEquals("靴子", EquipmentSlot.BOOTS.displayName)
        assertEquals("饰品", EquipmentSlot.ACCESSORY.displayName)
    }

    @Test
    fun equipmentSlot_values() {
        assertEquals(4, EquipmentSlot.values().size)
    }
}
