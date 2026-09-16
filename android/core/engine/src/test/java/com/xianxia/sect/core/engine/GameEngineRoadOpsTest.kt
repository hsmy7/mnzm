package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.engine.domain.cultivation.CultivationFacade
import com.xianxia.sect.core.engine.domain.road.RoadFacadeImpl
import com.xianxia.sect.core.nativebridge.FakeGameStateStore
import com.xianxia.sect.core.nativebridge.StateSyncService
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.util.RoadPlacementResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * GameEngineRoadOpsTest — 道路放置/删除 GameEngine 包装层语义。
 *
 * 数据同步契约（w3-13 反向通道删除后，handover §2.82）：AUTHORITATIVE 稳态
 * 放置/删除经 `RoadFacadeImpl` 的 native 事务臂执行并由前向镜像回流；本层
 * 无回导职责（旧"放置后立即增量回导"测试随通道删除一并移除）。JVM 无 .so
 * —— native 臂恒降级，正好覆盖 Kotlin 回退臂的放置/删除语义。
 *
 * 断言面：成功放置扣费落道路；Blocked（灵石不足/格子占用）零变更；
 * 删除成功；canPlaceRoad 与引擎判定一致。
 */
@Category(com.xianxia.sect.core.RobolectricTests::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GameEngineRoadOpsTest {

    /** 组装真实 RoadFacade 的 GameEngine（JVM 降级臂：native 桥未加载）。 */
    private fun buildEngine(store: GameStateStore): GameEngine {
        val roadFacade = RoadFacadeImpl(store, StateSyncService(store))
        // D1：GameEngine 构造时经 Facade 访问器立即求值 highFrequencyData/productionSlots
        // ——stub 链防 NPE（模式同 GameEngineCoordinationTest.EngineTestEnv）
        val mockProductionFacade = mock<com.xianxia.sect.core.engine.domain.production.ProductionFacade>().also {
            whenever(it.productionSlots).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(emptyList()))
        }
        val mockCultivationFacade = mock<CultivationFacade>().also {
            whenever(it.cultivationService).thenReturn(mock())
            whenever(it.discipleService).thenReturn(mock())
            whenever(it.productionFacade).thenReturn(mockProductionFacade)
            whenever(it.roadFacade).thenReturn(roadFacade)
            whenever(it.productionCoordinator).thenReturn(mock())
            whenever(it.buildingFacade).thenReturn(mock())
        }
        val mockEconomyFacade = mock<com.xianxia.sect.core.engine.domain.economy.EconomyFacade>().also {
            val mockInventoryFacade = mock<com.xianxia.sect.core.engine.domain.inventory.InventoryFacade>()
            whenever(mockInventoryFacade.inventorySystem).thenReturn(mock())
            whenever(it.inventoryFacade).thenReturn(mockInventoryFacade)
            whenever(it.mailService).thenReturn(mock())
        }
        val mockCore = mock<GameEngineCore>().also {
            whenever(it.stateSyncServiceRef).thenReturn(StateSyncService(store))
        }
        return GameEngine(
            gameEngineCore = mockCore,
            engineContextDispatcher = FakeEngineContextDispatcher(),
            stateStore = store,
            gameRngManager = GameRngManager(),
            explorationFacade = mock(),
            cultivationFacade = mockCultivationFacade,
            economyFacade = mockEconomyFacade,
            battleFacade = mock()
        )
    }

    private fun newStore(spiritStones: Long = 1000L): FakeGameStateStore =
        FakeGameStateStore().also {
            it.update { gameData = gameData.copy(spiritStones = spiritStones) }
        }

    @Test
    fun `placeRoad 成功 - 扣费并落道路`() {
        val store = newStore(spiritStones = 1000L)
        val engine = buildEngine(store)

        val result = engine.placeRoad(20, 20)

        assertTrue("放置应成功", result is RoadPlacementResult.Success)
        assertEquals("Kotlin 侧灵石已扣（1000-20）", 980L, store.gameDataSnapshot.spiritStones)
        assertTrue("道路已落库", store.gameDataSnapshot.roads.any { it.gridX == 20 && it.gridY == 20 })
    }

    @Test
    fun `placeRoad 灵石不足 - Blocked 且零变更`() {
        val store = newStore(spiritStones = 5L)
        val engine = buildEngine(store)

        val result = engine.placeRoad(20, 20)

        assertTrue("灵石不足应 Blocked", result is RoadPlacementResult.Blocked)
        assertEquals("未扣费", 5L, store.gameDataSnapshot.spiritStones)
        assertTrue("未落道路", store.gameDataSnapshot.roads.isEmpty())
    }

    @Test
    fun `placeRoad 被占格 - Blocked 且零变更`() {
        val store = newStore(spiritStones = 1000L)
        // 预先占用 (20,20)：模拟已有建筑占地（sectId 与本宗一致）
        store.update {
            gameData = gameData.copy(
                activeSectId = "s1",
                placedBuildings = gameData.placedBuildings + com.xianxia.sect.core.model.GridBuildingData(
                    buildingId = "b1", displayName = "灵田", gridX = 20, gridY = 20,
                    width = 1, height = 1, sectId = "s1", instanceId = "i1"
                )
            )
        }
        val engine = buildEngine(store)

        val result = engine.placeRoad(20, 20)

        assertTrue("被占格应 Blocked", result is RoadPlacementResult.Blocked)
        assertTrue("未落道路", store.gameDataSnapshot.roads.isEmpty())
    }

    @Test
    fun `removeRoad 成功 - 移除道路`() {
        val store = newStore(spiritStones = 1000L)
        val engine = buildEngine(store)

        engine.placeRoad(20, 20)
        engine.placeRoad(21, 20)
        val removeResult = engine.removeRoad(20, 20)

        assertTrue("删除应成功", removeResult is RoadPlacementResult.Success)
        assertTrue("删除后中心格不在", store.gameDataSnapshot.roads.none { it.gridX == 20 && it.gridY == 20 })
        assertTrue("邻居仍保留", store.gameDataSnapshot.roads.any { it.gridX == 21 && it.gridY == 20 })
    }

    @Test
    fun `canPlaceRoad 与引擎判定一致`() {
        val store = newStore(spiritStones = 1000L)
        val engine = buildEngine(store)

        assertTrue("空地可放置", engine.canPlaceRoad(20, 20))
        engine.placeRoad(20, 20)
        assertTrue("已放道路的格子不可再放", !engine.canPlaceRoad(20, 20))
        // 可建环外（边界树带）不可放
        assertTrue("越界可建环外不可放", !engine.canPlaceRoad(0, 0))
        assertTrue("GameConfig 常量一致", GameConfig.Road.COST_PER_CELL == 20L)
    }
}
