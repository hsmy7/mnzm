package com.xianxia.sect.core.util

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSet
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.StorageBagItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D-03 储物袋物化迁移测试（StorageBagMaterializer）。
 *
 * 老存档袋条目是引用式（itemId 指向仓库堆叠/实例）。物化把引用式条目转换为
 * 持有数据的独立条目（payload 非空）并从仓库扣减对应数量——防同一物品双持有。
 *
 * 覆盖：
 * - 6 类堆叠条目铸造 payload + 仓库扣减（复制防护）
 * - 实例条目（equipment_instance / manual_instance）从实例表取出入袋
 * - 悬空条目删除（引用不存在的堆叠/实例）
 * - 未知 itemType 保留原样
 * - 幂等（已物化条目跳过）
 */
class StorageBagMaterializerTest {

    private fun discipleWith(items: List<StorageBagItem>) = Disciple(
        id = "d-1", name = "甲", realm = 9, realmLayer = 1, cultivation = 10.0,
        age = 20, lifespan = 80, isAlive = true,
        equipment = EquipmentSet(storageBagItems = items)
    )

    private fun eqStack(id: String, qty: Int, minRealm: Int = 0) = EquipmentStack(
        id = id, name = "精铁剑", rarity = 1, slot = EquipmentSlot.WEAPON, quantity = qty, minRealm = minRealm
    )

    private fun mnStack(id: String, qty: Int, minRealm: Int = 0) = ManualStack(
        id = id, name = "太乙剑诀", rarity = 2, type = ManualType.ATTACK, quantity = qty, minRealm = minRealm
    )

    // ═══════════════════════════════════════════════════════════════
    // 堆叠条目物化：铸造 payload + 仓库扣减
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `equipment stack materializes with stackedData and deducts warehouse`() {
        val item = StorageBagItem(itemId = "eq1", itemType = "equipment_stack", name = "精铁剑", rarity = 1)
        val result = StorageBagMaterializer.materializeDiscipleBagItems(
            BagMaterializeInput(
                disciples = listOf(discipleWith(listOf(item))),
                equipmentStacks = listOf(eqStack("eq1", qty = 3, minRealm = 7)),
                equipmentInstances = emptyList(),
                manualStacks = emptyList(), manualInstances = emptyList(),
                pills = emptyList(), materials = emptyList(), herbs = emptyList(), seeds = emptyList()
            )
        )

        assertEquals("物化计数", 1, result.materializedCount)
        val bagItem = result.disciples.first().equipment.storageBagItems.first()
        assertTrue("payload 已铸造", bagItem.isMaterialized)
        assertEquals("minRealm 保真", 7, bagItem.stackedData?.minRealm)
        assertEquals("槽位保真", EquipmentSlot.WEAPON.name, bagItem.stackedData?.slot)
        assertEquals("仓库扣减 3→2（防复制）", 2, result.equipmentStacks.first().quantity)
    }

    @Test
    fun `equipment stack exhausted after deduct is removed from warehouse`() {
        val item = StorageBagItem(itemId = "eq1", itemType = "equipment_stack", name = "精铁剑", rarity = 1)
        val result = StorageBagMaterializer.materializeDiscipleBagItems(
            BagMaterializeInput(
                disciples = listOf(discipleWith(listOf(item))),
                equipmentStacks = listOf(eqStack("eq1", qty = 1)),
                equipmentInstances = emptyList(),
                manualStacks = emptyList(), manualInstances = emptyList(),
                pills = emptyList(), materials = emptyList(), herbs = emptyList(), seeds = emptyList()
            )
        )

        assertEquals("扣尽后仓库堆叠移除", 0, result.equipmentStacks.size)
        assertTrue("袋条目仍保留", result.disciples.first().equipment.storageBagItems.first().isMaterialized)
    }

