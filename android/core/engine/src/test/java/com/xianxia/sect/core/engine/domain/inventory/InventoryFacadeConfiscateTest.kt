package com.xianxia.sect.core.engine.domain.inventory

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.engine.config.GameConfigProvider
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.model.BagStackedData
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.state.WriteGuardRule
import com.xianxia.sect.core.util.GameRngManager
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner

/**
 * D-03 取回（没收）路径端到端测试（InventoryFacadeImpl.confiscateStorageBagItem）。
 *
 * 核心守卫：
 * - 实例条目（卸装/忘功法入袋，payload 持完整实例）取回时**保真物化回仓库堆叠**，
 *   不再经模板重建——修复"equipment_instance 不在重建分支 → 取回即丢物品"bug
 * - 仅 Success 才扣袋条目（C1 防复制）；仓库满（Failure）保留袋内物品待重试
 * - 堆叠类条目经模板重建，minRealm 用条目 stackedData 保真
 */
@RunWith(RobolectricTestRunner::class)
class InventoryFacadeConfiscateTest {

    @get:Rule val writeGuardRule = WriteGuardRule()
    private lateinit var store: FakeAtomicStateStore
    private lateinit var facade: InventoryFacadeImpl

    @Before
    fun setUp() {
        store = FakeAtomicStateStore()
        store.update { gameData = GameData(slotId = 1) }
        store.persistentDiscipleTables.writeAllowed = true
        ManualDatabase.initializeWithManuals(mapOf(
            "t1" to ManualDatabase.ManualTemplate(
                id = "t1", name = "太乙剑诀", type = ManualType.ATTACK, rarity = 2,
                description = "测试功法"
            )
        ))
        val wallet = com.xianxia.sect.core.wallet.SpiritStoneWallet(
            stateStore = store,
            ledger = mock(com.xianxia.sect.core.wallet.SpiritStoneLedger::class.java),
            eventBus = mock(com.xianxia.sect.core.event.EventBus::class.java)
        )
        val inventorySystem = InventorySystem(
            stateStore = store,
            inventoryConfig = InventoryConfig(),
            spiritStoneWallet = wallet,
            gameConfigProvider = GameConfigProvider(
                com.xianxia.sect.core.config.ConfigLoader(assetReader = { null })
            ),
            overflowMailHandler = com.xianxia.sect.core.overflow.NoOpOverflowMailHandler
        )
        facade = InventoryFacadeImpl(
            inventorySystem = inventorySystem,
            stateStore = store,
            inventoryConfig = InventoryConfig(),
            gameEngineCore = mock(),
            spiritStoneWallet = wallet,
            gameRngManager = mock(GameRngManager::class.java)
        )
    }

    @After
    fun tearDown() {
        // 恢复未初始化态，防污染其他条件初始化 ManualDatabase 的测试类
        ManualDatabase.resetForTest()
    }

    /** 插入存活弟子并设置储物袋条目 */
    private fun insertDiscipleWithBag(id: Int, items: List<StorageBagItem>) {
        val tables = store.persistentDiscipleTables
        tables.insert(Disciple(id = id.toString(), name = "弟子$id", age = 20))
        tables.isAlive[id] = 1
        tables.storageBagItems[id] = items
    }

    private fun eqInstance(id: String, name: String) = EquipmentInstance(
        id = id, name = name, rarity = 1, slot = EquipmentSlot.WEAPON
    )

    // ═══════════════════════════════════════════════════════════════
    // 实例条目取回：保真物化回仓库堆叠
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `instance item confiscated merges into stack and removes bag entry`() = runTest {
        store.equipmentStacks.value = listOf(
            EquipmentStack(id = "s1", name = "精铁剑", rarity = 1, slot = EquipmentSlot.WEAPON, quantity = 3)
        )
        insertDiscipleWithBag(1, listOf(
            StorageBagItem(
                itemId = "i1", itemType = "equipment_instance", name = "精铁剑", rarity = 1,
                equipmentInstance = eqInstance("i1", "精铁剑")
            )
        ))

        facade.confiscateStorageBagItem("1",
            store.persistentDiscipleTables.storageBagItems[1].first())

        assertEquals("堆叠合并 3→4", 4, store.equipmentStacks.value.first().quantity)
        assertEquals("袋条目移除", 0, store.persistentDiscipleTables.storageBagItems[1].size)
    }

    @Test
    fun `manual instance item confiscated merges into stack`() = runTest {
        store.manualStacks.value = listOf(
            com.xianxia.sect.core.model.ManualStack(
                id = "m1", name = "太乙剑诀", rarity = 2, type = ManualType.ATTACK, quantity = 2
            )
        )
        insertDiscipleWithBag(1, listOf(
            StorageBagItem(
                itemId = "mi1", itemType = "manual_instance", name = "太乙剑诀", rarity = 2,
                manualInstance = ManualInstance(id = "mi1", name = "太乙剑诀", rarity = 2, type = ManualType.ATTACK)
            )
        ))

        facade.confiscateStorageBagItem("1",
            store.persistentDiscipleTables.storageBagItems[1].first())

        assertEquals("堆叠合并 2→3", 3, store.manualStacks.value.first().quantity)
        assertEquals("袋条目移除", 0, store.persistentDiscipleTables.storageBagItems[1].size)
    }

