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
    
    @Query("SELECT * FROM disciples WHERE isAlive = 1 AND realm = :realm ORDER BY cultivation DESC")
    fun getAliveByRealm(realm: Int): Flow<List<Disciple>>
    
    @Query("SELECT * FROM disciples WHERE isAlive = 1 AND realm BETWEEN :minRealm AND :maxRealm ORDER BY realm ASC")
    fun getAliveByRealmRange(minRealm: Int, maxRealm: Int): Flow<List<Disciple>>
    
    @Query("SELECT * FROM disciples WHERE name LIKE '%' || :keyword || '%' AND isAlive = 1")
    suspend fun searchByName(keyword: String): List<Disciple>
    
    @Query("SELECT * FROM disciples WHERE isAlive = 1 AND discipleType = :type ORDER BY realm ASC")
    fun getByDiscipleType(type: String): Flow<List<Disciple>>
    
    @Query("SELECT * FROM disciples WHERE isAlive = 1 AND loyalty < :threshold ORDER BY loyalty ASC")
    fun getLowLoyalty(threshold: Int = 30): Flow<List<Disciple>>
    
    @Query("SELECT * FROM disciples WHERE isAlive = 1 AND age >= :minAge ORDER BY age DESC")
    fun getByMinAge(minAge: Int): Flow<List<Disciple>>
    
    @Query("SELECT COUNT(*) FROM disciples WHERE isAlive = 1")
    fun getAliveCount(): Flow<Int>
    
    @Query("SELECT COUNT(*) FROM disciples WHERE isAlive = 1 AND realm = :realm")
    suspend fun getCountByRealm(realm: Int): Int
    
    @Query("SELECT * FROM disciples WHERE isAlive = 1 AND status = :status ORDER BY realm DESC LIMIT :limit")
    suspend fun getByStatusWithLimit(status: DiscipleStatus, limit: Int): List<Disciple>
    
    @Query("SELECT * FROM disciples WHERE isAlive = 1 AND realm <= :maxRealm ORDER BY realm DESC, cultivation DESC")
    fun getDisciplesForBattle(maxRealm: Int): Flow<List<Disciple>>
    
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
    
    @Query("DELETE FROM disciples WHERE id = :id")
    suspend fun deleteById(id: String)
    
    @Query("DELETE FROM disciples WHERE isAlive = 0")
    suspend fun deleteDeadDisciples(): Int
    
    @Query("DELETE FROM disciples")
    suspend fun deleteAll()
    
    @Transaction
    suspend fun updateBatch(disciples: List<Disciple>) {
        disciples.forEach { update(it) }
    }
}

@Dao
interface EquipmentDao {
    @Query("SELECT * FROM equipment WHERE ownerId IS NULL OR isEquipped = 0")
    fun getUnequipped(): Flow<List<Equipment>>
    
    @Query("SELECT * FROM equipment")
    fun getAll(): Flow<List<Equipment>>
    
    @Query("SELECT * FROM equipment")
    suspend fun getAllSync(): List<Equipment>
    
    @Query("SELECT * FROM equipment WHERE id = :id")
    suspend fun getById(id: String): Equipment?
    
    @Query("SELECT * FROM equipment WHERE ownerId = :discipleId")
    suspend fun getByOwner(discipleId: String): List<Equipment>
    
    @Query("SELECT * FROM equipment WHERE slot = :slot AND (ownerId IS NULL OR isEquipped = 0)")
    fun getUnequippedBySlot(slot: EquipmentSlot): Flow<List<Equipment>>
    
    @Query("SELECT * FROM equipment WHERE rarity = :rarity ORDER BY name ASC")
    fun getByRarity(rarity: Int): Flow<List<Equipment>>
    
    @Query("SELECT * FROM equipment WHERE slot = :slot AND rarity >= :minRarity ORDER BY rarity DESC")
    fun getBySlotAndMinRarity(slot: EquipmentSlot, minRarity: Int): Flow<List<Equipment>>
    
    @Query("SELECT * FROM equipment WHERE minRealm <= :realm AND (ownerId IS NULL OR isEquipped = 0) ORDER BY rarity DESC")
    fun getEquippableForRealm(realm: Int): Flow<List<Equipment>>
    
    @Query("SELECT * FROM equipment WHERE name LIKE '%' || :keyword || '%'")
    suspend fun searchByName(keyword: String): List<Equipment>
    