    @Test
    fun `manual stack materializes with manualType and deducts warehouse`() {
        val item = StorageBagItem(itemId = "mn1", itemType = "manual_stack", name = "太乙剑诀", rarity = 2)
        val result = StorageBagMaterializer.materializeDiscipleBagItems(
            BagMaterializeInput(
                disciples = listOf(discipleWith(listOf(item))),
                equipmentStacks = emptyList(), equipmentInstances = emptyList(),
                manualStacks = listOf(mnStack("mn1", qty = 2, minRealm = 5)),
                manualInstances = emptyList(),
                pills = emptyList(), materials = emptyList(), herbs = emptyList(), seeds = emptyList()
            )
        )

        val bagItem = result.disciples.first().equipment.storageBagItems.first()
        assertEquals("manualType 保真", ManualType.ATTACK.name, bagItem.stackedData?.manualType)
        assertEquals("minRealm 保真", 5, bagItem.stackedData?.minRealm)
        assertEquals("仓库扣减 2→1", 1, result.manualStacks.first().quantity)
    }

    @Test
    fun `pill materializes and deducts full quantity`() {
        val item = StorageBagItem(itemId = "p1", itemType = "pill", name = "聚气丹", rarity = 1, quantity = 3)
        val result = StorageBagMaterializer.materializeDiscipleBagItems(
            BagMaterializeInput(
                disciples = listOf(discipleWith(listOf(item))),
                equipmentStacks = emptyList(), equipmentInstances = emptyList(),
                manualStacks = emptyList(), manualInstances = emptyList(),
                pills = listOf(Pill(id = "p1", name = "聚气丹", rarity = 1, quantity = 5)),
                materials = emptyList(), herbs = emptyList(), seeds = emptyList()
            )
        )

        assertTrue("payload 已铸造", result.disciples.first().equipment.storageBagItems.first().isMaterialized)
        assertEquals("仓库按条目 quantity 扣减 5→2", 2, result.pills.first().quantity)
    }

    @Test
    fun `herb materializes and deducts full quantity`() {
        val item = StorageBagItem(itemId = "h1", itemType = "herb", name = "灵草", rarity = 1, quantity = 2)
        val result = StorageBagMaterializer.materializeDiscipleBagItems(
            BagMaterializeInput(
                disciples = listOf(discipleWith(listOf(item))),
                equipmentStacks = emptyList(), equipmentInstances = emptyList(),
                manualStacks = emptyList(), manualInstances = emptyList(),
                pills = emptyList(), materials = emptyList(),
                herbs = listOf(Herb(id = "h1", name = "灵草", rarity = 1, quantity = 4)),
                seeds = emptyList()
            )
        )

        assertEquals("仓库扣减 4→2", 2, result.herbs.first().quantity)
    }

    @Test
    fun `seed materializes and deducts full quantity`() {
        val item = StorageBagItem(itemId = "s1", itemType = "seed", name = "灵稻种", rarity = 1, quantity = 2)
        val result = StorageBagMaterializer.materializeDiscipleBagItems(
            BagMaterializeInput(
                disciples = listOf(discipleWith(listOf(item))),
                equipmentStacks = emptyList(), equipmentInstances = emptyList(),
                manualStacks = emptyList(), manualInstances = emptyList(),
                pills = emptyList(), materials = emptyList(), herbs = emptyList(),
                seeds = listOf(Seed(id = "s1", name = "灵稻种", rarity = 1, quantity = 6))
            )
        )

        assertEquals("仓库扣减 6→4", 4, result.seeds.first().quantity)
    }

    @Test
    fun `material materializes and deducts full quantity`() {
        val item = StorageBagItem(itemId = "m1", itemType = "material", name = "铁矿石", rarity = 1, quantity = 1)
        val result = StorageBagMaterializer.materializeDiscipleBagItems(
            BagMaterializeInput(
                disciples = listOf(discipleWith(listOf(item))),
                equipmentStacks = emptyList(), equipmentInstances = emptyList(),
                manualStacks = emptyList(), manualInstances = emptyList(),
                pills = emptyList(),
                materials = listOf(Material(id = "m1", name = "铁矿石", rarity = 1, quantity = 1)),
                herbs = emptyList(), seeds = emptyList()
            )
        )

        assertEquals("扣尽移除", 0, result.materials.size)
        assertTrue(result.disciples.first().equipment.storageBagItems.first().isMaterialized)
    }

