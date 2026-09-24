package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.core.state.WriteGuardRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner

/**
 * G07：玩家败北统一入口（InventorySystem.materializeDiscipleBagAndMarkDead）。
 *
 * 核心守卫：
 * - **重伤**而非死亡：currentHp=1、isAlive 保持 1、不清袋、不计年报死亡
 * - 行囊保留（重伤可继续持有物品）
 */
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@RunWith(RobolectricTestRunner::class)
class InventorySystemDeathMaterializeTest {

    @get:Rule val writeGuardRule = WriteGuardRule()
    private lateinit var store: FakeAtomicStateStore
    private lateinit var inventorySystem: InventorySystem

    @Before
    fun setUp() {
        store = FakeAtomicStateStore()
        store.update { gameData = GameData(slotId = 1) }
        store.persistentDiscipleTables.writeAllowed = true
        val wallet = com.xianxia.sect.core.wallet.SpiritStoneWallet(
            stateStore = store,
            ledger = mock(com.xianxia.sect.core.wallet.SpiritStoneLedger::class.java),
            eventBus = mock(com.xianxia.sect.core.event.EventBus::class.java)
        )
        inventorySystem = InventorySystem(
            stateStore = store,
            inventoryConfig = InventoryConfig(),
            overflowMailHandler = com.xianxia.sect.core.overflow.NoOpOverflowMailHandler
        )
    }

    private fun insertDiscipleWithBag(id: Int, items: List<StorageBagItem>) {
        val tables = store.persistentDiscipleTables
        tables.insert(Disciple(id = id.toString(), name = "弟子$id"))
        tables.isAlive[id] = 1
        tables.currentHps[id] = 100
        tables.storageBagItems[id] = items
    }

    @Test
    fun `battle defeat marks injured keeps bag and skips death count`() = runTest {
        insertDiscipleWithBag(1, listOf(
            StorageBagItem(itemId = "bag1", itemType = "equipment_stack", name = "精铁剑", rarity = 1, quantity = 2)
        ))

        store.update {
            inventorySystem.materializeDiscipleBagAndMarkDead(this, 1, deathYear = 5, cause = "battle")
        }

        val tables = store.persistentDiscipleTables
        assertEquals("重伤 HP=1", 1, tables.currentHps[1])
        assertEquals("存活", 1, tables.isAlive[1])
        assertEquals("行囊保留", 1, tables.storageBagItems[1].size)
        assertEquals("不物化到仓库", 0, store.equipmentStacks.value.size)
        assertEquals("年报死亡计数不增", 0, store.gameData.value.annualDeceasedDisciples)
        assertTrue("无 deathYear", !tables.deathYears.contains(1))
    }

    @Test
    fun `repeated injury call keeps HP at 1 and no death count`() = runTest {
        insertDiscipleWithBag(1, emptyList())

        repeat(2) {
            store.update {
                inventorySystem.materializeDiscipleBagAndMarkDead(this, 1, deathYear = 5, cause = "battle")
            }
        }

        assertEquals("HP 仍为 1", 1, store.persistentDiscipleTables.currentHps[1])
        assertEquals("存活", 1, store.persistentDiscipleTables.isAlive[1])
        assertEquals("年报死亡计数 0", 0, store.gameData.value.annualDeceasedDisciples)
    }

    @Test
    fun `empty bag still marks injured`() = runTest {
        val tables = store.persistentDiscipleTables
        tables.insert(Disciple(id = "1", name = "弟子1"))
        tables.isAlive[1] = 1

        store.update {
            inventorySystem.materializeDiscipleBagAndMarkDead(this, 1, deathYear = 5, cause = "battle")
        }

        assertEquals("HP=1", 1, store.persistentDiscipleTables.currentHps[1])
        assertEquals("存活", 1, store.persistentDiscipleTables.isAlive[1])
        assertEquals("无年报死亡", 0, store.gameData.value.annualDeceasedDisciples)
    }

    @Test
    fun `unknown disciple id ignored`() = runTest {
        insertDiscipleWithBag(1, emptyList())
        store.update {
            inventorySystem.materializeDiscipleBagAndMarkDead(this, 999, deathYear = 5, cause = "battle")
        }
        assertEquals("其他弟子仍存活", 1, store.persistentDiscipleTables.isAlive[1])
        assertEquals("其他弟子 HP 未变", 100, store.persistentDiscipleTables.currentHps[1])
        assertEquals("未知 id 不计年报", 0, store.gameData.value.annualDeceasedDisciples)
    }
}
