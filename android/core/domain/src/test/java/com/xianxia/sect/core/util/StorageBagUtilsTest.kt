package com.xianxia.sect.core.util

import com.xianxia.sect.core.model.StorageBagItem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * StorageBagUtils 纯列表工具回归测试。
 *
 * 注：装备/功法实例转回仓库堆叠的合并语义（addEquipmentInstanceToBag /
 * addManualInstanceToBag）已随 P-20 迁移到 :core:engine 的 InventorySystem，
 * 相关测试在 engine 模块（真实容量 + 溢出转邮件 + excludeStackId）。
 */
class StorageBagUtilsTest {

    @Test
    fun `increaseItemQuantity - merges same item into one entry`() {
        val items = listOf(
            StorageBagItem(itemId = "s1", itemType = "equipment_stack", name = "铁剑", rarity = 1, quantity = 1)
        )
        val updated = StorageBagUtils.increaseItemQuantity(
            items,
            StorageBagItem(itemId = "s1", itemType = "equipment_stack", name = "铁剑", rarity = 1, quantity = 2)
        )
        assertEquals(1, updated.size)
        assertEquals(3, updated[0].quantity)
    }

    @Test
    fun `increaseItemQuantity - does not truncate overflow references`() {
        // P-20：引用列表不再按 maxStack 截断（溢出引用静默丢失 → 背包 UI 物品消失）
        val items = listOf(
            StorageBagItem(itemId = "s1", itemType = "equipment_stack", name = "铁剑", rarity = 1, quantity = 99)
        )
        val updated = StorageBagUtils.increaseItemQuantity(
            items,
            StorageBagItem(itemId = "s1", itemType = "equipment_stack", name = "铁剑", rarity = 1, quantity = 1)
        )
        assertEquals("引用应无截断累加", 100, updated[0].quantity)
    }
}
