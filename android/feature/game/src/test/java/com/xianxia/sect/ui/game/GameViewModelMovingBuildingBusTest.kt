package com.xianxia.sect.ui.game

import com.xianxia.sect.core.SectLevel
import com.xianxia.sect.core.audio.AudioConfig
import com.xianxia.sect.core.audio.AudioEngine
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.currentActiveSectId
import com.xianxia.sect.core.engine.di.IoDispatcher
import com.xianxia.sect.core.engine.domain.building.BuildingFacade
import com.xianxia.sect.core.engine.domain.disciple.DiscipleFacade
import com.xianxia.sect.core.engine.service.MailService
import com.xianxia.sect.core.engine.system.SystemManager
import com.xianxia.sect.core.engine.notifyUserInteraction
import com.xianxia.sect.core.engine.setActiveDialog
import com.xianxia.sect.core.engine.setFocusedDiscipleId
import com.xianxia.sect.core.engine.updateDisciple
import com.xianxia.sect.core.engine.updateGameData
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.perf.GpuTierDetector
import com.xianxia.sect.core.perf.ThermalMonitor
import com.xianxia.sect.core.perf.ThermalState
import com.xianxia.sect.data.SessionManager
import com.xianxia.sect.ui.game.delegate.GameLoopDelegate
import com.xianxia.sect.ui.game.sect.SurfaceProviderFactory
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * GameViewModel 移动中建筑总线排除测试（D-12，2026-08-06）。
 *
 * 从 GameViewModelTest 拆出（该场景式集成测试类已超 detekt TooLargeClass 600 行阈值）：
 * 总线推送键为 (activeSectId, placedBuildings, movingId) 三元组，移动中建筑被排除——
 * 拖拽窗口期不再双渲染/点不中/可叠建。
 */
class GameViewModelMovingBuildingBusTest {

    private val gameEngine: GameEngine = mockk(relaxed = true)
    private val gameEngineCore: GameEngineCore = mockk(relaxed = true)
    private val systemManager: SystemManager = mockk(relaxed = true)
    private val buildingConfigService: com.xianxia.sect.core.config.BuildingConfigService = mockk(relaxed = true)
    private val mailService: MailService = mockk(relaxed = true)
    private val discipleFacade: DiscipleFacade = mockk(relaxed = true)
    private val buildingFacade: BuildingFacade = mockk(relaxed = true)
    private val thermalMonitor: ThermalMonitor = mockk(relaxed = true)
    private val dialogManager: com.xianxia.sect.core.domain.dialog.DialogManager = mockk(relaxed = true)
    private val adService: com.xianxia.sect.core.engine.service.AdService = mockk(relaxed = true)
    private val audioConfig: AudioConfig = mockk(relaxed = true)
    private val audioEngine: AudioEngine = mockk(relaxed = true)
    private val sessionManager: SessionManager = mockk(relaxed = true)

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var viewModel: GameViewModel

    /** gameData 流（setUp 中 stub 为 [gameEngine.gameData] 的返回，改 .value 驱动命令总线推送） */
    private lateinit var gameDataFlow: MutableStateFlow<GameData>

    private val engineBlocks = mutableListOf<suspend CoroutineScope.() -> Unit>()

