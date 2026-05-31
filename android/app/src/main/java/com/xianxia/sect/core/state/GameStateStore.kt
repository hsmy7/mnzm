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
            val mergedDisciples = oldState.disciples.map { mainDisciple ->
                val shadowDisciple = shadow.disciples.find { it.id == mainDisciple.id }
                    ?: return@map mainDisciple
                val originDisciple = originDiscipleMap[mainDisciple.id]
                if (originDisciple == null || originDisciple === shadowDisciple) {
                    // 结算未修改此弟子 → 保留主状态版本（含玩家操作）
                    mainDisciple
                } else {
                    // 结算修改了此弟子 → 只应用结算相关的字段
                    mainDisciple.copy(
                        cultivation = shadowDisciple.cultivation,
                        realm = shadowDisciple.realm,
                        realmLayer = shadowDisciple.realmLayer,
                        lifespan = shadowDisciple.lifespan,
                        equipment = shadowDisciple.equipment,
                        combat = shadowDisciple.combat,
                        skills = shadowDisciple.skills,
                        manualIds = shadowDisciple.manualIds,
                        cultivationSpeedBonus = shadowDisciple.cultivationSpeedBonus,
                        cultivationSpeedDuration = shadowDisciple.cultivationSpeedDuration,
                        pillEffects = shadowDisciple.pillEffects,
                        maxHp = shadowDisciple.maxHp,
                        maxMp = shadowDisciple.maxMp,
                        isAlive = shadowDisciple.isAlive
                    )
                    // 注意：status, statusData 等字段保留 mainDisciple 的值（玩家可能改了分配）
                }
            }

            // gameData 合并：从 shadow 取结算后的完整数据，但保留玩家可能在结算期间修改的字段
            // 保留字段：建筑放置(placedBuildings)、弟子分配(elderSlots/*Slots)、政策设置等
            val mergedGameData = shadow.gameData.copy(
                placedBuildings = oldState.gameData.placedBuildings,
                elderSlots = oldState.gameData.elderSlots,
                librarySlots = oldState.gameData.librarySlots,
                residenceSlots = oldState.gameData.residenceSlots,
                spiritMineSlots = oldState.gameData.spiritMineSlots,
                patrolSlots = oldState.gameData.patrolSlots,
                warehouseGarrisons = oldState.gameData.warehouseGarrisons,
                spiritFieldPlants = oldState.gameData.spiritFieldPlants,
                gamePhase = oldState.gameData.gamePhase,
                gameMonth = oldState.gameData.gameMonth,
                gameYear = oldState.gameData.gameYear
            )

            UnifiedGameState(
                gameData = mergedGameData,
                disciples = mergedDisciples,
                equipmentStacks = shadow.equipmentStacks,
                equipmentInstances = shadow.equipmentInstances,
                manualStacks = shadow.manualStacks,
                manualInstances = shadow.manualInstances,
                pills = shadow.pills,
                materials = shadow.materials,
                herbs = shadow.herbs,
                seeds = shadow.seeds,
                teams = shadow.teams,
                battleLogs = shadow.battleLogs,
                alliances = shadow.gameData.alliances,
                isPaused = finalPaused,
                isLoading = finalLoading,
                isSaving = finalSaving,
                pendingBattleResult = oldState.pendingBattleResult,
                pendingNotification = shadow.pendingNotification ?: oldState.pendingNotification
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
