package com.xianxia.sect.core.repository

import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import kotlinx.coroutines.flow.Flow

// Domain-level repository interfaces — engine depends on these, not on data module DAOs.

interface DiscipleRepository {
    fun getDisciples(slotId: Int = 0): Flow<List<Disciple>>
    fun getAliveDisciples(slotId: Int = 0): Flow<List<Disciple>>
    suspend fun getDiscipleById(id: String, slotId: Int = 0): Disciple?
    suspend fun getDisciplesByStatus(status: DiscipleStatus, slotId: Int = 0): List<Disciple>
    suspend fun getAllDisciplesSync(slotId: Int = 0): List<Disciple>
}

interface WorldRepository {
    fun getTeams(slotId: Int = 0): Flow<List<ExplorationTeam>>
    fun getActiveTeams(slotId: Int = 0): Flow<List<ExplorationTeam>>
    suspend fun getTeamById(id: String, slotId: Int = 0): ExplorationTeam?
    suspend fun getAllTeamsSync(slotId: Int = 0): List<ExplorationTeam>
    fun getBuildingSlots(buildingId: String, slotId: Int = 0): Flow<List<BuildingSlot>>
    fun getAllBuildingSlots(slotId: Int = 0): Flow<List<BuildingSlot>>
    suspend fun getBuildingSlotsSync(buildingId: String, slotId: Int = 0): List<BuildingSlot>
    fun getUnlockedRecipes(slotId: Int = 0): Flow<List<Recipe>>
    fun getAllRecipes(slotId: Int = 0): Flow<List<Recipe>>
    fun getRecipesByType(type: RecipeType, slotId: Int = 0): Flow<List<Recipe>>
    suspend fun getRecipeById(id: String, slotId: Int = 0): Recipe?
    fun getRecentBattleLogs(limit: Int = 50, slotId: Int = 0): Flow<List<BattleLog>>
    fun getAllBattleLogs(slotId: Int = 0): Flow<List<BattleLog>>
    suspend fun getBattleLogById(id: String, slotId: Int = 0): BattleLog?
}

interface InventoryRepository {
    fun getManualStacks(slotId: Int = 0): Flow<List<ManualStack>>
    suspend fun getManualStackById(id: String, slotId: Int = 0): ManualStack?
    fun getManualInstances(slotId: Int = 0): Flow<List<ManualInstance>>
    suspend fun getManualInstanceById(id: String, slotId: Int = 0): ManualInstance?
    suspend fun getManualInstancesByOwner(discipleId: String, slotId: Int = 0): List<ManualInstance>
    fun getPills(slotId: Int = 0): Flow<List<Pill>>
    suspend fun getPillById(id: String, slotId: Int = 0): Pill?
    fun getMaterials(slotId: Int = 0): Flow<List<Material>>
    suspend fun getMaterialById(id: String, slotId: Int = 0): Material?
    fun getMaterialsByCategory(category: MaterialCategory, slotId: Int = 0): Flow<List<Material>>
    fun getSeeds(slotId: Int = 0): Flow<List<Seed>>
    suspend fun getSeedById(id: String, slotId: Int = 0): Seed?
    fun getHerbs(slotId: Int = 0): Flow<List<Herb>>
    suspend fun getHerbById(id: String, slotId: Int = 0): Herb?
}

interface EquipmentRepository {
    fun getEquipmentStacks(slotId: Int = 0): Flow<List<EquipmentStack>>
    suspend fun getEquipmentStackById(id: String, slotId: Int = 0): EquipmentStack?
    fun getEquipmentInstances(slotId: Int = 0): Flow<List<EquipmentInstance>>
    suspend fun getEquipmentInstanceById(id: String, slotId: Int = 0): EquipmentInstance?
    suspend fun getEquipmentInstancesByOwner(discipleId: String, slotId: Int = 0): List<EquipmentInstance>
}

interface ForgeRepository {
    suspend fun getForgeSlotBySlotIndex(slotIndex: Int, slotId: Int = 0): ForgeSlot?
}

interface GameDataRepository {
    fun getGameData(slotId: Int = 0): Flow<GameData?>
    suspend fun getGameDataSync(slotId: Int = 0): GameData?
    suspend fun initializeNewGame(): GameData
    suspend fun clearAllData(slotId: Int = 0)
}

/** Persistence port for ProductionSlotRepository — stays in engine, DAO access through this port. */
interface ProductionSlotDataPort {
    suspend fun getAllSync(): List<ProductionSlot>
    suspend fun insertAll(slots: List<ProductionSlot>)
    suspend fun update(slot: ProductionSlot)
    suspend fun updateAll(slots: List<ProductionSlot>)
    suspend fun insert(slot: ProductionSlot)
    suspend fun deleteById(id: String)
    suspend fun deleteBySlot(slotId: Int)
    suspend fun deleteBySlotAndBuildingType(slotId: Int, buildingType: BuildingType)
}

/** Persistence port for GameHeavyData. */
interface GameHeavyDataPort {
    suspend fun getLoadedKeys(slot: Int): List<String>
    suspend fun getByKey(slot: Int, key: String): GameHeavyData?
    suspend fun deleteByKey(slot: Int, key: String)
}

/** Persistence port for WorldMapState — read redundant world_map_state table. */
interface WorldMapStatePort {
    suspend fun getBySlot(slot: Int): WorldMapStateEntity?
}

/** Decodes heavy data rows from storage into typed game state. */
interface HeavyDataDecoder {
    fun decodeDiscipleListMapFromRows(rows: List<com.xianxia.sect.core.model.GameHeavyData>, key: String): Map<String, List<com.xianxia.sect.core.model.Disciple>>
    fun decodeSectDetailMapFromRows(rows: List<com.xianxia.sect.core.model.GameHeavyData>, key: String): Map<String, com.xianxia.sect.core.model.SectDetail>
    fun decodeExploredSectInfoMapFromRows(rows: List<com.xianxia.sect.core.model.GameHeavyData>, key: String): Map<String, com.xianxia.sect.core.model.ExploredSectInfo>
    fun decodeSectScoutInfoMapFromRows(rows: List<com.xianxia.sect.core.model.GameHeavyData>, key: String): Map<String, com.xianxia.sect.core.model.SectScoutInfo>
    fun decodeManualProficiencyMapFromRows(rows: List<com.xianxia.sect.core.model.GameHeavyData>, key: String): Map<String, List<com.xianxia.sect.core.model.ManualProficiencyData>>
    fun decodeDiscipleListFromRows(rows: List<com.xianxia.sect.core.model.GameHeavyData>, key: String): List<com.xianxia.sect.core.model.Disciple>
    fun decodeWorldSectListFromRows(rows: List<com.xianxia.sect.core.model.GameHeavyData>, key: String): List<com.xianxia.sect.core.model.WorldSect>
}
