package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.event.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton

data class GameState(
    val gameData: GameData = GameData(),
    val disciples: List<Disciple> = emptyList(),
    val equipment: List<Equipment> = emptyList(),
    val manuals: List<Manual> = emptyList(),
    val pills: List<Pill> = emptyList(),
    val materials: List<Material> = emptyList(),
    val herbs: List<Herb> = emptyList(),
    val seeds: List<Seed> = emptyList(),
    val teams: List<ExplorationTeam> = emptyList(),
    val events: List<GameEvent> = emptyList(),
    val battleLogs: List<BattleLog> = emptyList(),
    val alliances: List<Alliance> = emptyList(),
    
    val isPaused: Boolean = true,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val gameSpeed: Int = 1,
    
    val lastUpdateTime: Long = System.currentTimeMillis(),
    val version: Int = 1
) {
    val isRunning: Boolean get() = !isPaused && !isLoading && !isSaving
    
    val aliveDisciples: List<Disciple> get() = disciples.filter { it.isAlive }
    
    val idleDisciples: List<Disciple> get() = disciples.filter { it.isAlive && it.status == DiscipleStatus.IDLE }
    
    val workingDisciples: List<Disciple> get() = disciples.filter { it.isAlive && it.status != DiscipleStatus.IDLE }
    
    fun getDiscipleById(id: String): Disciple? = disciples.find { it.id == id }
    
    fun getEquipmentById(id: String): Equipment? = equipment.find { it.id == id }
    
    fun getManualById(id: String): Manual? = manuals.find { it.id == id }
    
    fun getEquipmentByOwner(discipleId: String): List<Equipment> = 
        equipment.filter { it.ownerId == discipleId }
    
    fun getManualsByOwner(discipleId: String): List<Manual> = 
        manuals.filter { it.ownerId == discipleId }
}

data class UnifiedGameState(
    val gameData: GameData = GameData(),
    val disciples: List<Disciple> = emptyList(),
    val equipment: List<Equipment> = emptyList(),
    val manuals: List<Manual> = emptyList(),
    val pills: List<Pill> = emptyList(),
    val materials: List<Material> = emptyList(),
    val herbs: List<Herb> = emptyList(),
    val seeds: List<Seed> = emptyList(),
    val teams: List<ExplorationTeam> = emptyList(),
    val events: List<GameEvent> = emptyList(),
    val battleLogs: List<BattleLog> = emptyList(),
    val alliances: List<Alliance> = emptyList(),
    
    val isPaused: Boolean = true,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val gameSpeed: Int = 1,
    
    val lastUpdateTime: Long = System.currentTimeMillis(),
    val version: Int = 1
) {
    val isRunning: Boolean get() = !isPaused && !isLoading && !isSaving
    
    val aliveDisciples: List<Disciple> get() = disciples.filter { it.isAlive }
    
    val idleDisciples: List<Disciple> get() = disciples.filter { it.isAlive && it.status == DiscipleStatus.IDLE }
    
    val workingDisciples: List<Disciple> get() = disciples.filter { it.isAlive && it.status != DiscipleStatus.IDLE }
    
    fun getDiscipleById(id: String): Disciple? = disciples.find { it.id == id }
    
    fun getEquipmentById(id: String): Equipment? = equipment.find { it.id == id }
    
    fun getManualById(id: String): Manual? = manuals.find { it.id == id }
    
    fun getEquipmentByOwner(discipleId: String): List<Equipment> = 
        equipment.filter { it.ownerId == discipleId }
    
    fun getManualsByOwner(discipleId: String): List<Manual> = 
        manuals.filter { it.ownerId == discipleId }
    
    fun toGameState(): GameState = GameState(
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
        alliances = alliances
    )
    
    companion object {
        fun fromGameState(state: GameState): UnifiedGameState = UnifiedGameState(
            gameData = state.gameData,
            disciples = state.disciples,
            equipment = state.equipment,
            manuals = state.manuals,
            pills = state.pills,
            materials = state.materials,
            herbs = state.herbs,
            seeds = state.seeds,
            teams = state.teams,
            events = state.events,
            battleLogs = state.battleLogs,
            alliances = state.alliances,
            isPaused = state.isPaused,
            isLoading = state.isLoading,
            isSaving = state.isSaving,
            gameSpeed = state.gameSpeed,
            lastUpdateTime = state.lastUpdateTime,
            version = state.version
        )
    }
}