    // ═══════════════════════════════════════════════════════════════
    // C1 防复制：仓库满保留袋内物品
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `instance item kept in bag when warehouse full - C1 no copy`() = runTest {
        // 仓库填满（达到上限后无空槽）
        val baseCapacity = GameConfig.Warehouse.BASE_CAPACITY
        repeat(baseCapacity) { i ->
            store.equipmentStacks.value = store.equipmentStacks.value +
                EquipmentStack(
                    id = "f$i", name = "独门武器$i", rarity = 1,
                    slot = EquipmentSlot.WEAPON, quantity = 1
                )
        }
        insertDiscipleWithBag(1, listOf(
            StorageBagItem(
                itemId = "i1", itemType = "equipment_instance", name = "精铁剑", rarity = 1,
                equipmentInstance = eqInstance("i1", "精铁剑")
            )
        ))

        facade.confiscateStorageBagItem("1",
            store.persistentDiscipleTables.storageBagItems[1].first())

        assertEquals("袋条目保留（待重试）", 1, store.persistentDiscipleTables.storageBagItems[1].size)
        assertEquals("无新堆叠（不复制）", baseCapacity, store.equipmentStacks.value.size)
    }

    // ═══════════════════════════════════════════════════════════════
    // 堆叠条目取回：模板重建 + minRealm 保真
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `stack item confiscated rebuilds via template and removes bag entry`() = runTest {
        insertDiscipleWithBag(1, listOf(
            StorageBagItem(
                itemId = "bag1", itemType = "equipment_stack", name = "精铁剑", rarity = 1, quantity = 1,
                stackedData = BagStackedData(minRealm = 7, slot = EquipmentSlot.WEAPON.name)
            )
        ))

        facade.confiscateStorageBagItem("1",
            store.persistentDiscipleTables.storageBagItems[1].first())

        assertEquals("模板重建入仓", 1, store.equipmentStacks.value.size)
        assertEquals("minRealm 保真", 7, store.equipmentStacks.value.first().minRealm)
        assertEquals("袋条目移除", 0, store.persistentDiscipleTables.storageBagItems[1].size)
    }

    @Test
    fun `stack item kept in bag when template missing`() = runTest {
        insertDiscipleWithBag(1, listOf(
            StorageBagItem(
                itemId = "bag1", itemType = "equipment_stack", name = "不存在的装备", rarity = 1, quantity = 1,
                stackedData = BagStackedData()
            )
        ))

        facade.confiscateStorageBagItem("1",
            store.persistentDiscipleTables.storageBagItems[1].first())

        assertEquals("袋条目保留", 1, store.persistentDiscipleTables.storageBagItems[1].size)
        assertEquals("仓库无新增", 0, store.equipmentStacks.value.size)
    }

    @Test
    fun `invalid disciple id ignored`() = runTest {
        insertDiscipleWithBag(1, listOf(
            StorageBagItem(itemId = "bag1", itemType = "equipment_stack", name = "精铁剑", rarity = 1)
        ))
        val item = store.persistentDiscipleTables.storageBagItems[1].first()

        facade.confiscateStorageBagItem("999", item)

        assertEquals("其他弟子不受影响", 1, store.persistentDiscipleTables.storageBagItems[1].size)
        assertTrue("仓库无变化", store.equipmentStacks.value.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════════
    // [严重-幂等] 重复调用（UI 双击/旧快照）不复制物品
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `double confiscate with stale snapshot does not duplicate`() = runTest {
        store.equipmentStacks.value = listOf(
            EquipmentStack(id = "s1", name = "精铁剑", rarity = 1, slot = EquipmentSlot.WEAPON, quantity = 3)
        )
        insertDiscipleWithBag(1, listOf(
            StorageBagItem(
                itemId = "i1", itemType = "equipment_instance", name = "精铁剑", rarity = 1,
                equipmentInstance = eqInstance("i1", "精铁剑")
            )
        ))
        val staleItem = store.persistentDiscipleTables.storageBagItems[1].first()

        // 双击：同一条目对象调用两次（UI 旧快照）
        facade.confiscateStorageBagItem("1", staleItem)
        facade.confiscateStorageBagItem("1", staleItem)

        assertEquals("只合并一次 3→4", 4, store.equipmentStacks.value.first().quantity)
        assertEquals("袋条目已清空", 0, store.persistentDiscipleTables.storageBagItems[1].size)
    }

    @Test
    fun `instance item with quantity greater than one removed entirely - no infinite copy`() = runTest {
        // 篡改/异常数据：实例条目 quantity>1（实例不可分）
        insertDiscipleWithBag(1, listOf(
            StorageBagItem(
                itemId = "i1", itemType = "equipment_instance", name = "精铁剑", rarity = 1, quantity = 3,
                equipmentInstance = eqInstance("i1", "精铁剑")
            )
        ))
        val item = store.persistentDiscipleTables.storageBagItems[1].first()

        // 第一次成功（整条删除），后续用旧快照重复调用：袋内无匹配条目直接返回
        facade.confiscateStorageBagItem("1", item)
        facade.confiscateStorageBagItem("1", item)

        assertEquals("实例仅物化一次（随机堆叠 id）", 1, store.equipmentStacks.value.size)
        assertEquals("袋条目整条删除", 0, store.persistentDiscipleTables.storageBagItems[1].size)
    }

    @Test
    fun `stack item with non-positive quantity refused`() = runTest {
        insertDiscipleWithBag(1, listOf(
            StorageBagItem(
                itemId = "bag1", itemType = "equipment_stack", name = "精铁剑", rarity = 1, quantity = 0,
                stackedData = BagStackedData()
            )
        ))
        val item = store.persistentDiscipleTables.storageBagItems[1].first()

        facade.confiscateStorageBagItem("1", item)

        assertEquals("0 数量不物化（不白得物品）", 0, store.equipmentStacks.value.size)
        assertEquals("袋条目保留（待玩家处理）", 1, store.persistentDiscipleTables.storageBagItems[1].size)
    }
}
