package com.xianxia.sect.data.local

import androidx.room.*
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.EquipmentStack
import kotlinx.coroutines.flow.Flow



@Dao
interface EquipmentStackDao {
    @Query("SELECT * FROM equipment_stacks WHERE slot_id = :slotId")
    fun getAll(slotId: Int): Flow<List<EquipmentStack>>

    @Query("SELECT * FROM equipment_stacks WHERE slot_id = :slotId")
    suspend fun getAllSync(slotId: Int): List<EquipmentStack>

    @Query("SELECT * FROM equipment_stacks WHERE slot_id = :slotId AND id = :id")
    suspend fun getById(slotId: Int, id: String): EquipmentStack?

    @Query("SELECT * FROM equipment_stacks WHERE slot_id = :slotId AND slot = :slot")
    fun getBySlot(slotId: Int, slot: EquipmentSlot): Flow<List<EquipmentStack>>

    @Query("SELECT * FROM equipment_stacks WHERE slot_id = :slotId AND rarity = :rarity ORDER BY name ASC")
    fun getByRarity(slotId: Int, rarity: Int): Flow<List<EquipmentStack>>

    @Query("SELECT * FROM equipment_stacks WHERE slot_id = :slotId AND minRealm <= :realm ORDER BY rarity DESC")
    fun getByRealm(slotId: Int, realm: Int): Flow<List<EquipmentStack>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(equipmentStack: EquipmentStack)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(equipmentStacks: List<EquipmentStack>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(equipmentStacks: List<EquipmentStack>)

    @Update
    suspend fun update(equipmentStack: EquipmentStack)

    @Update
    suspend fun updateAll(equipmentStacks: List<EquipmentStack>)

    @Query("SELECT id FROM equipment_stacks WHERE slot_id = :slotId")
    suspend fun getIdsBySlot(slotId: Int): List<String>

    @Delete
    suspend fun delete(equipmentStack: EquipmentStack)

    @Query("DELETE FROM equipment_stacks WHERE slot_id = :slotId AND id = :id")
    suspend fun deleteById(slotId: Int, id: String)

    @Query("DELETE FROM equipment_stacks WHERE slot_id = :slotId")
    suspend fun deleteAll(slotId: Int)

    @Query("DELETE FROM equipment_stacks")
    suspend fun deleteAllGlobal()

    @Transaction
    suspend fun updateBatch(equipmentStacks: List<EquipmentStack>) {
        updateAll(equipmentStacks)
    }
}

@Dao
interface EquipmentInstanceDao {
    @Query("SELECT * FROM equipment_instances WHERE slot_id = :slotId")
    fun getAll(slotId: Int): Flow<List<EquipmentInstance>>

    @Query("SELECT * FROM equipment_instances WHERE slot_id = :slotId")
    suspend fun getAllSync(slotId: Int): List<EquipmentInstance>

    @Query("SELECT * FROM equipment_instances WHERE slot_id = :slotId AND id = :id")
    suspend fun getById(slotId: Int, id: String): EquipmentInstance?

    @Query("SELECT * FROM equipment_instances WHERE slot_id = :slotId AND ownerId = :discipleId")
    suspend fun getByOwner(slotId: Int, discipleId: String): List<EquipmentInstance>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(equipmentInstance: EquipmentInstance)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(equipmentInstances: List<EquipmentInstance>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(equipmentInstances: List<EquipmentInstance>)

    @Update
    suspend fun update(equipmentInstance: EquipmentInstance)

    @Update
    suspend fun updateAll(equipmentInstances: List<EquipmentInstance>)

    @Query("SELECT id FROM equipment_instances WHERE slot_id = :slotId")
    suspend fun getIdsBySlot(slotId: Int): List<String>

    @Delete
    suspend fun delete(equipmentInstance: EquipmentInstance)

    @Query("DELETE FROM equipment_instances WHERE slot_id = :slotId AND id = :id")
    suspend fun deleteById(slotId: Int, id: String)

    @Query("DELETE FROM equipment_instances WHERE slot_id = :slotId")
    suspend fun deleteAll(slotId: Int)

    @Query("DELETE FROM equipment_instances")
    suspend fun deleteAllGlobal()

    @Transaction
    suspend fun updateBatch(equipmentInstances: List<EquipmentInstance>) {
        updateAll(equipmentInstances)
    }
}
