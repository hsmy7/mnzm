@file:Suppress("WildcardImport")

package com.xianxia.sect.core.engine.domain.building

import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.engine.di.IoDispatcher
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentGate
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentRegistry
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatusService
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.WriteGuardRule
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * 灵田种植（BuildingFacadeImpl.plantOnSpiritField(s)）Bug B 回归测试。
 *
 * 回归：此前种植数只受空地数约束、事务外 removeSeedSync 返回值被忽略
 * （种子不足也种满、种子 0 消耗免费种田）；修复后事务内限种 + 同事务扣种。
 */
@RunWith(RobolectricTestRunner::class)
class BuildingFacadeImplPlantingTest {

    @get:Rule
    val writeGuardRule = WriteGuardRule()

    private lateinit var store: FakeAtomicStateStore
    private lateinit var inventorySystem: InventorySystem
    private lateinit var facade: BuildingFacadeImpl

    @Before
    fun setUp() {
        store = FakeAtomicStateStore()
        inventorySystem = mock()
        facade = BuildingFacadeImpl(
            buildingService = mock(),
            stateStore = store,
            gameEngineCore = mock(),
            productionCoordinator = mock(),
            inventorySystem = inventorySystem,
            spiritStoneWallet = mock<SpiritStoneWallet>(),
            assignmentGate = DiscipleAssignmentGate(DiscipleAssignmentRegistry()),
            discipleStatusService = mock<DiscipleStatusService>(),
            ioDispatcher = IoDispatcher(Dispatchers.Unconfined)
        )
    }

    /** 聚灵草种（growTime=36/yield=5/rarity=1）种子实体 */
    private fun seedEntity(id: String, quantity: Int, isLocked: Boolean = false): Seed {
        val dbSeed = HerbDatabase.getSeedByName("聚灵草种") ?: error("聚灵草种必须在 HerbDatabase 中定义")
        return Seed(
            id = id, slotId = 1, name = "聚灵草种", rarity = dbSeed.rarity,
            growTime = 36, yield = 5, quantity = quantity, isLocked = isLocked
        )
    }

    /** 预置 5 块空地 + 种子库存 */
    private fun seedStoreWith(seed: Seed, fieldCount: Int = 5) {
        store.update {
            seeds = EntityStore(listOf(seed))
            gameData = gameData.copy(
                spiritFieldPlants = (1..fieldCount).map { i ->
                    SpiritFieldPlant(buildingInstanceId = "field$i")
                }
            )
        }
    }

    @Test
    fun `plantOnSpiritFields - 种子数量不足时只种植种子数量上限`() = runTest {
        val seed = seedEntity(id = "seed1", quantity = 2)
        seedStoreWith(seed)
        whenever(inventorySystem.getSeedById("seed1")).thenReturn(seed)

        facade.plantOnSpiritFields(
            listOf("field1", "field2", "field3", "field4", "field5"),
            "seed1", "sectA"
        )

        val planted = store.latestGameData.spiritFieldPlants.count { it.seedId == "seed1" }
        assertEquals("种子只有 2 颗，5 块空地只应种 2 块", 2, planted)
        assertTrue("种子应被扣尽（免费种田根因修复）", store.seeds.value.isEmpty())
    }

    @Test
    fun `plantOnSpiritField - 种子数量不足时不种植`() = runTest {
        // 单地块路径：种子数量为 0（库存已扣尽但界面残留选择）→ 不种植
        val seed = seedEntity(id = "seed1", quantity = 0)
        seedStoreWith(seed)
        whenever(inventorySystem.getSeedById("seed1")).thenReturn(seed)

        facade.plantOnSpiritField("field1", "seed1", "sectA")

        val planted = store.latestGameData.spiritFieldPlants.count { it.seedId == "seed1" }
        assertEquals("数量为 0 的种子不应种植", 0, planted)
    }

    @Test
    fun `plantOnSpiritFields - 种子锁定时不种植不扣种`() = runTest {
        val seed = seedEntity(id = "seed1", quantity = 2, isLocked = true)
        seedStoreWith(seed)
        whenever(inventorySystem.getSeedById("seed1")).thenReturn(seed)

        facade.plantOnSpiritFields(
            listOf("field1", "field2", "field3", "field4", "field5"),
            "seed1", "sectA"
        )

        val planted = store.latestGameData.spiritFieldPlants.count { it.seedId == "seed1" }
        assertEquals("锁定种子不应种植", 0, planted)
        assertEquals("锁定种子数量不应被消耗", 2, store.seeds.value.first().quantity)
    }

    @Test
    fun `plantOnSpiritFields - 种子充足时按空地数种植且同事务扣种`() = runTest {
        val seed = seedEntity(id = "seed1", quantity = 10)
        seedStoreWith(seed)
        whenever(inventorySystem.getSeedById("seed1")).thenReturn(seed)

        facade.plantOnSpiritFields(
            listOf("field1", "field2", "field3", "field4", "field5"),
            "seed1", "sectA"
        )

        val planted = store.latestGameData.spiritFieldPlants.count { it.seedId == "seed1" }
        assertEquals("种子充足时应种满 5 块空地", 5, planted)
        assertEquals("同事务扣种：剩余 5 颗", 5, store.seeds.value.first().quantity)
    }

    @Test
    fun `plantOnSpiritFields - 非本宗地块不种植不扣种`() = runTest {
        // 对抗性审查 F3：目标田 sectId 非本宗时不可播种（越权调用/数据损坏防御）
        val seed = seedEntity(id = "seed1", quantity = 2)
        store.update {
            seeds = EntityStore(listOf(seed))
            gameData = gameData.copy(
                spiritFieldPlants = (1..5).map { i ->
                    SpiritFieldPlant(buildingInstanceId = "field$i", sectId = "sectB")
                }
            )
        }
        whenever(inventorySystem.getSeedById("seed1")).thenReturn(seed)

        facade.plantOnSpiritFields(
            listOf("field1", "field2", "field3", "field4", "field5"),
            "seed1", "sectA"
        )

        val planted = store.latestGameData.spiritFieldPlants.count { it.seedId == "seed1" }
        assertEquals("跨宗门地块不应被种植", 0, planted)
        assertEquals("种子不被消耗", 2, store.seeds.value.first().quantity)
    }
}
