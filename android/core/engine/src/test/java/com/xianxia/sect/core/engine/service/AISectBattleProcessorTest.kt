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
        // 全链路验证：真实 PlayerDefenseProcessor（预警推进真实执行）
        val playerDefense = PlayerDefenseProcessor(
            stateStore = store,
            battleSystem = mock<BattleSystem>(),
            attackWarningService = attackWarningService,
            cultivationService = mock<CultivationService>(),
            sectWarehouseManager = mock<SectWarehouseManager>(),
            deathHandler = mock<DiscipleDeathHandler>()
        )
        val processor = AISectBattleProcessor(
            stateStore = store,
            thermalMonitor = thermalWith(false, false),
            battleSystem = mock<BattleSystem>(),
            playerDefenseProcessor = playerDefense,
            occupationResolver = mock<AISectOccupationResolver>()
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
            playerDefenseProcessor = mock<PlayerDefenseProcessor>(),
            occupationResolver = mock<AISectOccupationResolver>()
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

    private fun makeResolverWithFacade(buildingFacade: BuildingFacade): AISectOccupationResolver =
        AISectOccupationResolver(
            stateStore = mock<GameStateStore>(),
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
        val resolver = makeResolverWithFacade(buildingFacade)

        resolver.seizePlayerBuildingsAfterLoss(
            makeAttackResult(AIBattleWinner.ATTACKER, canOccupy = true),
            isPlayerOccupied = true
        )

        verify(buildingFacade).seizeBuildingsOfSect("def1")
    }

    @Test
    fun `AI战败玩家防守 - 不触发没收`() {
        val buildingFacade = mock<BuildingFacade>()
        val resolver = makeResolverWithFacade(buildingFacade)

        resolver.seizePlayerBuildingsAfterLoss(
            makeAttackResult(AIBattleWinner.DEFENDER, canOccupy = false),
            isPlayerOccupied = true
        )

        Mockito.verify(buildingFacade, Mockito.never()).seizeBuildingsOfSect("def1")
    }

    @Test
    fun `AI夺回AI占领宗门 - 不触发没收（玩家无建筑）`() {
        val buildingFacade = mock<BuildingFacade>()
        val resolver = makeResolverWithFacade(buildingFacade)

        resolver.seizePlayerBuildingsAfterLoss(
            makeAttackResult(AIBattleWinner.ATTACKER, canOccupy = true),
            isPlayerOccupied = false
        )

        Mockito.verify(buildingFacade, Mockito.never()).seizeBuildingsOfSect("def1")
    }

    private fun makeDisciple(id: String, realm: Int = 9, isAlive: Boolean = true): Disciple =
        Disciple(id = id, realm = realm, isAlive = isAlive)

    // ── L2 AI 降频：热控相位测试（settle 月 = 3/6/9/12，1 月跳过）──

    @Test
    fun `L2 NORMAL相位 - 首次对齐后 1月跳过 季度节奏 3-6-9-12`() {
        val processor = createProcessorWith(thermalWith(false, false))
        val state = makeState(GameData())

        // 首次调用（2026-1）：基准 = 2025-12，batch=0（旧逻辑 batch=1 在此月修炼）
        processor.processAISectOperations(2026, 1, state)
        assertEquals("首次调用 1 月跳过", 0, processor.currentAIBatchMonths())

        processor.processAISectOperations(2026, 2, state)
        assertEquals("2 月距基准 2 个月 < 3", 0, processor.currentAIBatchMonths())

        // 2026-3：距基准 3 个月 → 首个 settle 月，batch=3
        processor.processAISectOperations(2026, 3, state)
        assertEquals("2026-3 settle", 3, processor.currentAIBatchMonths())

        // 季度节奏：6/9/12 各距上次 settle 3 个月
        processor.processAISectOperations(2026, 6, state)
        assertEquals("2026-6 settle", 3, processor.currentAIBatchMonths())
        processor.processAISectOperations(2026, 9, state)
        assertEquals("2026-9 settle", 3, processor.currentAIBatchMonths())
        processor.processAISectOperations(2026, 12, state)
        assertEquals("2026-12 settle", 3, processor.currentAIBatchMonths())

        // 2027-1：距 2026-12 仅 1 个月 < 3 → 跳过（年变叠加月不再触发 AI 修炼）
        processor.processAISectOperations(2027, 1, state)
        assertEquals("2027-1 跳过", 0, processor.currentAIBatchMonths())
    }

    @Test
    fun `L2 REDUCE相位 - 6月节奏 1月仍跳过`() {
        val processor = createProcessorWith(thermalWith(emergency = false, reduce = true))
        val state = makeState(GameData())

        processor.processAISectOperations(2026, 1, state)
        assertEquals("首次 1 月跳过", 0, processor.currentAIBatchMonths())

        // 2026-6：距基准 6 个月 → settle（batch=6，6 月修炼一次性结算）
        processor.processAISectOperations(2026, 6, state)
        assertEquals("2026-6 settle", 6, processor.currentAIBatchMonths())

        // 2026-12：距 6 月 6 个月 → settle
        processor.processAISectOperations(2026, 12, state)
        assertEquals("2026-12 settle", 6, processor.currentAIBatchMonths())

        // 2027-1：距 2026-12 仅 1 个月 < 6 → 跳过
        processor.processAISectOperations(2027, 1, state)
        assertEquals("2027-1 跳过", 0, processor.currentAIBatchMonths())
    }

    @Test
    fun `L2 EMERGENCY相位 - 跨年批量一次性结算`() {
        val processor = createProcessorWith(thermalWith(emergency = true, reduce = false))
        val state = makeState(GameData())

        processor.processAISectOperations(2026, 1, state)
        assertEquals("首次 1 月跳过", 0, processor.currentAIBatchMonths())

        // 2027-1：距基准 13 个月 ≥ 12 → 跨年批量一次性 settle（batch=13，13 个月修炼一次结算）
        processor.processAISectOperations(2027, 1, state)
        assertEquals("2027-1 跨年批量", 13, processor.currentAIBatchMonths())
    }

    @Test
    fun `L2 相位 - 2月读档首次对齐 基准取3的倍数 settle月仍为3-6-9-12 1月跳过`() {
        // 对抗性审查 F4：首次调用在 2/5/8/11 月时，旧基准 (currentMonth-1) mod 3 ≠ 0，
        // settle 月会包含 1 月（年变叠加月触发 AI 修炼，降频目标失效）。
        // 修复后基准 = (当前月-1) 向下取 3 的倍数，settle 月恒为 3/6/9/12。
        val processor = createProcessorWith(thermalWith(false, false))
        val state = makeState(GameData())

        // 模拟 2 月读档后首次调用：基准对齐到 2025-12（mod 3 = 0）
        processor.processAISectOperations(2026, 2, state)
        assertEquals("2 月首次调用距基准 2 个月 < 3", 0, processor.currentAIBatchMonths())

        // 2026-3：首个 settle 月（3 mod 3 = 0）
        processor.processAISectOperations(2026, 3, state)
        assertEquals("2026-3 settle", 3, processor.currentAIBatchMonths())

        // 2026-12（mod 3 = 0，仍是 settle 月）：距 2026-3 九个月 → batch=9（一次结算）
        processor.processAISectOperations(2026, 12, state)
        assertEquals("2026-12 settle（距上次 9 个月）", 9, processor.currentAIBatchMonths())
        // 2027-1 距 2026-12 仅 1 个月 → 跳过（1 月永不 settle）
        processor.processAISectOperations(2027, 1, state)
        assertEquals("2027-1 跳过（1 月永不 settle）", 0, processor.currentAIBatchMonths())
    }

    @Test
    fun `L2 相位 - 时钟回退同月重复调用 跳过而非叠加修炼`() {
        // 对抗性审查 F1：monthsSince <= 0（读档到更早月份/同月重复调用）时
        // 旧逻辑 batchMonths=1 会重复执行一个月修炼；修复后跳过（0）。
        val processor = createProcessorWith(thermalWith(false, false))
        val state = makeState(GameData())

        processor.processAISectOperations(2026, 1, state)
        assertEquals("首次 1 月跳过", 0, processor.currentAIBatchMonths())
        processor.processAISectOperations(2026, 3, state)
        assertEquals("2026-3 settle 后 lastSettle=24315", 3, processor.currentAIBatchMonths())

        // 模拟读档回 2026-1（时钟回退）：monthsSince = -2 <= 0 → 跳过（不得叠加修炼）
        processor.processAISectOperations(2026, 1, state)
        assertEquals("时钟回退跳过", 0, processor.currentAIBatchMonths())

        // 同月重复调用：monthsSince = 1 > 0 但未达批次阈值 → 同样跳过
        processor.processAISectOperations(2026, 1, state)
        assertEquals("同月重复调用跳过", 0, processor.currentAIBatchMonths())
    }
}
