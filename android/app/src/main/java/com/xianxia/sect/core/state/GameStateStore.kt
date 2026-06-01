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

    private val _isPaused = MutableStateFlow(true)
    private val _isLoading = MutableStateFlow(false)
    private val _isSaving = MutableStateFlow(false)

    val warehouseFullEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    val unifiedState: StateFlow<UnifiedGameState> = _state.asStateFlow()

    val gameData: StateFlow<GameData> = _state.map { it.gameData }
        .distinctUntilChanged()
        .stateIn(applicationScopeProvider.scope, SharingStarted.WhileSubscribed(5_000), GameData())

    val disciples: StateFlow<List<Disciple>> = _state.map { it.disciples }
        .distinctUntilChanged()
        .stateIn(applicationScopeProvider.scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val equipmentStacks: StateFlow<List<EquipmentStack>> = _state.map { it.equipmentStacks }
        .distinctUntilChanged()
        .stateIn(applicationScopeProvider.scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val equipmentInstances: StateFlow<List<EquipmentInstance>> = _state.map { it.equipmentInstances }
        .distinctUntilChanged()
        .stateIn(applicationScopeProvider.scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val manualStacks: StateFlow<List<ManualStack>> = _state.map { it.manualStacks }
        .distinctUntilChanged()
        .stateIn(applicationScopeProvider.scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val manualInstances: StateFlow<List<ManualInstance>> = _state.map { it.manualInstances }
        .distinctUntilChanged()
        .stateIn(applicationScopeProvider.scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pills: StateFlow<List<Pill>> = _state.map { it.pills }
        .distinctUntilChanged()
        .stateIn(applicationScopeProvider.scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val materials: StateFlow<List<Material>> = _state.map { it.materials }
        .distinctUntilChanged()
        .stateIn(applicationScopeProvider.scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val herbs: StateFlow<List<Herb>> = _state.map { it.herbs }
        .distinctUntilChanged()
        .stateIn(applicationScopeProvider.scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val seeds: StateFlow<List<Seed>> = _state.map { it.seeds }
        .distinctUntilChanged()
        .stateIn(applicationScopeProvider.scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val storageBags: StateFlow<List<StorageBag>> = _state.map { it.storageBags }
        .distinctUntilChanged()
        .stateIn(applicationScopeProvider.scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val battleLogs: StateFlow<List<BattleLog>> = _state.map { it.battleLogs }
        .distinctUntilChanged()
        .stateIn(applicationScopeProvider.scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val teams: StateFlow<List<ExplorationTeam>> = _state.map { it.teams }
        .distinctUntilChanged()
        .stateIn(applicationScopeProvider.scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pendingBattleResult: StateFlow<BattleResultUIData?> = _state.map { it.pendingBattleResult }
        .distinctUntilChanged()
        .stateIn(applicationScopeProvider.scope, SharingStarted.WhileSubscribed(5_000), null)

    val pendingNotification: StateFlow<GameNotification?> = _state.map { it.pendingNotification }
        .distinctUntilChanged()
        .stateIn(applicationScopeProvider.scope, SharingStarted.WhileSubscribed(5_000), null)

    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    val discipleAggregates: StateFlow<List<DiscipleAggregate>> = _state
        .map { it.disciples }
        .distinctUntilChanged { old, new -> old === new }
        .map { disciples -> disciples.map { it.toAggregate() } }
        .stateIn(applicationScopeProvider.scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private data class CachedPower(
        val fingerprint: Int,
        val power: Long
    )

    private val disciplePowerCache = ConcurrentHashMap<String, CachedPower>()
    private val aiDisciplePowerCache = ConcurrentHashMap<String, CachedPower>()

    private val disciplesFlow = _state
        .map { it.disciples }
        .distinctUntilChanged { old, new -> old === new }

    private val equipmentInstancesFlow = _state
        .map { it.equipmentInstances }
        .distinctUntilChanged { old, new -> old === new }

    private val manualInstancesFlow = _state
        .map { it.manualInstances }
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

    private val aiSectDisciplesFlow = _state
        .map { it.gameData.aiSectDisciples }
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

            // 三路合并：只应用结算的变更，保留玩家在结算期间的操作
            // origin=创建影子时的状态, shadow=结算修改后的影子, oldState=当前主状态(含玩家操作)
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
            val mergedGameData = mergeGameData(origin?.gameData, shadow.gameData, oldState.gameData)

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
        _state.update { it.copy(gameData = update(it.gameData)) }
    }

    fun updateEquipmentStacksDirect(update: (List<EquipmentStack>) -> List<EquipmentStack>) {
        _state.update { it.copy(equipmentStacks = update(it.equipmentStacks)) }
    }

    fun updateEquipmentInstancesDirect(update: (List<EquipmentInstance>) -> List<EquipmentInstance>) {
        _state.update { it.copy(equipmentInstances = update(it.equipmentInstances)) }
    }

    fun updateManualStacksDirect(update: (List<ManualStack>) -> List<ManualStack>) {
        _state.update { it.copy(manualStacks = update(it.manualStacks)) }
    }

    fun updateManualInstancesDirect(update: (List<ManualInstance>) -> List<ManualInstance>) {
        _state.update { it.copy(manualInstances = update(it.manualInstances)) }
    }

    fun updatePillsDirect(update: (List<Pill>) -> List<Pill>) {
        _state.update { it.copy(pills = update(it.pills)) }
    }

    fun updateMaterialsDirect(update: (List<Material>) -> List<Material>) {
        _state.update { it.copy(materials = update(it.materials)) }
    }

    fun updateHerbsDirect(update: (List<Herb>) -> List<Herb>) {
        _state.update { it.copy(herbs = update(it.herbs)) }
    }

    fun updateSeedsDirect(update: (List<Seed>) -> List<Seed>) {
        _state.update { it.copy(seeds = update(it.seeds)) }
    }

    fun updateTeamsDirect(update: (List<ExplorationTeam>) -> List<ExplorationTeam>) {
        _state.update { it.copy(teams = update(it.teams)) }
    }

    fun updateBattleLogsDirect(update: (List<BattleLog>) -> List<BattleLog>) {
        _state.update { it.copy(battleLogs = update(it.battleLogs)) }
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
