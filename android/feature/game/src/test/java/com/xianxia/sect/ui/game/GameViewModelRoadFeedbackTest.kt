package com.xianxia.sect.ui.game

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.audio.AudioConfig
import com.xianxia.sect.core.audio.AudioPlayerFacade
import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.domain.dialog.DialogManager
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.domain.building.BuildingFacade
import com.xianxia.sect.core.engine.domain.disciple.DiscipleFacade
import com.xianxia.sect.core.engine.notifyUserInteraction
import com.xianxia.sect.core.engine.placeRoad
import com.xianxia.sect.core.engine.removeRoad
import com.xianxia.sect.core.engine.service.AdService
import com.xianxia.sect.core.engine.service.MailService
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.perf.ThermalMonitor
import com.xianxia.sect.core.perf.ThermalState
import com.xianxia.sect.core.engine.system.SystemManager
import com.xianxia.sect.core.util.RoadPlacementResult
import com.xianxia.sect.data.SessionManager
import com.xianxia.sect.ui.game.delegate.GameLoopDelegate
import com.xianxia.sect.ui.game.perf.GpuTierDetector
import com.xianxia.sect.ui.game.sect.SurfaceProviderFactory
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * GameViewModelRoadFeedbackTest — 道路放置/删除失败反馈测试。
 *
 * 守护契约：`GameViewModel.placeRoad/removeRoad` 不再静默丢弃引擎结果——`Blocked`
 * （灵石不足/格子不可用/已是道路）必须经 `BaseViewModel.showError` 事件通道提示玩家。
 * 成功路径不产生错误事件。
 *
 * 平台：MockK + TestDispatcher（launchOnEngine 捕获模式同 GameViewModelTest）；
 * placeRoad/removeRoad 为 GameEngineRoadOpsKt 顶层扩展，经 mockkStatic 拦截。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GameViewModelRoadFeedbackTest {

    private val gameEngine: GameEngine = mockk(relaxed = true)
    private val gameEngineCore: GameEngineCore = mockk(relaxed = true)
    private val systemManager: SystemManager = mockk(relaxed = true)
    private val buildingConfigService: BuildingConfigService = mockk(relaxed = true)
    private val mailService: MailService = mockk(relaxed = true)
    private val discipleFacade: DiscipleFacade = mockk(relaxed = true)
    private val buildingFacade: BuildingFacade = mockk(relaxed = true)
    private val thermalMonitor: ThermalMonitor = mockk(relaxed = true)
    private val dialogManager: DialogManager = mockk(relaxed = true)
    private val adService: AdService = mockk(relaxed = true)
    private val audioConfig: AudioConfig = mockk(relaxed = true)
    private val audioEngine: AudioPlayerFacade = mockk(relaxed = true)
    private val sessionManager: SessionManager = mockk(relaxed = true)

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var viewModel: GameViewModel

    /** launchOnEngine 捕获列表（同 GameViewModelTest——relaxed mock 不执行 lambda）。 */
    private val engineBlocks = mutableListOf<suspend kotlinx.coroutines.CoroutineScope.() -> Unit>()

    private suspend fun TestScope.runEngineBlocks() {
        engineBlocks.toList().forEach { block -> block.invoke(this) }
        engineBlocks.clear()
    }

    @Before
    fun setUp() {
        MockKAnnotations.init(this, relaxUnitFun = true)
        Dispatchers.setMain(testDispatcher)

        coEvery { gameEngine.launchOnEngine(any()) } answers {
            engineBlocks += args[0] as suspend kotlinx.coroutines.CoroutineScope.() -> Unit
            mockk<Job>(relaxed = true)
        }

        every { gameEngine.gameData } returns MutableStateFlow(GameData())
        every { gameEngine.resourcesHeader } returns MutableStateFlow(
            com.xianxia.sect.core.gameview.GameViewStore.RESOURCES_EMPTY
        )
        every { gameEngine.discipleAggregates } returns MutableStateFlow(emptyList<DiscipleAggregate>())
        every { gameEngine.disciples } returns MutableStateFlow(emptyList<Disciple>())
        every { gameEngine.equipmentStacks } returns MutableStateFlow(emptyList<EquipmentStack>())
        every { gameEngine.productionSlots } returns MutableStateFlow(emptyList<ProductionSlot>())
        every { gameEngine.gameDataSnapshot } returns GameData()

        GameLoopDelegate.healthCheckEnabled = false
        every { systemManager.errors } returns emptyFlow()
        every { thermalMonitor.thermalState } returns MutableStateFlow(ThermalState.NORMAL)
        every { dialogManager.currentDialog } returns MutableStateFlow(null)

        // placeRoad/removeRoad 是 GameEngineRoadOps.kt 顶层扩展——mockkStatic 拦截
        mockkStatic("com.xianxia.sect.core.engine.GameEngineRoadOpsKt")
        every { gameEngine.notifyUserInteraction() } just runs

        viewModel = GameViewModel(
            gameEngine,
            GameVmAudioServices(audioConfig, audioEngine),
            GameVmCoreServices(gameEngineCore, systemManager, thermalMonitor),
            GameVmUiServices(dialogManager, adService),
            GameVmDelegateServices(
                mailService, buildingConfigService,
                buildingFacade, discipleFacade,
                com.xianxia.sect.core.engine.di.IoDispatcher(testDispatcher),
                sessionManager,
                GpuTierDetector()
            ),
            SurfaceProviderFactory { mockk() }
        )
    }

    @After
    fun tearDown() {
        GameLoopDelegate.healthCheckEnabled = true
        Dispatchers.resetMain()
    }

    @Test
    fun `placeRoad Blocked - 经 showError 提示玩家`() = runTest(testDispatcher) {
        every { gameEngine.placeRoad(any(), any()) } returns RoadPlacementResult.Blocked("灵石不足")

        val errors = mutableListOf<String>()
        val collector = launch(UnconfinedTestDispatcher(testDispatcher.scheduler)) {
            viewModel.errorEvents.collect { errors += it }
        }

        viewModel.road.placeRoad(20, 20)
        runEngineBlocks()
        advanceUntilIdle()

        assertTrue("Blocked 必须提示玩家，实际收到: $errors", errors.contains("灵石不足"))
        collector.cancel()
    }

    @Test
    fun `placeRoad Blocked 格子不可用 - 提示原因`() = runTest(testDispatcher) {
        every { gameEngine.placeRoad(any(), any()) } returns RoadPlacementResult.Blocked("该格已有建筑或固定结构")

        val errors = mutableListOf<String>()
        val collector = launch(UnconfinedTestDispatcher(testDispatcher.scheduler)) {
            viewModel.errorEvents.collect { errors += it }
        }

        viewModel.road.placeRoad(10, 10)
        runEngineBlocks()
        advanceUntilIdle()

        assertTrue("应提示占用原因，实际收到: $errors", errors.contains("该格已有建筑或固定结构"))
        collector.cancel()
    }

    @Test
    fun `placeRoad Success - 不产生错误事件`() = runTest(testDispatcher) {
        every { gameEngine.placeRoad(any(), any()) } returns RoadPlacementResult.Success()

        val errors = mutableListOf<String>()
        val collector = launch(UnconfinedTestDispatcher(testDispatcher.scheduler)) {
            viewModel.errorEvents.collect { errors += it }
        }

        viewModel.road.placeRoad(20, 20)
        runEngineBlocks()
        advanceUntilIdle()

        assertEquals("成功放置不应有错误提示", emptyList<String>(), errors)
        collector.cancel()
    }

    @Test
    fun `removeRoad Blocked - 经 showError 提示玩家`() = runTest(testDispatcher) {
        every { gameEngine.removeRoad(any(), any()) } returns RoadPlacementResult.Blocked("该格没有道路")

        val errors = mutableListOf<String>()
        val collector = launch(UnconfinedTestDispatcher(testDispatcher.scheduler)) {
            viewModel.errorEvents.collect { errors += it }
        }

        viewModel.road.removeRoad(20, 20)
        runEngineBlocks()
        advanceUntilIdle()

        assertTrue("删除失败应提示，实际收到: $errors", errors.contains("该格没有道路"))
        collector.cancel()
    }

    @Test
    fun `道路造价常量与引擎一致`() {
        // 守卫：UI 无独立造价，消耗统一收敛于 GameConfig.Road（防 UI/引擎漂移）
        assertEquals("道路造价应统一为 20 灵石/格", 20L, GameConfig.Road.COST_PER_CELL)
    }
}
