package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.domain.cultivation.CultivationFacade
import com.xianxia.sect.core.engine.domain.economy.EconomyFacade
import com.xianxia.sect.core.engine.domain.inventory.InventoryFacade
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.engine.domain.production.ProductionFacade
import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.RewardCardItem
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.StorageBag
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.state.BattleResultUIData
import com.xianxia.sect.core.state.BootPhase
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.GameNotification
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.PendingBeastAttack
import com.xianxia.sect.core.state.PendingMarriageProposal
import com.xianxia.sect.core.state.RunState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever



/**
 * GameEngineCoordination 数据完整性守卫的单元测试。
 */
class GameEngineCoordinationTest {

    @Test
    fun `ensureGameDataIntegrity - worldMapSects 空时重生`() = runBlocking {
        val env = EngineTestEnv()
        env.store.gameDataValue = GameData().copy(
            sectName = "青云宗",
            worldMapSects = emptyList()
        )

        env.engine.ensureGameDataIntegrity()

        val result = env.store.gameDataValue
        assertTrue("worldMapSects 应被重生为非空",
            result.worldMapSects.isNotEmpty())
        assertTrue("应包含玩家宗门",
            result.worldMapSects.any { it.isPlayerSect })
    }

    @Test
    fun `ensureGameDataIntegrity - worldMapSects 非空时跳过`() = runBlocking {
        val env = EngineTestEnv()
        env.store.gameDataValue = GameData().copy(
            sectName = "青云宗",
            worldMapSects = listOf(WorldSect(
                id = "player_sect", name = "青云宗",
                x = 849f, y = 463f, isPlayerSect = true,
                level = 3, discovered = true, relation = 100
            ))
        )

        env.engine.ensureGameDataIntegrity()

        assertEquals("worldMapSects 应保留原有 1 个",
            1, env.store.gameDataValue.worldMapSects.size)
    }

    @Test
    fun `ensureHeavyDataLoaded - worldMapSects 非空时标记完成`() = runBlocking {
        // C12 修复（2026-08-05）：原实现为空操作守卫（从不检查数据），
        // 现短路前置 worldMapSects 非空校验
        val env = EngineTestEnv()
        env.store.gameDataValue = GameData().copy(
            sectName = "青云宗",
            worldMapSects = listOf(WorldSect(
                id = "player_sect", name = "青云宗",
                x = 849f, y = 463f, isPlayerSect = true,
                level = 3, discovered = true, relation = 100
            ))
        )

        env.engine.ensureHeavyDataLoaded()

        assertTrue("worldMapSects 非空应标记 heavyDataLoaded",
            env.engine.heavyDataLoaded)
    }

    @Test
    fun `ensureHeavyDataLoaded - worldMapSects 为空时不标记完成`() = runBlocking {
        // C12：数据缺失时不标记完成——后续调用重试，由 ensureGameDataIntegrity 重生
        val env = EngineTestEnv()
        env.store.gameDataValue = GameData().copy(
            sectName = "青云宗",
            worldMapSects = emptyList()
        )

        env.engine.ensureHeavyDataLoaded()

        org.junit.Assert.assertFalse("worldMapSects 为空应保持未完成",
            env.engine.heavyDataLoaded)
    }

    @Test
    fun `ensureGameDataIntegrity - sectName 空时不崩溃`() = runBlocking {
        val env = EngineTestEnv()
        env.store.gameDataValue = GameData().copy(
            sectName = "",
            worldMapSects = emptyList()
        )

        env.engine.ensureGameDataIntegrity()
        assertTrue("sectName 为空时 worldMapSects 仍为空",
            env.store.gameDataValue.worldMapSects.isEmpty())
    }

    @Test
    fun `createNewGame - mapSeed 非零`() = runBlocking {
        val env = EngineTestEnv()
        // 生产代码会访问 productionCoordinator.repository，stub 避免 mock 返回 null
        whenever(env.engine.productionCoordinator.repository).thenReturn(mock())

        env.engine.createNewGame("青云宗", 1)

        assertTrue("新游戏 mapSeed 不应为 0", env.store.gameDataValue.mapSeed != 0)
    }

    @Test
    fun `restartGameSuspend - mapSeed 非零（旧实现恒为 0 的回归守卫）`() = runBlocking {
        val env = EngineTestEnv()

        env.engine.restartGameSuspend("", 1)

        assertTrue("重启后 mapSeed 不应为 0，否则全分区 PRNG 种子归零且地图相同",
            env.store.gameDataValue.mapSeed != 0)
    }