    // ═══════════════════════════════════════════════════════════════
    // 实例条目物化：实例表取出入袋（含完整数据）
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `equipment instance moves from instance table into bag`() {
        val item = StorageBagItem(itemId = "i1", itemType = "equipment_instance", name = "传承剑", rarity = 3)
        val instance = EquipmentInstance(id = "i1", name = "传承剑", rarity = 3, slot = EquipmentSlot.WEAPON)
        val result = StorageBagMaterializer.materializeDiscipleBagItems(
            BagMaterializeInput(
                disciples = listOf(discipleWith(listOf(item))),
                equipmentStacks = emptyList(),
                equipmentInstances = listOf(instance),
                manualStacks = emptyList(), manualInstances = emptyList(),
                pills = emptyList(), materials = emptyList(), herbs = emptyList(), seeds = emptyList()
            )
        )

        val bagItem = result.disciples.first().equipment.storageBagItems.first()
        assertEquals("完整实例入袋", instance, bagItem.equipmentInstance)
        assertEquals("实例表清空（防双持有）", 0, result.equipmentInstances.size)
    }

    @Test
    fun `manual instance moves from instance table into bag`() {
        val item = StorageBagItem(itemId = "mi1", itemType = "manual_instance", name = "残缺剑谱", rarity = 2)
        val instance = ManualInstance(id = "mi1", name = "残缺剑谱", rarity = 2, type = ManualType.ATTACK)
        val result = StorageBagMaterializer.materializeDiscipleBagItems(
            BagMaterializeInput(
                disciples = listOf(discipleWith(listOf(item))),
                equipmentStacks = emptyList(), equipmentInstances = emptyList(),
                manualStacks = emptyList(),
                manualInstances = listOf(instance),
                pills = emptyList(), materials = emptyList(), herbs = emptyList(), seeds = emptyList()
            )
        )

        assertEquals("完整实例入袋", instance, result.disciples.first().equipment.storageBagItems.first().manualInstance)
        assertEquals("实例表清空", 0, result.manualInstances.size)
    }

    // ═══════════════════════════════════════════════════════════════
    // 悬空条目 / 未知类型 / 幂等
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `dangling item deleted without affecting valid items`() {
        val valid = StorageBagItem(itemId = "eq1", itemType = "equipment_stack", name = "精铁剑", rarity = 1)
        val dangling = StorageBagItem(itemId = "ghost", itemType = "equipment_stack", name = "幽灵剑", rarity = 1)
        val result = StorageBagMaterializer.materializeDiscipleBagItems(
            BagMaterializeInput(
                disciples = listOf(discipleWith(listOf(valid, dangling))),
                equipmentStacks = listOf(eqStack("eq1", qty = 2)),
                equipmentInstances = emptyList(),
                manualStacks = emptyList(), manualInstances = emptyList(),
                pills = emptyList(), materials = emptyList(), herbs = emptyList(), seeds = emptyList()
            )
        )

        assertEquals("悬空条目删除", 1, result.disciples.first().equipment.storageBagItems.size)
        assertEquals("有效条目保留", "eq1", result.disciples.first().equipment.storageBagItems.first().itemId)
        assertEquals("计数仅有效条目", 1, result.materializedCount)
    }

