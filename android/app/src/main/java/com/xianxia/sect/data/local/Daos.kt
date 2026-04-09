@file:Suppress("DEPRECATION")

package com.xianxia.sect.data.local

import androidx.room.*
import com.xianxia.sect.core.model.*
import kotlinx.coroutines.flow.Flow


@Dao
interface GameDataDao {
    @Query("SELECT * FROM game_data WHERE slot_id = :slotId LIMIT 1")
    fun getGameData(slotId: Int): Flow<GameData?>

    @Query("SELECT * FROM game_data WHERE slot_id = :slotId LIMIT 1")
    suspend fun getGameDataSync(slotId: Int): GameData?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(gameData: GameData)

    @Update
    suspend fun update(gameData: GameData)

    /** 获取指定槽位下已有的实体 ID 集合（用于 UPSERT 差量写入） */
    @Query("SELECT id FROM game_data WHERE slot_id = :slotId")
    suspend fun getIdsBySlot(slotId: Int): List<String>

    @Query("DELETE FROM game_data WHERE slot_id = :slotId")
    suspend fun deleteAll(slotId: Int)

    // 全局删除（管理员操作用，如卸载清理）
    @Query("DELETE FROM game_data")
    suspend fun deleteAllGlobal()
}

@Dao
interface DiscipleDao {
    @Query("SELECT * FROM disciples WHERE slot_id = :slotId AND isAlive = 1 ORDER BY realm ASC, cultivation DESC")
    fun getAllAlive(slotId: Int): Flow<List<Disciple>>

    @Query("SELECT * FROM disciples WHERE slot_id = :slotId ORDER BY realm ASC, cultivation DESC")
    fun getAll(slotId: Int): Flow<List<Disciple>>

    // 通过主键ID查询，discipleId 全局唯一，但建议也加 slotId 用于安全校验
    @Query("SELECT * FROM disciples WHERE slot_id = :slotId AND id = :id")
    suspend fun getById(slotId: Int, id: String): Disciple?

    @Query("SELECT * FROM disciples WHERE slot_id = :slotId AND status = :status AND isAlive = 1")
    suspend fun getByStatus(slotId: Int, status: DiscipleStatus): List<Disciple>

    @Query("SELECT * FROM disciples WHERE slot_id = :slotId AND isAlive = 1")
    suspend fun getAllAliveSync(slotId: Int): List<Disciple>

    @Query("SELECT * FROM disciples WHERE slot_id = :slotId AND isAlive = 1 AND realm = :realm ORDER BY cultivation DESC")
    fun getAliveByRealm(slotId: Int, realm: Int): Flow<List<Disciple>>

    @Query("SELECT * FROM disciples WHERE slot_id = :slotId AND isAlive = 1 AND realm BETWEEN :minRealm AND :maxRealm ORDER BY realm ASC")
    fun getAliveByRealmRange(slotId: Int, minRealm: Int, maxRealm: Int): Flow<List<Disciple>>

    @Query("SELECT * FROM disciples WHERE slot_id = :slotId AND name LIKE '%' || :keyword || '%' AND isAlive = 1")
    suspend fun searchByName(slotId: Int, keyword: String): List<Disciple>

    @Query("SELECT * FROM disciples WHERE slot_id = :slotId AND isAlive = 1 AND discipleType = :type ORDER BY realm ASC")
    fun getByDiscipleType(slotId: Int, type: String): Flow<List<Disciple>>

    @Query("SELECT * FROM disciples WHERE slot_id = :slotId AND isAlive = 1 AND loyalty < :threshold ORDER BY loyalty ASC")
    fun getLowLoyalty(slotId: Int, threshold: Int = 30): Flow<List<Disciple>>

    @Query("SELECT * FROM disciples WHERE slot_id = :slotId AND isAlive = 1 AND age >= :minAge ORDER BY age DESC")
    fun getByMinAge(slotId: Int, minAge: Int): Flow<List<Disciple>>

    @Query("SELECT COUNT(*) FROM disciples WHERE slot_id = :slotId AND isAlive = 1")
    fun getAliveCount(slotId: Int): Flow<Int>

    @Query("SELECT COUNT(*) FROM disciples WHERE slot_id = :slotId AND isAlive = 1 AND realm = :realm")
    suspend fun getCountByRealm(slotId: Int, realm: Int): Int

    @Query("SELECT * FROM disciples WHERE slot_id = :slotId AND isAlive = 1 AND status = :status ORDER BY realm DESC LIMIT :limit")
    suspend fun getByStatusWithLimit(slotId: Int, status: DiscipleStatus, limit: Int): List<Disciple>

    @Query("SELECT * FROM disciples WHERE slot_id = :slotId AND isAlive = 1 AND realm <= :maxRealm ORDER BY realm DESC, cultivation DESC")
    fun getDisciplesForBattle(slotId: Int, maxRealm: Int): Flow<List<Disciple>>

    // Insert/Update/Delete 操作基于实体，实体层负责设置正确的 slot_id
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(disciple: Disciple)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(disciples: List<Disciple>)

    @Update
    suspend fun update(disciple: Disciple)

    @Update
    suspend fun updateAll(disciples: List<Disciple>)

    /** 获取指定槽位下已有的实体 ID 集合（用于 UPSERT 差量写入） */
    @Query("SELECT id FROM disciples WHERE slot_id = :slotId")
    suspend fun getIdsBySlot(slotId: Int): List<String>

    @Delete
    suspend fun delete(disciple: Disciple)

    @Query("DELETE FROM disciples WHERE slot_id = :slotId AND id = :id")
    suspend fun deleteById(slotId: Int, id: String)

    @Query("DELETE FROM disciples WHERE slot_id = :slotId AND isAlive = 0")
    suspend fun deleteDeadDisciples(slotId: Int): Int

    @Query("DELETE FROM disciples WHERE slot_id = :slotId")
    suspend fun deleteAll(slotId: Int)

    // 全局删除（管理员操作用）
    @Query("DELETE FROM disciples")
    suspend fun deleteAllGlobal()

    @Transaction
    suspend fun updateBatch(disciples: List<Disciple>) {
        disciples.forEach { update(it) }
    }
}

