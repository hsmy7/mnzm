package com.xianxia.sect.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 旧存档堆叠重建函数测试（2026-08-01 堆叠序列化缺陷修复）。
 *
 * 覆盖 [rebuildEquipmentStacks] / [rebuildManualStacks] 的：
 * - 游离实例（未装备/未学习）重建
 * - 装备中/学习中实例跳过
 * - 同 (name, rarity, slot) 分组聚合 quantity
 * - 空输入 / 全装备输入返回空列表
 */
class StackRebuildTest {

    // ── Equipment ──

    @Test
    fun `rebuildEquipmentStacks - 游离实例按 name-rarity-slot 分组聚合`() {
        val instances = listOf(
            EquipmentInstance(name = "青锋剑", rarity = 3, slot = EquipmentSlot.WEAPON),
            EquipmentInstance(name = "青锋剑", rarity = 3, slot = EquipmentSlot.WEAPON),
            EquipmentInstance(name = "玄铁甲", rarity = 2, slot = EquipmentSlot.ARMOR)
        )
        val stacks = rebuildEquipmentStacks(instances)
        assertEquals(2, stacks.size)
        val sword = stacks.first { it.name == "青锋剑" }
        assertEquals(2, sword.quantity)
        assertEquals(3, sword.rarity)
        assertEquals(EquipmentSlot.WEAPON, sword.slot)
    }

    @Test
    fun `rebuildEquipmentStacks - 装备中实例跳过`() {
        val instances = listOf(
            EquipmentInstance(name = "青锋剑", rarity = 3, slot = EquipmentSlot.WEAPON, ownerId = "d1", isEquipped = true),
            EquipmentInstance(name = "玄铁甲", rarity = 2, slot = EquipmentSlot.ARMOR, ownerId = "d2", isEquipped = true)
        )
        assertTrue(rebuildEquipmentStacks(instances).isEmpty())
    }

    @Test
    fun `rebuildEquipmentStacks - 混合游离与装备仅重建游离`() {
        val instances = listOf(
            EquipmentInstance(name = "青锋剑", rarity = 3, slot = EquipmentSlot.WEAPON, ownerId = "d1", isEquipped = true),
            EquipmentInstance(name = "玄铁甲", rarity = 2, slot = EquipmentSlot.ARMOR)
        )
        val stacks = rebuildEquipmentStacks(instances)
        assertEquals(1, stacks.size)
        assertEquals("玄铁甲", stacks[0].name)
    }

    @Test
    fun `rebuildEquipmentStacks - 空输入返回空列表`() {
        assertTrue(rebuildEquipmentStacks(emptyList()).isEmpty())
    }

    @Test
    fun `rebuildEquipmentStacks - 不同 slot 同名不合并`() {
        val instances = listOf(
            EquipmentInstance(name = "青锋剑", rarity = 3, slot = EquipmentSlot.WEAPON),
            EquipmentInstance(name = "青锋剑", rarity = 3, slot = EquipmentSlot.ACCESSORY)
        )
        val stacks = rebuildEquipmentStacks(instances)
        assertEquals(2, stacks.size)
    }

    // ── Manual ──

    @Test
    fun `rebuildManualStacks - 游离实例按 name-rarity-type 分组聚合`() {
        val instances = listOf(
            ManualInstance(name = "御剑诀", rarity = 3, type = ManualType.ATTACK),
            ManualInstance(name = "御剑诀", rarity = 3, type = ManualType.ATTACK),
            ManualInstance(name = "淬体诀", rarity = 2, type = ManualType.DEFENSE)
        )
        val stacks = rebuildManualStacks(instances)
        assertEquals(2, stacks.size)
        val manual = stacks.first { it.name == "御剑诀" }
        assertEquals(2, manual.quantity)
        assertEquals(ManualType.ATTACK, manual.type)
    }

    @Test
    fun `rebuildManualStacks - 学习中实例跳过`() {
        val instances = listOf(
            ManualInstance(name = "御剑诀", rarity = 3, type = ManualType.ATTACK, ownerId = "d1", isLearned = true)
        )
        assertTrue(rebuildManualStacks(instances).isEmpty())
    }

    @Test
    fun `rebuildManualStacks - 空输入返回空列表`() {
        assertTrue(rebuildManualStacks(emptyList()).isEmpty())
    }
}
