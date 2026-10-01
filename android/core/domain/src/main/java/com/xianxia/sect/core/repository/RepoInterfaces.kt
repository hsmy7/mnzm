package com.xianxia.sect.core.repository

import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.BuildingSlot
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.ExploredSectInfo
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GameHeavyData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.MaterialCategory
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Recipe
import com.xianxia.sect.core.model.RecipeType
import com.xianxia.sect.core.model.SectDetail
import com.xianxia.sect.core.model.SectScoutInfo
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import kotlinx.coroutines.flow.Flow



// Domain-level repository interfaces — engine depends on these, not on data module DAOs.

interface DiscipleRepository {
    fun getDisciples(): Flow<List<Disciple>>
    fun getAliveDisciples(): Flow<List<Disciple>>
    suspend fun getDiscipleById(id: String): Disciple?
    suspend fun getDisciplesByStatus(status: DiscipleStatus): List<Disciple>
    suspend fun getAllDisciplesSync(): List<Disciple>
}

interface WorldRepository {
    fun getBuildingSlots(buildingId: String): Flow<List<BuildingSlot>>
    fun getAllBuildingSlots(): Flow<List<BuildingSlot>>
    suspend fun getBuildingSlotsSync(buildingId: String): List<BuildingSlot>
    fun getUnlockedRecipes(): Flow<List<Recipe>>
    fun getAllRecipes(): Flow<List<Recipe>>
    fun getRecipesByType(type: RecipeType): Flow<List<Recipe>>
    suspend fun getRecipeById(id: String): Recipe?
    fun getRecentBattleLogs(limit: Int = 50): Flow<List<BattleLog>>
    fun getAllBattleLogs(): Flow<List<BattleLog>>
    suspend fun getBattleLogById(id: String): BattleLog?
}

@Suppress("TooManyFunctions") // 库存仓储契约：六类物品+钱包的查询/变更端口，函数数即仓储协议面
interface InventoryRepository {
    fun getManualStacks(): Flow<List<ManualStack>>
    suspend fun getManualStackById(id: String): ManualStack?
    fun getManualInstances(): Flow<List<ManualInstance>>
    suspend fun getManualInstanceById(id: String): ManualInstance?
    suspend fun getManualInstancesByOwner(discipleId: String): List<ManualInstance>
    fun getPills(): Flow<List<Pill>>
    suspend fun getPillById(id: String): Pill?
    fun getMaterials(): Flow<List<Material>>
    suspend fun getMaterialById(id: String): Material?
    fun getMaterialsByCategory(category: MaterialCategory): Flow<List<Material>>
    fun getSeeds(): Flow<List<Seed>>
    suspend fun getSeedById(id: String): Seed?
    fun getHerbs(): Flow<List<Herb>>
    suspend fun getHerbById(id: String): Herb?
}

interface EquipmentRepository {
    fun getEquipmentInstances(): Flow<List<EquipmentInstance>>
    suspend fun getEquipmentInstanceById(id: String): EquipmentInstance?
    suspend fun getEquipmentInstancesByOwner(discipleId: String): List<EquipmentInstance>
}

interface GameDataRepository {
    fun getGameData(): Flow<GameData?>
    suspend fun getGameDataSync(): GameData?
    suspend fun initializeNewGame(): GameData
    suspend fun clearAllData()
}

/** Persistence port for ProductionSlotRepository — stays in engine, DAO access through this port. */
interface ProductionSlotDataPort {
    fun getAllSync(): List<ProductionSlot>
    suspend fun insertAll(slots: List<ProductionSlot>)
    suspend fun update(slot: ProductionSlot)
    suspend fun updateAll(slots: List<ProductionSlot>)
    suspend fun insert(slot: ProductionSlot)
    suspend fun deleteById(id: String)
    suspend fun deleteAll()
    suspend fun deleteByBuildingType(buildingType: BuildingType)
}

/** Persistence port for GameHeavyData. */
interface GameHeavyDataPort {
    fun getLoadedKeys(): List<String>
    fun getByKey(key: String): GameHeavyData?
    fun deleteByKey(key: String)
}

/** Decodes heavy data rows from storage into typed game state. */
interface HeavyDataDecoder {
    fun decodeDiscipleListMapFromRows(rows: List<com.xianxia.sect.core.model.GameHeavyData>, key: String): Map<String,
        List<com.xianxia.sect.core.model.Disciple>>
    fun decodeSectDetailMapFromRows(rows: List<com.xianxia.sect.core.model.GameHeavyData>, key: String): Map<String,
        com.xianxia.sect.core.model.SectDetail>
    fun decodeExploredSectInfoMapFromRows(rows: List<com.xianxia.sect.core.model.GameHeavyData>,
        key: String): Map<String, com.xianxia.sect.core.model.ExploredSectInfo>
    fun decodeSectScoutInfoMapFromRows(rows: List<com.xianxia.sect.core.model.GameHeavyData>, key: String): Map<String,
        com.xianxia.sect.core.model.SectScoutInfo>
    fun decodeManualProficiencyMapFromRows(rows: List<com.xianxia.sect.core.model.GameHeavyData>,
        key: String): Map<String, List<com.xianxia.sect.core.model.ManualProficiencyData>>
    fun decodeDiscipleListFromRows(rows: List<com.xianxia.sect.core.model.GameHeavyData>,
        key: String): List<com.xianxia.sect.core.model.Disciple>
    fun decodeWorldSectListFromRows(rows: List<com.xianxia.sect.core.model.GameHeavyData>,
        key: String): List<com.xianxia.sect.core.model.WorldSect>
}