    @Query("SELECT * FROM equipment WHERE ownerId IS NULL ORDER BY rarity DESC, name ASC")
    fun getUnowned(): Flow<List<Equipment>>
    
    @Query("SELECT COUNT(*) FROM equipment WHERE rarity = :rarity")
    suspend fun getCountByRarity(rarity: Int): Int
    
    @Query("SELECT * FROM equipment WHERE nurtureLevel > 0 ORDER BY nurtureLevel DESC")
    fun getNurtured(): Flow<List<Equipment>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(equipment: Equipment)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(equipments: List<Equipment>)
    
    @Update
    suspend fun update(equipment: Equipment)
    
    @Update
    suspend fun updateAll(equipments: List<Equipment>)
    
    @Delete
    suspend fun delete(equipment: Equipment)
    
    @Query("DELETE FROM equipment WHERE id = :id")
    suspend fun deleteById(id: String)
    
    @Query("DELETE FROM equipment WHERE ownerId IS NULL")
    suspend fun deleteUnowned(): Int
    
    @Query("DELETE FROM equipment")
    suspend fun deleteAll()
    
    @Transaction
    suspend fun updateBatch(equipments: List<Equipment>) {
        equipments.forEach { update(it) }
    }
}

@Dao
interface ManualDao {
    @Query("SELECT * FROM manuals")
    fun getAll(): Flow<List<Manual>>
    
    @Query("SELECT * FROM manuals")
    suspend fun getAllSync(): List<Manual>
    
    @Query("SELECT * FROM manuals WHERE ownerId IS NULL")
    fun getUnlearned(): Flow<List<Manual>>
    
    @Query("SELECT * FROM manuals WHERE ownerId = :discipleId")
    suspend fun getByOwner(discipleId: String): List<Manual>
    
    @Query("SELECT * FROM manuals WHERE id = :id")
    suspend fun getById(id: String): Manual?
    
    @Query("SELECT * FROM manuals WHERE type = :type ORDER BY rarity DESC")
    fun getByType(type: ManualType): Flow<List<Manual>>
    
    @Query("SELECT * FROM manuals WHERE rarity = :rarity AND ownerId IS NULL")
    fun getUnlearnedByRarity(rarity: Int): Flow<List<Manual>>
    
    @Query("SELECT * FROM manuals WHERE minRealm <= :realm AND ownerId IS NULL ORDER BY rarity DESC")
    fun getLearnableForRealm(realm: Int): Flow<List<Manual>>
    
    @Query("SELECT * FROM manuals WHERE name LIKE '%' || :keyword || '%'")
    suspend fun searchByName(keyword: String): List<Manual>
    
    @Query("SELECT * FROM manuals WHERE ownerId IS NULL AND quantity > 0 ORDER BY rarity DESC")
    fun getAvailableManuals(): Flow<List<Manual>>
    
    @Query("SELECT COUNT(*) FROM manuals WHERE ownerId IS NOT NULL")
    fun getLearnedCount(): Flow<Int>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(manual: Manual)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(manuals: List<Manual>)
    
    @Update
    suspend fun update(manual: Manual)
    
    @Update
    suspend fun updateAll(manuals: List<Manual>)
    
    @Delete
    suspend fun delete(manual: Manual)
    
    @Query("DELETE FROM manuals WHERE ownerId IS NULL AND quantity <= 0")
    suspend fun deleteEmptyUnlearned(): Int
    
    @Query("DELETE FROM manuals")
    suspend fun deleteAll()
    
    @Transaction
    suspend fun updateBatch(manuals: List<Manual>) {
        manuals.forEach { update(it) }
    }
}

@Dao
interface PillDao {
    @Query("SELECT * FROM pills WHERE quantity > 0")
    fun getAll(): Flow<List<Pill>>
    
    @Query("SELECT * FROM pills")
    suspend fun getAllSync(): List<Pill>
    
    @Query("SELECT * FROM pills WHERE id = :id")
    suspend fun getById(id: String): Pill?
    
    @Query("SELECT * FROM pills WHERE category = :category AND quantity > 0 ORDER BY rarity DESC")
    fun getByCategory(category: PillCategory): Flow<List<Pill>>
    
    @Query("SELECT * FROM pills WHERE targetRealm = :realm AND quantity > 0 ORDER BY rarity DESC")
    fun getByTargetRealm(realm: Int): Flow<List<Pill>>
    
