package com.xianxia.sect.data.local

import androidx.room.*
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.MaterialCategory
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.PillCategory
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.StorageBag
import kotlinx.coroutines.flow.Flow



@Dao
@Suppress("TooManyFunctions") // Room DAO @Query 契约面：函数数=数据访问协议面（查询维度×读写双向），
// Room 要求 DAO 方法驻留接口承载实现代理生成；项目已做过一轮 DAO 域拆分（DiscipleSubDaos），
// 继续拆分只会碎片化数据访问协议并倍增注入面
interface PillDao {
    @Query("SELECT * FROM pills WHERE quantity > 0")
    fun getAll(): Flow<List<Pill>>

    @Query("SELECT * FROM pills ")
    suspend fun getAllSync(): List<Pill>

    @Query("SELECT * FROM pills WHERE id = :id")
    suspend fun getById(id: String): Pill?

    @Query("SELECT * FROM pills WHERE category = :category AND quantity > 0 ORDER BY rarity DESC")
    fun getByCategory(category: PillCategory): Flow<List<Pill>>

    @Query("SELECT * FROM pills WHERE targetRealm = :realm AND quantity > 0 ORDER BY rarity DESC")
    fun getByTargetRealm(realm: Int): Flow<List<Pill>>

    @Query("SELECT * FROM pills WHERE rarity >= :minRarity AND quantity > 0 ORDER BY rarity " +
        "DESC, name ASC")
    fun getByMinRarity(minRarity: Int): Flow<List<Pill>>

    @Query("SELECT * FROM pills WHERE name LIKE '%' || :keyword || '%' AND quantity > 0")
    suspend fun searchByName(keyword: String): List<Pill>

    @Query("SELECT * FROM pills WHERE breakthroughChance > 0 AND targetRealm = :realm AND " +
        "quantity > 0 ORDER BY breakthroughChance DESC")
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(pills: List<Pill>)

    @Update
    suspend fun update(pill: Pill)

    @Update
    suspend fun updateAll(pills: List<Pill>)

    @Delete
    suspend fun delete(pill: Pill)

    @Query("DELETE FROM pills WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM pills WHERE quantity <= 0")
    suspend fun deleteEmpty(): Int

    @Query("DELETE FROM pills ")
    suspend fun deleteAll()

    /** 增量落盘 id 对账面（SS5）：已落盘 id 全量枚举（仅 id 列，不物化实体）。 */
    @Query("SELECT id FROM pills")
    suspend fun getAllIds(): List<String>

    /** 增量落盘 id 对账面（SS5）：删除集按 id 批删（调用方分批防 SQLite 变量上限）。 */
    @Query("DELETE FROM pills WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Transaction
    suspend fun updateBatch(pills: List<Pill>) {
        updateAll(pills)
    }
}

@Dao
@Suppress("TooManyFunctions") // Room DAO @Query 契约面：函数数=数据访问协议面（查询维度×读写双向），
// Room 要求 DAO 方法驻留接口承载实现代理生成；项目已做过一轮 DAO 域拆分（DiscipleSubDaos），
// 继续拆分只会碎片化数据访问协议并倍增注入面
interface MaterialDao {
    @Query("SELECT * FROM materials WHERE quantity > 0")
    fun getAll(): Flow<List<Material>>

    @Query("SELECT * FROM materials ")
    suspend fun getAllSync(): List<Material>

    @Query("SELECT * FROM materials WHERE id = :id")
    suspend fun getById(id: String): Material?

    @Query("SELECT * FROM materials WHERE category = :category AND quantity > 0 ORDER BY " +
        "rarity DESC")
    fun getByCategory(category: MaterialCategory): Flow<List<Material>>

    @Query("SELECT * FROM materials WHERE rarity >= :minRarity AND quantity > 0 ORDER BY " +
        "rarity DESC, name ASC")
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(materials: List<Material>)

    @Update
    suspend fun update(material: Material)

    @Update
    suspend fun updateAll(materials: List<Material>)

    @Query("SELECT id FROM materials ")
    suspend fun getIdsBySlot(): List<String>

    @Delete
    suspend fun delete(material: Material)

    @Query("DELETE FROM materials WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM materials WHERE quantity <= 0")
    suspend fun deleteEmpty(): Int

    @Query("DELETE FROM materials ")
    suspend fun deleteAll()

    /** 增量落盘 id 对账面（SS5）：已落盘 id 全量枚举（仅 id 列，不物化实体）。 */
    @Query("SELECT id FROM materials ")
    suspend fun getAllIds(): List<String>

    /** 增量落盘 id 对账面（SS5）：删除集按 id 批删（调用方分批防 SQLite 变量上限）。 */
    @Query("DELETE FROM materials WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM materials")
    suspend fun deleteAllGlobal()

    @Transaction
    suspend fun updateBatch(materials: List<Material>) {
        updateAll(materials)
    }
}

@Dao
@Suppress("TooManyFunctions") // Room DAO @Query 契约面：函数数=数据访问协议面（查询维度×读写双向），
// Room 要求 DAO 方法驻留接口承载实现代理生成；项目已做过一轮 DAO 域拆分（DiscipleSubDaos），
// 继续拆分只会碎片化数据访问协议并倍增注入面
interface SeedDao {
    @Query("SELECT * FROM seeds WHERE quantity > 0")
    fun getAll(): Flow<List<Seed>>

