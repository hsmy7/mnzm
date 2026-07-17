package com.xianxia.sect.ui.game.saveload

import android.util.Log
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.ui.game.SaveLoadViewModelConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * SaveLoad 状态管理枚举
 */
enum class SaveLoadState {
    IDLE, SAVING, LOADING
}

/**
 * 存档状态管理委托 — 管理存档/读档的 UI 状态、加载进度、预加载状态。
 */
class SaveLoadStateDelegate(
    private val stateStore: GameStateStore
) {
    private val TAG = "SaveLoadStateDelegate"

    private val _loadingProgress = MutableStateFlow(0f)
    val loadingProgress: StateFlow<Float> = _loadingProgress.asStateFlow()

    private val _preloadPhase = MutableStateFlow(SaveLoadViewModelConstants.PHASE_INIT)
    val preloadPhase: StateFlow<String> = _preloadPhase.asStateFlow()

    private val _isRestarting = MutableStateFlow(false)
    val isRestarting: StateFlow<Boolean> = _isRestarting.asStateFlow()

    private val _pendingSlot = MutableStateFlow<Int?>(null)
    val pendingSlot: StateFlow<Int?> = _pendingSlot.asStateFlow()

    private val _pendingAction = MutableStateFlow<String?>(null)
    val pendingAction: StateFlow<String?> = _pendingAction.asStateFlow()

    val saveLoadState: StateFlow<SaveLoadState> = combine(
        stateStore.unifiedState.map { it.isSaving },
        stateStore.unifiedState.map { it.isLoading }
    ) { isSaving, isLoading ->
        when {
            isLoading -> SaveLoadState.LOADING
            isSaving -> SaveLoadState.SAVING
            else -> SaveLoadState.IDLE
        }
    }.stateIn(CoroutineScope(Dispatchers.Default), SharingStarted.Eagerly, SaveLoadState.IDLE)

    val isLoading: StateFlow<Boolean> = stateStore.unifiedState
        .map { it.isLoading }
        .stateIn(CoroutineScope(Dispatchers.Default), SharingStarted.Eagerly, false)

    val isSaving: StateFlow<Boolean> = stateStore.unifiedState
        .map { it.isSaving }
        .stateIn(CoroutineScope(Dispatchers.Default), SharingStarted.Eagerly, false)

fun setLoadingProgress(progress: Float) { _loadingProgress.value = progress }

    fun setPreloadPhase(phase: String) { _preloadPhase.value = phase }

    fun setRestarting(value: Boolean) { _isRestarting.value = value }

    fun setPendingAction(slot: Int?, action: String?) {
        _pendingSlot.value = slot
        _pendingAction.value = action
    }

    fun clearPendingAction() {
        _pendingSlot.value = null
        _pendingAction.value = null
    }

    suspend fun setSaveLoadState(
        isSaving: Boolean? = null,
        isLoading: Boolean? = null,
        pendingSlot: Int? = _pendingSlot.value,
        pendingAction: String? = _pendingAction.value
    ) {
        val current = stateStore.unifiedState.value
        val finalIsSaving = isSaving ?: current.isSaving
        val finalIsLoading = isLoading ?: current.isLoading

        try { stateStore.update { this.isLoading = finalIsLoading } }
          catch (e: CancellationException) { throw e }
          catch (e: Exception) { Log.w(TAG, "Failed to sync isLoading: ${e.message}") }

        try { stateStore.update { this.isSaving = finalIsSaving } }
          catch (e: CancellationException) { throw e }
          catch (e: Exception) { Log.w(TAG, "Failed to sync isSaving: ${e.message}") }

        _pendingSlot.value = pendingSlot
        _pendingAction.value = pendingAction
    }
}
