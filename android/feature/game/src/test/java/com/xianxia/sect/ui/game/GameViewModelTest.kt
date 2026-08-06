package com.xianxia.sect.ui.game

import com.xianxia.sect.core.SectLevel
import com.xianxia.sect.core.config.BuildingConfigModel
import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.ui.game.delegate.GameLoopDelegate
import com.xianxia.sect.core.engine.currentActiveSectId
import com.xianxia.sect.core.engine.notifyUserInteraction
import com.xianxia.sect.core.engine.setActiveDialog
import com.xianxia.sect.core.engine.setFocusedDiscipleId
import com.xianxia.sect.core.engine.updateGameData
import com.xianxia.sect.core.engine.updateDisciple
import com.xianxia.sect.core.engine.batchUpdateAutoAssignAndGuide
import com.xianxia.sect.core.engine.domain.building.BuildingFacade
import com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
import com.xianxia.sect.core.engine.domain.disciple.DiscipleFacade
import com.xianxia.sect.core.engine.di.IoDispatcher
import com.xianxia.sect.core.engine.service.DailySignInService
import com.xianxia.sect.core.engine.service.MailService
import com.xianxia.sect.core.engine.system.SystemManager
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleCore
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.SectPolicies
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.engine.service.AdService
import com.xianxia.sect.core.audio.AudioConfig
import com.xianxia.sect.core.audio.AudioEngine
import com.xianxia.sect.core.perf.ThermalMonitor
import com.xianxia.sect.core.perf.ThermalState
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.domain.dialog.DialogManager
import com.xianxia.sect.core.domain.dialog.DialogType
import com.xianxia.sect.data.SessionManager
import com.xianxia.sect.ui.navigation.GameRoute
import com.xianxia.sect.ui.game.building.registerDefaults
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    // ── 13 个注入依赖的 MockK mock（GameVmServices 归组后按值对象注入）──
    private val gameEngine: GameEngine = mockk(relaxed = true)
    private val gameEngineCore: GameEngineCore = mockk(relaxed = true)
    private val systemManager: SystemManager = mockk(relaxed = true)
    private val buildingConfigService: BuildingConfigService = mockk(relaxed = true)
    private val mailService: MailService = mockk(relaxed = true)
    private val dailySignInService: DailySignInService = mockk(relaxed = true)
    private val discipleFacade: DiscipleFacade = mockk(relaxed = true)
    private val buildingFacade: BuildingFacade = mockk(relaxed = true)
    private val thermalMonitor: ThermalMonitor = mockk(relaxed = true)
    private val dialogManager: com.xianxia.sect.core.domain.dialog.DialogManager = mockk(relaxed = true)
    private val adService: AdService = mockk(relaxed = true)
    private val audioConfig: AudioConfig = mockk(relaxed = true)
    private val audioEngine: AudioEngine = mockk(relaxed = true)
    private val sessionManager: SessionManager = mockk(relaxed = true)

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var viewModel: GameViewModel

    /** gameData 流（setUp 中 stub 为 [gameEngine.gameData] 的返回，改 .value 驱动命令总线推送） */
    private lateinit var gameDataFlow: MutableStateFlow<GameData>

    /**
     * launchOnEngine 捕获列表（2026-08-01 根因修复）。
     *
     * 历史误诊：文档声称 18 个失败源于"mockkStatic 拦截 Kotlin 2.2 顶层扩展函数失效"，
     * 实际根因是 relaxed mock 上 launchOnEngine 返回 mock Job、lambda 永不执行——
     * 所有异步路径（delegate 经 launchOnEngine 派发）的副作用从未发生。
     * 本列表捕获 lambda，测试内通过 runEngineBlocks() 显式执行。
     */
    private val engineBlocks = mutableListOf<suspend CoroutineScope.() -> Unit>()

    /** 执行所有捕获的引擎块并清空（TestScope 实现 CoroutineScope） */
    private suspend fun TestScope.runEngineBlocks() {
        engineBlocks.toList().forEach { block -> block.invoke(this) }
        engineBlocks.clear()
    }

    @Before
    fun setUp() {
        MockKAnnotations.init(this, relaxUnitFun = true)
        Dispatchers.setMain(testDispatcher)

        // ── 捕获 launchOnEngine 的 lambda：relaxed mock 默认返回 mock Job 且不执行 ──
        coEvery { gameEngine.launchOnEngine(any()) } answers {
            engineBlocks += args[0] as suspend CoroutineScope.() -> Unit
            mockk<Job>(relaxed = true)
        }

        // ── Stub gameEngine StateFlow 属性（构造期间被 Flow 链引用）──
        // gameData 流由字段持有引用：init 协程的 distinctUntilChanged 依赖同一实例，
        // 测试通过改 gameDataFlow.value 驱动命令总线推送
        gameDataFlow = MutableStateFlow(GameData())
        every { gameEngine.gameData } returns gameDataFlow
        every { gameEngine.discipleAggregates } returns MutableStateFlow(emptyList<DiscipleAggregate>())
        every { gameEngine.disciples } returns MutableStateFlow(emptyList<Disciple>())
        every { gameEngine.equipmentStacks } returns MutableStateFlow(emptyList<EquipmentStack>())
        every { gameEngine.productionSlots } returns MutableStateFlow(emptyList<ProductionSlot>())

        // ── Stub gameDataSnapshot：玩家宗门 HUGE 级，绕过 placeBuilding 的
        //    宗门等级硬检查（relaxed mock 返回空 worldMapSects → SMALL < 建筑要求）──
        every { gameEngine.gameDataSnapshot } returns GameData(
            worldMapSects = listOf(
                WorldSect(id = "player_sect", name = "玩家宗门", level = SectLevel.TOP, isPlayerSect = true)
            )
        )


        // ── 禁用健康检查（GameLoopDelegate 每秒访问 mock 属性 → Kotlin 反射
        //    类加载风暴 → 测试卡死，jstack 实证 JarFile.getVersionedEntry）──
        GameLoopDelegate.healthCheckEnabled = false

        // ── Stub systemManager.errors（init 块中收集）──
        every { systemManager.errors } returns emptyFlow()

        // ── Stub thermalMonitor.thermalState（属性直接赋值）──
        every { thermalMonitor.thermalState } returns MutableStateFlow(ThermalState.NORMAL)

        // ── Stub dailySignInService.getMilestoneRewards()（属性初始化时调用）──
        every { dailySignInService.getMilestoneRewards() } returns emptyList()

        // ── Stub dialogManager.currentDialog（init 块中收集）──
        every { dialogManager.currentDialog } returns MutableStateFlow(null)

        // ── 注册建筑特征注册表（XianxiaApplication.onCreate 在测试环境不执行，
        //    findByDisplayName 返回 null 会使 placeBuilding 提前 return）──
        BuildingFeatureRegistry.registerDefaults()

        // ── Mock GameEngine 扩展函数（定义在 GameEngineCoordination.kt / GameEngineGuideOps.kt）──
        mockkStatic("com.xianxia.sect.core.engine.GameEngineCoordinationKt")
        mockkStatic("com.xianxia.sect.core.engine.GameEngineGuideOpsKt")
        every { gameEngine.setFocusedDiscipleId(any()) } just runs
        every { gameEngine.currentActiveSectId() } returns "test-sect"
        every { gameEngine.notifyUserInteraction() } just runs
        every { gameEngine.setActiveDialog(any()) } just runs
        coEvery { gameEngine.updateGameData(any()) } returns Unit
        coEvery { gameEngine.updateDisciple(any<String>(), any()) } returns Unit


        viewModel = GameViewModel(
            gameEngine,
            GameVmAudioServices(audioConfig, audioEngine),
            GameVmCoreServices(gameEngineCore, systemManager, thermalMonitor),
            GameVmUiServices(dialogManager, adService),
            GameVmDelegateServices(
                dailySignInService, mailService, buildingConfigService,
                buildingFacade, discipleFacade,
                // 2026-08-01：注入 TestDispatcher 替代真实 Dispatchers.IO
                //（旧代码用真实 IO 线程，runTest 的 advanceUntilIdle 等待不到）
                IoDispatcher(testDispatcher),
                sessionManager
            )
        )
    }

    @After
    fun tearDown() {
        GameLoopDelegate.healthCheckEnabled = true
        engineBlocks.clear()
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

        viewModel.placeBuilding("炼丹炉", 3, 3)
        runEngineBlocks()
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

        viewModel.placeBuilding("炼丹炉", 3, 3)
        runEngineBlocks()
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

        viewModel.placeBuilding("炼丹炉", 3, 3)
        runEngineBlocks()
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

        viewModel.placeBuilding("炼丹炉", 4, 5)
        runEngineBlocks()
        advanceUntilIdle()

        assertTrue("updateGameData 闭包应被捕获", lambdaSlot.isCaptured)

        // 用测试数据调用闭包，验证内部逻辑
        val originalData = GameData(spiritStones = 5000L)
        val result = lambdaSlot.captured(originalData)

        assertEquals("灵石应扣除 500", 4500L, result.spiritStones)
        assertEquals("应新增 1 个建筑", 1, result.placedBuildings.size)
        assertEquals("建筑名应为炼丹炉", "炼丹炉", result.placedBuildings[0].displayName)
        assertEquals("建筑 X 坐标应为 4", 4, result.placedBuildings[0].gridX)
        assertEquals("建筑 Y 坐标应为 5", 5, result.placedBuildings[0].gridY)
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

        viewModel.placeBuilding("炼丹炉", 3, 3)
        runEngineBlocks()
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

        viewModel.placeBuilding("炼丹炉", 3, 3)
        runEngineBlocks()
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

        viewModel.placeBuilding("藏经阁", 3, 3)
        runEngineBlocks()
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
        runEngineBlocks()
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
    fun dismissDialog_delegates_to_dialogManager_close() = runTest(testDispatcher) {
        every { dialogManager.close() } just runs
        viewModel.dismissDialog()
        verify { dialogManager.close() }
    }

    @Test
    fun navigateToDialog_delegates_to_dialogManager_open() = runTest(testDispatcher) {
        every { dialogManager.open(any(), any()) } just runs
        viewModel.navigateToDialog(DialogType.Settings)
        verify { dialogManager.open(any(), any()) }
    }

    // ════════════════════════════════════════════════════════════════
    // 场景 4：天枢殿三大自动设置方法
    // ════════════════════════════════════════════════════════════════
    // 覆盖 ViewModel → GameEngine.updateGameData 链路，
    // 验证 setAutoAssignSettings / setBreakthroughAutoPillSettings /
    // setAutoEquipSettings / setAutoLearnSettings /
    // setDaoCompanionBannedRootCounts / setDaoCompanionConsentRequired
    // 六个方法是否正确将参数写入 GameData。

    @Test
    fun `setAutoAssignSettings - 9参数正确映射到 sectPolicies copy`() = runTest(testDispatcher) {
        // 实际调用链是 batchUpdateAutoAssignAndGuide（合并引导计数器，非 updateGameData）
        val newPoliciesSlot = slot<SectPolicies>()
        coEvery { gameEngine.batchUpdateAutoAssignAndGuide(any(), capture(newPoliciesSlot), any(), any(), any()) } just runs

        viewModel.setAutoAssignSettings(
            mineFocused = true, mineRootCounts = listOf(1, 2), mineThreshold = 5,
            alchemyFocused = true, alchemyRootCounts = emptyList(), alchemyThreshold = 1,
            forgeFocused = false, forgeRootCounts = listOf(1, 3, 5), forgeThreshold = 10
        )
        runEngineBlocks()
        advanceUntilIdle()

        assertTrue("newPolicies 应被捕获", newPoliciesSlot.isCaptured)
        val p = newPoliciesSlot.captured

        assertTrue("灵矿 focused 应为 true", p.autoMineFocused)
        assertEquals("灵矿 rootCounts", listOf(1, 2), p.autoMineRootCounts)
        assertEquals("灵矿 threshold", 5, p.autoMineThreshold)

        assertTrue("炼丹 focused 应为 true", p.autoAlchemyFocused)
        assertEquals("炼丹 rootCounts", emptyList<Int>(), p.autoAlchemyRootCounts)
        assertEquals("炼丹 threshold", 1, p.autoAlchemyThreshold)

        assertFalse("炼器 focused 应为 false", p.autoForgeFocused)
        assertEquals("炼器 rootCounts", listOf(1, 3, 5), p.autoForgeRootCounts)
        assertEquals("炼器 threshold", 10, p.autoForgeThreshold)
    }

    @Test
    fun `setAutoAssignSettings - 全默认值时仍正确传递`() = runTest(testDispatcher) {
        val newPoliciesSlot = slot<SectPolicies>()
        coEvery { gameEngine.batchUpdateAutoAssignAndGuide(any(), capture(newPoliciesSlot), any(), any(), any()) } just runs

        viewModel.setAutoAssignSettings(
            mineFocused = false, mineRootCounts = emptyList(), mineThreshold = 1,
            alchemyFocused = false, alchemyRootCounts = emptyList(), alchemyThreshold = 1,
            forgeFocused = false, forgeRootCounts = emptyList(), forgeThreshold = 1
        )
        runEngineBlocks()
        advanceUntilIdle()

        assertTrue("newPolicies 应被捕获", newPoliciesSlot.isCaptured)
        val p = newPoliciesSlot.captured
        assertFalse("灵矿 focused 应为 false", p.autoMineFocused)
        assertEquals("灵矿 rootCounts 应为空", emptyList<Int>(), p.autoMineRootCounts)
        assertEquals("灵矿 threshold", 1, p.autoMineThreshold)
        assertFalse("炼丹 focused 应为 false", p.autoAlchemyFocused)
        assertEquals("炼丹 rootCounts 应为空", emptyList<Int>(), p.autoAlchemyRootCounts)
        assertFalse("炼器 focused 应为 false", p.autoForgeFocused)
        assertEquals("炼器 rootCounts 应为空", emptyList<Int>(), p.autoForgeRootCounts)
    }

    @Test
    fun `setBreakthroughAutoPillSettings - focused和rootCounts正确写入GameData`() = runTest(testDispatcher) {
        val lambdaSlot = slot<(GameData) -> GameData>()
        coEvery { gameEngine.updateGameData(capture(lambdaSlot)) } returns Unit

        viewModel.setBreakthroughAutoPillSettings(focused = true, rootCounts = setOf(1, 3))
        runEngineBlocks()
        advanceUntilIdle()

        val result = lambdaSlot.captured(GameData())
        assertTrue("breakthroughAutoPillFocused 应为 true", result.breakthroughAutoPillFocused)
        assertEquals("breakthroughAutoPillRootCounts", setOf(1, 3), result.breakthroughAutoPillRootCounts)
    }

    @Test
    fun `setAutoEquipSettings - focused和rootCounts正确写入GameData`() = runTest(testDispatcher) {
        val lambdaSlot = slot<(GameData) -> GameData>()
        coEvery { gameEngine.updateGameData(capture(lambdaSlot)) } returns Unit

        viewModel.setAutoEquipSettings(focused = true, rootCounts = setOf(2))
        runEngineBlocks()
        advanceUntilIdle()

        val result = lambdaSlot.captured(GameData())
        assertTrue("autoEquipFromWarehouseFocused 应为 true", result.autoEquipFromWarehouseFocused)
        assertEquals("autoEquipFromWarehouseRootCounts", setOf(2), result.autoEquipFromWarehouseRootCounts)
    }

    @Test
    fun `setAutoLearnSettings - focused=false和空rootCounts正确写入`() = runTest(testDispatcher) {
        val lambdaSlot = slot<(GameData) -> GameData>()
        coEvery { gameEngine.updateGameData(capture(lambdaSlot)) } returns Unit

        viewModel.setAutoLearnSettings(focused = false, rootCounts = emptySet())
        runEngineBlocks()
        advanceUntilIdle()

        val result = lambdaSlot.captured(GameData())
        assertFalse("autoLearnFromWarehouseFocused 应为 false", result.autoLearnFromWarehouseFocused)
        assertEquals("autoLearnFromWarehouseRootCounts", emptySet<Int>(), result.autoLearnFromWarehouseRootCounts)
    }

    @Test
    fun `setDaoCompanionBannedRootCounts - 正确写入GameData`() = runTest(testDispatcher) {
        val lambdaSlot = slot<(GameData) -> GameData>()
        coEvery { gameEngine.updateGameData(capture(lambdaSlot)) } returns Unit

        viewModel.setDaoCompanionBannedRootCounts(setOf(4, 5))
        runEngineBlocks()
        advanceUntilIdle()

        val result = lambdaSlot.captured(GameData())
        assertEquals("daoCompanionBannedRootCounts", setOf(4, 5), result.daoCompanionBannedRootCounts)
    }

    @Test
    fun `setDaoCompanionConsentRequired - 正确写入GameData`() = runTest(testDispatcher) {
        val lambdaSlot = slot<(GameData) -> GameData>()
        coEvery { gameEngine.updateGameData(capture(lambdaSlot)) } returns Unit

        viewModel.setDaoCompanionConsentRequired(required = true)
        runEngineBlocks()
        advanceUntilIdle()

        val result = lambdaSlot.captured(GameData())
        assertTrue("daoCompanionConsentRequired 应为 true", result.daoCompanionConsentRequired)
    }

    // ════════════════════════════════════════════════════════════════
    // 场景 4：宗门改名逻辑（renameSect）
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `renameSect - 更新 sectName 和玩家宗门名称，不影响其他宗门`() = runTest(testDispatcher) {
        val lambdaSlot = slot<(GameData) -> GameData>()
        coEvery { gameEngine.updateGameData(capture(lambdaSlot)) } returns Unit

        val playerSect = WorldSect(id = "player", name = "青云宗", isPlayerSect = true)
        val aiSect = WorldSect(id = "ai-1", name = "血煞宗", isPlayerSect = false)
        val originalData = GameData(
            sectName = "青云宗",
            worldMapSects = listOf(playerSect, aiSect)
        )

        viewModel.renameSect("太虚宗")
        runEngineBlocks()
        advanceUntilIdle()

        val result = lambdaSlot.captured(originalData)
        assertEquals("GameData.sectName 应更新为新名称", "太虚宗", result.sectName)
        assertEquals("玩家宗门名称应更新", "太虚宗", result.worldMapSects.find { it.isPlayerSect }?.name)
        assertEquals("AI 宗门名称不应被修改", "血煞宗", result.worldMapSects.find { !it.isPlayerSect }?.name)
    }

    @Test
    fun `renameSect - 同名更新仍传递到 updateGameData`() = runTest(testDispatcher) {
        val lambdaSlot = slot<(GameData) -> GameData>()
        coEvery { gameEngine.updateGameData(capture(lambdaSlot)) } returns Unit

        val playerSect = WorldSect(id = "player", name = "青云宗", isPlayerSect = true)
        val originalData = GameData(sectName = "青云宗", worldMapSects = listOf(playerSect))

        viewModel.renameSect("青云宗")
        runEngineBlocks()
        advanceUntilIdle()

        val result = lambdaSlot.captured(originalData)
        assertEquals("同名更新应保持不变", "青云宗", result.sectName)
        assertEquals("玩家宗门名称应保持不变", "青云宗", result.worldMapSects.find { it.isPlayerSect }?.name)
    }

    @Test
    fun `renameSect - 调用 dismissDialog`() = runTest(testDispatcher) {
        val lambdaSlot = slot<(GameData) -> GameData>()
        coEvery { gameEngine.updateGameData(capture(lambdaSlot)) } returns Unit

        viewModel.renameSect("太虚宗")
        runEngineBlocks()
        advanceUntilIdle()

        coVerify { gameEngine.updateGameData(any()) }
        verify { gameEngine.setActiveDialog(null) }
    }

    // ════════════════════════════════════════════════════════════════
    // 场景 4：渲染命令总线过滤（2026-08-06 修复：总线只推送 activeSectId 匹配的建筑）
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `init - 命令总线仅推送 activeSectId 匹配的建筑`() = runTest(testDispatcher) {
        val homeMine = GridBuildingData(displayName = "灵矿场", gridX = 10, gridY = 10,
            width = 4, height = 4, sectId = "", instanceId = "m1")
        val aiForge = GridBuildingData(displayName = "锻造坊", gridX = 20, gridY = 20,
            width = 5, height = 3, sectId = "ai-1", instanceId = "f1")
        gameDataFlow.value = GameData(activeSectId = "", placedBuildings = listOf(homeMine, aiForge))
        advanceUntilIdle()

        val bus = viewModel.getRenderCommandBus()
        assertEquals("应只推送 1 栋本宗建筑（AI 宗门建筑不可渲染）", 1, bus.buildingCount)
        assertEquals("应推送本宗矿场（gridX=10）", 10f, bus.buildingData!![0])
    }

    @Test
    fun `init - enterSect 切换宗门后命令总线重推新宗门建筑`() = runTest(testDispatcher) {
        val homeMine = GridBuildingData(displayName = "灵矿场", gridX = 10, gridY = 10,
            width = 4, height = 4, sectId = "", instanceId = "m1")
        val aiForge = GridBuildingData(displayName = "锻造坊", gridX = 20, gridY = 20,
            width = 5, height = 3, sectId = "ai-1", instanceId = "f1")
        gameDataFlow.value = GameData(activeSectId = "", placedBuildings = listOf(homeMine, aiForge))
        advanceUntilIdle()
        assertEquals(1, viewModel.getRenderCommandBus().buildingCount)

        // enterSect 只改 activeSectId，placedBuildings 不变 —— 推送键必须包含 activeSectId 才会重推
        gameDataFlow.value = GameData(activeSectId = "ai-1", placedBuildings = listOf(homeMine, aiForge))
        advanceUntilIdle()

        val bus = viewModel.getRenderCommandBus()
        assertEquals("切换宗门后应重推新宗门建筑", 1, bus.buildingCount)
        assertEquals("应推送 AI 宗门锻造坊（gridX=20）", 20f, bus.buildingData!![0])
    }

    @Test
    fun `init - 当前宗门无建筑时总线推送空数组而非 null`() = runTest(testDispatcher) {
        // 2026-08-06 对抗性审查 F2：空推送必须为非 null 空数组——渲染端
        // `busSnapshot?.data ?: frame.buildingData` 在总线 null 时回退旧 frame，
        // 进入无建筑宗门会闪现/残留前宗门建筑
        val homeMine = GridBuildingData(displayName = "灵矿场", gridX = 0, gridY = 0,
            width = 4, height = 4, sectId = "", instanceId = "m1")
        gameDataFlow.value = GameData(activeSectId = "empty-sect", placedBuildings = listOf(homeMine))
        advanceUntilIdle()

        val bus = viewModel.getRenderCommandBus()
        assertNotNull("空宗门应推送空数组（非 null），防止渲染回退旧 frame", bus.buildingData)
        assertEquals("空数组长度为 0", 0, bus.buildingData!!.size)
        assertEquals(0, bus.buildingCount)
    }

    @Test
    fun `init - 同宗门建筑增删仍触发重推`() = runTest(testDispatcher) {
        val mine = GridBuildingData(displayName = "灵矿场", gridX = 10, gridY = 10,
            width = 4, height = 4, sectId = "", instanceId = "m1")
        val forge = GridBuildingData(displayName = "锻造坊", gridX = 20, gridY = 20,
            width = 5, height = 3, sectId = "", instanceId = "f1")
        gameDataFlow.value = GameData(activeSectId = "", placedBuildings = listOf(mine))
        advanceUntilIdle()
        assertEquals("初始 1 栋建筑", 1, viewModel.getRenderCommandBus().buildingCount)

        gameDataFlow.value = GameData(activeSectId = "", placedBuildings = listOf(mine, forge))
        advanceUntilIdle()
        assertEquals("同宗门新增建筑应重推", 2, viewModel.getRenderCommandBus().buildingCount)
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

