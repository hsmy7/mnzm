package com.xianxia.sect.core.state

import android.util.Log
import androidx.compose.runtime.Immutable
import com.xianxia.sect.core.engine.SectCombatPowerCalculator
import com.xianxia.sect.core.model.*
import com.xianxia.sect.data.GameStateRepository
import com.xianxia.sect.di.ApplicationScopeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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

@Singleton
class GameStateStore @Inject constructor(
    private val applicationScopeProvider: ApplicationScopeProvider,
    private val repository: GameStateRepository
) {

    @Volatile
    var focusedDiscipleId: String? = null

    @Volatile
    var activeTab: String = "OVERVIEW"

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

    val warehouseFullEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    // LEGACY: 全量 combine，仅用于 GameEngineCore.state 和 UnifiedGameStateManager
    // 新代码应使用 highFreqState / entityState / configState 或独立 StateFlow
    val unifiedState: StateFlow<UnifiedGameState> = combine(
        _gameDataFlow, _disciplesFlow, _equipmentStacksFlow, _equipmentInstancesFlow,
        _manualStacksFlow, _manualInstancesFlow, _pillsFlow, _materialsFlow,
        _herbsFlow, _seedsFlow, _storageBagsFlow, _teamsFlow, _battleLogsFlow,
        _pendingBattleResultFlow, _pendingNotificationFlow, _isPaused, _isLoading, _isSaving
    ) { args ->
        val gd = args[0] as GameData
        UnifiedGameState(
            gameData = gd,
            disciples = args[1] as List<Disciple>,
            equipmentStacks = args[2] as List<EquipmentStack>,
            equipmentInstances = args[3] as List<EquipmentInstance>,
            manualStacks = args[4] as List<ManualStack>,
            manualInstances = args[5] as List<ManualInstance>,
            pills = args[6] as List<Pill>,
            materials = args[7] as List<Material>,
            herbs = args[8] as List<Herb>,
            seeds = args[9] as List<Seed>,
            storageBags = args[10] as List<StorageBag>,
            teams = args[11] as List<ExplorationTeam>,
            battleLogs = args[12] as List<BattleLog>,
            alliances = gd.alliances,
            isPaused = args[15] as Boolean,
            isLoading = args[16] as Boolean,
            isSaving = args[17] as Boolean,
            pendingBattleResult = args[13] as BattleResultUIData?,
            pendingNotification = args[14] as GameNotification?
        )
    }.stateIn(applicationScopeProvider.scope, SharingStarted.WhileSubscribed(5_000), UnifiedGameState())

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
            val originKeys = origin.manualProficiencies.keys
            val oldKeys = oldState.manualProficiencies.keys
            val result = shadow.manualProficiencies.toMutableMap()
            result.keys.removeAll(originKeys - oldKeys)  // 玩家删除的
            for (key in oldKeys - originKeys) {           // 玩家新增的
                if (key !in result) result[key] = oldState.manualProficiencies[key]!!
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
        "spiritFieldPlants" to { origin, shadow, oldState ->
            val originIds = origin.spiritFieldPlants.map { it.buildingInstanceId }.toSet()
            val oldIds = oldState.spiritFieldPlants.map { it.buildingInstanceId }.toSet()
            val shadowMap = shadow.spiritFieldPlants.associateBy { it.buildingInstanceId }
            val result = shadow.spiritFieldPlants.toMutableList()
            // 玩家在结算期间种植的（oldState 有，origin 无）
            for (plant in oldState.spiritFieldPlants) {
                if (plant.buildingInstanceId !in originIds && plant.buildingInstanceId !in shadowMap) {
                    result.add(plant)
                }
            }
            // 玩家在结算期间移除的（origin 有，oldState 无）
            val removedByPlayer = originIds - oldIds
            if (removedByPlayer.isNotEmpty()) {
                result.filter { it.buildingInstanceId !in removedByPlayer }
            } else {
                result
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
                        val diedInSettlement = originDisciple.isAlive && !shadowDisciple.isAlive
                        val revivedInSettlement = !originDisciple.isAlive && shadowDisciple.isAlive
                        val equipChanged = originDisciple.equipment != shadowDisciple.equipment
                        val combatChanged = originDisciple.combat != shadowDisciple.combat
                        val manualsChanged = originDisciple.manualIds != shadowDisciple.manualIds
                        mainDisciple.copy(
                            cultivation = shadowDisciple.cultivation,
                            realm = shadowDisciple.realm,
                            realmLayer = shadowDisciple.realmLayer,
                            lifespan = shadowDisciple.lifespan,
                            equipment = if (equipChanged) shadowDisciple.equipment else mainDisciple.equipment,
                            combat = if (combatChanged) shadowDisciple.combat else mainDisciple.combat,
                            manualIds = if (manualsChanged) shadowDisciple.manualIds else mainDisciple.manualIds,
                            skills = shadowDisciple.skills,
                            cultivationSpeedBonus = shadowDisciple.cultivationSpeedBonus,
                            cultivationSpeedDuration = shadowDisciple.cultivationSpeedDuration,
                            pillEffects = shadowDisciple.pillEffects,
                            isAlive = when {
                                diedInSettlement || revivedInSettlement -> shadowDisciple.isAlive
                                else -> mainDisciple.isAlive
                            },
                            // 玩家操作字段：始终保留玩家最新值，不被影子覆盖
                            discipleType = mainDisciple.discipleType,
                            status = mainDisciple.status,
                            statusData = mainDisciple.statusData
                        )
                    }
                }
            }

            if (mergedDisciples !== oldDisciples) this.disciples = mergedDisciples
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
    }

    fun setLoadingDirect(loading: Boolean) {
        _isLoading.value = loading
    }

    fun setSavingDirect(saving: Boolean) {
        _isSaving.value = saving
    }

    fun setPendingBattleResult(result: BattleResultUIData) {
        _pendingBattleResultFlow.value = result
    }

    fun clearPendingBattleResult() {
        _pendingBattleResultFlow.value = null
    }

    fun setPendingNotification(notification: GameNotification) {
        _pendingNotificationFlow.value = notification
    }

    fun clearPendingNotification() {
        _pendingNotificationFlow.value = null
    }

    fun updateDisciplesDirect(update: (List<Disciple>) -> List<Disciple>) {
        _disciplesFlow.value = update(_disciplesFlow.value)
        repository.markDirty(disciples = true)
    }

    fun updateGameDataDirect(update: (GameData) -> GameData) {
        _gameDataFlow.value = update(_gameDataFlow.value)
        repository.markDirty(gameData = true)
    }

    fun updateEquipmentStacksDirect(update: (List<EquipmentStack>) -> List<EquipmentStack>) {
        _equipmentStacksFlow.value = update(_equipmentStacksFlow.value)
        repository.markDirty(equipmentStacks = true)
    }

    fun updateEquipmentInstancesDirect(update: (List<EquipmentInstance>) -> List<EquipmentInstance>) {
        _equipmentInstancesFlow.value = update(_equipmentInstancesFlow.value)
        repository.markDirty(equipmentInstances = true)
    }

    fun updateManualStacksDirect(update: (List<ManualStack>) -> List<ManualStack>) {
        _manualStacksFlow.value = update(_manualStacksFlow.value)
        repository.markDirty(manualStacks = true)
    }

    fun updateManualInstancesDirect(update: (List<ManualInstance>) -> List<ManualInstance>) {
        _manualInstancesFlow.value = update(_manualInstancesFlow.value)
        repository.markDirty(manualInstances = true)
    }

    fun updatePillsDirect(update: (List<Pill>) -> List<Pill>) {
        _pillsFlow.value = update(_pillsFlow.value)
        repository.markDirty(pills = true)
    }

    fun updateMaterialsDirect(update: (List<Material>) -> List<Material>) {
        _materialsFlow.value = update(_materialsFlow.value)
        repository.markDirty(materials = true)
    }

    fun updateHerbsDirect(update: (List<Herb>) -> List<Herb>) {
        _herbsFlow.value = update(_herbsFlow.value)
        repository.markDirty(herbs = true)
    }

    fun updateSeedsDirect(update: (List<Seed>) -> List<Seed>) {
        _seedsFlow.value = update(_seedsFlow.value)
        repository.markDirty(seeds = true)
    }

    fun updateTeamsDirect(update: (List<ExplorationTeam>) -> List<ExplorationTeam>) {
        _teamsFlow.value = update(_teamsFlow.value)
        repository.markDirty(teams = true)
    }

    fun updateBattleLogsDirect(update: (List<BattleLog>) -> List<BattleLog>) {
        _battleLogsFlow.value = update(_battleLogsFlow.value)
        repository.markDirty(battleLogs = true)
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
            elderSlots = oldState.elderSlots,
            librarySlots = oldState.librarySlots,
            residenceSlots = oldState.residenceSlots,
            spiritMineSlots = oldState.spiritMineSlots,
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
            travelingMerchantItems = oldState.travelingMerchantItems,
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
        )
    }
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
