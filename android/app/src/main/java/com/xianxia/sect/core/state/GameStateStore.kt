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

    val unifiedState: StateFlow<UnifiedGameState> = _state.asStateFlow()

    val gameData: StateFlow<GameData> = _state.map { it.gameData }
        .distinctUntilChanged()
        .stateIn(applicationScopeProvider.scope, SharingStarted.Eagerly, GameData())

    val disciples: StateFlow<List<Disciple>> = _state.map { it.disciples }
        .distinctUntilChanged()
        .stateIn(applicationScopeProvider.scope, SharingStarted.Eagerly, emptyList())

    val equipmentStacks: StateFlow<List<EquipmentStack>> = _state.map { it.equipmentStacks }
        .distinctUntilChanged()
        .stateIn(applicationScopeProvider.scope, SharingStarted.Eagerly, emptyList())

    val equipmentInstances: StateFlow<List<EquipmentInstance>> = _state.map { it.equipmentInstances }
        .distinctUntilChanged()
        .stateIn(applicationScopeProvider.scope, SharingStarted.Eagerly, emptyList())

    val manualStacks: StateFlow<List<ManualStack>> = _state.map { it.manualStacks }
        .distinctUntilChanged()
        .stateIn(applicationScopeProvider.scope, SharingStarted.Eagerly, emptyList())

    val manualInstances: StateFlow<List<ManualInstance>> = _state.map { it.manualInstances }
        .distinctUntilChanged()
        .stateIn(applicationScopeProvider.scope, SharingStarted.Eagerly, emptyList())

    val pills: StateFlow<List<Pill>> = _state.map { it.pills }
        .distinctUntilChanged()
        .stateIn(applicationScopeProvider.scope, SharingStarted.Eagerly, emptyList())

    val materials: StateFlow<List<Material>> = _state.map { it.materials }
        .distinctUntilChanged()
        .stateIn(applicationScopeProvider.scope, SharingStarted.Eagerly, emptyList())

    val herbs: StateFlow<List<Herb>> = _state.map { it.herbs }
        .distinctUntilChanged()
        .stateIn(applicationScopeProvider.scope, SharingStarted.Eagerly, emptyList())

    val seeds: StateFlow<List<Seed>> = _state.map { it.seeds }
        .distinctUntilChanged()
        .stateIn(applicationScopeProvider.scope, SharingStarted.Eagerly, emptyList())

    val events: StateFlow<List<GameEvent>> = _state.map { it.events }
        .distinctUntilChanged()
        .stateIn(applicationScopeProvider.scope, SharingStarted.Eagerly, emptyList())

    val battleLogs: StateFlow<List<BattleLog>> = _state.map { it.battleLogs }
        .distinctUntilChanged()
        .stateIn(applicationScopeProvider.scope, SharingStarted.Eagerly, emptyList())

    val teams: StateFlow<List<ExplorationTeam>> = _state.map { it.teams }
        .distinctUntilChanged()
        .stateIn(applicationScopeProvider.scope, SharingStarted.Eagerly, emptyList())

    val isPaused: StateFlow<Boolean> = _state.map { it.isPaused }
        .distinctUntilChanged()
        .stateIn(applicationScopeProvider.scope, SharingStarted.Eagerly, true)

    val isLoading: StateFlow<Boolean> = _state.map { it.isLoading }
        .distinctUntilChanged()
        .stateIn(applicationScopeProvider.scope, SharingStarted.Eagerly, false)

    val isSaving: StateFlow<Boolean> = _state.map { it.isSaving }
        .distinctUntilChanged()
        .stateIn(applicationScopeProvider.scope, SharingStarted.Eagerly, false)

    val discipleAggregates: StateFlow<List<DiscipleAggregate>> = _state
        .map { state -> state.disciples.map { it.toAggregate() } }
        .distinctUntilChanged()
        .stateIn(applicationScopeProvider.scope, SharingStarted.Eagerly, emptyList())

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

    suspend fun update(block: suspend MutableGameState.() -> Unit) {
        check(!isInTransaction()) {
            "GameStateStore.update() must not be called inside an existing transaction (nested lock). " +
                "Use currentTransactionMutableState() to modify state within a tick transaction."
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
                _state.value = UnifiedGameState(
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
                    isPaused = reusableMutableState.isPaused,
                    isLoading = reusableMutableState.isLoading,
                    isSaving = reusableMutableState.isSaving
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
        teams: List<ExplorationTeam>,
        events: List<GameEvent>,
        battleLogs: List<BattleLog>,
        isPaused: Boolean = true,
        isLoading: Boolean = false,
        isSaving: Boolean = false
    ) {
        transactionMutex.withLock {
            _state.value = UnifiedGameState(
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
    }

    suspend fun reset() {
        transactionMutex.withLock {
            _state.value = UnifiedGameState()
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
        val fixedItems = disciple.storageBagItems.map { item ->
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
