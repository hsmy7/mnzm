package com.xianxia.sect.core.state

import com.xianxia.sect.core.engine.SectCombatPowerCalculator
import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.BloodRefinementPctTotal
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.ExplorationTeam
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.RewardCardItem
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.StorageBag
import com.xianxia.sect.core.model.spiritStones
import android.os.Looper
import com.xianxia.sect.BuildConfig
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.data.GameStateRepository
import com.xianxia.sect.di.ApplicationScopeProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton



@OptIn(FlowPreview::class)
@Singleton
class GameStateStoreImpl @Inject constructor(
    private val applicationScopeProvider: ApplicationScopeProvider,
    private val repository: GameStateRepository,
    /**
     * RNG 事务钩子（P0-1 K 项根治）：事务失败回滚时同步回滚分区 PRNG 状态。
     * 默认 [NoopRngSnapshotPort] 供非注入测试环境使用；Hilt 注入真实实现
     * （App 层委托 GameRngManager）。
     */
    private val rngSnapshotPort: RngSnapshotPort = NoopRngSnapshotPort
) : GameStateStore {

    /**
     * P-5：弟子聚合结果流——由 assemble 写回点同步更新（与组装对齐，
     * 消除常驻 10Hz 采样管道）。无订阅时仍持有最新值（每旬一次增量归并，成本微秒级）。
     */
    private val _aggregatesFlow = MutableStateFlow<List<DiscipleAggregate>>(emptyList())

    /**
     * P-5：聚合代际版本号——与 [discipleVersion] 对齐。
     * 不匹配时 [discipleAggregatesSnapshot] 按需重算一次（load/reset 清缓存窗口兜底）。
     */
    @Volatile
    private var aggregatesGen = -1L

    /** P-5：宗门战力——与聚合同步计算（同刻可观察），血炼变化在 update 提交处重算 */
    private val _combatPowerFlow = MutableStateFlow(0L)

    /**
     * 锁外弟子组装专用单线程调度器。
     *
     * 竞态防护：assembleAllIncremental 基于执行时读取的 _disciplesFlow 快照合并
     * changedIds——若多个增量组装协程并发交错（各自读同一旧快照、后写覆盖先写），
     * 会丢失弟子（burst 更新实测丢 2/50）。单线程串行执行保证
     * 后启动的组装读到前一次写回的结果，顺序正确。
     */
    private val assembleDispatcher: CoroutineDispatcher =
        Dispatchers.Default.limitedParallelism(1)

    /**
     * 弟子数据代际版本号（2026-08-01 修复）。
     *
     * loadFromSnapshot/reset 会整体替换弟子数据——递增版本号使 assembleDispatcher
     * 上排队中的陈旧增量组装任务被作废（协程首行校验版本号），防止"load 完成写回
     * 完整列表后，迟到的不完整 assemble 结果覆盖 _disciplesFlow"（丢弟子/陈尸）。
     */
    private val discipleVersion = java.util.concurrent.atomic.AtomicLong(0)

    /** 测试模式：允许主线程调用 update（仅在 Robolectric 单元测试中使用） */
    @Volatile
    var unsafeAllowMainThreadUpdateForTest = false

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
        // Mutable 列 unmodifiable 防御：Debug/CI 开启（原地修改立即抛错），Release 零成本
        DiscipleTables.mutableValueGuardEnabled = BuildConfig.DEBUG
    }
    /** P-3：最近一次 update 事务的脏列索引（供锁外 patch 组装复用子对象引用） */
    @Volatile
    private var lastDirtyColumns: Set<Int> = emptySet()
    override val discipleTables: DiscipleTables get() = _discipleTables

    private val transactionLock = ReentrantLock()

    // ── D-01 事务世代号与观察者（溢出草稿按事务提交/回滚落盘/丢弃） ──

    /**
     * 已提交顶层事务计数——新顶层事务的世代号 = committed + 1（单调递增）。
     * 嵌套事务不分配新世代号（归外层事务）。loadFromSnapshot/reset 不经过
     * [update] 钩子，不递增。
     */
    private val committedGeneration = AtomicLong(0)

    /**
     * 进行中顶层事务的世代号；无事务 = 0。
     * 事务内入队的副作用（如溢出草稿）按此值打标，提交/回滚钩子据此
     * 决定落盘或丢弃。
     */
    @Volatile
    private var pendingGeneration = 0L

    /** 事务观察者列表（构造注册后常驻，写多读少场景） */
    private val transactionObservers = CopyOnWriteArrayList<GameStateStore.TransactionObserver>()

    override val currentTransactionGeneration: Long
        get() = pendingGeneration

    override fun registerTransactionObserver(observer: GameStateStore.TransactionObserver) {
        if (transactionObservers.contains(observer)) return
        transactionObservers.add(observer)
    }

    /** 提交钩子：锁外、事务线程上逐个回调；observer 异常不得破坏状态提交 */
    private fun fireCommitted(txGen: Long) {
        for (observer in transactionObservers) {
            try {
                observer.onTransactionCommitted(txGen)
            } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
                DomainLog.e(TAG, "TransactionObserver.onTransactionCommitted 异常", t)
            }
        }
    }

    /** 回滚钩子：锁外、事务线程上逐个回调；observer 异常不得吞掉原始异常（调用方负责） */
    private fun fireRollback(txGen: Long) {
        for (observer in transactionObservers) {
            try {
                observer.onTransactionRolledBack(txGen)
            } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
                DomainLog.e(TAG, "TransactionObserver.onTransactionRolledBack 异常", t)
            }
        }
    }

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
    private val _pendingMarriageProposalsFlow =
        MutableStateFlow<List<PendingMarriageProposal>>(emptyList())

    private val _isPaused = MutableStateFlow(true)
    private val _isLoading = MutableStateFlow(false)
    private val _isSaving = MutableStateFlow(false)

    // ── 游戏生命周期（纯运行时，不随存档保存）──
    // 新 API：LifecycleState 原子化 + BootPhase + RunState 派生
    private val _lifecycleState = MutableStateFlow(GameStateStore.LifecycleState())
    // 派生 StateFlow（保持旧引用者不中断）
    private val _bootPhase = MutableStateFlow(BootPhase.UNINITIALIZED)
    private val _runState = MutableStateFlow(RunState.IDLE)

    /** 原子化设置生命周期状态 — 同时更新 _lifecycleState、_bootPhase、_runState */
    private fun setLifecycleStateAtomic(bootPhase: BootPhase, runState: RunState) {
        _lifecycleState.value = GameStateStore.LifecycleState(bootPhase = bootPhase, runState = runState)
        _bootPhase.value = bootPhase
        _runState.value = runState
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

    override val warehouseFullEvent: MutableSharedFlow<String> = MutableSharedFlow<String>(extraBufferCapacity = 1)

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

    override fun takeAtomicSnapshot(): GameStateStore.GameSnapshot = transactionLock.withLock {
        GameStateStore.GameSnapshot(
            gameData = _gameDataFlow.value,
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
            battleLogs = _battleLogsFlow.value
        )
    }

    override val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()
    override val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    override val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    override val bootPhase: StateFlow<BootPhase> = _bootPhase.asStateFlow()
    override val lifecycleState: StateFlow<GameStateStore.LifecycleState> = _lifecycleState.asStateFlow()
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
    override val pendingMarriageProposals: StateFlow<List<PendingMarriageProposal>> =
        _pendingMarriageProposalsFlow.asStateFlow()

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
            )
        }
        .distinctUntilChanged()
        .stateIn(applicationScopeProvider.scope, SharingStarted.WhileSubscribed(5_000), GameStateStore.ConfigState())

    /**
     * 聚合缓存（P-5）：assemble 写回点同步写入。
     * 修复前 [discipleAggregatesSnapshot] 在调用线程全量 toAggregate()——
     * UI 打开弹窗触发多次主线程 O(D) 扫描（ProductionViewModel 8 处等）。
     * 现为 O(1) 缓存读取；仅 load/reset 清缓存后、写回点未覆盖的窗口
     * 按需重算一次（见 getter 代际校验）。
     */
    @Volatile
    private var cachedAggregates: List<DiscipleAggregate> = emptyList()

    override val discipleAggregatesSnapshot: List<DiscipleAggregate>
        get() {
            // P-5：写回点未覆盖窗口（loadFromSnapshot 清缓存后到 assemble 协程
            // 执行完成之间、失败回滚后）按需重算一次并同步 flow——随后写回点接管。
            if (aggregatesGen != discipleVersion.get()) {
                // S2 修复（对抗性审查）：TOCTOU——计算期间 load/reset 可能递增代际
                // 并清空缓存，本 getter 不取锁，计算完成后必须校验代际未变才写缓存，
                // 否则陈旧聚合被盖上"当前代"印章持久化（读档后 UI 显示旧档数据直到
                // 下一笔弟子事务）。恢复旧 derivedAggregation 的"计算后校验"模式。
                val gen = discipleVersion.get()
                val disciples = _disciplesFlow.value
                val fresh = disciples.map { it.toAggregate() }
                if (discipleVersion.get() != gen) {
                    // 代际已变（load/reset 发生）：丢弃本次结果，下次调用重算
                    return cachedAggregates
                }
                // P-5：战力与聚合同步（先算后写，避免中间状态窗口）
                val power = computeCombatPower(
                    fresh, _gameDataFlow.value.bloodRefinementPctTotals
                )
                cachedAggregates = fresh
                _aggregatesFlow.value = fresh
                _combatPowerFlow.value = power
                aggregatesGen = discipleVersion.get()
            }
            return cachedAggregates
        }

    private data class CachedPower(
        val fingerprint: Int,
        val power: Long
    )

    private val disciplePowerCache = ConcurrentHashMap<String, CachedPower>()
    private val aiDisciplePowerCache = ConcurrentHashMap<String, CachedPower>()

    // 中间流：直接从独立 MutableStateFlow 派生
    // 这些独立流只在对应字段实际变化时才发射，所以 combine 的频率大幅降低
    // （disciplesFlow 已随 P-5 聚合管道移除——聚合改为 assemble 写回点同步计算）
    private val bloodRefinementPctFlow = _gameDataFlow
        .map { it.bloodRefinementPctTotals }
        .distinctUntilChanged { old, new -> old === new }

    private val equipmentInstancesFlow = _equipmentInstancesFlow
        .distinctUntilChanged { old, new -> old === new }

    private val manualInstancesFlow = _manualInstancesFlow
        .distinctUntilChanged { old, new -> old === new }

    /**
     * P-5：聚合计算写回点（由 assemble 协程在写回 [disciplesFlow] 后同步调用）。
     *
     * 原 10Hz 常驻 combine/sample 管道（无 UI 订阅也持续轮询 + O(D) 深比较）移除，
     * 聚合改为"组装完成即计算"（assembleDispatcher 单线程串行，无竞争）——
     * 空闲零成本，更新零延迟（原 ≤100ms 采样延迟）。
     * 增量归并（[mergeAggregatesIncremental]）语义不变：仅新增/变更弟子重算
     * toAggregate，未变弟子复用旧 Aggregate 对象（UI 引用稳定）。
     *
     * @param disciples 组装后的完整弟子列表（id 升序）
     * @param gen 组装任务的代际版本号（调用点已校验与当前一致）
     */
    private fun updateAggregates(disciples: List<Disciple>, gen: Long) {
        val prevAggregates = cachedAggregates
        val aggregates = if (prevAggregates.size == disciples.size && prevAggregates.isNotEmpty()) {
            mergeAggregatesIncremental(prevAggregates, disciples)
        } else {
            disciples.map { it.toAggregate() }
        }
        // 先计算后写入：战力计算可能耗时（首次 JIT/冷路径），若先写 aggregates 再算
        // 战力，观察者会在两流写入之间看到"聚合新、战力旧"的中间状态窗口。
        // 两值算完再连续写入（纳秒级窗口，观察者不可能命中）。
        val power = computeCombatPower(
            aggregates, _gameDataFlow.value.bloodRefinementPctTotals
        )
        cachedAggregates = aggregates
        _aggregatesFlow.value = aggregates
        _combatPowerFlow.value = power
        aggregatesGen = gen
    }

    /**
     * 宗门战力汇总（仅存活弟子累计；指纹缓存保留——血炼百分比变化时仅重算
     * 血炼弟子，其余缓存命中）。
     *
     * @param aggregates 弟子聚合列表
     * @param bloodRefinementPctTotals 血炼百分比总计映射
     * @return 宗门总战力
     */
    private fun computeCombatPower(
        aggregates: List<DiscipleAggregate>,
        bloodRefinementPctTotals: Map<String, BloodRefinementPctTotal>
    ): Long {
        var total = 0L
        for (aggregate in aggregates) {
            if (!aggregate.isAlive) continue
            val discipleId = aggregate.id
            val brPct = bloodRefinementPctTotals[discipleId]
            val fp = SectCombatPowerCalculator.computeFingerprint(aggregate, brPct)
            val cached = disciplePowerCache[discipleId]
            if (cached != null && cached.fingerprint == fp) {
                total += cached.power
            } else {
                val power = SectCombatPowerCalculator.calculateDisciplePower(aggregate, brPct)
                disciplePowerCache[discipleId] = CachedPower(fp, power)
                total += power
            }
        }
        val aliveIds = aggregates.filter { it.isAlive }.map { it.id }.toSet()
        disciplePowerCache.keys.retainAll(aliveIds)
        return total
    }

    /**
     * 双指针增量归并（2026-08-01）：prev（升序）与 disciples（升序）diff，
     * 仅对新增/变更弟子调用 [Disciple.toAggregate]，未变弟子复用旧对象。
     *
     * 变化判定：`prev.sourceRef === disciples[j]`（引用相等）——增量组装
     * （assembleAllIncremental）保证未变弟子复用旧 Disciple 对象引用、变更弟子
     * 产出新对象。2026-08-01 修复：仅按 id 匹配复用会丢失列级变更
     * （同 id 新 Disciple 的修为/生死/属性不反映到聚合）。
     * 两列表 size 不等或 diff 失序时由调用方退化全量。
     */
    internal fun mergeAggregatesIncremental(
        prev: List<DiscipleAggregate>,
        disciples: List<Disciple>
    ): List<DiscipleAggregate> {
        // 2026-08-01 对抗性审查优化：一次性预解析两侧 id 数组 + 升序校验——
        // 旧实现在双指针循环内每次 toIntOrNull() 字符串解析（300 次×2），
        // 遍历开销与 toAggregate 同量级，增量收益被吃掉大半；
        // 解析失败/失序时退化为全量（安全兜底）
        val prevIds = parseOrderedIds(prev.size) { prev[it].id }
        val curIds = parseOrderedIds(disciples.size) { disciples[it].id }
        if (prevIds == null || curIds == null) return disciples.map { it.toAggregate() }

        val result = ArrayList<DiscipleAggregate>(disciples.size)
        var i = 0
        var j = 0
        while (i < prev.size && j < disciples.size) {
            when {
                prevIds[i] == curIds[j] -> {
                    // 引用相等 → 未变复用；否则变更 → 重建（列级写入/生死变化）
                    if (prev[i].sourceRef === disciples[j]) {
                        result.add(prev[i])
                    } else {
                        result.add(disciples[j].toAggregate())
                    }
                    i++; j++
                }
                prevIds[i] < curIds[j] -> i++  // prev 中被移除的弟子（跳过）
                else -> { result.add(disciples[j].toAggregate()); j++ }  // 新增弟子
            }
        }
        while (j < disciples.size) { result.add(disciples[j].toAggregate()); j++ }
        return result
    }

    /**
     * 预解析弟子 id 数组并校验升序（2026-08-01 增量聚合辅助）。
     * 解析失败（非数值 id）或失序时返回 null——调用方退化为全量。
     *
     * @param size 弟子数量
     * @param idAt 按索引取 id 字符串
     * @return 升序 id 数组；非数值/失序返回 null
     */
    private fun parseOrderedIds(size: Int, idAt: (Int) -> String?): IntArray? {
        val ids = IntArray(size)
        var lastId = -1
        for (i in 0 until size) {
            val id = idAt(i)?.toIntOrNull()
            if (id == null || id < lastId) return null
            ids[i] = id
            lastId = id
        }
        return ids
    }

    override val discipleAggregates: StateFlow<List<DiscipleAggregate>> =
        _aggregatesFlow.asStateFlow()

    /** 宗门战力：与聚合同步（[updateAggregates] 写回点 + update 提交处血炼重算） */
    override val sectCombatPower: StateFlow<Long> = _combatPowerFlow.asStateFlow()

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
        pendingNotification = null,
        pendingMarriageProposals = emptyList()
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
    override fun clearPendingNotification() {
        _pendingNotificationFlow.value = null
        _updateVersion.value++
        _stateDirty = true
    }

    /** 通知队列（v3+） */
    override fun enqueueNotification(notification: GameNotification) {
        notificationQueue.offer(notification)
        if (notificationQueue.size > 200) notificationQueue.poll() // 上限 200，丢弃最旧
        _notificationsFlow.value = notificationQueue.toList()
        // 2026-08-01 修复：bump 版本号——unifiedState 依赖 _updateVersion 发射，
        // 旧实现不 bump 导致依赖统一快照的 UI 看不到通知变更（隐式契约缺口）
        _updateVersion.value++
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

    override fun removePendingBeastAttack(beastLevelId: String) {
        _pendingBeastAttacksFlow.value = _pendingBeastAttacksFlow.value.filter {
            it.beastLevel.id != beastLevelId
        }
        _updateVersion.value++
        _stateDirty = true
    }

    override fun clearPendingMarriageProposals() {
        _pendingMarriageProposalsFlow.value = emptyList()
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


    /**
     * update() 事务起始快照——字段变化检测基准。
     *
     * 将事务前全部 StateFlow 值聚合为单一对象，避免 diffAndEmit/markDirty/
     * detectFieldChanges 等辅助函数出现超长参数列表（聚合 data class 模式）。
     */
    private data class UpdateBaseline(
        val gameData: GameData,
        val equipmentStacks: List<EquipmentStack>,
        val equipmentInstances: List<EquipmentInstance>,
        val manualStacks: List<ManualStack>,
        val manualInstances: List<ManualInstance>,
        val pills: List<Pill>,
        val materials: List<Material>,
        val herbs: List<Herb>,
        val seeds: List<Seed>,
        val storageBags: List<StorageBag>,
        val battleLogs: List<BattleLog>,
        val teams: List<ExplorationTeam>,
        val isPaused: Boolean,
        val isLoading: Boolean,
        val isSaving: Boolean,
        val pendingNotification: GameNotification?,
        val pendingMarriageProposals: List<PendingMarriageProposal>
    )

    /** 提交阶段标志：final 状态三连 + block 内通知/婚姻变更检测结果 */
    private data class CommitFlags(
        val finalPaused: Boolean,
        val finalLoading: Boolean,
        val finalSaving: Boolean,
        val notificationChanged: Boolean,
        val proposalsChanged: Boolean
    )

    override fun update(block: MutableGameState.() -> Unit) {
        // ★ 运行时监护：主线程调用 update 是架构违规，
        // 第一层防护（launchOnEngine）已确保所有调用通过引擎线程，
        // 若此处触发说明有代码绕过防护直接调用了 update。
        if (!unsafeAllowMainThreadUpdateForTest && Looper.myLooper() == Looper.getMainLooper()) {
            if (BuildConfig.DEBUG) {
                error("stateStore.update() 被主线程调用，架构违规必须修复")
            } else {
                // Release 构建：记录致命错误后立即返回，宁可丢失一次状态更新也不阻塞主线程导致 ANR
                DomainLog.e(
                    TAG,
                    "update() 被主线程调用! 已跳过此次更新。" +
                        "必须通过 GameEngine.launchOnEngine 派发到引擎线程。",
                    IllegalStateException("主线程调用堆栈")
                )
                return
            }
        }

        var disciplesNeedReassemble = false
        // D-01 事务世代号：本次顶层事务的世代号（0 = 无事务/重入路径）。
        // committed 标记事务是否成功提交——异常/取消传播到锁外时 finally 据此
        // fireRollback（草稿丢弃，防复制）；成功则在锁外 fireCommitted（草稿落盘）。
        var txGen = 0L
        var committed = false

        try {
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
                    // D-01：分配本事务世代号（嵌套事务重入路径不达此处，归外层事务）
                    txGen = committedGeneration.incrementAndGet()
                    pendingGeneration = txGen
                reentrantBuffer.set(reusableMutableState)
                val baseline = captureBaseline()
                initReusableState(baseline)
                val notificationBeforeBlock = reusableMutableState.pendingNotification
                val proposalsBeforeBlock = reusableMutableState.pendingMarriageProposals
                executeBlockWithRngGuard(block)
                // ★ 冻结 EntityStore 快照，确保 items 引用正确反映变化
                freezeStores()
                val flags = resolveCommitFlags(
                    baseline,
                    reusableMutableState.pendingNotification !== notificationBeforeBlock,
                    reusableMutableState.pendingMarriageProposals !== proposalsBeforeBlock
                )
                // 个体 StateFlow 发射（始终执行，但有 !!! 引用比较防止无意义发射）
                // ★ 已移除自动批量发射模式：该模式在 ≥3 字段变化时抑制个体发射，
                // 导致时间和仓库显示冻结，而修炼流（锁外异步组装）继续更新。
                emitStateFlows(baseline, flags)
                // COW 快照隔离后，副本的 mutationVersion 从 0 起步且不再被
                // copyTo 逐元素写入污染，dirtyTracker 只记录本次事务真实写入的列。
                // 用 isDirty 判定"本次事务是否真的改了弟子数据"：
                // 纯 UI 事务（无弟子数据变更）不再触发全量 assembleAll。
                // 所有生产写路径（列级写入/insert/update/remove/replaceAll/
                // markDead/clear）均伴随列级 onWrite → dirtyTracker 标记。
                disciplesNeedReassemble = reusableMutableState.discipleTables.dirtyTracker.isDirty
                if (disciplesNeedReassemble) {
                    // 锁内仅标记，实际 assembleAll() 在锁外执行
                    // 减少 transactionMutex 持有时间，降低游戏循环锁争用
                    _discipleDirty = true
                }
                markDirtyFor(baseline, disciplesNeedReassemble)
                // 仅在有字段变化时递增版本号，触发 unifiedState 批处理重建
                if (detectFieldChanges(baseline, disciplesNeedReassemble, flags)) {
                    _updateVersion.value++
                    _stateDirty = true
                }
                // P-5：血炼百分比变化（不触发弟子组装）时同步重算战力——
                // 指纹缓存使仅血炼弟子重算、其余命中（O(D) 引用比较 + 缓存查找，微秒级）；
                // 弟子同时变化时由锁外 assemble 写回点（updateAggregates）统一重算。
                // S3 修复（对抗性审查）：cachedAggregates 为空（load/reset 窗口）时跳过——
                // 空缓存重算战力=0 会闪 0，且此时全量冷算在锁内（load 已清指纹缓存）；
                // 由随后的 assemble 写回点用最新血炼补算。
                if (reusableMutableState.gameData.bloodRefinementPctTotals
                    !== baseline.gameData.bloodRefinementPctTotals &&
                    !disciplesNeedReassemble && cachedAggregates.isNotEmpty()
                ) {
                    _combatPowerFlow.value = computeCombatPower(
                        cachedAggregates, reusableMutableState.gameData.bloodRefinementPctTotals
                    )
                }
                _discipleTables = reusableMutableState.discipleTables
                _discipleTables.writeAllowed = false  // ★ 出厂后锁定，防止绕过 update{} 直接写
                // P-3：捕获本事务脏列索引（供锁外 patch 组装复用子对象引用）。
                // 提交后立即消费——下一事务开始时 DirtyTracker 恒为空（既有不变量）。
                lastDirtyColumns = _discipleTables.dirtyTracker.consumeDirtyColumns()

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
            committed = true
        } finally {
            // D-01：世代号复位 + 回滚钩子（异常/取消传播路径；草稿丢弃防复制）。
            // fireRollback 在 pendingGeneration 复位后执行（观察者读到 0）。
            // ★ 嵌套（重入）update 不分配世代号（txGen=0），不得清零 pendingGeneration——
            // 否则外层事务尚未提交，草稿入队读 gen=0 走立即落盘路径，回滚时草稿已持久化
            // （复制）；嵌套事务归外层，世代号由外层事务统一提交/回滚。
            if (txGen > 0L) pendingGeneration = 0
            if (!committed && txGen > 0L) fireRollback(txGen)
        }
        // D-01：提交成功（锁外、事务线程）——提交钩子同步落盘（草稿持久化；
        // 观察者异常由 fireCommitted 内部捕获，不得破坏状态提交）
        if (committed && txGen > 0L) fireCommitted(txGen)

        // 在锁外执行增量 assemble，减少 transactionMutex 持有时间。
        // 使用 changedIdTracker 追踪本次事务中修改过的弟子 ID，
        // 只重新组装有变化的弟子，与全量缓存合并。
        // 对标 Bevy ECS change tick 跳过未修改组件的表迭代。
        // ★ 单线程调度器串行执行：增量组装读"执行时"的 _disciplesFlow 快照，
        //   并发交错会互相覆盖（丢弟子）；串行保证后启动的组装读到前次写回结果。
        if (disciplesNeedReassemble) {
            dispatchAssemble()
        }

    }

    /**
     * 事务块执行 + RNG 快照/回滚（P0-1 K 项根治）。
     *
     * 事务失败（block 抛异常）时 COW 缓冲被丢弃（状态回滚），但事务内消费的
     * 分区 PRNG 已前进——若不恢复，状态与随机序列永久分叉，读档重放不可复现
     * （游戏循环捕获异常后继续运行，分叉会被持久化）。
     *
     * 处理：block 前快照全部 8 分区状态（8×Long，成本可忽略），异常时恢复后
     * 原样抛出。CancellationException 直接重抛且不触发恢复（协程取消非事务
     * 失败）；恢复失败不掩盖原始异常（记录日志后继续抛出）。
     */
    @Suppress("ThrowsCount") // 事务失败/取消/OOM 三条路径各自原样抛出（必须显式）
    private fun executeBlockWithRngGuard(block: MutableGameState.() -> Unit) {
        val rngBaseline = rngSnapshotPort.snapshot()
        try {
            reusableMutableState.block()
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // 事务失败类型不可预期（结算逻辑任意异常均须恢复 RNG），必须全捕获
            try {
                rngSnapshotPort.restore(rngBaseline)
            } catch (@Suppress("TooGenericExceptionCaught") restoreFailure: Exception) {
                // 恢复失败类型不可预期（委托 GameRngManager），全捕获仅记录日志
                DomainLog.e(
                    TAG,
                    "RNG 回滚失败，确定性已破坏（事务异常将按原样抛出）",
                    restoreFailure
                )
            }
            throw e
        } catch (e: OutOfMemoryError) {
            // F7 对抗性审查修复：OOM 是 Error 非 Exception——COW 缓冲丢弃（状态回滚）
            // 但 RNG 已前进。与 loadFromSnapshot 的 OOM 处理对称，尽力恢复 RNG
            //（8×Long 赋值成本可忽略，内存耗尽时亦应尝试），再原样抛出
            try {
                rngSnapshotPort.restore(rngBaseline)
            } catch (@Suppress("TooGenericExceptionCaught") restoreFailure: Exception) {
                DomainLog.e(TAG, "OOM 回滚时 RNG 恢复失败，确定性已破坏", restoreFailure)
            }
            throw e
        }
    }

    /** 捕获事务起始时全部 StateFlow 快照（字段变化检测基准）。 */
    private fun captureBaseline(): UpdateBaseline = UpdateBaseline(
        gameData = _gameDataFlow.value,
        equipmentStacks = _equipmentStacksFlow.value,
        equipmentInstances = _equipmentInstancesFlow.value,
        manualStacks = _manualStacksFlow.value,
        manualInstances = _manualInstancesFlow.value,
        pills = _pillsFlow.value,
        materials = _materialsFlow.value,
        herbs = _herbsFlow.value,
        seeds = _seedsFlow.value,
        storageBags = _storageBagsFlow.value,
        battleLogs = _battleLogsFlow.value,
        teams = _teamsFlow.value,
        isPaused = _isPaused.value,
        isLoading = _isLoading.value,
        isSaving = _isSaving.value,
        pendingNotification = _pendingNotificationFlow.value,
        pendingMarriageProposals = _pendingMarriageProposalsFlow.value
    )

    /** 用基线快照初始化 reusableMutableState（COW deepCopy 每列 O(1) 共享存储）。 */
    private fun initReusableState(baseline: UpdateBaseline) {
        reusableMutableState.apply {
            gameData = baseline.gameData
            // COW deepCopy 每列 O(1) 共享存储，无需增量复制；
            // consumeDirtyColumns 仅为维护 DirtyTracker 状态（提交后表无事务外写入，恒为空）
            val dirtyCols = _discipleTables.dirtyTracker.consumeDirtyColumns()
            discipleTables = _discipleTables.deepCopy(dirtyCols).apply { writeAllowed = true }
            equipmentStacks = EntityStore(baseline.equipmentStacks)
            equipmentInstances = EntityStore(baseline.equipmentInstances)
            manualStacks = EntityStore(baseline.manualStacks)
            manualInstances = EntityStore(baseline.manualInstances)
            pills = EntityStore(baseline.pills)
            materials = EntityStore(baseline.materials)
            herbs = EntityStore(baseline.herbs)
            seeds = EntityStore(baseline.seeds)
            storageBags = EntityStore(baseline.storageBags)
            battleLogs = baseline.battleLogs
            teams = baseline.teams
            isPaused = baseline.isPaused
            isLoading = baseline.isLoading
            isSaving = baseline.isSaving
            pendingNotification = baseline.pendingNotification
            pendingMarriageProposals = baseline.pendingMarriageProposals
        }
    }

    /** 冻结全部 EntityStore 快照，确保 items 引用正确反映变化。 */
    private fun freezeStores() {
        reusableMutableState.equipmentStacks.freeze()
        reusableMutableState.equipmentInstances.freeze()
        reusableMutableState.manualStacks.freeze()
        reusableMutableState.manualInstances.freeze()
        reusableMutableState.pills.freeze()
        reusableMutableState.materials.freeze()
        reusableMutableState.herbs.freeze()
        reusableMutableState.seeds.freeze()
        reusableMutableState.storageBags.freeze()
    }

    /** 计算 final 状态三连 + 提交标志（isSaving/isLoading 以锁外最新值为准）。 */
    private fun resolveCommitFlags(
        baseline: UpdateBaseline,
        notificationChanged: Boolean,
        proposalsChanged: Boolean
    ): CommitFlags {
        val finalPaused = if (_isPaused.value != baseline.isPaused)
            _isPaused.value else reusableMutableState.isPaused
        val finalLoading = if (_isLoading.value != baseline.isLoading)
            _isLoading.value else reusableMutableState.isLoading
        val finalSaving = if (_isSaving.value != baseline.isSaving)
            _isSaving.value else reusableMutableState.isSaving
        _isPaused.value = finalPaused
        _isLoading.value = finalLoading
        _isSaving.value = finalSaving
        return CommitFlags(finalPaused, finalLoading, finalSaving, notificationChanged, proposalsChanged)
    }

    /** 个体 StateFlow 发射（引用比较防止无意义发射）。 */
    @Suppress("CyclomaticComplexMethod")  // 14 路引用比较分发，逻辑不可简化（原 update 内联时同复杂度）
    private fun emitStateFlows(baseline: UpdateBaseline, flags: CommitFlags) {
        if (reusableMutableState.gameData !== baseline.gameData)
            _gameDataFlow.value = reusableMutableState.gameData
        if (reusableMutableState.equipmentStacks.items !== baseline.equipmentStacks)
            _equipmentStacksFlow.value = reusableMutableState.equipmentStacks.items
        if (reusableMutableState.equipmentInstances.items !== baseline.equipmentInstances)
            _equipmentInstancesFlow.value = reusableMutableState.equipmentInstances.items
        if (reusableMutableState.manualStacks.items !== baseline.manualStacks)
            _manualStacksFlow.value = reusableMutableState.manualStacks.items
        if (reusableMutableState.manualInstances.items !== baseline.manualInstances)
            _manualInstancesFlow.value = reusableMutableState.manualInstances.items
        if (reusableMutableState.pills.items !== baseline.pills)
            _pillsFlow.value = reusableMutableState.pills.items
        if (reusableMutableState.materials.items !== baseline.materials)
            _materialsFlow.value = reusableMutableState.materials.items
        if (reusableMutableState.herbs.items !== baseline.herbs)
            _herbsFlow.value = reusableMutableState.herbs.items
        if (reusableMutableState.seeds.items !== baseline.seeds)
            _seedsFlow.value = reusableMutableState.seeds.items
        if (reusableMutableState.storageBags.items !== baseline.storageBags)
            _storageBagsFlow.value = reusableMutableState.storageBags.items
        if (reusableMutableState.teams !== baseline.teams)
            _teamsFlow.value = reusableMutableState.teams
        if (reusableMutableState.battleLogs !== baseline.battleLogs)
            _battleLogsFlow.value = reusableMutableState.battleLogs
        if (flags.notificationChanged)
            _pendingNotificationFlow.value = reusableMutableState.pendingNotification
        if (flags.proposalsChanged)
            _pendingMarriageProposalsFlow.value = reusableMutableState.pendingMarriageProposals
    }

    /** 仓库脏标记（repository 持久化调度依据）。 */
    private fun markDirtyFor(baseline: UpdateBaseline, disciplesNeedReassemble: Boolean) {
        repository.markDirty(
            gameData = reusableMutableState.gameData !== baseline.gameData,
            disciples = disciplesNeedReassemble,
            equipmentStacks = reusableMutableState.equipmentStacks.items !== baseline.equipmentStacks,
            equipmentInstances = reusableMutableState.equipmentInstances.items !== baseline.equipmentInstances,
            manualStacks = reusableMutableState.manualStacks.items !== baseline.manualStacks,
            manualInstances = reusableMutableState.manualInstances.items !== baseline.manualInstances,
            pills = reusableMutableState.pills.items !== baseline.pills,
            materials = reusableMutableState.materials.items !== baseline.materials,
            herbs = reusableMutableState.herbs.items !== baseline.herbs,
            seeds = reusableMutableState.seeds.items !== baseline.seeds,
            storageBags = reusableMutableState.storageBags.items !== baseline.storageBags,
            teams = reusableMutableState.teams !== baseline.teams,
            battleLogs = reusableMutableState.battleLogs !== baseline.battleLogs
        )
    }

    /** 事务内是否有字段变化（决定是否递增版本号触发 unifiedState 重建）。 */
    @Suppress("CyclomaticComplexMethod")  // 17 路字段比较，逻辑不可简化（原 update 内联时同复杂度）
    private fun detectFieldChanges(
        baseline: UpdateBaseline,
        disciplesNeedReassemble: Boolean,
        flags: CommitFlags
    ): Boolean = reusableMutableState.gameData !== baseline.gameData
        || disciplesNeedReassemble
        || reusableMutableState.equipmentStacks.items !== baseline.equipmentStacks
        || reusableMutableState.equipmentInstances.items !== baseline.equipmentInstances
        || reusableMutableState.manualStacks.items !== baseline.manualStacks
        || reusableMutableState.manualInstances.items !== baseline.manualInstances
        || reusableMutableState.pills.items !== baseline.pills
        || reusableMutableState.materials.items !== baseline.materials
        || reusableMutableState.herbs.items !== baseline.herbs
        || reusableMutableState.seeds.items !== baseline.seeds
        || reusableMutableState.storageBags.items !== baseline.storageBags
        || reusableMutableState.teams !== baseline.teams
        || reusableMutableState.battleLogs !== baseline.battleLogs
        || flags.finalPaused != baseline.isPaused
        || flags.finalLoading != baseline.isLoading
        || flags.finalSaving != baseline.isSaving
        || flags.notificationChanged
        || flags.proposalsChanged

    /**
     * 锁外增量组装（减少 transactionMutex 持有时间）。
     *
     * 使用 changedIdTracker 追踪本次事务中修改过的弟子 ID，
     * 只重新组装有变化的弟子，与全量缓存合并。
     * 对标 Bevy ECS change tick 跳过未修改组件的表迭代。
     * ★ 单线程调度器串行执行：增量组装读"执行时"的 _disciplesFlow 快照，
     *   并发交错会互相覆盖（丢弟子）；串行保证后启动的组装读到前次写回结果。
     */
    private fun dispatchAssemble() {
        // 捕获提交时代的版本号：load/reset 会递增版本号作废排队中的陈旧组装任务
        val gen = discipleVersion.get()
        val changedIds = _discipleTables.changedIdTracker.consumeChangedIds()
        // T4（2026-08-05）：容量拒绝标志——大 id 弟子未被 changedIds 记录，
        // 增量组装会保留其陈旧快照数据；置位时强制走全量兜底分支
        val forceFullAssemble = _discipleTables.changedIdTracker.consumeRejectedRecord()
        // P-3：本事务脏列索引（update 提交处捕获，patch 组装按组复用子对象引用）
        val dirtyColumns = lastDirtyColumns
        if (changedIds.isNotEmpty() && !forceFullAssemble) {
            applicationScopeProvider.scope.launch(assembleDispatcher) {
                if (discipleVersion.get() != gen) return@launch
                val prevSnapshot = _disciplesFlow.value
                // 2026-08-01：变更覆盖大部分弟子时增量归并无收益（增量还需组装每
                // 个变更弟子 + 归并）；P-3 起全量路径改用 patch 组装——仅重装脏列
                // 所属子对象组，未脏组复用 prev 引用（每旬 cultivation 全量场景
                // 消除 ~67 列读 + 6 子对象分配/弟子）
                val list = if (changedIds.size >= prevSnapshot.size / 2) {
                    _discipleTables.assembleAllPatched(prevSnapshot, changedIds, dirtyColumns)
                } else {
                    _discipleTables.assembleAllIncremental(prevSnapshot, changedIds)
                }
                // P-14 H1 加固（2026-08-05）：publish 前二次版本检查——首次检查通过后、
                // assemble 执行期间若 load/reset 已锁内替换表并递增版本，陈旧结果必须丢弃，
                // 不得用旧 changedIds 组装出的部分结果覆盖加载列表
                if (discipleVersion.get() != gen) return@launch
                _disciplesFlow.value = list
                // P-5：聚合与组装对齐（单线程串行，无竞争；组装完成即聚合新鲜）
                updateAggregates(list, gen)
            }
        } else {
            // 回退：changedIdTracker 可能未捕获列级写入，全量 assemble 兜底
            applicationScopeProvider.scope.launch(assembleDispatcher) {
                if (discipleVersion.get() != gen) return@launch
                val list = _discipleTables.assembleAll()
                // P-14 H1 加固（2026-08-05）：同增量分支——assemble 期间版本变化则丢弃
                if (discipleVersion.get() != gen) return@launch
                _disciplesFlow.value = list
                updateAggregates(list, gen)
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
    /** loadFromSnapshot 失败回滚基线（C-8 拆分——旧值快照聚合） */
    private data class LoadBaseline(
        val gameData: GameData,
        val disciples: List<Disciple>,
        val equipmentStacks: List<EquipmentStack>,
        val equipmentInstances: List<EquipmentInstance>,
        val manualStacks: List<ManualStack>,
        val manualInstances: List<ManualInstance>,
        val pills: List<Pill>,
        val materials: List<Material>,
        val herbs: List<Herb>,
        val seeds: List<Seed>,
        val storageBags: List<StorageBag>,
        val teams: List<ExplorationTeam>,
        val battleLogs: List<BattleLog>,
        val isPaused: Boolean,
        val isLoading: Boolean,
        val isSaving: Boolean,
        val deathRecords: List<DeathRecord>
    )

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
            // P0-1：读档前快照 RNG 状态——loadFromSnapshot 失败回滚（rollbackLoad）
            // 时同步恢复，保证状态与随机序列一致（状态/RNG 错配的确定性修复）
            val rngBaseline = rngSnapshotPort.snapshot()
            // 2026-08-01 修复：版本号递增必须**最先**执行（clear 之前）——
            // 排队中的增量组装若在 load 获取锁前通过 gen 检查，会与 clear+insert 并发
            discipleVersion.incrementAndGet()
            // 缓存清除在所有写入之前执行
            disciplePowerCache.clear()
            aiDisciplePowerCache.clear()
            // 2026-08-01：聚合缓存失效——load 整体替换弟子列表，
            // 否则增量 diff 会用旧缓存与新列表错误合并
            cachedAggregates = emptyList()

            // 保存旧值用于失败回滚（C-8 拆分：LoadBaseline 聚合）
            val baseline = LoadBaseline(
                gameData = _gameDataFlow.value,
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
                isPaused = _isPaused.value,
                isLoading = _isLoading.value,
                isSaving = _isSaving.value,
                // clear() 现会清空 _deathRecords——回滚时需恢复
                deathRecords = _discipleTables.deathRecords.toList()
            )

            try {
                // P-9：旧档事件 sequenceId 一次性回填（旧档全 0 → 按列表序分配，
                // 保证消息列表稳定 key；新档无 0 序号时零成本跳过）
                _gameDataFlow.value = backfillEventSequenceIds(gameData)
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
                // P0-1：状态 + RNG 锁内原子切换——读档成功后用存档内的 rngStates
                // 覆盖当前 PRNG 状态（原在 SaveLoadViewModel 的 UI 协程中 restoreStates，
                // 位置在 loadData 之后：load 成功但其后失败会错过恢复 → 状态/RNG 错配）
                if (gameData.rngStates.isNotEmpty()) {
                    rngSnapshotPort.restore(gameData.rngStates)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                rollbackLoad(baseline, e, rngBaseline)
                throw e
            } catch (e: OutOfMemoryError) {
                // C3-c（2026-08-05）：OOM 是 Error 非 Exception，原 catch 接不住——
                // crafted 大 id 弟子扩容千万级平铺表（≈7GB）直接崩溃且重试即崩溃循环。
                // 状态已撕裂必须先回滚（内存耗尽时尽力而为），再转 IllegalStateException
                // 使外层统一走失败处理（StorageEngine / performLoadToSlot）
                try {
                    rollbackLoad(baseline, IllegalStateException("读档内存不足", e), rngBaseline)
                } catch (@Suppress("TooGenericExceptionCaught") rollbackFailure: Exception) {
                    // 内存耗尽时回滚失败类型不可预期（赋值/扩容均可能抛），必须全吞尽力而为
                    DomainLog.e(TAG, "OOM 回滚失败（内存已耗尽）", rollbackFailure)
                }
                throw IllegalStateException("读档内存不足，存档可能异常过大", e)
            } finally {
                _discipleTables.writeAllowed = false
            }
        }
        // ★ 锁外投递组装（版本号已在锁内最先递增——2026-08-01 修复）：
        // 递增版本号作废 assembleDispatcher 队列中基于旧数据的排队任务，
        // 并将本组装投递到同一单线程调度器——避免与增量组装协程并发交错
        val gen = discipleVersion.get()
        applicationScopeProvider.scope.launch(assembleDispatcher) {
            if (discipleVersion.get() != gen) return@launch
            val list = _discipleTables.assembleAll()
            // P-14 H1 加固（2026-08-05）：publish 前二次版本检查——load 投递后、
            // 执行前若又有新 load 递增版本，本任务结果同样丢弃（保队列内后入者胜）
            if (discipleVersion.get() != gen) return@launch
            _disciplesFlow.value = list
            // P-5：load 后聚合同步新鲜（getter 代际校验兜底窗口收敛到协程执行完成）
            updateAggregates(list, gen)
        }
    }

    /**
     * C-8：loadFromSnapshot 失败回滚——恢复全部 Flow 旧值 + 重建 DiscipleTables。
     *
     * ★ COW 快照隔离：不能依赖 oldTables.deepCopy()（共享 store 会被 clear()
     * 原地清空——提交后的列是 owned 状态不触发私有化）。回滚直接用内存中的
     * oldDisciples 列表（不受 clear 影响）重建。
     *
     * @param baseline 事务前快照（LoadBaseline）
     * @param e 触发回滚的异常（仅用于日志）
     */
    private fun rollbackLoad(baseline: LoadBaseline, e: Exception, rngBaseline: Map<Int, Long>) {
        DomainLog.e(TAG, "loadFromSnapshot 失败，执行回滚: ${e.message}", e)
        // P0-1：读档失败回滚时同步恢复 RNG 到读档前状态（与状态回滚一致）
        try {
            rngSnapshotPort.restore(rngBaseline)
        } catch (@Suppress("TooGenericExceptionCaught") restoreFailure: Exception) {
            // 恢复失败类型不可预期（委托 GameRngManager），全捕获仅记录日志
            DomainLog.e(TAG, "读档回滚时 RNG 恢复失败，确定性已破坏", restoreFailure)
        }
        _gameDataFlow.value = baseline.gameData
        _disciplesFlow.value = baseline.disciples
        // P-5：回滚路径同步恢复聚合（load 开头已清空缓存）
        cachedAggregates = baseline.disciples.map { it.toAggregate() }
        _aggregatesFlow.value = cachedAggregates
        _combatPowerFlow.value = computeCombatPower(
            cachedAggregates, baseline.gameData.bloodRefinementPctTotals
        )
        aggregatesGen = discipleVersion.get()
        _discipleTables.apply { writeAllowed = true }.clear()
        baseline.disciples.forEach { _discipleTables.insert(it) }
        baseline.deathRecords.forEach { _discipleTables.addDeathRecord(it) }
        _equipmentStacksFlow.value = baseline.equipmentStacks
        _equipmentInstancesFlow.value = baseline.equipmentInstances
        _manualStacksFlow.value = baseline.manualStacks
        _manualInstancesFlow.value = baseline.manualInstances
        _pillsFlow.value = baseline.pills
        _materialsFlow.value = baseline.materials
        _herbsFlow.value = baseline.herbs
        _seedsFlow.value = baseline.seeds
        _storageBagsFlow.value = baseline.storageBags
        _teamsFlow.value = baseline.teams
        _battleLogsFlow.value = baseline.battleLogs
        _isPaused.value = baseline.isPaused
        _isLoading.value = baseline.isLoading
        _isSaving.value = baseline.isSaving
    }

    /**
     * P-9：旧档事件 [GameEventRecord.sequenceId] 一次性回填。
     *
     * 旧档（v4.0.83 之前）全部 sequenceId=0，消息列表头部 takeLast 移除后
     * 全部 key 位移导致整列表重建；加载后按列表序回填 1..N。无 0 序号时零成本返回原对象。
     * O(N) 一次（列表上限 200 条）。
     *
     * T1 修复（2026-08-05）：存在任一 0 序号时**整体重编号 1..N（列表序）**——
     * 原实现只重编号 0 条目（[0,0,5] → [6,7,5]），靠前 0 序号拿到比靠后非零条目
     * 更大的序号，破坏"序号随列表位置递增"的稳定 key 语义。
     *
     * @param gameData 待加载的游戏数据
     * @return 回填后的 GameData（无变化时返回原引用）
     */
    private fun backfillEventSequenceIds(gameData: GameData): GameData {
        val records = gameData.gameEventRecords
        if (records.none { it.sequenceId == 0L }) return gameData
        val backfilled = records.mapIndexed { index, record ->
            record.copy(sequenceId = (index + 1).toLong())
        }
        return gameData.copy(gameEventRecords = backfilled)
    }

    override suspend fun reset() {
        transactionLock.withLock {
            // 2026-08-01 修复：版本号递增必须**最先**执行（clear 之前）——
            // 若在锁内末尾递增，排队中的增量组装可在 reset 获取锁前通过 gen 检查，
            // 与 clear 并发遍历表（组装出半截列表覆盖空列表）
            discipleVersion.incrementAndGet()
            disciplePowerCache.clear()
            aiDisciplePowerCache.clear()
            // 2026-08-01：聚合缓存失效（同 load 语义）
            cachedAggregates = emptyList()
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
        // 2026-08-01 对抗性审查修复：reset 后投递同调度器全量组装（镜像 load 做法）——
        // 版本号检查是"检查后执行"单点模式，排队任务若在 reset 锁前通过 gen 检查，
        // 会在 reset 清表后把陈旧列表写回（覆盖空列表）。投递组装任务使其成为
        // 最后写者（FIFO），彻底闭合 TOCTOU 窗口。
        val gen = discipleVersion.get()
        applicationScopeProvider.scope.launch(assembleDispatcher) {
            if (discipleVersion.get() != gen) return@launch
            val list = _discipleTables.assembleAll()
            _disciplesFlow.value = list
            // P-5：reset 后聚合同步新鲜
            updateAggregates(list, gen)
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
