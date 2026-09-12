package com.xianxia.sect.core.engine.domain.building

import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.di.IoDispatcher
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentGate
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentRegistry
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatusService
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.engine.mockSmart
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.SpiritFieldPlant
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.repository.ProductionSlotDataPort
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.WriteGuardRule
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.wallet.SpiritStoneLedger
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * ProductionUiNativeTxGateTest — 生产 UI 面 + 灵田种植族 native 臂门控降级守卫
 * （batch-17，BuildingNativeTxGateTest / ExplorationNativeTxGateTest 同族）。
 *
 * 守护契约：
 * - 降级契约：JVM 单测环境 GameCoreBridge 恒未加载 → AUTHORITATIVE 与 flag OFF
 *   两模式下 8 个入口的 native 臂均返回 false/null（零镜像变更），调用方回退
 *   Kotlin 原路径，回退臂语义与下沉前逐字一致（双实现并行契约）。
 * - 镜像守卫：mock 的 stateSyncServiceRef 未 stub / 返回 null 时不得 NPE
 *   （handover findings 13）。
 * - 回退臂语义：生产槽任命写槽 + gate 登记；卸任清槽 + gate 释放；自动续炼
 *   翻转；灵田播种扣种 + 写地块；灵田移除清空地块——下沉前后状态面不变。
 *
 * C++ 事务本身（校验链判定序 / 失败零写入 / 零 RNG 全分区快照差分）由桌面
 * GTest production_ui_tx_test.cpp 黄金用例守护；真机转发臂由真机验证批覆盖。
 */
@Suppress("DEPRECATION") // 测试需访问 GameData 镜像列
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@RunWith(RobolectricTestRunner::class)
class ProductionUiNativeTxGateTest {

    @get:Rule val writeGuardRule = WriteGuardRule()

    private lateinit var tables: DiscipleTables
    private lateinit var state: MutableGameState
    private lateinit var mockStore: GameStateStore
    private lateinit var repository: ProductionSlotRepository
    private lateinit var inventorySystem: InventorySystem
    private lateinit var gate: DiscipleAssignmentGate
    private lateinit var facade: BuildingFacadeImpl

    companion object {
        private const val DISCIPLE_A = "1"
        private const val FIELD = "field-1"
        private const val SEED = "seed-1"
        private const val SECT = "sect_player"
    }

