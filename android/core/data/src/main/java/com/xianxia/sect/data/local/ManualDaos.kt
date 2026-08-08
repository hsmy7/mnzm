package com.xianxia.sect.data.local

import androidx.room.*
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.ManualType
import kotlinx.coroutines.flow.Flow



@Dao
interface ManualStackDao {
    @Query("SELECT * FROM manual_stacks WHERE slot_id = :slotId")
    fun getAll(slotId: Int): Flow<List<ManualStack>>

    @Query("SELECT * FROM manual_stacks WHERE slot_id = :slotId")
    suspend fun getAllSync(slotId: Int): List<ManualStack>

    @Query("SELECT * FROM manual_stacks WHERE slot_id = :slotId AND id = :id")
    suspend fun getById(slotId: Int, id: String): ManualStack?

    @Query("SELECT * FROM manual_stacks WHERE slot_id = :slotId AND type = :type ORDER BY rarity DESC")
    fun getByType(slotId: Int, type: ManualType): Flow<List<ManualStack>>

    @Query("SELECT * FROM manual_stacks WHERE slot_id = :slotId AND rarity = :rarity ORDER BY name ASC")
    fun getByRarity(slotId: Int, rarity: Int): Flow<List<ManualStack>>

    @Query("SELECT * FROM manual_stacks WHERE slot_id = :slotId AND minRealm <= :realm ORDER BY rarity DESC")
    fun getByRealm(slotId: Int, realm: Int): Flow<List<ManualStack>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(manualStack: ManualStack)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(manualStacks: List<ManualStack>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(manualStacks: List<ManualStack>)

    @Update
    suspend fun update(manualStack: ManualStack)

    @Update
    suspend fun updateAll(manualStacks: List<ManualStack>)

    @Query("SELECT id FROM manual_stacks WHERE slot_id = :slotId")
    suspend fun getIdsBySlot(slotId: Int): List<String>

    @Delete
    suspend fun delete(manualStack: ManualStack)

    @Query("DELETE FROM manual_stacks WHERE slot_id = :slotId AND id = :id")
    suspend fun deleteById(slotId: Int, id: String)

    @Query("DELETE FROM manual_stacks WHERE slot_id = :slotId")
    suspend fun deleteAll(slotId: Int)

    @Query("DELETE FROM manual_stacks")
    suspend fun deleteAllGlobal()

    @Transaction
    suspend fun updateBatch(manualStacks: List<ManualStack>) {
        updateAll(manualStacks)
    }
}

@Dao
interface ManualInstanceDao {
    @Query("SELECT * FROM manual_instances WHERE slot_id = :slotId")
    fun getAll(slotId: Int): Flow<List<ManualInstance>>

    @Query("SELECT * FROM manual_instances WHERE slot_id = :slotId")
    suspend fun getAllSync(slotId: Int): List<ManualInstance>

    @Query("SELECT * FROM manual_instances WHERE slot_id = :slotId AND id = :id")
    suspend fun getById(slotId: Int, id: String): ManualInstance?

    @Query("SELECT * FROM manual_instances WHERE slot_id = :slotId AND ownerId = :discipleId")
    suspend fun getByOwner(slotId: Int, discipleId: String): List<ManualInstance>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(manualInstance: ManualInstance)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(manualInstances: List<ManualInstance>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(manualInstances: List<ManualInstance>)

    @Update
    suspend fun update(manualInstance: ManualInstance)

    @Update
    suspend fun updateAll(manualInstances: List<ManualInstance>)

    @Query("SELECT id FROM manual_instances WHERE slot_id = :slotId")
    suspend fun getIdsBySlot(slotId: Int): List<String>

    @Delete
    suspend fun delete(manualInstance: ManualInstance)

    @Query("DELETE FROM manual_instances WHERE slot_id = :slotId AND id = :id")
    suspend fun deleteById(slotId: Int, id: String)

    @Query("DELETE FROM manual_instances WHERE slot_id = :slotId")
    suspend fun deleteAll(slotId: Int)

    @Query("DELETE FROM manual_instances")
    suspend fun deleteAllGlobal()

    @Transaction
    suspend fun updateBatch(manualInstances: List<ManualInstance>) {
        updateAll(manualInstances)
    }
}
