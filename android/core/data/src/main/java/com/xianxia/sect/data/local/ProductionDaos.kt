package com.xianxia.sect.data.local

import androidx.room.*
import com.xianxia.sect.core.model.BuildingSlot
import com.xianxia.sect.core.model.Recipe
import com.xianxia.sect.core.model.RecipeType
import kotlinx.coroutines.flow.Flow



@Dao
interface BuildingSlotDao {
    @Query("SELECT * FROM building_slots WHERE slot_id = :slotId AND buildingId = :buildingId ORDER BY slotIndex")
    fun getByBuilding(slotId: Int, buildingId: String): Flow<List<BuildingSlot>>

    @Query("SELECT * FROM building_slots WHERE slot_id = :slotId")
    fun getAll(slotId: Int): Flow<List<BuildingSlot>>

    @Query("SELECT * FROM building_slots WHERE slot_id = :slotId AND buildingId = :buildingId")
    suspend fun getByBuildingSync(slotId: Int, buildingId: String): List<BuildingSlot>

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

    @Query("SELECT id FROM building_slots WHERE slot_id = :slotId")
    suspend fun getIdsBySlot(slotId: Int): List<String>

    @Delete
    suspend fun delete(slot: BuildingSlot)

    @Query("DELETE FROM building_slots WHERE slot_id = :slotId")
    suspend fun deleteAll(slotId: Int)

    @Query("DELETE FROM building_slots")
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

    @Query("SELECT * FROM recipes WHERE slot_id = :slotId AND id = :id")
    suspend fun getById(slotId: Int, id: String): Recipe?

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

    @Query("SELECT id FROM recipes WHERE slot_id = :slotId")
    suspend fun getIdsBySlot(slotId: Int): List<String>

    @Query("DELETE FROM recipes WHERE slot_id = :slotId")
    suspend fun deleteAll(slotId: Int)

    @Query("DELETE FROM recipes")
    suspend fun deleteAllGlobal()
}