@Dao
interface EquipmentDao {
    @Query("SELECT * FROM equipment WHERE slot_id = :slotId AND (ownerId IS NULL OR isEquipped = 0)")
    fun getUnequipped(slotId: Int): Flow<List<Equipment>>

    @Query("SELECT * FROM equipment WHERE slot_id = :slotId")
    fun getAll(slotId: Int): Flow<List<Equipment>>

    @Query("SELECT * FROM equipment WHERE slot_id = :slotId")
    suspend fun getAllSync(slotId: Int): List<Equipment>

    // 通过主键ID查询，全局唯一，但建议也加 slotId 用于安全校验
    @Query("SELECT * FROM equipment WHERE slot_id = :slotId AND id = :id")
    suspend fun getById(slotId: Int, id: String): Equipment?

    // 通过外键 ownerId 查询，ownerId 关联弟子表，建议加 slotId 过滤
    @Query("SELECT * FROM equipment WHERE slot_id = :slotId AND ownerId = :discipleId")
    suspend fun getByOwner(slotId: Int, discipleId: String): List<Equipment>

    @Query("SELECT * FROM equipment WHERE slot_id = :slotId AND slot = :slot AND (ownerId IS NULL OR isEquipped = 0)")
    fun getUnequippedBySlot(slotId: Int, slot: EquipmentSlot): Flow<List<Equipment>>

    @Query("SELECT * FROM equipment WHERE slot_id = :slotId AND rarity = :rarity ORDER BY name ASC")
    fun getByRarity(slotId: Int, rarity: Int): Flow<List<Equipment>>

    @Query("SELECT * FROM equipment WHERE slot_id = :slotId AND slot = :slot AND rarity >= :minRarity ORDER BY rarity DESC")
    fun getBySlotAndMinRarity(slotId: Int, slot: EquipmentSlot, minRarity: Int): Flow<List<Equipment>>

    @Query("SELECT * FROM equipment WHERE slot_id = :slotId AND minRealm <= :realm AND (ownerId IS NULL OR isEquipped = 0) ORDER BY rarity DESC")
    fun getEquippableForRealm(slotId: Int, realm: Int): Flow<List<Equipment>>

    @Query("SELECT * FROM equipment WHERE slot_id = :slotId AND name LIKE '%' || :keyword || '%'")
    suspend fun searchByName(slotId: Int, keyword: String): List<Equipment>

    @Query("SELECT * FROM equipment WHERE slot_id = :slotId AND ownerId IS NULL ORDER BY rarity DESC, name ASC")
    fun getUnowned(slotId: Int): Flow<List<Equipment>>

    @Query("SELECT COUNT(*) FROM equipment WHERE slot_id = :slotId AND rarity = :rarity")
    suspend fun getCountByRarity(slotId: Int, rarity: Int): Int

    @Query("SELECT * FROM equipment WHERE slot_id = :slotId AND nurtureLevel > 0 ORDER BY nurtureLevel DESC")
    fun getNurtured(slotId: Int): Flow<List<Equipment>>

    // Insert/Update/Delete 操作基于实体，实体层负责设置正确的 slot_id
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(equipment: Equipment)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(equipments: List<Equipment>)

    @Update
    suspend fun update(equipment: Equipment)

    @Update
    suspend fun updateAll(equipments: List<Equipment>)

    /** 获取指定槽位下已有的实体 ID 集合（用于 UPSERT 差量写入） */
    @Query("SELECT id FROM equipment WHERE slot_id = :slotId")
    suspend fun getIdsBySlot(slotId: Int): List<String>

    @Delete
    suspend fun delete(equipment: Equipment)

    @Query("DELETE FROM equipment WHERE slot_id = :slotId AND id = :id")
    suspend fun deleteById(slotId: Int, id: String)

    @Query("DELETE FROM equipment WHERE slot_id = :slotId AND ownerId IS NULL")
    suspend fun deleteUnowned(slotId: Int): Int

    @Query("DELETE FROM equipment WHERE slot_id = :slotId")
    suspend fun deleteAll(slotId: Int)

    // 全局删除（管理员操作用）
    @Query("DELETE FROM equipment")
    suspend fun deleteAllGlobal()

    @Transaction
    suspend fun updateBatch(equipments: List<Equipment>) {
        equipments.forEach { update(it) }
    }
}

@Dao
interface ManualDao {
    @Query("SELECT * FROM manuals WHERE slot_id = :slotId")
    fun getAll(slotId: Int): Flow<List<Manual>>

    @Query("SELECT * FROM manuals WHERE slot_id = :slotId")
    suspend fun getAllSync(slotId: Int): List<Manual>

    @Query("SELECT * FROM manuals WHERE slot_id = :slotId AND ownerId IS NULL")
    fun getUnlearned(slotId: Int): Flow<List<Manual>>

    // 通过外键 ownerId 查询，建议加 slotId 过滤
    @Query("SELECT * FROM manuals WHERE slot_id = :slotId AND ownerId = :discipleId")
    suspend fun getByOwner(slotId: Int, discipleId: String): List<Manual>

    // 通过主键ID查询，全局唯一，但建议也加 slotId 用于安全校验
    @Query("SELECT * FROM manuals WHERE slot_id = :slotId AND id = :id")
    suspend fun getById(slotId: Int, id: String): Manual?

    @Query("SELECT * FROM manuals WHERE slot_id = :slotId AND type = :type ORDER BY rarity DESC")
    fun getByType(slotId: Int, type: ManualType): Flow<List<Manual>>

    @Query("SELECT * FROM manuals WHERE slot_id = :slotId AND rarity = :rarity AND ownerId IS NULL")
    fun getUnlearnedByRarity(slotId: Int, rarity: Int): Flow<List<Manual>>

    @Query("SELECT * FROM manuals WHERE slot_id = :slotId AND minRealm <= :realm AND ownerId IS NULL ORDER BY rarity DESC")
    fun getLearnableForRealm(slotId: Int, realm: Int): Flow<List<Manual>>

    @Query("SELECT * FROM manuals WHERE slot_id = :slotId AND name LIKE '%' || :keyword || '%'")
    suspend fun searchByName(slotId: Int, keyword: String): List<Manual>

