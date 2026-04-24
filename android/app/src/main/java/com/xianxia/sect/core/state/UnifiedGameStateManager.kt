package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.*
import com.xianxia.sect.di.ApplicationScopeProvider
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

data class UnifiedGameState(
    val gameData: GameData = GameData(),
    val disciples: List<Disciple> = emptyList(),
    val equipmentStacks: List<EquipmentStack> = emptyList(),
    val equipmentInstances: List<EquipmentInstance> = emptyList(),
    val manualStacks: List<ManualStack> = emptyList(),
    val manualInstances: List<ManualInstance> = emptyList(),
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

    fun getEquipmentById(id: String): EquipmentInstance? = equipmentInstances.find { it.id == id }

    fun getManualById(id: String): ManualInstance? = manualInstances.find { it.id == id }

    fun getEquipmentByOwner(discipleId: String): List<EquipmentInstance> =
        equipmentInstances.filter { it.ownerId == discipleId }

    fun getManualsByOwner(discipleId: String): List<ManualInstance> =
        manualInstances.filter { it.ownerId == discipleId }
}

interface UnifiedStateObserver {
    fun onStateChanged(newState: UnifiedGameState)
}

@Singleton
class UnifiedGameStateManager @Inject constructor(
    private val stateStore: GameStateStore,
    private val applicationScopeProvider: ApplicationScopeProvider
) {

    companion object {
        private const val TAG = "UnifiedGameStateManager"
    }

    private val observers = CopyOnWriteArrayList<WeakReference<UnifiedStateObserver>>()

    val state: StateFlow<UnifiedGameState> get() = stateStore.unifiedState

    init {
        applicationScopeProvider.scope.launch {
            stateStore.unifiedState.collect { newState ->
                notifyObservers(newState)
            }
        }
    }

    suspend fun setPaused(paused: Boolean) {
        stateStore.update { isPaused = paused }
    }

    suspend fun setLoading(loading: Boolean) {
        stateStore.update { isLoading = loading }
    }

    suspend fun setSaving(saving: Boolean) {
        stateStore.update { isSaving = saving }
    }

    /**
     * 非挂起版本的 setPaused，直接更新 StateFlow，不经过 Mutex。
     * 适用于从主线程调用、不能使用 runBlocking 的场景（如 startGameLoop/stopGameLoop/pauseForBackground）。
     * 对于简单的布尔标志位更新，直接赋值是线程安全的。
     */
    fun setPausedDirect(paused: Boolean) {
        stateStore.setPausedDirect(paused)
    }

    fun setLoadingDirect(loading: Boolean) {
        stateStore.setLoadingDirect(loading)
    }

    fun setSavingDirect(saving: Boolean) {
        stateStore.setSavingDirect(saving)
    }

    suspend fun loadState(newState: UnifiedGameState) {
        stateStore.loadFromSnapshot(
            gameData = newState.gameData,
            disciples = newState.disciples,
            equipmentStacks = newState.equipmentStacks,
            equipmentInstances = newState.equipmentInstances,
            manualStacks = newState.manualStacks,
            manualInstances = newState.manualInstances,
            pills = newState.pills,
            materials = newState.materials,
            herbs = newState.herbs,
            seeds = newState.seeds,
            teams = newState.teams,
            events = newState.events,
            battleLogs = newState.battleLogs,
            isPaused = newState.isPaused,
            isLoading = newState.isLoading,
            isSaving = newState.isSaving
        )
    }

    fun addObserver(observer: UnifiedStateObserver) {
        observers.add(WeakReference(observer))
    }

    fun removeObserver(observer: UnifiedStateObserver) {
        observers.removeAll { it.get() == observer }
    }

    private fun notifyObservers(newState: UnifiedGameState) {
        observers.removeAll { it.get() == null }
        val snapshot = observers.mapNotNull { it.get() }.toTypedArray()
        for (observer in snapshot) {
            try {
                observer.onStateChanged(newState)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying observer", e)
            }
        }
    }
}
