package com.xianxia.sect.core.engine

import com.xianxia.sect.core.config.BuildingConfigService
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
import com.xianxia.sect.core.model.MapPreloadData
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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*



class BootSequenceControllerTest {

    private lateinit var stateStore: FakeGameStateStore
    private lateinit var gameEngineCore: GameEngineCore
    private lateinit var gameEngine: GameEngine
    private lateinit var buildingConfigService: BuildingConfigService
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

        // EngineContextDispatcher: 使用 Fake 确保 extension 函数内部 withEngineContext 正常执行
        whenever(gameEngine.engineContextDispatcher).thenReturn(FakeEngineContextDispatcher())

        // GameEngine 属性: 扩展函数 (updateGameData / ensureHeavyDataLoaded) 内部
        // 通过 gameEngine.stateStore 访问 FakeGameStateStore，因此需要 stub
        whenever(gameEngine.stateStore).thenReturn(stateStore)
        whenever(gameEngine.gameData).thenReturn(gameDataFlow)
        whenever(gameEngine.gameDataSnapshot).thenReturn(gameDataFlow.value)
        whenever(gameEngine.disciples).thenReturn(disciplesFlow)
        whenever(gameEngine.discipleTables).thenReturn(discipleTables)
        whenever(gameEngine.discipleAggregatesSnapshot).thenReturn(emptyList())

        // BuildingConfigService: 原样返回传入列表
        // 注：fixupBuildingSizes 默认参数编译为 3 参数签名（buildings, worldW, worldH）
        whenever(buildingConfigService.fixupBuildingSizes(
            any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()
        )).thenAnswer {
            @Suppress("UNCHECKED_CAST")
            (it.getArgument(0) as List<GridBuildingData>)
        }

        // GameEngineCore 生命周期方法: 不执行真实逻辑
        doNothing().whenever(gameEngineCore).startGameLoop()
        doNothing().whenever(gameEngineCore).stopGameLoop()


        // CultivationService: ensureGameDataIntegrity → checkAndRepairMerchantAndRecruit 会调用
        whenever(gameEngine.cultivationService).thenReturn(mock())

        // T15（2026-08-05）：recoverWithPartialData 补守卫需访问 productionCoordinator.repository。
        // 注意：不 stub getSlots()——ProductionSlotRepository 的属性 `val slots` 编译为同名 JVM
        // getter（返回 StateFlow），与函数 getSlots()（返回 List）同名，Mockito 按名匹配到
        // StateFlow 版本抛 WrongTypeOfReturnValue；BootSequenceController 调用有 try-catch 兜底，
        // mock 默认 null 会被 catch 转为 emptyList，无需 stub。
        whenever(gameEngine.productionCoordinator).thenReturn(mock())

        // assignmentGate: 创建真实 Gate 用于注册表重建
        val realGate = com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentGate(
            com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentRegistry()
        )
        whenever(gameEngine.assignmentGate).thenReturn(realGate)

        controller = BootSequenceController(
            stateStore = stateStore,
            gameEngineCore = gameEngineCore,
            gameEngine = gameEngine,
            buildingConfigService = buildingConfigService
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

        verify(gameEngineCore).startGameLoop()
        verify(buildingConfigService).fixupBuildingSizes(
            any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()
        )
    }

    // ──────────────────────────────────────────────────────────────────
    // Test 1.5: Step 3 建筑自愈守卫（D-13 孤儿归一化 + D-11 activeSectId 净化）
    // ──────────────────────────────────────────────────────────────────

    /** 向 FakeGameStateStore 注入初始游戏数据（loadFromSnapshot 是唯一写入入口） */
    private suspend fun injectTestGameData(gameData: GameData) {
        stateStore.loadFromSnapshot(
            gameData,
            disciples = emptyList(), equipmentStacks = emptyList(), equipmentInstances = emptyList(),
            manualStacks = emptyList(), manualInstances = emptyList(), pills = emptyList(),
            materials = emptyList(), herbs = emptyList(), seeds = emptyList(),
            storageBags = emptyList(), battleLogs = emptyList(),
            isPaused = false, isLoading = false, isSaving = false
        )
        // gameDataSnapshot stub 同步到注入数据（boot Step 3.5 溢出迁移/边界迁移读取）
        whenever(gameEngine.gameDataSnapshot).thenReturn(gameData)
    }