sealed class UnifiedStateChange {
    abstract val timestamp: Long
    
    data class GameDataUpdate(
        val oldData: GameData,
        val newData: GameData,
        override val timestamp: Long = System.currentTimeMillis()
    ) : UnifiedStateChange()
    
    data class DiscipleUpdated(
        val discipleId: String,
        val changes: Map<String, Any?>,
        override val timestamp: Long = System.currentTimeMillis()
    ) : UnifiedStateChange()
    
    data class DiscipleUpdate(
        val discipleId: String,
        val oldDisciple: Disciple?,
        val newDisciple: Disciple?,
        override val timestamp: Long = System.currentTimeMillis()
    ) : UnifiedStateChange()
    
    data class DiscipleListUpdate(
        val disciples: List<Disciple>,
        override val timestamp: Long = System.currentTimeMillis()
    ) : UnifiedStateChange()
    
    data class EquipmentUpdate(
        val equipmentId: String,
        val oldEquipment: Equipment?,
        val newEquipment: Equipment?,
        override val timestamp: Long = System.currentTimeMillis()
    ) : UnifiedStateChange()
    
    data class CultivationProgress(
        val discipleId: String,
        val progress: Double,
        override val timestamp: Long = System.currentTimeMillis()
    ) : UnifiedStateChange()
    
