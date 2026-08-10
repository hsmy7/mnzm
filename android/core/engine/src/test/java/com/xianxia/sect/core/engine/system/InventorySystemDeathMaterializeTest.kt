package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.engine.config.GameConfigProvider
import com.xianxia.sect.core.model.BagStackedData
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSlot
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
 * D-03 对抗性审查修复：死亡统一入口测试（InventorySystem.materializeDiscipleBagAndMarkDead）。
 *
 * 核心守卫：
 * - 袋物品物化回仓库（玩家保留，死亡不吞物品）+ 清空袋条目 + 标记死亡（同一事务）
 * - 重复调用幂等：第一次物化后袋清空，第二次不重复物化（防物品复制）
 * - 堆叠条目 quantity 非法（<=0）拒绝物化（防篡改档白得物品）
 * - 袋空/无模板条目不阻塞死亡标记
 */
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
            spiritStoneWallet = wallet,
            gameConfigProvider = GameConfigProvider(
                com.xianxia.sect.core.config.ConfigLoader(assetReader = { null })
            ),
            overflowMailHandler = com.xianxia.sect.core.overflow.NoOpOverflowMailHandler
        )
    }

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
    // 死亡物化：袋物品回仓库 + 清袋 + 标记死亡（单事务）
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `death materializes bag to warehouse clears bag and marks dead`() = runTest {
        // 袋内：1 堆叠条目（模板重建）+ 1 实例条目（保真物化）——
        // 同 key（精铁剑/稀有度1/WEAPON）自动合并为 1 条堆叠，数量=2+1
        insertDiscipleWithBag(1, listOf(
            StorageBagItem(
                itemId = "bag1", itemType = "equipment_stack", name = "精铁剑", rarity = 1, quantity = 2,
                stackedData = BagStackedData(minRealm = 7, slot = EquipmentSlot.WEAPON.name)
            ),
            StorageBagItem(
                itemId = "i1", itemType = "equipment_instance", name = "精铁剑", rarity = 1,
                equipmentInstance = eqInstance("i1", "精铁剑")
            )
        ))

        store.update {
            inventorySystem.materializeDiscipleBagAndMarkDead(this, 1, deathYear = 5, cause = "battle")
        }

        // 堆叠先入仓（minRealm=7 保真），实例 toStack 同 key 合并入该堆叠（quantity+1）
        assertEquals("实例+堆叠同 key 合并为 1 条", 1, store.equipmentStacks.value.size)
        assertEquals("数量 = 堆叠2 + 实例1", 3, store.equipmentStacks.value.first().quantity)
        assertEquals("模板重建 minRealm 保真", 7, store.equipmentStacks.value.first().minRealm)
        // 袋清空
        assertEquals("袋条目清空", 0, store.persistentDiscipleTables.storageBagItems[1].size)
        // 标记死亡（统一入口：isAlive=0 + deathYears）
        assertEquals("isAlive=0", 0, store.persistentDiscipleTables.isAlive[1])
        assertEquals("deathYears 记录", 5, store.persistentDiscipleTables.deathYears[1])
        // 年报死亡计数（wasAlive 守卫：首次调用计 1）
        assertEquals("年报死亡计数 1", 1, store.gameData.value.annualDeceasedDisciples)
    }

    // ═══════════════════════════════════════════════════════════════
    // 幂等：重复死亡处理不重复物化（防物品复制）
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `repeated death call is idempotent - no double materialize`() = runTest {
        insertDiscipleWithBag(1, listOf(
            StorageBagItem(
                itemId = "i1", itemType = "equipment_instance", name = "精铁剑", rarity = 1,
                equipmentInstance = eqInstance("i1", "精铁剑")
            )
        ))

        repeat(2) {
            store.update {
                inventorySystem.materializeDiscipleBagAndMarkDead(this, 1, deathYear = 5, cause = "battle")
            }
        }

        assertEquals("第一次物化后仓库恰 1 堆叠，第二次不重复", 1, store.equipmentStacks.value.size)
        assertEquals("isAlive 保持 0", 0, store.persistentDiscipleTables.isAlive[1])
        // 年报死亡计数守卫：二次调用（世界关卡双标记模式）只计 1 次
        assertEquals("守卫防双计：年报死亡计数恰 1", 1, store.gameData.value.annualDeceasedDisciples)
    }

    // ═══════════════════════════════════════════════════════════════
    // 防御：非法数量堆叠条目拒绝物化
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `stack item with non-positive quantity not materialized`() = runTest {
        insertDiscipleWithBag(1, listOf(
            StorageBagItem(
                itemId = "bag1", itemType = "equipment_stack", name = "精铁剑", rarity = 1, quantity = 0,
                stackedData = BagStackedData()
            )
        ))

        store.update {
            inventorySystem.materializeDiscipleBagAndMarkDead(this, 1, deathYear = 5, cause = "battle")
        }

        assertEquals("0 数量不物化（不白得物品）", 0, store.equipmentStacks.value.size)
        assertEquals("死亡标记不受阻塞", 0, store.persistentDiscipleTables.isAlive[1])
        assertTrue("袋已清空", store.persistentDiscipleTables.storageBagItems[1].isEmpty())
    }

    // ═══════════════════════════════════════════════════════════════
    // 边界：袋空 / 无模板条目不阻塞死亡标记
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `empty bag still marks dead`() = runTest {
        val tables = store.persistentDiscipleTables
        tables.insert(Disciple(id = "1", name = "弟子1", age = 20))
        tables.isAlive[1] = 1

        store.update {
            inventorySystem.materializeDiscipleBagAndMarkDead(this, 1, deathYear = 5, cause = "age")
        }

        assertEquals("袋空直接标记死亡", 0, store.persistentDiscipleTables.isAlive[1])
        assertEquals("袋空路径年报死亡计数 1", 1, store.gameData.value.annualDeceasedDisciples)
    }

    @Test
    fun `unknown disciple id ignored`() = runTest {
        insertDiscipleWithBag(1, listOf(
            StorageBagItem(
                itemId = "i1", itemType = "equipment_instance", name = "精铁剑", rarity = 1,
                equipmentInstance = eqInstance("i1", "精铁剑")
            )
        ))

        store.update {
            inventorySystem.materializeDiscipleBagAndMarkDead(this, 999, deathYear = 5, cause = "battle")
        }

        assertEquals("其他弟子袋不受影响", 1, store.persistentDiscipleTables.storageBagItems[1].size)
        assertEquals("其他弟子仍存活", 1, store.persistentDiscipleTables.isAlive[1])
        assertEquals("无物化", 0, store.equipmentStacks.value.size)
        // 守卫：未知 id（isAlive 无槽位）不计数
        assertEquals("未知 id 不计入年报死亡", 0, store.gameData.value.annualDeceasedDisciples)
    }
}
