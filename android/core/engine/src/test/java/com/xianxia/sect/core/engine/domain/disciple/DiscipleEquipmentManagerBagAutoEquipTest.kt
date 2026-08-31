package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.EquipmentSet
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.StorageBagItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-08-31 B 批：自动装备候选统一（仓库 + 储物袋）与更高品阶替换。
 *
 * 覆盖：
 * - 袋内 equipment_instance 直接装配（attachedInstances 重建入表、袋条目移除、孕养保真）
 * - 已装备低品阶 + 袋内/仓库高品阶 → 自动替换（旧装备回袋、replacedInstances 供移除）
 * - 已装备高品阶 + 更低候选 → 不替换
 * - 统一比较键：品阶优先 → 类型匹配 → 孕养等级
 * - 境界不足袋内条目不装配
 * - 袋内 equipment_stack 模板重建装配
 */
class DiscipleEquipmentManagerBagAutoEquipTest {

    private val manager = DiscipleEquipmentManager()

    private fun disciple(
        weaponId: String = "",
        bag: List<StorageBagItem> = emptyList()
    ) = Disciple(
        id = "1",
        name = "测试弟子",
        realm = 9,
        equipment = EquipmentSet(weaponId = weaponId, storageBagItems = bag)
    )

    private fun bagInstance(
        itemId: String,
        name: String,
        rarity: Int,
        physicalAttack: Int = 10,
        nurtureLevel: Int = 0
    ): StorageBagItem {
        val instance = EquipmentInstance(
            id = itemId, name = name, rarity = rarity,
            slot = EquipmentSlot.WEAPON, physicalAttack = physicalAttack,
            minRealm = 9, nurtureLevel = nurtureLevel,
            ownerId = "1", isEquipped = false
        )
        return StorageBagItem(
            itemId = itemId, itemType = ITEM_TYPE_EQUIPMENT_INSTANCE,
            name = name, rarity = rarity, quantity = 1,
            equipmentInstance = instance
        )
    }

    private fun warehouseWeapon(id: String, rarity: Int, name: String = "仓库剑$id"): EquipmentStack =
        EquipmentStack(
            id = id, name = name, rarity = rarity, slot = EquipmentSlot.WEAPON,
            physicalAttack = 5, minRealm = 9, quantity = 2
        )

    // ── 袋内实例直接装配 ──────────────────────────────────────────

    @Test
    fun `袋内实例装配 - 空槽装配且袋条目移除孕养保真`() {
        val d = disciple(bag = listOf(bagInstance("i1", "青云剑", 4, physicalAttack = 100, nurtureLevel = 2)))

        val result = manager.processAutoEquipFromWarehouse(
            disciple = d, warehouseStacks = emptyList(), equipmentInstances = emptyMap(),
            gameYear = 1, gameMonth = 1
        )

        assertEquals("应装配袋内实例", "i1", result.disciple.equipment.weaponId)
        assertTrue("袋条目应移除", result.disciple.equipment.storageBagItems.isEmpty())
        assertEquals("attachedInstances 应含装配实例", 1, result.attachedInstances.size)
        assertEquals("孕养等级保真", 2, result.attachedInstances[0].nurtureLevel)
        assertTrue("attached 实例应置 isEquipped", result.attachedInstances[0].isEquipped)
        assertEquals("装配实例 ownerId 应为弟子", "1", result.attachedInstances[0].ownerId)
        assertTrue("newInstances 应空（实例已存在）", result.newInstances.isEmpty())
    }

    // ── 更高品阶替换 ──────────────────────────────────────────────

    @Test
    fun `更高品阶替换 - 低品阶换袋内高品阶且旧装备回袋`() {
        val old = EquipmentInstance(
            id = "w1", name = "铁剑", rarity = 1, slot = EquipmentSlot.WEAPON,
            physicalAttack = 15, minRealm = 9, ownerId = "1", isEquipped = true
        )
        val d = disciple(
            weaponId = "w1",
            bag = listOf(bagInstance("n1", "青云剑", 4, physicalAttack = 100))
        )

        val result = manager.processAutoEquipFromWarehouse(
            disciple = d, warehouseStacks = emptyList(),
            equipmentInstances = mapOf("w1" to old),
            gameYear = 3, gameMonth = 5
        )

        assertEquals("应替换为高品阶", "n1", result.disciple.equipment.weaponId)
        assertEquals("replacedInstances 应含旧实例", listOf("w1"), result.replacedInstances.map { it.id })
        // 旧装备已回袋（equipment_instance 保真条目）
        val bagItems = result.disciple.equipment.storageBagItems
        assertEquals("旧装备应回袋", 1, bagItems.size)
        assertEquals("w1", bagItems[0].itemId)
        assertEquals(ITEM_TYPE_EQUIPMENT_INSTANCE, bagItems[0].itemType)
        assertEquals("回袋时间应取当前年", 3, bagItems[0].obtainedYear)
        assertEquals("attachedInstances 应含新实例", listOf("n1"), result.attachedInstances.map { it.id })
    }

