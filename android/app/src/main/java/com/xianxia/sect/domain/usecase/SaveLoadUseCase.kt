package com.xianxia.sect.domain.usecase

import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.data.facade.RefactoredStorageFacade
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.model.SaveSlot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SaveLoadUseCase @Inject constructor(
    private val gameEngine: GameEngine,
    private val storageFacade: RefactoredStorageFacade
) {
    sealed class SaveResult {
        data class Success(val slotId: Int) : SaveResult()
        data class Error(val message: String) : SaveResult()
    }
    
    sealed class LoadResult {
        data class Success(val slotId: Int) : LoadResult()
        data class Error(val message: String) : LoadResult()
    }
    
    suspend fun saveGame(slotId: Int): SaveResult = withContext(Dispatchers.IO) {
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
                alliances = snapshot.alliances
            )
            
            when (val result = storageFacade.save(slotId, saveData)) {
                is com.xianxia.sect.data.unified.SaveResult.Success -> SaveResult.Success(slotId)
                is com.xianxia.sect.data.unified.SaveResult.Failure -> SaveResult.Error(result.message)
            }
        } catch (e: Exception) {
            SaveResult.Error(e.message ?: "保存失败")
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
                        alliances = saveData.alliances
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
