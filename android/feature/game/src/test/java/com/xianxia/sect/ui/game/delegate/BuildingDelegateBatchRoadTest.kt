package com.xianxia.sect.ui.game.delegate

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.domain.building.BuildingFacade
import com.xianxia.sect.core.engine.placeRoad
import com.xianxia.sect.core.engine.updateGameData
import com.xianxia.sect.ui.game.sect.GoldFingerState
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * BuildingDelegateBatchRoadTest — 金手指一键批量建造石板路测试（2026-08-31 根因修复配套）。
 *
 * 守护契约：石板路不在 BuildingFeatureRegistry（doPlaceBuilding 对"石板路"早退），
 * 金手指批量道路必须逐格走 `GameEngine.placeRoad`（每格扣 20 灵石、落 roads、即时回导）——
 * 旧实现走 doPlaceBuilding → findByDisplayName null 早退 → 批量道路 0 建 0 扣，
 * 玩家实测"批量建造只扣 20 灵石但不建路"（那 20 来自批量前的单击）。
 */
class BuildingDelegateBatchRoadTest {

    @MockK(relaxed = true)
    private lateinit var gameEngine: GameEngine

    @MockK(relaxed = true)
    private lateinit var buildingFacade: BuildingFacade

    @MockK(relaxed = true)
    private lateinit var buildingConfigService: BuildingConfigService

    private val testDispatcher = StandardTestDispatcher()

    private val engineBlocks = mutableListOf<suspend CoroutineScope.() -> Unit>()

    private lateinit var delegate: BuildingDelegate

    private fun packCell(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFF_FFFF)

    /** 执行所有捕获的引擎块并清空（relaxed mock 的 launchOnEngine 不执行 lambda）。 */
    private suspend fun TestScope.runEngineBlocks() {
        engineBlocks.toList().forEach { block -> block.invoke(this) }
        engineBlocks.clear()
    }

    @Before
    fun setUp() {
        MockKAnnotations.init(this, relaxUnitFun = true)
        Dispatchers.setMain(testDispatcher)

        coEvery { gameEngine.launchOnEngine(any()) } answers {
            engineBlocks += args[0] as suspend CoroutineScope.() -> Unit
            mockk<Job>(relaxed = true)
        }

        // placeRoad/removeRoad 是 GameEngineRoadOpsKt 顶层扩展——mockkStatic 拦截并记录
        mockkStatic("com.xianxia.sect.core.engine.GameEngineRoadOpsKt")
        every { gameEngine.placeRoad(any(), any()) } returns mockk()

        // 普通建筑路径（对照组）走 updateGameData（GameEngineCoordinationKt 顶层扩展）
        mockkStatic("com.xianxia.sect.core.engine.GameEngineCoordinationKt")
        coEvery { gameEngine.updateGameData(any()) } returns Unit

        // 对照组"炼丹炉"的网格尺寸（doPlaceBuilding 解构 getBuildingGridSize 需非 null）
        every { buildingConfigService.getBuildingGridSize("炼丹炉") } returns Pair(4, 3)

        delegate = BuildingDelegate(
            gameEngine = gameEngine,
            buildingFacade = buildingFacade,
            buildingConfigService = buildingConfigService,
            dispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `批量石板路 - 逐格调用 placeRoad`() = runTest(testDispatcher) {
        // 金手指框选 3 格（两格 valid + 一格 invalid）
        val gf = GoldFingerState(
            isActive = true,
            buildingName = GameConfig.Road.DISPLAY_NAME,
            buildingSize = com.xianxia.sect.core.util.GridSnapHelper.BuildingSize(1, 1),
            buildingCost = GameConfig.Road.COST_PER_CELL,
            canBuildCount = 2,
            cellValidity = mapOf(
                packCell(20, 20) to true,
                packCell(21, 20) to true,
                packCell(22, 20) to false
            )
        )

        delegate.batchPlaceBuilding(gf)
        runEngineBlocks()
        advanceUntilIdle()

        verify(exactly = 1) { gameEngine.placeRoad(20, 20) }
        verify(exactly = 1) { gameEngine.placeRoad(21, 20) }
        verify(exactly = 0) { gameEngine.placeRoad(22, 20) }
    }

    @Test
    fun `批量石板路 - 空选区或未激活零调用`() = runTest(testDispatcher) {
        delegate.batchPlaceBuilding(GoldFingerState())
        delegate.batchPlaceBuilding(
            GoldFingerState(isActive = true, buildingName = GameConfig.Road.DISPLAY_NAME, canBuildCount = 0)
        )
        runEngineBlocks()
        advanceUntilIdle()

        verify(exactly = 0) { gameEngine.placeRoad(any(), any()) }
    }

    @Test
    fun `批量普通建筑 - 不走 placeRoad`() = runTest(testDispatcher) {
        val gf = GoldFingerState(
            isActive = true,
            buildingName = "炼丹炉",
            buildingSize = com.xianxia.sect.core.util.GridSnapHelper.BuildingSize(4, 3),
            buildingCost = 500L,
            canBuildCount = 1,
            cellValidity = mapOf(packCell(10, 10) to true)
        )

        delegate.batchPlaceBuilding(gf)
        runEngineBlocks()
        advanceUntilIdle()

        // 普通建筑走 doPlaceBuilding（updateGameData），不触碰道路路径
        verify(exactly = 0) { gameEngine.placeRoad(any(), any()) }
    }
}
