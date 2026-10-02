package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.model.BagStackedData
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.core.registry.ManualDatabase
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Test


/**
 * 袋条目物化回仓库测试（materializeBagItemsToWarehouse）：
 * 弟子袋物品物化回仓库（发放类——溢出自动转邮件，物品不丢）。
 *
 * B3 装备重构口径：
 * - 装备实例条目的"物化回仓"已由实例单轨承载（实例恒在实例表，卸装入袋不清表），
 *   袋 → 仓库搬运语义不再适用——装备实例物化用例随堆叠轨退役删除
 *   （实例保真由 DiscipleEquipmentService 卸装/装配链与 EquipmentUpgradeServiceTest 看护）
 * - 装备堆叠条目（equipment_stack）不再重建（堆叠轨退役）
 * - 功法/材料等堆叠类条目照旧合并/重建
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
        // 溢出转邮件链（handleOverflowResult → resolveManualTemplateId）按名查
        // ManualDatabase，未初始化会抛 IllegalStateException——对齐
        // BagItemReconstructorTest 的条件初始化 + 复位口径
        ManualDatabase.initializeWithManuals(mapOf(
            "t1" to ManualDatabase.ManualTemplate(
                id = "t1", name = "太乙剑诀", type = ManualType.ATTACK, rarity = 2,
                description = "测试功法"
            ),
            "t2" to ManualDatabase.ManualTemplate(
                id = "t2", name = "新功法", type = ManualType.ATTACK, rarity = 1,
                description = "测试功法"
            )
        ))
        store = FakeAtomicStateStore()
        store.update { gameData = GameData() }
        overflowHandler = RecordingOverflowHandler()
        inventorySystem = InventorySystem(
            stateStore = store,
            inventoryConfig = InventoryConfig(),
            overflowMailHandler = overflowHandler
        )
    }

    @After
    fun tearDown() {
        // 恢复未初始化态，防污染其他条件初始化 ManualDatabase 的测试类
        ManualDatabase.resetForTest()
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
    fun `materialize - equipment stack entry no longer rebuilt`() {
        // B3 堆叠轨退役：equipment_stack 条目不再经模板重建入库
        val count = inventorySystem.materializeBagItemsToWarehouse(listOf(
            StorageBagItem(
                itemId = "bag1", itemType = "equipment_stack", name = "精铁剑", rarity = 1,
                quantity = 2, stackedData = BagStackedData(minRealm = 7, slot = EquipmentSlot.HANDS.name)
            )
        ))

        assertEquals("装备堆叠条目不再物化", 0, count)
        assertEquals("实例轨零新增", 0, store.equipmentInstances.value.size)
    }

    @Test
    fun `materialize - unknown template dropped without affecting other items`() {
        val count = inventorySystem.materializeBagItemsToWarehouse(listOf(
            StorageBagItem(
                itemId = "b1", itemType = "material_stack", name = "不存在的材料", rarity = 1,
                stackedData = BagStackedData()
            ),
            StorageBagItem(
                itemId = "mi2", itemType = "manual_instance", name = "太乙剑诀", rarity = 2,
                manualInstance = ManualInstance(id = "mi2", name = "太乙剑诀", rarity = 2, type = ManualType.ATTACK)
            )
        ))

        assertEquals("仅成功 1 条", 1, count)
        assertEquals("失败条目未入库", 0, store.manualStacks.value.count { it.name == "不存在的材料" })
        assertEquals("成功条目已入库", 1, store.manualStacks.value.count { it.name == "太乙剑诀" })
    }

    @Test
    fun `materialize - legacy un-materialized item ignored`() {
        // 老存档引用式条目（payload 空）：读档物化器已处理，运行期物化忽略
        val count = inventorySystem.materializeBagItemsToWarehouse(listOf(
            StorageBagItem(itemId = "s1", itemType = "equipment_stack", name = "精铁剑", rarity = 1)
        ))

        assertEquals(0, count)
        assertEquals(0, store.equipmentInstances.value.size)
    }

    @Test
    fun `materialize - manual stack overflows to mail without losing item`() {
        // 功法堆叠物化：仓库满 → Partial → 溢出转邮件（物品不丢）
        val baseCapacity = com.xianxia.sect.core.GameConfig.Warehouse.BASE_CAPACITY
        store.update {
            manualStacks.replaceAll((0 until baseCapacity).map { i ->
                ManualStack(id = "m$i", name = "独门功法$i", rarity = 1, type = ManualType.ATTACK, quantity = 1)
            })
        }

        val count = inventorySystem.materializeBagItemsToWarehouse(listOf(
            StorageBagItem(
                itemId = "mi1", itemType = "manual_instance", name = "新功法", rarity = 1,
                manualInstance = ManualInstance(id = "mi1", name = "新功法", rarity = 1, type = ManualType.ATTACK)
            )
        ))

        // 仓库满 → 溢出转邮件（不丢）
        assertEquals("物化完成", 1, count)
        assertEquals("溢出邮件草稿", 1, overflowHandler.drafts.size)
    }
}
