package com.xianxia.sect.core.engine

import com.xianxia.sect.core.engine.domain.battle.BattleFacade
import com.xianxia.sect.core.domain.building.registerTestFeatures
import com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * B3（2026-08-08）：validateAndFixSpiritMineData 矿场槽位 sectId 对齐测试。
 *
 * 失配场景：槽位 sectId ≠ 矿场建筑 sectId → SpiritMineDialog 按建筑 sectId 过滤显示
 * 虚构空槽，玩家任命后 UI 不刷新（矿场版"任命不生效"）。对话框打开必触发本函数
 * （零新增调用点的收敛时机），本测试验证对齐 + 幂等 + 空槽补齐。
 */
class SpiritMineSectAlignmentTest {

    @get:Rule val writeGuardRule = WriteGuardRule()
    private lateinit var store: FakeAtomicStateStore
    private lateinit var engine: GameEngine

    @Before
    fun setUp() {
        BuildingFeatureRegistry.registerTestFeatures()
        store = FakeAtomicStateStore()
        val mockCore = mock<com.xianxia.sect.core.engine.GameEngineCore>()
        // updateGameDataSync 经 launchInScope 执行 stateStore.update——必须同步执行块，
        // 否则 validateAndFix 的写回静默丢失（thenReturn(mock()) 不执行块）
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

    @Test
    fun `失配槽位 sectId 对齐至矿场建筑`() {
        store.update {
            gameData = gameData.copy(
                worldMapSects = listOf(WorldSect(id = "sect_p", isPlayerSect = true)),
                placedBuildings = listOf(
                    GridBuildingData(
                        displayName = "灵矿场", sectId = "sect_p",
                        instanceId = "mine_1", gridX = 0, gridY = 0, width = 4, height = 4
                    )
                ),
                spiritMineSlots = listOf(
                    SpiritMineSlot(index = 0, sectId = "sect_old", buildingInstanceId = "mine_1"),
                    SpiritMineSlot(index = 1, sectId = "", buildingInstanceId = "mine_1")
                )
            )
        }

        engine.validateAndFixSpiritMineData()

        val gd = store.latestGameData
        assertEquals("3 个槽位应补齐", 3, gd.spiritMineSlots.size)
        assertTrue("全部槽位 sectId 应对齐建筑",
            gd.spiritMineSlots.all { it.sectId == "sect_p" })
    }

    @Test
    fun `对齐幂等 - 连续两次结果一致`() {
        store.update {
            gameData = gameData.copy(
                worldMapSects = listOf(WorldSect(id = "sect_p", isPlayerSect = true)),
                placedBuildings = listOf(
                    GridBuildingData(
                        displayName = "灵矿场", sectId = "sect_p",
                        instanceId = "mine_1", gridX = 0, gridY = 0, width = 4, height = 4
                    )
                ),
                spiritMineSlots = listOf(
                    SpiritMineSlot(index = 0, sectId = "sect_old", buildingInstanceId = "mine_1")
                )
            )
        }

        engine.validateAndFixSpiritMineData()
        val first = store.latestGameData.spiritMineSlots
        engine.validateAndFixSpiritMineData()
        val second = store.latestGameData.spiritMineSlots

        assertEquals(first, second)
        assertTrue(second.all { it.sectId == "sect_p" })
    }

    @Test
    fun `多矿场各自槽位对齐各自建筑 sectId`() {
        store.update {
            gameData = gameData.copy(
                worldMapSects = listOf(WorldSect(id = "sect_p", isPlayerSect = true)),
                placedBuildings = listOf(
                    GridBuildingData(
                        displayName = "灵矿场", sectId = "sect_p",
                        instanceId = "mine_1", gridX = 0, gridY = 0, width = 4, height = 4
                    ),
                    GridBuildingData(
                        displayName = "灵矿场", sectId = "sect_2",
                        instanceId = "mine_2", gridX = 10, gridY = 10, width = 4, height = 4
                    )
                ),
                spiritMineSlots = listOf(
                    SpiritMineSlot(index = 0, sectId = "sect_old", buildingInstanceId = "mine_1")
                )
            )
        }

        engine.validateAndFixSpiritMineData()

        val slots = store.latestGameData.spiritMineSlots
        assertEquals(6, slots.size)
        assertEquals("第一座矿的槽位对齐 sect_p", "sect_p", slots[0].sectId)
        assertEquals("第二座矿的槽位对齐 sect_2", "sect_2", slots[3].sectId)
    }
}
