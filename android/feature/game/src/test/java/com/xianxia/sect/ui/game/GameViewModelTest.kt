package com.xianxia.sect.ui.game

import android.content.Context
import com.xianxia.sect.core.config.BuildingConfigModel
import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.currentActiveSectId
import com.xianxia.sect.core.engine.notifyUserInteraction
import com.xianxia.sect.core.engine.setActiveDialog
import com.xianxia.sect.core.engine.setFocusedDiscipleId
import com.xianxia.sect.core.engine.updateGameData
import com.xianxia.sect.core.engine.updateDisciple
import com.xianxia.sect.core.engine.domain.battle.BattleFacade
import com.xianxia.sect.core.engine.domain.building.BuildingFacade
import com.xianxia.sect.core.engine.domain.diplomacy.DiplomacyFacade
import com.xianxia.sect.core.engine.domain.disciple.DiscipleFacade
import com.xianxia.sect.core.engine.domain.inventory.InventoryFacade
import com.xianxia.sect.core.engine.domain.production.ProductionFacade
import com.xianxia.sect.core.engine.domain.save.SaveFacade
import com.xianxia.sect.core.engine.service.DailySignInService
import com.xianxia.sect.core.engine.service.MailService
import com.xianxia.sect.core.engine.system.SystemManager
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleCore
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.perf.ThermalMonitor
import com.xianxia.sect.core.perf.ThermalState
import com.xianxia.sect.core.state.UnifiedGameState
import com.xianxia.sect.core.usecase.DisciplePositionQueryUseCase
import com.xianxia.sect.ui.navigation.GameRoute
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * GameViewModel 核心交互单元测试。
 *
 * 覆盖三个场景：
 * 1. 建筑放置逻辑（placeBuilding / getBuildingCost / getBuildingGridSize）
 * 2. 弟子选择逻辑（showDiscipleDetail / dismissDiscipleDetail / toggleFollowDisciple）
 * 3. 对话框导航委托（openXxxDialog / closeCurrentDialog / closeAllDialogs）
 *
 * 已知限制：
 * - GameEngine 的关键方法（setFocusedDiscipleId / updateGameData / updateDisciple / currentActiveSectId /
 *   notifyUserInteraction / setActiveDialog）是定义在 GameEngineCoordination.kt 中的扩展函数，
 *   而非 GameEngine 的成员方法。本测试通过 mockkStatic 拦截这些扩展函数。
 * - navigation / disciple / planting / inventory 四个 delegate 在 GameViewModel 构造时初始化（非注入），
 *   因此无法直接 mock。对话框导航测试通过验证 navigationEvents / popBackEvents Channel 的发射事件
 *   来间接验证委托转发是否正确。
 */
class GameViewModelTest {

    // ── 16 个注入依赖的 MockK mock ──────────────────────────────────
    private val gameEngine: GameEngine = mockk(relaxed = true)
    private val gameEngineCore: GameEngineCore = mockk(relaxed = true)
    private val appContext: Context = mockk(relaxed = true)
    private val systemManager: SystemManager = mockk(relaxed = true)
    private val disciplePositionQuery: DisciplePositionQueryUseCase = mockk(relaxed = true)
    private val buildingConfigService: BuildingConfigService = mockk(relaxed = true)
    private val mailService: MailService = mockk(relaxed = true)
    private val dailySignInService: DailySignInService = mockk(relaxed = true)
    private val discipleFacade: DiscipleFacade = mockk(relaxed = true)
    private val productionFacade: ProductionFacade = mockk(relaxed = true)
    private val inventoryFacade: InventoryFacade = mockk(relaxed = true)
    private val buildingFacade: BuildingFacade = mockk(relaxed = true)
    private val battleFacade: BattleFacade = mockk(relaxed = true)
    private val diplomacyFacade: DiplomacyFacade = mockk(relaxed = true)
    private val saveFacade: SaveFacade = mockk(relaxed = true)
    private val thermalMonitor: ThermalMonitor = mockk(relaxed = true)

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var viewModel: GameViewModel