    @Query("SELECT * FROM pills WHERE rarity >= :minRarity AND quantity > 0 ORDER BY rarity DESC, name ASC")
    fun getByMinRarity(minRarity: Int): Flow<List<Pill>>
    
    @Query("SELECT * FROM pills WHERE name LIKE '%' || :keyword || '%' AND quantity > 0")
    suspend fun searchByName(keyword: String): List<Pill>
    
    @Query("SELECT * FROM pills WHERE breakthroughChance > 0 AND targetRealm = :realm AND quantity > 0 ORDER BY breakthroughChance DESC")
    fun getBreakthroughPillsForRealm(realm: Int): Flow<List<Pill>>
    
    @Query("SELECT * FROM pills WHERE extendLife > 0 AND quantity > 0 ORDER BY extendLife DESC")
    fun getLifeExtensionPills(): Flow<List<Pill>>
    
    @Query("SELECT * FROM pills WHERE revive = 1 AND quantity > 0")
    fun getRevivePills(): Flow<List<Pill>>
    
    @Query("SELECT SUM(quantity) FROM pills WHERE category = :category")
    suspend fun getTotalQuantityByCategory(category: PillCategory): Int
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(pill: Pill)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(pills: List<Pill>)
    
    @Update
    suspend fun update(pill: Pill)
    
    @Update
    suspend fun updateAll(pills: List<Pill>)
    
    @Delete
    suspend fun delete(pill: Pill)
    
    @Query("DELETE FROM pills WHERE quantity <= 0")
    suspend fun deleteEmpty(): Int
    
    @Query("DELETE FROM pills")
    suspend fun deleteAll()
    
    @Transaction
    suspend fun updateBatch(pills: List<Pill>) {
        pills.forEach { update(it) }
    }
}

@Dao
interface MaterialDao {
    @Query("SELECT * FROM materials WHERE quantity > 0")
    fun getAll(): Flow<List<Material>>
    
    @Query("SELECT * FROM materials")
    suspend fun getAllSync(): List<Material>
    
    @Query("SELECT * FROM materials WHERE id = :id")
    suspend fun getById(id: String): Material?
    
    @Query("SELECT * FROM materials WHERE category = :category AND quantity > 0 ORDER BY rarity DESC")
    fun getByCategory(category: MaterialCategory): Flow<List<Material>>
    
    @Query("SELECT * FROM materials WHERE rarity >= :minRarity AND quantity > 0 ORDER BY rarity DESC, name ASC")
    fun getByMinRarity(minRarity: Int): Flow<List<Material>>
    
    @Query("SELECT * FROM materials WHERE name LIKE '%' || :keyword || '%' AND quantity > 0")
    suspend fun searchByName(keyword: String): List<Material>
    
    @Query("SELECT SUM(quantity) FROM materials WHERE category = :category")
    suspend fun getTotalQuantityByCategory(category: MaterialCategory): Int
    
    @Query("SELECT * FROM materials WHERE quantity > 0 ORDER BY category, rarity DESC")
    fun getAllGroupedByCategory(): Flow<List<Material>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(material: Material)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(materials: List<Material>)
    
    @Update
    suspend fun update(material: Material)
    
    @Update
    suspend fun updateAll(materials: List<Material>)
    
    @Delete
    suspend fun delete(material: Material)
    
    @Query("DELETE FROM materials WHERE quantity <= 0")
    suspend fun deleteEmpty(): Int
    
    @Query("DELETE FROM materials")
    suspend fun deleteAll()
    
    @Transaction
    suspend fun updateBatch(materials: List<Material>) {
        materials.forEach { update(it) }
    }
}

@Dao
interface SeedDao {
    @Query("SELECT * FROM seeds WHERE quantity > 0")
    fun getAll(): Flow<List<Seed>>
    
    @Query("SELECT * FROM seeds")
    suspend fun getAllSync(): List<Seed>
    
    @Query("SELECT * FROM seeds WHERE id = :id")
    suspend fun getById(id: String): Seed?
    
    @Query("SELECT * FROM seeds WHERE rarity >= :minRarity AND quantity > 0 ORDER BY rarity DESC, growTime ASC")
    fun getByMinRarity(minRarity: Int): Flow<List<Seed>>
    
    @Query("SELECT * FROM seeds WHERE growTime <= :maxGrowTime AND quantity > 0 ORDER BY growTime ASC")
    fun getByMaxGrowTime(maxGrowTime: Int): Flow<List<Seed>>
    
