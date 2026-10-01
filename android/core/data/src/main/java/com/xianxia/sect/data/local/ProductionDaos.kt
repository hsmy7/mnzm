package com.xianxia.sect.data.local

import androidx.room.*
import com.xianxia.sect.core.model.BuildingSlot
import com.xianxia.sect.core.model.Recipe
import com.xianxia.sect.core.model.RecipeType
import kotlinx.coroutines.flow.Flow



@Dao
@Suppress("TooManyFunctions") // Room DAO @Query 契约面：函数数=数据访问协议面（查询维度×读写双向），
// Room 要求 DAO 方法驻留接口承载实现代理生成；项目已做过一轮 DAO 域拆分（DiscipleSubDaos），
// 继续拆分只会碎片化数据访问协议并倍增注入面
interface BuildingSlotDao {
    @Query("SELECT * FROM building_slots WHERE buildingId = :buildingId ORDER BY slotIndex")
    fun getByBuilding(buildingId: String): Flow<List<BuildingSlot>>

    @Query("SELECT * FROM building_slots ")
    fun getAll(): Flow<List<BuildingSlot>>

    @Query("SELECT * FROM building_slots WHERE buildingId = :buildingId")
    suspend fun getByBuildingSync(buildingId: String): List<BuildingSlot>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(slot: BuildingSlot)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(slots: List<BuildingSlot>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(slots: List<BuildingSlot>)

    @Update
    suspend fun update(slot: BuildingSlot)

    @Update
    suspend fun updateAll(slots: List<BuildingSlot>)

    @Delete
    suspend fun delete(slot: BuildingSlot)

    @Query("DELETE FROM building_slots ")
    suspend fun deleteAll()

    @Query("DELETE FROM building_slots")
    suspend fun deleteAllGlobal()
}

@Dao
@Suppress("TooManyFunctions") // Room DAO @Query 契约面：函数数=数据访问协议面（查询维度×读写双向），
// Room 要求 DAO 方法驻留接口承载实现代理生成；项目已做过一轮 DAO 域拆分（DiscipleSubDaos），
// 继续拆分只会碎片化数据访问协议并倍增注入面
interface RecipeDao {
    @Query("SELECT * FROM recipes WHERE isUnlocked = 1")
    fun getUnlocked(): Flow<List<Recipe>>

    @Query("SELECT * FROM recipes ")
    fun getAll(): Flow<List<Recipe>>

    @Query("SELECT * FROM recipes WHERE type = :type AND isUnlocked = 1")
    fun getByType(type: RecipeType): Flow<List<Recipe>>

    @Query("SELECT * FROM recipes WHERE id = :id")
    suspend fun getById(id: String): Recipe?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recipe: Recipe)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(recipes: List<Recipe>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(recipes: List<Recipe>)

    @Update
    suspend fun update(recipe: Recipe)

    @Update
    suspend fun updateAll(recipes: List<Recipe>)

    @Query("SELECT id FROM recipes ")
    suspend fun getIdsBySlot(): List<String>

    @Query("DELETE FROM recipes ")
    suspend fun deleteAll()

    @Query("DELETE FROM recipes")
    suspend fun deleteAllGlobal()
}

