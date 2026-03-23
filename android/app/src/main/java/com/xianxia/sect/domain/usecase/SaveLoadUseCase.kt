package com.xianxia.sect.domain.usecase

import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.data.SaveManager
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.model.SaveSlot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SaveLoadUseCase @Inject constructor(
    private val gameEngine: GameEngine,
    private val saveManager: SaveManager
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
                slots = snapshot.buildingSlots,
                events = snapshot.events,
                battleLogs = snapshot.battleLogs,
                alliances = snapshot.alliances,
                supportTeams = snapshot.supportTeams,
                alchemySlots = snapshot.alchemySlots
            )
            
            val success = saveManager.save(slotId, saveData)
            if (success) {
                SaveResult.Success(slotId)
            } else {
                SaveResult.Error("保存失败")
            }
        } catch (e: Exception) {
            SaveResult.Error(e.message ?: "保存失败")
        }
    }
    
    suspend fun loadGame(slotId: Int): LoadResult = withContext(Dispatchers.IO) {
        try {
            val saveData = saveManager.load(slotId)
                ?: return@withContext LoadResult.Error("存档不存在")
            
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
                slots = saveData.slots,
                events = saveData.events,
                battleLogs = saveData.battleLogs,
                alliances = saveData.alliances,
                supportTeams = saveData.supportTeams,
                alchemySlots = saveData.alchemySlots
            )
            
            LoadResult.Success(slotId)
        } catch (e: Exception) {
            LoadResult.Error(e.message ?: "加载失败")
        }
    }
    
    fun getSaveSlots(): List<SaveSlot> {
        return saveManager.getSaveSlots()
    }
    
    fun deleteSave(slotId: Int): Boolean {
        return try {
            saveManager.delete(slotId)
        } catch (e: Exception) {
            false
        }
    }
}