    @Query("SELECT * FROM seeds ")
    suspend fun getAllSync(): List<Seed>

    @Query("SELECT * FROM seeds WHERE id = :id")
    suspend fun getById(id: String): Seed?

    @Query("SELECT * FROM seeds WHERE rarity >= :minRarity AND quantity > 0 ORDER BY rarity " +
        "DESC, growTime ASC")
    fun getByMinRarity(minRarity: Int): Flow<List<Seed>>

    @Query("SELECT * FROM seeds WHERE growTime <= :maxGrowTime AND quantity > 0 ORDER BY " +
        "growTime ASC")
    fun getByMaxGrowTime(maxGrowTime: Int): Flow<List<Seed>>

    @Query("SELECT * FROM seeds WHERE name LIKE '%' || :keyword || '%' AND quantity > 0")
    suspend fun searchByName(keyword: String): List<Seed>

    @Query("SELECT SUM(quantity) FROM seeds ")
    suspend fun getTotalQuantity(): Int

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

    @Query("SELECT id FROM seeds ")
    suspend fun getIdsBySlot(): List<String>

    @Delete
    suspend fun delete(seed: Seed)

    @Query("DELETE FROM seeds WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM seeds WHERE quantity <= 0")
    suspend fun deleteEmpty(): Int

    @Query("DELETE FROM seeds ")
    suspend fun deleteAll()

    /** 增量落盘 id 对账面（SS5）：已落盘 id 全量枚举（仅 id 列，不物化实体）。 */
    @Query("SELECT id FROM seeds ")
    suspend fun getAllIds(): List<String>

    /** 增量落盘 id 对账面（SS5）：删除集按 id 批删（调用方分批防 SQLite 变量上限）。 */
    @Query("DELETE FROM seeds WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM seeds")
    suspend fun deleteAllGlobal()

    @Transaction
    suspend fun updateBatch(seeds: List<Seed>) {
        updateAll(seeds)
    }
}

@Dao
@Suppress("TooManyFunctions") // Room DAO @Query 契约面：函数数=数据访问协议面（查询维度×读写双向），
// Room 要求 DAO 方法驻留接口承载实现代理生成；项目已做过一轮 DAO 域拆分（DiscipleSubDaos），
// 继续拆分只会碎片化数据访问协议并倍增注入面
interface HerbDao {
    @Query("SELECT * FROM herbs WHERE quantity > 0")
    fun getAll(): Flow<List<Herb>>

    @Query("SELECT * FROM herbs ")
    suspend fun getAllSync(): List<Herb>

    @Query("SELECT * FROM herbs WHERE id = :id")
    suspend fun getById(id: String): Herb?

    @Query("SELECT * FROM herbs WHERE category = :category AND quantity > 0 ORDER BY rarity DESC")
    fun getByCategory(category: String): Flow<List<Herb>>

    @Query("SELECT * FROM herbs WHERE rarity >= :minRarity AND quantity > 0 ORDER BY rarity " +
        "DESC, name ASC")
    fun getByMinRarity(minRarity: Int): Flow<List<Herb>>

    @Query("SELECT * FROM herbs WHERE name LIKE '%' || :keyword || '%' AND quantity > 0")
    suspend fun searchByName(keyword: String): List<Herb>

    @Query("SELECT SUM(quantity) FROM herbs WHERE category = :category")
    suspend fun getTotalQuantityByCategory(category: String): Int

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

    @Query("SELECT id FROM herbs ")
    suspend fun getIdsBySlot(): List<String>

    @Delete
    suspend fun delete(herb: Herb)

    @Query("DELETE FROM herbs WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM herbs WHERE quantity <= 0")
    suspend fun deleteEmpty(): Int

    @Query("DELETE FROM herbs ")
    suspend fun deleteAll()

    /** 增量落盘 id 对账面（SS5）：已落盘 id 全量枚举（仅 id 列，不物化实体）。 */
    @Query("SELECT id FROM herbs ")
    suspend fun getAllIds(): List<String>

    /** 增量落盘 id 对账面（SS5）：删除集按 id 批删（调用方分批防 SQLite 变量上限）。 */
    @Query("DELETE FROM herbs WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM herbs")
    suspend fun deleteAllGlobal()

    @Transaction
    suspend fun updateBatch(herbs: List<Herb>) {
        updateAll(herbs)
    }
}

@Dao
interface StorageBagDao {
    @Query("SELECT * FROM storage_bags WHERE quantity > 0")
    suspend fun getAll(): List<StorageBag>

    @Query("SELECT * FROM storage_bags WHERE id = :id")
    suspend fun getById(id: String): StorageBag?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(storageBag: StorageBag)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(storageBags: List<StorageBag>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(storageBags: List<StorageBag>)

    @Query("SELECT * FROM storage_bags ")
    suspend fun getAllSync(): List<StorageBag>

    @Query("DELETE FROM storage_bags ")
    suspend fun deleteAll()

    /** 增量落盘 id 对账面（SS5）：已落盘 id 全量枚举（仅 id 列，不物化实体）。 */
    @Query("SELECT id FROM storage_bags")
    suspend fun getAllIds(): List<String>

    /** 增量落盘 id 对账面（SS5）：删除集按 id 批删（调用方分批防 SQLite 变量上限）。 */
    @Query("DELETE FROM storage_bags WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}
