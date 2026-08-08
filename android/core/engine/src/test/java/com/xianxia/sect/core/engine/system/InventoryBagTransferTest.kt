package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.engine.config.GameConfigProvider
import com.xianxia.sect.core.model.BagStackedData
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.model.StorageBagItem
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test


/**
 * D-03 袋条目物化回仓库测试（materializeBagItemsToWarehouse）：
 * 弟子死亡/逐出时袋物品物化回仓库（发放类——溢出自动转邮件，物品不丢）。
 *
 * 独立存储后袋条目持有数据（payload/stackedData），物化仅做"袋 → 仓库"搬运，
 * 不再有"袋满失败"概念（袋容量无上限）。
 */
class InventoryBagTransferTest {

    private lateinit var store: FakeAtomicStateStore
    private lateinit var inventorySystem: InventorySystem
    private lateinit var overflowHandler: RecordingOverflowHandler

    /** 记录溢出邮件草稿的 handler（验证溢出转邮件语义） */
    private class RecordingOverflowHandler : com.xianxia.sect.core.overflow.OverflowMailHandler {
        val drafts = mutableListOf<com.xianxia.sect.core.overflow.OverflowMailDraft>()
        override fun sendOverflowMails(drafts: List<com.xianxia.sect.core.overflow.OverflowMailDraft>) {
            this.drafts.addAll(drafts)
        }
    }

    @Before
    fun setUp() {
        store = FakeAtomicStateStore()
        store.update { gameData = GameData(slotId = 1) }
        overflowHandler = RecordingOverflowHandler()
        inventorySystem = InventorySystem(
            stateStore = store,
            inventoryConfig = InventoryConfig(),
            spiritStoneWallet = com.xianxia.sect.core.wallet.SpiritStoneWallet(
                stateStore = store,
                ledger = org.mockito.Mockito.mock(com.xianxia.sect.core.wallet.SpiritStoneLedger::class.java),
                eventBus = org.mockito.Mockito.mock(com.xianxia.sect.core.event.EventBus::class.java)
            ),
            gameConfigProvider = GameConfigProvider(
                com.xianxia.sect.core.config.ConfigLoader(assetReader = { null })
            ),
            overflowMailHandler = overflowHandler
        )
    }

    private fun eqInstance(id: String, name: String) = EquipmentInstance(
        id = id, name = name, rarity = 1, slot = EquipmentSlot.WEAPON
    )

    @Test
    fun `materialize - equipment instance merges into stack and removes instance`() {
        store.equipmentStacks.value = listOf(
            EquipmentStack(id = "s1", name = "精铁剑", rarity = 1, slot = EquipmentSlot.WEAPON, quantity = 3)
        )
        store.equipmentInstances.value = listOf(eqInstance("i1", "精铁剑"))

        val count = inventorySystem.materializeBagItemsToWarehouse(listOf(
            StorageBagItem(
                itemId = "i1", itemType = "equipment_instance", name = "精铁剑", rarity = 1,
                equipmentInstance = eqInstance("i1", "精铁剑")
            )
        ))

        assertEquals("物化计数", 1, count)
        assertEquals("合并后堆叠数量", 4, store.equipmentStacks.value.first().quantity)
        assertEquals("实例已从实例表移除（防双持有）", 0, store.equipmentInstances.value.size)
    }

    @Test
    fun `materialize - manual instance merges into stack and removes instance`() {
        store.manualStacks.value = listOf(
            ManualStack(id = "m1", name = "太乙剑诀", rarity = 2, type = ManualType.ATTACK, quantity = 2)
        )
        store.manualInstances.value = listOf(
            ManualInstance(id = "mi1", name = "太乙剑诀", rarity = 2, type = ManualType.ATTACK)
        )

        val count = inventorySystem.materializeBagItemsToWarehouse(listOf(
            StorageBagItem(
                itemId = "mi1", itemType = "manual_instance", name = "太乙剑诀", rarity = 2,
                manualInstance = ManualInstance(id = "mi1", name = "太乙剑诀", rarity = 2, type = ManualType.ATTACK)
            )
        ))

        assertEquals(1, count)
        assertEquals(3, store.manualStacks.value.first().quantity)
        assertEquals(0, store.manualInstances.value.size)
    }

    @Test
    fun `materialize - equipment stack rebuilds from template with stackedData minRealm`() {
        // 堆叠条目（赏赐/购买入袋）：模板重建完整堆叠，minRealm 用条目 stackedData 保真
        val count = inventorySystem.materializeBagItemsToWarehouse(listOf(
            StorageBagItem(
                itemId = "bag1", itemType = "equipment_stack", name = "精铁剑", rarity = 1,
                quantity = 2, stackedData = BagStackedData(minRealm = 7, slot = EquipmentSlot.WEAPON.name)
            )
        ))

        assertEquals(1, count)
        val stack = store.equipmentStacks.value.first()
        assertEquals("精铁剑", stack.name)
        assertEquals("条目数量保真", 2, stack.quantity)
        assertEquals("minRealm 保真（非 rarity 推导）", 7, stack.minRealm)
    }

    @Test
    fun `materialize - unknown template dropped without affecting other items`() {
        val count = inventorySystem.materializeBagItemsToWarehouse(listOf(
            StorageBagItem(
                itemId = "b1", itemType = "equipment_stack", name = "不存在的装备", rarity = 1,
                stackedData = BagStackedData()
            ),
            StorageBagItem(
                itemId = "i2", itemType = "equipment_instance", name = "精铁剑", rarity = 1,
                equipmentInstance = eqInstance("i2", "精铁剑")
            )
        ))

        assertEquals("仅成功 1 条", 1, count)
        assertEquals("失败条目未入库", 0, store.equipmentStacks.value.count { it.name == "不存在的装备" })
        assertEquals("成功条目已入库", 1, store.equipmentStacks.value.count { it.name == "精铁剑" })
    }

    @Test
    fun `materialize - legacy un-materialized item ignored`() {
        // 老存档引用式条目（payload 空）：读档物化器已处理，运行期物化忽略
        val count = inventorySystem.materializeBagItemsToWarehouse(listOf(
            StorageBagItem(itemId = "s1", itemType = "equipment_stack", name = "精铁剑", rarity = 1)
        ))

        assertEquals(0, count)
        assertEquals(0, store.equipmentStacks.value.size)
    }

    @Test
    fun `materialize - warehouse full overflows to mail without losing instance`() {
        // 实例物化：仓库满 → returnEquipmentToStack Partial → 实例转邮件（物品不丢）
        val baseCapacity = com.xianxia.sect.core.GameConfig.Warehouse.BASE_CAPACITY
        repeat(baseCapacity) { i ->
            store.equipmentStacks.value = store.equipmentStacks.value +
                EquipmentStack(
                    id = "s$i", name = "独门武器$i", rarity = 1,
                    slot = EquipmentSlot.WEAPON, quantity = 1
                )
        }

        val count = inventorySystem.materializeBagItemsToWarehouse(listOf(
            StorageBagItem(
                itemId = "i1", itemType = "equipment_instance", name = "新武器", rarity = 1,
                equipmentInstance = eqInstance("i1", "新武器")
            )
        ))

        // 仓库满 → Failure(Full) → handleOverflowResult 已把物品转邮件（不丢），
        // 实例删除防"邮件+实例"双份复制
        assertEquals("溢出转邮件视为物化完成", 1, count)
        assertEquals("实例已删除（防复制）", 0, store.equipmentInstances.value.size)
        assertEquals("溢出邮件草稿", 1, overflowHandler.drafts.size)
        assertEquals("溢出数量", 1, overflowHandler.drafts[0].quantity)
    }
}