    data class ItemCrafted(
        val itemId: String,
        val itemType: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : UnifiedStateChange()
    
    data class BattleCompleted(
        val battleId: String,
        val result: BattleResult,
        override val timestamp: Long = System.currentTimeMillis()
    ) : UnifiedStateChange()
    
    data class PauseStateChange(
        val isPaused: Boolean,
        override val timestamp: Long = System.currentTimeMillis()
    ) : UnifiedStateChange()
    
    data class FullStateUpdate(
        val state: UnifiedGameState,
        override val timestamp: Long = System.currentTimeMillis()
    ) : UnifiedStateChange()
}

data class BattleResult(
    val victory: Boolean,
    val playerLosses: Int = 0,
    val enemyLosses: Int = 0,
    val rewards: List<RewardSelectedItem> = emptyList()
) {
    fun toBattleResultInfo(): BattleResultInfo = BattleResultInfo(
        victory = victory,
        playerLosses = playerLosses,
        enemyLosses = enemyLosses,
        rewards = rewards.map { 
            RewardItemInfo(
                itemId = it.id,
                itemName = it.name,
                quantity = it.quantity,
                rarity = it.rarity
            )
        }
    )
}

interface UnifiedStateObserver {
    fun onStateChange(change: UnifiedStateChange)
}

@Singleton
class UnifiedGameStateManager @Inject constructor(
    private val eventBus: EventBus
) {
    
    private val stateMutex = Mutex()
    
    private val _state = MutableStateFlow(UnifiedGameState())
    val state: StateFlow<UnifiedGameState> = _state.asStateFlow()
    
    private val observers = CopyOnWriteArrayList<UnifiedStateObserver>()
    
    private val changeHistory = ConcurrentLinkedQueue<UnifiedStateChange>()
    private val maxHistorySize = 100
    
    val currentState: UnifiedGameState 
        get() = _state.value
    
    suspend fun <T> withState(block: (UnifiedGameState) -> Pair<UnifiedGameState, T>): T {
        return stateMutex.withLock {
            val (newState, result) = block(_state.value)
            _state.value = newState.copy(
                lastUpdateTime = System.currentTimeMillis()
            )
            val change = UnifiedStateChange.FullStateUpdate(newState)
            recordChange(change)
            notifyObservers(change)
            result
        }
    }
    
    suspend fun updateState(block: (UnifiedGameState) -> UnifiedGameState) {
        stateMutex.withLock {
            val oldState = _state.value
            val newState = block(oldState)
            _state.value = newState.copy(
                lastUpdateTime = System.currentTimeMillis()
            )
            
            val change = UnifiedStateChange.FullStateUpdate(newState)
            recordChange(change)
            notifyObservers(change)
        }
    }
    
    suspend fun updateStateSync(block: (UnifiedGameState) -> UnifiedGameState) {
        stateMutex.withLock {
            val oldState = _state.value
            val newState = block(oldState)
            _state.value = newState.copy(
                lastUpdateTime = System.currentTimeMillis()
            )
            
            val change = UnifiedStateChange.FullStateUpdate(newState)
            recordChange(change)
            notifyObservers(change)
        }
    }
    
    suspend fun updateGameData(update: (GameData) -> GameData) {
        stateMutex.withLock {
            val oldData = _state.value.gameData
            val newData = update(oldData)
            
            _state.value = _state.value.copy(
                gameData = newData,
                lastUpdateTime = System.currentTimeMillis()
            )
            
            val change = UnifiedStateChange.GameDataUpdate(oldData, newData)
            recordChange(change)
            notifyObservers(change)
            
            eventBus.emit(SectEvent(
                sectId = newData.id,
                sectName = newData.sectName,
                action = "data_update",
                details = mapOf("timestamp" to System.currentTimeMillis())
            ))
        }
    }
    
    fun updateGameDataSync(update: (GameData) -> GameData) {
        val oldData = _state.value.gameData
        val newData = update(oldData)
        
        _state.value = _state.value.copy(
            gameData = newData,
            lastUpdateTime = System.currentTimeMillis()
        )
        
        val change = UnifiedStateChange.GameDataUpdate(oldData, newData)
        recordChange(change)
        notifyObservers(change)
    }
    
    suspend fun updateDisciple(discipleId: String, update: (Disciple) -> Disciple) {
        stateMutex.withLock {
            val currentDisciples = _state.value.disciples
            val index = currentDisciples.indexOfFirst { it.id == discipleId }
            
            if (index >= 0) {
                val oldDisciple = currentDisciples[index]
                val newDisciple = update(oldDisciple)
                val newDisciples = currentDisciples.toMutableList()
                newDisciples[index] = newDisciple
                
                _state.value = _state.value.copy(
                    disciples = newDisciples,
                    lastUpdateTime = System.currentTimeMillis()
                )
                
                val change = UnifiedStateChange.DiscipleUpdate(discipleId, oldDisciple, newDisciple)
                recordChange(change)
                notifyObservers(change)
                
                val changes = mutableMapOf<String, Any?>()
                if (oldDisciple.cultivation != newDisciple.cultivation) {
                    changes["cultivation"] = newDisciple.cultivation
                }
                if (oldDisciple.realm != newDisciple.realm) {
                    changes["realm"] = newDisciple.realm
                }
                if (oldDisciple.status != newDisciple.status) {
                    changes["status"] = newDisciple.status
                }
                if (changes.isNotEmpty()) {
                    eventBus.emit(DiscipleUpdatedEvent(
                        discipleId = discipleId,
                        changes = changes
                    ))
                }
            }
        }
    }
    
    suspend fun updateDisciples(update: (List<Disciple>) -> List<Disciple>) {
        stateMutex.withLock {
            val newDisciples = update(_state.value.disciples)
            
            _state.value = _state.value.copy(
                disciples = newDisciples,
                lastUpdateTime = System.currentTimeMillis()
            )
            
            val change = UnifiedStateChange.DiscipleListUpdate(newDisciples)
            recordChange(change)
            notifyObservers(change)
        }
    }
    
    suspend fun addDisciple(disciple: Disciple) {
        stateMutex.withLock {
            val newDisciples = _state.value.disciples + disciple
            
            _state.value = _state.value.copy(
                disciples = newDisciples,
                lastUpdateTime = System.currentTimeMillis()
            )
            
            val change = UnifiedStateChange.DiscipleUpdate(disciple.id, null, disciple)
            recordChange(change)
            notifyObservers(change)
            
            eventBus.emit(DiscipleUpdatedEvent(
                discipleId = disciple.id,
                changes = mapOf("added" to true, "name" to disciple.name)
            ))
        }
    }
    
    suspend fun removeDisciple(discipleId: String) {
        stateMutex.withLock {
            val oldDisciple = _state.value.disciples.find { it.id == discipleId }
            val newDisciples = _state.value.disciples.filter { it.id != discipleId }
            
            _state.value = _state.value.copy(
                disciples = newDisciples,
                lastUpdateTime = System.currentTimeMillis()
            )
            
            val change = UnifiedStateChange.DiscipleUpdate(discipleId, oldDisciple, null)
            recordChange(change)
            notifyObservers(change)
            
            eventBus.emit(DiscipleUpdatedEvent(
                discipleId = discipleId,
                changes = mapOf("removed" to true)
            ))
        }
    }
    
    suspend fun emitCultivationProgress(discipleId: String, progress: Double) {
        stateMutex.withLock {
            val change = UnifiedStateChange.CultivationProgress(discipleId, progress)
            recordChange(change)
            notifyObservers(change)
            
            eventBus.emit(CultivationProgressEvent(
                discipleId = discipleId,
                progress = progress
            ))
        }
    }
    
    suspend fun emitItemCrafted(itemId: String, itemType: String) {
        stateMutex.withLock {
            val change = UnifiedStateChange.ItemCrafted(itemId, itemType)
            recordChange(change)
            notifyObservers(change)
            
            eventBus.emit(ItemCraftedEvent(
                itemId = itemId,
                itemType = itemType
            ))
        }
    }
    
    suspend fun emitBattleCompleted(battleId: String, result: BattleResult) {
        stateMutex.withLock {
            val change = UnifiedStateChange.BattleCompleted(battleId, result)
            recordChange(change)
            notifyObservers(change)
            
            eventBus.emit(BattleCompletedEvent(
                battleId = battleId,
                result = result.toBattleResultInfo()
            ))
        }
    }
    
    suspend fun setPaused(paused: Boolean) {
        stateMutex.withLock {
            _state.value = _state.value.copy(
                isPaused = paused,
                lastUpdateTime = System.currentTimeMillis()
            )
            
            val change = UnifiedStateChange.PauseStateChange(paused)
            recordChange(change)
            notifyObservers(change)
        }
    }
    
    suspend fun setLoading(loading: Boolean) {
        stateMutex.withLock {
            _state.value = _state.value.copy(
                isLoading = loading,
                lastUpdateTime = System.currentTimeMillis()
            )
        }
    }
    
    suspend fun setSaving(saving: Boolean) {
        stateMutex.withLock {
            _state.value = _state.value.copy(
                isSaving = saving,
                lastUpdateTime = System.currentTimeMillis()
            )
        }
    }
    
    suspend fun loadState(newState: UnifiedGameState) {
        stateMutex.withLock {
            _state.value = newState.copy(
                lastUpdateTime = System.currentTimeMillis()
            )
            
            val change = UnifiedStateChange.FullStateUpdate(newState)
            recordChange(change)
            notifyObservers(change)
        }
    }
    
    suspend fun loadFromGameState(gameState: GameState) {
        loadState(UnifiedGameState.fromGameState(gameState))
    }
    
    fun addObserver(observer: UnifiedStateObserver) {
        observers.add(observer)
    }
    
    fun removeObserver(observer: UnifiedStateObserver) {
        observers.remove(observer)
    }
    
    fun getChangeHistory(): List<UnifiedStateChange> = changeHistory.toList()
    
    fun getChangesSince(timestamp: Long): List<UnifiedStateChange> = 
        changeHistory.filter { it.timestamp >= timestamp }
    
    private fun recordChange(change: UnifiedStateChange) {
        changeHistory.add(change)
        while (changeHistory.size > maxHistorySize) {
            changeHistory.poll()
        }
    }
    
    private fun notifyObservers(change: UnifiedStateChange) {
        observers.forEach { observer ->
            try {
                observer.onStateChange(change)
            } catch (e: Exception) {
                android.util.Log.e("UnifiedGameStateManager", "Error notifying observer", e)
            }
        }
    }
    
    suspend fun createSnapshot(): UnifiedGameState {
        return stateMutex.withLock { _state.value.copy() }
    }
    
    fun clearHistory() {
        changeHistory.clear()
    }
}
