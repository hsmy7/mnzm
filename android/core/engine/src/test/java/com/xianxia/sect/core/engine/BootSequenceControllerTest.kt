package com.xianxia.sect.core.engine

import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.engine.domain.disciple.DiscipleSnapshotCache
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.state.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.*

class BootSequenceControllerTest {

    private lateinit var stateStore: FakeGameStateStore
    private lateinit var gameEngineCore: GameEngineCore
    private lateinit var gameEngine: GameEngine
    private lateinit var buildingConfigService: BuildingConfigService
    private lateinit var discipleSnapshotCache: DiscipleSnapshotCache
    private lateinit var controller: BootSequenceController

    private val gameDataFlow = MutableStateFlow(GameData())
    private val disciplesFlow = MutableStateFlow<List<Disciple>>(emptyList())
    private val discipleTables = DiscipleTables()

    @Before
    fun setUp() {
        stateStore = FakeGameStateStore()
        gameEngineCore = mock()
        gameEngine = mock()
        buildingConfigService = mock()
        discipleSnapshotCache = mock()

        // GameEngine 属性: 扩展函数 (updateGameData / ensureHeavyDataLoaded) 内部
        // 通过 gameEngine.stateStore 访问 FakeGameStateStore，因此需要 stub
        whenever(gameEngine.stateStore).thenReturn(stateStore)
        whenever(gameEngine.gameData).thenReturn(gameDataFlow)
        whenever(gameEngine.gameDataSnapshot).thenReturn(gameDataFlow.value)
        whenever(gameEngine.disciples).thenReturn(disciplesFlow)
        whenever(gameEngine.discipleTables).thenReturn(discipleTables)
        whenever(gameEngine.discipleAggregatesSnapshot).thenReturn(emptyList())

        // BuildingConfigService: 原样返回传入列表
        whenever(buildingConfigService.fixupBuildingSizes(any())).thenAnswer {
            @Suppress("UNCHECKED_CAST")
            (it.getArgument(0) as List<GridBuildingData>)
        }

        // GameEngineCore 生命周期方法: 不执行真实逻辑
        doNothing().whenever(gameEngineCore).startListening()
        doNothing().whenever(gameEngineCore).startGameLoop()
        doNothing().whenever(gameEngineCore).stopGameLoop()

        // DiscipleSnapshotCache
        doNothing().whenever(discipleSnapshotCache).prewarm(any())

        controller = BootSequenceController(
            stateStore = stateStore,
            gameEngineCore = gameEngineCore,
            gameEngine = gameEngine,
            buildingConfigService = buildingConfigService,
            discipleSnapshotCache = discipleSnapshotCache
        )
    }

    // ──────────────────────────────────────────────────────────────────
    // Test 1: IDLE → 全阶段推进 → PLAYING
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `boot - when IDLE - advances through all boot phases and sets PLAYING`() = runTest {
        stateStore.runState.value = RunState.IDLE
        stateStore.bootPhase.value = BootPhase.UNINITIALIZED
        var onSuccessCalled = false

        val result = controller.boot(slot = 1, onSuccess = { onSuccessCalled = true })

        assertTrue("boot should succeed", result.isSuccess)
        assertTrue("onSuccess callback should be called", onSuccessCalled)
        assertEquals("runState should be PLAYING", RunState.PLAYING, stateStore.runState.value)
        assertEquals("bootPhase should be BOOT_COMPLETE", BootPhase.BOOT_COMPLETE, stateStore.bootPhase.value)

        // 验证阶段推进序列: UNINITIALIZED → DATA_READY → SYSTEMS_READY → MAP_READY → BOOT_COMPLETE
        assertEquals(
            "advanceBootPhase 应被调用 4 次",
            listOf(
                BootPhase.DATA_READY,
                BootPhase.SYSTEMS_READY,
                BootPhase.MAP_READY,
                BootPhase.BOOT_COMPLETE
            ),
            stateStore.bootPhaseHistory
        )

        verify(gameEngineCore).startListening()
        verify(gameEngineCore).startGameLoop()
        verify(buildingConfigService).fixupBuildingSizes(any())
        verify(discipleSnapshotCache).prewarm(discipleTables)
    }

