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
    var equipment: List<Equipment>,
    var manuals: List<Manual>,
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

    val equipment: StateFlow<List<Equipment>> = _state.map { it.equipment }
        .distinctUntilChanged()
        .stateIn(applicationScopeProvider.scope, SharingStarted.Eagerly, emptyList())

    val manuals: StateFlow<List<Manual>> = _state.map { it.manuals }
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

    private val reusableMutableState = MutableGameState(
        gameData = GameData(),
        disciples = emptyList(),
        equipment = emptyList(),
        manuals = emptyList(),
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
                equipment = current.equipment
                manuals = current.manuals
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
                    equipment = reusableMutableState.equipment,
                    manuals = reusableMutableState.manuals,
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
        equipment: List<Equipment>,
        manuals: List<Manual>,
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
                equipment = equipment,
                manuals = manuals,
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