    @Before
    fun setUp() {
        MockKAnnotations.init(this, relaxUnitFun = true)
        Dispatchers.setMain(testDispatcher)

        coEvery { gameEngine.launchOnEngine(any()) } answers {
            engineBlocks += args[0] as suspend CoroutineScope.() -> Unit
            mockk<Job>(relaxed = true)
        }

        gameDataFlow = MutableStateFlow(GameData())
        every { gameEngine.gameData } returns gameDataFlow
        every { gameEngine.discipleAggregates } returns MutableStateFlow(emptyList<DiscipleAggregate>())
        every { gameEngine.disciples } returns MutableStateFlow(emptyList<Disciple>())
        every { gameEngine.equipmentStacks } returns MutableStateFlow(emptyList<EquipmentStack>())
        every { gameEngine.productionSlots } returns MutableStateFlow(emptyList<ProductionSlot>())
        every { gameEngine.gameDataSnapshot } returns GameData(
            worldMapSects = listOf(com.xianxia.sect.core.model.WorldSect(
                id = "player_sect", name = "玩家宗门", level = SectLevel.TOP, isPlayerSect = true
            ))
        )

        GameLoopDelegate.healthCheckEnabled = false
        every { systemManager.errors } returns emptyFlow()
        every { thermalMonitor.thermalState } returns MutableStateFlow(ThermalState.NORMAL)
        every { dialogManager.currentDialog } returns MutableStateFlow(null)

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
                mailService, buildingConfigService,
                buildingFacade, discipleFacade,
                IoDispatcher(testDispatcher),
                sessionManager,
                // 2026-08-14 平板省电：GPU 档位检测（本测试不触达渲染路径，detect 不调用）
                GpuTierDetector()
            ),
            // 2026-08-13 平台抽象：surface 提供者工厂（本测试不触达渲染路径）
            SurfaceProviderFactory { mockk() }
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
    // 移动中建筑总线排除（D-12，2026-08-06）
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `setMovingBuildingInstanceId_拖拽中总线排除该建筑`() = runTest(testDispatcher) {
        val mine = GridBuildingData(displayName = "灵矿场", gridX = 10, gridY = 10,
            width = 4, height = 4, sectId = "", instanceId = "m1")
        val forge = GridBuildingData(displayName = "锻造坊", gridX = 20, gridY = 20,
            width = 5, height = 3, sectId = "", instanceId = "f1")
        gameDataFlow.value = GameData(activeSectId = "", placedBuildings = listOf(mine, forge))
        advanceUntilIdle()
        assertEquals("初始 2 栋建筑", 2, viewModel.getRenderCommandBus().buildingCount)

        // 长按拖拽 forge（Compose 侧将其从交互索引排除）→ 总线同步排除，消除双渲染
        viewModel.setMovingBuildingInstanceId("f1")
        advanceUntilIdle()
        val bus = viewModel.getRenderCommandBus()
        assertEquals("拖拽中总线应排除该建筑", 1, bus.buildingCount)
        assertEquals("剩余推送应为矿场（gridX=10）", 10f, bus.buildingData!![0])
    }

    @Test
    fun `setMovingBuildingInstanceId_清空后总线恢复该建筑`() = runTest(testDispatcher) {
        val mine = GridBuildingData(displayName = "灵矿场", gridX = 10, gridY = 10,
            width = 4, height = 4, sectId = "", instanceId = "m1")
        gameDataFlow.value = GameData(activeSectId = "", placedBuildings = listOf(mine))
        advanceUntilIdle()

        viewModel.setMovingBuildingInstanceId("m1")
        advanceUntilIdle()
        assertEquals("拖拽中排除", 0, viewModel.getRenderCommandBus().buildingCount)

        // 确认/取消移动 → 通道清空 → 总线恢复
        viewModel.setMovingBuildingInstanceId(null)
        advanceUntilIdle()
        val bus = viewModel.getRenderCommandBus()
        assertEquals("清空后恢复推送", 1, bus.buildingCount)
        assertEquals(10f, bus.buildingData!![0])
    }

    @Test
    fun `init - 移动中建筑与 enterSect 重推互不冲突`() = runTest(testDispatcher) {
        val homeMine = GridBuildingData(displayName = "灵矿场", gridX = 10, gridY = 10,
            width = 4, height = 4, sectId = "", instanceId = "m1")
        val aiForge = GridBuildingData(displayName = "锻造坊", gridX = 20, gridY = 20,
            width = 5, height = 3, sectId = "ai-1", instanceId = "f1")
        gameDataFlow.value = GameData(activeSectId = "", placedBuildings = listOf(homeMine, aiForge))
        advanceUntilIdle()

        // 拖拽期间切宗门：movingId 保留但只影响新宗门作用域（本宗建筑本就不过滤）
        viewModel.setMovingBuildingInstanceId("f1")
        gameDataFlow.value = GameData(activeSectId = "ai-1", placedBuildings = listOf(homeMine, aiForge))
        advanceUntilIdle()

        val bus = viewModel.getRenderCommandBus()
        assertEquals("切宗门后重推 AI 宗门建筑，且拖拽中的 f1 被排除", 0, bus.buildingCount)
    }
}
