package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.RewardCardItem
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.StorageBag
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



/**
 * 测试用 [GameStateStore] 假实现（共享）——引擎测试的**唯一官方** [GameStateStore] 替身。
 *
 * 使用持久化 [DiscipleTables] 实例，确保跨 update 调用持久化。
 * 原为 GameEngineAtomicAssignTest 私有副本，抽取为共享供多测试文件复用。
 *
 * ## 为什么用 Fake 而不是 mock(GameStateStore)
 *
 * Mockito mock 对未 stub 成员默认返回 null/空——服务重构新增依赖访问路径时
 * 静默 NPE（堆栈不指向 mock 调用点，定位困难）。本 Fake 所有成员都是真实
 * 语义（StateFlow/EntityStore/COW 事务），不存在"未 stub"概念。
 *
 * ## 用法
 *
 * ```kotlin
 * val store = FakeAtomicStateStore()
 * store.setGameData(GameData(gameYear = 10))   // 可选：设置初值
 * val service = SomeService(stateStore = store, ...)
 * store.update { it.gameData = it.gameData.copy(...) }  // 直接改状态
 * // 断言：store.latestGameData / store.gameData.value / store.discipleTables
 * ```
 *
 * 禁止在测试中 mock(GameStateStore::class.java)；非 store 依赖（Repository/Service）
 * 需要 mock 时使用 [mockSmart]（RETURNS_SMART_NULLS，未 stub 调用显式失败）。
 */
internal class FakeAtomicStateStore : GameStateStore {

    // 持久化 DiscipleTables 实例（newMutable 复用同一实例，确保跨 update 持久化）
    val persistentDiscipleTables = DiscipleTables()

    // 持久化材料实例（血炼等用例需要跨 update 保留插入的材料）
    val persistentMaterials = EntityStore<Material>()

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
    override val highFreqState = MutableStateFlow(GameStateStore.HighFreqState())
    override val entityState = MutableStateFlow(GameStateStore.EntityState())
    override val configState = MutableStateFlow(GameStateStore.ConfigState())

    // ── 快照 ──
    override val discipleAggregatesSnapshot: List<DiscipleAggregate> get() = discipleAggregates.value
    override val gameDataSnapshot: GameData
        get() {
            val gate = snapshotGate
            if (gate != null && gate(Thread.currentThread())) {
                snapshotEnteredLatch.countDown()
                snapshotReleaseLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            }
            return _gameData.value
        }
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

