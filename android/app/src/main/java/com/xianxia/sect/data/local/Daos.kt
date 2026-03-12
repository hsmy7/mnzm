package com.xianxia.sect.data.local

import androidx.room.*
import com.xianxia.sect.core.model.*
import kotlinx.coroutines.flow.Flow

@Dao
interface GameDataDao {
    @Query("SELECT * FROM game_data LIMIT 1")
    fun getGameData(): Flow<GameData?>
    
    @Query("SELECT * FROM game_data LIMIT 1")
    suspend fun getGameDataSync(): GameData?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(gameData: GameData)
    
    @Update
    suspend fun update(gameData: GameData)
    
    @Query("DELETE FROM game_data")
    suspend fun deleteAll()
}

@Dao
interface DiscipleDao {
    @Query("SELECT * FROM disciples WHERE isAlive = 1 ORDER BY realm ASC, cultivation DESC")
    fun getAllAlive(): Flow<List<Disciple>>
    
    @Query("SELECT * FROM disciples ORDER BY realm ASC, cultivation DESC")
    fun getAll(): Flow<List<Disciple>>
    
    @Query("SELECT * FROM disciples WHERE id = :id")
    suspend fun getById(id: String): Disciple?
    
    @Query("SELECT * FROM disciples WHERE status = :status AND isAlive = 1")
    suspend fun getByStatus(status: DiscipleStatus): List<Disciple>
    
    @Query("SELECT * FROM disciples WHERE isAlive = 1")
    suspend fun getAllAliveSync(): List<Disciple>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(disciple: Disciple)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(disciples: List<Disciple>)
    
    @Update
    suspend fun update(disciple: Disciple)
    
    @Update
    suspend fun updateAll(disciples: List<Disciple>)
    
    @Delete
    suspend fun delete(disciple: Disciple)
    
    @Query("DELETE FROM disciples")
    suspend fun deleteAll()
}

@Dao
interface EquipmentDao {
    @Query("SELECT * FROM equipment WHERE ownerId IS NULL OR isEquipped = 0")
    fun getUnequipped(): Flow<List<Equipment>>
    
    @Query("SELECT * FROM equipment")
    fun getAll(): Flow<List<Equipment>>
    
    @Query("SELECT * FROM equipment WHERE id = :id")
    suspend fun getById(id: String): Equipment?
    
    @Query("SELECT * FROM equipment WHERE ownerId = :discipleId")
    suspend fun getByOwner(discipleId: String): List<Equipment>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(equipment: Equipment)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(equipments: List<Equipment>)
    
    @Update
    suspend fun update(equipment: Equipment)
    
    @Delete
    suspend fun delete(equipment: Equipment)
    
    @Query("DELETE FROM equipment")
    suspend fun deleteAll()
}

@Dao
interface ManualDao {
    @Query("SELECT * FROM manuals")
    fun getAll(): Flow<List<Manual>>
    
    @Query("SELECT * FROM manuals WHERE ownerId IS NULL")
    fun getUnlearned(): Flow<List<Manual>>
    
    @Query("SELECT * FROM manuals WHERE ownerId = :discipleId")
    suspend fun getByOwner(discipleId: String): List<Manual>
    
    @Query("SELECT * FROM manuals WHERE id = :id")
    suspend fun getById(id: String): Manual?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(manual: Manual)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(manuals: List<Manual>)
    
    @Update
    suspend fun update(manual: Manual)
    
    @Delete
    suspend fun delete(manual: Manual)
    
    @Query("DELETE FROM manuals")
    suspend fun deleteAll()
}

@Dao
interface PillDao {
    @Query("SELECT * FROM pills WHERE quantity > 0")
    fun getAll(): Flow<List<Pill>>
    
    @Query("SELECT * FROM pills WHERE id = :id")
    suspend fun getById(id: String): Pill?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(pill: Pill)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(pills: List<Pill>)
    
    @Update
    suspend fun update(pill: Pill)
    
    @Delete
    suspend fun delete(pill: Pill)
    