    @Before
    fun setUp() {
        MockKAnnotations.init(this, relaxUnitFun = true)
        Dispatchers.setMain(testDispatcher)

        // ── Stub gameEngine StateFlow 属性（构造期间被 Flow 链引用）──
        every { gameEngine.gameData } returns MutableStateFlow(GameData())
        every { gameEngine.discipleAggregates } returns MutableStateFlow(emptyList<DiscipleAggregate>())
        every { gameEngine.disciples } returns MutableStateFlow(emptyList<Disciple>())
        every { gameEngine.equipmentStacks } returns MutableStateFlow(emptyList<EquipmentStack>())
        every { gameEngine.productionSlots } returns MutableStateFlow(emptyList<ProductionSlot>())

        // ── Stub gameEngineCore.state（isPaused Flow 链引用）──
        every { gameEngineCore.state } returns MutableStateFlow(UnifiedGameState())

        // ── Stub systemManager.errors（init 块中收集）──
        every { systemManager.errors } returns emptyFlow()

        // ── Stub thermalMonitor.thermalState（属性直接赋值）──
        every { thermalMonitor.thermalState } returns MutableStateFlow(ThermalState.NORMAL)

        // ── Stub dailySignInService.getMilestoneRewards()（属性初始化时调用）──
        every { dailySignInService.getMilestoneRewards() } returns emptyList()

        // ── Mock GameEngine 扩展函数（定义在 GameEngineCoordination.kt）──
        mockkStatic("com.xianxia.sect.core.engine.GameEngineCoordinationKt")
        every { gameEngine.setFocusedDiscipleId(any()) } just runs
        every { gameEngine.currentActiveSectId() } returns "test-sect"
        every { gameEngine.notifyUserInteraction() } just runs
        every { gameEngine.setActiveDialog(any()) } just runs
        coEvery { gameEngine.updateGameData(any()) } returns Unit
        coEvery { gameEngine.updateDisciple(any<String>(), any()) } returns Unit

        viewModel = GameViewModel(
            gameEngine, gameEngineCore, appContext, systemManager,
            disciplePositionQuery, buildingConfigService, mailService,
            dailySignInService, discipleFacade, productionFacade,
            inventoryFacade, buildingFacade, battleFacade,
            diplomacyFacade, saveFacade, thermalMonitor
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    // ════════════════════════════════════════════════════════════════
    // 场景 1：建筑放置逻辑（placeBuilding / getBuildingCost / getBuildingGridSize）
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `getBuildingCost - 返回配置中的灵石消耗`() {
        every { buildingConfigService.getBuildingConfigByDisplayName("炼丹炉") } returns
            BuildingConfigModel(
                id = "alchemy", displayName = "炼丹炉",
                buildingType = "ALCHEMY", cost = 500L
            )
        assertEquals(500L, viewModel.getBuildingCost("炼丹炉"))
    }

    @Test
    fun `getBuildingCost - 配置不存在时返回默认值 1000`() {
        every { buildingConfigService.getBuildingConfigByDisplayName("未知建筑") } returns null
        assertEquals(1000L, viewModel.getBuildingCost("未知建筑"))
    }

    @Test
    fun `getBuildingGridSize - 返回配置中的网格尺寸`() {
        every { buildingConfigService.getBuildingGridSize("炼丹炉") } returns Pair(2, 3)
        val (w, h) = viewModel.getBuildingGridSize("炼丹炉")
        assertEquals(2, w)
        assertEquals(3, h)
    }

    @Test
    fun `placeBuilding - 调用 buildingConfigService 获取配置和尺寸`() = runTest(testDispatcher) {
        every { buildingConfigService.getBuildingConfigByDisplayName("炼丹炉") } returns
            BuildingConfigModel(
                id = "alchemy", displayName = "炼丹炉",
                buildingType = "ALCHEMY", cost = 500L,
                gridWidth = 2, gridHeight = 3
            )
        every { buildingConfigService.getBuildingGridSize("炼丹炉") } returns Pair(2, 3)

        viewModel.placeBuilding("炼丹炉", 0, 0)
        advanceUntilIdle()

        verify { buildingConfigService.getBuildingConfigByDisplayName("炼丹炉") }
        verify { buildingConfigService.getBuildingGridSize("炼丹炉") }
    }

    @Test
    fun `placeBuilding - 调用 currentActiveSectId 获取活跃宗门 ID`() = runTest(testDispatcher) {
        every { buildingConfigService.getBuildingConfigByDisplayName("炼丹炉") } returns
            BuildingConfigModel(
                id = "alchemy", displayName = "炼丹炉",
                buildingType = "ALCHEMY", cost = 500L
            )
        every { buildingConfigService.getBuildingGridSize("炼丹炉") } returns Pair(2, 3)

        viewModel.placeBuilding("炼丹炉", 0, 0)
        advanceUntilIdle()

        verify { gameEngine.currentActiveSectId() }
    }

    @Test
    fun `placeBuilding - 调用 updateGameData 更新游戏数据`() = runTest(testDispatcher) {
        every { buildingConfigService.getBuildingConfigByDisplayName("炼丹炉") } returns
            BuildingConfigModel(
                id = "alchemy", displayName = "炼丹炉",
                buildingType = "ALCHEMY", cost = 500L
            )
        every { buildingConfigService.getBuildingGridSize("炼丹炉") } returns Pair(2, 3)

        viewModel.placeBuilding("炼丹炉", 0, 0)
        advanceUntilIdle()

        coVerify { gameEngine.updateGameData(any()) }
    }

    @Test
    fun `placeBuilding - updateGameData 闭包内扣除灵石并创建建筑`() = runTest(testDispatcher) {
        every { buildingConfigService.getBuildingConfigByDisplayName("炼丹炉") } returns
            BuildingConfigModel(
                id = "alchemy", displayName = "炼丹炉",
                buildingType = "ALCHEMY", cost = 500L
            )
        every { buildingConfigService.getBuildingGridSize("炼丹炉") } returns Pair(2, 3)

        // 捕获 updateGameData 闭包
        val lambdaSlot = slot<(GameData) -> GameData>()
        coEvery { gameEngine.updateGameData(capture(lambdaSlot)) } returns Unit

        viewModel.placeBuilding("炼丹炉", 1, 2)
        advanceUntilIdle()

        assertTrue("updateGameData 闭包应被捕获", lambdaSlot.isCaptured)

        // 用测试数据调用闭包，验证内部逻辑
        val originalData = GameData(spiritStones = 5000L)
        val result = lambdaSlot.captured(originalData)

        assertEquals("灵石应扣除 500", 4500L, result.spiritStones)
        assertEquals("应新增 1 个建筑", 1, result.placedBuildings.size)
        assertEquals("建筑名应为炼丹炉", "炼丹炉", result.placedBuildings[0].displayName)
        assertEquals("建筑 X 坐标应为 1", 1, result.placedBuildings[0].gridX)
        assertEquals("建筑 Y 坐标应为 2", 2, result.placedBuildings[0].gridY)
        assertEquals("建筑宽度应为 2", 2, result.placedBuildings[0].width)
        assertEquals("建筑高度应为 3", 3, result.placedBuildings[0].height)
        assertEquals("宗门 ID 应为 test-sect", "test-sect", result.placedBuildings[0].sectId)
    }

    @Test
    fun `placeBuilding - 灵石不足时不扣除`() = runTest(testDispatcher) {
        every { buildingConfigService.getBuildingConfigByDisplayName("炼丹炉") } returns
            BuildingConfigModel(
                id = "alchemy", displayName = "炼丹炉",
                buildingType = "ALCHEMY", cost = 500L
            )
        every { buildingConfigService.getBuildingGridSize("炼丹炉") } returns Pair(2, 3)

        val lambdaSlot = slot<(GameData) -> GameData>()
        coEvery { gameEngine.updateGameData(capture(lambdaSlot)) } returns Unit

        viewModel.placeBuilding("炼丹炉", 0, 0)
        advanceUntilIdle()

        val originalData = GameData(spiritStones = 100L) // 不足 500
        val result = lambdaSlot.captured(originalData)

        assertEquals("灵石不足时应原样返回", 100L, result.spiritStones)
        assertEquals("不应新增建筑", 0, result.placedBuildings.size)
    }

    @Test
    fun `placeBuilding - 炼丹炉创建 ProductionSlot`() = runTest(testDispatcher) {
        every { buildingConfigService.getBuildingConfigByDisplayName("炼丹炉") } returns
            BuildingConfigModel(
                id = "alchemy", displayName = "炼丹炉",
                buildingType = "ALCHEMY", cost = 500L
            )
        every { buildingConfigService.getBuildingGridSize("炼丹炉") } returns Pair(2, 3)

        val lambdaSlot = slot<(GameData) -> GameData>()
        coEvery { gameEngine.updateGameData(capture(lambdaSlot)) } returns Unit

        viewModel.placeBuilding("炼丹炉", 0, 0)
        advanceUntilIdle()

        val result = lambdaSlot.captured(GameData(spiritStones = 5000L))

        assertEquals("应创建 1 个 ProductionSlot", 1, result.productionSlots.size)
        assertEquals(
            "ProductionSlot 类型应为 ALCHEMY",
            BuildingType.ALCHEMY,
            result.productionSlots[0].buildingType
        )
    }

    @Test
    fun `placeBuilding - 已有同名建筑时跳过`() = runTest(testDispatcher) {
        // 选用有建造数量限制的建筑（藏经阁 noLimit=false），
        // 炼丹炉 noLimit=true 会跳过同名检查，无法验证本场景。
        every { buildingConfigService.getBuildingConfigByDisplayName("藏经阁") } returns
            BuildingConfigModel(
                id = "library", displayName = "藏经阁",
                buildingType = "LIBRARY", cost = 500L
            )
        every { buildingConfigService.getBuildingGridSize("藏经阁") } returns Pair(2, 3)

        val lambdaSlot = slot<(GameData) -> GameData>()
        coEvery { gameEngine.updateGameData(capture(lambdaSlot)) } returns Unit

        viewModel.placeBuilding("藏经阁", 0, 0)
        advanceUntilIdle()

        // 预置一个同宗门同名建筑
        val existingBuilding = GridBuildingData(
            buildingId = "藏经阁", displayName = "藏经阁",
            gridX = 5, gridY = 5, width = 2, height = 3,
            sectId = "test-sect", instanceId = "existing-1"
        )
        val originalData = GameData(
            spiritStones = 5000L,
            placedBuildings = listOf(existingBuilding)
        )
        val result = lambdaSlot.captured(originalData)

        assertEquals("已有同名建筑时灵石不应扣除", 5000L, result.spiritStones)
        assertEquals("已有同名建筑时不应新增", 1, result.placedBuildings.size)
    }

    // ════════════════════════════════════════════════════════════════
    // 场景 2：弟子选择逻辑（showDiscipleDetail / dismissDiscipleDetail / toggleFollowDisciple）
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `showDiscipleDetail - 设置 detailDisciple 状态`() {
        val disciple = createTestDiscipleAggregate("d1", "测试弟子")
        val request = DiscipleDetailRequest(disciple = disciple, allDisciples = listOf(disciple))

        viewModel.showDiscipleDetail(request)

        assertEquals("detailDisciple 应为请求的弟子", request, viewModel.detailDisciple.value)
    }

    @Test
    fun `showDiscipleDetail - 调用 setFocusedDiscipleId 设置聚焦弟子`() {
        val disciple = createTestDiscipleAggregate("d1", "测试弟子")
        val request = DiscipleDetailRequest(disciple = disciple, allDisciples = listOf(disciple))

        viewModel.showDiscipleDetail(request)

        verify { gameEngine.setFocusedDiscipleId("d1") }
    }

    @Test
    fun `showDiscipleDetail - 推入 DISCIPLE_DETAIL overlay`() {
        val disciple = createTestDiscipleAggregate("d1", "测试弟子")
        val request = DiscipleDetailRequest(disciple = disciple, allDisciples = listOf(disciple))

        viewModel.showDiscipleDetail(request)

        assertTrue(
            "overlayOrder 应包含 DISCIPLE_DETAIL",
            viewModel.overlayOrder.contains(TopOverlay.DISCIPLE_DETAIL)
        )
    }

    @Test
    fun `dismissDiscipleDetail - 清空 detailDisciple 状态`() {
        // 先设置弟子详情
        val disciple = createTestDiscipleAggregate("d1", "测试弟子")
        val request = DiscipleDetailRequest(disciple = disciple, allDisciples = listOf(disciple))
        viewModel.showDiscipleDetail(request)

        viewModel.dismissDiscipleDetail()

        assertNull("detailDisciple 应被清空", viewModel.detailDisciple.value)
    }

    @Test
    fun `dismissDiscipleDetail - 调用 setFocusedDiscipleId 清除聚焦`() {
        val disciple = createTestDiscipleAggregate("d1", "测试弟子")
        val request = DiscipleDetailRequest(disciple = disciple, allDisciples = listOf(disciple))
        viewModel.showDiscipleDetail(request)

        viewModel.dismissDiscipleDetail()

        verify { gameEngine.setFocusedDiscipleId(null) }
    }

    @Test
    fun `dismissDiscipleDetail - 移除 DISCIPLE_DETAIL overlay`() {
        val disciple = createTestDiscipleAggregate("d1", "测试弟子")
        val request = DiscipleDetailRequest(disciple = disciple, allDisciples = listOf(disciple))
        viewModel.showDiscipleDetail(request)

        viewModel.dismissDiscipleDetail()

        assertFalse(
            "overlayOrder 不应再包含 DISCIPLE_DETAIL",
            viewModel.overlayOrder.contains(TopOverlay.DISCIPLE_DETAIL)
        )
    }

    @Test
    fun `toggleFollowDisciple - 委托到 DiscipleDelegate 并调用 updateDisciple`() = runTest(testDispatcher) {
        viewModel.toggleFollowDisciple("d1")
        advanceUntilIdle()

        coVerify { gameEngine.updateDisciple("d1", any()) }
    }

    // ════════════════════════════════════════════════════════════════
    // 场景 3：对话框导航委托（openXxxDialog / closeCurrentDialog / closeAllDialogs）
    // ════════════════════════════════════════════════════════════════
    //
    // navigation delegate 在 GameViewModel 构造时初始化（非注入），无法直接 mock。
    // 每个 openXxxDialog 方法调用 navigation.openXxxDialog()，后者调用
    // onNavigate(GameRoute.XXX)，最终通过 _navigationEvents Channel 发射路由事件。
    // 本组测试通过验证 navigationEvents Flow 发射的事件来间接验证委托转发。

    @Test
    fun `openSpiritMineDialog - 转发到 NavigationDelegate 并发出 SpiritMine 路由`() = runTest(testDispatcher) {
        val deferred = async { viewModel.navigationEvents.first() }
        viewModel.openSpiritMineDialog()
        assertEquals(GameRoute.SpiritMine, deferred.await())
    }

    @Test
    fun `openHerbGardenDialog - 转发到 NavigationDelegate 并发出 HerbGarden 路由`() = runTest(testDispatcher) {
        val deferred = async { viewModel.navigationEvents.first() }
        viewModel.openHerbGardenDialog()
        assertEquals(GameRoute.HerbGarden, deferred.await())
    }

    @Test
    fun `openAlchemyDialog - 转发到 NavigationDelegate 并发出 Alchemy 路由`() = runTest(testDispatcher) {
        val deferred = async { viewModel.navigationEvents.first() }
        viewModel.openAlchemyDialog()
        assertEquals(GameRoute.Alchemy, deferred.await())
    }

    @Test
    fun `openForgeDialog - 转发到 NavigationDelegate 并发出 Forge 路由`() = runTest(testDispatcher) {
        val deferred = async { viewModel.navigationEvents.first() }
        viewModel.openForgeDialog()
        assertEquals(GameRoute.Forge, deferred.await())
    }

    @Test
    fun `openLibraryDialog - 转发到 NavigationDelegate 并发出 Library 路由`() = runTest(testDispatcher) {
        val deferred = async { viewModel.navigationEvents.first() }
        viewModel.openLibraryDialog()
        assertEquals(GameRoute.Library, deferred.await())
    }

    @Test
    fun `openWorldMapDialog - 转发到 NavigationDelegate 并发出 WorldMap 路由`() = runTest(testDispatcher) {
        val deferred = async { viewModel.navigationEvents.first() }
        viewModel.openWorldMapDialog()
        assertEquals(GameRoute.WorldMap, deferred.await())
    }

    @Test
    fun `openRecruitDialog - 转发到 NavigationDelegate 并发出 Recruit 路由`() = runTest(testDispatcher) {
        val deferred = async { viewModel.navigationEvents.first() }
        viewModel.openRecruitDialog()
        assertEquals(GameRoute.Recruit, deferred.await())
    }

    @Test
    fun `openMerchantDialog - 转发到 NavigationDelegate 并发出 Merchant 路由`() = runTest(testDispatcher) {
        val deferred = async { viewModel.navigationEvents.first() }
        viewModel.openMerchantDialog()
        assertEquals(GameRoute.Merchant, deferred.await())
    }

    @Test
    fun `openDiplomacyDialog - 转发到 NavigationDelegate 并发出 Diplomacy 路由`() = runTest(testDispatcher) {
        val deferred = async { viewModel.navigationEvents.first() }
        viewModel.openDiplomacyDialog()
        assertEquals(GameRoute.Diplomacy, deferred.await())
    }

    @Test
    fun `openBattleLogDialog - 转发到 NavigationDelegate 并发出 BattleLog 路由`() = runTest(testDispatcher) {
        val deferred = async { viewModel.navigationEvents.first() }
        viewModel.openBattleLogDialog()
        assertEquals(GameRoute.BattleLog, deferred.await())
    }

    @Test
    fun `openSalaryConfigDialog - 转发到 NavigationDelegate 并发出 SalaryConfig 路由`() = runTest(testDispatcher) {
        val deferred = async { viewModel.navigationEvents.first() }
        viewModel.openSalaryConfigDialog()
        assertEquals(GameRoute.SalaryConfig, deferred.await())
    }

    @Test
    fun `closeCurrentDialog - 转发到 NavigationDelegate 并发出 popBack null`() = runTest(testDispatcher) {
        val deferred = async { viewModel.popBackEvents.first() }
        viewModel.closeCurrentDialog()
        assertEquals(null, deferred.await())
    }

    @Test
    fun `closeAllDialogs - 转发到 NavigationDelegate 并发出 popBack empty`() = runTest(testDispatcher) {
        val deferred = async { viewModel.popBackEvents.first() }
        viewModel.closeAllDialogs()
        assertEquals("empty", deferred.await())
    }

    // ════════════════════════════════════════════════════════════════
    // 辅助方法
    // ════════════════════════════════════════════════════════════════

    /** 创建测试用 DiscipleAggregate */
    private fun createTestDiscipleAggregate(id: String, name: String): DiscipleAggregate {
        return DiscipleAggregate(
            core = DiscipleCore(id = id, name = name),
            combatStats = null,
            equipment = null,
            extended = null,
            attributes = null
        )
    }
}