    @Test
    fun `unknown itemType kept as-is without materialization`() {
        val unknown = StorageBagItem(itemId = "x1", itemType = "奇异类型", name = "未知物品", rarity = 1)
        val result = StorageBagMaterializer.materializeDiscipleBagItems(
            BagMaterializeInput(
                disciples = listOf(discipleWith(listOf(unknown))),
                equipmentStacks = emptyList(), equipmentInstances = emptyList(),
                manualStacks = emptyList(), manualInstances = emptyList(),
                pills = emptyList(), materials = emptyList(), herbs = emptyList(), seeds = emptyList()
            )
        )

        assertEquals("未知条目保留", 1, result.disciples.first().equipment.storageBagItems.size)
        assertFalse("不铸造 payload（不猜语义）", result.disciples.first().equipment.storageBagItems.first().isMaterialized)
        assertEquals("不计入物化", 0, result.materializedCount)
    }

    @Test
    fun `already materialized items skipped - idempotent`() {
        val instance = EquipmentInstance(id = "i1", name = "传承剑", rarity = 3, slot = EquipmentSlot.WEAPON)
        val materialized = StorageBagItem(
            itemId = "i1", itemType = "equipment_instance", name = "传承剑", rarity = 3,
            equipmentInstance = instance
        )
        // 仓库堆叠数量不足也无妨——已物化条目不扣减
        val result = StorageBagMaterializer.materializeDiscipleBagItems(
            BagMaterializeInput(
                disciples = listOf(discipleWith(listOf(materialized))),
                equipmentStacks = emptyList(), equipmentInstances = emptyList(),
                manualStacks = emptyList(), manualInstances = emptyList(),
                pills = emptyList(), materials = emptyList(), herbs = emptyList(), seeds = emptyList()
            )
        )

        assertEquals("计数为零", 0, result.materializedCount)
        assertEquals("条目原样保留", instance, result.disciples.first().equipment.storageBagItems.first().equipmentInstance)
    }

    @Test
    fun `already materialized legacy stacked item not re-deducted`() {
        // 老存档 8-12 字段已内嵌 effect/grade 的条目 + 新 payload——重复调用不重复扣减
        val materialized = StorageBagItem(
            itemId = "p1", itemType = "pill", name = "聚气丹", rarity = 1, quantity = 3,
            stackedData = com.xianxia.sect.core.model.BagStackedData()
        )
        val result = StorageBagMaterializer.materializeDiscipleBagItems(
            BagMaterializeInput(
                disciples = listOf(discipleWith(listOf(materialized))),
                equipmentStacks = emptyList(), equipmentInstances = emptyList(),
                manualStacks = emptyList(), manualInstances = emptyList(),
                pills = listOf(Pill(id = "p1", name = "聚气丹", rarity = 1, quantity = 5)),
                materials = emptyList(), herbs = emptyList(), seeds = emptyList()
            )
        )

        assertEquals("已物化不重复扣减", 5, result.pills.first().quantity)
        assertEquals("计数为零", 0, result.materializedCount)
    }

    @Test
    fun `null payload after migration leaves raw top-level fields intact`() {
        // 老条目顶层字段（name/rarity/quantity/effect/grade）迁移后原样保留
        val item = StorageBagItem(
            itemId = "eq1", itemType = "equipment_stack", name = "精铁剑", rarity = 1, quantity = 2,
            obtainedYear = 12, obtainedMonth = 3
        )
        val result = StorageBagMaterializer.materializeDiscipleBagItems(
            BagMaterializeInput(
                disciples = listOf(discipleWith(listOf(item))),
                equipmentStacks = listOf(eqStack("eq1", qty = 5)),
                equipmentInstances = emptyList(),
                manualStacks = emptyList(), manualInstances = emptyList(),
                pills = emptyList(), materials = emptyList(), herbs = emptyList(), seeds = emptyList()
            )
        )

        val bagItem = result.disciples.first().equipment.storageBagItems.first()
        assertEquals("名称保留", "精铁剑", bagItem.name)
        assertEquals("数量保留", 2, bagItem.quantity)
        assertEquals("获得时间保留", 12, bagItem.obtainedYear)
        assertNull("payload 无实例字段", bagItem.equipmentInstance)
    }
}