    @Query("DELETE FROM pills")
    suspend fun deleteAll()
}

@Dao
interface MaterialDao {
    @Query("SELECT * FROM materials WHERE quantity > 0")
    fun getAll(): Flow<List<Material>>
    
    @Query("SELECT * FROM materials WHERE id = :id")
    suspend fun getById(id: String): Material?
    
    @Query("SELECT * FROM materials WHERE category = :category")
    suspend fun getByCategory(category: MaterialCategory): List<Material>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(material: Material)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(materials: List<Material>)
    
    @Update
    suspend fun update(material: Material)
    
    @Delete
    suspend fun delete(material: Material)
    
    @Query("DELETE FROM materials")
    suspend fun deleteAll()
}

@Dao
interface SeedDao {
    @Query("SELECT * FROM seeds WHERE quantity > 0")
    fun getAll(): Flow<List<Seed>>
    
    @Query("SELECT * FROM seeds WHERE id = :id")
    suspend fun getById(id: String): Seed?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(seed: Seed)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(seeds: List<Seed>)
    
    @Update
    suspend fun update(seed: Seed)
    
    @Delete
    suspend fun delete(seed: Seed)
    
    @Query("DELETE FROM seeds")
    suspend fun deleteAll()
}

@Dao
interface HerbDao {
    @Query("SELECT * FROM herbs WHERE quantity > 0")
    fun getAll(): Flow<List<Herb>>
    
    @Query("SELECT * FROM herbs WHERE id = :id")
    suspend fun getById(id: String): Herb?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(herb: Herb)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(herbs: List<Herb>)
    
    @Update
    suspend fun update(herb: Herb)
    
    @Delete
    suspend fun delete(herb: Herb)
    
    @Query("DELETE FROM herbs")
    suspend fun deleteAll()
}

@Dao
interface ExplorationTeamDao {
    @Query("SELECT * FROM exploration_teams")
    fun getAll(): Flow<List<ExplorationTeam>>
    
    @Query("SELECT * FROM exploration_teams WHERE status != 'COMPLETED'")
    fun getActive(): Flow<List<ExplorationTeam>>
    
    @Query("SELECT * FROM exploration_teams WHERE id = :id")
    suspend fun getById(id: String): ExplorationTeam?
    
    @Query("SELECT * FROM exploration_teams")
    suspend fun getAllSync(): List<ExplorationTeam>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(team: ExplorationTeam)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(teams: List<ExplorationTeam>)
    
    @Update
    suspend fun update(team: ExplorationTeam)
    
    @Update
    suspend fun updateAll(teams: List<ExplorationTeam>)
    
    @Delete
    suspend fun delete(team: ExplorationTeam)
    
    @Query("DELETE FROM exploration_teams")
    suspend fun deleteAll()
}

@Dao
interface BuildingSlotDao {
    @Query("SELECT * FROM building_slots WHERE buildingId = :buildingId ORDER BY slotIndex")
    fun getByBuilding(buildingId: String): Flow<List<BuildingSlot>>
    
    @Query("SELECT * FROM building_slots")
    fun getAll(): Flow<List<BuildingSlot>>
    
    @Query("SELECT * FROM building_slots WHERE buildingId = :buildingId")
    suspend fun getByBuildingSync(buildingId: String): List<BuildingSlot>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(slot: BuildingSlot)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(slots: List<BuildingSlot>)
    
    @Update
    suspend fun update(slot: BuildingSlot)
    
    @Delete
    suspend fun delete(slot: BuildingSlot)
    
    @Query("DELETE FROM building_slots")
    suspend fun deleteAll()
}

@Dao
interface GameEventDao {
    @Query("SELECT * FROM game_events ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(limit: Int = 50): Flow<List<GameEvent>>
    
    @Query("SELECT * FROM game_events ORDER BY timestamp DESC")
    fun getAll(): Flow<List<GameEvent>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: GameEvent)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<GameEvent>)
    
    @Query("DELETE FROM game_events WHERE timestamp < :before")
    suspend fun deleteOld(before: Long)
    
    @Query("DELETE FROM game_events")
    suspend fun deleteAll()
}

