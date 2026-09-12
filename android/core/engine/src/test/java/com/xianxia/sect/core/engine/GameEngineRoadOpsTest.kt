package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.domain.cultivation.CultivationFacade
import com.xianxia.sect.core.engine.domain.road.RoadFacadeImpl
import com.xianxia.sect.core.nativebridge.FakeGameStateStore
import com.xianxia.sect.core.nativebridge.StateSyncService
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.util.RoadPlacementResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
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
 * GameEngineRoadOpsTest — 道路放置/删除即时回导测试。
 *
 * 守护契约：placeRoad/removeRoad 成功后必须**立即**把本事务（含灵石扣除）增量回导
 * C++ 真相源——AUTHORITATIVE tick 步骤 ②' 的 `resetReverseAccumulator()` 会无条件
 * 清空反向捕获累加器，若只靠 tick 步骤 ⑤ 顺带回导，放置在 tick 间隙的捕获被清空 →
 * C++ 永远不知晓灵石扣除 → 后续前向镜像覆盖 Kotlin → "灵石未扣除"。
 *
 * 断言面：成功放置/删除后反向信封必达（含扣后灵石）；Blocked（灵石不足/格子占用）
 * 零发送；回导失败降级全量不崩溃。
 */
@Category(com.xianxia.sect.core.RobolectricTests::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GameEngineRoadOpsTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    /** 记录型反向发送器：记录收到的信封 JSON，返回可配置结果。 */
    private class RecordingSender : (ByteArray) -> Boolean {
        val envelopes = mutableListOf<String>()
        var result = true
        override fun invoke(bytes: ByteArray): Boolean {
            envelopes.add(bytes.decodeToString())
            return result
        }
    }

    /** 组装真实 RoadFacade + 真实 StateSyncService（注入记录发送器）的 GameEngine。 */
    private fun buildEngine(
        store: GameStateStore,
        sender: RecordingSender
    ): GameEngine {
        val roadFacade = RoadFacadeImpl(store)
        val sync = StateSyncService(store, sender)
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
            whenever(it.stateSyncServiceRef).thenReturn(sync)
        }
        return GameEngine(
            gameEngineCore = mockCore,
            engineContextDispatcher = FakeEngineContextDispatcher(),
            stateStore = store,
            gameRngManager = mock(),
            explorationFacade = mock(),
            cultivationFacade = mockCultivationFacade,
            economyFacade = mockEconomyFacade,
            battleFacade = mock()
        )
    }

    /** 信封 JSON → changed.gameData.spiritStones（结构不匹配返回 null——解析辅助语义）。 */
    @Suppress("SwallowedException")  // 结构不匹配返回 null 是本辅助的既定契约（断言方判空报错）
    private fun String.envelopeSpiritStones(): Long? = try {
        json.parseToJsonElement(this).jsonObject["changed"]?.jsonObject
            ?.get("gameData")?.jsonObject
            ?.get("spiritStones")?.jsonPrimitive?.longOrNull
    } catch (e: Exception) {
        null
    }

    private fun newStore(spiritStones: Long = 1000L): FakeGameStateStore =
        FakeGameStateStore().also {
            it.update { gameData = gameData.copy(spiritStones = spiritStones) }
        }

    @Test
    fun `placeRoad 成功 - 立即回导且信封含扣后灵石`() {
        val store = newStore(spiritStones = 1000L)
        val sender = RecordingSender()
        val engine = buildEngine(store, sender)

        val result = engine.placeRoad(20, 20)

        assertTrue("放置应成功", result is RoadPlacementResult.Success)
        assertEquals("放置后应立即回导 C++（1 封）", 1, sender.envelopes.size)
        assertEquals(
            "回导信封应携带扣后灵石（1000-20）",
            980L,
            sender.envelopes.single().envelopeSpiritStones()
        )
        assertEquals("Kotlin 侧灵石已扣", 980L, store.gameDataSnapshot.spiritStones)
    }

    @Test
    fun `placeRoad 连续放置 - 每格各自回导扣后灵石`() {
        val store = newStore(spiritStones = 1000L)
        val sender = RecordingSender()
        val engine = buildEngine(store, sender)

        engine.placeRoad(20, 20)
        engine.placeRoad(21, 20)

        assertEquals("两次放置各回导一次", 2, sender.envelopes.size)
        assertEquals("第一次回导 980", 980L, sender.envelopes[0].envelopeSpiritStones())
        assertEquals("第二次回导 960", 960L, sender.envelopes[1].envelopeSpiritStones())
    }

    @Test
    fun `placeRoad 灵石不足 - Blocked 且不回导`() {
        val store = newStore(spiritStones = 5L)
        val sender = RecordingSender()
        val engine = buildEngine(store, sender)

        val result = engine.placeRoad(20, 20)

        assertTrue("灵石不足应 Blocked", result is RoadPlacementResult.Blocked)
        assertEquals("Blocked 零发送", 0, sender.envelopes.size)
        assertEquals("未扣费", 5L, store.gameDataSnapshot.spiritStones)
    }

    @Test
    fun `placeRoad 被占格 - Blocked 且不回导`() {
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
        val sender = RecordingSender()
        val engine = buildEngine(store, sender)

        val result = engine.placeRoad(20, 20)

        assertTrue("被占格应 Blocked", result is RoadPlacementResult.Blocked)
        assertEquals("Blocked 零发送", 0, sender.envelopes.size)
        assertTrue("未落道路", store.gameDataSnapshot.roads.isEmpty())
    }

    @Test
    fun `removeRoad 成功 - 回导`() {
        val store = newStore(spiritStones = 1000L)
        val sender = RecordingSender()
        val engine = buildEngine(store, sender)

        engine.placeRoad(20, 20)
        engine.placeRoad(21, 20)
        val removeResult = engine.removeRoad(20, 20)

        assertTrue("删除应成功", removeResult is RoadPlacementResult.Success)
        assertEquals("两次放置 + 一次删除 = 3 封回导", 3, sender.envelopes.size)
        assertTrue("删除后中心格不在", store.gameDataSnapshot.roads.none { it.gridX == 20 && it.gridY == 20 })
    }

    @Test
    fun `回导失败 - 降级全量兜底不崩溃`() {
        val store = newStore(spiritStones = 1000L)
        val sender = RecordingSender().apply { result = false }
        val engine = buildEngine(store, sender)

        // 增量回导失败 → 降级 importToNative 全量（native 未加载时内部静默 false）——
        // 契约：不崩溃、Kotlin 侧状态照常
        val result = engine.placeRoad(20, 20)

        assertTrue("放置本身不受回导失败影响", result is RoadPlacementResult.Success)
        assertTrue("道路仍落库", store.gameDataSnapshot.roads.any { it.gridX == 20 && it.gridY == 20 })
        assertEquals("灵石仍扣", 980L, store.gameDataSnapshot.spiritStones)
    }

    @Test
    fun `canPlaceRoad 与引擎判定一致`() {
        val store = newStore(spiritStones = 1000L)
        val sender = RecordingSender()
        val engine = buildEngine(store, sender)

        assertTrue("空地可放置", engine.canPlaceRoad(20, 20))
        engine.placeRoad(20, 20)
        assertTrue("已放道路的格子不可再放", !engine.canPlaceRoad(20, 20))
        // 可建环外（边界树带）不可放
        assertTrue("越界可建环外不可放", !engine.canPlaceRoad(0, 0))
        assertTrue("GameConfig 常量一致", GameConfig.Road.COST_PER_CELL == 20L)
    }

    @Test
    fun `镜像写入不吞玩家捕获 - 回导仍发送扣后灵石`() {
        // 架构守护：前向镜像经 updateMirror 不参与反向捕获——
        // 玩家操作（放置扣灵石）的捕获在镜像到来后必须保留，tick ⑤ 回导仍发送扣后值。
        val store = newStore(spiritStones = 1000L)
        val sender = RecordingSender()
        val sync = StateSyncService(store, sender)

        // 玩家操作：放置道路（扣 20 灵石 + 落道路）——产生反向捕获
        store.update {
            gameData = gameData.copy(
                spiritStones = gameData.spiritStones - GameConfig.Road.COST_PER_CELL,
                roads = gameData.roads + com.xianxia.sect.core.model.RoadData(gridX = 20, gridY = 20)
            )
        }
        // 前向镜像到来：镜像事务不参与反向捕获——不得吞掉玩家操作捕获
        store.updateMirror {
            gameData = gameData.copy(gameMonth = gameData.gameMonth + 1)
        }

        assertTrue("镜像后玩家捕获仍在（⑤ 可发送）", sync.applyDirtyToNative())
        assertEquals("回导信封应含扣后灵石（1000-20）", 980L, sender.envelopes.single().envelopeSpiritStones())
    }
}
