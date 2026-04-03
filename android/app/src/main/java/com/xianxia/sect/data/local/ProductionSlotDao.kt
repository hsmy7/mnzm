package com.xianxia.sect.data.local

import androidx.room.*
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlotStatus
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
    fun getByBuildingTypeSync(buildingType: BuildingType): Flow<List<ProductionSlot>>
    
    @Query("SELECT * FROM production_slots WHERE id = :id")
    suspend fun getById(id: String): ProductionSlot?
    
    @Query("SELECT * FROM production_slots WHERE buildingId = :buildingId ORDER BY slotIndex")
    fun getByBuildingId(buildingId: String): Flow<List<ProductionSlot>>
    
    @Query("SELECT * FROM production_slots WHERE buildingId = :buildingId AND slotIndex = :slotIndex LIMIT 1")
    suspend fun getByBuildingIdAndIndex(buildingId: String, slotIndex: Int): ProductionSlot?
    
    @Query("SELECT * FROM production_slots WHERE buildingType = :buildingType AND status = :status")
    fun getByTypeAndStatus(buildingType: BuildingType, status: ProductionSlotStatus): Flow<List<ProductionSlot>>
    
    @Query("""
        SELECT * FROM production_slots 
        WHERE status = 'WORKING' 
        ORDER BY buildingType, slotIndex
    """)
    fun getWorkingSlots(): Flow<List<ProductionSlot>>
    
    @Query("""
        SELECT * FROM production_slots 
        WHERE status = 'COMPLETED'
    """)
    fun getCompletedSlots(): Flow<List<ProductionSlot>>
    
    @Query("""
        SELECT * FROM production_slots
        WHERE status = 'IDLE'
    """)
    fun getIdleSlots(): Flow<List<ProductionSlot>>
    
    @Query("""
        SELECT * FROM production_slots 
        WHERE status = 'WORKING' 
          AND (startYear * 12 + startMonth + duration) <= :currentYear * 12 + :currentMonth
    """)
    suspend fun getFinishedSlots(currentYear: Int, currentMonth: Int): List<ProductionSlot>
    
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
    
    @Query("""
        UPDATE production_slots 
        SET status = :newStatus 
        WHERE id IN (:ids)
    """)
    suspend fun batchUpdateStatus(ids: List<String>, newStatus: ProductionSlotStatus)
}
