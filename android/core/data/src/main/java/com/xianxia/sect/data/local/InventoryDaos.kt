package com.xianxia.sect.data.local

import androidx.room.*
import com.xianxia.sect.core.model.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PillDao {
    @Query("SELECT * FROM pills WHERE slot_id = :slotId AND quantity > 0")
    fun getAll(slotId: Int): Flow<List<Pill>>

    @Query("SELECT * FROM pills WHERE slot_id = :slotId")
    suspend fun getAllSync(slotId: Int): List<Pill>

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(pill: Pill)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(pills: List<Pill>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(pills: List<Pill>)

    @Update
    suspend fun update(pill: Pill)

    @Update
    suspend fun updateAll(pills: List<Pill>)

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

    @Query("DELETE FROM pills")
    suspend fun deleteAllGlobal()

    @Transaction
    suspend fun updateBatch(pills: List<Pill>) {
        updateAll(pills)
    }
}

@Dao
interface MaterialDao {
    @Query("SELECT * FROM materials WHERE slot_id = :slotId AND quantity > 0")
    fun getAll(slotId: Int): Flow<List<Material>>

    @Query("SELECT * FROM materials WHERE slot_id = :slotId")
    suspend fun getAllSync(slotId: Int): List<Material>

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(material: Material)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(materials: List<Material>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(materials: List<Material>)

    @Update
    suspend fun update(material: Material)

    @Update
    suspend fun updateAll(materials: List<Material>)

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

    @Query("DELETE FROM materials")
    suspend fun deleteAllGlobal()

    @Transaction
    suspend fun updateBatch(materials: List<Material>) {
        updateAll(materials)
    }
}

@Dao
interface SeedDao {
    @Query("SELECT * FROM seeds WHERE slot_id = :slotId AND quantity > 0")
    fun getAll(slotId: Int): Flow<List<Seed>>

    @Query("SELECT * FROM seeds WHERE slot_id = :slotId")
    suspend fun getAllSync(slotId: Int): List<Seed>

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(seed: Seed)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(seeds: List<Seed>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(seeds: List<Seed>)

    @Update
    suspend fun update(seed: Seed)

    @Update
    suspend fun updateAll(seeds: List<Seed>)

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

    @Query("DELETE FROM seeds")
    suspend fun deleteAllGlobal()

    @Transaction
    suspend fun updateBatch(seeds: List<Seed>) {
        updateAll(seeds)
    }
}

@Dao
interface HerbDao {
    @Query("SELECT * FROM herbs WHERE slot_id = :slotId AND quantity > 0")
    fun getAll(slotId: Int): Flow<List<Herb>>

    @Query("SELECT * FROM herbs WHERE slot_id = :slotId")
    suspend fun getAllSync(slotId: Int): List<Herb>

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(herb: Herb)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(herbs: List<Herb>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(herbs: List<Herb>)

    @Update
    suspend fun update(herb: Herb)

    @Update
    suspend fun updateAll(herbs: List<Herb>)

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

    @Query("DELETE FROM herbs")
    suspend fun deleteAllGlobal()

    @Transaction
    suspend fun updateBatch(herbs: List<Herb>) {
        updateAll(herbs)
    }
}

@Dao
interface StorageBagDao {
    @Query("SELECT * FROM storage_bags WHERE slot_id = :slotId AND quantity > 0")
    suspend fun getAll(slotId: Int): List<StorageBag>

    @Query("SELECT * FROM storage_bags WHERE slot_id = :slotId AND id = :id")
    suspend fun getById(slotId: Int, id: String): StorageBag?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(storageBag: StorageBag)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(storageBags: List<StorageBag>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(storageBags: List<StorageBag>)

    @Query("SELECT * FROM storage_bags WHERE slot_id = :slotId")
    suspend fun getAllSync(slotId: Int): List<StorageBag>

    @Query("DELETE FROM storage_bags WHERE slot_id = :slotId")
    suspend fun deleteAll(slotId: Int)
}