    @Before
    fun setUp() {
        tables = DiscipleTables()
        state = createMutableState(tables)
        state.discipleTables.writeAllowed = true
        state.discipleTables.addId(1)
        state.discipleTables.names[1] = "弟子1"
        state.discipleTables.statuses[1] = DiscipleStatus.IDLE
        state.discipleTables.isAlive[1] = 1
        state.discipleTables.realms[1] = 9
        state.discipleTables.realmLayers[1] = 1
        state.discipleTables.writeAllowed = false

        mockStore = mockSmart(GameStateStore::class.java)
        Mockito.doAnswer { state.gameData }.`when`(mockStore).gameDataSnapshot
        Mockito.doAnswer { inv ->
            val block = inv.getArgument<MutableGameState.() -> Unit>(0)
            block(state)
            null
        }.`when`(mockStore).update(any())

        gate = DiscipleAssignmentGate(DiscipleAssignmentRegistry())
        val scopeProvider = mockSmart(CoroutineScopeProvider::class.java)
        whenever(scopeProvider.scope)
            .thenReturn(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        repository = ProductionSlotRepository(
            dao = mockSmart(ProductionSlotDataPort::class.java),
            configService = mockSmart(BuildingConfigService::class.java),
            scopeProvider = scopeProvider
        )
        val productionCoordinator = mockSmart(ProductionCoordinator::class.java)
        Mockito.doReturn(repository).`when`(productionCoordinator).repository

        // launchInScope 需真跑块（mockSmart 的 SmartNull 不会执行 lambda）
        val gameEngineCore = mockSmart(GameEngineCore::class.java)
        Mockito.doAnswer { inv ->
            val block = inv.getArgument<suspend CoroutineScope.() -> Unit>(0)
            runBlocking { block(CoroutineScope(Dispatchers.Unconfined)) }
            mock<Job>()
        }.`when`(gameEngineCore).launchInScope(any())

        inventorySystem = mockSmart(InventorySystem::class.java)
        facade = BuildingFacadeImpl(
            buildingService = mockSmart(BuildingService::class.java),
            stateStore = mockStore,
            gameEngineCore = gameEngineCore,
            productionCoordinator = productionCoordinator,
            inventorySystem = inventorySystem,
            spiritStoneWallet = SpiritStoneWallet(
                stateStore = mockStore,
                ledger = SpiritStoneLedger(),
                eventBus = mockSmart(com.xianxia.sect.core.event.EventBus::class.java)
            ),
            assignmentGate = gate,
            discipleStatusService = mockSmart(DiscipleStatusService::class.java),
            ioDispatcher = IoDispatcher(Dispatchers.Unconfined)
        )
    }

    @After
    fun restoreFlag() {
        NativeEngineFlag.mode = NativeEngineFlag.Mode.AUTHORITATIVE
    }

    private fun createMutableState(tbl: DiscipleTables) = MutableGameState(
        gameData = GameData(),
        discipleTables = tbl,
        equipmentStacks = EntityStore(emptyList()),
        equipmentInstances = EntityStore(emptyList()),
        manualStacks = EntityStore(emptyList()),
        manualInstances = EntityStore(emptyList()),
        pills = EntityStore(emptyList()),
        materials = EntityStore(emptyList()),
        herbs = EntityStore(emptyList()),
        seeds = EntityStore(emptyList()),
        storageBags = EntityStore(emptyList()),
        battleLogs = emptyList(),
        isPaused = false,
        isLoading = false,
        isSaving = false
    )

    private fun alchemySlot(index: Int, occupant: String? = null) = ProductionSlot(
        id = "slot-a$index",
        slotIndex = index,
        buildingType = BuildingType.ALCHEMY,
        buildingId = "alchemy",
        assignedDiscipleId = occupant,
        assignedDiscipleName = if (occupant == null) "" else "弟子$occupant"
    )

    private suspend fun seedRepository(vararg slots: ProductionSlot) {
        repository.loadSlots(slots.toList())
    }

    // ── 转发臂门控：降级信号（零镜像变更） ────────────────────────

    @Test
    fun `任命 native 臂 - AUTHORITATIVE 桥未加载降级 false`() = runTest {
        seedRepository(alchemySlot(0))
        val handled = facade.productionNativeAssign(BuildingType.ALCHEMY, 0, DISCIPLE_A, "弟子1")
        assertFalse("桥未加载应降级 false（调用方回退 Kotlin 原路径）", handled)
        assertNull("零镜像变更", state.gameData.productionSlots.firstOrNull {
            it.buildingType == BuildingType.ALCHEMY && it.slotIndex == 0
        }?.assignedDiscipleId)
    }

    @Test
    fun `任命 native 臂 - flag OFF 降级 false`() = runTest {
        seedRepository(alchemySlot(0))
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            assertFalse(
                facade.productionNativeAssign(BuildingType.ALCHEMY, 0, DISCIPLE_A, "弟子1")
            )
        }
    }

    @Test
    fun `卸任 native 臂 - 桥未加载降级 false`() = runTest {
        seedRepository(alchemySlot(0, DISCIPLE_A))
        assertFalse(facade.productionNativeRemove(BuildingType.ALCHEMY, 0))
    }

    @Test
    fun `自动续炼 native 臂 - 桥未加载返回 null`() = runTest {
        seedRepository(alchemySlot(0))
        assertNull(facade.productionNativeToggleAutoRestart(BuildingType.ALCHEMY, 0))
    }

    @Test
    fun `灵田族 native 臂 - 桥未加载降级 false`() = runTest {
        assertFalse(facade.spiritFieldNativePlantOne(FIELD, SEED, SECT))
        assertFalse(facade.spiritFieldNativePlantBatch(listOf(FIELD), SEED, SECT))
        assertFalse(facade.spiritFieldNativeRemoveOne(FIELD))
        assertFalse(facade.spiritFieldNativeRemoveBatch(listOf(FIELD)))
    }

    // ── 回退臂语义（下沉前后状态面不变） ──────────────────────────

    @Test
    fun `任命 - 桥未加载走回退臂写槽并登记 gate`() = runTest {
        seedRepository(alchemySlot(0))
        facade.assignDiscipleToProductionSlot(BuildingType.ALCHEMY, 0, DISCIPLE_A, "弟子1")
        val slot = state.gameData.productionSlots.first {
            it.buildingType == BuildingType.ALCHEMY && it.slotIndex == 0
        }
        assertEquals(DISCIPLE_A, slot.assignedDiscipleId)
        assertEquals("弟子1", slot.assignedDiscipleName)
        assertTrue("gate 应登记", gate.isAssigned(DISCIPLE_A))
        // repo 同步写
        assertEquals(DISCIPLE_A, repository.getSlotByIndex(BuildingType.ALCHEMY, 0)?.assignedDiscipleId)
    }

