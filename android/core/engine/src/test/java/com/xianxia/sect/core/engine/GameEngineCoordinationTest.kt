package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.*
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
}

// ── 测试用 GameEngine + GameStateStore 的最小化环境 ──

private class EngineTestEnv {
    val store = SimpleStore()
    val engine = GameEngine(
        gameEngineCore = mock(),
        engineContextDispatcher = FakeEngineContextDispatcher(),
        stateStore = store,
        inventorySystem = mock(),
        inventoryConfig = mock(),
        battleSystem = mock(),
        productionCoordinator = mock(),
        discipleService = mock(),
        combatService = mock(),
        explorationService = mock(),
        buildingService = mock(),
        saveService = mock(),
        cultivationService = mock(),
        diplomacyService = mock(),
        redeemCodeService = mock(),
        formulaService = mock(),
        mailService = mock(),
        dailySignInService = mock(),
        autoBuyService = mock(),
        heavyDataPort = mock(),
        heavyDataDecoder = mock(),
        discipleFacade = mock(),
        battleFacade = mock(),
        buildingFacade = mock(),
        inventoryFacade = mock(),
        diplomacyFacade = mock(),
        productionFacade = mock(),
        saveFacade = mock(),
        spiritStoneWallet = mock(),
        gameRngManager = mock(),
        assignmentGate = mock(),
        lawEnforcementProcessor = mock(),
        secretRealmService = mock()
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
            teams = emptyList(),
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
    override val teams = MutableStateFlow<List<ExplorationTeam>>(emptyList())
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
    override val teamsSnapshot: List<ExplorationTeam> get() = emptyList()
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
            teams = emptyList(), battleLogs = emptyList(),
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
        teams: List<ExplorationTeam>, battleLogs: List<BattleLog>,
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