@Dao
interface DungeonDao {
    @Query("SELECT * FROM dungeons WHERE isUnlocked = 1")
    fun getUnlocked(): Flow<List<Dungeon>>
    
    @Query("SELECT * FROM dungeons")
    fun getAll(): Flow<List<Dungeon>>
    
    @Query("SELECT * FROM dungeons WHERE id = :id")
    suspend fun getById(id: String): Dungeon?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(dungeon: Dungeon)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(dungeons: List<Dungeon>)
    
    @Update
    suspend fun update(dungeon: Dungeon)
    
    @Query("DELETE FROM dungeons")
    suspend fun deleteAll()
}

@Dao
interface RecipeDao {
    @Query("SELECT * FROM recipes WHERE isUnlocked = 1")
    fun getUnlocked(): Flow<List<Recipe>>
    
    @Query("SELECT * FROM recipes")
    fun getAll(): Flow<List<Recipe>>
    
    @Query("SELECT * FROM recipes WHERE type = :type AND isUnlocked = 1")
    fun getByType(type: RecipeType): Flow<List<Recipe>>
    
    @Query("SELECT * FROM recipes WHERE id = :id")
    suspend fun getById(id: String): Recipe?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recipe: Recipe)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(recipes: List<Recipe>)
    
    @Update
    suspend fun update(recipe: Recipe)
    
    @Query("DELETE FROM recipes")
    suspend fun deleteAll()
}

@Dao
interface BattleLogDao {
    @Query("SELECT * FROM battle_logs ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(limit: Int = 50): Flow<List<BattleLog>>
    
    @Query("SELECT * FROM battle_logs ORDER BY timestamp DESC")
    fun getAll(): Flow<List<BattleLog>>
    
    @Query("SELECT * FROM battle_logs WHERE id = :id")
    suspend fun getById(id: String): BattleLog?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: BattleLog)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(logs: List<BattleLog>)
    
    @Query("DELETE FROM battle_logs WHERE timestamp < :before")
    suspend fun deleteOld(before: Long)
    
    @Query("DELETE FROM battle_logs")
    suspend fun deleteAll()
}

@Dao
interface WarTeamDao {
    @Query("SELECT * FROM war_teams")
    fun getAll(): Flow<List<WarTeam>>
    
    @Query("SELECT * FROM war_teams WHERE id = :id")
    suspend fun getById(id: String): WarTeam?
    
    @Query("SELECT * FROM war_teams WHERE status = :status")
    suspend fun getByStatus(status: WarTeamStatus): List<WarTeam>
    
    @Query("SELECT * FROM war_teams WHERE stationedSectId = :sectId")
    suspend fun getByStationedSect(sectId: String): List<WarTeam>
    
    @Query("SELECT * FROM war_teams WHERE targetSectId = :sectId")
    suspend fun getByTargetSect(sectId: String): List<WarTeam>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(warTeam: WarTeam)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(warTeams: List<WarTeam>)
    
    @Update
    suspend fun update(warTeam: WarTeam)
    
    @Update
    suspend fun updateAll(warTeams: List<WarTeam>)
    
    @Delete
    suspend fun delete(warTeam: WarTeam)
    
    @Query("DELETE FROM war_teams")
    suspend fun deleteAll()
}

@Dao
interface ForgeSlotDao {
    @Query("SELECT * FROM forge_slots ORDER BY slotIndex")
    fun getAll(): Flow<List<ForgeSlot>>
    
    @Query("SELECT * FROM forge_slots WHERE slotIndex = :slotIndex")
    suspend fun getBySlotIndex(slotIndex: Int): ForgeSlot?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(slot: ForgeSlot)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(slots: List<ForgeSlot>)
    
    @Update
    suspend fun update(slot: ForgeSlot)
    
    @Delete
    suspend fun delete(slot: ForgeSlot)
    
    @Query("DELETE FROM forge_slots")
    suspend fun deleteAll()
}
