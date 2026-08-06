package com.xianxia.sect.ui.game.delegate

import com.xianxia.sect.core.config.BuildingConfigModel
import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.currentActiveSectId
import com.xianxia.sect.core.engine.updateGameData
import com.xianxia.sect.core.engine.domain.building.BuildingFacade
import com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.ui.game.building.registerDefaults
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
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
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 引擎层放置重叠防御测试（2026-08-06 第一性原理兜底）。
 *
 * 覆盖：
 * - doPlaceBuilding 同宗门重叠 → 拒绝放置（不新增、不扣灵石）
 * - 跨宗门同坐标 → 放行（不同宗门独立网格）
 * - 无重叠 → 正常放置
 * - overlapsExisting 纯函数边界
 */
class BuildingDelegateOverlapTest {

    @MockK(relaxed = true)
    private lateinit var gameEngine: GameEngine

    @MockK(relaxed = true)
    private lateinit var buildingFacade: BuildingFacade

    @MockK(relaxed = true)
    private lateinit var buildingConfigService: BuildingConfigService

    private val testDispatcher = StandardTestDispatcher()

    /** launchOnEngine 捕获列表（与 GameViewModelTest 同款：relaxed mock 不执行 lambda） */
    private val engineBlocks = mutableListOf<suspend CoroutineScope.() -> Unit>()

    /** updateGameData 的执行状态：lambda 在此真实执行，模拟引擎事务 */
    private var currentData = GameData(
        spiritStones = 100_000L,
        worldMapSects = listOf(com.xianxia.sect.core.model.WorldSect(
            id = "player_sect", isPlayerSect = true,
            level = com.xianxia.sect.core.SectLevel.TOP
        ))
    )

    private lateinit var delegate: BuildingDelegate