    @Query("SELECT * FROM manuals WHERE slot_id = :slotId AND ownerId IS NULL AND quantity > 0 ORDER BY rarity DESC")
    fun getAvailableManuals(slotId: Int): Flow<List<Manual>>

    @Query("SELECT COUNT(*) FROM manuals WHERE slot_id = :slotId AND ownerId IS NOT NULL")
    fun getLearnedCount(slotId: Int): Flow<Int>

    // Insert/Update/Delete 操作基于实体，实体层负责设置正确的 slot_id
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(manual: Manual)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(manuals: List<Manual>)

    @Update
    suspend fun update(manual: Manual)

    @Update
    suspend fun updateAll(manuals: List<Manual>)

    /** 获取指定槽位下已有的实体 ID 集合（用于 UPSERT 差量写入） */
    @Query("SELECT id FROM manuals WHERE slot_id = :slotId")
    suspend fun getIdsBySlot(slotId: Int): List<String>

    @Delete
    suspend fun delete(manual: Manual)

    @Query("DELETE FROM manuals WHERE slot_id = :slotId AND id = :id")
    suspend fun deleteById(slotId: Int, id: String)

    @Query("DELETE FROM manuals WHERE slot_id = :slotId AND ownerId IS NULL AND quantity <= 0")
    suspend fun deleteEmptyUnlearned(slotId: Int): Int

    @Query("DELETE FROM manuals WHERE slot_id = :slotId")
    suspend fun deleteAll(slotId: Int)

    // 全局删除（管理员操作用）
    @Query("DELETE FROM manuals")
    suspend fun deleteAllGlobal()

    @Transaction
    suspend fun updateBatch(manuals: List<Manual>) {
        manuals.forEach { update(it) }
    }
}

@Dao
interface PillDao {
    @Query("SELECT * FROM pills WHERE slot_id = :slotId AND quantity > 0")
    fun getAll(slotId: Int): Flow<List<Pill>>

    @Query("SELECT * FROM pills WHERE slot_id = :slotId")
    suspend fun getAllSync(slotId: Int): List<Pill>

    // 通过主键ID查询，全局唯一，但建议也加 slotId 用于安全校验
    @Query("SELECT * FROM pills WHERE slot_id = :slotId AND id = :id")
    suspend fun getById(slotId: Int, id: String): Pill?

    @Query("SELECT * FROM pills WHERE slot_id = :slotId AND category = :category AND quantity > 0 ORDER BY rarity DESC")
    fun getByCategory(slotId: Int, category: PillCategory): Flow<List<Pill>>

    @Query("SELECT * FROM pills WHERE slot_id = :slotId AND targetRealm = :realm AND quantity > 0 ORDER BY rarity DESC")
    fun getByTargetRealm(slotId: Int, realm: Int): Flow<List<Pill>>

    @Query("SELECT * FROM pills WHERE slot_id = :slotId AND rarity >= :minRarity AND quantity > 0 ORDER BY rarity DESC, name ASC")
    fun getByMinRarity(slotId: Int, minRarity: Int): Flow<List<Pill>>

    @Query("SELECT * FROM pills WHERE slot_id = :slotId AND name LIKE '%' || :keyword || '%' AND quantity > 0")
    suspend fun searchByName(slotId: Int, keyword: String): List<Pill>

    @Query("SELECT * FROM pills WHERE slot_id = :slotId AND breakthroughChance > 0 AND targetRealm = :realm AND quantity > 0 ORDER BY breakthroughChance DESC")
    fun getBreakthroughPillsForRealm(slotId: Int, realm: Int): Flow<List<Pill>>

    @Query("SELECT * FROM pills WHERE slot_id = :slotId AND extendLife > 0 AND quantity > 0 ORDER BY extendLife DESC")
    fun getLifeExtensionPills(slotId: Int): Flow<List<Pill>>

    @Query("SELECT * FROM pills WHERE slot_id = :slotId AND revive = 1 AND quantity > 0")
    fun getRevivePills(slotId: Int): Flow<List<Pill>>

    @Query("SELECT SUM(quantity) FROM pills WHERE slot_id = :slotId AND category = :category")
    suspend fun getTotalQuantityByCategory(slotId: Int, category: PillCategory): Int

    // Insert/Update/Delete 操作基于实体，实体层负责设置正确的 slot_id
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(pill: Pill)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(pills: List<Pill>)

    @Update
    suspend fun update(pill: Pill)

    @Update
    suspend fun updateAll(pills: List<Pill>)

    /** 获取指定槽位下已有的实体 ID 集合（用于 UPSERT 差量写入） */
    @Query("SELECT id FROM pills WHERE slot_id = :slotId")
    suspend fun getIdsBySlot(slotId: Int): List<String>

    @Delete
    suspend fun delete(pill: Pill)

    @Query("DELETE FROM pills WHERE slot_id = :slotId AND id = :id")
    suspend fun deleteById(slotId: Int, id: String)

    @Query("DELETE FROM pills WHERE slot_id = :slotId AND quantity <= 0")
    suspend fun deleteEmpty(slotId: Int): Int

    @Query("DELETE FROM pills WHERE slot_id = :slotId")
    suspend fun deleteAll(slotId: Int)

    // 全局删除（管理员操作用）
    @Query("DELETE FROM pills")
    suspend fun deleteAllGlobal()

    @Transaction
    suspend fun updateBatch(pills: List<Pill>) {
        pills.forEach { update(it) }
    }
}

@Dao
interface MaterialDao {
    @Query("SELECT * FROM materials WHERE slot_id = :slotId AND quantity > 0")
    fun getAll(slotId: Int): Flow<List<Material>>

    @Query("SELECT * FROM materials WHERE slot_id = :slotId")
    suspend fun getAllSync(slotId: Int): List<Material>

    // 通过主键ID查询，全局唯一，但建议也加 slotId 用于安全校验
    @Query("SELECT * FROM materials WHERE slot_id = :slotId AND id = :id")
    suspend fun getById(slotId: Int, id: String): Material?

    @Query("SELECT * FROM materials WHERE slot_id = :slotId AND category = :category AND quantity > 0 ORDER BY rarity DESC")
    fun getByCategory(slotId: Int, category: MaterialCategory): Flow<List<Material>>