    @Test
    fun `更高品阶替换 - 已装备高品阶不降级`() {
        val current = EquipmentInstance(
            id = "w1", name = "青云剑", rarity = 4, slot = EquipmentSlot.WEAPON,
            physicalAttack = 100, minRealm = 9, ownerId = "1", isEquipped = true
        )
        val d = disciple(
            weaponId = "w1",
            bag = listOf(bagInstance("n1", "铁剑", 1, physicalAttack = 5))
        )

        val result = manager.processAutoEquipFromWarehouse(
            disciple = d, warehouseStacks = emptyList(),
            equipmentInstances = mapOf("w1" to current),
            gameYear = 1, gameMonth = 1
        )

        assertEquals("高品阶不应被低品阶替换", "w1", result.disciple.equipment.weaponId)
        assertTrue("不应有替换", result.replacedInstances.isEmpty())
        assertTrue("低品阶候选应保留袋内", result.disciple.equipment.storageBagItems.size == 1)
    }

    // ── 统一比较键：品阶优先 ──────────────────────────────────────

    @Test
    fun `统一比较键 - 袋内高品阶优先于仓库低品阶`() {
        val d = disciple(bag = listOf(bagInstance("i1", "青云剑", 4, physicalAttack = 100)))
        val stacks = listOf(warehouseWeapon("e1", rarity = 1))

        val result = manager.processAutoEquipFromWarehouse(
            disciple = d, warehouseStacks = stacks, equipmentInstances = emptyMap(),
            gameYear = 1, gameMonth = 1
        )

        assertEquals("应选袋内 r4 而非仓库 r1", "i1", result.disciple.equipment.weaponId)
        assertTrue("仓库堆叠不应被扣减", result.stackUpdates.isEmpty())
    }

    // ── 境界门槛 ──────────────────────────────────────────────────

    @Test
    fun `境界不足 - 袋内条目不装配`() {
        val instance = EquipmentInstance(
            id = "i1", name = "高阶剑", rarity = 5, slot = EquipmentSlot.WEAPON,
            physicalAttack = 500, minRealm = 2, ownerId = "1", isEquipped = false
        )
        val d = disciple(bag = listOf(
            StorageBagItem(
                itemId = "i1", itemType = ITEM_TYPE_EQUIPMENT_INSTANCE,
                name = "高阶剑", rarity = 5, quantity = 1, equipmentInstance = instance
            )
        ))

        val result = manager.processAutoEquipFromWarehouse(
            disciple = d, warehouseStacks = emptyList(), equipmentInstances = emptyMap(),
            gameYear = 1, gameMonth = 1
        )

        assertTrue("境界不足不应装配", result.disciple.equipment.weaponId.isEmpty())
        assertTrue(result.attachedInstances.isEmpty())
        assertEquals("袋内条目应保留", 1, result.disciple.equipment.storageBagItems.size)
    }

    // ── 袋内堆叠模板重建 ──────────────────────────────────────────

    @Test
    fun `袋内堆叠 - 模板重建装配并扣减数量`() {
        val bagItem = StorageBagItem(
            itemId = "ironSword", itemType = ITEM_TYPE_EQUIPMENT_STACK,
            name = "精铁剑", rarity = 1, quantity = 2,
            stackedData = com.xianxia.sect.core.model.BagStackedData(
                minRealm = 9, slot = EquipmentSlot.WEAPON.name
            )
        )
        val d = disciple(bag = listOf(bagItem))

        val result = manager.processAutoEquipFromWarehouse(
            disciple = d, warehouseStacks = emptyList(), equipmentInstances = emptyMap(),
            gameYear = 1, gameMonth = 1
        )

        assertFalse("模板重建应装配", result.disciple.equipment.weaponId.isEmpty())
        assertEquals("newInstances 应含模板重建实例", 1, result.newInstances.size)
        assertEquals("精铁剑", result.newInstances[0].name)
        assertEquals("袋内堆叠数量应 2→1", 1, result.disciple.equipment.storageBagItems.size)
    }
}
