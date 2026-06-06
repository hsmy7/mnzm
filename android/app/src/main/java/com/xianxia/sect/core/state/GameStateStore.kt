package com.xianxia.sect.core.state

import android.util.Log
import androidx.compose.runtime.Immutable
import com.xianxia.sect.core.engine.SectCombatPowerCalculator
import com.xianxia.sect.core.model.*
import com.xianxia.sect.data.GameStateRepository
import com.xianxia.sect.di.ApplicationScopeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
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
import javax.inject.Inject
import javax.inject.Singleton

data class MutableGameState(
    var gameData: GameData,
    var disciples: List<Disciple>,
    var equipmentStacks: List<EquipmentStack>,
    var equipmentInstances: List<EquipmentInstance>,
    var manualStacks: List<ManualStack>,
    var manualInstances: List<ManualInstance>,
    var pills: List<Pill>,
    var materials: List<Material>,
    var herbs: List<Herb>,
    var seeds: List<Seed>,
    var storageBags: List<StorageBag>,
    var teams: List<ExplorationTeam>,
    var battleLogs: List<BattleLog>,
    var isPaused: Boolean,
    var isLoading: Boolean,
    var isSaving: Boolean,
    var pendingNotification: GameNotification? = null
)

@OptIn(FlowPreview::class)
@Singleton
class GameStateStore @Inject constructor(
    private val applicationScopeProvider: ApplicationScopeProvider,
    private val repository: GameStateRepository
) {

    @Volatile
    var focusedDiscipleId: String? = null

    @Volatile
    var activeTab: String = "OVERVIEW"

    @Volatile
    var activeDialog: String? = null

    companion object {
        private const val TAG = "GameStateStore"
    }

    private val transactionMutex = Mutex()

    @Volatile
    private var currentTransactionState: MutableGameState? = null

    // 增量发射：每个字段独立的 MutableStateFlow，只在引用变化时发射
    private val _gameDataFlow = MutableStateFlow(GameData())
    private val _disciplesFlow = MutableStateFlow<List<Disciple>>(emptyList())
    private val _equipmentStacksFlow = MutableStateFlow<List<EquipmentStack>>(emptyList())
    private val _equipmentInstancesFlow = MutableStateFlow<List<EquipmentInstance>>(emptyList())
    private val _manualStacksFlow = MutableStateFlow<List<ManualStack>>(emptyList())
    private val _manualInstancesFlow = MutableStateFlow<List<ManualInstance>>(emptyList())
    private val _pillsFlow = MutableStateFlow<List<Pill>>(emptyList())
    private val _materialsFlow = MutableStateFlow<List<Material>>(emptyList())
    private val _herbsFlow = MutableStateFlow<List<Herb>>(emptyList())
    private val _seedsFlow = MutableStateFlow<List<Seed>>(emptyList())
    private val _storageBagsFlow = MutableStateFlow<List<StorageBag>>(emptyList())
    private val _battleLogsFlow = MutableStateFlow<List<BattleLog>>(emptyList())
    private val _teamsFlow = MutableStateFlow<List<ExplorationTeam>>(emptyList())
    private val _pendingBattleResultFlow = MutableStateFlow<BattleResultUIData?>(null)
    private val _pendingNotificationFlow = MutableStateFlow<GameNotification?>(null)

    private val _isPaused = MutableStateFlow(true)
    private val _isLoading = MutableStateFlow(false)
    private val _isSaving = MutableStateFlow(false)

    // 版本计数器：每次 update() 有字段变化时递增，用于 unifiedState 批处理触发
    private val _updateVersion = MutableStateFlow(0L)

    val warehouseFullEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    // LEGACY: 按版本触发的批处理模式，50ms 内多次更新合并为一次
    // 新代码应使用 highFreqState / entityState / configState 或独立 StateFlow
    val unifiedState: StateFlow<UnifiedGameState> = _updateVersion
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
    val gameData: StateFlow<GameData> = _gameDataFlow.asStateFlow()
    val disciples: StateFlow<List<Disciple>> = _disciplesFlow.asStateFlow()
    val equipmentStacks: StateFlow<List<EquipmentStack>> = _equipmentStacksFlow.asStateFlow()
    val equipmentInstances: StateFlow<List<EquipmentInstance>> = _equipmentInstancesFlow.asStateFlow()
    val manualStacks: StateFlow<List<ManualStack>> = _manualStacksFlow.asStateFlow()
    val manualInstances: StateFlow<List<ManualInstance>> = _manualInstancesFlow.asStateFlow()
    val pills: StateFlow<List<Pill>> = _pillsFlow.asStateFlow()
    val materials: StateFlow<List<Material>> = _materialsFlow.asStateFlow()
    val herbs: StateFlow<List<Herb>> = _herbsFlow.asStateFlow()
    val seeds: StateFlow<List<Seed>> = _seedsFlow.asStateFlow()
    val storageBags: StateFlow<List<StorageBag>> = _storageBagsFlow.asStateFlow()
    val battleLogs: StateFlow<List<BattleLog>> = _battleLogsFlow.asStateFlow()
    val teams: StateFlow<List<ExplorationTeam>> = _teamsFlow.asStateFlow()
    val pendingBattleResult: StateFlow<BattleResultUIData?> = _pendingBattleResultFlow.asStateFlow()
    val pendingNotification: StateFlow<GameNotification?> = _pendingNotificationFlow.asStateFlow()

    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    // === 三层 StateFlow 架构 ===
    // HighFreq: 高频变化字段，sample 降频
    @Immutable
    data class HighFreqState(
        val spiritStones: Long = 0L,
        val gameYear: Int = 1,
        val gameMonth: Int = 1,
        val gamePhase: Int = 1,
        val isPaused: Boolean = true
    )

    val highFreqState: StateFlow<HighFreqState> = combine(
        _gameDataFlow.map { it.spiritStones }.distinctUntilChanged(),
        _gameDataFlow.map { it.gameYear }.distinctUntilChanged(),
        _gameDataFlow.map { it.gameMonth }.distinctUntilChanged(),
        _gameDataFlow.map { it.gamePhase }.distinctUntilChanged(),
        _isPaused
    ) { spiritStones, year, month, phase, paused ->
        HighFreqState(spiritStones, year, month, phase, paused)
    }.stateIn(applicationScopeProvider.scope, SharingStarted.WhileSubscribed(5_000), HighFreqState())

    // EntityFlow: 实体数据，distinctUntilChanged
    data class EntityState(
        val disciples: List<Disciple> = emptyList(),
        val equipmentStacks: List<EquipmentStack> = emptyList(),
        val equipmentInstances: List<EquipmentInstance> = emptyList(),
        val manualStacks: List<ManualStack> = emptyList(),
        val manualInstances: List<ManualInstance> = emptyList(),
        val pills: List<Pill> = emptyList(),
        val materials: List<Material> = emptyList(),
        val herbs: List<Herb> = emptyList(),
        val seeds: List<Seed> = emptyList(),
        val storageBags: List<StorageBag> = emptyList(),
        val teams: List<ExplorationTeam> = emptyList(),
        val battleLogs: List<BattleLog> = emptyList()
    )

    val entityState: StateFlow<EntityState> = combine(
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
        EntityState(
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
    }.stateIn(applicationScopeProvider.scope, SharingStarted.WhileSubscribed(5_000), EntityState())

    // ConfigFlow: 配置数据，从 gameData 派生，distinctUntilChanged
    data class ConfigState(
        val sectPolicies: SectPolicies = SectPolicies(),
        val monthlySalary: Map<Int, Int> = emptyMap(),
        val monthlySalaryEnabled: Map<Int, Boolean> = emptyMap(),
        val elderSlots: ElderSlots? = null,
        val placedBuildings: List<GridBuildingData> = emptyList(),
        val autoRecruitSpiritRootFilter: Set<Int> = emptySet(),
        val gameSpeed: Int = 1,
        val autoSaveIntervalMonths: Int = 3
    )

    val configState: StateFlow<ConfigState> = _gameDataFlow
        .map { gd ->
            ConfigState(
                sectPolicies = gd.sectPolicies,
                monthlySalary = gd.monthlySalary,
                monthlySalaryEnabled = gd.monthlySalaryEnabled,
                elderSlots = gd.elderSlots,
                placedBuildings = gd.placedBuildings,
                autoRecruitSpiritRootFilter = gd.autoRecruitSpiritRootFilter,
                gameSpeed = gd.gameSpeed,
                autoSaveIntervalMonths = gd.autoSaveIntervalMonths
            )
        }
        .distinctUntilChanged()
        .stateIn(applicationScopeProvider.scope, SharingStarted.WhileSubscribed(5_000), ConfigState())

    private val aggregateCache = ConcurrentHashMap<String, DiscipleAggregate>()

    val discipleAggregates: StateFlow<List<DiscipleAggregate>> = _disciplesFlow
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

    val sectCombatPower: StateFlow<Long> = combine(
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

    val aiSectCombatPowers: StateFlow<Map<String, Long>> = aiSectDisciplesFlow
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

    internal fun isInTransaction(): Boolean = currentTransactionState != null

    internal fun currentTransactionMutableState(): MutableGameState? = currentTransactionState

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

    fun createShadow(): MutableGameState {
        val gd = _gameDataFlow.value
        val disc = _disciplesFlow.value
        val es = _equipmentStacksFlow.value
        val ei = _equipmentInstancesFlow.value
        val ms = _manualStacksFlow.value
        val mi = _manualInstancesFlow.value
        val p = _pillsFlow.value
        val mat = _materialsFlow.value
        val h = _herbsFlow.value
        val s = _seedsFlow.value
        val sb = _storageBagsFlow.value
        val t = _teamsFlow.value
        val bl = _battleLogsFlow.value
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
            storageBags = sb,
            teams = t,
            battleLogs = bl,
            alliances = gd.alliances,
            isPaused = _isPaused.value,
            isLoading = _isLoading.value,
            isSaving = _isSaving.value,
            pendingNotification = _pendingNotificationFlow.value
        )
        shadowOrigin = snapshot
        return MutableGameState(
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
            storageBags = sb,
            teams = t,
            battleLogs = bl,
            isPaused = _isPaused.value,
            isLoading = _isLoading.value,
            isSaving = _isSaving.value,
            pendingNotification = _pendingNotificationFlow.value
        )
    }

    suspend fun swapFromShadow(shadow: MutableGameState) {
        val origin = shadowOrigin
        update {
            val oldGameData = this.gameData
            val oldDisciples = this.disciples
            val oldTeams = this.teams
            val oldBattleLogs = this.battleLogs
            // 物品列表旧引用 — 月度结算中生产/消耗的物品变更需要传播回 live state
            val oldHerbs = this.herbs
            val oldSeeds = this.seeds
            val oldEquipmentStacks = this.equipmentStacks
            val oldEquipmentInstances = this.equipmentInstances
            val oldPills = this.pills
            val oldMaterials = this.materials
            val oldManualStacks = this.manualStacks
            val oldManualInstances = this.manualInstances

            val mergedGameData = mergeGameData(origin?.gameData, shadow.gameData, oldGameData)
            this.gameData = mergedGameData

            if (shadow.teams !== oldTeams) this.teams = shadow.teams
            if (shadow.battleLogs !== oldBattleLogs) this.battleLogs = shadow.battleLogs

            val originDiscipleMap = origin?.disciples?.associateBy { it.id } ?: emptyMap()
            val shadowDiscipleMap = shadow.disciples.associateBy { it.id }
            val mergedDisciples = oldDisciples.mapNotNull { mainDisciple ->
                val shadowDisciple = shadowDiscipleMap[mainDisciple.id]
                if (shadowDisciple == null) {
                    if (originDiscipleMap.containsKey(mainDisciple.id)) {
                        null
                    } else {
                        mainDisciple
                    }
                } else {
                    val originDisciple = originDiscipleMap[mainDisciple.id]
                    if (originDisciple == null || originDisciple === shadowDisciple) {
                        mainDisciple
                    } else {
                        mergeDiscipleAfterSettlement(mainDisciple, shadowDisciple, originDisciple)
                    }
                }
            }

            if (mergedDisciples !== oldDisciples) this.disciples = mergedDisciples

            // 传播物品列表变更：月度结算中的收获/消耗必须同步回 live state
            if (shadow.herbs !== oldHerbs) this.herbs = shadow.herbs
            if (shadow.seeds !== oldSeeds) this.seeds = shadow.seeds
            if (shadow.equipmentStacks !== oldEquipmentStacks) this.equipmentStacks = shadow.equipmentStacks
            if (shadow.equipmentInstances !== oldEquipmentInstances) this.equipmentInstances = shadow.equipmentInstances
            if (shadow.pills !== oldPills) this.pills = shadow.pills
            if (shadow.materials !== oldMaterials) this.materials = shadow.materials
            if (shadow.manualStacks !== oldManualStacks) this.manualStacks = shadow.manualStacks
            if (shadow.manualInstances !== oldManualInstances) this.manualInstances = shadow.manualInstances
        }
        shadowOrigin = null
    }

    fun beginShadowTransaction(shadow: MutableGameState) {
        currentTransactionState = shadow
        shadowTransactionThread = Thread.currentThread()
    }

    fun endShadowTransaction() {
        currentTransactionState = null
        shadowTransactionThread = null
    }

    fun getCurrentSeeds(): List<Seed> = _seedsFlow.value

    fun getCurrentHerbs(): List<Herb> = _herbsFlow.value

    fun getCurrentMaterials(): List<Material> = _materialsFlow.value

    private val reusableMutableState = MutableGameState(
        gameData = GameData(),
        disciples = emptyList(),
        equipmentStacks = emptyList(),
        equipmentInstances = emptyList(),
        manualStacks = emptyList(),
        manualInstances = emptyList(),
        pills = emptyList(),
        materials = emptyList(),
        herbs = emptyList(),
        seeds = emptyList(),
        storageBags = emptyList(),
        battleLogs = emptyList(),
        teams = emptyList(),
        isPaused = true,
        isLoading = false,
        isSaving = false,
        pendingNotification = null
    )

    fun setPausedDirect(paused: Boolean) {
        _isPaused.value = paused
        _updateVersion.value++
    }

    fun setLoadingDirect(loading: Boolean) {
        _isLoading.value = loading
        _updateVersion.value++
    }

    fun setSavingDirect(saving: Boolean) {
        _isSaving.value = saving
        _updateVersion.value++
    }

    fun setPendingBattleResult(result: BattleResultUIData) {
        _pendingBattleResultFlow.value = result
        _updateVersion.value++
    }

    fun clearPendingBattleResult() {
        _pendingBattleResultFlow.value = null
        _updateVersion.value++
    }

    fun setPendingNotification(notification: GameNotification) {
        _pendingNotificationFlow.value = notification
        _updateVersion.value++
    }

    fun clearPendingNotification() {
        _pendingNotificationFlow.value = null
        _updateVersion.value++
    }

    // 直接读取快照（绕过 stateIn 的 Dispatchers.Default 调度延迟）
    val gameDataSnapshot: GameData get() = _gameDataFlow.value
    val discipleAggregatesSnapshot: List<DiscipleAggregate> get() = _disciplesFlow.value.map { it.toAggregate() }
    val disciplesSnapshot: List<Disciple> get() = _disciplesFlow.value
    val equipmentStacksSnapshot: List<EquipmentStack> get() = _equipmentStacksFlow.value
    val equipmentInstancesSnapshot: List<EquipmentInstance> get() = _equipmentInstancesFlow.value
    val manualStacksSnapshot: List<ManualStack> get() = _manualStacksFlow.value
    val manualInstancesSnapshot: List<ManualInstance> get() = _manualInstancesFlow.value
    val pillsSnapshot: List<Pill> get() = _pillsFlow.value
    val materialsSnapshot: List<Material> get() = _materialsFlow.value
    val herbsSnapshot: List<Herb> get() = _herbsFlow.value
    val seedsSnapshot: List<Seed> get() = _seedsFlow.value
    val storageBagsSnapshot: List<StorageBag> get() = _storageBagsFlow.value
    val teamsSnapshot: List<ExplorationTeam> get() = _teamsFlow.value
    val battleLogsSnapshot: List<BattleLog> get() = _battleLogsFlow.value

    private var shadowTransactionThread: Thread? = null

    suspend fun update(block: suspend MutableGameState.() -> Unit) {
        if (shadowTransactionThread == Thread.currentThread()) {
            check(currentTransactionState == null) {
                "GameStateStore.update() must not be called inside an existing transaction (nested lock). " +
                    "Use currentTransactionMutableState() to modify state within a tick transaction."
            }
        }
        transactionMutex.withLock {
            val curGame = _gameDataFlow.value
            val curDisc = _disciplesFlow.value
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
                disciples = curDisc
                equipmentStacks = curES
                equipmentInstances = curEI
                manualStacks = curMS
                manualInstances = curMI
                pills = curP
                materials = curMat
                herbs = curH
                seeds = curS
                storageBags = curSB
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
                if (reusableMutableState.disciples !== curDisc) _disciplesFlow.value = reusableMutableState.disciples
                if (reusableMutableState.equipmentStacks !== curES) _equipmentStacksFlow.value = reusableMutableState.equipmentStacks
                if (reusableMutableState.equipmentInstances !== curEI) _equipmentInstancesFlow.value = reusableMutableState.equipmentInstances
                if (reusableMutableState.manualStacks !== curMS) _manualStacksFlow.value = reusableMutableState.manualStacks
                if (reusableMutableState.manualInstances !== curMI) _manualInstancesFlow.value = reusableMutableState.manualInstances
                if (reusableMutableState.pills !== curP) _pillsFlow.value = reusableMutableState.pills
                if (reusableMutableState.materials !== curMat) _materialsFlow.value = reusableMutableState.materials
                if (reusableMutableState.herbs !== curH) _herbsFlow.value = reusableMutableState.herbs
                if (reusableMutableState.seeds !== curS) _seedsFlow.value = reusableMutableState.seeds
                if (reusableMutableState.storageBags !== curSB) _storageBagsFlow.value = reusableMutableState.storageBags
                if (reusableMutableState.teams !== curT) _teamsFlow.value = reusableMutableState.teams
                if (reusableMutableState.battleLogs !== curBL) _battleLogsFlow.value = reusableMutableState.battleLogs
                if (blockChangedNotification) _pendingNotificationFlow.value = reusableMutableState.pendingNotification
                repository.markDirty(
                    gameData = reusableMutableState.gameData !== curGame,
                    disciples = reusableMutableState.disciples !== curDisc,
                    equipmentStacks = reusableMutableState.equipmentStacks !== curES,
                    equipmentInstances = reusableMutableState.equipmentInstances !== curEI,
                    manualStacks = reusableMutableState.manualStacks !== curMS,
                    manualInstances = reusableMutableState.manualInstances !== curMI,
                    pills = reusableMutableState.pills !== curP,
                    materials = reusableMutableState.materials !== curMat,
                    herbs = reusableMutableState.herbs !== curH,
                    seeds = reusableMutableState.seeds !== curS,
                    storageBags = reusableMutableState.storageBags !== curSB,
                    teams = reusableMutableState.teams !== curT,
                    battleLogs = reusableMutableState.battleLogs !== curBL
                )
                // 仅在有字段变化时递增版本号，触发 unifiedState 批处理重建
                val anyFieldChanged = reusableMutableState.gameData !== curGame
                    || reusableMutableState.disciples !== curDisc
                    || reusableMutableState.equipmentStacks !== curES
                    || reusableMutableState.equipmentInstances !== curEI
                    || reusableMutableState.manualStacks !== curMS
                    || reusableMutableState.manualInstances !== curMI
                    || reusableMutableState.pills !== curP
                    || reusableMutableState.materials !== curMat
                    || reusableMutableState.herbs !== curH
                    || reusableMutableState.seeds !== curS
                    || reusableMutableState.storageBags !== curSB
                    || reusableMutableState.teams !== curT
                    || reusableMutableState.battleLogs !== curBL
                    || finalPaused != curPaused
                    || finalLoading != curLoading
                    || finalSaving != curSaving
                    || blockChangedNotification
                if (anyFieldChanged) _updateVersion.value++
            } finally {
                currentTransactionState = null
            }
        }
    }

    suspend fun loadFromSnapshot(
        gameData: GameData,
        disciples: List<Disciple>,
        equipmentStacks: List<EquipmentStack> = emptyList(),
        equipmentInstances: List<EquipmentInstance> = emptyList(),
        manualStacks: List<ManualStack> = emptyList(),
        manualInstances: List<ManualInstance> = emptyList(),
        pills: List<Pill>,
        materials: List<Material>,
        herbs: List<Herb>,
        seeds: List<Seed>,
        storageBags: List<StorageBag> = emptyList(),
        teams: List<ExplorationTeam>,
        battleLogs: List<BattleLog>,
        isPaused: Boolean = true,
        isLoading: Boolean = false,
        isSaving: Boolean = false
    ) {
        transactionMutex.withLock {
            disciplePowerCache.clear()
            aiDisciplePowerCache.clear()
            aggregateCache.clear()
            _gameDataFlow.value = gameData
            _disciplesFlow.value = disciples
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

    suspend fun reset() {
        transactionMutex.withLock {
            disciplePowerCache.clear()
            aiDisciplePowerCache.clear()
            aggregateCache.clear()
            _gameDataFlow.value = GameData()
            _disciplesFlow.value = emptyList()
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
            monthlySalary = oldState.monthlySalary,
            monthlySalaryEnabled = oldState.monthlySalaryEnabled,
            placedBuildings = oldState.placedBuildings,
            elderSlots = c["elderSlots"]!!(origin, shadow, oldState) as ElderSlots,
            librarySlots = c["librarySlots"]!!(origin, shadow, oldState) as List<LibrarySlot>,
            spiritMineSlots = c["spiritMineSlots"]!!(origin, shadow, oldState) as List<SpiritMineSlot>,
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
            recruitList = threeWayId(
                origin.recruitList, shadow.recruitList, oldState.recruitList
            ) { (it as Disciple).id },
            activeMissions = threeWayId(
                origin.activeMissions, shadow.activeMissions, oldState.activeMissions
            ) { (it as ActiveMission).id },
            alliances = threeWayId(
                origin.alliances, shadow.alliances, oldState.alliances
            ) { (it as Alliance).id },

            // === CUSTOM ===
            worldLevels = c["worldLevels"]!!(origin, shadow, oldState) as List<WorldLevel>,
            worldMapSects = c["worldMapSects"]!!(origin, shadow, oldState) as List<WorldSect>,
            sectDetails = c["sectDetails"]!!(origin, shadow, oldState) as Map<String, SectDetail>,
            sectRelations = c["sectRelations"]!!(origin, shadow, oldState) as List<SectRelation>,
            manualProficiencies = c["manualProficiencies"]!!(origin, shadow, oldState) as Map<String, List<ManualProficiencyData>>,
            aiSectDisciples = c["aiSectDisciples"]!!(origin, shadow, oldState) as Map<String, List<Disciple>>,
            spiritFieldPlants = c["spiritFieldPlants"]!!(origin, shadow, oldState) as List<SpiritFieldPlant>,
            bloodRefinements = c["bloodRefinements"]!!(origin, shadow, oldState) as Map<String, List<String>>,
            activeBloodRefinements = c["activeBloodRefinements"]!!(origin, shadow, oldState) as Map<String, BloodRefinementProgress>,
        )
    }
}

    // ==================== 子字段级合并函数 ====================

    /**
     * EquipmentSet 子字段合并。
     * 结算域（nurture×4）：总是从 shadow。玩家域（weaponId 等）：总是从 main。
     * 共享域（storageBagItems）：main 做底 + 结算新增 - 结算删除。
     */
    private fun mergeEquipment(
        main: EquipmentSet, shadow: EquipmentSet, origin: EquipmentSet
    ): EquipmentSet {
        val originBagIds = origin.storageBagItems.map { it.itemId }.toSet()
        val shadowBagIds = shadow.storageBagItems.map { it.itemId }.toSet()
        val addedBySettlement = shadow.storageBagItems.filter { it.itemId !in originBagIds }
        val removedBySettlement = originBagIds - shadowBagIds
        val mergedBag = (main.storageBagItems + addedBySettlement)
            .filter { it.itemId !in removedBySettlement }

        return main.copy(
            weaponNurture = shadow.weaponNurture,
            armorNurture = shadow.armorNurture,
            bootsNurture = shadow.bootsNurture,
            accessoryNurture = shadow.accessoryNurture,
            storageBagItems = mergedBag,
        )
    }

    /**
     * CombatAttributes 子字段合并。
     * baseXxx/variance/统计 从 shadow。currentHp/currentMp 仅在结算发生突破时从 shadow。
     */
    private fun mergeCombat(
        main: CombatAttributes, shadow: CombatAttributes, origin: CombatAttributes
    ): CombatAttributes {
        // 突破是唯一会修改 baseHp/baseMp 的操作
        val baseStatsChanged = shadow.baseHp != origin.baseHp || shadow.baseMp != origin.baseMp

        return main.copy(
            baseHp = shadow.baseHp,
            baseMp = shadow.baseMp,
            basePhysicalAttack = shadow.basePhysicalAttack,
            baseMagicAttack = shadow.baseMagicAttack,
            basePhysicalDefense = shadow.basePhysicalDefense,
            baseMagicDefense = shadow.baseMagicDefense,
            baseSpeed = shadow.baseSpeed,
            hpVariance = shadow.hpVariance,
            mpVariance = shadow.mpVariance,
            physicalAttackVariance = shadow.physicalAttackVariance,
            magicAttackVariance = shadow.magicAttackVariance,
            physicalDefenseVariance = shadow.physicalDefenseVariance,
            magicDefenseVariance = shadow.magicDefenseVariance,
            speedVariance = shadow.speedVariance,
            totalCultivation = shadow.totalCultivation,
            breakthroughCount = shadow.breakthroughCount,
            breakthroughFailCount = shadow.breakthroughFailCount,
            currentHp = if (baseStatsChanged) shadow.currentHp else main.currentHp,
            currentMp = if (baseStatsChanged) shadow.currentMp else main.currentMp,
        )
    }

    /**
     * manualIds 集合合并：main + 结算新增 - 结算删除。
     */
    private fun mergeManualIds(
        main: List<String>, shadow: List<String>, origin: List<String>
    ): List<String> {
        val originSet = origin.toSet()
        val shadowSet = shadow.toSet()
        return (main.toSet() + (shadowSet - originSet) - (originSet - shadowSet)).toList()
    }

    /**
     * PillEffects 子字段合并。
     * 13 个 bonus 字段从 main（只有玩家用丹药修改），pillEffectDuration 做 delta 合并。
     */
    private fun mergePillEffects(
        main: PillEffects, shadow: PillEffects, origin: PillEffects
    ): PillEffects {
        val durationDelta = shadow.pillEffectDuration - origin.pillEffectDuration
        return main.copy(
            pillEffectDuration = (main.pillEffectDuration + durationDelta).coerceAtLeast(0)
        )
    }

    /**
     * Skills 子字段合并。
     * loyalty/salaryPaidCount/salaryMissedCount 从 shadow（结算修改），其余从 main。
     */
    private fun mergeSkills(
        main: SkillStats, shadow: SkillStats, origin: SkillStats
    ): SkillStats {
        return main.copy(
            loyalty = shadow.loyalty,
            salaryPaidCount = shadow.salaryPaidCount,
            salaryMissedCount = shadow.salaryMissedCount,
        )
    }

    // ==================== 弟子结算合并 ====================

    /**
     * 结算后合并弟子状态：将影子结算结果应用到主状态，同时保留玩家的操作。
     *
     * ## 字段分类
     *
     * **结算修改字段（从 shadow 取值）**：
     * | 字段 | 说明 |
     * |------|------|
     * | cultivation, realm, realmLayer | 修炼进度、境界 |
     * | lifespan | 寿命消耗 |
     * | equipment (conditional) | 装备养成变化 |
     * | combat (conditional) | 战斗属性变化 |
     * | manualIds (conditional) | 功法变化 |
     * | skills | 技能属性变化 |
     * | cultivationSpeedBonus / Duration | 修炼buff |
     * | pillEffects | 丹药效果变化 |
     * | isAlive | 死亡/复活 |
     *
     * **玩家操作字段（显式保留主状态值）**：
     * | 字段 | 说明 |
     * |------|------|
     * | discipleType | 内门/外门切换 |
     * | status | 弟子状态（分配任务等） |
     * | statusData | 状态数据 |
     *
     * **默认保留字段（copy() 自动保留，无需显式声明）**：
     * autoLearnFromWarehouse, soulPower, talentIds, manualMasteries,
     * gender, portraitRes, name, surname, spiritRootType, social, usage, age, slotId, id
     *
     * **⚠️ 新增 Disciple 字段时**：判断该字段属于上述哪一类，
     * 若是结算修改字段 → 加入 copy() 参数列表；
     * 若是玩家操作字段 → 加入显式保留列表。
     */
    private fun mergeDiscipleAfterSettlement(
        mainDisciple: Disciple,
        shadowDisciple: Disciple,
        originDisciple: Disciple
    ): Disciple {
        val died = originDisciple.isAlive && !shadowDisciple.isAlive
        val revived = !originDisciple.isAlive && shadowDisciple.isAlive

        return mainDisciple.copy(
            // 标量 delta 合并
            cultivation = mainDisciple.cultivation + (shadowDisciple.cultivation - originDisciple.cultivation),
            lifespan = mainDisciple.lifespan + (shadowDisciple.lifespan - originDisciple.lifespan),

            // 无条件 shadow（仅结算修改）
            realm = shadowDisciple.realm,
            realmLayer = shadowDisciple.realmLayer,

            // 条件保留（玩家可能用丹药修改）
            cultivationSpeedBonus = if (mainDisciple.cultivationSpeedBonus != originDisciple.cultivationSpeedBonus)
                mainDisciple.cultivationSpeedBonus else shadowDisciple.cultivationSpeedBonus,
            cultivationSpeedDuration = if (mainDisciple.cultivationSpeedDuration != originDisciple.cultivationSpeedDuration)
                mainDisciple.cultivationSpeedDuration else shadowDisciple.cultivationSpeedDuration,

            // 子字段级合并
            equipment = mergeEquipment(mainDisciple.equipment, shadowDisciple.equipment, originDisciple.equipment),
            combat = mergeCombat(mainDisciple.combat, shadowDisciple.combat, originDisciple.combat),
            manualIds = mergeManualIds(mainDisciple.manualIds, shadowDisciple.manualIds, originDisciple.manualIds),
            pillEffects = mergePillEffects(mainDisciple.pillEffects, shadowDisciple.pillEffects, originDisciple.pillEffects),
            skills = mergeSkills(mainDisciple.skills, shadowDisciple.skills, originDisciple.skills),

            // 条件覆盖（已有模式）
            isAlive = if (died || revived) shadowDisciple.isAlive else mainDisciple.isAlive,

            // 玩家操作字段
            discipleType = mainDisciple.discipleType,
            status = mainDisciple.status,
            statusData = mainDisciple.statusData,
        )
    }

    fun fixStorageBagReferences(
    equipmentStacks: List<EquipmentStack>,
    equipmentInstances: List<EquipmentInstance>,
    manualStacks: List<ManualStack>,
    manualInstances: List<ManualInstance>,
    disciples: List<Disciple>
): List<Disciple> {
    val stackIds = equipmentStacks.map { it.id }.toSet()
    val instanceIds = equipmentInstances.map { it.id }.toSet()
    val manualStackIds = manualStacks.map { it.id }.toSet()
    val manualInstanceIds = manualInstances.map { it.id }.toSet()

    return disciples.map { disciple ->
        val fixedItems = disciple.equipment.storageBagItems.map { item ->
            when {
                item.itemType == "equipment" -> {
                    when {
                        instanceIds.contains(item.itemId) -> item.copy(itemType = "equipment_instance")
                        stackIds.contains(item.itemId) -> item.copy(itemType = "equipment_stack")
                        else -> item
                    }
                }
                item.itemType == "manual" -> {
                    when {
                        manualInstanceIds.contains(item.itemId) -> item.copy(itemType = "manual_instance")
                        manualStackIds.contains(item.itemId) -> item.copy(itemType = "manual_stack")
                        else -> item
                    }
                }
                else -> item
            }
        }
        disciple.copyWith(storageBagItems = fixedItems)
    }
}
