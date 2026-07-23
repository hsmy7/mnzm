package com.xianxia.sect.core.engine

import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentGate
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentRegistry
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.*
import com.xianxia.sect.core.util.DomainResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner

/**
 * GameEngineAtomicAssign 原子扩展方法的单元测试。
 *
 * 验证 6 个原子方法在 [GameStateStore.update] 单事务内的状态变更正确性。
 */
@RunWith(RobolectricTestRunner::class)
class GameEngineAtomicAssignTest {

    @get:Rule val writeGuardRule = WriteGuardRule()
    private lateinit var store: FakeAtomicStateStore
    private lateinit var gate: DiscipleAssignmentGate
    private lateinit var engine: GameEngine

    private val DISCIPLE_A = "1"
    private val DISCIPLE_B = "2"
    private val BUILDING_ID = "residence_b1"
    private val SLOT_0 = 0

    @Before
    fun setUp() {
        gate = DiscipleAssignmentGate(DiscipleAssignmentRegistry())
        store = FakeAtomicStateStore()

        // 创建测试弟子（在事务内初始化 DiscipleTables，tables 实例被 store 持久化）
        store.update {
            discipleTables.writeAllowed = true
            val a = DISCIPLE_A.toInt()
            discipleTables.ids.add(a)
            discipleTables.names[a] = "弟子A"
            discipleTables.statuses[a] = DiscipleStatus.IDLE
            discipleTables.isAlive[a] = 1
            discipleTables.realms[a] = 9
            discipleTables.realmLayers[a] = 1
            discipleTables.portraitRes[a] = "portrait_a"

            val b = DISCIPLE_B.toInt()
            discipleTables.ids.add(b)
            discipleTables.names[b] = "弟子B"
            discipleTables.statuses[b] = DiscipleStatus.IDLE
            discipleTables.isAlive[b] = 1
            discipleTables.realms[b] = 9
            discipleTables.realmLayers[b] = 1
            discipleTables.portraitRes[b] = "portrait_b"
            discipleTables.writeAllowed = false
        }

        // 创建测试槽位
        store.update {
            gameData = gameData.copy(
                residenceSlots = listOf(
                    ResidenceSlot(buildingInstanceId = BUILDING_ID, slotIndex = SLOT_0)
                ),
                patrolSlots = listOf(
                    PatrolSlot(index = 0),
                    PatrolSlot(index = 1)
                )
            )
        }

        // 使用 mock() 创建 GameEngine，31 个构造参数中仅 stateStore + assignmentGate 为真实实现
        engine = GameEngine(
            gameEngineCore = mock(),
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
            assignmentGate = gate,
            lawEnforcementProcessor = mock()
        )
    }

    // ── 住所分配 ──

    @Test
    fun `assignToResidenceAtomic 空槽位分配成功`() = runTest {
        val result = engine.assignToResidenceAtomic(BUILDING_ID, SLOT_0, DISCIPLE_A)

        assertTrue("应为 Success", result.isSuccess)
        val slot = store.latestGameData.residenceSlots[SLOT_0]
        assertEquals("槽位应写入弟子 A", DISCIPLE_A, slot.discipleId)
        assertEquals("槽位名应正确", "弟子A", slot.discipleName)
        assertTrue("gate 应注册", gate.isAssigned(DISCIPLE_A))
    }

    @Test
    fun `assignToResidenceAtomic 覆盖原住户时释放旧弟子`() = runTest {
        engine.assignToResidenceAtomic(BUILDING_ID, SLOT_0, DISCIPLE_A)
        assertTrue(gate.isAssigned(DISCIPLE_A))

        val result = engine.assignToResidenceAtomic(BUILDING_ID, SLOT_0, DISCIPLE_B)

        assertTrue("覆盖应成功", result.isSuccess)
        val slot = store.latestGameData.residenceSlots[SLOT_0]
        assertEquals("槽位应写入弟子 B", DISCIPLE_B, slot.discipleId)
        assertFalse("弟子 A 的 gate 应释放", gate.isAssigned(DISCIPLE_A))
        assertTrue("弟子 B 的 gate 应注册", gate.isAssigned(DISCIPLE_B))
    }

    @Test
    fun `assignToResidenceAtomic 不存在的弟子返回 Failure`() = runTest {
        val result = engine.assignToResidenceAtomic(BUILDING_ID, SLOT_0, "999")
        assertTrue("应为 Failure", result.isFailure)
    }

    // ── 住所移除 ──

    @Test
    fun `removeFromResidenceAtomic 移除后槽位清空`() = runTest {
        engine.assignToResidenceAtomic(BUILDING_ID, SLOT_0, DISCIPLE_A)

        val result = engine.removeFromResidenceAtomic(BUILDING_ID, SLOT_0)

        assertTrue("移除应成功", result.isSuccess)
        assertEquals("槽位应清空", "", store.latestGameData.residenceSlots[SLOT_0].discipleId)
        assertFalse("gate 应释放", gate.isAssigned(DISCIPLE_A))
    }

