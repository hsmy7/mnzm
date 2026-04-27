package com.xianxia.sect.core.state

import android.util.Log
import com.xianxia.sect.core.model.*
import com.xianxia.sect.di.ApplicationScopeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    var events: List<GameEvent>,
    var battleLogs: List<BattleLog>,
    var teams: List<ExplorationTeam>,
    var isPaused: Boolean,
    var isLoading: Boolean,
    var isSaving: Boolean
)

@Singleton
class GameStateStore @Inject constructor(
    private val applicationScopeProvider: ApplicationScopeProvider
) {

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

    val unifiedState: StateFlow<UnifiedGameState> = _state.asStateFlow()

    val gameData: StateFlow<GameData> = _state.map { it.gameData }
        .distinctUntilChanged()
        .stateIn(applicationScopeProvider.scope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 30_000), GameData())

    val disciples: StateFlow<List<Disciple>> = _state.map { it.disciples }
        .distinctUntilChanged()
        .stateIn(applicationScopeProvider.scope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 30_000), emptyList())

    val equipmentStacks: StateFlow<List<EquipmentStack>> = _state.map { it.equipmentStacks }
        .distinctUntilChanged()
        .stateIn(applicationScopeProvider.scope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 30_000), emptyList())

    val equipmentInstances: StateFlow<List<EquipmentInstance>> = _state.map { it.equipmentInstances }
        .distinctUntilChanged()
        .stateIn(applicationScopeProvider.scope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 30_000), emptyList())

    val manualStacks: StateFlow<List<ManualStack>> = _state.map { it.manualStacks }
        .distinctUntilChanged()
        .stateIn(applicationScopeProvider.scope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 30_000), emptyList())

    val manualInstances: StateFlow<List<ManualInstance>> = _state.map { it.manualInstances }
        .distinctUntilChanged()
        .stateIn(applicationScopeProvider.scope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 30_000), emptyList())

    val pills: StateFlow<List<Pill>> = _state.map { it.pills }
        .distinctUntilChanged()
        .stateIn(applicationScopeProvider.scope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 30_000), emptyList())

    val materials: StateFlow<List<Material>> = _state.map { it.materials }
        .distinctUntilChanged()
        .stateIn(applicationScopeProvider.scope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 30_000), emptyList())

    val herbs: StateFlow<List<Herb>> = _state.map { it.herbs }
        .distinctUntilChanged()
        .stateIn(applicationScopeProvider.scope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 30_000), emptyList())

    val seeds: StateFlow<List<Seed>> = _state.map { it.seeds }
        .distinctUntilChanged()
        .stateIn(applicationScopeProvider.scope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 30_000), emptyList())

    val events: StateFlow<List<GameEvent>> = _state.map { it.events }
        .distinctUntilChanged()
        .stateIn(applicationScopeProvider.scope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 30_000), emptyList())

    val battleLogs: StateFlow<List<BattleLog>> = _state.map { it.battleLogs }
        .distinctUntilChanged()
        .stateIn(applicationScopeProvider.scope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 30_000), emptyList())

    val teams: StateFlow<List<ExplorationTeam>> = _state.map { it.teams }
        .distinctUntilChanged()
        .stateIn(applicationScopeProvider.scope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 30_000), emptyList())

    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    val discipleAggregates: StateFlow<List<DiscipleAggregate>> = _state
        .map { state -> state.disciples.map { it.toAggregate() } }
        .distinctUntilChanged()
        .stateIn(applicationScopeProvider.scope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 30_000), emptyList())

    internal fun isInTransaction(): Boolean = currentTransactionState != null

    internal fun currentTransactionMutableState(): MutableGameState? = currentTransactionState

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
        events = emptyList(),
        battleLogs = emptyList(),
        teams = emptyList(),
        isPaused = true,
        isLoading = false,
        isSaving = false
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

    suspend fun update(block: suspend MutableGameState.() -> Unit) {
        transactionMutex.withLock {
            check(currentTransactionState == null) {
                "GameStateStore.update() must not be called inside an existing transaction (nested lock). " +
                    "Use currentTransactionMutableState() to modify state within a tick transaction."
            }
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
                events = current.events
                battleLogs = current.battleLogs
                teams = current.teams
                isPaused = current.isPaused
                isLoading = current.isLoading
                isSaving = current.isSaving
            }
            currentTransactionState = reusableMutableState
            try {
                reusableMutableState.block()
                _state.update { _ ->
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
                        events = reusableMutableState.events,
                        battleLogs = reusableMutableState.battleLogs,
                        alliances = reusableMutableState.gameData.alliances,
                        isPaused = finalPaused,
                        isLoading = finalLoading,
                        isSaving = finalSaving
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
        events: List<GameEvent>,
        battleLogs: List<BattleLog>,
        isPaused: Boolean = true,
        isLoading: Boolean = false,
        isSaving: Boolean = false
    ) {
        transactionMutex.withLock {
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
                    events = events,
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
