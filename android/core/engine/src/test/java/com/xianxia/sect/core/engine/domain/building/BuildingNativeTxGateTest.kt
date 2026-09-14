package com.xianxia.sect.core.engine.domain.building

import com.xianxia.sect.core.SectLevel
import com.xianxia.sect.core.domain.building.registerTestFeatures
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.di.IoDispatcher
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentGate
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentRegistry
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatusService
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.engine.mockSmart
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.event.EventBus
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.config.BuildingConfigService
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * 建筑域 native 事务门控单测（batch-06 下沉——RoadFacadeImplTest 同族三级
 * 降级契约守护）。
 *
 * JVM 单测环境 GameCoreBridge 恒未加载：断言 AUTHORITATIVE 稳态与 flag OFF
 * 两模式下建筑四操作均走 Kotlin 回退臂且状态变更语义不变（native 臂零激活、
 * 零异常泄漏）；native 事务本身的校验链语义由桌面 C++ building_tx_test.cpp
 * 黄金用例守护，真机转发臂由真机验证批覆盖。
 */
@Suppress("DEPRECATION") // 测试需访问 GameData 镜像列
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@RunWith(RobolectricTestRunner::class)
class BuildingNativeTxGateTest {

    @get:Rule val writeGuardRule = WriteGuardRule()

    private lateinit var tables: DiscipleTables
    private lateinit var state: MutableGameState
    private lateinit var mockStore: GameStateStore
    private lateinit var gameEngineCore: GameEngineCore
    private lateinit var facade: BuildingFacadeImpl

    companion object {
        @BeforeClass
        @JvmStatic
        fun initRegistry() {
            BuildingFeatureRegistry.registerTestFeatures()
        }
    }

    @Before
    fun setUp() {
        tables = DiscipleTables()
        state = createMutableState(tables)
        mockStore = mockSmart(GameStateStore::class.java)
        Mockito.doAnswer { state.gameData }.`when`(mockStore).gameDataSnapshot
        Mockito.doAnswer { inv ->
            val block = inv.getArgument<MutableGameState.() -> Unit>(0)
            block(state)
            null
        }.`when`(mockStore).update(any())

        gameEngineCore = mockSmart(GameEngineCore::class.java)
        val scopeProvider = mockSmart(CoroutineScopeProvider::class.java)
        whenever(scopeProvider.scope).thenReturn(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        val repository = ProductionSlotRepository(
            dao = mockSmart(ProductionSlotDataPort::class.java),
            configService = mockSmart(BuildingConfigService::class.java),
            scopeProvider = scopeProvider
        )
        val productionCoordinator = mockSmart(ProductionCoordinator::class.java)
        Mockito.doReturn(repository).`when`(productionCoordinator).repository

        val wallet = SpiritStoneWallet(
            stateStore = mockStore,
            ledger = SpiritStoneLedger(),
            eventBus = mockSmart(EventBus::class.java)
        )
        facade = BuildingFacadeImpl(
            buildingService = mockSmart(BuildingService::class.java),
            stateStore = mockStore,
            gameEngineCore = gameEngineCore,
            productionCoordinator = productionCoordinator,
            inventorySystem = mockSmart(InventorySystem::class.java),
            spiritStoneWallet = wallet,
            assignmentGate = DiscipleAssignmentGate(DiscipleAssignmentRegistry()),
            discipleStatusService = mockSmart(DiscipleStatusService::class.java),
            ioDispatcher = IoDispatcher()
        )
    }

    @After
    fun restoreFlag() {
        NativeEngineFlag.mode = NativeEngineFlag.Mode.AUTHORITATIVE
    }

    private fun createMutableState(tables: DiscipleTables) = MutableGameState(
        gameData = GameData(),
        discipleTables = tables,
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

    private fun setupState(stones: Long, level: Int = SectLevel.MEDIUM) {
        state.gameData = GameData(
            spiritStones = stones,
            worldMapSects = listOf(WorldSect(id = "main", level = level, isPlayerSect = true)),
            activeSectId = "main",
            placedBuildings = listOf(
                GridBuildingData(
                    buildingId = "single_residence", displayName = "初级单人住所",
                    gridX = 20, gridY = 20, width = 4, height = 4,
                    instanceId = "s1", sectId = "main"
                )
            )
        )
    }

    private fun placeCandidate() = GridBuildingData(
        buildingId = "single_residence", displayName = "初级单人住所",
        gridX = 60, gridY = 60, width = 4, height = 4,
        instanceId = "s2", sectId = "main"
    )

    // ── tryNativePlaceBuilding 门控 ─────────────────────────────

    @Test
    fun `place native 尝试 - AUTHORITATIVE 且桥未加载返回 false`() {
        setupState(stones = 100_000)
        val feature = BuildingFeatureRegistry.findByKey("single_residence")!!
        val handled = facade.tryNativePlaceBuilding(placeCandidate(), feature, 1200)
        assertFalse("桥未加载应降级 false（调用方回退 Kotlin 原路径）", handled)
        assertEquals("零状态变更", 100_000L, state.gameData.spiritStones)
    }

    @Test
    fun `place native 尝试 - flag OFF 返回 false`() {
        setupState(stones = 100_000)
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            assertFalse(
                facade.tryNativePlaceBuilding(
                    placeCandidate(), BuildingFeatureRegistry.findByKey("single_residence")!!, 1200
                )
            )
        }
    }

    // ── 四操作回退臂语义（native 降级下行为不变） ────────────────

    @Test
    fun `升级 - AUTHORITATIVE 桥未加载走回退臂成功`() = runTest {
        setupState(stones = 100_000)
        val result = facade.upgradeBuilding("s1")
        assertTrue("降级臂应成功，实际：$result", result is UpgradeResult.Success)
        assertEquals(100_000L - 38_000L, state.gameData.spiritStones)
        assertEquals("single_residence_upgraded", state.gameData.placedBuildings[0].buildingId)
    }

    @Test
    fun `升级 - flag OFF 走回退臂成功`() = runTest {
        setupState(stones = 100_000)
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            val result = facade.upgradeBuilding("s1")
            assertTrue(result is UpgradeResult.Success)
        }
        assertEquals(100_000L - 38_000L, state.gameData.spiritStones)
    }

    @Test
    fun `迁移 - AUTHORITATIVE 桥未加载走回退臂成功`() = runTest {
        setupState(stones = 100_000)
        facade.moveBuildingDirect("s1", 60, 60)
        assertEquals(60, state.gameData.placedBuildings[0].gridX)
        assertEquals(60, state.gameData.placedBuildings[0].gridY)
    }

    @Test
    fun `迁移 - flag OFF 走回退臂成功`() = runTest {
        setupState(stones = 100_000)
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            facade.moveBuildingDirect("s1", 60, 60)
        }
        assertEquals(60, state.gameData.placedBuildings[0].gridY)
    }
}