    @Test
    fun `removeFromResidenceAtomic 空槽位不做操作`() = runTest {
        val result = engine.removeFromResidenceAtomic(BUILDING_ID, SLOT_0)
        assertTrue("空槽位移除应 Success", result.isSuccess)
    }

    @Test
    fun `removeFromResidenceAtomic 不清除其他系统槽位`() = runTest {
        engine.assignToResidenceAtomic(BUILDING_ID, SLOT_0, DISCIPLE_A)
        // 模拟 A 也在巡视楼中
        store.update {
            val slots = gameData.patrolSlots.toMutableList()
            slots[0] = PatrolSlot(index = 0, discipleId = DISCIPLE_A, discipleName = "弟子A")
            gameData = gameData.copy(patrolSlots = slots)
        }

        engine.removeFromResidenceAtomic(BUILDING_ID, SLOT_0)

        assertEquals("巡视楼槽位不应被清除", DISCIPLE_A, store.latestGameData.patrolSlots[0].discipleId)
    }

    // ── 巡视楼分配 ──

    @Test
    fun `assignPatrolAtomic 分配成功设状态 PATROLLING`() = runTest {
        val result = engine.assignPatrolAtomic(DISCIPLE_A, globalIndex = 0)

        assertTrue("分配应成功", result.isSuccess)
        assertEquals("槽位应写入弟子 A", DISCIPLE_A, store.latestGameData.patrolSlots[0].discipleId)
        assertTrue("gate 应注册", gate.isAssigned(DISCIPLE_A))
    }

    @Test
    fun `assignPatrolAtomic 使用塔索引重载`() = runTest {
        val result = engine.assignPatrolAtomic(DISCIPLE_A, towerIndex = 0, slotOffset = 0, slotsPerTower = 2)
        assertTrue("便利重载应成功", result.isSuccess)
        assertEquals("槽位 0 应写入弟子 A", DISCIPLE_A, store.latestGameData.patrolSlots[0].discipleId)
    }

    // ── 巡视楼移除 ──

    @Test
    fun `removePatrolAtomic 移除后槽位清空 gate 释放`() = runTest {
        engine.assignPatrolAtomic(DISCIPLE_A, globalIndex = 0)

        val result = engine.removePatrolAtomic(globalIndex = 0)

        assertTrue("移除应成功", result.isSuccess)
        assertEquals("槽位应清空", "", store.latestGameData.patrolSlots[0].discipleId)
        assertFalse("gate 应释放", gate.isAssigned(DISCIPLE_A))
    }

    @Test
    fun `removePatrolAtomic 空槽位不做操作`() = runTest {
        val result = engine.removePatrolAtomic(globalIndex = 0)
        assertTrue("空槽位移除应 Success", result.isSuccess)
    }

    // ── 巡视楼交换 ──

    @Test
    fun `swapPatrolAtomic 交换两个槽位`() = runTest {
        engine.assignPatrolAtomic(DISCIPLE_A, globalIndex = 0)
        engine.assignPatrolAtomic(DISCIPLE_B, globalIndex = 1)

        val result = engine.swapPatrolAtomic(fromGlobalIndex = 0, toGlobalIndex = 1)

        assertTrue("交换应成功", result.isSuccess)
        val d = store.latestGameData
        assertEquals("槽位 0 应为弟子 B", DISCIPLE_B, d.patrolSlots[0].discipleId)
        assertEquals("槽位 1 应为弟子 A", DISCIPLE_A, d.patrolSlots[1].discipleId)
    }

    @Test
    fun `swapPatrolAtomic 相同索引不做操作`() = runTest {
        engine.assignPatrolAtomic(DISCIPLE_A, globalIndex = 0)
        val result = engine.swapPatrolAtomic(fromGlobalIndex = 0, toGlobalIndex = 0)
        assertTrue("同索引交换应 Success", result.isSuccess)
        assertEquals("槽位不变", DISCIPLE_A, store.latestGameData.patrolSlots[0].discipleId)
    }

    // ── 批量分配 ──

    @Test
    fun `autoAssignPatrolAtomic 批量分配成功`() = runTest {
        val result = engine.autoAssignPatrolAtomic(listOf(0 to DISCIPLE_A, 1 to DISCIPLE_B))

        assertTrue("批量分配应成功", result.isSuccess)
        val d = store.latestGameData
        assertEquals("槽位 0 为 A", DISCIPLE_A, d.patrolSlots[0].discipleId)
        assertEquals("槽位 1 为 B", DISCIPLE_B, d.patrolSlots[1].discipleId)
        assertTrue("A gate 注册", gate.isAssigned(DISCIPLE_A))
        assertTrue("B gate 注册", gate.isAssigned(DISCIPLE_B))
    }