    @Test
    fun `任命 - flag OFF 走回退臂写槽`() = runTest {
        seedRepository(alchemySlot(0))
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            facade.assignDiscipleToProductionSlot(BuildingType.ALCHEMY, 0, DISCIPLE_A, "弟子1")
        }
        val slot = state.gameData.productionSlots.first {
            it.buildingType == BuildingType.ALCHEMY && it.slotIndex == 0
        }
        assertEquals(DISCIPLE_A, slot.assignedDiscipleId)
        assertTrue(gate.isAssigned(DISCIPLE_A))
    }

    @Test
    fun `卸任 - 桥未加载走回退臂清槽并释放 gate`() = runTest {
        seedRepository(alchemySlot(0, DISCIPLE_A))
        facade.assignDiscipleToProductionSlot(BuildingType.ALCHEMY, 0, DISCIPLE_A, "弟子1")
        facade.removeDiscipleFromProductionSlot(BuildingType.ALCHEMY, 0)
        val slot = state.gameData.productionSlots.first {
            it.buildingType == BuildingType.ALCHEMY && it.slotIndex == 0
        }
        assertNull(slot.assignedDiscipleId)
        assertTrue(slot.assignedDiscipleName.isEmpty())
        assertFalse("gate 应释放", gate.isAssigned(DISCIPLE_A))
        assertNull(repository.getSlotByIndex(BuildingType.ALCHEMY, 0)?.assignedDiscipleId)
    }

    @Test
    fun `自动续炼翻转 - 桥未加载走回退臂翻转镜像与 repo`() = runTest {
        seedRepository(alchemySlot(0))
        facade.toggleAutoRestart(BuildingType.ALCHEMY, 0)
        assertTrue(
            state.gameData.productionSlots.first {
                it.buildingType == BuildingType.ALCHEMY && it.slotIndex == 0
            }.autoRestartEnabled
        )
        assertTrue(repository.getSlotByIndex(BuildingType.ALCHEMY, 0)?.autoRestartEnabled == true)
        facade.toggleAutoRestart(BuildingType.ALCHEMY, 0)
        assertFalse(
            state.gameData.productionSlots.first {
                it.buildingType == BuildingType.ALCHEMY && it.slotIndex == 0
            }.autoRestartEnabled
        )
    }

    @Test
    fun `灵田播种 - 桥未加载走回退臂扣种并写地块`() = runTest {
        whenever(inventorySystem.getSeedById(SEED)).thenReturn(
            Seed(id = SEED, name = "灵芝种子", rarity = 2, growTime = 4, yield = 2, quantity = 3)
        )
        state.seeds = EntityStore<Seed>(
            listOf(Seed(id = SEED, name = "灵芝种子", rarity = 2, growTime = 4, yield = 2, quantity = 3))
        )
        state.gameData = state.gameData.copy(
            gameYear = 3,
            gameMonth = 5,
            spiritFieldPlants = listOf(SpiritFieldPlant(buildingInstanceId = FIELD))
        )

        facade.plantOnSpiritField(FIELD, SEED, SECT)

        val plant = state.gameData.spiritFieldPlants.first { it.buildingInstanceId == FIELD }
        assertEquals(SEED, plant.seedId)
        assertEquals("灵芝种子", plant.seedName)
        assertEquals(4, plant.growTime)
        assertEquals(2, plant.expectedYield)
        assertEquals(SECT, plant.sectId)
        assertEquals(3 * 12 + 5 + 4, plant.completionMonth)
        assertEquals(3, plant.completionPhase)
        assertEquals("种子应扣 1", 2, state.seeds.get(SEED)?.quantity)
    }

    @Test
    fun `灵田移除 - 桥未加载走回退臂清空地块`() = runTest {
        state.gameData = state.gameData.copy(
            spiritFieldPlants = listOf(
                SpiritFieldPlant(
                    buildingInstanceId = FIELD, seedId = SEED, seedName = "灵芝种子",
                    growTime = 4, expectedYield = 2, plantYear = 3, plantMonth = 1,
                    sectId = SECT, completionMonth = 41, completionPhase = 3
                )
            )
        )

        facade.removePlantFromSpiritField(FIELD)

        val plant = state.gameData.spiritFieldPlants.first { it.buildingInstanceId == FIELD }
        assertTrue(plant.seedId.isEmpty())
        assertTrue(plant.seedName.isEmpty())
        assertEquals(0, plant.growTime)
        assertEquals(0, plant.expectedYield)
        assertEquals(1, plant.completionPhase)
        assertEquals(FIELD, plant.buildingInstanceId)
    }
}