    @Query("SELECT * FROM seeds WHERE name LIKE '%' || :keyword || '%' AND quantity > 0")
    suspend fun searchByName(keyword: String): List<Seed>
    
    @Query("SELECT SUM(quantity) FROM seeds")
    suspend fun getTotalQuantity(): Int
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(seed: Seed)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(seeds: List<Seed>)
    
    @Update
    suspend fun update(seed: Seed)
    
    @Update
    suspend fun updateAll(seeds: List<Seed>)
    
    @Delete
    suspend fun delete(seed: Seed)
    
    @Query("DELETE FROM seeds WHERE quantity <= 0")
    suspend fun deleteEmpty(): Int
    
    @Query("DELETE FROM seeds")
    suspend fun deleteAll()
    
    @Transaction
    suspend fun updateBatch(seeds: List<Seed>) {
        seeds.forEach { update(it) }
    }
}

@Dao
interface HerbDao {
    @Query("SELECT * FROM herbs WHERE quantity > 0")
    fun getAll(): Flow<List<Herb>>
    
    @Query("SELECT * FROM herbs")
    suspend fun getAllSync(): List<Herb>
    
    @Query("SELECT * FROM herbs WHERE id = :id")
    suspend fun getById(id: String): Herb?
    
    @Query("SELECT * FROM herbs WHERE category = :category AND quantity > 0 ORDER BY rarity DESC")
    fun getByCategory(category: String): Flow<List<Herb>>
    
    @Query("SELECT * FROM herbs WHERE rarity >= :minRarity AND quantity > 0 ORDER BY rarity DESC, name ASC")
    fun getByMinRarity(minRarity: Int): Flow<List<Herb>>
    
    @Query("SELECT * FROM herbs WHERE name LIKE '%' || :keyword || '%' AND quantity > 0")
    suspend fun searchByName(keyword: String): List<Herb>
    
    @Query("SELECT SUM(quantity) FROM herbs WHERE category = :category")
    suspend fun getTotalQuantityByCategory(category: String): Int
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(herb: Herb)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(herbs: List<Herb>)
    
    @Update
    suspend fun update(herb: Herb)
    
    @Update
    suspend fun updateAll(herbs: List<Herb>)
    
    @Delete
    suspend fun delete(herb: Herb)
    
    @Query("DELETE FROM herbs WHERE quantity <= 0")
    suspend fun deleteEmpty(): Int
    
    @Query("DELETE FROM herbs")
    suspend fun deleteAll()
    
    @Transaction
    suspend fun updateBatch(herbs: List<Herb>) {
        herbs.forEach { update(it) }
    }
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
    
    @Query("SELECT * FROM game_events ORDER BY timestamp DESC")
    suspend fun getAllSync(): List<GameEvent>
    
    @Query("SELECT * FROM game_events WHERE id = :id")
    suspend fun getById(id: String): GameEvent?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: GameEvent)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<GameEvent>)
    
    @Query("DELETE FROM game_events WHERE id = :id")
    suspend fun deleteById(id: String)
    
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
    
    @Query("SELECT * FROM battle_logs ORDER BY timestamp DESC")
    suspend fun getAllSync(): List<BattleLog>
    
    @Query("SELECT * FROM battle_logs WHERE id = :id")
    suspend fun getById(id: String): BattleLog?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: BattleLog)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(logs: List<BattleLog>)
    
    @Query("DELETE FROM battle_logs WHERE id = :id")
    suspend fun deleteById(id: String)
    
    @Query("DELETE FROM battle_logs WHERE timestamp < :before")
    suspend fun deleteOld(before: Long)
    
    @Query("DELETE FROM battle_logs")
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

@Dao
interface AlchemySlotDao {
    @Query("SELECT * FROM alchemy_slots ORDER BY slotIndex")
    fun getAll(): Flow<List<AlchemySlot>>
    
    @Query("SELECT * FROM alchemy_slots")
    suspend fun getAllSync(): List<AlchemySlot>
    
    @Query("SELECT * FROM alchemy_slots WHERE slotIndex = :slotIndex")
    suspend fun getBySlotIndex(slotIndex: Int): AlchemySlot?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(slot: AlchemySlot)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(slots: List<AlchemySlot>)
    
    @Update
    suspend fun update(slot: AlchemySlot)
    
    @Delete
    suspend fun delete(slot: AlchemySlot)
    
    @Query("DELETE FROM alchemy_slots")
    suspend fun deleteAll()
}