    @Query("SELECT * FROM materials WHERE slot_id = :slotId AND rarity >= :minRarity AND quantity > 0 ORDER BY rarity DESC, name ASC")
    fun getByMinRarity(slotId: Int, minRarity: Int): Flow<List<Material>>

    @Query("SELECT * FROM materials WHERE slot_id = :slotId AND name LIKE '%' || :keyword || '%' AND quantity > 0")
    suspend fun searchByName(slotId: Int, keyword: String): List<Material>

    @Query("SELECT SUM(quantity) FROM materials WHERE slot_id = :slotId AND category = :category")
    suspend fun getTotalQuantityByCategory(slotId: Int, category: MaterialCategory): Int

    @Query("SELECT * FROM materials WHERE slot_id = :slotId AND quantity > 0 ORDER BY category, rarity DESC")
    fun getAllGroupedByCategory(slotId: Int): Flow<List<Material>>

    // Insert/Update/Delete 操作基于实体，实体层负责设置正确的 slot_id
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(material: Material)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(materials: List<Material>)

    @Update
    suspend fun update(material: Material)

    @Update
    suspend fun updateAll(materials: List<Material>)

    /** 获取指定槽位下已有的实体 ID 集合（用于 UPSERT 差量写入） */
    @Query("SELECT id FROM materials WHERE slot_id = :slotId")
    suspend fun getIdsBySlot(slotId: Int): List<String>

    @Delete
    suspend fun delete(material: Material)

    @Query("DELETE FROM materials WHERE slot_id = :slotId AND id = :id")
    suspend fun deleteById(slotId: Int, id: String)

    @Query("DELETE FROM materials WHERE slot_id = :slotId AND quantity <= 0")
    suspend fun deleteEmpty(slotId: Int): Int

    @Query("DELETE FROM materials WHERE slot_id = :slotId")
    suspend fun deleteAll(slotId: Int)

    // 全局删除（管理员操作用）
    @Query("DELETE FROM materials")
    suspend fun deleteAllGlobal()

    @Transaction
    suspend fun updateBatch(materials: List<Material>) {
        materials.forEach { update(it) }
    }
}

@Dao
interface SeedDao {
    @Query("SELECT * FROM seeds WHERE slot_id = :slotId AND quantity > 0")
    fun getAll(slotId: Int): Flow<List<Seed>>

    @Query("SELECT * FROM seeds WHERE slot_id = :slotId")
    suspend fun getAllSync(slotId: Int): List<Seed>

    // 通过主键ID查询，全局唯一，但建议也加 slotId 用于安全校验
    @Query("SELECT * FROM seeds WHERE slot_id = :slotId AND id = :id")
    suspend fun getById(slotId: Int, id: String): Seed?

    @Query("SELECT * FROM seeds WHERE slot_id = :slotId AND rarity >= :minRarity AND quantity > 0 ORDER BY rarity DESC, growTime ASC")
    fun getByMinRarity(slotId: Int, minRarity: Int): Flow<List<Seed>>

    @Query("SELECT * FROM seeds WHERE slot_id = :slotId AND growTime <= :maxGrowTime AND quantity > 0 ORDER BY growTime ASC")
    fun getByMaxGrowTime(slotId: Int, maxGrowTime: Int): Flow<List<Seed>>

    @Query("SELECT * FROM seeds WHERE slot_id = :slotId AND name LIKE '%' || :keyword || '%' AND quantity > 0")
    suspend fun searchByName(slotId: Int, keyword: String): List<Seed>

    @Query("SELECT SUM(quantity) FROM seeds WHERE slot_id = :slotId")
    suspend fun getTotalQuantity(slotId: Int): Int

    // Insert/Update/Delete 操作基于实体，实体层负责设置正确的 slot_id
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(seed: Seed)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(seeds: List<Seed>)

    @Update
    suspend fun update(seed: Seed)

    @Update
    suspend fun updateAll(seeds: List<Seed>)

    /** 获取指定槽位下已有的实体 ID 集合（用于 UPSERT 差量写入） */
    @Query("SELECT id FROM seeds WHERE slot_id = :slotId")
    suspend fun getIdsBySlot(slotId: Int): List<String>

    @Delete
    suspend fun delete(seed: Seed)

    @Query("DELETE FROM seeds WHERE slot_id = :slotId AND id = :id")
    suspend fun deleteById(slotId: Int, id: String)

    @Query("DELETE FROM seeds WHERE slot_id = :slotId AND quantity <= 0")
    suspend fun deleteEmpty(slotId: Int): Int

    @Query("DELETE FROM seeds WHERE slot_id = :slotId")
    suspend fun deleteAll(slotId: Int)

    // 全局删除（管理员操作用）
    @Query("DELETE FROM seeds")
    suspend fun deleteAllGlobal()

    @Transaction
    suspend fun updateBatch(seeds: List<Seed>) {
        seeds.forEach { update(it) }
    }
}

@Dao
interface HerbDao {
    @Query("SELECT * FROM herbs WHERE slot_id = :slotId AND quantity > 0")
    fun getAll(slotId: Int): Flow<List<Herb>>

    @Query("SELECT * FROM herbs WHERE slot_id = :slotId")
    suspend fun getAllSync(slotId: Int): List<Herb>

    // 通过主键ID查询，全局唯一，但建议也加 slotId 用于安全校验
    @Query("SELECT * FROM herbs WHERE slot_id = :slotId AND id = :id")
    suspend fun getById(slotId: Int, id: String): Herb?

    @Query("SELECT * FROM herbs WHERE slot_id = :slotId AND category = :category AND quantity > 0 ORDER BY rarity DESC")
    fun getByCategory(slotId: Int, category: String): Flow<List<Herb>>

    @Query("SELECT * FROM herbs WHERE slot_id = :slotId AND rarity >= :minRarity AND quantity > 0 ORDER BY rarity DESC, name ASC")
    fun getByMinRarity(slotId: Int, minRarity: Int): Flow<List<Herb>>

    @Query("SELECT * FROM herbs WHERE slot_id = :slotId AND name LIKE '%' || :keyword || '%' AND quantity > 0")
    suspend fun searchByName(slotId: Int, keyword: String): List<Herb>

