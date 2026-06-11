package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.*
import com.xianxia.sect.di.ApplicationScopeProvider
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import com.xianxia.sect.core.util.DomainLog
import java.util.concurrent.CopyOnWriteArrayList
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

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
     * 适用于从主线程调用、不能使用挂起函数的场景（如 startGameLoop/stopGameLoop/pauseForBackground）。
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
            storageBags = newState.storageBags,
            teams = newState.teams,
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
                DomainLog.e(TAG, "Error notifying observer", e)
            }
        }
    }
}
