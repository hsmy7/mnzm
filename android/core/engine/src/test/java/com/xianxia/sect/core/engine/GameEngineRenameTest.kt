package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner

/**
 * GameEngine.renameDisciple 原子改名 + 招募列表同人残留净化的单元测试。
 *
 * 背景：改名会破坏 RecruitIntegrity.isSamePerson 的 5 字段签名匹配，
 * 若不同时净化 recruitList，残留双胞胎将永久逃脱三层净化、可被重复招募。
 *
 * ★ Robolectric 必需：DiscipleTables 的 Ref 列（names 等）基于
 * android.util.SparseArray——无 Robolectric 时 SparseArray 未 mock
 * （returnDefaultValues 静默失效），写入被丢弃导致测试假失败。
 */
@RunWith(RobolectricTestRunner::class)
class GameEngineRenameTest {

    /** 测试直接操作 DiscipleTables（事务外 allocateAndInsert）需要禁用写守卫 */
    @get:Rule val writeGuardRule = WriteGuardRule()

    @Test
    fun `renameDisciple - 清除同人残留且保留无关条目`() = runBlocking {
        val env = RenameEnv()
        env.store.tables.allocateAndInsert(createDisciple(id = "1", name = "旧名", age = 20))
        val twin = createDisciple(id = "recruit-1", name = "旧名", age = 21)
        val other = createDisciple(id = "recruit-2", name = "无关弟子", age = 30)
        env.store.gameDataValue = GameData(recruitList = listOf(twin, other))

        env.engine.renameDisciple("1", "新名")

        assertEquals("弟子表应已改名", "新名", env.store.tables.assemble(1).name)
        val kept = env.store.gameDataValue.recruitList
        assertEquals("同人残留应被清除，无关条目保留", 1, kept.size)
        assertEquals("recruit-2", kept[0].id)
    }

    @Test
    fun `renameDisciple - 无同人残留时列表不变`() = runBlocking {
        val env = RenameEnv()
        env.store.tables.allocateAndInsert(createDisciple(id = "1", name = "旧名", age = 20))
        val other = createDisciple(id = "recruit-2", name = "无关弟子", age = 30)
        env.store.gameDataValue = GameData(recruitList = listOf(other))

        env.engine.renameDisciple("1", "新名")

        assertEquals("新名", env.store.tables.assemble(1).name)
        assertEquals("无同人残留时列表不变", 1, env.store.gameDataValue.recruitList.size)
    }

    @Test
    fun `renameDisciple - 已死亡弟子非对称容差命中清除、低龄条目保留`() = runBlocking {
        val env = RenameEnv()
        env.store.tables.allocateAndInsert(
            createDisciple(id = "1", name = "死者", age = 18, isAlive = false)
        )
        // 同源拷贝残留：年龄冻结在死者年龄之上 → 非对称容差命中
        val clone = createDisciple(id = "recruit-1", name = "死者", age = 20)
        // 合法同名新条目：年龄远小于死者冻结年龄 → 不误删
        val fresh = createDisciple(id = "recruit-2", name = "死者", age = 10)
        env.store.gameDataValue = GameData(recruitList = listOf(clone, fresh))

        env.engine.renameDisciple("1", "新名")

        val kept = env.store.gameDataValue.recruitList
        assertEquals("同源拷贝清除、低龄合法条目保留", 1, kept.size)
        assertEquals("recruit-2", kept[0].id)
    }

    @Test
    fun `renameDisciple - 不存在的 id 无副作用`() = runBlocking {
        val env = RenameEnv()
        val other = createDisciple(id = "recruit-2", name = "无关弟子", age = 30)
        env.store.gameDataValue = GameData(recruitList = listOf(other))

        env.engine.renameDisciple("999", "新名")

        assertEquals("招募列表不变", 1, env.store.gameDataValue.recruitList.size)
    }

    @Test
    fun `renameDisciple - 空招募列表正常改名`() = runBlocking {
        val env = RenameEnv()
        env.store.tables.allocateAndInsert(createDisciple(id = "1", name = "旧名", age = 20))

        env.engine.renameDisciple("1", "新名")

        assertEquals("新名", env.store.tables.assemble(1).name)
        assertTrue("招募列表仍为空", env.store.gameDataValue.recruitList.isEmpty())
    }
}

// ── 测试用最小环境 ──

private class RenameEnv {
    val store = RenameStore()
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
        lawEnforcementProcessor = mock()
    )
}

private class RenameStore : GameStateStore {

    /** 测试用：直接读写 GameData */
    var gameDataValue: GameData = GameData()

    private val _gameDataFlow = MutableStateFlow(GameData())
    override val gameData: StateFlow<GameData> get() = _gameDataFlow
    override val gameDataSnapshot: GameData get() = gameDataValue

    val tables = DiscipleTables()
    override val discipleTables: DiscipleTables get() = tables

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
            discipleTables = tables,
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
        // ★ 模拟真实 GameStateStoreImpl 语义：事务内 writeAllowed=true（COW 提交表锁定后，
        // 引擎线程执行 update 时 writeGuardEnabled=true，测试 store 不放开会抛
        // "Direct write outside stateStore.update"）
        tables.writeAllowed = true
        try {
            block(mutable)
        } finally {
            tables.writeAllowed = false
        }
        gameDataValue = mutable.gameData
        _gameDataFlow.value = mutable.gameData
    }
    override val lifecycleState = MutableStateFlow(GameStateStore.LifecycleState())
    override val bootPhase = MutableStateFlow(BootPhase.UNINITIALIZED)
    override val runState = MutableStateFlow(RunState.IDLE)
    override val unifiedState = MutableStateFlow(UnifiedGameState())
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
    override fun setPendingNotification(notification: GameNotification) {}
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
            gameData = gameDataValue, discipleTables = tables,
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

private fun createDisciple(
    id: String,
    name: String,
    age: Int = 18,
    isAlive: Boolean = true
) = Disciple(
    id = id, name = name, age = age, realm = 9, realmLayer = 1,
    cultivation = 0.0, isAlive = isAlive, status = DiscipleStatus.IDLE,
    discipleType = "outer", spiritRootType = "metal", gender = "male",
    portraitRes = "default", skills = SkillStats(), combat = CombatAttributes(),
    lifespan = 80, social = SocialData(), usage = UsageTracking()
)