    @Query("SELECT SUM(quantity) FROM herbs WHERE slot_id = :slotId AND category = :category")
    suspend fun getTotalQuantityByCategory(slotId: Int, category: String): Int

    // Insert/Update/Delete 操作基于实体，实体层负责设置正确的 slot_id
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(herb: Herb)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(herbs: List<Herb>)

    @Update
    suspend fun update(herb: Herb)

    @Update
    suspend fun updateAll(herbs: List<Herb>)

    /** 获取指定槽位下已有的实体 ID 集合（用于 UPSERT 差量写入） */
    @Query("SELECT id FROM herbs WHERE slot_id = :slotId")
    suspend fun getIdsBySlot(slotId: Int): List<String>

    @Delete
    suspend fun delete(herb: Herb)

    @Query("DELETE FROM herbs WHERE slot_id = :slotId AND id = :id")
    suspend fun deleteById(slotId: Int, id: String)

    @Query("DELETE FROM herbs WHERE slot_id = :slotId AND quantity <= 0")
    suspend fun deleteEmpty(slotId: Int): Int

    @Query("DELETE FROM herbs WHERE slot_id = :slotId")
    suspend fun deleteAll(slotId: Int)

    // 全局删除（管理员操作用）
    @Query("DELETE FROM herbs")
    suspend fun deleteAllGlobal()

    @Transaction
    suspend fun updateBatch(herbs: List<Herb>) {
        herbs.forEach { update(it) }
    }
}

@Dao
interface ExplorationTeamDao {
    @Query("SELECT * FROM exploration_teams WHERE slot_id = :slotId")
    fun getAll(slotId: Int): Flow<List<ExplorationTeam>>

    @Query("SELECT * FROM exploration_teams WHERE slot_id = :slotId AND status != 'COMPLETED'")
    fun getActive(slotId: Int): Flow<List<ExplorationTeam>>

    // 通过主键ID查询，全局唯一，但建议也加 slotId 用于安全校验
    @Query("SELECT * FROM exploration_teams WHERE slot_id = :slotId AND id = :id")
    suspend fun getById(slotId: Int, id: String): ExplorationTeam?

    @Query("SELECT * FROM exploration_teams WHERE slot_id = :slotId")
    suspend fun getAllSync(slotId: Int): List<ExplorationTeam>

    // Insert/Update/Delete 操作基于实体，实体层负责设置正确的 slot_id
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(team: ExplorationTeam)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(teams: List<ExplorationTeam>)

    @Update
    suspend fun update(team: ExplorationTeam)

    @Update
    suspend fun updateAll(teams: List<ExplorationTeam>)

    /** 获取指定槽位下已有的实体 ID 集合（用于 UPSERT 差量写入） */
    @Query("SELECT id FROM exploration_teams WHERE slot_id = :slotId")
    suspend fun getIdsBySlot(slotId: Int): List<String>

    @Delete
    suspend fun delete(team: ExplorationTeam)

    @Query("DELETE FROM exploration_teams WHERE slot_id = :slotId AND id = :id")
    suspend fun deleteById(slotId: Int, id: String)

    @Query("DELETE FROM exploration_teams WHERE slot_id = :slotId")
    suspend fun deleteAll(slotId: Int)

    // 全局删除（管理员操作用）
    @Query("DELETE FROM exploration_teams")
    suspend fun deleteAllGlobal()
}

@Dao
interface BuildingSlotDao {
    // 通过外键 buildingId 查询，建议加 slotId 过滤
    @Query("SELECT * FROM building_slots WHERE slot_id = :slotId AND buildingId = :buildingId ORDER BY slotIndex")
    fun getByBuilding(slotId: Int, buildingId: String): Flow<List<BuildingSlot>>

    @Query("SELECT * FROM building_slots WHERE slot_id = :slotId")
    fun getAll(slotId: Int): Flow<List<BuildingSlot>>

    @Query("SELECT * FROM building_slots WHERE slot_id = :slotId AND buildingId = :buildingId")
    suspend fun getByBuildingSync(slotId: Int, buildingId: String): List<BuildingSlot>

    // Insert/Update/Delete 操作基于实体，实体层负责设置正确的 slot_id
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(slot: BuildingSlot)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(slots: List<BuildingSlot>)

    @Update
    suspend fun update(slot: BuildingSlot)

    @Update
    suspend fun updateAll(slots: List<BuildingSlot>)

    /** 获取指定槽位下已有的实体 ID 集合（用于 UPSERT 差量写入） */
    @Query("SELECT id FROM building_slots WHERE slot_id = :slotId")
    suspend fun getIdsBySlot(slotId: Int): List<String>

    @Delete
    suspend fun delete(slot: BuildingSlot)

    @Query("DELETE FROM building_slots WHERE slot_id = :slotId")
    suspend fun deleteAll(slotId: Int)

    // 全局删除（管理员操作用）
    @Query("DELETE FROM building_slots")
    suspend fun deleteAllGlobal()
}

@Dao
interface GameEventDao {
    @Query("SELECT * FROM game_events WHERE slot_id = :slotId ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(slotId: Int, limit: Int = 50): Flow<List<GameEvent>>

    @Query("SELECT * FROM game_events WHERE slot_id = :slotId ORDER BY timestamp DESC")
    fun getAll(slotId: Int): Flow<List<GameEvent>>

    @Query("SELECT * FROM game_events WHERE slot_id = :slotId ORDER BY timestamp DESC")
    suspend fun getAllSync(slotId: Int): List<GameEvent>

    // 通过主键ID查询，全局唯一，但建议也加 slotId 用于安全校验
    @Query("SELECT * FROM game_events WHERE slot_id = :slotId AND id = :id")
    suspend fun getById(slotId: Int, id: String): GameEvent?

    // Insert 操作基于实体，实体层负责设置正确的 slot_id
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: GameEvent)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<GameEvent>)

    @Update
    suspend fun updateAll(events: List<GameEvent>)

    /** 获取指定槽位下已有的实体 ID 集合（用于 UPSERT 差量写入） */
    @Query("SELECT id FROM game_events WHERE slot_id = :slotId")
    suspend fun getIdsBySlot(slotId: Int): List<String>

    @Query("DELETE FROM game_events WHERE slot_id = :slotId AND id = :id")
    suspend fun deleteById(slotId: Int, id: String)

    @Query("DELETE FROM game_events WHERE slot_id = :slotId AND timestamp < :before")
    suspend fun deleteOld(slotId: Int, before: Long)

    @Query("DELETE FROM game_events WHERE slot_id = :slotId")
    suspend fun deleteAll(slotId: Int)

    // 全局删除（管理员操作用）
    @Query("DELETE FROM game_events")
    suspend fun deleteAllGlobal()
}

