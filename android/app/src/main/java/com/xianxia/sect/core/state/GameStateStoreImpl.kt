package com.xianxia.sect.core.state

import com.xianxia.sect.core.engine.SectCombatPowerCalculator
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.data.GameStateRepository
import com.xianxia.sect.di.ApplicationScopeProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(FlowPreview::class)
@Singleton
class GameStateStoreImpl @Inject constructor(
    private val applicationScopeProvider: ApplicationScopeProvider,
    private val repository: GameStateRepository
) : GameStateStore {

    @Volatile
    override var activeTab: String = "OVERVIEW"

    @Volatile
    override var activeDialog: String? = null

    @Volatile
    override var activeSubDialogs: Set<String> = emptySet()

    // ── Dirty 标志 —— 供 SaveLoadViewModel 检查是否有未保存变更 ──
    @Volatile private var _stateDirty = false
    @Volatile private var _discipleDirty = false

    /** 强制标记状态脏（供外部手动触发保存） */
    fun markDirty() { _stateDirty = true }

    /**
     * 检查并消费 dirty 标志。
     * @return true 表示自上次调用以来有状态变更
     */
    fun consumeDirty(): Boolean {
        val dirty = _stateDirty || _discipleDirty
        _stateDirty = false
        _discipleDirty = false
        return dirty
    }

    companion object {
        private const val TAG = "GameStateStore"
        private const val UPDATE_WARN_THRESHOLD_MS = 500L
    }

    private var _discipleTables = DiscipleTables().also {
        // Release 构建关闭一致性校验（仅在 Debug 开发中开启）
        DiscipleTables.consistencyCheckEnabled = false
    }
    override val discipleTables: DiscipleTables get() = _discipleTables

    private val transactionLock = ReentrantLock()

    /**
     * 显式重入计数，替代原 thread identity 检测。
     *
     * - reentrantCount > 0 表示当前线程已持有锁，嵌套 update() 直接操作 buffer 后返回
     * - reentrantBuffer 保存最外层 update() 的 buffer 引用，供嵌套调用读取
     *
     * 原实现用 [transactionOwnerThread] AtomicReference<Thread?> 检测重入，
     * 但该方案依赖"所有引擎代码在同一线程运行"的约定——任何调度器切换
     *（如 withContext(IO)）会导致 identity 检查失败、同线程死锁。
     * 显式计数方案不依赖线程身份，调度器切换后 count 仍正确。
     */
    private val reentrantCount = AtomicInteger(0)
    private val reentrantBuffer = AtomicReference<MutableGameState?>(null)

    /** 上次 assembleAll 时的 mutationVersion，用于跳过无变化的重新装配 */
    private var lastAssembledMutationVersion: Long = 0

    // 增量发射：每个字段独立的 MutableStateFlow，只在引用变化时发射
    internal val _gameDataFlow = MutableStateFlow(GameData())
    internal val _disciplesFlow = MutableStateFlow<List<Disciple>>(emptyList())
    internal val _equipmentStacksFlow = MutableStateFlow<List<EquipmentStack>>(emptyList())
    internal val _equipmentInstancesFlow = MutableStateFlow<List<EquipmentInstance>>(emptyList())
    internal val _manualStacksFlow = MutableStateFlow<List<ManualStack>>(emptyList())
    internal val _manualInstancesFlow = MutableStateFlow<List<ManualInstance>>(emptyList())
    internal val _pillsFlow = MutableStateFlow<List<Pill>>(emptyList())
    internal val _materialsFlow = MutableStateFlow<List<Material>>(emptyList())
    internal val _herbsFlow = MutableStateFlow<List<Herb>>(emptyList())
    internal val _seedsFlow = MutableStateFlow<List<Seed>>(emptyList())
    internal val _storageBagsFlow = MutableStateFlow<List<StorageBag>>(emptyList())
    internal val _battleLogsFlow = MutableStateFlow<List<BattleLog>>(emptyList())
    internal val _teamsFlow = MutableStateFlow<List<ExplorationTeam>>(emptyList())
    internal val _pendingBattleResultFlow = MutableStateFlow<BattleResultUIData?>(null)
    internal val _pendingNotificationFlow = MutableStateFlow<GameNotification?>(null)
    /** 通知队列（替代单值 _pendingNotificationFlow） */
    internal val _notificationsFlow = MutableStateFlow<List<GameNotification>>(emptyList())
    private val notificationQueue = java.util.concurrent.ConcurrentLinkedQueue<GameNotification>()
    internal val _pendingBattleRewardCardsFlow = MutableStateFlow<List<RewardCardItem>>(emptyList())
    internal val _rewardCardQueueFlow = MutableStateFlow<List<RewardCardItem>>(emptyList())
    internal val _pendingBeastAttacksFlow = MutableStateFlow<List<PendingBeastAttack>>(emptyList())

    private val _isPaused = MutableStateFlow(true)
    private val _isLoading = MutableStateFlow(false)
    private val _isSaving = MutableStateFlow(false)

    // ── 游戏生命周期（纯运行时，不随存档保存）──
    // 新 API：LifecycleState 原子化 + BootPhase + RunState 派生
    private val _lifecycleState = MutableStateFlow(GameStateStore.LifecycleState())
    // 派生 StateFlow（保持旧引用者不中断）
    private val _bootPhase = MutableStateFlow(BootPhase.UNINITIALIZED)
    private val _runState = MutableStateFlow(RunState.IDLE)
    // 旧 API 兼容：由 _lifecycleState 同步更新
    private val _gameLifecycle = MutableStateFlow(GameLifecycle.UNINITIALIZED)

    /** 根据当前 BootPhase + RunState 计算对应的 GameLifecycle 兼容值 */
    private fun computeGameLifecycle(boot: BootPhase, run: RunState): GameLifecycle = when {
        run == RunState.PLAYING && boot >= BootPhase.BOOT_COMPLETE -> GameLifecycle.PLAYING
        boot >= BootPhase.MAP_READY -> GameLifecycle.MAP_READY
        boot >= BootPhase.SYSTEMS_READY -> GameLifecycle.SYSTEMS_READY
        boot >= BootPhase.DATA_READY -> GameLifecycle.DATA_READY
        else -> GameLifecycle.UNINITIALIZED
    }

    /** 原子化设置生命周期状态 — 同时更新 _lifecycleState、_bootPhase、_runState、_gameLifecycle */
    private fun setLifecycleStateAtomic(bootPhase: BootPhase, runState: RunState) {
        _lifecycleState.value = GameStateStore.LifecycleState(bootPhase = bootPhase, runState = runState)
        _bootPhase.value = bootPhase
        _runState.value = runState
        _gameLifecycle.value = computeGameLifecycle(bootPhase, runState)
    }

    // 版本计数器：每次 update() 有字段变化时递增，用于 unifiedState 批处理触发
    internal val _updateVersion = MutableStateFlow(0L)

    // ── 发射节流（R19）：批量结算时抑制个体字段发射，仅依赖 _updateVersion ──
    // ⚠ 已移除自动批量发射模式（auto-batch emission mode）——该优化在 ≥3 字段变化时
    // 抑制个体 StateFlow 发射，导致时间/仓库显示冻结而修炼（异步组装）持续更新。
    // 个体 StateFlow 已有 !!! 引用比较做变化检测，无性能问题。
    /** 预留接口，已弃用（不再自动触发） */
    override fun enterBatchEmissionMode() { /* no-op */ }
    /** 预留接口，已弃用（不再自动触发） */
    override fun exitBatchEmissionMode() { /* no-op */ }

    override val warehouseFullEvent: MutableSharedFlow<Unit> = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    // LEGACY: 按版本触发的批处理模式，50ms 内多次更新合并为一次
    // 新代码应使用 highFreqState / entityState / configState 或独立 StateFlow
    override val unifiedState: StateFlow<UnifiedGameState> = _updateVersion
        .sample(50)
        .map { buildUnifiedState() }
        .stateIn(applicationScopeProvider.scope, SharingStarted.WhileSubscribed(5_000), UnifiedGameState())

    private fun buildUnifiedState(): UnifiedGameState {
        val gd = _gameDataFlow.value
        return UnifiedGameState(
            gameData = gd,
            disciples = _disciplesFlow.value,
            equipmentStacks = _equipmentStacksFlow.value,
            equipmentInstances = _equipmentInstancesFlow.value,
            manualStacks = _manualStacksFlow.value,
            manualInstances = _manualInstancesFlow.value,
            pills = _pillsFlow.value,
            materials = _materialsFlow.value,
            herbs = _herbsFlow.value,
            seeds = _seedsFlow.value,
            storageBags = _storageBagsFlow.value,
            teams = _teamsFlow.value,
            battleLogs = _battleLogsFlow.value,
            alliances = gd.alliances,
            isPaused = _isPaused.value,
            isLoading = _isLoading.value,
            isSaving = _isSaving.value,
            pendingBattleResult = _pendingBattleResultFlow.value,
            pendingNotification = _pendingNotificationFlow.value
        )
    }

    // 公开 StateFlow——直接来自独立 MutableStateFlow，零 .map{} 开销
    override val gameData: StateFlow<GameData> = _gameDataFlow.asStateFlow()
    override val disciples: StateFlow<List<Disciple>> = _disciplesFlow.asStateFlow()
    override val equipmentStacks: StateFlow<List<EquipmentStack>> = _equipmentStacksFlow.asStateFlow()
    override val equipmentInstances: StateFlow<List<EquipmentInstance>> = _equipmentInstancesFlow.asStateFlow()
    override val manualStacks: StateFlow<List<ManualStack>> = _manualStacksFlow.asStateFlow()
    override val manualInstances: StateFlow<List<ManualInstance>> = _manualInstancesFlow.asStateFlow()
    override val pills: StateFlow<List<Pill>> = _pillsFlow.asStateFlow()
    override val materials: StateFlow<List<Material>> = _materialsFlow.asStateFlow()
    override val herbs: StateFlow<List<Herb>> = _herbsFlow.asStateFlow()
    override val seeds: StateFlow<List<Seed>> = _seedsFlow.asStateFlow()
    override val storageBags: StateFlow<List<StorageBag>> = _storageBagsFlow.asStateFlow()
    override val battleLogs: StateFlow<List<BattleLog>> = _battleLogsFlow.asStateFlow()
    override val teams: StateFlow<List<ExplorationTeam>> = _teamsFlow.asStateFlow()

    // === GameStateSnapshotProvider 接口实现 ===
    override val gameDataSnapshot: GameData get() = _gameDataFlow.value
    override val disciplesSnapshot: List<Disciple> get() = _disciplesFlow.value
    override val equipmentStacksSnapshot: List<EquipmentStack> get() = _equipmentStacksFlow.value
    override val equipmentInstancesSnapshot: List<EquipmentInstance> get() = _equipmentInstancesFlow.value
    override val manualStacksSnapshot: List<ManualStack> get() = _manualStacksFlow.value
    override val manualInstancesSnapshot: List<ManualInstance> get() = _manualInstancesFlow.value
    override val pillsSnapshot: List<Pill> get() = _pillsFlow.value
    override val materialsSnapshot: List<Material> get() = _materialsFlow.value
    override val herbsSnapshot: List<Herb> get() = _herbsFlow.value
    override val seedsSnapshot: List<Seed> get() = _seedsFlow.value
    override val storageBagsSnapshot: List<StorageBag> get() = _storageBagsFlow.value
    override val teamsSnapshot: List<ExplorationTeam> get() = _teamsFlow.value
    override val battleLogsSnapshot: List<BattleLog> get() = _battleLogsFlow.value

    override val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()
    override val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    override val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    override val lifecycleState: StateFlow<GameStateStore.LifecycleState> = _lifecycleState.asStateFlow()
    override val gameLifecycle: StateFlow<GameLifecycle> = _gameLifecycle.asStateFlow()
    override val bootPhase: StateFlow<BootPhase> = _bootPhase.asStateFlow()
    override val runState: StateFlow<RunState> = _runState.asStateFlow()

    override fun advanceBootPhase() {
        val current = _bootPhase.value
        val nextOrdinal = current.ordinal + 1
        check(nextOrdinal <= BootPhase.entries.lastIndex) {
            "Already at terminal boot phase: $current"
        }
        setLifecycleStateAtomic(BootPhase.entries[nextOrdinal], _runState.value)
    }

    override fun resetBootPhase() {
        setLifecycleStateAtomic(BootPhase.UNINITIALIZED, _runState.value)
    }

    override fun setPlaying() {
        check(_bootPhase.value >= BootPhase.BOOT_COMPLETE) {
            "Cannot setPlaying() when bootPhase=${_bootPhase.value} is not BOOT_COMPLETE"
        }
        setLifecycleStateAtomic(_bootPhase.value, RunState.PLAYING)
    }

    override fun setLoading() {
        setLifecycleStateAtomic(_bootPhase.value, RunState.LOADING)
    }

    override fun setReloading() {
        setLifecycleStateAtomic(_bootPhase.value, RunState.RELOADING)
    }

    override fun setIdle() {
        setLifecycleStateAtomic(_bootPhase.value, RunState.IDLE)
    }

    override val pendingBattleResult: StateFlow<BattleResultUIData?> = _pendingBattleResultFlow.asStateFlow()
    override val pendingNotification: StateFlow<GameNotification?> = _pendingNotificationFlow.asStateFlow()
    override val notifications: StateFlow<List<GameNotification>> = _notificationsFlow.asStateFlow()
    override val pendingBattleRewardCards: StateFlow<List<RewardCardItem>> = _pendingBattleRewardCardsFlow.asStateFlow()
    override val rewardCardQueue: StateFlow<List<RewardCardItem>> = _rewardCardQueueFlow.asStateFlow()
    override val pendingBeastAttacks: StateFlow<List<PendingBeastAttack>> = _pendingBeastAttacksFlow.asStateFlow()

    // === 三层 StateFlow 架构 ===
    // HighFreq: 高频变化字段，sample 降频
    // 使用分组 combine 避免 5+ 参数限制
    private val highFreqStones = combine(
        _gameDataFlow.map { it.spiritStones }.distinctUntilChanged(),
        _gameDataFlow.map { it.midGradeSpiritStones }.distinctUntilChanged(),
        _gameDataFlow.map { it.highGradeSpiritStones }.distinctUntilChanged()
    ) { low, mid, high -> Triple(low, mid, high) }

    private val highFreqTime = combine(
        _gameDataFlow.map { it.gameYear }.distinctUntilChanged(),
        _gameDataFlow.map { it.gameMonth }.distinctUntilChanged(),
        _gameDataFlow.map { it.gamePhase }.distinctUntilChanged()
    ) { year, month, phase -> Triple(year, month, phase) }

    override val highFreqState: StateFlow<GameStateStore.HighFreqState> = combine(
        highFreqStones, highFreqTime, _isPaused
    ) { stones, time, paused ->
        GameStateStore.HighFreqState(
            lowGradeSpiritStones = stones.first,
            midGradeSpiritStones = stones.second,
            highGradeSpiritStones = stones.third,
            gameYear = time.first,
            gameMonth = time.second,
            gamePhase = time.third,
            isPaused = paused
        )
    }.stateIn(applicationScopeProvider.scope, SharingStarted.WhileSubscribed(5_000), GameStateStore.HighFreqState())

    // EntityFlow: 实体数据，distinctUntilChanged
    override val entityState: StateFlow<GameStateStore.EntityState> = combine(
        _disciplesFlow,
        _equipmentStacksFlow,
        _equipmentInstancesFlow,
        _manualStacksFlow,
        _manualInstancesFlow,
        _pillsFlow,
        _materialsFlow,
        _herbsFlow,
        _seedsFlow,
        _storageBagsFlow,
        _teamsFlow,
        _battleLogsFlow
    ) { args ->
        GameStateStore.EntityState(
            disciples = args[0] as List<Disciple>,
            equipmentStacks = args[1] as List<EquipmentStack>,
            equipmentInstances = args[2] as List<EquipmentInstance>,
            manualStacks = args[3] as List<ManualStack>,
            manualInstances = args[4] as List<ManualInstance>,
            pills = args[5] as List<Pill>,
            materials = args[6] as List<Material>,
            herbs = args[7] as List<Herb>,
            seeds = args[8] as List<Seed>,
            storageBags = args[9] as List<StorageBag>,
            teams = args[10] as List<ExplorationTeam>,
            battleLogs = args[11] as List<BattleLog>
        )
    }.stateIn(applicationScopeProvider.scope, SharingStarted.WhileSubscribed(5_000), GameStateStore.EntityState())

    // ConfigFlow: 配置数据，从 gameData 派生，distinctUntilChanged
    override val configState: StateFlow<GameStateStore.ConfigState> = _gameDataFlow
        .map { gd ->
            GameStateStore.ConfigState(
                sectPolicies = gd.sectPolicies,
                yearlySalary = gd.yearlySalary,
                yearlySalaryEnabled = gd.yearlySalaryEnabled,
                elderSlots = gd.elderSlots,
                placedBuildings = gd.placedBuildings,
                autoRecruitSpiritRootFilter = gd.autoRecruitSpiritRootFilter,
                autoSaveIntervalMonths = gd.autoSaveIntervalMonths
            )
        }
        .distinctUntilChanged()
        .stateIn(applicationScopeProvider.scope, SharingStarted.WhileSubscribed(5_000), GameStateStore.ConfigState())

    override val discipleAggregates: StateFlow<List<DiscipleAggregate>> = _disciplesFlow
        .sample(200)
        .map { disciples ->
            disciples.map { it.toAggregate() }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(applicationScopeProvider.scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    override val discipleAggregatesSnapshot: List<DiscipleAggregate>
        get() = _disciplesFlow.value.map { it.toAggregate() }

    private data class CachedPower(
        val fingerprint: Int,
        val power: Long
    )

    private val disciplePowerCache = ConcurrentHashMap<String, CachedPower>()
    private val aiDisciplePowerCache = ConcurrentHashMap<String, CachedPower>()

    // 中间流：直接从独立 MutableStateFlow 派生
    // 这些独立流只在对应字段实际变化时才发射，所以 combine 的频率大幅降低
    private val disciplesFlow = _disciplesFlow
        .distinctUntilChanged { old, new -> old === new }

    private val bloodRefinementPctFlow = _gameDataFlow
        .map { it.bloodRefinementPctTotals }
        .distinctUntilChanged { old, new -> old === new }

    private val equipmentInstancesFlow = _equipmentInstancesFlow
        .distinctUntilChanged { old, new -> old === new }

    private val manualInstancesFlow = _manualInstancesFlow
        .distinctUntilChanged { old, new -> old === new }

    override val sectCombatPower: StateFlow<Long> = combine(
        disciplesFlow,
        bloodRefinementPctFlow
    ) { disciples, bloodRefinementPctTotals ->
        val aliveDisciples = disciples.filter { it.isAlive }
        val aliveIds = aliveDisciples.map { it.id }.toSet()

        disciplePowerCache.keys.retainAll(aliveIds)

        var total = 0L
        for (disciple in aliveDisciples) {
            val aggregate = disciple.toAggregate()
            val brPct = bloodRefinementPctTotals[disciple.id]
            val fp = SectCombatPowerCalculator.computeFingerprint(aggregate, brPct)
            val cached = disciplePowerCache[disciple.id]
            if (cached != null && cached.fingerprint == fp) {
                total += cached.power
            } else {
                val power = SectCombatPowerCalculator.calculateDisciplePower(aggregate, brPct)
                disciplePowerCache[disciple.id] = CachedPower(fp, power)
                total += power
            }
        }
        total
    }.sample(300)
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(applicationScopeProvider.scope, SharingStarted.WhileSubscribed(5_000), 0L)

    private val aiSectDisciplesFlow = _gameDataFlow
        .map { it.aiSectDisciples }
        .distinctUntilChanged { old, new -> old === new }

    override val aiSectCombatPowers: StateFlow<Map<String, Long>> = combine(
        aiSectDisciplesFlow,
        bloodRefinementPctFlow
    ) { aiDisciplesMap, bloodRefinementPctTotals ->
        val currentDiscipleIds = aiDisciplesMap.values.flatten().map { it.id }.toSet()
        aiDisciplePowerCache.keys.retainAll(currentDiscipleIds)

        val result = mutableMapOf<String, Long>()
        for ((sectId, disciples) in aiDisciplesMap) {
            val aliveDisciples = disciples.filter { it.isAlive }
            if (aliveDisciples.isEmpty()) {
                result[sectId] = 0L
                continue
            }

            var total = 0L
            for (disciple in aliveDisciples) {
                val aggregate = disciple.toAggregate()
                val brPct = bloodRefinementPctTotals[disciple.id]
                val fp = SectCombatPowerCalculator.computeFingerprint(aggregate, brPct)
                val cached = aiDisciplePowerCache[disciple.id]
                if (cached != null && cached.fingerprint == fp) {
                    total += cached.power
                } else {
                    val power = SectCombatPowerCalculator.calculateDisciplePower(aggregate, brPct)
                    aiDisciplePowerCache[disciple.id] = CachedPower(fp, power)
                    total += power
                }
            }
            result[sectId] = total
        }
        result.toMap()
    }.distinctUntilChanged { old, new -> old === new || old == new }
        .stateIn(applicationScopeProvider.scope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    override fun modifyState(block: MutableGameState.() -> Unit) {
        if (reentrantCount.get() > 0) {
            reentrantBuffer.get()?.block()
        } else {
            update { block() }
        }
    }

    private val reusableMutableState = MutableGameState(
        gameData = GameData(),
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
        battleLogs = emptyList(),
        teams = emptyList(),
        isPaused = true,
        isLoading = false,
        isSaving = false,
        pendingNotification = null
    )

    override fun setPausedDirect(paused: Boolean) {
        _isPaused.value = paused
        _updateVersion.value++
        _stateDirty = true
    }

    override fun setLoadingDirect(loading: Boolean) {
        _isLoading.value = loading
        _updateVersion.value++
        _stateDirty = true
    }

    override fun setSavingDirect(saving: Boolean) {
        _isSaving.value = saving
        _updateVersion.value++
        _stateDirty = true
    }

    // === 快照读取（绕过 stateIn 调度延迟） ===
    override fun getCurrentSeeds(): List<Seed> = _seedsFlow.value
    override fun getCurrentHerbs(): List<Herb> = _herbsFlow.value
    override fun getCurrentMaterials(): List<Material> = _materialsFlow.value

    // === 通知 API ===
    override fun setPendingNotification(notification: GameNotification) {
        _pendingNotificationFlow.value = notification
        _updateVersion.value++
        _stateDirty = true
    }

    override fun clearPendingNotification() {
        _pendingNotificationFlow.value = null
        _updateVersion.value++
        _stateDirty = true
    }

    /** 通知队列（v3+）— 替代单值 [setPendingNotification] */
    override fun enqueueNotification(notification: GameNotification) {
        notificationQueue.offer(notification)
        if (notificationQueue.size > 200) notificationQueue.poll() // 上限 200，丢弃最旧
        _notificationsFlow.value = notificationQueue.toList()
    }

    override fun consumeNotification(): GameNotification? {
        val item = notificationQueue.poll()
        if (item != null) _notificationsFlow.value = notificationQueue.toList()
        return item
    }

    override fun setPendingBattleResult(result: BattleResultUIData) {
        _pendingBattleResultFlow.value = result
        _updateVersion.value++
        _stateDirty = true
    }

    override fun clearPendingBattleResult() {
        _pendingBattleResultFlow.value = null
        _updateVersion.value++
        _stateDirty = true
    }

    override fun setPendingBeastAttacks(attacks: List<PendingBeastAttack>) {
        _pendingBeastAttacksFlow.value = attacks
        // 注意：此方法仅在 stateStore.update{} 事务内部调用，_stateDirty/_updateVersion 由外层事务统一管理
    }

    override fun clearPendingBeastAttacks() {
        _pendingBeastAttacksFlow.value = emptyList()
        _updateVersion.value++
        _stateDirty = true
    }

    override fun setPendingBattleRewardCards(cards: List<RewardCardItem>) {
        _pendingBattleRewardCardsFlow.value = cards
    }

    override fun clearPendingBattleRewardCards() {
        _pendingBattleRewardCardsFlow.value = emptyList()
    }

    override fun enqueueRewardCards(items: List<RewardCardItem>) {
        _rewardCardQueueFlow.value = _rewardCardQueueFlow.value + items
    }

    override fun clearRewardCardQueue(count: Int) {
        _rewardCardQueueFlow.value = _rewardCardQueueFlow.value.drop(count)
    }


    override fun update(block: MutableGameState.() -> Unit) {
        var disciplesNeedReassemble = false

        transactionLock.withLock {
            val lockStartNs = System.nanoTime()
            // ★ 显式重入检测（在锁内，跨线程安全）
            if (reentrantCount.get() > 0) {
                val buffer = reentrantBuffer.get() ?: return@withLock
                buffer.block()
                // 重入路径：buffer 即最外层 update 的 reusableMutableState，
                // 各字段变化由外层 update 在提交阶段统一检测并写回 StateFlow，无需在此重复处理。
                return@withLock
            }
            try {
                reentrantCount.set(1)
                reentrantBuffer.set(reusableMutableState)
                val curGame = _gameDataFlow.value
                val curES = _equipmentStacksFlow.value
                val curEI = _equipmentInstancesFlow.value
                val curMS = _manualStacksFlow.value
                val curMI = _manualInstancesFlow.value
                val curP = _pillsFlow.value
                val curMat = _materialsFlow.value
                val curH = _herbsFlow.value
                val curS = _seedsFlow.value
                val curSB = _storageBagsFlow.value
                val curBL = _battleLogsFlow.value
                val curT = _teamsFlow.value
                val curPaused = _isPaused.value
                val curLoading = _isLoading.value
                val curSaving = _isSaving.value
                val curNotif = _pendingNotificationFlow.value
                reusableMutableState.apply {
                    gameData = curGame
                    // 增量 deepCopy：只复制自上次 update 以来被写过的列
                    val dirtyCols = _discipleTables.dirtyTracker.consumeDirtyColumns()
                    discipleTables = _discipleTables.deepCopy(dirtyCols).apply { writeAllowed = true }
                    equipmentStacks = EntityStore(curES)
                    equipmentInstances = EntityStore(curEI)
                    manualStacks = EntityStore(curMS)
                    manualInstances = EntityStore(curMI)
                    pills = EntityStore(curP)
                    materials = EntityStore(curMat)
                    herbs = EntityStore(curH)
                    seeds = EntityStore(curS)
                    storageBags = EntityStore(curSB)
                    battleLogs = curBL
                    teams = curT
                    isPaused = curPaused
                    isLoading = curLoading
                    isSaving = curSaving
                    pendingNotification = curNotif
                }
                val notificationBeforeBlock = reusableMutableState.pendingNotification
                reusableMutableState.block()
                // ★ 冻结 EntityStore 快照，确保 items 引用正确反映变化
                reusableMutableState.equipmentStacks.freeze()
                reusableMutableState.equipmentInstances.freeze()
                reusableMutableState.manualStacks.freeze()
                reusableMutableState.manualInstances.freeze()
                reusableMutableState.pills.freeze()
                reusableMutableState.materials.freeze()
                reusableMutableState.herbs.freeze()
                reusableMutableState.seeds.freeze()
                reusableMutableState.storageBags.freeze()
                val blockChangedNotification = reusableMutableState.pendingNotification !== notificationBeforeBlock
                val finalPaused = if (_isPaused.value != curPaused) _isPaused.value else reusableMutableState.isPaused
                val finalLoading = if (_isLoading.value != curLoading) _isLoading.value else reusableMutableState.isLoading
                val finalSaving = if (_isSaving.value != curSaving) _isSaving.value else reusableMutableState.isSaving
                _isPaused.value = finalPaused
                _isLoading.value = finalLoading
                _isSaving.value = finalSaving
                // 个体 StateFlow 发射（始终执行，但有 !!! 引用比较防止无意义发射）
                // ★ 已移除自动批量发射模式：该模式在 ≥3 字段变化时抑制个体发射，
                // 导致时间和仓库显示冻结，而修炼流（锁外异步组装）继续更新。
                if (reusableMutableState.gameData !== curGame) _gameDataFlow.value = reusableMutableState.gameData
                if (reusableMutableState.equipmentStacks.items !== curES) _equipmentStacksFlow.value = reusableMutableState.equipmentStacks.items
                if (reusableMutableState.equipmentInstances.items !== curEI) _equipmentInstancesFlow.value = reusableMutableState.equipmentInstances.items
                if (reusableMutableState.manualStacks.items !== curMS) _manualStacksFlow.value = reusableMutableState.manualStacks.items
                if (reusableMutableState.manualInstances.items !== curMI) _manualInstancesFlow.value = reusableMutableState.manualInstances.items
                if (reusableMutableState.pills.items !== curP) _pillsFlow.value = reusableMutableState.pills.items
                if (reusableMutableState.materials.items !== curMat) _materialsFlow.value = reusableMutableState.materials.items
                if (reusableMutableState.herbs.items !== curH) _herbsFlow.value = reusableMutableState.herbs.items
                if (reusableMutableState.seeds.items !== curS) _seedsFlow.value = reusableMutableState.seeds.items
                if (reusableMutableState.storageBags.items !== curSB) _storageBagsFlow.value = reusableMutableState.storageBags.items
                if (reusableMutableState.teams !== curT) _teamsFlow.value = reusableMutableState.teams
                if (reusableMutableState.battleLogs !== curBL)
                    _battleLogsFlow.value = reusableMutableState.battleLogs
                if (blockChangedNotification)
                    _pendingNotificationFlow.value = reusableMutableState.pendingNotification
                val disciplesChanged = reusableMutableState.discipleTables !== _discipleTables
                val mutated = reusableMutableState.discipleTables.mutationVersion
                disciplesNeedReassemble = disciplesChanged || mutated != lastAssembledMutationVersion
                if (disciplesNeedReassemble) {
                    // 锁内仅标记 mutationVersion，实际 assembleAll() 在锁外执行
                    // 减少 transactionMutex 持有时间，降低游戏循环锁争用
                    lastAssembledMutationVersion = mutated
                    _discipleDirty = true
                }
                repository.markDirty(
                    gameData = reusableMutableState.gameData !== curGame,
                    disciples = disciplesNeedReassemble,
                    equipmentStacks = reusableMutableState.equipmentStacks.items !== curES,
                    equipmentInstances = reusableMutableState.equipmentInstances.items !== curEI,
                    manualStacks = reusableMutableState.manualStacks.items !== curMS,
                    manualInstances = reusableMutableState.manualInstances.items !== curMI,
                    pills = reusableMutableState.pills.items !== curP,
                    materials = reusableMutableState.materials.items !== curMat,
                    herbs = reusableMutableState.herbs.items !== curH,
                    seeds = reusableMutableState.seeds.items !== curS,
                    storageBags = reusableMutableState.storageBags.items !== curSB,
                    teams = reusableMutableState.teams !== curT,
                    battleLogs = reusableMutableState.battleLogs !== curBL
                )
                // 仅在有字段变化时递增版本号，触发 unifiedState 批处理重建
                val anyFieldChanged = reusableMutableState.gameData !== curGame
                    || disciplesNeedReassemble
                    || reusableMutableState.equipmentStacks.items !== curES
                    || reusableMutableState.equipmentInstances.items !== curEI
                    || reusableMutableState.manualStacks.items !== curMS
                    || reusableMutableState.manualInstances.items !== curMI
                    || reusableMutableState.pills.items !== curP
                    || reusableMutableState.materials.items !== curMat
                    || reusableMutableState.herbs.items !== curH
                    || reusableMutableState.seeds.items !== curS
                    || reusableMutableState.storageBags.items !== curSB
                    || reusableMutableState.teams !== curT
                    || reusableMutableState.battleLogs !== curBL
                    || finalPaused != curPaused
                    || finalLoading != curLoading
                    || finalSaving != curSaving
                    || blockChangedNotification
                if (anyFieldChanged) {
                    _updateVersion.value++
                    _stateDirty = true
                }
                _discipleTables = reusableMutableState.discipleTables
                _discipleTables.writeAllowed = false  // ★ 出厂后锁定，防止绕过 update{} 直接写

                // ANR 诊断：记录锁内耗时超过阈值的 update 调用
                val lockElapsedMs = (System.nanoTime() - lockStartNs) / 1_000_000
                if (lockElapsedMs > UPDATE_WARN_THRESHOLD_MS) {
                    com.xianxia.sect.core.util.DomainLog.w(
                        TAG, "update() 锁内耗时 ${lockElapsedMs}ms（阈值=${UPDATE_WARN_THRESHOLD_MS}ms）"
                    )
                }
            } finally {
                reusableMutableState.discipleTables.writeAllowed = false
                reentrantCount.set(0)
                reentrantBuffer.set(null)
            }
        }

        // 在锁外执行增量 assemble，减少 transactionMutex 持有时间。
        // 使用 changedIdTracker 追踪本次事务中修改过的弟子 ID，
        // 只重新组装有变化的弟子，与全量缓存合并。
        // 对标 Bevy ECS change tick 跳过未修改组件的表迭代。
        if (disciplesNeedReassemble) {
            val changedIds = _discipleTables.changedIdTracker.consumeChangedIds()
            if (changedIds.isNotEmpty()) {
                applicationScopeProvider.scope.launch {
                    val prevSnapshot = _disciplesFlow.value
                    _disciplesFlow.value = _discipleTables.assembleAllIncremental(prevSnapshot, changedIds)
                }
            } else {
                // 回退：changedIdTracker 可能未捕获列级写入，全量 assemble 兜底
                applicationScopeProvider.scope.launch {
                    _disciplesFlow.value = _discipleTables.assembleAll()
                }
            }
        }

    }

    override fun <R> updateAndReturn(block: MutableGameState.() -> R): R {
        @Suppress("UNCHECKED_CAST")
        var result: R? = null
        update {
            result = block()
        }
        return result as R
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
        teams: List<ExplorationTeam>,
        battleLogs: List<BattleLog>,
        isPaused: Boolean,
        isLoading: Boolean,
        isSaving: Boolean
    ) {
        transactionLock.withLock {
            // 缓存清除在所有写入之前执行
            disciplePowerCache.clear()
            aiDisciplePowerCache.clear()

            // 保存旧值用于失败回滚
            val oldGameData = _gameDataFlow.value
            val oldDisciples = _disciplesFlow.value
            val oldEquipmentStacks = _equipmentStacksFlow.value
            val oldEquipmentInstances = _equipmentInstancesFlow.value
            val oldManualStacks = _manualStacksFlow.value
            val oldManualInstances = _manualInstancesFlow.value
            val oldPills = _pillsFlow.value
            val oldMaterials = _materialsFlow.value
            val oldHerbs = _herbsFlow.value
            val oldSeeds = _seedsFlow.value
            val oldStorageBags = _storageBagsFlow.value
            val oldTeams = _teamsFlow.value
            val oldBattleLogs = _battleLogsFlow.value
            val oldIsPaused = _isPaused.value
            val oldIsLoading = _isLoading.value
            val oldIsSaving = _isSaving.value
            val oldTables = _discipleTables.deepCopy()

            try {
                _gameDataFlow.value = gameData
                _disciplesFlow.value = disciples
                _discipleTables.apply { writeAllowed = true }.clear()
                disciples.forEach { _discipleTables.insert(it) }

                // 血炼旧绝对值 → 新百分比乘区 一次性迁移
                migrateBloodRefinementFromAbsoluteToPct()

                _equipmentStacksFlow.value = equipmentStacks
                _equipmentInstancesFlow.value = equipmentInstances
                _manualStacksFlow.value = manualStacks
                _manualInstancesFlow.value = manualInstances
                _pillsFlow.value = pills
                _materialsFlow.value = materials
                _herbsFlow.value = herbs
                _seedsFlow.value = seeds
                _storageBagsFlow.value = storageBags
                _teamsFlow.value = teams
                _battleLogsFlow.value = battleLogs
                _isPaused.value = isPaused
                _isLoading.value = isLoading
                _isSaving.value = isSaving
                repository.setActiveSlot(gameData.slotId)
                repository.markAllDirty()
                _updateVersion.value++
                _stateDirty = false
                _discipleDirty = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 回滚所有已写入的 Flow 值
                DomainLog.e(TAG, "loadFromSnapshot 失败，执行回滚: ${e.message}", e)
                _gameDataFlow.value = oldGameData
                _disciplesFlow.value = oldDisciples
                _discipleTables.apply { writeAllowed = true }.clear()
                oldTables.ids.forEach { id ->
                    val d = oldTables.assemble(id)
                    _discipleTables.insert(d)
                }
                _equipmentStacksFlow.value = oldEquipmentStacks
                _equipmentInstancesFlow.value = oldEquipmentInstances
                _manualStacksFlow.value = oldManualStacks
                _manualInstancesFlow.value = oldManualInstances
                _pillsFlow.value = oldPills
                _materialsFlow.value = oldMaterials
                _herbsFlow.value = oldHerbs
                _seedsFlow.value = oldSeeds
                _storageBagsFlow.value = oldStorageBags
                _teamsFlow.value = oldTeams
                _battleLogsFlow.value = oldBattleLogs
                _isPaused.value = oldIsPaused
                _isLoading.value = oldIsLoading
                _isSaving.value = oldIsSaving
                throw e
            } finally {
                _discipleTables.writeAllowed = false
            }
        }
        // ★ 锁外同步版本号，防止首个 update() 触发不必要的 assembleAll
        _disciplesFlow.value = _discipleTables.assembleAll()
        lastAssembledMutationVersion = _discipleTables.mutationVersion
    }

    override suspend fun reset() {
        transactionLock.withLock {
            disciplePowerCache.clear()
            aiDisciplePowerCache.clear()
            _gameDataFlow.value = GameData()
            _disciplesFlow.value = emptyList()
            _discipleTables.writeAllowed = true
            try { _discipleTables.clear() } finally { _discipleTables.writeAllowed = false }
            _equipmentStacksFlow.value = emptyList()
            _equipmentInstancesFlow.value = emptyList()
            _manualStacksFlow.value = emptyList()
            _manualInstancesFlow.value = emptyList()
            _pillsFlow.value = emptyList()
            _materialsFlow.value = emptyList()
            _herbsFlow.value = emptyList()
            _seedsFlow.value = emptyList()
            _storageBagsFlow.value = emptyList()
            _teamsFlow.value = emptyList()
            _battleLogsFlow.value = emptyList()
            _pendingBattleResultFlow.value = null
            _pendingNotificationFlow.value = null
            _notificationsFlow.value = emptyList()
            while (notificationQueue.poll() != null) { /* drain queue */ }
            _pendingBattleRewardCardsFlow.value = emptyList()
            _rewardCardQueueFlow.value = emptyList()
            _pendingBeastAttacksFlow.value = emptyList()
            _isPaused.value = true
            _isLoading.value = false
            _isSaving.value = false
            _updateVersion.value++
            _stateDirty = false
            _discipleDirty = false
            repository.clearDirty()
        }
    }

    override suspend fun resetForSlot(slotId: Int) {
        reset()
        repository.setActiveSlot(slotId)
    }

    /**
     * 血炼旧绝对值 → 新百分比乘区 一次性迁移。
     *
     * 将旧 [BloodRefinementBonusTotal] 的绝对值转换为 [BloodRefinementPctTotal] 的百分比。
     * 同时从 [DiscipleTables] 的 base* 列回退历史血炼绝对值（防止计算时双算）。
     *
     * 迁移条件：oldTotals 非空且 newTotals 为空。
     */
    private fun migrateBloodRefinementFromAbsoluteToPct() {
        val gd = _gameDataFlow.value
        val oldTotals = gd.bloodRefinementBonusTotals
        if (oldTotals.isEmpty()) return
        if (gd.bloodRefinementPctTotals.isNotEmpty()) return // 已迁移

        val migrated = mutableMapOf<String, BloodRefinementPctTotal>()
        for ((discipleId, old) in oldTotals) {
            val dId = discipleId.toIntOrNull() ?: continue
            val hpOrig = _discipleTables.baseHps[dId] - old.hpBonus
            val paOrig = _discipleTables.basePhysicalAttacks[dId] - old.physicalAttackBonus
            val maOrig = _discipleTables.baseMagicAttacks[dId] - old.magicAttackBonus
            val pdOrig = _discipleTables.basePhysicalDefenses[dId] - old.physicalDefenseBonus
            val mdOrig = _discipleTables.baseMagicDefenses[dId] - old.magicDefenseBonus
            val spdOrig = _discipleTables.baseSpeeds[dId] - old.speedBonus

            // 跳过数据损坏的条目：原始 base <= 0 意味着数据不一致
            if (hpOrig <= 0 || paOrig <= 0 || maOrig <= 0 ||
                pdOrig <= 0 || mdOrig <= 0 || spdOrig <= 0) continue

            migrated[discipleId] = BloodRefinementPctTotal(
                discipleId = discipleId,
                hpBonusPct = old.hpBonus.toDouble() / hpOrig,
                physicalAttackBonusPct = old.physicalAttackBonus.toDouble() / paOrig,
                magicAttackBonusPct = old.magicAttackBonus.toDouble() / maOrig,
                physicalDefenseBonusPct = old.physicalDefenseBonus.toDouble() / pdOrig,
                magicDefenseBonusPct = old.magicDefenseBonus.toDouble() / mdOrig,
                speedBonusPct = old.speedBonus.toDouble() / spdOrig
            )

            // 从 base* 列回退历史血炼绝对值（防止计算时双算）
            _discipleTables.baseHps[dId] = hpOrig
            _discipleTables.basePhysicalAttacks[dId] = paOrig
            _discipleTables.baseMagicAttacks[dId] = maOrig
            _discipleTables.basePhysicalDefenses[dId] = pdOrig
            _discipleTables.baseMagicDefenses[dId] = mdOrig
            _discipleTables.baseSpeeds[dId] = spdOrig
        }

        _gameDataFlow.value = gd.copy(
            bloodRefinementBonusTotals = emptyMap(),
            bloodRefinementPctTotals = migrated
        )
    }

    // ==================== GameData 策略表驱动合并 ====================
}

fun fixStorageBagReferences(
    equipmentStacks: List<EquipmentStack>,
    equipmentInstances: List<EquipmentInstance>,
    manualStacks: List<ManualStack>,
    manualInstances: List<ManualInstance>,
    disciples: List<Disciple>
): List<Disciple> = com.xianxia.sect.core.util.fixStorageBagReferences(
    equipmentStacks, equipmentInstances, manualStacks, manualInstances, disciples
)