    @Test
    fun `restartGameSuspend - 两次重启种子不同`() = runBlocking {
        val env = EngineTestEnv()

        env.engine.restartGameSuspend("", 1)
        val first = env.store.gameDataValue.mapSeed
        env.engine.restartGameSuspend("", 1)
        val second = env.store.gameDataValue.mapSeed

        assertTrue("两次重启应产生不同地图种子（相同为缺陷）", first != second)
    }

    // ── 2026-08-06 修复：初始灵矿场 4×4 与配置一致（此前 2×2 致新档首次会话外圈点不中）──

    @Test
    fun `createNewGame - 初始灵矿场为 4x4 与配置一致`() = runBlocking {
        val env = EngineTestEnv()
        whenever(env.engine.productionCoordinator.repository).thenReturn(mock())

        env.engine.createNewGame("青云宗", 1)

        val mine = env.store.gameDataValue.placedBuildings.single()
        assertEquals("初始灵矿场宽度应为 4（spirit_mine 配置占地）", 4, mine.width)
        assertEquals("初始灵矿场高度应为 4（spirit_mine 配置占地）", 4, mine.height)
        assertEquals("本宗建筑 sectId 应为空串", "", mine.sectId)
        assertEquals("灵矿场应居中放置", GameConfig.SectMap.WORLD_WIDTH_CELLS / 2 - 1, mine.gridX)
    }

    @Test
    fun `restartGameSuspend - 初始灵矿场为 4x4 与配置一致`() = runBlocking {
        val env = EngineTestEnv()

        env.engine.restartGameSuspend("青云宗", 1)

        val mine = env.store.gameDataValue.placedBuildings.single()
        assertEquals("重启后初始灵矿场宽度应为 4", 4, mine.width)
        assertEquals("重启后初始灵矿场高度应为 4", 4, mine.height)
    }

    @Test
    fun `enterSect - 仅更新 activeSectId 不触碰 placedBuildings`() = runBlocking {
        // 契约守卫：GameViewModel 命令总线重推依赖 enterSect 只改 activeSectId
        //（若 enterSect 顺带修改 placedBuildings，总线键 (activeSectId, placedBuildings)
        // 会同时失效，重推语义被破坏）
        // B2（2026-08-08）：enterSect 增加会话内收敛——activeSectId 必须是 worldMapSects
        // 中玩家持有（isPlayerSect/isPlayerOccupied）的宗门，否则被净化归 ""。
        // 种子先声明 ai-1 为玩家持有宗门；无孤儿建筑时 placedBuildings 仍不被触碰
        //（收敛只动失配数据，幂等）。
        val env = EngineTestEnv()
        val mine = GridBuildingData(
            buildingId = "灵矿场", displayName = "灵矿场",
            gridX = 10, gridY = 10, width = 4, height = 4,
            instanceId = "m1", sectId = ""
        )
        env.store.gameDataValue = env.store.gameDataValue.copy(
            worldMapSects = listOf(WorldSect(id = "ai-1", isPlayerSect = true)),
            placedBuildings = listOf(mine)
        )

        env.engine.enterSect("ai-1")

        val data = env.store.gameDataValue
        assertEquals("activeSectId 应切换为 ai-1", "ai-1", data.activeSectId)
        assertEquals("placedBuildings 不应被 enterSect 修改", 1, data.placedBuildings.size)
        assertEquals("建筑内容不应变化", mine, data.placedBuildings.single())
    }
}

// ── 测试用 GameEngine + GameStateStore 的最小化环境 ──

private class EngineTestEnv {
    val store = SimpleStore()

    // D1：构造时 highFrequencyData/productionSlots 经 Facade 访问器求值——stub 链防 NPE
    private val mockCultivationFacade = mock<CultivationFacade>().also {
        org.mockito.kotlin.whenever(it.cultivationService).thenReturn(mock())
        org.mockito.kotlin.whenever(it.discipleService).thenReturn(mock())
        val mockProductionFacade = mock<ProductionFacade>()
        org.mockito.kotlin.whenever(mockProductionFacade.productionSlots)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow(emptyList()))
        org.mockito.kotlin.whenever(it.productionFacade).thenReturn(mockProductionFacade)
        val mockPC = mock<ProductionCoordinator>()
        org.mockito.kotlin.whenever(mockPC.repository).thenReturn(mock())
        org.mockito.kotlin.whenever(it.productionCoordinator).thenReturn(mockPC)
    }
    private val mockEconomyFacade = mock<EconomyFacade>().also {
        val mockInventoryFacade = mock<InventoryFacade>()
        org.mockito.kotlin.whenever(mockInventoryFacade.inventorySystem).thenReturn(mock())
        org.mockito.kotlin.whenever(it.inventoryFacade).thenReturn(mockInventoryFacade)
        org.mockito.kotlin.whenever(it.mailService).thenReturn(mock())
    }

    val engine = GameEngine(
        gameEngineCore = mock(),
        engineContextDispatcher = FakeEngineContextDispatcher(),
        stateStore = store,
        gameRngManager = mock(),
        explorationFacade = mock(),
        cultivationFacade = mockCultivationFacade,
        economyFacade = mockEconomyFacade,
        battleFacade = mock()
    )
}