@Dao
interface DungeonDao {
    @Query("SELECT * FROM dungeons WHERE slot_id = :slotId AND isUnlocked = 1")
    fun getUnlocked(slotId: Int): Flow<List<Dungeon>>

    @Query("SELECT * FROM dungeons WHERE slot_id = :slotId")
    fun getAll(slotId: Int): Flow<List<Dungeon>>

    // 通过主键ID查询，全局唯一，但建议也加 slotId 用于安全校验
    @Query("SELECT * FROM dungeons WHERE slot_id = :slotId AND id = :id")
    suspend fun getById(slotId: Int, id: String): Dungeon?

    // Insert/Update/Delete 操作基于实体，实体层负责设置正确的 slot_id
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(dungeon: Dungeon)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(dungeons: List<Dungeon>)

    @Update
    suspend fun update(dungeon: Dungeon)

    @Update
    suspend fun updateAll(dungeons: List<Dungeon>)

    /** 获取指定槽位下已有的实体 ID 集合（用于 UPSERT 差量写入） */
    @Query("SELECT id FROM dungeons WHERE slot_id = :slotId")
    suspend fun getIdsBySlot(slotId: Int): List<String>

    @Query("DELETE FROM dungeons WHERE slot_id = :slotId")
    suspend fun deleteAll(slotId: Int)

    // 全局删除（管理员操作用）
    @Query("DELETE FROM dungeons")
    suspend fun deleteAllGlobal()
}

@Dao
interface RecipeDao {
    @Query("SELECT * FROM recipes WHERE slot_id = :slotId AND isUnlocked = 1")
    fun getUnlocked(slotId: Int): Flow<List<Recipe>>

    @Query("SELECT * FROM recipes WHERE slot_id = :slotId")
    fun getAll(slotId: Int): Flow<List<Recipe>>

    @Query("SELECT * FROM recipes WHERE slot_id = :slotId AND type = :type AND isUnlocked = 1")
    fun getByType(slotId: Int, type: RecipeType): Flow<List<Recipe>>

    // 通过主键ID查询，全局唯一，但建议也加 slotId 用于安全校验
    @Query("SELECT * FROM recipes WHERE slot_id = :slotId AND id = :id")
    suspend fun getById(slotId: Int, id: String): Recipe?

    // Insert/Update/Delete 操作基于实体，实体层负责设置正确的 slot_id
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recipe: Recipe)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(recipes: List<Recipe>)

    @Update
    suspend fun update(recipe: Recipe)

    @Update
    suspend fun updateAll(recipes: List<Recipe>)

    /** 获取指定槽位下已有的实体 ID 集合（用于 UPSERT 差量写入） */
    @Query("SELECT id FROM recipes WHERE slot_id = :slotId")
    suspend fun getIdsBySlot(slotId: Int): List<String>

    @Query("DELETE FROM recipes WHERE slot_id = :slotId")
    suspend fun deleteAll(slotId: Int)

    // 全局删除（管理员操作用）
    @Query("DELETE FROM recipes")
    suspend fun deleteAllGlobal()
}

@Dao
interface BattleLogDao {
    @Query("SELECT * FROM battle_logs WHERE slot_id = :slotId ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(slotId: Int, limit: Int = 50): Flow<List<BattleLog>>

    @Query("SELECT * FROM battle_logs WHERE slot_id = :slotId ORDER BY timestamp DESC")
    fun getAll(slotId: Int): Flow<List<BattleLog>>

    @Query("SELECT * FROM battle_logs WHERE slot_id = :slotId ORDER BY timestamp DESC")
    suspend fun getAllSync(slotId: Int): List<BattleLog>

    // 通过主键ID查询，全局唯一，但建议也加 slotId 用于安全校验
    @Query("SELECT * FROM battle_logs WHERE slot_id = :slotId AND id = :id")
    suspend fun getById(slotId: Int, id: String): BattleLog?

    // Insert 操作基于实体，实体层负责设置正确的 slot_id
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: BattleLog)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(logs: List<BattleLog>)

    @Update
    suspend fun updateAll(logs: List<BattleLog>)

    /** 获取指定槽位下已有的实体 ID 集合（用于 UPSERT 差量写入） */
    @Query("SELECT id FROM battle_logs WHERE slot_id = :slotId")
    suspend fun getIdsBySlot(slotId: Int): List<String>

    @Query("DELETE FROM battle_logs WHERE slot_id = :slotId AND id = :id")
    suspend fun deleteById(slotId: Int, id: String)

    @Query("DELETE FROM battle_logs WHERE slot_id = :slotId AND timestamp < :before")
    suspend fun deleteOld(slotId: Int, before: Long)

    @Query("DELETE FROM battle_logs WHERE slot_id = :slotId")
    suspend fun deleteAll(slotId: Int)

    // 全局删除（管理员操作用）
    @Query("DELETE FROM battle_logs")
    suspend fun deleteAllGlobal()
}

@Dao
interface ForgeSlotDao {
    @Query("SELECT * FROM forge_slots WHERE slot_id = :slotId ORDER BY slotIndex")
    fun getAll(slotId: Int): Flow<List<ForgeSlot>>

    @Query("SELECT * FROM forge_slots WHERE slot_id = :slotId AND slotIndex = :slotIndex")
    suspend fun getBySlotIndex(slotId: Int, slotIndex: Int): ForgeSlot?

    // Insert/Update/Delete 操作基于实体，实体层负责设置正确的 slot_id
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(slot: ForgeSlot)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(slots: List<ForgeSlot>)

    @Update
    suspend fun update(slot: ForgeSlot)

    @Update
    suspend fun updateAll(slots: List<ForgeSlot>)

