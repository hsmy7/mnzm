package com.xianxia.sect.core.state

import android.util.Log
import com.xianxia.sect.core.model.*
import com.xianxia.sect.di.ApplicationScopeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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

    private val transactionMutex = Mutex()

    @Volatile
    private var currentTransactionState: MutableGameState? = null

    private val _batchVersion = MutableStateFlow(0L)

    private val _gameData = MutableStateFlow(GameData())
    private val _disciples = MutableStateFlow<List<Disciple>>(emptyList())
    private val _equipment = MutableStateFlow<List<Equipment>>(emptyList())
    private val _manuals = MutableStateFlow<List<Manual>>(emptyList())
    private val _pills = MutableStateFlow<List<Pill>>(emptyList())
    private val _materials = MutableStateFlow<List<Material>>(emptyList())
    private val _herbs = MutableStateFlow<List<Herb>>(emptyList())
    private val _seeds = MutableStateFlow<List<Seed>>(emptyList())
    private val _events = MutableStateFlow<List<GameEvent>>(emptyList())
    private val _battleLogs = MutableStateFlow<List<BattleLog>>(emptyList())
    private val _teams = MutableStateFlow<List<ExplorationTeam>>(emptyList())

    private val _isPaused = MutableStateFlow(true)
    private val _isLoading = MutableStateFlow(false)
    private val _isSaving = MutableStateFlow(false)

    val gameData: StateFlow<GameData> get() = _gameData
    val disciples: StateFlow<List<Disciple>> get() = _disciples
    val equipment: StateFlow<List<Equipment>> get() = _equipment
    val manuals: StateFlow<List<Manual>> get() = _manuals
    val pills: StateFlow<List<Pill>> get() = _pills
    val materials: StateFlow<List<Material>> get() = _materials
    val herbs: StateFlow<List<Herb>> get() = _herbs
    val seeds: StateFlow<List<Seed>> get() = _seeds
    val events: StateFlow<List<GameEvent>> get() = _events
    val battleLogs: StateFlow<List<BattleLog>> get() = _battleLogs
    val teams: StateFlow<List<ExplorationTeam>> get() = _teams

    val isPaused: StateFlow<Boolean> get() = _isPaused
    val isLoading: StateFlow<Boolean> get() = _isLoading
    val isSaving: StateFlow<Boolean> get() = _isSaving

    val discipleAggregates: StateFlow<List<DiscipleAggregate>> = _disciples
        .map { list -> list.map { it.toAggregate() } }
        .stateIn(
            applicationScopeProvider.scope,
            SharingStarted.Eagerly,
            emptyList()
        )

    @Suppress("UNCHECKED_CAST")
    val unifiedState: StateFlow<UnifiedGameState> = combine(
        _gameData as kotlinx.coroutines.flow.Flow<Any>,
        _disciples as kotlinx.coroutines.flow.Flow<Any>,
        _equipment as kotlinx.coroutines.flow.Flow<Any>,
        _manuals as kotlinx.coroutines.flow.Flow<Any>,
        _pills as kotlinx.coroutines.flow.Flow<Any>,
        _materials as kotlinx.coroutines.flow.Flow<Any>,
        _herbs as kotlinx.coroutines.flow.Flow<Any>,
        _seeds as kotlinx.coroutines.flow.Flow<Any>,
        _teams as kotlinx.coroutines.flow.Flow<Any>,
        _events as kotlinx.coroutines.flow.Flow<Any>,
        _battleLogs as kotlinx.coroutines.flow.Flow<Any>,
        _isPaused as kotlinx.coroutines.flow.Flow<Any>,
        _isLoading as kotlinx.coroutines.flow.Flow<Any>,
        _isSaving as kotlinx.coroutines.flow.Flow<Any>,
        _batchVersion as kotlinx.coroutines.flow.Flow<Any>
    ) { values ->
        val gd = values[0] as GameData
        val version = values[14] as Long
        UnifiedGameState(
            gameData = gd,
            disciples = values[1] as List<Disciple>,
            equipment = values[2] as List<Equipment>,
            manuals = values[3] as List<Manual>,
            pills = values[4] as List<Pill>,
            materials = values[5] as List<Material>,
            herbs = values[6] as List<Herb>,
            seeds = values[7] as List<Seed>,
            teams = values[8] as List<ExplorationTeam>,
            events = values[9] as List<GameEvent>,
            battleLogs = values[10] as List<BattleLog>,
            alliances = gd.alliances,
            isPaused = values[11] as Boolean,
            isLoading = values[12] as Boolean,
            isSaving = values[13] as Boolean,
            batchVersion = version
        )
    }.distinctUntilChanged { old, new -> old.batchVersion == new.batchVersion }
        .stateIn(
            applicationScopeProvider.scope,
            SharingStarted.Eagerly,
            UnifiedGameState()
        )

    internal fun isInTransaction(): Boolean = currentTransactionState != null

    internal fun currentTransactionMutableState(): MutableGameState? = currentTransactionState

    suspend fun update(block: suspend MutableGameState.() -> Unit) {
        check(!isInTransaction()) {
            "GameStateStore.update() must not be called inside an existing transaction (nested lock). " +
                "Use currentTransactionMutableState() to modify state within a tick transaction."
        }
        transactionMutex.withLock {
            val mutable = MutableGameState(
                gameData = _gameData.value,
                disciples = _disciples.value,
                equipment = _equipment.value,
                manuals = _manuals.value,
                pills = _pills.value,
                materials = _materials.value,
                herbs = _herbs.value,
                seeds = _seeds.value,
                events = _events.value,
                battleLogs = _battleLogs.value,
                teams = _teams.value,
                isPaused = _isPaused.value,
                isLoading = _isLoading.value,
                isSaving = _isSaving.value
            )
            currentTransactionState = mutable
            try {
                mutable.block()
                _batchVersion.value++
                _gameData.value = mutable.gameData
                _disciples.value = mutable.disciples
                _equipment.value = mutable.equipment
                _manuals.value = mutable.manuals
                _pills.value = mutable.pills
                _materials.value = mutable.materials
                _herbs.value = mutable.herbs
                _seeds.value = mutable.seeds
                _events.value = mutable.events
                _battleLogs.value = mutable.battleLogs
                _teams.value = mutable.teams
                _isPaused.value = mutable.isPaused
                _isLoading.value = mutable.isLoading
                _isSaving.value = mutable.isSaving
                _batchVersion.value++
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
            _batchVersion.value++
            _gameData.value = gameData
            _disciples.value = disciples
            _equipment.value = equipment
            _manuals.value = manuals
            _pills.value = pills
            _materials.value = materials
            _herbs.value = herbs
            _seeds.value = seeds
            _teams.value = teams
            _events.value = events
            _battleLogs.value = battleLogs
            _isPaused.value = isPaused
            _isLoading.value = isLoading
            _isSaving.value = isSaving
            _batchVersion.value++
        }
    }

    suspend fun reset() {
        transactionMutex.withLock {
            _batchVersion.value++
            _gameData.value = GameData()
            _disciples.value = emptyList()
            _equipment.value = emptyList()
            _manuals.value = emptyList()
            _pills.value = emptyList()
            _materials.value = emptyList()
            _herbs.value = emptyList()
            _seeds.value = emptyList()
            _events.value = emptyList()
            _battleLogs.value = emptyList()
            _teams.value = emptyList()
            _isPaused.value = true
            _isLoading.value = false
            _isSaving.value = false
            _batchVersion.value++
        }
    }
}