    // ──────────────────────────────────────────────────────────────────
    // Test 2: PLAYING → STOP → RELOADING → ... → PLAYING
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `boot - when PLAYING - resets boot phase and reloads properly`() = runTest {
        stateStore.runState.value = RunState.PLAYING
        stateStore.bootPhase.value = BootPhase.BOOT_COMPLETE
        var onSuccessCalled = false

        val result = controller.boot(slot = 1, onSuccess = { onSuccessCalled = true })

        assertTrue("reload should succeed", result.isSuccess)
        assertTrue("onSuccess callback should be called", onSuccessCalled)
        assertEquals("runState should be PLAYING after reload", RunState.PLAYING, stateStore.runState.value)
        assertEquals("bootPhase should be BOOT_COMPLETE after reload", BootPhase.BOOT_COMPLETE, stateStore.bootPhase.value)

        // 验证 reload 路径: stopGameLoop → setReloading → resetBootPhase
        verify(gameEngineCore).stopGameLoop()
        assertTrue("setReloading should have been called", stateStore.reloadingCalled)
        assertTrue("resetBootPhase should have been called", stateStore.resetCalled)

        // runState 经历: PLAYING → RELOADING → PLAYING
        assertTrue(
            "runState should have passed through RELOADING",
            stateStore.runStateHistory.contains(RunState.RELOADING)
        )

        // 正常推进路径继续执行
        verify(gameEngineCore).startListening()
        verify(gameEngineCore).startGameLoop()
    }

    // ──────────────────────────────────────────────────────────────────
    // Test 3: 异常恢复 — 有部分数据时走 recoverWithPartialData 路径
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `boot - error recovery with partial data - recovers and returns success`() = runTest {
        stateStore.runState.value = RunState.IDLE
        stateStore.bootPhase.value = BootPhase.UNINITIALIZED

        // 构造部分数据: 宗门名非空 + 弟子非空
        gameDataFlow.value = GameData(sectName = "青云宗")
        disciplesFlow.value = listOf(Disciple())

        var onSuccessCalled = false
        var onErrorCalled = false

        // onPreloadResources 抛异常 → 触发 catch → recoverWithPartialData → 成功恢复
        val result = controller.boot(
            slot = 1,
            onPreloadResources = { throw RuntimeException("preload failure") },
            onSuccess = { onSuccessCalled = true },
            onError = { msg ->
                onErrorCalled = true
            }
        )

        assertTrue("boot should succeed via recovery", result.isSuccess)
        assertTrue("onSuccess callback should be called", onSuccessCalled)
        assertFalse("onError should not be called", onErrorCalled)

        // 恢复应设置 bootPhase=BOOT_COMPLETE, runState=PLAYING
        assertEquals(
            "bootPhase should be BOOT_COMPLETE after recovery",
            BootPhase.BOOT_COMPLETE, stateStore.bootPhase.value
        )
        assertEquals(
            "runState should be PLAYING after recovery",
            RunState.PLAYING, stateStore.runState.value
        )

        // recoverWithPartialData 内调用了 startGameLoop
        verify(gameEngineCore).startGameLoop()
    }