    @Before
    fun setUp() {
        MockKAnnotations.init(this, relaxUnitFun = true)
        Dispatchers.setMain(testDispatcher)

        coEvery { gameEngine.launchOnEngine(any()) } answers {
            engineBlocks += args[0] as suspend CoroutineScope.() -> Unit
            mockk<Job>(relaxed = true)
        }

        // updateGameData 是顶层扩展（编译为静态方法，args[0]=receiver gameEngine，args[1]=lambda）
        mockkStatic("com.xianxia.sect.core.engine.GameEngineCoordinationKt")
        coEvery { gameEngine.updateGameData(any()) } answers {
            val update = args[1] as (GameData) -> GameData
            currentData = update.invoke(currentData)
        }
        every { gameEngine.currentActiveSectId() } returns ""

        every { buildingConfigService.getBuildingConfigByDisplayName("炼丹炉") } returns
            BuildingConfigModel(
                id = "alchemy", displayName = "炼丹炉",
                buildingType = "ALCHEMY", cost = 500L,
                gridWidth = 4, gridHeight = 3
            )
        every { buildingConfigService.getBuildingGridSize("炼丹炉") } returns Pair(4, 3)

        // 建筑特征注册表（XianxiaApplication.onCreate 在测试环境不执行）
        BuildingFeatureRegistry.registerDefaults()

        // 重叠拒绝路径的 Log.w（纯 JVM 环境 android.util.Log 未 mock）
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>(), any()) } returns 0
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0

        delegate = BuildingDelegate(
            gameEngine = gameEngine,
            buildingFacade = buildingFacade,
            buildingConfigService = buildingConfigService,
            dispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        engineBlocks.clear()
        Dispatchers.resetMain()
        unmockkAll()
    }

    /** 执行所有捕获的引擎块并清空（TestScope 实现 CoroutineScope） */
    private suspend fun TestScope.runEngineBlocks() {
        engineBlocks.toList().forEach { block -> block.invoke(this) }
        engineBlocks.clear()
    }

    private fun gameDataWith(buildings: List<GridBuildingData>): GameData = GameData(
        spiritStones = 100_000L,
        worldMapSects = listOf(com.xianxia.sect.core.model.WorldSect(
            id = "player_sect", isPlayerSect = true,
            level = com.xianxia.sect.core.SectLevel.TOP
        )),
        placedBuildings = buildings
    )

    // ================================================================
    // doPlaceBuilding 重叠防御
    // ================================================================

    @Test
    fun `doPlaceBuilding_sameSectOverlap_拒绝放置且不扣灵石`() = runTest(testDispatcher) {
        // 已有炼丹炉 (10,10,4×3) sectId=""（本宗）
        currentData = gameDataWith(
            listOf(GridBuildingData(displayName = "炼丹炉", gridX = 10, gridY = 10,
                width = 4, height = 3, sectId = "", instanceId = "existing"))
        )

        delegate.placeBuilding("炼丹炉", 11, 11)  // 与已有重叠
        runEngineBlocks()
        advanceUntilIdle()

        assertEquals("重叠放置应被拒绝，不新增建筑", 1, currentData.placedBuildings.size)
        assertEquals("重叠放置不应扣灵石", 100_000L, currentData.spiritStones)
    }

    @Test
    fun `doPlaceBuilding_differentSectSameCoords_放行`() = runTest(testDispatcher) {
        // 已有建筑属于 AI 宗门（sectId="sect_ai"）——不同宗门独立网格，本宗放置不受影响
        currentData = gameDataWith(
            listOf(GridBuildingData(displayName = "炼丹炉", gridX = 10, gridY = 10,
                width = 4, height = 3, sectId = "sect_ai", instanceId = "ai_alchemy"))
        )

        delegate.placeBuilding("炼丹炉", 10, 10)
        runEngineBlocks()
        advanceUntilIdle()

        assertEquals("跨宗门同坐标应放行", 2, currentData.placedBuildings.size)
        assertEquals("放行应扣灵石", 99_500L, currentData.spiritStones)
    }

    @Test
    fun `doPlaceBuilding_noOverlap_正常放置`() = runTest(testDispatcher) {
        delegate.placeBuilding("炼丹炉", 10, 10)
        runEngineBlocks()
        advanceUntilIdle()

        assertEquals("无重叠正常放置", 1, currentData.placedBuildings.size)
        assertEquals("放置应扣灵石", 99_500L, currentData.spiritStones)
        val placed = currentData.placedBuildings.single()
        assertEquals("新建筑应带当前宗门归属", "", placed.sectId)
    }

    // ================================================================
    // overlapsExisting 纯函数边界
    // ================================================================

    @Test
    fun `overlapsExisting_sameSectOverlap_true`() {
        val buildings = listOf(
            GridBuildingData(displayName = "A", gridX = 10, gridY = 10, width = 4, height = 3,
                sectId = "", instanceId = "a")
        )
        assert(overlapsExisting(buildings, sectId = "", gridX = 12, gridY = 11, width = 4, height = 3))
    }

    @Test
    fun `overlapsExisting_sameSectNoOverlap_false`() {
        val buildings = listOf(
            GridBuildingData(displayName = "A", gridX = 10, gridY = 10, width = 4, height = 3,
                sectId = "", instanceId = "a")
        )
        // 紧贴右侧不重叠（x 相接）
        assert(!overlapsExisting(buildings, sectId = "", gridX = 14, gridY = 10, width = 4, height = 3))
        // 紧贴下方不重叠（y 相接）
        assert(!overlapsExisting(buildings, sectId = "", gridX = 10, gridY = 13, width = 4, height = 3))
    }

    @Test
    fun `overlapsExisting_differentSectSameCoords_false`() {
        val buildings = listOf(
            GridBuildingData(displayName = "A", gridX = 10, gridY = 10, width = 4, height = 3,
                sectId = "sect_ai", instanceId = "a")
        )
        assert(!overlapsExisting(buildings, sectId = "", gridX = 10, gridY = 10, width = 4, height = 3))
    }

    @Test
    fun `overlapsExisting_emptyBuildings_false`() {
        assert(!overlapsExisting(emptyList(), sectId = "", gridX = 10, gridY = 10, width = 4, height = 3))
    }
}
