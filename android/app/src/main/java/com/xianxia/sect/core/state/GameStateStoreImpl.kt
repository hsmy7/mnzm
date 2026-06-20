package com.xianxia.sect.core.state

import com.xianxia.sect.core.engine.SectCombatPowerCalculator
import com.xianxia.sect.core.model.*
import com.xianxia.sect.data.GameStateRepository
import com.xianxia.sect.di.ApplicationScopeProvider
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
    override var focusedDiscipleId: String? = null

    @Volatile
    override var activeTab: String = "OVERVIEW"

    @Volatile
    override var activeDialog: String? = null

    companion object {
        private const val TAG = "GameStateStore"
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

    private val aggregateCache = ConcurrentHashMap<String, DiscipleAggregate>()

    override val discipleAggregates: StateFlow<List<DiscipleAggregate>> = _disciplesFlow
        .map { disciples ->
            val currentIds = disciples.map { it.id }.toSet()
            aggregateCache.keys.retainAll(currentIds)
            disciples.map { disciple ->
                aggregateCache.getOrPut(disciple.id) { disciple.toAggregate() }
                    .takeIf { it.sourceRef === disciple }
                    ?: disciple.toAggregate().also { aggregateCache[disciple.id] = it }
            }
        }
        .stateIn(applicationScopeProvider.scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    override val discipleAggregatesSnapshot: List<DiscipleAggregate> get() = _disciplesFlow.value.map { it.toAggregate() }

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
    }.distinctUntilChanged()
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

    override fun currentTransactionMutableState(): MutableGameState? = currentTransactionState

    private var shadowOrigin: UnifiedGameState? = null

    // CUSTOM 字段的合并函数（origin, shadow, oldState → 合并后的值）
    // 策略声明见 GameData.kt 各字段的 @SettlementStrategy 注解
    private val customGameDataMergers: Map<String, (GameData, GameData, GameData) -> Any?> = mapOf(
        "worldLevels" to { origin, shadow, oldState ->
            val oldLevelMap = oldState.worldLevels.associateBy { it.id }
            shadow.worldLevels.map { sl ->
                val ol = oldLevelMap[sl.id]
                if (ol != null && ol.defeated && !sl.defeated) sl.copy(defeated = true) else sl
            }
        },
        "worldMapSects" to { origin, shadow, oldState ->
            val originMap = origin.worldMapSects.associateBy { it.id }
            shadow.worldMapSects.map { ss ->
                val os = originMap[ss.id]
                val ms = oldState.worldMapSects.find { it.id == ss.id }
                if (os != null && ms != null) ss.copy(
                    garrisonSlots = ms.garrisonSlots,
                    isPlayerOccupied = ms.isPlayerOccupied,
                    occupierSectId = ms.occupierSectId,
                    occupierBattleTeamId = ms.occupierBattleTeamId
                ) else ss
            }
        },
        "sectDetails" to { origin, shadow, oldState ->
            val originDetails = origin.sectDetails
            val result = shadow.sectDetails.toMutableMap()
            for ((sectId, oldDetail) in oldState.sectDetails) {
                val originDetail = originDetails[sectId]
                val shadowDetail = result[sectId]
                if (originDetail != null && shadowDetail != null) {
                    val tradeChanged = oldDetail.tradeItems !== originDetail.tradeItems
                    val scoutChanged = oldDetail.scoutInfo !== originDetail.scoutInfo
                    if (tradeChanged || scoutChanged) {
                        result[sectId] = shadowDetail.copy(
                            tradeItems = if (tradeChanged) oldDetail.tradeItems else shadowDetail.tradeItems,
                            scoutInfo = if (scoutChanged) oldDetail.scoutInfo else shadowDetail.scoutInfo
                        )
                    }
                }
            }
            result
        },
        "sectRelations" to { origin, shadow, oldState ->
            val originMap = origin.sectRelations.associateBy { "${it.sectId1}_${it.sectId2}" }
            shadow.sectRelations.map { sr ->
                val or = originMap["${sr.sectId1}_${sr.sectId2}"]
                val mr = oldState.sectRelations.find { it.sectId1 == sr.sectId1 && it.sectId2 == sr.sectId2 }
                if (or != null && mr != null) sr.copy(favor = mr.favor + (sr.favor - or.favor))
                else if (mr != null) mr else sr
            }
        },
        "manualProficiencies" to { origin, shadow, oldState ->
            val originMap = origin.manualProficiencies
            val shadowMap = shadow.manualProficiencies
            val oldMap = oldState.manualProficiencies
            val result = mutableMapOf<String, List<ManualProficiencyData>>()

            val allDiscipleIds = (shadowMap.keys + oldMap.keys).toSet()
            for (discipleId in allDiscipleIds) {
                val originList = originMap[discipleId] ?: emptyList()
                val shadowList = shadowMap[discipleId]
                val oldList = oldMap[discipleId]

                if (shadowList == null) {
                    // 结算删除了该弟子的熟练度 → 保留 oldState（玩家操作）
                    if (oldList != null) result[discipleId] = oldList
                    continue
                }
                if (oldList == null) {
                    // 结算新增 → 使用 shadow
                    result[discipleId] = shadowList
                    continue
                }

                // 两边都有：子条目 delta 合并
                val originById = originList.associateBy { it.manualId }
                val shadowById = shadowList.associateBy { it.manualId }
                val oldById = oldList.associateBy { it.manualId }

                val allManualIds = (shadowById.keys + oldById.keys).toSet()
                val merged = allManualIds.mapNotNull { manualId ->
                    val op = originById[manualId]
                    val sp = shadowById[manualId]
                    val mp = oldById[manualId]

                    when {
                        sp != null && mp != null && op != null ->
                            sp.copy(
                                proficiency = mp.proficiency + (sp.proficiency - op.proficiency),
                                level = maxOf(mp.level, sp.level),
                                masteryLevel = maxOf(mp.masteryLevel, sp.masteryLevel)
                            )
                        sp != null && mp != null ->
                            sp.copy(proficiency = maxOf(mp.proficiency, sp.proficiency))
                        sp != null -> sp    // 结算新增
                        mp != null -> mp    // 玩家新增
                        else -> null
                    }
                }
                if (merged.isNotEmpty()) result[discipleId] = merged
            }
            result
        },
        "aiSectDisciples" to { origin, shadow, oldState ->
            val result = shadow.aiSectDisciples.toMutableMap()
            for ((sectId, shadowList) in result.toMap()) {
                val originList = origin.aiSectDisciples[sectId]
                val oldList = oldState.aiSectDisciples[sectId]
                if (originList != null && oldList != null && originList !== oldList) {
                    result[sectId] = oldList
                }
            }
            result
        },
        "bloodRefinements" to { origin, shadow, oldState ->
            // 结算新增的完成记录 + oldState 已有的
            val result = oldState.bloodRefinements.toMutableMap()
            for ((discipleId, materials) in shadow.bloodRefinements) {
                result[discipleId] = (result[discipleId] ?: emptyList()) + materials
            }
            result
        },
        "activeBloodRefinements" to { origin, shadow, oldState ->
            // oldState 做底（保留玩家在结算期间新增的），移除结算完成的
            val result = oldState.activeBloodRefinements.toMutableMap()
            val completedBySettlement = origin.activeBloodRefinements.keys - shadow.activeBloodRefinements.keys
            completedBySettlement.forEach { result.remove(it) }
            result
        },
        "spiritFieldPlants" to { origin, shadow, oldState ->
            val originMap = origin.spiritFieldPlants.associateBy { it.buildingInstanceId }
            val shadowMap = shadow.spiritFieldPlants.associateBy { it.buildingInstanceId }
            val oldMap = oldState.spiritFieldPlants.associateBy { it.buildingInstanceId }

            val allIds = (shadowMap.keys + oldMap.keys).toSet()
            allIds.mapNotNull { id ->
                val op = originMap[id]
                val sp = shadowMap[id]
                val mp = oldMap[id]
                when {
                    sp != null && mp != null && op != null && op != mp ->
                        mp  // 玩家修改了 → 保留 oldState
                    sp != null && mp != null ->
                        sp  // 结算修改 → 保留 shadow
                    sp != null -> sp     // 仅 shadow 有
                    mp != null -> mp     // 仅 oldState 有（玩家新增）
                    else -> null
                }
            }
        },
        "elderSlots" to { origin, shadow, oldState ->
            val o = origin.elderSlots; val s = shadow.elderSlots
            var r = oldState.elderSlots
            if (o.viceSectMaster.isNotEmpty() && s.viceSectMaster.isEmpty()) r = r.copy(viceSectMaster = "")
            if (o.herbGardenElder.isNotEmpty() && s.herbGardenElder.isEmpty()) r = r.copy(herbGardenElder = "")
            if (o.alchemyElder.isNotEmpty() && s.alchemyElder.isEmpty()) r = r.copy(alchemyElder = "")
            if (o.forgeElder.isNotEmpty() && s.forgeElder.isEmpty()) r = r.copy(forgeElder = "")
            if (o.outerElder.isNotEmpty() && s.outerElder.isEmpty()) r = r.copy(outerElder = "")
            if (o.preachingElder.isNotEmpty() && s.preachingElder.isEmpty()) r = r.copy(preachingElder = "")
            if (o.lawEnforcementElder.isNotEmpty() && s.lawEnforcementElder.isEmpty()) r = r.copy(lawEnforcementElder = "")
            if (o.innerElder.isNotEmpty() && s.innerElder.isEmpty()) r = r.copy(innerElder = "")
            if (o.qingyunPreachingElder.isNotEmpty() && s.qingyunPreachingElder.isEmpty()) r = r.copy(qingyunPreachingElder = "")
            r
        },
        "spiritMineSlots" to { origin, shadow, oldState ->
            val oSlots = origin.spiritMineSlots.associateBy { it.index }
            val sSlots = shadow.spiritMineSlots.associateBy { it.index }
            oldState.spiritMineSlots.map { slot ->
                val os = oSlots[slot.index] ?: return@map slot
                val ss = sSlots[slot.index] ?: return@map slot
                if (os.discipleId.isNotEmpty() && ss.discipleId.isEmpty()) slot.copy(discipleId = "", discipleName = "") else slot
            }
        },
        "librarySlots" to { origin, shadow, oldState ->
            val oSlots = origin.librarySlots.associateBy { it.index }
            val sSlots = shadow.librarySlots.associateBy { it.index }
            oldState.librarySlots.map { slot ->
                val os = oSlots[slot.index] ?: return@map slot
                val ss = sSlots[slot.index] ?: return@map slot
                if (os.discipleId.isNotEmpty() && ss.discipleId.isEmpty()) slot.copy(discipleId = "", discipleName = "") else slot
            }
        },
    )

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
        val isSettlement = shadow.isSettlementShadow
        update {
            val oldGameData = this.gameData
            // 结算修改字段（始终同步）
            val oldEquipmentInstances = this.equipmentInstances.items
            val oldPills = this.pills.items
            val oldManualInstances = this.manualInstances.items
            val oldHerbs = this.herbs.items
            val oldSeeds = this.seeds.items
            val oldEquipmentStacks = this.equipmentStacks.items
            val oldMaterials = this.materials.items
            val oldManualStacks = this.manualStacks.items
            // 非结算字段（仅在非结算 shadow 时同步）
            val oldTeams = if (!isSettlement) this.teams else null
            val oldBattleLogs = if (!isSettlement) this.battleLogs else null

            val mergedGameData = mergeGameData(origin?.gameData, shadow.gameData, oldGameData)
            this.gameData = mergedGameData

            // 结算修改字段始终同步（生产消耗/产出）
            if (shadow.equipmentInstances.items !== oldEquipmentInstances) this.equipmentInstances = shadow.equipmentInstances
            if (shadow.pills.items !== oldPills) this.pills = shadow.pills
            if (shadow.manualInstances.items !== oldManualInstances) this.manualInstances = shadow.manualInstances
            if (shadow.herbs.items !== oldHerbs) this.herbs = shadow.herbs
            if (shadow.seeds.items !== oldSeeds) this.seeds = shadow.seeds
            if (shadow.equipmentStacks.items !== oldEquipmentStacks) this.equipmentStacks = shadow.equipmentStacks
            if (shadow.materials.items !== oldMaterials) this.materials = shadow.materials
            if (shadow.manualStacks.items !== oldManualStacks) this.manualStacks = shadow.manualStacks

            // 非结算字段仅在非结算 shadow 时同步
            if (!isSettlement) {
                if (shadow.teams !== oldTeams) this.teams = shadow.teams
                if (shadow.battleLogs !== oldBattleLogs) this.battleLogs = shadow.battleLogs
            }

            this.discipleTables = shadow.discipleTables
        }
        shadowOrigin = null
    }

    override fun beginShadowTransaction(shadow: MutableGameState) {
        currentTransactionState = shadow
        shadowTransactionThread = Thread.currentThread()
    }

    override fun endShadowTransaction() {
        currentTransactionState = null
        shadowTransactionThread = null
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

    private var shadowTransactionThread: Thread? = null

    override suspend fun update(block: suspend MutableGameState.() -> Unit) {
        if (shadowTransactionThread == Thread.currentThread()) {
            check(currentTransactionState == null) {
                "GameStateStore.update() must not be called inside an existing transaction (nested lock). " +
                    "Use currentTransactionMutableState() to modify state within a tick transaction."
            }
        }

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
        if (shadowTransactionThread == Thread.currentThread()) {
            check(currentTransactionState == null) {
                "GameStateStore.updateAndReturn() must not be called inside " +
                    "an existing transaction (nested lock). " +
                    "Use currentTransactionMutableState() to modify state " +
                    "within a tick transaction."
            }
        }

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
            disciplePowerCache.clear()
            aiDisciplePowerCache.clear()
            aggregateCache.clear()
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
        }
    }

    override suspend fun reset() {
        transactionMutex.withLock {
            disciplePowerCache.clear()
            aiDisciplePowerCache.clear()
            aggregateCache.clear()
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

    /**
     * 根据 GameData 各字段的 @SettlementStrategy 注解合并。
     * origin=null 时无结算在进行，直接返回 oldState。
     */
    private fun mergeGameData(origin: GameData?, shadow: GameData, oldState: GameData): GameData {
        if (origin == null) return oldState
        val c = customGameDataMergers

        // THREE_WAY_ID 通用合并：shadow 做底 + 玩家新增 - 玩家删除
        fun <T> threeWayId(
            originList: List<T>, shadowList: List<T>, oldList: List<T>,
            idSelector: (T) -> String
        ): List<T> {
            val originIds = originList.map(idSelector).toSet()
            val shadowIds = shadowList.map(idSelector).toSet()
            val oldIds = oldList.map(idSelector).toSet()
            val result = shadowList.toMutableList()
            // 玩家新增
            for (item in oldList) {
                if (idSelector(item) !in originIds && idSelector(item) !in shadowIds)
                    result.add(item)
            }
            // 玩家删除
            val removedByPlayer = originIds - oldIds
            if (removedByPlayer.isNotEmpty())
                return result.filter { idSelector(it) !in removedByPlayer }
            return result
        }

        return shadow.copy(
            // === PRESERVE_OLD ===
            gameYear = oldState.gameYear,
            gameMonth = oldState.gameMonth,
            gamePhase = oldState.gamePhase,
            gameSpeed = oldState.gameSpeed,
            autoSaveIntervalMonths = oldState.autoSaveIntervalMonths,
            yearlySalary = oldState.yearlySalary,
            yearlySalaryEnabled = oldState.yearlySalaryEnabled,
            placedBuildings = oldState.placedBuildings,
            elderSlots = c.getValue("elderSlots")(origin, shadow, oldState) as ElderSlots,
            librarySlots = c.getValue("librarySlots")(origin, shadow, oldState) as List<LibrarySlot>,
            spiritMineSlots = c.getValue("spiritMineSlots")(origin, shadow, oldState) as List<SpiritMineSlot>,
            residenceSlots = oldState.residenceSlots,
            patrolSlots = oldState.patrolSlots,
            patrolConfig = oldState.patrolConfig,
            patrolConfigs = oldState.patrolConfigs,
            warehouseGarrisons = oldState.warehouseGarrisons,
            sectPolicies = oldState.sectPolicies,
            autoRecruitSpiritRootFilter = oldState.autoRecruitSpiritRootFilter,
            daoCompanionBannedRootCounts = oldState.daoCompanionBannedRootCounts,
            daoCompanionConsentRequired = oldState.daoCompanionConsentRequired,
            breakthroughAutoPillFocused = oldState.breakthroughAutoPillFocused,
            breakthroughAutoPillRootCounts = oldState.breakthroughAutoPillRootCounts,
            autoEquipFromWarehouseFocused = oldState.autoEquipFromWarehouseFocused,
            autoEquipFromWarehouseRootCounts = oldState.autoEquipFromWarehouseRootCounts,
            autoLearnFromWarehouseFocused = oldState.autoLearnFromWarehouseFocused,
            autoLearnFromWarehouseRootCounts = oldState.autoLearnFromWarehouseRootCounts,
            battleTeams = oldState.battleTeams,
            usedTeamNumbers = oldState.usedTeamNumbers,
            // travelingMerchantItems 由年度结算刷新，不保留旧值（Phase_AgingAndDeath 已更新 shadow）
            playerListedItems = oldState.playerListedItems,
            usedRedeemCodes = oldState.usedRedeemCodes,
            activeSectId = oldState.activeSectId,
            patrolBattleResultPopup = oldState.patrolBattleResultPopup,
            playerProtectionEnabled = oldState.playerProtectionEnabled,
            playerProtectionStartYear = oldState.playerProtectionStartYear,
            playerHasAttackedAI = oldState.playerHasAttackedAI,
            productionSlots = oldState.productionSlots,

            // === DELTA ===
            spiritStones = oldState.spiritStones + (shadow.spiritStones - origin.spiritStones),

            // === THREE_WAY_ID ===
            activeMissions = threeWayId(
                origin.activeMissions, shadow.activeMissions, oldState.activeMissions
            ) { (it as ActiveMission).id },
            alliances = threeWayId(
                origin.alliances, shadow.alliances, oldState.alliances
            ) { (it as Alliance).id },
            recruitList = threeWayId(
                origin.recruitList, shadow.recruitList, oldState.recruitList
            ) { (it as Disciple).id },

            // === CUSTOM ===
            worldLevels = c.getValue("worldLevels")(origin, shadow, oldState) as List<WorldLevel>,
            worldMapSects = c.getValue("worldMapSects")(origin, shadow, oldState) as List<WorldSect>,
            sectDetails = c.getValue("sectDetails")(origin, shadow, oldState) as Map<String, SectDetail>,
            sectRelations = c.getValue("sectRelations")(origin, shadow, oldState) as List<SectRelation>,
            manualProficiencies = c.getValue("manualProficiencies")(origin, shadow, oldState) as Map<String, List<ManualProficiencyData>>,
            aiSectDisciples = c.getValue("aiSectDisciples")(origin, shadow, oldState) as Map<String, List<Disciple>>,
            spiritFieldPlants = c.getValue("spiritFieldPlants")(origin, shadow, oldState) as List<SpiritFieldPlant>,
            bloodRefinements = c.getValue("bloodRefinements")(origin, shadow, oldState) as Map<String, List<String>>,
            activeBloodRefinements = c.getValue("activeBloodRefinements")(origin, shadow, oldState) as Map<String, BloodRefinementProgress>,
        )
    }
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
