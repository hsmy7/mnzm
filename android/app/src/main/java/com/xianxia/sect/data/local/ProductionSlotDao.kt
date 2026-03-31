package com.xianxia.sect.data.local

import androidx.room.*
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.model.production.BuildingType
import kotlinx.coroutines.flow.Flow

@Dao
interface ProductionSlotDao {
    @Query("SELECT * FROM production_slots WHERE buildingType = :buildingType ORDER BY slotIndex")
    fun getByBuildingType(buildingType: BuildingType): Flow<List<ProductionSlot>>
    
    @Query("SELECT * FROM production_slots ORDER BY buildingType, slotIndex")
    fun getAll(): Flow<List<ProductionSlot>>
    
    @Query("SELECT * FROM production_slots")
    suspend fun getAllSync(): List<ProductionSlot>
    
    @Query("SELECT * FROM production_slots WHERE buildingType = :buildingType")
    suspend fun getByBuildingTypeSync(buildingType: BuildingType): List<ProductionSlot>
    
    @Query("SELECT * FROM production_slots WHERE id = :id")
    suspend fun getById(id: String): ProductionSlot?
    
    @Query("SELECT * FROM production_slots WHERE buildingType = :buildingType AND slotIndex = :slotIndex")
    suspend fun getByBuildingTypeAndIndex(buildingType: BuildingType, slotIndex: Int): ProductionSlot?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(slot: ProductionSlot)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(slots: List<ProductionSlot>)
    
    @Update
    suspend fun update(slot: ProductionSlot)
    
    @Update
    suspend fun updateAll(slots: List<ProductionSlot>)
    
    @Delete
    suspend fun delete(slot: ProductionSlot)
    
    @Query("DELETE FROM production_slots WHERE id = :id")
    suspend fun deleteById(id: String)
    
    @Query("DELETE FROM production_slots WHERE buildingType = :buildingType")
    suspend fun deleteByBuildingType(buildingType: BuildingType)
    
    @Query("DELETE FROM production_slots")
    suspend fun deleteAll()
    
    @Transaction
    suspend fun updateBatch(slots: List<ProductionSlot>) {
        slots.forEach { update(it) }
    }
    
    @Transaction
    suspend fun replaceSlotsForBuilding(buildingType: BuildingType, slots: List<ProductionSlot>) {
        deleteByBuildingType(buildingType)
        insertAll(slots)
    }
}