    /** 获取指定槽位下已有的实体 ID 集合（用于 UPSERT 差量写入） */
    @Query("SELECT id FROM forge_slots WHERE slot_id = :slotId")
    suspend fun getIdsBySlot(slotId: Int): List<String>

    @Delete
    suspend fun delete(slot: ForgeSlot)

    @Query("DELETE FROM forge_slots WHERE slot_id = :slotId")
    suspend fun deleteAll(slotId: Int)

    // 全局删除（管理员操作用）
    @Query("DELETE FROM forge_slots")
    suspend fun deleteAllGlobal()
}

@Dao
interface AlchemySlotDao {
    @Query("SELECT * FROM alchemy_slots WHERE slot_id = :slotId ORDER BY slotIndex")
    fun getAll(slotId: Int): Flow<List<AlchemySlot>>

    @Query("SELECT * FROM alchemy_slots WHERE slot_id = :slotId")
    suspend fun getAllSync(slotId: Int): List<AlchemySlot>

    @Query("SELECT * FROM alchemy_slots WHERE slot_id = :slotId AND slotIndex = :slotIndex")
    suspend fun getBySlotIndex(slotId: Int, slotIndex: Int): AlchemySlot?

    // Insert/Update/Delete 操作基于实体，实体层负责设置正确的 slot_id
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(slot: AlchemySlot)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(slots: List<AlchemySlot>)

    @Update
    suspend fun update(slot: AlchemySlot)

    @Update
    suspend fun updateAll(slots: List<AlchemySlot>)

    /** 获取指定槽位下已有的实体 ID 集合（用于 UPSERT 差量写入） */
    @Query("SELECT id FROM alchemy_slots WHERE slot_id = :slotId")
    suspend fun getIdsBySlot(slotId: Int): List<String>

    @Delete
    suspend fun delete(slot: AlchemySlot)

    @Query("DELETE FROM alchemy_slots WHERE slot_id = :slotId")
    suspend fun deleteAll(slotId: Int)

    // 全局删除（管理员操作用）
    @Query("DELETE FROM alchemy_slots")
    suspend fun deleteAllGlobal()
}

// ==================== 拆分弟子表的 DAO 接口（用于 OptimizedGameDatabase 版本 58+）====================
// 多租户改造：所有拆分表 DAO 都增加了 slot_id 过滤，确保不同存档槽位数据隔离

@Dao
interface DiscipleCoreDao {
    @Query("SELECT * FROM disciples_core WHERE slot_id = :slotId AND isAlive = 1 ORDER BY realm ASC, cultivation DESC")
    fun getAllAlive(slotId: Int): Flow<List<DiscipleCore>>

    @Query("SELECT * FROM disciples_core WHERE slot_id = :slotId ORDER BY realm ASC, cultivation DESC")
    fun getAll(slotId: Int): Flow<List<DiscipleCore>>

    // 通过主键ID查询，全局唯一，但建议也加 slotId 用于安全校验
    @Query("SELECT * FROM disciples_core WHERE slot_id = :slotId AND id = :id")
    suspend fun getById(slotId: Int, id: String): DiscipleCore?

    @Query("SELECT * FROM disciples_core WHERE slot_id = :slotId AND isAlive = 1")
    suspend fun getAllAliveSync(slotId: Int): List<DiscipleCore>

    @Query("SELECT * FROM disciples_core WHERE slot_id = :slotId AND isAlive = 1 AND realm = :realm ORDER BY cultivation DESC")
    fun getAliveByRealm(slotId: Int, realm: Int): Flow<List<DiscipleCore>>

    // Insert/Update/Delete 操作基于实体，实体层负责设置正确的 slot_id
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(discipleCore: DiscipleCore)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(disciplesCore: List<DiscipleCore>)

    @Update
    suspend fun update(discipleCore: DiscipleCore)

    @Update
    suspend fun updateAll(disciplesCore: List<DiscipleCore>)

    /** 获取指定槽位下已有的实体 ID 集合（用于 UPSERT 差量写入） */
    @Query("SELECT id FROM disciples_core WHERE slot_id = :slotId")
    suspend fun getIdsBySlot(slotId: Int): List<String>

    @Delete
    suspend fun delete(discipleCore: DiscipleCore)

    @Query("DELETE FROM disciples_core WHERE slot_id = :slotId AND id = :id")
    suspend fun deleteById(slotId: Int, id: String)

    @Query("SELECT * FROM disciples_core WHERE slot_id = :slotId")
    suspend fun getAllSync(slotId: Int): List<DiscipleCore>

    @Query("DELETE FROM disciples_core WHERE slot_id = :slotId")
    suspend fun deleteAll(slotId: Int)

    // 全局删除（管理员操作用）
    @Query("DELETE FROM disciples_core")
    suspend fun deleteAllGlobal()
}

@Dao
interface DiscipleCombatStatsDao {
    // 多租户改造：通过外键 discipleId 查询，discipleId 全局唯一，但建议加 slotId 用于安全校验
    @Query("SELECT * FROM disciples_combat WHERE slot_id = :slotId AND discipleId = :discipleId")
    suspend fun getByDiscipleId(slotId: Int, discipleId: String): DiscipleCombatStats?

    @Query("SELECT * FROM disciples_combat WHERE slot_id = :slotId")
    fun getAll(slotId: Int): Flow<List<DiscipleCombatStats>>

    // Insert/Update/Delete 操作基于实体，实体层负责设置正确的 slot_id
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(combatStats: DiscipleCombatStats)

    @Update
    suspend fun update(combatStats: DiscipleCombatStats)

    @Delete
    suspend fun delete(combatStats: DiscipleCombatStats)

    @Query("DELETE FROM disciples_combat WHERE slot_id = :slotId AND discipleId = :discipleId")
    suspend fun deleteByDiscipleId(slotId: Int, discipleId: String)

    @Query("DELETE FROM disciples_combat WHERE slot_id = :slotId AND discipleId IN (:ids)")
    suspend fun deleteByDiscipleIds(slotId: Int, ids: List<String>)

    @Query("DELETE FROM disciples_combat WHERE slot_id = :slotId")
    suspend fun deleteAll(slotId: Int)

