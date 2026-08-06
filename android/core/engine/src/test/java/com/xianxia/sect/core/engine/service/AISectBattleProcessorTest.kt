package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.SectLevel
import com.xianxia.sect.core.engine.SectWarehouseManager
import com.xianxia.sect.core.engine.domain.battle.aisRngManager
import com.xianxia.sect.core.engine.domain.battle.AIBattleWinner
import com.xianxia.sect.core.engine.domain.battle.AISectAttackManager
import com.xianxia.sect.core.engine.domain.battle.AttackWarningService
import com.xianxia.sect.core.engine.domain.battle.BattleSystem
import com.xianxia.sect.core.engine.domain.building.BuildingFacade
import com.xianxia.sect.core.exploration.DiscipleDeathHandler
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.SectDetail
import com.xianxia.sect.core.model.SectWarehouse
import com.xianxia.sect.core.model.WarehouseItem
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.perf.ThermalMonitor
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.util.GameRngManager
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

/**
 * P-15 迁移守卫：AISectBattleProcessor（CaveExplorationProcessor 拆出）核心行为防漂移。
 *
 * 覆盖：AI 宗门升级链 / 玩家宗门不升级 / 非玩家宗门仓库清理 / 热控分批路径 / 入口全链路。
 * 说明：AI-vs-AI 决策应用与玩家占领防御构建的深度断言依赖完整游戏状态构造，
 * 由 CaveExplorationProcessorTest.buildDefenseBattleEnemies + 全量集成回归兜底。
 */
class AISectBattleProcessorTest {

    private val attackWarningService = mock<AttackWarningService>()

    @Before
    fun setUp() {
        // decidePlayerAttack/decideAttacks 真实执行依赖分区 RNG 注入
        aisRngManager = GameRngManager()
    }

    @After
    fun tearDown() {
        // 恢复默认值防跨类静态污染（P-14 H3 同类问题）
        aisRngManager = null
    }

    @Test
    fun `processAISectOperations - AI宗门升级链生效 玩家宗门不自动升级`() {
        val processor = createProcessorWith(thermalWith(false, false))

        val aiSect = WorldSect(id = "ai1", name = "AI宗", level = SectLevel.SMALL, isPlayerSect = false)
        val playerSect = WorldSect(id = "player", name = "玩家宗", level = SectLevel.SMALL, isPlayerSect = true)
        val data = GameData(
            worldMapSects = listOf(playerSect, aiSect),
            aiSectDisciples = mapOf("ai1" to listOf(makeDisciple("d1", realm = 5))),
            sectDetails = mapOf("ai1" to SectDetail(sectId = "ai1"))
        )
        val state = makeState(data)

        processor.processAISectOperations(2026, 1, state)

        val synced = state.gameData.worldMapSects
        assertEquals("AI 宗门（有 realm≤5 弟子）应升级到 MEDIUM", SectLevel.MEDIUM, synced.find { it.id == "ai1" }?.level)
        assertEquals("玩家宗门不得由月度 tick 自动升级", SectLevel.SMALL, synced.find { it.id == "player" }?.level)
    }

    @Test
    fun `processAISectOperations - 非玩家宗门仓库清空 玩家宗门仓库保留`() {
        val processor = createProcessorWith(thermalWith(false, false))

        val aiSect = WorldSect(id = "ai1", name = "AI宗", level = SectLevel.SMALL, isPlayerSect = false)
        val playerSect = WorldSect(id = "player", name = "玩家宗", level = SectLevel.SMALL, isPlayerSect = true)
        val data = GameData(
            worldMapSects = listOf(playerSect, aiSect),
            aiSectDisciples = mapOf("ai1" to listOf(makeDisciple("d1", realm = 9))),
            sectDetails = mapOf(
                "ai1" to SectDetail(
                    sectId = "ai1",
                    warehouse = SectWarehouse(items = listOf(WarehouseItem(itemId = "x")))
                ),
                "player" to SectDetail(
                    sectId = "player",
                    warehouse = SectWarehouse(items = listOf(WarehouseItem(itemId = "y")))
                )
            )
        )
        val state = makeState(data)

        processor.processAISectOperations(2026, 1, state)

        assertTrue("非玩家宗门仓库应清空", state.gameData.sectDetails.getValue("ai1").warehouse.items.isEmpty())
        assertEquals("玩家宗门仓库不得被清理", 1, state.gameData.sectDetails.getValue("player").warehouse.items.size)
    }

    @Test
    fun `processAISectOperations - 热控紧急模式走紧急分批路径`() {
        val thermal = thermalWith(emergency = true, reduce = false)
        val processor = createProcessorWith(thermal)

        val aiSect = WorldSect(id = "ai1", name = "AI宗", level = SectLevel.TOP, isPlayerSect = false)
        val data = GameData(
            worldMapSects = listOf(WorldSect(id = "player", isPlayerSect = true), aiSect),
            aiSectDisciples = mapOf("ai1" to listOf(makeDisciple("d1", realm = 9)))
        )
        val state = makeState(data)

        // 首次调用仅初始化分批基准月（不查热控）；跨 12 个月后再调用触发紧急分批判定
        processor.processAISectOperations(2026, 1, state)
        processor.processAISectOperations(2027, 1, state)

        verify(thermal).shouldEmergencySave()
        // 顶级宗门不降级、流程无异常
        assertEquals(SectLevel.TOP, state.gameData.worldMapSects.find { it.id == "ai1" }?.level)
    }