    // ── CancellationException ──

    @Test(expected = kotlinx.coroutines.CancellationException::class)
    fun `CancellationException 不被吞入 Failure`() = runTest {
        DomainResult.catching<Unit> {
            throw kotlinx.coroutines.CancellationException("测试取消")
        }
    }

    // ── gate 与槽位一致性 ──

    @Test
    fun `已入住弟子的 gate 注册与 residenceSlots 一致`() = runTest {
        engine.assignToResidenceAtomic(BUILDING_ID, SLOT_0, DISCIPLE_A)

        for (slot in store.latestGameData.residenceSlots) {
            if (slot.discipleId.isNotEmpty()) {
                assertTrue("已入住的弟子 ${slot.discipleId} 应在 gate 中注册", gate.isAssigned(slot.discipleId))
            }
        }
    }
}

// ── Fake Atomic State Store ──

private class FakeAtomicStateStore : GameStateStore {

    // 持久化 DiscipleTables 实例（newMutable 复用同一实例，确保跨 update 持久化）
    val persistentDiscipleTables = DiscipleTables()

    // ── 启动/运行状态 ──
    override val lifecycleState = MutableStateFlow(GameStateStore.LifecycleState())
    override val bootPhase = MutableStateFlow(BootPhase.UNINITIALIZED)
    override val runState = MutableStateFlow(RunState.IDLE)

    // ── StateFlow 观察 ──
    private val _gameData = MutableStateFlow(GameData())
    override val gameData: StateFlow<GameData> get() = _gameData
    override val disciples = MutableStateFlow<List<Disciple>>(emptyList())
    override val discipleTables: DiscipleTables get() = persistentDiscipleTables
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
    override val pendingBattleResult = MutableStateFlow<BattleResultUIData?>(null)
    override val pendingNotification = MutableStateFlow<GameNotification?>(null)
    override val rewardCardQueue = MutableStateFlow<List<RewardCardItem>>(emptyList())
    override val pendingBeastAttacks = MutableStateFlow<List<PendingBeastAttack>>(emptyList())
    override val pendingBattleRewardCards = MutableStateFlow<List<RewardCardItem>>(emptyList())
    override val sectCombatPower = MutableStateFlow(0L)
    override val aiSectCombatPowers = MutableStateFlow<Map<String, Long>>(emptyMap())
    override val discipleAggregates = MutableStateFlow<List<DiscipleAggregate>>(emptyList())
    override val highFreqState = MutableStateFlow(GameStateStore.HighFreqState())
    override val entityState = MutableStateFlow(GameStateStore.EntityState())
    override val configState = MutableStateFlow(GameStateStore.ConfigState())
    override val unifiedState = MutableStateFlow(UnifiedGameState())

    // ── 快照 ──
    override val discipleAggregatesSnapshot: List<DiscipleAggregate> get() = discipleAggregates.value
    override val gameDataSnapshot: GameData get() = _gameData.value
    override val disciplesSnapshot: List<Disciple> get() = disciples.value
    override val equipmentStacksSnapshot: List<EquipmentStack> get() = equipmentStacks.value
    override val equipmentInstancesSnapshot: List<EquipmentInstance> get() = equipmentInstances.value
    override val manualStacksSnapshot: List<ManualStack> get() = manualStacks.value
    override val manualInstancesSnapshot: List<ManualInstance> get() = manualInstances.value
    override val pillsSnapshot: List<Pill> get() = pills.value
    override val materialsSnapshot: List<Material> get() = materials.value
    override val herbsSnapshot: List<Herb> get() = herbs.value
    override val seedsSnapshot: List<Seed> get() = seeds.value
    override val storageBagsSnapshot: List<StorageBag> get() = storageBags.value
    override val teamsSnapshot: List<ExplorationTeam> get() = teams.value
    override val battleLogsSnapshot: List<BattleLog> get() = battleLogs.value

    // ── 兼容层 ──
    override val gameLifecycle = MutableStateFlow(GameLifecycle.UNINITIALIZED)
    override val warehouseFullEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    // ── UI 交互状态 ──
    override var activeTab: String = ""
    override var activeDialog: String? = ""
    override var activeSubDialogs: Set<String> = emptySet()

