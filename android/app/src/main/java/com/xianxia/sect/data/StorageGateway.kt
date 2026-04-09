@file:Suppress("DEPRECATION")

package com.xianxia.sect.data

import android.util.Log
import com.xianxia.sect.core.model.*
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.orchestrator.StorageOrchestrator
import com.xianxia.sect.data.unified.SaveResult
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StorageGateway @Inject constructor(
    private val saveRepository: com.xianxia.sect.data.unified.SaveRepository,
    private val gameRepository: GameRepository,
    private val orchestrator: StorageOrchestrator
) {
    companion object {
        private const val TAG = "StorageGateway"
    }

    suspend fun saveSlot(slotId: Int, saveData: SaveData): SaveResult<Unit> {
        Log.d(TAG, "saveSlot() delegated to orchestrator | slot=$slotId")
        return orchestrator.saveSlot(slotId, saveData)
    }

    suspend fun loadSlot(slotId: Int): SaveResult<SaveData> {
        Log.d(TAG, "loadSlot() delegated to orchestrator | slot=$slotId")
        return orchestrator.loadSlot(slotId)
    }

    suspend fun deleteSlot(slotId: Int): SaveResult<Unit> {
        Log.d(TAG, "deleteSlot() delegated to orchestrator | slot=$slotId")
        return orchestrator.deleteSlot(slotId)
    }

    suspend fun hasSaveData(slotId: Int): Boolean {
        return saveRepository.hasSave(slotId)
    }

    fun getGameDataFlow(slotId: Int = GameRepository.DEFAULT_SLOT_ID): Flow<GameData?> = gameRepository.getGameData(slotId)

    suspend fun getGameDataSync(slotId: Int = GameRepository.DEFAULT_SLOT_ID): GameData? = gameRepository.getGameDataSync(slotId)

    suspend fun saveGameData(gameData: GameData) = gameRepository.saveGameData(gameData)

    fun getDisciplesFlow(slotId: Int = GameRepository.DEFAULT_SLOT_ID): Flow<List<Disciple>> = gameRepository.getDisciples(slotId)

    suspend fun getDiscipleById(id: String, slotId: Int = GameRepository.DEFAULT_SLOT_ID): Disciple? = gameRepository.getDiscipleById(id, slotId)

    suspend fun addDisciple(disciple: Disciple) = gameRepository.addDisciple(disciple)

    suspend fun updateDisciple(disciple: Disciple) = gameRepository.updateDisciple(disciple)

    suspend fun deleteDisciple(disciple: Disciple) = gameRepository.deleteDisciple(disciple)

    fun getEquipmentFlow(slotId: Int = GameRepository.DEFAULT_SLOT_ID): Flow<List<Equipment>> = gameRepository.getEquipment(slotId)

    suspend fun getEquipmentById(id: String, slotId: Int = GameRepository.DEFAULT_SLOT_ID): Equipment? = gameRepository.getEquipmentById(id, slotId)

    suspend fun addEquipment(equipment: Equipment) = gameRepository.addEquipment(equipment)

    suspend fun updateEquipment(equipment: Equipment) = gameRepository.updateEquipment(equipment)

    suspend fun deleteEquipment(equipment: Equipment) = gameRepository.deleteEquipment(equipment)

    fun getManualsFlow(slotId: Int = GameRepository.DEFAULT_SLOT_ID): Flow<List<Manual>> = gameRepository.getManuals(slotId)

    suspend fun getManualById(id: String, slotId: Int = GameRepository.DEFAULT_SLOT_ID): Manual? = gameRepository.getManualById(id, slotId)

    suspend fun addManual(manual: Manual) = gameRepository.addManual(manual)

    suspend fun updateManual(manual: Manual) = gameRepository.updateManual(manual)

    fun getPillsFlow(slotId: Int = GameRepository.DEFAULT_SLOT_ID): Flow<List<Pill>> = gameRepository.getPills(slotId)

    suspend fun getPillById(id: String, slotId: Int = GameRepository.DEFAULT_SLOT_ID): Pill? = gameRepository.getPillById(id, slotId)

    suspend fun addPill(pill: Pill) = gameRepository.addPill(pill)

    suspend fun updatePill(pill: Pill) = gameRepository.updatePill(pill)

    fun getMaterialsFlow(slotId: Int = GameRepository.DEFAULT_SLOT_ID): Flow<List<Material>> = gameRepository.getMaterials(slotId)

    suspend fun getMaterialById(id: String, slotId: Int = GameRepository.DEFAULT_SLOT_ID): Material? = gameRepository.getMaterialById(id, slotId)

    suspend fun addMaterial(material: Material) = gameRepository.addMaterial(material)

    suspend fun updateMaterial(material: Material) = gameRepository.updateMaterial(material)

    fun getSeedsFlow(slotId: Int = GameRepository.DEFAULT_SLOT_ID): Flow<List<Seed>> = gameRepository.getSeeds(slotId)

    suspend fun addSeed(seed: Seed) = gameRepository.addSeed(seed)

    suspend fun updateSeed(seed: Seed) = gameRepository.updateSeed(seed)

    fun getHerbsFlow(slotId: Int = GameRepository.DEFAULT_SLOT_ID): Flow<List<Herb>> = gameRepository.getHerbs(slotId)

    suspend fun addHerb(herb: Herb) = gameRepository.addHerb(herb)

    suspend fun updateHerb(herb: Herb) = gameRepository.updateHerb(herb)

    fun getTeamsFlow(slotId: Int = GameRepository.DEFAULT_SLOT_ID): Flow<List<ExplorationTeam>> = gameRepository.getTeams(slotId)

    suspend fun getTeamById(id: String, slotId: Int = GameRepository.DEFAULT_SLOT_ID): ExplorationTeam? = gameRepository.getTeamById(id, slotId)

    suspend fun addTeam(team: ExplorationTeam) = gameRepository.addTeam(team)

    suspend fun updateTeam(team: ExplorationTeam) = gameRepository.updateTeam(team)

    fun getEventsFlow(limit: Int = 50, slotId: Int = GameRepository.DEFAULT_SLOT_ID): Flow<List<GameEvent>> = gameRepository.getRecentEvents(limit, slotId)

    suspend fun addEvent(event: GameEvent) = gameRepository.addEvent(event)

    suspend fun deleteOldEvents(before: Long, slotId: Int = GameRepository.DEFAULT_SLOT_ID) = gameRepository.deleteOldEvents(before, slotId)

    fun getBattleLogsFlow(limit: Int = 50, slotId: Int = GameRepository.DEFAULT_SLOT_ID): Flow<List<BattleLog>> =
        gameRepository.getRecentBattleLogs(limit, slotId)

    suspend fun addBattleLog(log: BattleLog) = gameRepository.addBattleLog(log)

    suspend fun deleteOldBattleLogs(before: Long, slotId: Int = GameRepository.DEFAULT_SLOT_ID) = gameRepository.deleteOldBattleLogs(before, slotId)

    suspend fun initializeNewGame(): GameData {
        Log.i(TAG, "initializeNewGame() called")
        return gameRepository.initializeNewGame()
    }

    suspend fun clearAllData(slotId: Int = GameRepository.DEFAULT_SLOT_ID) {
        Log.w(TAG, "clearAllData() called - this will delete all data in slot $slotId")
        gameRepository.clearAllData(slotId)
    }
}
