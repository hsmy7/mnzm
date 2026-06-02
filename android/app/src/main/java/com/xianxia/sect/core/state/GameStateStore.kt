package com.xianxia.sect.core.state

import android.util.Log
import com.xianxia.sect.core.engine.SectCombatPowerCalculator
import com.xianxia.sect.core.model.*
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
import kotlinx.coroutines.flow.update
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
    private val applicationScopeProvider: ApplicationScopeProvider
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

    private val _state = MutableStateFlow(UnifiedGameState())

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

    val unifiedState: StateFlow<UnifiedGameState> = _state.asStateFlow()

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

    // 中间流：直接从独立 MutableStateFlow 派生，不再走 _state.map{} 链
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
        val current = _state.value
        shadowOrigin = current  // 保存原始状态，swap 时用于三路合并
        return MutableGameState(
            gameData = current.gameData,
            disciples = current.disciples,
            equipmentStacks = current.equipmentStacks,
            equipmentInstances = current.equipmentInstances,
            manualStacks = current.manualStacks,
            manualInstances = current.manualInstances,
            pills = current.pills,
            materials = current.materials,
            herbs = current.herbs,
            seeds = current.seeds,
            storageBags = current.storageBags,
            teams = current.teams,
            battleLogs = current.battleLogs,
            isPaused = current.isPaused,
            isLoading = current.isLoading,
            isSaving = current.isSaving,
            pendingNotification = current.pendingNotification
        )
    }

    fun swapFromShadow(shadow: MutableGameState) {
        val origin = shadowOrigin
        _state.update { oldState ->
            val finalPaused = _isPaused.value
            val finalLoading = _isLoading.value
            val finalSaving = _isSaving.value

            val mergedGameData = mergeGameData(origin?.gameData, shadow.gameData, oldState.gameData)
            _gameDataFlow.value = mergedGameData
            // 更新可能在结算中变化的独立流
            if (shadow.teams !== oldState.teams) _teamsFlow.value = shadow.teams
            if (shadow.battleLogs !== oldState.battleLogs) _battleLogsFlow.value = shadow.battleLogs
            val originDiscipleMap = origin?.disciples?.associateBy { it.id } ?: emptyMap()
            val shadowDiscipleMap = shadow.disciples.associateBy { it.id }
            val mergedDisciples = oldState.disciples.mapNotNull { mainDisciple ->
                val shadowDisciple = shadowDiscipleMap[mainDisciple.id]
                if (shadowDisciple == null) {
                    // 弟子不在 shadow 中 → 检查 origin：origin 有但 shadow 无 = 结算删除了(叛逃/处决)
                    if (originDiscipleMap.containsKey(mainDisciple.id)) {
                        null  // 结算删除了 → 从合并结果中移除
                    } else {
                        mainDisciple  // origin 也没有 → 玩家在结算期间新增的 → 保留
                    }
                } else {
                    val originDisciple = originDiscipleMap[mainDisciple.id]
                    if (originDisciple == null || originDisciple === shadowDisciple) {
                        // 结算未修改此弟子 → 保留主状态版本（含玩家操作）
                        mainDisciple
                    } else {
                    // 结算修改了此弟子 → 只应用结算实际变更的字段
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
                        }
                    )
                    // 注意：status, statusData 等字段保留 mainDisciple 的值（玩家可能改了分配）
                }
            }
            }

            // gameData 合并：每个字段的策略见 GameData.kt 的 @SettlementStrategy 注解
            // 测试 GameDataSettlementCoverageTest 反射校验全覆盖，缺少注解 → 编译失败

            // 增量发射：只在结算实际修改的字段上更新独立流
            if (mergedDisciples !== oldState.disciples) _disciplesFlow.value = mergedDisciples

            UnifiedGameState(
                gameData = mergedGameData,
                disciples = mergedDisciples,
                equipmentStacks = oldState.equipmentStacks,
                equipmentInstances = oldState.equipmentInstances,
                manualStacks = oldState.manualStacks,
                manualInstances = oldState.manualInstances,
                pills = oldState.pills,
                materials = oldState.materials,
                herbs = oldState.herbs,
                seeds = oldState.seeds,
                storageBags = oldState.storageBags,
                teams = shadow.teams,
                battleLogs = shadow.battleLogs,
                alliances = mergedGameData.alliances,
                isPaused = finalPaused,
                isLoading = finalLoading,
                isSaving = finalSaving,
                pendingBattleResult = oldState.pendingBattleResult,
                pendingNotification = oldState.pendingNotification
            )
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

    fun getCurrentSeeds(): List<Seed> = _state.value.seeds

    fun getCurrentHerbs(): List<Herb> = _state.value.herbs

    fun getCurrentMaterials(): List<Material> = _state.value.materials

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
        _state.update { it.copy(isPaused = paused) }
    }

    fun setLoadingDirect(loading: Boolean) {
        _isLoading.value = loading
        _state.update { it.copy(isLoading = loading) }
    }

    fun setSavingDirect(saving: Boolean) {
        _isSaving.value = saving
        _state.update { it.copy(isSaving = saving) }
    }

    fun setPendingBattleResult(result: BattleResultUIData) {
        _state.update { it.copy(pendingBattleResult = result) }
    }

    fun clearPendingBattleResult() {
        _state.update { it.copy(pendingBattleResult = null) }
    }

    fun setPendingNotification(notification: GameNotification) {
        _state.update { it.copy(pendingNotification = notification) }
    }

    fun clearPendingNotification() {
        _state.update { it.copy(pendingNotification = null) }
    }

    fun updateDisciplesDirect(update: (List<Disciple>) -> List<Disciple>) {
        _state.update { it.copy(disciples = update(it.disciples)) }
    }

    fun updateGameDataDirect(update: (GameData) -> GameData) {
        _state.update {
            val newGameData = update(it.gameData)
            _gameDataFlow.value = newGameData
            it.copy(gameData = newGameData)
        }
    }

    fun updateEquipmentStacksDirect(update: (List<EquipmentStack>) -> List<EquipmentStack>) {
        _state.update {
            val newValue = update(it.equipmentStacks)
            _equipmentStacksFlow.value = newValue
            it.copy(equipmentStacks = newValue)
        }
    }

    fun updateEquipmentInstancesDirect(update: (List<EquipmentInstance>) -> List<EquipmentInstance>) {
        _state.update {
            val newValue = update(it.equipmentInstances)
            _equipmentInstancesFlow.value = newValue
            it.copy(equipmentInstances = newValue)
        }
    }

    fun updateManualStacksDirect(update: (List<ManualStack>) -> List<ManualStack>) {
        _state.update {
            val newValue = update(it.manualStacks)
            _manualStacksFlow.value = newValue
            it.copy(manualStacks = newValue)
        }
    }

    fun updateManualInstancesDirect(update: (List<ManualInstance>) -> List<ManualInstance>) {
        _state.update {
            val newValue = update(it.manualInstances)
            _manualInstancesFlow.value = newValue
            it.copy(manualInstances = newValue)
        }
    }

    fun updatePillsDirect(update: (List<Pill>) -> List<Pill>) {
        _state.update {
            val newValue = update(it.pills)
            _pillsFlow.value = newValue
            it.copy(pills = newValue)
        }
    }

    fun updateMaterialsDirect(update: (List<Material>) -> List<Material>) {
        _state.update {
            val newValue = update(it.materials)
            _materialsFlow.value = newValue
            it.copy(materials = newValue)
        }
    }

    fun updateHerbsDirect(update: (List<Herb>) -> List<Herb>) {
        _state.update {
            val newValue = update(it.herbs)
            _herbsFlow.value = newValue
            it.copy(herbs = newValue)
        }
    }

    fun updateSeedsDirect(update: (List<Seed>) -> List<Seed>) {
        _state.update {
            val newValue = update(it.seeds)
            _seedsFlow.value = newValue
            it.copy(seeds = newValue)
        }
    }

    fun updateTeamsDirect(update: (List<ExplorationTeam>) -> List<ExplorationTeam>) {
        _state.update {
            val newValue = update(it.teams)
            _teamsFlow.value = newValue
            it.copy(teams = newValue)
        }
    }

    fun updateBattleLogsDirect(update: (List<BattleLog>) -> List<BattleLog>) {
        _state.update {
            val newValue = update(it.battleLogs)
            _battleLogsFlow.value = newValue
            it.copy(battleLogs = newValue)
        }
    }

    // 直接读取快照（绕过 stateIn 的 Dispatchers.Default 调度延迟）
    val gameDataSnapshot: GameData get() = _state.value.gameData
    val discipleAggregatesSnapshot: List<DiscipleAggregate> get() = _state.value.disciples.map { it.toAggregate() }
    val disciplesSnapshot: List<Disciple> get() = _state.value.disciples
    val equipmentStacksSnapshot: List<EquipmentStack> get() = _state.value.equipmentStacks
    val equipmentInstancesSnapshot: List<EquipmentInstance> get() = _state.value.equipmentInstances
    val manualStacksSnapshot: List<ManualStack> get() = _state.value.manualStacks
    val manualInstancesSnapshot: List<ManualInstance> get() = _state.value.manualInstances
    val pillsSnapshot: List<Pill> get() = _state.value.pills
    val materialsSnapshot: List<Material> get() = _state.value.materials
    val herbsSnapshot: List<Herb> get() = _state.value.herbs
    val seedsSnapshot: List<Seed> get() = _state.value.seeds
    val storageBagsSnapshot: List<StorageBag> get() = _state.value.storageBags
    val teamsSnapshot: List<ExplorationTeam> get() = _state.value.teams
    val battleLogsSnapshot: List<BattleLog> get() = _state.value.battleLogs

    private var shadowTransactionThread: Thread? = null

    suspend fun update(block: suspend MutableGameState.() -> Unit) {
        // 仅阻止同一线程内的嵌套调用（结算流程内误调 update 属编程错误），
        // 其他线程（如 UI）直接通过，不等待影子事务
        if (shadowTransactionThread == Thread.currentThread()) {
            check(currentTransactionState == null) {
                "GameStateStore.update() must not be called inside an existing transaction (nested lock). " +
                    "Use currentTransactionMutableState() to modify state within a tick transaction."
            }
        }
        transactionMutex.withLock {
            val current = _state.value
            reusableMutableState.apply {
                gameData = current.gameData
                disciples = current.disciples
                equipmentStacks = current.equipmentStacks
                equipmentInstances = current.equipmentInstances
                manualStacks = current.manualStacks
                manualInstances = current.manualInstances
                pills = current.pills
                materials = current.materials
                herbs = current.herbs
                seeds = current.seeds
                storageBags = current.storageBags
                battleLogs = current.battleLogs
                teams = current.teams
                isPaused = current.isPaused
                isLoading = current.isLoading
                isSaving = current.isSaving
                pendingNotification = current.pendingNotification
            }
            currentTransactionState = reusableMutableState
            try {
                val notificationBeforeBlock = reusableMutableState.pendingNotification
                reusableMutableState.block()
                val blockChangedNotification = reusableMutableState.pendingNotification !== notificationBeforeBlock
                _state.update { oldState ->
                    val finalPaused = if (_isPaused.value != current.isPaused) _isPaused.value else reusableMutableState.isPaused
                    val finalLoading = if (_isLoading.value != current.isLoading) _isLoading.value else reusableMutableState.isLoading
                    val finalSaving = if (_isSaving.value != current.isSaving) _isSaving.value else reusableMutableState.isSaving
                    _isPaused.value = finalPaused
                    _isLoading.value = finalLoading
                    _isSaving.value = finalSaving
                    // 增量发射：只在引用变化时更新独立 MutableStateFlow
                    if (reusableMutableState.gameData !== current.gameData) _gameDataFlow.value = reusableMutableState.gameData
                    if (reusableMutableState.disciples !== current.disciples) _disciplesFlow.value = reusableMutableState.disciples
                    if (reusableMutableState.equipmentStacks !== current.equipmentStacks) _equipmentStacksFlow.value = reusableMutableState.equipmentStacks
                    if (reusableMutableState.equipmentInstances !== current.equipmentInstances) _equipmentInstancesFlow.value = reusableMutableState.equipmentInstances
                    if (reusableMutableState.manualStacks !== current.manualStacks) _manualStacksFlow.value = reusableMutableState.manualStacks
                    if (reusableMutableState.manualInstances !== current.manualInstances) _manualInstancesFlow.value = reusableMutableState.manualInstances
                    if (reusableMutableState.pills !== current.pills) _pillsFlow.value = reusableMutableState.pills
                    if (reusableMutableState.materials !== current.materials) _materialsFlow.value = reusableMutableState.materials
                    if (reusableMutableState.herbs !== current.herbs) _herbsFlow.value = reusableMutableState.herbs
                    if (reusableMutableState.seeds !== current.seeds) _seedsFlow.value = reusableMutableState.seeds
                    if (reusableMutableState.storageBags !== current.storageBags) _storageBagsFlow.value = reusableMutableState.storageBags
                    if (reusableMutableState.teams !== current.teams) _teamsFlow.value = reusableMutableState.teams
                    if (reusableMutableState.battleLogs !== current.battleLogs) _battleLogsFlow.value = reusableMutableState.battleLogs
                    if (blockChangedNotification) _pendingNotificationFlow.value = reusableMutableState.pendingNotification
                    UnifiedGameState(
                        gameData = reusableMutableState.gameData,
                        disciples = reusableMutableState.disciples,
                        equipmentStacks = reusableMutableState.equipmentStacks,
                        equipmentInstances = reusableMutableState.equipmentInstances,
                        manualStacks = reusableMutableState.manualStacks,
                        manualInstances = reusableMutableState.manualInstances,
                        pills = reusableMutableState.pills,
                        materials = reusableMutableState.materials,
                        herbs = reusableMutableState.herbs,
                        seeds = reusableMutableState.seeds,
                        storageBags = reusableMutableState.storageBags,
                        teams = reusableMutableState.teams,
                        battleLogs = reusableMutableState.battleLogs,
                        alliances = reusableMutableState.gameData.alliances,
                        isPaused = finalPaused,
                        isLoading = finalLoading,
                        isSaving = finalSaving,
                        pendingBattleResult = oldState.pendingBattleResult,
                        pendingNotification = if (blockChangedNotification) reusableMutableState.pendingNotification else oldState.pendingNotification
                    )
                }
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
            // 批量更新所有独立流
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
            _state.update {
                UnifiedGameState(
                    gameData = gameData,
                    disciples = disciples,
                    equipmentStacks = equipmentStacks,
                    equipmentInstances = equipmentInstances,
                    manualStacks = manualStacks,
                    manualInstances = manualInstances,
                    pills = pills,
                    materials = materials,
                    herbs = herbs,
                    seeds = seeds,
                    storageBags = emptyList(),
                    teams = teams,
                    battleLogs = battleLogs,
                    alliances = gameData.alliances,
                    isPaused = isPaused,
                    isLoading = isLoading,
                    isSaving = isSaving
                )
            }
            _isPaused.value = isPaused
            _isLoading.value = isLoading
            _isSaving.value = isSaving
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
            _state.update { UnifiedGameState() }
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