    // ── 通知 / 战斗结果 ──
    override fun getCurrentSeeds(): List<Seed> = seeds.value
    override fun getCurrentHerbs(): List<Herb> = herbs.value
    override fun getCurrentMaterials(): List<Material> = materials.value
    override fun setPendingNotification(n: GameNotification) { pendingNotification.value = n }
    override fun clearPendingNotification() { pendingNotification.value = null }
    override val notifications = MutableStateFlow<List<GameNotification>>(emptyList())
    override fun enqueueNotification(n: GameNotification) { notifications.value = notifications.value + n }
    override fun consumeNotification(): GameNotification? {
        val item = notifications.value.firstOrNull()
        if (item != null) notifications.value = notifications.value.drop(1)
        return item
    }
    override fun setPendingBattleResult(r: BattleResultUIData) { pendingBattleResult.value = r }
    override fun clearPendingBattleResult() { pendingBattleResult.value = null }
    override fun setPendingBeastAttacks(a: List<PendingBeastAttack>) { pendingBeastAttacks.value = a }
    override fun clearPendingBeastAttacks() { pendingBeastAttacks.value = emptyList() }
    override fun removePendingBeastAttack(beastLevelId: String) { pendingBeastAttacks.value = pendingBeastAttacks.value.filter { it.beastLevel.id != beastLevelId } }
    override fun setPendingBattleRewardCards(c: List<RewardCardItem>) { pendingBattleRewardCards.value = c }
    override fun clearPendingBattleRewardCards() { pendingBattleRewardCards.value = emptyList() }
    override fun enqueueRewardCards(items: List<RewardCardItem>) {}
    override fun clearRewardCardQueue(count: Int) {}
    override fun setPausedDirect(paused: Boolean) { isPaused.value = paused }
    override fun setLoadingDirect(loading: Boolean) { isLoading.value = loading }
    override fun setSavingDirect(saving: Boolean) { isSaving.value = saving }
    override suspend fun loadFromSnapshot(gameData: GameData, disciples: List<Disciple>, equipmentStacks: List<EquipmentStack>, equipmentInstances: List<EquipmentInstance>, manualStacks: List<ManualStack>, manualInstances: List<ManualInstance>, pills: List<Pill>, materials: List<Material>, herbs: List<Herb>, seeds: List<Seed>, storageBags: List<StorageBag>, teams: List<ExplorationTeam>, battleLogs: List<BattleLog>, isPaused: Boolean, isLoading: Boolean, isSaving: Boolean) {
        this._gameData.value = gameData; this.disciples.value = disciples; this.teams.value = teams; this.battleLogs.value = battleLogs; this.isPaused.value = isPaused; this.isLoading.value = isLoading; this.isSaving.value = isSaving
    }
    override suspend fun reset() { _gameData.value = GameData(); disciples.value = emptyList(); bootPhase.value = BootPhase.UNINITIALIZED; runState.value = RunState.IDLE }

    // ── 生命周期 ──
    override fun advanceBootPhase() {}
    override fun resetBootPhase() {}
    override fun setPlaying() { runState.value = RunState.PLAYING }
    override fun setReloading() { runState.value = RunState.RELOADING }
    override fun setLoading() { runState.value = RunState.LOADING }
    override fun setIdle() { runState.value = RunState.IDLE }

    /** 最后一次 update 后的 GameData 快照 */
    var latestGameData: GameData = GameData()
        private set

    override fun update(block: MutableGameState.() -> Unit) {
        val m = newMutable()
        block(m)
        _gameData.value = m.gameData
        latestGameData = m.gameData
        teams.value = m.teams
        battleLogs.value = m.battleLogs
        isPaused.value = m.isPaused
        isLoading.value = m.isLoading
        isSaving.value = m.isSaving
    }

    override fun <R> updateAndReturn(block: MutableGameState.() -> R): R {
        val m = newMutable()
        val result = block(m)
        _gameData.value = m.gameData
        latestGameData = m.gameData
        teams.value = m.teams; battleLogs.value = m.battleLogs
        isPaused.value = m.isPaused; isLoading.value = m.isLoading; isSaving.value = m.isSaving
        return result
    }

    override fun modifyState(block: MutableGameState.() -> Unit) { update(block) }
    override fun enterBatchEmissionMode() {}
    override fun exitBatchEmissionMode() {}
    override fun takeAtomicSnapshot(): GameStateStore.GameSnapshot = GameStateStore.GameSnapshot()

    /** 使用持久化 DiscipleTables 实例，确保跨 update 调用持久化 */
    private fun newMutable() = MutableGameState(
        gameData = _gameData.value,
        discipleTables = persistentDiscipleTables,
        equipmentStacks = EntityStore(),
        equipmentInstances = EntityStore(),
        manualStacks = EntityStore(),
        manualInstances = EntityStore(),
        pills = EntityStore(),
        materials = EntityStore(),
        herbs = EntityStore(),
        seeds = EntityStore(),
        storageBags = EntityStore(),
        teams = teams.value,
        battleLogs = battleLogs.value,
        isPaused = isPaused.value,
        isLoading = isLoading.value,
        isSaving = isSaving.value
    )
}
