package com.xianxia.sect.data.local

import androidx.room.*
import com.xianxia.sect.core.model.DiscipleAttributes
import com.xianxia.sect.core.model.DiscipleCombatStats
import com.xianxia.sect.core.model.DiscipleCompact
import com.xianxia.sect.core.model.DiscipleCore
import com.xianxia.sect.core.model.DiscipleEquipment
import com.xianxia.sect.core.model.DiscipleExtended
import kotlinx.coroutines.flow.Flow



@Dao
interface DiscipleCoreDao {
    @Query("SELECT * FROM disciples_core WHERE slot_id = :slotId AND isAlive = 1 ORDER BY realm ASC, cultivation DESC")
    fun getAllAlive(slotId: Int): Flow<List<DiscipleCore>>

    @Query("SELECT * FROM disciples_core WHERE slot_id = :slotId ORDER BY realm ASC, cultivation DESC")
    fun getAll(slotId: Int): Flow<List<DiscipleCore>>

    @Query("SELECT * FROM disciples_core WHERE slot_id = :slotId AND id = :id")
    suspend fun getById(slotId: Int, id: String): DiscipleCore?

    @Query("SELECT * FROM disciples_core WHERE slot_id = :slotId AND isAlive = 1")
    suspend fun getAllAliveSync(slotId: Int): List<DiscipleCore>

    @Query("SELECT * FROM disciples_core WHERE slot_id = :slotId AND isAlive = 1 AND realm = :realm ORDER BY cultivation DESC")
    fun getAliveByRealm(slotId: Int, realm: Int): Flow<List<DiscipleCore>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(discipleCore: DiscipleCore)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(disciplesCore: List<DiscipleCore>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(disciplesCore: List<DiscipleCore>)

    @Update
    suspend fun update(discipleCore: DiscipleCore)

    @Update
    suspend fun updateAll(disciplesCore: List<DiscipleCore>)

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

    @Query("DELETE FROM disciples_core")
    suspend fun deleteAllGlobal()
}

@Dao
interface DiscipleCombatStatsDao {
    @Query("SELECT * FROM disciples_combat WHERE slot_id = :slotId AND discipleId = :discipleId")
    suspend fun getByDiscipleId(slotId: Int, discipleId: String): DiscipleCombatStats?

    @Query("SELECT * FROM disciples_combat WHERE slot_id = :slotId")
    fun getAll(slotId: Int): Flow<List<DiscipleCombatStats>>

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

    @Query("DELETE FROM disciples_combat")
    suspend fun deleteAllGlobal()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(combatStatsList: List<DiscipleCombatStats>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(combatStatsList: List<DiscipleCombatStats>)

    @Update
    suspend fun updateAll(combatStatsList: List<DiscipleCombatStats>)

    @Query("SELECT discipleId FROM disciples_combat WHERE slot_id = :slotId")
    suspend fun getIdsBySlot(slotId: Int): List<String>
}

@Dao
interface DiscipleEquipmentDao {
    @Query("SELECT * FROM disciples_equipment WHERE slot_id = :slotId AND discipleId = :discipleId")
    suspend fun getByDiscipleId(slotId: Int, discipleId: String): DiscipleEquipment?

    @Query("SELECT * FROM disciples_equipment WHERE slot_id = :slotId")
    fun getAll(slotId: Int): Flow<List<DiscipleEquipment>>

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

    @Query("DELETE FROM disciples_equipment")
    suspend fun deleteAllGlobal()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(equipmentList: List<DiscipleEquipment>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(equipmentList: List<DiscipleEquipment>)

    @Update
    suspend fun updateAll(equipmentList: List<DiscipleEquipment>)

    @Query("SELECT discipleId FROM disciples_equipment WHERE slot_id = :slotId")
    suspend fun getIdsBySlot(slotId: Int): List<String>
}

@Dao
interface DiscipleExtendedDao {
    @Query("SELECT * FROM disciples_extended WHERE slot_id = :slotId AND discipleId = :discipleId")
    suspend fun getByDiscipleId(slotId: Int, discipleId: String): DiscipleExtended?

    @Query("SELECT * FROM disciples_extended WHERE slot_id = :slotId")
    fun getAll(slotId: Int): Flow<List<DiscipleExtended>>

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

    @Query("DELETE FROM disciples_extended")
    suspend fun deleteAllGlobal()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(extendedList: List<DiscipleExtended>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(extendedList: List<DiscipleExtended>)

    @Update
    suspend fun updateAll(extendedList: List<DiscipleExtended>)

    @Query("SELECT discipleId FROM disciples_extended WHERE slot_id = :slotId")
    suspend fun getIdsBySlot(slotId: Int): List<String>
}

@Dao
interface DiscipleAttributesDao {
    @Query("SELECT * FROM disciples_attributes WHERE slot_id = :slotId AND discipleId = :discipleId")
    suspend fun getByDiscipleId(slotId: Int, discipleId: String): DiscipleAttributes?

    @Query("SELECT * FROM disciples_attributes WHERE slot_id = :slotId")
    fun getAll(slotId: Int): Flow<List<DiscipleAttributes>>

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

    @Query("DELETE FROM disciples_attributes")
    suspend fun deleteAllGlobal()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(attributesList: List<DiscipleAttributes>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(attributesList: List<DiscipleAttributes>)

    @Update
    suspend fun updateAll(attributesList: List<DiscipleAttributes>)

    @Query("SELECT discipleId FROM disciples_attributes WHERE slot_id = :slotId")
    suspend fun getIdsBySlot(slotId: Int): List<String>
}

@Dao
interface DiscipleCompactDao {
    @Query("SELECT * FROM disciple_compact WHERE slot_id = :slotId AND isAlive = 1")
    fun getAllAlive(slotId: Int): Flow<List<DiscipleCompact>>

    @Query("SELECT * FROM disciple_compact WHERE slot_id = :slotId")
    fun getAll(slotId: Int): Flow<List<DiscipleCompact>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<DiscipleCompact>)

    @Query("DELETE FROM disciple_compact WHERE slot_id = :slotId")
    suspend fun deleteAll(slotId: Int)
}