    // 全局删除（管理员操作用）
    @Query("DELETE FROM disciples_combat")
    suspend fun deleteAllGlobal()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(combatStatsList: List<DiscipleCombatStats>)

    @Update
    suspend fun updateAll(combatStatsList: List<DiscipleCombatStats>)

    /** 获取指定槽位下已有的实体 discipleId 集合（用于 UPSERT 差量写入） */
    @Query("SELECT discipleId FROM disciples_combat WHERE slot_id = :slotId")
    suspend fun getIdsBySlot(slotId: Int): List<String>
}

@Dao
interface DiscipleEquipmentDao {
    // 多租户改造：通过外键 discipleId 查询，discipleId 全局唯一，但建议加 slotId 用于安全校验
    @Query("SELECT * FROM disciples_equipment WHERE slot_id = :slotId AND discipleId = :discipleId")
    suspend fun getByDiscipleId(slotId: Int, discipleId: String): DiscipleEquipment?

    @Query("SELECT * FROM disciples_equipment WHERE slot_id = :slotId")
    fun getAll(slotId: Int): Flow<List<DiscipleEquipment>>

    // Insert/Update/Delete 操作基于实体，实体层负责设置正确的 slot_id
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(equipment: DiscipleEquipment)

    @Update
    suspend fun update(equipment: DiscipleEquipment)

    @Delete
    suspend fun delete(equipment: DiscipleEquipment)

    @Query("DELETE FROM disciples_equipment WHERE slot_id = :slotId AND discipleId = :discipleId")
    suspend fun deleteByDiscipleId(slotId: Int, discipleId: String)

    @Query("DELETE FROM disciples_equipment WHERE slot_id = :slotId AND discipleId IN (:ids)")
    suspend fun deleteByDiscipleIds(slotId: Int, ids: List<String>)

    @Query("DELETE FROM disciples_equipment WHERE slot_id = :slotId")
    suspend fun deleteAll(slotId: Int)

    // 全局删除（管理员操作用）
    @Query("DELETE FROM disciples_equipment")
    suspend fun deleteAllGlobal()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(equipmentList: List<DiscipleEquipment>)

    @Update
    suspend fun updateAll(equipmentList: List<DiscipleEquipment>)

    /** 获取指定槽位下已有的实体 discipleId 集合（用于 UPSERT 差量写入） */
    @Query("SELECT discipleId FROM disciples_equipment WHERE slot_id = :slotId")
    suspend fun getIdsBySlot(slotId: Int): List<String>
}

@Dao
interface DiscipleExtendedDao {
    // 多租户改造：通过外键 discipleId 查询，discipleId 全局唯一，但建议加 slotId 用于安全校验
    @Query("SELECT * FROM disciples_extended WHERE slot_id = :slotId AND discipleId = :discipleId")
    suspend fun getByDiscipleId(slotId: Int, discipleId: String): DiscipleExtended?

    @Query("SELECT * FROM disciples_extended WHERE slot_id = :slotId")
    fun getAll(slotId: Int): Flow<List<DiscipleExtended>>

    // Insert/Update/Delete 操作基于实体，实体层负责设置正确的 slot_id
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(extended: DiscipleExtended)

    @Update
    suspend fun update(extended: DiscipleExtended)

    @Delete
    suspend fun delete(extended: DiscipleExtended)

    @Query("DELETE FROM disciples_extended WHERE slot_id = :slotId AND discipleId = :discipleId")
    suspend fun deleteByDiscipleId(slotId: Int, discipleId: String)

    @Query("DELETE FROM disciples_extended WHERE slot_id = :slotId AND discipleId IN (:ids)")
    suspend fun deleteByDiscipleIds(slotId: Int, ids: List<String>)

    @Query("DELETE FROM disciples_extended WHERE slot_id = :slotId")
    suspend fun deleteAll(slotId: Int)

    // 全局删除（管理员操作用）
    @Query("DELETE FROM disciples_extended")
    suspend fun deleteAllGlobal()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(extendedList: List<DiscipleExtended>)

    @Update
    suspend fun updateAll(extendedList: List<DiscipleExtended>)

    /** 获取指定槽位下已有的实体 discipleId 集合（用于 UPSERT 差量写入） */
    @Query("SELECT discipleId FROM disciples_extended WHERE slot_id = :slotId")
    suspend fun getIdsBySlot(slotId: Int): List<String>
}

@Dao
interface DiscipleAttributesDao {
    // 多租户改造：通过外键 discipleId 查询，discipleId 全局唯一，但建议加 slotId 用于安全校验
    @Query("SELECT * FROM disciples_attributes WHERE slot_id = :slotId AND discipleId = :discipleId")
    suspend fun getByDiscipleId(slotId: Int, discipleId: String): DiscipleAttributes?

    @Query("SELECT * FROM disciples_attributes WHERE slot_id = :slotId")
    fun getAll(slotId: Int): Flow<List<DiscipleAttributes>>

    // Insert/Update/Delete 操作基于实体，实体层负责设置正确的 slot_id
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(attributes: DiscipleAttributes)

    @Update
    suspend fun update(attributes: DiscipleAttributes)

    @Delete
    suspend fun delete(attributes: DiscipleAttributes)

    @Query("DELETE FROM disciples_attributes WHERE slot_id = :slotId AND discipleId = :discipleId")
    suspend fun deleteByDiscipleId(slotId: Int, discipleId: String)

    @Query("DELETE FROM disciples_attributes WHERE slot_id = :slotId AND discipleId IN (:ids)")
    suspend fun deleteByDiscipleIds(slotId: Int, ids: List<String>)

    @Query("DELETE FROM disciples_attributes WHERE slot_id = :slotId")
    suspend fun deleteAll(slotId: Int)

    // 全局删除（管理员操作用）
    @Query("DELETE FROM disciples_attributes")
    suspend fun deleteAllGlobal()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(attributesList: List<DiscipleAttributes>)

    @Update
    suspend fun updateAll(attributesList: List<DiscipleAttributes>)

    /** 获取指定槽位下已有的实体 discipleId 集合（用于 UPSERT 差量写入） */
    @Query("SELECT discipleId FROM disciples_attributes WHERE slot_id = :slotId")
    suspend fun getIdsBySlot(slotId: Int): List<String>
}
