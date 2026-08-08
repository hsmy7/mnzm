package com.xianxia.sect.core.engine

import com.xianxia.sect.core.engine.domain.battle.BattleFacade
import com.xianxia.sect.core.engine.domain.cultivation.CultivationFacade
import com.xianxia.sect.core.engine.domain.economy.EconomyFacade
import com.xianxia.sect.core.engine.domain.exploration.ExplorationFacade
import com.xianxia.sect.core.engine.domain.production.ProductionFacade
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.SpiritMineSlot
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.state.WriteGuardRule
import com.xianxia.sect.core.util.GameRngManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * B2（2026-08-08）：enterSect 会话内 sectId 收敛测试。
 *
 * R2 场景：boot 自愈只在读档时跑一次；世界重生后（worldSects 曾为空、归一化整体跳过）
 * 进入宗门时旧 sectId 建筑永不匹配 → 不可见不可点。enterSect 复用读档自愈纯函数
 * （幂等）在每次进入宗门时收敛。
 */
class GameEngineSectConvergenceTest {

    @get:Rule val writeGuardRule = WriteGuardRule()
    private lateinit var store: FakeAtomicStateStore
    private lateinit var engine: GameEngine

    @Before
    fun setUp() {
        store = FakeAtomicStateStore()
        val mockCore = mock<com.xianxia.sect.core.engine.GameEngineCore>()
        // updateGameDataSync 经 launchInScope 执行 stateStore.update——必须同步执行块，
        // 否则收敛写回静默丢失（thenReturn(mock()) 不执行块）
        whenever(mockCore.launchInScope(any())).thenAnswer { invocation ->
            val block = invocation.getArgument<suspend CoroutineScope.() -> Unit>(0)
            runBlocking { block(CoroutineScope(Dispatchers.Unconfined)) }
            mock<kotlinx.coroutines.Job>()
        }
        // GameEngine 构造器 300/301 行非惰性属性立即求值：productionSlots 必须 stub，
        // cultivationService 返回 mock（其 getHighFrequencyData 对 Flow 返回空流）
        val mockProductionFacade = mock<ProductionFacade>()
        whenever(mockProductionFacade.productionSlots).thenReturn(MutableStateFlow(emptyList()))
        val mockCultivationFacade = mock<CultivationFacade>()
        whenever(mockCultivationFacade.cultivationService).thenReturn(mock())
        whenever(mockCultivationFacade.productionFacade).thenReturn(mockProductionFacade)
        engine = GameEngine(
            gameEngineCore = mockCore,
            engineContextDispatcher = FakeEngineContextDispatcher(),
            stateStore = store,
            gameRngManager = mock<GameRngManager>(),
            explorationFacade = mock<ExplorationFacade>(),
            cultivationFacade = mockCultivationFacade,
            economyFacade = mock<EconomyFacade>(),
            battleFacade = mock<BattleFacade>()
        )
    }

    private fun b(displayName: String, sectId: String, instanceId: String) =
        GridBuildingData(displayName = displayName, sectId = sectId, instanceId = instanceId)

    /** 种子：玩家宗门 sect_p 存在；孤儿建筑 sect_dead + 本宗建筑 "" + 矿场孤儿槽 */
    private fun seedWorld() {
        store.update {
            gameData = gameData.copy(
                worldMapSects = listOf(WorldSect(id = "sect_p", isPlayerSect = true)),
                placedBuildings = listOf(
                    b("炼丹炉", "sect_dead", "orphan_1"),
                    b("灵矿场", "", "home_mine")
                ),
                spiritMineSlots = listOf(
                    SpiritMineSlot(index = 0, sectId = "sect_dead", buildingInstanceId = "orphan_1")
                )
            )
        }
    }

    @Test
    fun `enterSect 无效宗门时 activeSectId 净化且孤儿建筑归入本宗`() = runTest {
        seedWorld()

        engine.enterSect("sect_dead")

        val gd = store.latestGameData
        assertEquals("无效 sectId 应净化归空", "", gd.activeSectId)
        assertEquals("孤儿建筑应归入本宗", "",
            gd.placedBuildings.find { it.instanceId == "orphan_1" }?.sectId)
        assertEquals("本宗建筑不动", "",
            gd.placedBuildings.find { it.instanceId == "home_mine" }?.sectId)
        assertEquals("孤儿矿场槽位应同步归空", "",
            gd.spiritMineSlots.single().sectId)
    }

    @Test
    fun `enterSect 玩家宗门时 activeSectId 保留且孤儿建筑归入本宗`() = runTest {
        seedWorld()

        engine.enterSect("sect_p")

        val gd = store.latestGameData
        assertEquals("玩家宗门应保留", "sect_p", gd.activeSectId)
        assertEquals("孤儿建筑应归入本宗", "",
            gd.placedBuildings.find { it.instanceId == "orphan_1" }?.sectId)
    }

    @Test
    fun `enterSect 幂等 - 连续两次收敛结果一致`() = runTest {
        seedWorld()

        engine.enterSect("sect_dead")
        val first = store.latestGameData
        engine.enterSect("")
        val second = store.latestGameData

        assertEquals(first.placedBuildings, second.placedBuildings)
        assertEquals(first.spiritMineSlots, second.spiritMineSlots)
        assertEquals("", second.activeSectId)
    }
}