    @Test
    fun `processAISectOperations 入口 - 无预警无AI宗门时全流程安全执行`() {
        val store = mock<GameStateStore>()
        val data = GameData(worldMapSects = listOf(WorldSect(id = "player", isPlayerSect = true)))
        val tables = DiscipleTables().apply { writeAllowed = true }
        whenever(store.gameData).thenReturn(MutableStateFlow(data))
        whenever(store.discipleTables).thenReturn(tables)
        whenever(store.update(any())).thenAnswer { inv ->
            inv.getArgument<MutableGameState.() -> Unit>(0).invoke(makeState(data))
        }
        val processor = AISectBattleProcessor(
            stateStore = store,
            thermalMonitor = thermalWith(false, false),
            battleSystem = mock<BattleSystem>(),
            attackWarningService = attackWarningService,
            cultivationService = mock<CultivationService>(),
            sectWarehouseManager = mock<SectWarehouseManager>(),
            deathHandler = mock<DiscipleDeathHandler>(),
            buildingFacade = mock<BuildingFacade>()
        )

        processor.processAISectOperations(2026, 1)

        // 预警生命周期已推进；无预警/无 AI 宗门时不产生任何攻击行为
        verify(attackWarningService).advanceWarningsIfNeededSync(any())
    }

    private fun thermalWith(emergency: Boolean, reduce: Boolean): ThermalMonitor {
        val thermal = mock<ThermalMonitor>()
        whenever(thermal.shouldEmergencySave()).thenReturn(emergency)
        whenever(thermal.shouldReduceWorkload()).thenReturn(reduce)
        return thermal
    }

    private fun createProcessorWith(thermal: ThermalMonitor): AISectBattleProcessor {
        val store = mock<GameStateStore>()
        whenever(store.update(any())).thenAnswer { inv ->
            inv.getArgument<MutableGameState.() -> Unit>(0).invoke(makeState())
        }
        return AISectBattleProcessor(
            stateStore = store,
            thermalMonitor = thermal,
            battleSystem = mock<BattleSystem>(),
            attackWarningService = attackWarningService,
            cultivationService = mock<CultivationService>(),
            sectWarehouseManager = mock<SectWarehouseManager>(),
            deathHandler = mock<DiscipleDeathHandler>(),
            buildingFacade = mock<BuildingFacade>()
        )
    }

    private fun makeState(data: GameData = GameData()): MutableGameState {
        val tables = DiscipleTables().apply { writeAllowed = true }
        return MutableGameState(
            gameData = data,
            discipleTables = tables,
            equipmentStacks = EntityStore(),
            equipmentInstances = EntityStore(),
            manualStacks = EntityStore(),
            manualInstances = EntityStore(),
            pills = EntityStore(),
            materials = EntityStore(),
            herbs = EntityStore(),
            seeds = EntityStore(),
            storageBags = EntityStore(),
            teams = emptyList(),
            battleLogs = emptyList(),
            isPaused = false, isLoading = false, isSaving = false
        )
    }

    // ── 2026-08-06：玩家占领宗门被 AI 夺回 → 没收该宗门建筑（无返还）──

    private fun makeProcessorWithFacade(buildingFacade: BuildingFacade): AISectBattleProcessor =
        AISectBattleProcessor(
            stateStore = mock<GameStateStore>(),
            thermalMonitor = thermalWith(false, false),
            battleSystem = mock<BattleSystem>(),
            attackWarningService = attackWarningService,
            cultivationService = mock<CultivationService>(),
            sectWarehouseManager = mock<SectWarehouseManager>(),
            deathHandler = mock<DiscipleDeathHandler>(),
            buildingFacade = buildingFacade
        )

    private fun makeAttackResult(winner: AIBattleWinner, canOccupy: Boolean) =
        AISectAttackManager.AIAttackResult(
            attackerSectId = "atk1", defenderSectId = "def1",
            attackerSectName = "攻击宗", defenderSectName = "被占宗",
            winner = winner, canOccupy = canOccupy,
            deadAttackerIds = emptyList(), deadDefenderIds = emptyList(),
            survivingAttackers = listOf(makeDisciple("a1"))
        )

    @Test
    fun `AI夺回玩家占领宗门 - 没收该宗门建筑`() {
        val buildingFacade = mock<BuildingFacade>()
        val processor = makeProcessorWithFacade(buildingFacade)

        processor.seizePlayerBuildingsAfterLoss(
            makeAttackResult(AIBattleWinner.ATTACKER, canOccupy = true),
            isPlayerOccupied = true
        )

        verify(buildingFacade).seizeBuildingsOfSect("def1")
    }

    @Test
    fun `AI战败玩家防守 - 不触发没收`() {
        val buildingFacade = mock<BuildingFacade>()
        val processor = makeProcessorWithFacade(buildingFacade)

        processor.seizePlayerBuildingsAfterLoss(
            makeAttackResult(AIBattleWinner.DEFENDER, canOccupy = false),
            isPlayerOccupied = true
        )

        Mockito.verify(buildingFacade, Mockito.never()).seizeBuildingsOfSect("def1")
    }

    @Test
    fun `AI夺回AI占领宗门 - 不触发没收（玩家无建筑）`() {
        val buildingFacade = mock<BuildingFacade>()
        val processor = makeProcessorWithFacade(buildingFacade)

        processor.seizePlayerBuildingsAfterLoss(
            makeAttackResult(AIBattleWinner.ATTACKER, canOccupy = true),
            isPlayerOccupied = false
        )

        Mockito.verify(buildingFacade, Mockito.never()).seizeBuildingsOfSect("def1")
    }

    private fun makeDisciple(id: String, realm: Int = 9, isAlive: Boolean = true): Disciple =
        Disciple(id = id, realm = realm, isAlive = isAlive)
}