    @Test
    fun `boot - D13 孤儿建筑归一化生效`() = runTest {
        stateStore.runState.value = RunState.IDLE
        stateStore.bootPhase.value = BootPhase.UNINITIALIZED

        injectTestGameData(
            GameData(
                placedBuildings = listOf(
                    GridBuildingData(displayName = "灵矿场", gridX = 10, gridY = 10,
                        width = 4, height = 4, sectId = "sect_dead", instanceId = "m1"),
                    GridBuildingData(displayName = "炼丹炉", gridX = 20, gridY = 20,
                        width = 4, height = 3, sectId = "", instanceId = "f1")
                ),
                worldMapSects = listOf(
                    WorldSect(id = "player_sect", name = "玩家宗门", isPlayerSect = true),
                    WorldSect(id = "sect_1", name = "AI 宗门")
                )
            )
        )

        val result = controller.boot(slot = 1)
        assertTrue("boot should succeed", result.isSuccess)

        val gd = stateStore.gameData.value
        assertEquals("孤儿建筑应归入本宗", "",
            gd.placedBuildings.first { it.instanceId == "m1" }.sectId)
        assertEquals("本宗建筑不动", "",
            gd.placedBuildings.first { it.instanceId == "f1" }.sectId)
    }

    @Test
    fun `boot - D11 activeSectId 残留净化生效`() = runTest {
        stateStore.runState.value = RunState.IDLE
        stateStore.bootPhase.value = BootPhase.UNINITIALIZED

        injectTestGameData(
            GameData(
                activeSectId = "sect_dead",  // 指向不存在的宗门 → 应净化回本宗 ""
                placedBuildings = listOf(
                    GridBuildingData(displayName = "灵矿场", gridX = 10, gridY = 10,
                        width = 4, height = 4, sectId = "", instanceId = "m1")
                ),
                worldMapSects = listOf(
                    WorldSect(id = "player_sect", name = "玩家宗门", isPlayerSect = true),
                    WorldSect(id = "sect_1", name = "AI 宗门")
                )
            )
        )

        val result = controller.boot(slot = 1)
        assertTrue("boot should succeed", result.isSuccess)

        assertEquals("残留 activeSectId 应净化回本宗", "", stateStore.gameData.value.activeSectId)
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
        var capturedMap: MapPreloadData? = null

        // onPreloadResources 抛异常 → 触发 catch → recoverWithPartialData → 成功恢复
        val result = controller.boot(
            slot = 1,
            onPreloadResources = { throw RuntimeException("preload failure") },
            onSuccess = { onSuccessCalled = true },
            onError = { msg ->
                onErrorCalled = true
            },
            onMapReady = { capturedMap = it }
        )

        assertTrue("boot should succeed via recovery", result.isSuccess)
        assertTrue("onSuccess callback should be called", onSuccessCalled)
        assertFalse("onError should not be called", onErrorCalled)

        // 2026-08-04 修复断言：recover 路径必须调用 onMapReady——
        // 否则 UI 侧 mapPreloadData 为 null → 永久 LoadingScreen
        assertNotNull("recover 路径应调用 onMapReady", capturedMap)

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

    // ──────────────────────────────────────────────────────────────────
    // Test 6: 地图生成失败 → boot 硬失败（不再静默半成功）
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `boot - map generation failure - returns failure and does not call onSuccess`() = runTest {
        stateStore.runState.value = RunState.IDLE
        stateStore.bootPhase.value = BootPhase.UNINITIALIZED

        // 2026-08-04 修复断言：地图生成失败必须硬失败（stopGameLoop + onError），
        // 原实现静默推进到 BOOT_COMPLETE 但 onMapReady 未调用 → 永久 LoadingScreen
        val failingFlow = mock<StateFlow<GameData>>()
        whenever(failingFlow.value).thenThrow(RuntimeException("map seed unavailable"))
        whenever(gameEngine.gameData).thenReturn(failingFlow)
        // cleanupAfterBootFailure 会检查 isGameLoopRunning 决定是否停循环
        whenever(gameEngineCore.isGameLoopRunning).thenReturn(true)

        var onSuccessCalled = false
        var onErrorCalled = false

        val result = controller.boot(
            slot = 1,
            onSuccess = { onSuccessCalled = true },
            onError = { onErrorCalled = true }
        )

        assertFalse("boot should fail when map generation fails", result.isSuccess)
        assertFalse("onSuccess should NOT be called", onSuccessCalled)
        assertTrue("onError should be called", onErrorCalled)

        // 失败后清理：停循环 + 生命周期复位
        verify(gameEngineCore).stopGameLoop()
        assertEquals("runState 应复位为 IDLE", RunState.IDLE, stateStore.runState.value)
        assertEquals("bootPhase 应复位为 UNINITIALIZED", BootPhase.UNINITIALIZED, stateStore.bootPhase.value)
    }

    // ──────────────────────────────────────────────────────────────────
    // T15（2026-08-05）：recoverWithPartialData 补完整性守卫
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `recover - guards run before map generation`() = runTest {
        stateStore.runState.value = RunState.IDLE
        stateStore.bootPhase.value = BootPhase.UNINITIALIZED
        gameDataFlow.value = GameData(sectName = "青云宗")
        disciplesFlow.value = listOf(Disciple())

        val result = controller.boot(
            slot = 1,
            onPreloadResources = { throw RuntimeException("preload failure") },
            onSuccess = {},
            onError = {}
        )
        assertTrue("recover 应成功", result.isSuccess)
    }

    @Test
    fun `recover - guard failure aborts recovery and returns failure`() = runTest {
        stateStore.runState.value = RunState.IDLE
        stateStore.bootPhase.value = BootPhase.UNINITIALIZED
        gameDataFlow.value = GameData(sectName = "青云宗")
        disciplesFlow.value = listOf(Disciple())

        // 恢复路径的完整性守卫（recoverWithPartialData 内的 ensureHeavyDataLoaded）
        // 抛异常 → 放弃恢复走 onError（原注入点 discipleSnapshotCache.prewarm
        // 已随死代码清理删除，恢复守卫块内重型数据加载为等价注入点）
        // 失败清理会检查循环状态
        whenever(gameEngineCore.isGameLoopRunning).thenReturn(true)
        whenever(gameEngine.ensureHeavyDataLoaded()).thenThrow(RuntimeException("guard failure"))

        var onErrorCalled = false
        val result = controller.boot(
            slot = 1,
            onPreloadResources = {},
            onSuccess = {},
            onError = { onErrorCalled = true }
        )

        assertFalse("守卫失败应放弃恢复", result.isSuccess)
        assertTrue("onError 应被调用", onErrorCalled)
        assertEquals("runState 保持 IDLE", RunState.IDLE, stateStore.runState.value)
        assertEquals("bootPhase 保持 UNINITIALIZED", BootPhase.UNINITIALIZED, stateStore.bootPhase.value)
    }

    @Test
    fun `recover - empty sectName still returns false without guards`() = runTest {
        stateStore.runState.value = RunState.IDLE
        stateStore.bootPhase.value = BootPhase.UNINITIALIZED
        gameDataFlow.value = GameData(sectName = "")
        disciplesFlow.value = listOf(Disciple())

        // 判据失败：不触发守卫（回归守卫——恢复前置条件未满足时不可启动守卫流程）
        val result = controller.boot(
            slot = 1,
            onPreloadResources = { throw RuntimeException("preload failure") },
            onSuccess = {},
            onError = {}
        )
        assertFalse("空 sectName 不应恢复", result.isSuccess)
        verify(gameEngine, never()).ensureHeavyDataLoaded()
    }
}

// ====================================================================
// FakeGameStateStore — 跟踪 bootPhase/runState 变化
// ====================================================================

/**
 * 用于 [BootSequenceControllerTest] 的 [GameStateStore] Fake 实现。
 *
 * 与无操作存根不同，本 Fake 会实际修改 [bootPhase] 和 [runState] 的
 * StateFlow 值，以便 [BootSequenceController]
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

    override fun advanceBootPhase() {
        val next = BootPhase.entries[bootPhase.value.ordinal + 1]
        bootPhase.value = next
        bootPhaseHistory.add(next)
    }

    override fun resetBootPhase() {
        bootPhase.value = BootPhase.UNINITIALIZED
        bootPhaseHistory.add(BootPhase.UNINITIALIZED)
        resetCalled = true
    }

    override fun setPlaying() {
        runState.value = RunState.PLAYING
        runStateHistory.add(RunState.PLAYING)
    }

    override fun setReloading() {
        runState.value = RunState.RELOADING
        runStateHistory.add(RunState.RELOADING)
        reloadingCalled = true
    }

    override fun setLoading() {
        runState.value = RunState.LOADING
        runStateHistory.add(RunState.LOADING)
    }

    override fun setIdle() {
        runState.value = RunState.IDLE
        runStateHistory.add(RunState.IDLE)
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
    override val battleLogs = MutableStateFlow<List<BattleLog>>(emptyList())
    override val isPaused = MutableStateFlow(false)
    override val isLoading = MutableStateFlow(false)
    override val isSaving = MutableStateFlow(false)
    override val pendingBattleResult = MutableStateFlow<BattleResultUIData?>(null)
    override val pendingNotification = MutableStateFlow<GameNotification?>(null)
    override val rewardCardQueue = MutableStateFlow<List<RewardCardItem>>(emptyList())
    override val pendingBeastAttacks = MutableStateFlow<List<PendingBeastAttack>>(emptyList())
    override val pendingMarriageProposals = MutableStateFlow<List<PendingMarriageProposal>>(emptyList())
    override val pendingBattleRewardCards = MutableStateFlow<List<RewardCardItem>>(emptyList())
    override val sectCombatPower = MutableStateFlow(0L)
    override val aiSectCombatPowers = MutableStateFlow<Map<String, Long>>(emptyMap())
    override val discipleAggregates = MutableStateFlow<List<DiscipleAggregate>>(emptyList())

    // ── 三层 StateFlow ──
    override val highFreqState = MutableStateFlow(GameStateStore.HighFreqState())
    override val entityState = MutableStateFlow(GameStateStore.EntityState())
    override val configState = MutableStateFlow(GameStateStore.ConfigState())

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
    override val battleLogsSnapshot: List<BattleLog> get() = battleLogs.value

    // ── 兼容层 API ──

    // ── 事件 ──
    override val warehouseFullEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)

    // ── UI 交互状态 ──
    override var activeTab: String = ""
    override var activeDialog: String? = ""
    override var activeSubDialogs: Set<String> = emptySet()

    // ── 通知 / 战斗结果 ──
    override fun getCurrentSeeds(): List<Seed> = seeds.value
    override fun getCurrentHerbs(): List<Herb> = herbs.value
    override fun getCurrentMaterials(): List<Material> = materials.value
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
    override fun clearPendingMarriageProposals() { pendingMarriageProposals.value = emptyList() }
    override fun setPendingBattleRewardCards(c: List<RewardCardItem>) { pendingBattleRewardCards.value = c }
    override fun clearPendingBattleRewardCards() { pendingBattleRewardCards.value = emptyList() }
    override fun enqueueRewardCards(items: List<RewardCardItem>) {}
    override fun clearRewardCardQueue(count: Int) {}

    // ── 直接设置 ──
    override fun setPausedDirect(paused: Boolean) { isPaused.value = paused }
    override fun setLoadingDirect(loading: Boolean) { isLoading.value = loading }
    override fun setSavingDirect(saving: Boolean) { isSaving.value = saving }

    // ── 事务 API ──
    override suspend fun loadFromSnapshot(
        gameData: GameData, disciples: List<Disciple>,
        equipmentStacks: List<EquipmentStack>, equipmentInstances: List<EquipmentInstance>,
        manualStacks: List<ManualStack>, manualInstances: List<ManualInstance>,
        pills: List<Pill>, materials: List<Material>, herbs: List<Herb>, seeds: List<Seed>,
        storageBags: List<StorageBag>,
        battleLogs: List<BattleLog>, isPaused: Boolean, isLoading: Boolean, isSaving: Boolean
    ) {
        this._gameData.value = gameData
        this.disciples.value = disciples
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
                battleLogs.value = m.battleLogs
        isPaused.value = m.isPaused
        isLoading.value = m.isLoading
        isSaving.value = m.isSaving
    }

    override fun <R> updateAndReturn(block: MutableGameState.() -> R): R {
        val m = newMutable()
        val result = block(m)
        _gameData.value = m.gameData
                battleLogs.value = m.battleLogs
        isPaused.value = m.isPaused
        isLoading.value = m.isLoading
        isSaving.value = m.isSaving
        return result
    }

    override fun modifyState(block: MutableGameState.() -> Unit) { update(block) }
    override fun enterBatchEmissionMode() {}
    override fun exitBatchEmissionMode() {}
    override fun takeAtomicSnapshot(): GameStateStore.GameSnapshot = GameStateStore.GameSnapshot()

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
                battleLogs = battleLogs.value,
        isPaused = isPaused.value,
        isLoading = isLoading.value,
        isSaving = isSaving.value
    )
}