    // ── 兼容层 ──
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
    override fun removePendingBeastAttack(beastLevelId: String) {
        pendingBeastAttacks.value =
            pendingBeastAttacks.value.filter { it.beastLevel.id != beastLevelId }
    }
    override fun clearPendingMarriageProposals() { pendingMarriageProposals.value = emptyList() }
    override fun setPendingBattleRewardCards(c: List<RewardCardItem>) { pendingBattleRewardCards.value = c }
    override fun clearPendingBattleRewardCards() { pendingBattleRewardCards.value = emptyList() }
    override fun enqueueRewardCards(items: List<RewardCardItem>) { /* Fake：战斗奖励直接入队，无需队列 */ }
    override fun clearRewardCardQueue(count: Int) { /* Fake：无队列可清 */ }
    override fun setPausedDirect(paused: Boolean) {
        setPausedDirectCalls.add(paused)
        isPaused.value = paused
    }
    override fun setLoadingDirect(loading: Boolean) {
        setLoadingDirectCalls.add(loading)
        isLoading.value = loading
    }
    override fun setSavingDirect(saving: Boolean) {
        setSavingDirectCalls.add(saving)
        isSaving.value = saving
    }
    override suspend fun loadFromSnapshot(
        gameData: GameData,
        disciples: List<Disciple>,
        equipmentStacks: List<EquipmentStack>,
        equipmentInstances: List<EquipmentInstance>,
        manualStacks: List<ManualStack>,
        manualInstances: List<ManualInstance>,
        pills: List<Pill>,
        materials: List<Material>,
        herbs: List<Herb>,
        seeds: List<Seed>,
        storageBags: List<StorageBag>,
        battleLogs: List<BattleLog>,
        isPaused: Boolean,
        isLoading: Boolean,
        isSaving: Boolean
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

    // ── 生命周期 ──
    override fun advanceBootPhase() { /* Fake：boot 阶段由测试直接赋值 */ }
    override fun resetBootPhase() { /* Fake：boot 阶段由测试直接赋值 */ }
    override fun setPlaying() { runState.value = RunState.PLAYING }
    override fun setReloading() { runState.value = RunState.RELOADING }
    override fun setLoading() { runState.value = RunState.LOADING }
    override fun setIdle() { runState.value = RunState.IDLE }

    /**
     * 测试专用：gameDataSnapshot 读取阻塞门控（GameEngineCoreLifecycleInterleavingTest
     * 模拟 emergency 锁内读取阻塞，替代 mock 时代 `when(store.gameDataSnapshot)
     * .thenAnswer { ... }`）。为 null 不门控；非 null 时 predicate 返回 true 的线程
     * 读取 gameDataSnapshot 会：上报 snapshotEnteredLatch → 阻塞在 snapshotReleaseLatch
     * （最多 5s）。releaseLatch 计数归零后后续读取立即通过——天然等价 mock 时代的
     * blockOnce（emergency 内部第二次快照读取不再阻塞）。线程身份判定由测试传入
     * （仅 emergency 线程阻塞，循环线程/主线程不阻塞）。
     */
    var snapshotGate: ((Thread) -> Boolean)? = null
    val snapshotEnteredLatch = java.util.concurrent.CountDownLatch(1)
    val snapshotReleaseLatch = java.util.concurrent.CountDownLatch(1)

    /** setPausedDirect 调用记录（生命周期测试断言"stop 不触碰暂停状态"） */
    val setPausedDirectCalls = mutableListOf<Boolean>()

    /** setSavingDirect/setLoadingDirect 调用记录（看门狗卡死复位测试断言标志复位） */
    val setSavingDirectCalls = mutableListOf<Boolean>()
    val setLoadingDirectCalls = mutableListOf<Boolean>()

    /** 最后一次 update 后的 GameData 快照 */
    var latestGameData: GameData = GameData()
        private set

    /**
     * 便捷设置初始 GameData（update 事务外直接覆盖）。
     * 替代 mock 时代的 `when(store.gameData).thenReturn(MutableStateFlow(...))` stub。
     */
    fun setGameData(data: GameData) {
        _gameData.value = data
        latestGameData = data
    }

    /** 嵌套事务深度——模拟真实 store 的 writeAllowed 生命周期（重入安全） */
    private var writeDepth = 0

    /**
     * 当前事务缓冲——重入事务（外层 update 内调 updateAndReturn，如
     * confiscate → returnEquipmentToStack）复用同一缓冲，对齐真实
     * GameStateStoreImpl 的 COW 重入语义；原实现每次新建缓冲导致
     * 内层修改被外层旧缓冲 syncFlows 覆盖丢失。
     */
    private var activeMutable: MutableGameState? = null

    override fun update(block: MutableGameState.() -> Unit) {
        val m = mutableForTransaction()
        try {
            block(m)
        } finally {
            endTransaction()
        }
        if (writeDepth == 0) syncFlows(m)
    }

    override fun <R> updateAndReturn(block: MutableGameState.() -> R): R {
        val m = mutableForTransaction()
        val result = try {
            block(m)
        } finally {
            endTransaction()
        }
        if (writeDepth == 0) syncFlows(m)
        return result
    }

    /** 获取事务缓冲：最外层新建并激活，重入复用（同真实 store 的嵌套事务语义） */
    private fun mutableForTransaction(): MutableGameState {
        if (writeDepth++ == 0) {
            val m = newMutable()
            activeMutable = m
            m.discipleTables.writeAllowed = true
            return m
        }
        return activeMutable ?: error("重入事务异常：activeMutable 为空")
    }

    private fun endTransaction() {
        if (--writeDepth == 0) {
            activeMutable?.discipleTables?.writeAllowed = false
            activeMutable = null
        }
    }

    /**
     * 将事务缓冲写回全部 StateFlow（P-20 增强：物品实体跨事务持久化——
     * 原实现只同步 gameData，InventorySystem 等物品仓库路径的修改会丢失）。
     */
    private fun syncFlows(m: MutableGameState) {
        _gameData.value = m.gameData
        latestGameData = m.gameData
        equipmentStacks.value = m.equipmentStacks.all()
        equipmentInstances.value = m.equipmentInstances.all()
        manualStacks.value = m.manualStacks.all()
        manualInstances.value = m.manualInstances.all()
        pills.value = m.pills.all()
        materials.value = m.materials.all()
        herbs.value = m.herbs.all()
        seeds.value = m.seeds.all()
        storageBags.value = m.storageBags.all()
        battleLogs.value = m.battleLogs
        isPaused.value = m.isPaused
        isLoading.value = m.isLoading
        isSaving.value = m.isSaving
    }

    override fun modifyState(block: MutableGameState.() -> Unit) { update(block) }
    override fun enterBatchEmissionMode() { /* Fake：无批处理发射 */ }
    override fun exitBatchEmissionMode() { /* Fake：无批处理发射 */ }
    override fun takeAtomicSnapshot(): GameStateStore.GameSnapshot = GameStateStore.GameSnapshot()

    /** 使用持久化 DiscipleTables 实例 + 物品实体从 flow 值初始化，确保跨 update 持久化 */
    private fun newMutable() = MutableGameState(
        gameData = _gameData.value,
        discipleTables = persistentDiscipleTables,
        equipmentStacks = EntityStore(equipmentStacks.value),
        equipmentInstances = EntityStore(equipmentInstances.value),
        manualStacks = EntityStore(manualStacks.value),
        manualInstances = EntityStore(manualInstances.value),
        pills = EntityStore(pills.value),
        materials = persistentMaterials,
        herbs = EntityStore(herbs.value),
        seeds = EntityStore(seeds.value),
        storageBags = EntityStore(storageBags.value),
        battleLogs = battleLogs.value,
        isPaused = isPaused.value,
        isLoading = isLoading.value,
        isSaving = isSaving.value
    )
}
