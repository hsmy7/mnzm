package com.xianxia.sect.domain.usecase

import android.util.Log
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.data.facade.RefactoredStorageFacade
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.model.SaveSlot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SaveLoadUseCase @Inject constructor(
    private val gameEngine: GameEngine,
    private val storageFacade: RefactoredStorageFacade
) {
    companion object {
        private const val TAG = "SaveLoadUseCase"
    }

    sealed class SaveResult {
        data class Success(val slotId: Int) : SaveResult()
        data class Error(val message: String) : SaveResult()
    }
    
    sealed class LoadResult {
        data class Success(val slotId: Int) : LoadResult()
        data class Error(val message: String) : LoadResult()
    }

    enum class SaveState { IDLE, CAPTURING, SAVING, COMPLETED, FAILED }

    private val _saveState = MutableStateFlow(SaveState.IDLE)
    val saveState: StateFlow<SaveState> = _saveState.asStateFlow()

    private val saveScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var pendingSaveJob: kotlinx.coroutines.Job? = null

    @Suppress("DEPRECATION")
    suspend fun saveGame(slotId: Int): SaveResult = withContext(Dispatchers.IO) {
        try {
            _saveState.value = SaveState.CAPTURING
            val snapshot = gameEngine.getStateSnapshot()
            val saveData = SaveData(
                gameData = snapshot.gameData,
                disciples = snapshot.disciples,
                equipment = snapshot.equipment,
                manuals = snapshot.manuals,
                pills = snapshot.pills,
                materials = snapshot.materials,
                herbs = snapshot.herbs,
                seeds = snapshot.seeds,
                teams = snapshot.teams,
                events = snapshot.events,
                battleLogs = snapshot.battleLogs,
                alliances = snapshot.alliances,
                productionSlots = snapshot.productionSlots
            )
            _saveState.value = SaveState.SAVING

            when (val result = storageFacade.save(slotId, saveData)) {
                is com.xianxia.sect.data.unified.SaveResult.Success -> {
                    _saveState.value = SaveState.COMPLETED
                    SaveResult.Success(slotId)
                }
                is com.xianxia.sect.data.unified.SaveResult.Failure -> {
                    _saveState.value = SaveState.FAILED
                    SaveResult.Error(result.message)
                }
            }
        } catch (e: Exception) {
            _saveState.value = SaveState.FAILED
            SaveResult.Error(e.message ?: "保存失败")
        }
    }

    @Suppress("DEPRECATION")
    fun saveGameAsync(slotId: Int) {
        if (_saveState.value == SaveState.CAPTURING || _saveState.value == SaveState.SAVING) {
            Log.w(TAG, "Save already in progress, skipping async save for slot $slotId")
            return
        }

        _saveState.value = SaveState.CAPTURING
        pendingSaveJob = saveScope.launch {
            try {
                val snapshot = gameEngine.getStateSnapshotSync()
                val saveData = SaveData(
                    gameData = snapshot.gameData,
                    disciples = snapshot.disciples,
                    equipment = snapshot.equipment,
                    manuals = snapshot.manuals,
                    pills = snapshot.pills,
                    materials = snapshot.materials,
                    herbs = snapshot.herbs,
                    seeds = snapshot.seeds,
                    teams = snapshot.teams,
                    events = snapshot.events,
                    battleLogs = snapshot.battleLogs,
                    alliances = snapshot.alliances,
                    productionSlots = snapshot.productionSlots
                )
                _saveState.value = SaveState.SAVING

                when (val result = storageFacade.save(slotId, saveData)) {
                    is com.xianxia.sect.data.unified.SaveResult.Success -> {
                        _saveState.value = SaveState.COMPLETED
                        Log.i(TAG, "Async save completed for slot $slotId")
                    }
                    is com.xianxia.sect.data.unified.SaveResult.Failure -> {
                        _saveState.value = SaveState.FAILED
                        Log.e(TAG, "Async save failed for slot $slotId: ${result.message}")
                    }
                }
            } catch (e: Exception) {
                _saveState.value = SaveState.FAILED
                Log.e(TAG, "Async save exception for slot $slotId", e)
            }
        }
    }
    
    suspend fun loadGame(slotId: Int): LoadResult = withContext(Dispatchers.IO) {
        try {
            when (val result = storageFacade.load(slotId)) {
                is com.xianxia.sect.data.unified.SaveResult.Success -> {
                    val saveData = result.data
                    gameEngine.loadData(
                        gameData = saveData.gameData,
                        disciples = saveData.disciples,
                        equipment = saveData.equipment,
                        manuals = saveData.manuals,
                        pills = saveData.pills,
                        materials = saveData.materials,
                        herbs = saveData.herbs,
                        seeds = saveData.seeds,
                        teams = saveData.teams,
                        events = saveData.events,
                        battleLogs = saveData.battleLogs,
                        alliances = saveData.alliances,
                        productionSlots = saveData.productionSlots
                    )
                    LoadResult.Success(slotId)
                }
                is com.xianxia.sect.data.unified.SaveResult.Failure -> LoadResult.Error(result.message)
            }
        } catch (e: Exception) {
            LoadResult.Error(e.message ?: "加载失败")
        }
    }
    
    fun getSaveSlots(): List<SaveSlot> {
        return storageFacade.getSaveSlots()
    }
    
    suspend fun deleteSave(slotId: Int): Boolean {
        return try {
            storageFacade.delete(slotId).isSuccess
        } catch (e: Exception) {
            false
        }
    }
}
