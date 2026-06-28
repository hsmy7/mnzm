package com.xianxia.sect.core.state

import com.xianxia.sect.core.engine.SectCombatPowerCalculator
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.data.GameStateRepository
import com.xianxia.sect.di.ApplicationScopeProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
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

    companion object {
        private const val TAG = "GameStateStore"

        /**
         * 三路合并 discipleTables — 影子为基础，生命周期字段取真实 store 的值。
         *
         * 问题：空闲模式下 processYearlyEvents() 将老化/反思释放/哀悼到期等
         * 写入真实 store 的 discipleTables，但后续 swapFromShadow 会用影子的旧
         * 副本整体覆盖，导致年龄回退。
         *
         * 合并规则：
         * - 以 shadow 为基础（保留修炼/生产进度）
         * - 生命周期字段（age/isAlive/status/griefEndYear 等）如与 origin
         *   不同，说明被年度事件修改过，取 current 的值
         * - origin 有但 current 没有的弟子已死亡，从结果中移除
         * - shadow 独有的弟子（子嗣出生）保留
         */
        internal fun mergeDiscipleTables(
            originDisciples: List<Disciple>,
            shadow: DiscipleTables,
            current: DiscipleTables
        ): DiscipleTables {
            val result = shadow.deepCopy()
            val originMap = originDisciples.associateBy { it.id }
            val currentIds = current.ids.toSet()

            // 1. 移除已死亡的弟子（origin 有，current 没有）
            for (oDisciple in originDisciples) {
                val id = oDisciple.id.toInt()
                if (id !in currentIds && id in result.ids) {
                    result.remove(id)
                }
            }

            // 2. 生存弟子：生命周期字段若被年度/实时轨修改，取 current 的值
            for (id in current.ids) {
                val oDisciple = originMap[id.toString()] ?: continue

                // -- processDiscipleAging --
                val currentAge = current.ages.getOrDefault(id, 0)
                if (currentAge != oDisciple.age)
                    result.ages[id] = currentAge

                // -- 实时轨 HP/MP 恢复 --
                // 实时轨每旬恢复，批量轨不持有，影子 swap 时从主状态保留
                val currentHp = current.currentHps.getOrDefault(id, 0)
                if (currentHp != oDisciple.currentHp)
                    result.currentHps[id] = currentHp

                val currentMp = current.currentMps.getOrDefault(id, 0)
                if (currentMp != oDisciple.currentMp)
                    result.currentMps[id] = currentMp

                // -- 实时轨突破：大境界变更 --
                // 突破改变大境界，影子未参与，从主状态保留
                val currentRealm = current.realms.getOrDefault(id, 0)
                if (currentRealm != oDisciple.realm)
                    result.realms[id] = currentRealm

                val currentIsAlive = current.isAlive.getOrDefault(id, 0)
                val originAlive = if (oDisciple.isAlive) 1 else 0
                if (currentIsAlive != originAlive)
                    result.isAlive[id] = currentIsAlive

                val currentRealmLayer = current.realmLayers.getOrDefault(id, 0)
                if (currentRealmLayer != oDisciple.realmLayer)
                    result.realmLayers[id] = currentRealmLayer

                val currentLifespan = current.lifespans.getOrDefault(id, 0)
                if (currentLifespan != oDisciple.lifespan)
                    result.lifespans[id] = currentLifespan

                // -- processReflectionRelease --
                val currentStatus = current.statuses.getOrDefault(
                    id, DiscipleStatus.IDLE
                )
                if (currentStatus != oDisciple.status)
                    result.statuses[id] = currentStatus

                val currentStatusData = current.statusData.getOrDefault(
                    id, emptyMap()
                )
                if (currentStatusData != oDisciple.statusData)
                    result.statusData[id] = currentStatusData

                val currentMorality = current.moralities.getOrDefault(id, 0)
                if (currentMorality != oDisciple.skills.morality)
                    result.moralities[id] = currentMorality

                val currentLoyalty = current.loyalties.getOrDefault(id, 0)
                if (currentLoyalty != oDisciple.skills.loyalty)
                    result.loyalties[id] = currentLoyalty

                // -- processGriefExpiry --
                // 注：nullable 字段 null 时不 insert，须用 getOrNull 防 NoSuchElementException
                val currentGriefEnd = current.griefEndYears.getOrNull(id)
                if (currentGriefEnd != oDisciple.social.griefEndYear)
                    result.griefEndYears[id] = currentGriefEnd

                // -- handleDiscipleDeath 连锁 --
                val currentPartnerId = current.partnerIds.getOrNull(id)
                if (currentPartnerId != oDisciple.social.partnerId)
                    result.partnerIds[id] = currentPartnerId

                val currentMasterId = current.masterIds.getOrNull(id)
                if (currentMasterId != oDisciple.social.masterId)
                    result.masterIds[id] = currentMasterId

                // -- 实时轨修炼进度保留 --
                // 焦点域（如 DISCIPLES）在空闲模式下仍 100ms 实时结算，
                // 直接写真实 store。批量结算 shadow 走 30s 非焦点域轨道。
                // 取 max 确保实时轨更快的进展不被批量影子覆盖。
                val currentCult = current.cultivations.getOrDefault(id, 0.0)
                if (currentCult > result.cultivations.getOrDefault(id, 0.0))
                    result.cultivations[id] = currentCult
            }

            return result
        }
    }

    private var _discipleTables = DiscipleTables()
    override val discipleTables: DiscipleTables get() = _discipleTables

    private val transactionMutex = Mutex()

    /**
     * 当前持有 transactionMutex 的线程（null 表示未持有）。
     *
     * 用于检测 [update] 的重入调用：游戏循环 tick 在 `stateStore.update {}` 块内
     * 同步调用各 GameSystem，而某些 System/Service（如 CultivationEventProcessor.processPhaseTick、
     * ProductionSubsystem 的生产完成逻辑）内部又调用了 `stateStore.update {}`。
     * 由于 [transactionMutex] 是不可重入的，同线程二次 withLock 会永久死锁。
     *
     * 游戏循环跑在单线程 dispatcher（GameEngineCore.GAME_DISPATCHER）上，
     * 因此用线程身份即可精确识别重入；UI/ViewModel 的 update 调用在主线程或 IO 线程，
     * 不会与游戏循环线程混淆。
     */
    private val transactionOwnerThread = AtomicReference<Thread?>(null)

    @Volatile
    private var currentTransactionState: MutableGameState? = null

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
    internal val _pendingBattleRewardCardsFlow = MutableStateFlow<List<RewardCardItem>>(emptyList())
    internal val _rewardCardQueueFlow = MutableStateFlow<List<RewardCardItem>>(emptyList())
    internal val _pendingBeastAttacksFlow = MutableStateFlow<List<PendingBeastAttack>>(emptyList())

    private val _isPaused = MutableStateFlow(true)
    private val _isLoading = MutableStateFlow(false)
    private val _isSaving = MutableStateFlow(false)

    // 版本计数器：每次 update() 有字段变化时递增，用于 unifiedState 批处理触发
    internal val _updateVersion = MutableStateFlow(0L)

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

    override val pendingBattleResult: StateFlow<BattleResultUIData?> = _pendingBattleResultFlow.asStateFlow()
    override val pendingNotification: StateFlow<GameNotification?> = _pendingNotificationFlow.asStateFlow()
    override val pendingBattleRewardCards: StateFlow<List<RewardCardItem>> = _pendingBattleRewardCardsFlow.asStateFlow()
    override val rewardCardQueue: StateFlow<List<RewardCardItem>> = _rewardCardQueueFlow.asStateFlow()
    override val pendingBeastAttacks: StateFlow<List<PendingBeastAttack>> = _pendingBeastAttacksFlow.asStateFlow()

    // === 三层 StateFlow 架构 ===
    // HighFreq: 高频变化字段，sample 降频
    override val highFreqState: StateFlow<GameStateStore.HighFreqState> = combine(
        _gameDataFlow.map { it.spiritStones }.distinctUntilChanged(),
        _gameDataFlow.map { it.gameYear }.distinctUntilChanged(),
        _gameDataFlow.map { it.gameMonth }.distinctUntilChanged(),
        _gameDataFlow.map { it.gamePhase }.distinctUntilChanged(),
        _isPaused
    ) { spiritStones, year, month, phase, paused ->
        GameStateStore.HighFreqState(spiritStones, year, month, phase, paused)
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
                gameSpeed = gd.gameSpeed,
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

    private val equipmentInstancesFlow = _equipmentInstancesFlow
        .distinctUntilChanged { old, new -> old === new }

    private val manualInstancesFlow = _manualInstancesFlow
        .distinctUntilChanged { old, new -> old === new }

    override val sectCombatPower: StateFlow<Long> = combine(
        disciplesFlow,
        equipmentInstancesFlow,
        manualInstancesFlow
    ) { disciples, equipInstances, manualInstances ->
        val aliveDisciples = disciples.filter { it.isAlive }
        val equipMap = equipInstances.associateBy { it.id }
        val manualMap = manualInstances.associateBy { it.id }
        val aliveIds = aliveDisciples.map { it.id }.toSet()

        disciplePowerCache.keys.retainAll(aliveIds)

        var total = 0L
        for (disciple in aliveDisciples) {
            val aggregate = disciple.toAggregate()
            val fp = SectCombatPowerCalculator.computePlayerFingerprint(aggregate)
            val cached = disciplePowerCache[disciple.id]
            if (cached != null && cached.fingerprint == fp) {
                total += cached.power
            } else {
                val power = SectCombatPowerCalculator.calculatePlayerDisciplePower(
                    aggregate, equipMap, manualMap
                )
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

    override val aiSectCombatPowers: StateFlow<Map<String, Long>> = aiSectDisciplesFlow
        .map { aiDisciplesMap ->
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
                    val fp = SectCombatPowerCalculator.computeAIFingerprint(aggregate)
                    val cached = aiDisciplePowerCache[disciple.id]
                    if (cached != null && cached.fingerprint == fp) {
                        total += cached.power
                    } else {
                        val power = SectCombatPowerCalculator.calculateAIDisciplePower(aggregate)
                        aiDisciplePowerCache[disciple.id] = CachedPower(fp, power)
                        total += power
                    }
                }
                result[sectId] = total
            }
            result.toMap()
        }
        .distinctUntilChanged { old, new -> old === new || old == new }
        .stateIn(applicationScopeProvider.scope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    override suspend fun modifyState(block: MutableGameState.() -> Unit) {
        val txState = currentTransactionState
        if (txState != null && transactionOwnerThread.get() == Thread.currentThread()) {
            txState.block()
        } else {
            update { block() }
        }
    }

    private var shadowOrigin: UnifiedGameState? = null

    // CUSTOM 字段的合并函数（origin, shadow, oldState → 合并后的值）
    // 策略声明见 GameData.kt 各字段的 @SettlementStrategy 注解

    override fun createSettlementShadow(): MutableGameState {
        val gd = _gameDataFlow.value
        val disc = _disciplesFlow.value
        val ei = _equipmentInstancesFlow.value
        val mi = _manualInstancesFlow.value
        val p = _pillsFlow.value
        // 生产方法会读写这些字段——必须拷贝
        val es = _equipmentStacksFlow.value
        val ms = _manualStacksFlow.value
        val mat = _materialsFlow.value
        val h = _herbsFlow.value
        val s = _seedsFlow.value
        val snapshot = UnifiedGameState(
            gameData = gd,
            disciples = disc,
            equipmentStacks = es,
            equipmentInstances = ei,
            manualStacks = ms,
            manualInstances = mi,
            pills = p,
            materials = mat,
            herbs = h,
            seeds = s,
            storageBags = emptyList(),
            teams = emptyList(),
            battleLogs = emptyList(),
            alliances = gd.alliances,
            isPaused = _isPaused.value,
            isLoading = _isLoading.value,
            isSaving = _isSaving.value,
            pendingNotification = _pendingNotificationFlow.value
        )
        shadowOrigin = snapshot
        return MutableGameState(
            gameData = gd,
            discipleTables = _discipleTables.deepCopy(),
            equipmentStacks = EntityStore(es),
            equipmentInstances = EntityStore(ei),
            manualStacks = EntityStore(ms),
            manualInstances = EntityStore(mi),
            pills = EntityStore(p),
            materials = EntityStore(mat),
            herbs = EntityStore(h),
            seeds = EntityStore(s),
            storageBags = EntityStore(),
            teams = emptyList(),
            battleLogs = emptyList(),
            isPaused = _isPaused.value,
            isLoading = _isLoading.value,
            isSaving = _isSaving.value,
            pendingNotification = _pendingNotificationFlow.value,
            isSettlementShadow = true
        )
    }

    override suspend fun swapFromShadow(shadow: MutableGameState) {
        val origin = shadowOrigin
        update {
            // gameData 和物品永不被影子修改 — 全部走 phase loop 的 stateStore.update
            // 只需合并 discipleTables（修炼值/HP/MP/子嗣出生）
            this.discipleTables = if (origin != null) {
                mergeDiscipleTables(origin.disciples, shadow.discipleTables, this.discipleTables)
            } else {
                shadow.discipleTables
            }
        }
        shadowOrigin = null
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
    }

    override fun setLoadingDirect(loading: Boolean) {
        _isLoading.value = loading
        _updateVersion.value++
    }

    override fun setSavingDirect(saving: Boolean) {
        _isSaving.value = saving
        _updateVersion.value++
    }

    // === 快照读取（绕过 stateIn 调度延迟） ===
    override fun getCurrentSeeds(): List<Seed> = _seedsFlow.value
    override fun getCurrentHerbs(): List<Herb> = _herbsFlow.value
    override fun getCurrentMaterials(): List<Material> = _materialsFlow.value

    // === 通知 API ===
    override fun setPendingNotification(notification: GameNotification) {
        _pendingNotificationFlow.value = notification
        _updateVersion.value++
    }

    override fun clearPendingNotification() {
        _pendingNotificationFlow.value = null
        _updateVersion.value++
    }

    override fun setPendingBattleResult(result: BattleResultUIData) {
        _pendingBattleResultFlow.value = result
        _updateVersion.value++
    }

    override fun clearPendingBattleResult() {
        _pendingBattleResultFlow.value = null
        _updateVersion.value++
    }

    override fun setPendingBeastAttacks(attacks: List<PendingBeastAttack>) {
        _pendingBeastAttacksFlow.value = attacks
        _updateVersion.value++
    }

    override fun clearPendingBeastAttacks() {
        _pendingBeastAttacksFlow.value = emptyList()
        _updateVersion.value++
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


    override suspend fun update(block: suspend MutableGameState.() -> Unit) {

        // 重入检测：若当前线程已持有 transactionMutex，说明本次 update 是在
        // tick 事务内被某个 System/Service 嵌套调用的。transactionMutex 不可重入，
        // 若再次 withLock 会同线程自死锁。改为直接对事务内状态（reusableMutableState）
        // 执行 block——它正是外层 update 正在操作的对象，改动会随外层 update 在
        // 提交阶段统一写回 StateFlow。
        if (transactionOwnerThread.get() == Thread.currentThread() && currentTransactionState != null) {
            val txState = requireNotNull(currentTransactionState) {
                "currentTransactionState became null after reentrance check"
            }
            txState.block()
            // 重入路径：txState 即外层 update 持有的 reusableMutableState，
            // 各字段变化由外层 update 在提交阶段统一检测并写回 StateFlow，无需在此重复处理。
            return
        }

        transactionMutex.withLock {
            transactionOwnerThread.set(Thread.currentThread())
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
                discipleTables = _discipleTables
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
            currentTransactionState = reusableMutableState
            try {
                val notificationBeforeBlock = reusableMutableState.pendingNotification
                reusableMutableState.block()
                val blockChangedNotification = reusableMutableState.pendingNotification !== notificationBeforeBlock
                val finalPaused = if (_isPaused.value != curPaused) _isPaused.value else reusableMutableState.isPaused
                val finalLoading = if (_isLoading.value != curLoading) _isLoading.value else reusableMutableState.isLoading
                val finalSaving = if (_isSaving.value != curSaving) _isSaving.value else reusableMutableState.isSaving
                _isPaused.value = finalPaused
                _isLoading.value = finalLoading
                _isSaving.value = finalSaving
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
                if (reusableMutableState.battleLogs !== curBL) _battleLogsFlow.value = reusableMutableState.battleLogs
                if (blockChangedNotification) _pendingNotificationFlow.value = reusableMutableState.pendingNotification
                val disciplesChanged = reusableMutableState.discipleTables !== _discipleTables
                val mutated = reusableMutableState.discipleTables.mutationVersion
                if (disciplesChanged || mutated != lastAssembledMutationVersion) {
                    _disciplesFlow.value = reusableMutableState.discipleTables.assembleAll()
                    lastAssembledMutationVersion = mutated
                }
                repository.markDirty(
                    gameData = reusableMutableState.gameData !== curGame,
                    disciples = disciplesChanged || mutated != lastAssembledMutationVersion,
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
                    || disciplesChanged || mutated != lastAssembledMutationVersion
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
                if (anyFieldChanged) _updateVersion.value++
                _discipleTables = reusableMutableState.discipleTables
            } finally {
                currentTransactionState = null
                transactionOwnerThread.set(null)
            }
        }
    }

    override suspend fun <R> updateAndReturn(block: suspend MutableGameState.() -> R): R {

        // 重入检测：当前线程已持有 transactionMutex，直接对事务内状态执行 block
        if (transactionOwnerThread.get() == Thread.currentThread()
            && currentTransactionState != null
        ) {
            return requireNotNull(currentTransactionState) {
                "currentTransactionState became null after reentrance check"
            }.block()
        }

        transactionMutex.withLock {
            transactionOwnerThread.set(Thread.currentThread())
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
                discipleTables = _discipleTables
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
            currentTransactionState = reusableMutableState
            try {
                val notificationBeforeBlock = reusableMutableState.pendingNotification
                val result = reusableMutableState.block()
                val blockChangedNotification =
                    reusableMutableState.pendingNotification !== notificationBeforeBlock
                val finalPaused = if (_isPaused.value != curPaused)
                    _isPaused.value else reusableMutableState.isPaused
                val finalLoading = if (_isLoading.value != curLoading)
                    _isLoading.value else reusableMutableState.isLoading
                val finalSaving = if (_isSaving.value != curSaving)
                    _isSaving.value else reusableMutableState.isSaving
                _isPaused.value = finalPaused
                _isLoading.value = finalLoading
                _isSaving.value = finalSaving
                if (reusableMutableState.gameData !== curGame)
                    _gameDataFlow.value = reusableMutableState.gameData
                if (reusableMutableState.equipmentStacks.items !== curES)
                    _equipmentStacksFlow.value = reusableMutableState.equipmentStacks.items
                if (reusableMutableState.equipmentInstances.items !== curEI)
                    _equipmentInstancesFlow.value = reusableMutableState.equipmentInstances.items
                if (reusableMutableState.manualStacks.items !== curMS)
                    _manualStacksFlow.value = reusableMutableState.manualStacks.items
                if (reusableMutableState.manualInstances.items !== curMI)
                    _manualInstancesFlow.value = reusableMutableState.manualInstances.items
                if (reusableMutableState.pills.items !== curP)
                    _pillsFlow.value = reusableMutableState.pills.items
                if (reusableMutableState.materials.items !== curMat)
                    _materialsFlow.value = reusableMutableState.materials.items
                if (reusableMutableState.herbs.items !== curH)
                    _herbsFlow.value = reusableMutableState.herbs.items
                if (reusableMutableState.seeds.items !== curS)
                    _seedsFlow.value = reusableMutableState.seeds.items
                if (reusableMutableState.storageBags.items !== curSB)
                    _storageBagsFlow.value = reusableMutableState.storageBags.items
                if (reusableMutableState.teams !== curT)
                    _teamsFlow.value = reusableMutableState.teams
                if (reusableMutableState.battleLogs !== curBL)
                    _battleLogsFlow.value = reusableMutableState.battleLogs
                if (blockChangedNotification)
                    _pendingNotificationFlow.value = reusableMutableState.pendingNotification
                val disciplesChanged = reusableMutableState.discipleTables !== _discipleTables
                val mutated = reusableMutableState.discipleTables.mutationVersion
                if (disciplesChanged || mutated != lastAssembledMutationVersion) {
                    _disciplesFlow.value = reusableMutableState.discipleTables.assembleAll()
                    lastAssembledMutationVersion = mutated
                }
                repository.markDirty(
                    gameData = reusableMutableState.gameData !== curGame,
                    disciples = disciplesChanged || mutated != lastAssembledMutationVersion,
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
                val anyFieldChanged = reusableMutableState.gameData !== curGame
                    || disciplesChanged || mutated != lastAssembledMutationVersion
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
                if (anyFieldChanged) _updateVersion.value++
                _discipleTables = reusableMutableState.discipleTables
                return result
            } finally {
                currentTransactionState = null
                transactionOwnerThread.set(null)
            }
        }
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
        transactionMutex.withLock {
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
                _discipleTables.clear()
                disciples.forEach { _discipleTables.insert(it) }
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 回滚所有已写入的 Flow 值
                DomainLog.e(TAG, "loadFromSnapshot 失败，执行回滚: ${e.message}", e)
                _gameDataFlow.value = oldGameData
                _disciplesFlow.value = oldDisciples
                _discipleTables.clear()
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
            }
        }
    }

    override suspend fun reset() {
        transactionMutex.withLock {
            disciplePowerCache.clear()
            aiDisciplePowerCache.clear()
            _gameDataFlow.value = GameData()
            _disciplesFlow.value = emptyList()
            _discipleTables.clear()
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
            _isPaused.value = true
            _isLoading.value = false
            _isSaving.value = false
            _updateVersion.value++
        }
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