private class SimpleStore : GameStateStore {

    /** 测试用：直接读写 GameData */
    var gameDataValue: GameData = GameData()

    private val _gameDataFlow = MutableStateFlow(GameData())
    override val gameData: StateFlow<GameData> get() = _gameDataFlow
    override val gameDataSnapshot: GameData get() = gameDataValue

    private val _tables = DiscipleTables()
    override val discipleTables: DiscipleTables get() = _tables

    // EntityStore 实例（MutableGameState 需要）
    private val eqStacks = EntityStore<EquipmentStack>()
    private val eqInstances = EntityStore<EquipmentInstance>()
    private val mnStacks = EntityStore<ManualStack>()
    private val mnInstances = EntityStore<ManualInstance>()
    private val pils = EntityStore<Pill>()
    private val mats = EntityStore<Material>()
    private val hrbs = EntityStore<Herb>()
    private val sds = EntityStore<Seed>()
    private val stBags = EntityStore<StorageBag>()

    override fun update(block: MutableGameState.() -> Unit) {
        val mutable = MutableGameState(
            gameData = gameDataValue,
            discipleTables = _tables,
            equipmentStacks = eqStacks,
            equipmentInstances = eqInstances,
            manualStacks = mnStacks,
            manualInstances = mnInstances,
            pills = pils,
            materials = mats,
            herbs = hrbs,
            seeds = sds,
            storageBags = stBags,
                        battleLogs = emptyList(),
            isPaused = false,
            isLoading = false,
            isSaving = false
        )
        block(mutable)
        gameDataValue = mutable.gameData
        _gameDataFlow.value = mutable.gameData
    }
    override val lifecycleState = MutableStateFlow(GameStateStore.LifecycleState())
    override val bootPhase = MutableStateFlow(BootPhase.UNINITIALIZED)
    override val runState = MutableStateFlow(RunState.IDLE)
    override val disciples = MutableStateFlow<List<Disciple>>(emptyList())
    override val discipleAggregates = MutableStateFlow<List<DiscipleAggregate>>(emptyList())
    override val equipmentStacks = MutableStateFlow<List<EquipmentStack>>(emptyList())
    override val equipmentInstances = MutableStateFlow<List<EquipmentInstance>>(emptyList())
    override val manualStacks = MutableStateFlow<List<ManualStack>>(emptyList())
    override val manualInstances = MutableStateFlow<List<ManualInstance>>(emptyList())
    override val pills = MutableStateFlow<List<Pill>>(emptyList())
    override val materials = MutableStateFlow<List<Material>>(emptyList())
    override val herbs = MutableStateFlow<List<Herb>>(emptyList())
    override val seeds = MutableStateFlow<List<Seed>>(emptyList())
    override val storageBags = MutableStateFlow<List<StorageBag>>(emptyList())
    override val battleLogs = MutableStateFlow<List<BattleLog>>(emptyList())
    override val isPaused = MutableStateFlow(false)
    override val isLoading = MutableStateFlow(false)
    override val isSaving = MutableStateFlow(false)
    override val pendingNotification = MutableStateFlow<GameNotification?>(null)
    override val pendingBattleResult = MutableStateFlow<BattleResultUIData?>(null)
    override val rewardCardQueue = MutableStateFlow<List<RewardCardItem>>(emptyList())
    override val pendingBeastAttacks = MutableStateFlow<List<PendingBeastAttack>>(emptyList())
    override val pendingMarriageProposals = MutableStateFlow<List<PendingMarriageProposal>>(emptyList())
    override val pendingBattleRewardCards = MutableStateFlow<List<RewardCardItem>>(emptyList())
    override val sectCombatPower = MutableStateFlow(0L)
    override val aiSectCombatPowers = MutableStateFlow<Map<String, Long>>(emptyMap())
    override val highFreqState = MutableStateFlow(GameStateStore.HighFreqState())
    override val entityState = MutableStateFlow(GameStateStore.EntityState())
    override val configState = MutableStateFlow(GameStateStore.ConfigState())
    override val disciplesSnapshot: List<Disciple> get() = emptyList()
    override val equipmentStacksSnapshot: List<EquipmentStack> get() = emptyList()
    override val equipmentInstancesSnapshot: List<EquipmentInstance> get() = emptyList()
    override val manualStacksSnapshot: List<ManualStack> get() = emptyList()
    override val manualInstancesSnapshot: List<ManualInstance> get() = emptyList()
    override val pillsSnapshot: List<Pill> get() = emptyList()
    override val materialsSnapshot: List<Material> get() = emptyList()
    override val herbsSnapshot: List<Herb> get() = emptyList()
    override val seedsSnapshot: List<Seed> get() = emptyList()
    override val storageBagsSnapshot: List<StorageBag> get() = emptyList()
    override val battleLogsSnapshot: List<BattleLog> get() = emptyList()
    override val discipleAggregatesSnapshot: List<DiscipleAggregate> get() = emptyList()
    override val notifications = MutableStateFlow<List<GameNotification>>(emptyList())
    override val warehouseFullEvent = MutableSharedFlow<String>()
    override var activeTab: String = ""
    override var activeDialog: String? = null
    override var activeSubDialogs: Set<String> = emptySet()
    override fun getCurrentSeeds(): List<Seed> = emptyList()
    override fun getCurrentHerbs(): List<Herb> = emptyList()
    override fun getCurrentMaterials(): List<Material> = emptyList()
    override fun enqueueNotification(notification: GameNotification) {}
    override fun consumeNotification(): GameNotification? = null
    override fun clearPendingNotification() {}
    override fun setPendingBattleResult(result: BattleResultUIData) {}
    override fun clearPendingBattleResult() {}
    override fun setPendingBeastAttacks(attacks: List<PendingBeastAttack>) {}
    override fun clearPendingBeastAttacks() {}
    override fun removePendingBeastAttack(beastLevelId: String) {}
    override fun clearPendingMarriageProposals() {}
    override fun setPendingBattleRewardCards(cards: List<RewardCardItem>) {}
    override fun clearPendingBattleRewardCards() {}
    override fun enqueueRewardCards(items: List<RewardCardItem>) {}
    override fun clearRewardCardQueue(count: Int) {}
    override fun <R> updateAndReturn(block: MutableGameState.() -> R): R {
        val m = MutableGameState(
            gameData = gameDataValue, discipleTables = _tables,
            equipmentStacks = eqStacks, equipmentInstances = eqInstances,
            manualStacks = mnStacks, manualInstances = mnInstances,
            pills = pils, materials = mats, herbs = hrbs,
            seeds = sds, storageBags = stBags,
            battleLogs = emptyList(),
            isPaused = false, isLoading = false, isSaving = false)
        val r = block(m)
        gameDataValue = m.gameData
        return r
    }
    override fun modifyState(block: MutableGameState.() -> Unit) { update(block) }
    override fun setPausedDirect(paused: Boolean) {}
    override fun setLoadingDirect(loading: Boolean) {}
    override fun setSavingDirect(saving: Boolean) {}
    override suspend fun loadFromSnapshot(
        gameData: GameData, disciples: List<Disciple>,
        equipmentStacks: List<EquipmentStack>, equipmentInstances: List<EquipmentInstance>,
        manualStacks: List<ManualStack>, manualInstances: List<ManualInstance>,
        pills: List<Pill>, materials: List<Material>, herbs: List<Herb>,
        seeds: List<Seed>, storageBags: List<StorageBag>,
        battleLogs: List<BattleLog>,
        isPaused: Boolean, isLoading: Boolean, isSaving: Boolean
    ) { this.gameDataValue = gameData }
    override suspend fun reset() { gameDataValue = GameData() }
    override fun advanceBootPhase() {}
    override fun resetBootPhase() {}
    override fun setPlaying() {}
    override fun setReloading() {}
    override fun setLoading() {}
    override fun setIdle() {}
    override fun enterBatchEmissionMode() {}
    override fun exitBatchEmissionMode() {}
    override fun takeAtomicSnapshot(): GameStateStore.GameSnapshot = GameStateStore.GameSnapshot()
}