    // ──────────────────────────────────────────────────────────────────
    // Test 4: onProgress 回调值验证
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `boot - onProgress callback receives correct values`() = runTest {
        stateStore.runState.value = RunState.IDLE
        stateStore.bootPhase.value = BootPhase.UNINITIALIZED
        val progressValues = mutableListOf<Float>()

        controller.boot(slot = 1, onProgress = { progressValues.add(it) })

        assertTrue("onProgress should have been called multiple times", progressValues.size >= 6)

        // 验证起始值和终值
        assertEquals("first progress should be 0.05", 0.05f, progressValues.first(), 0.001f)
        assertEquals("last progress should be 1.0", 1.0f, progressValues.last(), 0.001f)

        // 验证关键里程碑值存在
        assertTrue("progress should include 0.10", progressValues.contains(0.10f))
        assertTrue("progress should include 0.20", progressValues.contains(0.20f))
        assertTrue("progress should include 0.40", progressValues.contains(0.40f))
        assertTrue("progress should include 0.60", progressValues.contains(0.60f))
        assertTrue("progress should include 0.80", progressValues.contains(0.80f))

        // 严格递增
        for (i in 1 until progressValues.size) {
            assertTrue(
                "progress should be strictly increasing: " +
                    "progress[$i]=${progressValues[i]} <= progress[${i - 1}]=${progressValues[i - 1]}",
                progressValues[i - 1] < progressValues[i]
            )
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Test 5: onMapReady 收到 MapPreloadData
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `boot - onMapReady receives MapPreloadData when map generation succeeds`() = runTest {
        stateStore.runState.value = RunState.IDLE
        stateStore.bootPhase.value = BootPhase.UNINITIALIZED
        var capturedMapData: MapPreloadData? = null

        controller.boot(slot = 1, onMapReady = { capturedMapData = it })

        assertNotNull("onMapReady should receive MapPreloadData", capturedMapData)
        capturedMapData?.let { data ->
            assertTrue("worldWidthCells should be positive", data.worldWidthCells > 0)
            assertTrue("worldHeightCells should be positive", data.worldHeightCells > 0)
            assertEquals("tileSize should match GameConfig value", 32, data.tileSize)
            assertTrue("flatTileData should be non-empty", data.flatTileData.isNotEmpty())
        }
    }
}

// ====================================================================
// FakeGameStateStore — 跟踪 bootPhase/runState 变化
// ====================================================================

/**
 * 用于 [BootSequenceControllerTest] 的 [GameStateStore] Fake 实现。
 *
 * 与 [ExplorationTeamManagerTest] 中的无操作存根不同，本 Fake 会实际修改
 * [bootPhase] 和 [runState] 的 StateFlow 值，以便 [BootSequenceController]
 * 在启动过程中读取到正确的当前状态。同时记录 [advanceBootPhase] /
 * [resetBootPhase] / [setReloading] 等调用历史供测试断言使用。
 */
@Suppress("TooManyFunctions")
private class FakeGameStateStore : GameStateStore {

    // ── 启动/运行状态（测试关注点）──
    override val lifecycleState = MutableStateFlow(GameStateStore.LifecycleState())
    override val bootPhase = MutableStateFlow(BootPhase.UNINITIALIZED)
    override val runState = MutableStateFlow(RunState.IDLE)

    /** 每次 [advanceBootPhase] 的记录（含 reset 后的 UNINITIALIZED） */
    val bootPhaseHistory = mutableListOf<BootPhase>()

    /** 每次 runState 变化的记录 */
    val runStateHistory = mutableListOf(RunState.IDLE)

    var reloadingCalled = false
    var resetCalled = false

    private fun syncGameLifecycle() {
        gameLifecycle.value = when {
            runState.value == RunState.PLAYING && bootPhase.value >= BootPhase.BOOT_COMPLETE -> GameLifecycle.PLAYING
            bootPhase.value >= BootPhase.MAP_READY -> GameLifecycle.MAP_READY
            bootPhase.value >= BootPhase.SYSTEMS_READY -> GameLifecycle.SYSTEMS_READY
            bootPhase.value >= BootPhase.DATA_READY -> GameLifecycle.DATA_READY
            else -> GameLifecycle.UNINITIALIZED
        }
    }

    override fun advanceBootPhase() {
        val next = BootPhase.entries[bootPhase.value.ordinal + 1]
        bootPhase.value = next
        bootPhaseHistory.add(next)
        syncGameLifecycle()
    }

    override fun resetBootPhase() {
        bootPhase.value = BootPhase.UNINITIALIZED
        bootPhaseHistory.add(BootPhase.UNINITIALIZED)
        resetCalled = true
        syncGameLifecycle()
    }

    override fun setPlaying() {
        runState.value = RunState.PLAYING
        runStateHistory.add(RunState.PLAYING)
        syncGameLifecycle()
    }

    override fun setReloading() {
        runState.value = RunState.RELOADING
        runStateHistory.add(RunState.RELOADING)
        reloadingCalled = true
        syncGameLifecycle()
    }

    override fun setLoading() {
        runState.value = RunState.LOADING
        runStateHistory.add(RunState.LOADING)
        syncGameLifecycle()
    }

    override fun setIdle() {
        runState.value = RunState.IDLE
        runStateHistory.add(RunState.IDLE)
        syncGameLifecycle()
    }

    // ── StateFlow 观察 ──

    private val _gameData = MutableStateFlow(GameData())
    override val gameData: StateFlow<GameData> get() = _gameData
    override val disciples = MutableStateFlow<List<Disciple>>(emptyList())
    override val discipleTables = DiscipleTables()
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

    // ── 三层 StateFlow ──
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

    // ── 兼容层 API ──
    override val gameLifecycle = MutableStateFlow(GameLifecycle.UNINITIALIZED)
    override fun transitionTo(state: GameLifecycle) { gameLifecycle.value = state }
    override fun forceLifecycle(state: GameLifecycle) { gameLifecycle.value = state }

    // ── 事件 ──
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
    override fun setPendingBattleResult(r: BattleResultUIData) { pendingBattleResult.value = r }
    override fun clearPendingBattleResult() { pendingBattleResult.value = null }
    override fun setPendingBeastAttacks(a: List<PendingBeastAttack>) { pendingBeastAttacks.value = a }
    override fun clearPendingBeastAttacks() { pendingBeastAttacks.value = emptyList() }
    override fun setPendingBattleRewardCards(c: List<RewardCardItem>) { pendingBattleRewardCards.value = c }
    override fun clearPendingBattleRewardCards() { pendingBattleRewardCards.value = emptyList() }
    override fun enqueueRewardCards(items: List<RewardCardItem>) {}
    override fun clearRewardCardQueue(count: Int) {}

    // ── 直接设置 ──
    override fun setPausedDirect(paused: Boolean) { isPaused.value = paused }
    override fun setLoadingDirect(loading: Boolean) { isLoading.value = loading }
    override fun setSavingDirect(saving: Boolean) { isSaving.value = saving }

    // ── Shadow / 事务 API ──
    override fun createSettlementShadow(productionSlots: List<ProductionSlot>): MutableGameState = newMutable()

    override suspend fun swapFromShadow(shadow: MutableGameState) {
        _gameData.value = shadow.gameData
        teams.value = shadow.teams
        battleLogs.value = shadow.battleLogs
        isPaused.value = shadow.isPaused
        isLoading.value = shadow.isLoading
        isSaving.value = shadow.isSaving
    }

    override suspend fun loadFromSnapshot(
        gameData: GameData, disciples: List<Disciple>,
        equipmentStacks: List<EquipmentStack>, equipmentInstances: List<EquipmentInstance>,
        manualStacks: List<ManualStack>, manualInstances: List<ManualInstance>,
        pills: List<Pill>, materials: List<Material>, herbs: List<Herb>, seeds: List<Seed>,
        storageBags: List<StorageBag>, teams: List<ExplorationTeam>,
        battleLogs: List<BattleLog>, isPaused: Boolean, isLoading: Boolean, isSaving: Boolean
    ) {
        this._gameData.value = gameData
        this.disciples.value = disciples
        this.teams.value = teams
        this.battleLogs.value = battleLogs
        this.isPaused.value = isPaused
        this.isLoading.value = isLoading
        this.isSaving.value = isSaving
    }

    override suspend fun reset() {
        _gameData.value = GameData()
        disciples.value = emptyList()
        bootPhase.value = BootPhase.UNINITIALIZED
        runState.value = RunState.IDLE
    }

    // ── 核心事务 API ──
    override fun update(block: MutableGameState.() -> Unit) {
        val m = newMutable()
        block(m)
        _gameData.value = m.gameData
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
        teams.value = m.teams
        battleLogs.value = m.battleLogs
        isPaused.value = m.isPaused
        isLoading.value = m.isLoading
        isSaving.value = m.isSaving
        return result
    }

    override fun modifyState(block: MutableGameState.() -> Unit) { update(block) }
    override fun enterBatchEmissionMode() {}
    override fun exitBatchEmissionMode() {}

    private fun newMutable() = MutableGameState(
        gameData = _gameData.value,
        discipleTables = DiscipleTables(),
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
