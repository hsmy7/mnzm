package com.xianxia.sect.data.repository

import android.util.Log
import androidx.room.withTransaction
import com.xianxia.sect.core.model.*
import com.xianxia.sect.data.local.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiscipleRepository @Inject constructor(
    private val database: GameDatabase,
    private val discipleDao: DiscipleDao,
    private val discipleCoreDao: DiscipleCoreDao,
    private val discipleCombatStatsDao: DiscipleCombatStatsDao,
    private val discipleEquipmentDao: DiscipleEquipmentDao,
    private val discipleExtendedDao: DiscipleExtendedDao,
    private val discipleAttributesDao: DiscipleAttributesDao
) {
    companion object {
        private const val TAG = "DiscipleRepository"
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    fun getAllAlive(): Flow<List<Disciple>> = discipleDao.getAllAlive()
    
    fun getAllAliveAggregates(): Flow<List<DiscipleAggregate>> {
        return discipleCoreDao.getAllAliveWithRelations().map { relations ->
            relations.map { it.toAggregate() }
        }
    }
    
    fun getAll(): Flow<List<Disciple>> = discipleDao.getAll()
    
    fun getAllAggregates(): Flow<List<DiscipleAggregate>> {
        return discipleCoreDao.getAllWithRelations().map { relations ->
            relations.map { it.toAggregate() }
        }
    }
    
    suspend fun getById(id: String): Disciple? = discipleDao.getById(id)
    
    suspend fun getAggregateById(id: String): DiscipleAggregate? {
        return withContext(Dispatchers.IO) {
            discipleCoreDao.getByIdWithRelations(id)?.toAggregate()
        }
    }
    
    suspend fun getByStatus(status: DiscipleStatus): List<Disciple> = 
        discipleDao.getByStatus(status)
    
    suspend fun getAggregatesByStatus(status: String): List<DiscipleAggregate> {
        return withContext(Dispatchers.IO) {
            discipleCoreDao.getByStatusWithRelations(status).map { it.toAggregate() }
        }
    }
    
    suspend fun getAllAliveSync(): List<Disciple> = discipleDao.getAllAliveSync()
    
    suspend fun getAllAliveAggregatesSync(): List<DiscipleAggregate> {
        return withContext(Dispatchers.IO) {
            discipleCoreDao.getAllAliveSyncWithRelations().map { it.toAggregate() }
        }
    }
    
    suspend fun getAllAggregatesSync(): List<DiscipleAggregate> {
        return withContext(Dispatchers.IO) {
            discipleCoreDao.getAllSyncWithRelations().map { it.toAggregate() }
        }
    }
    
    fun getAliveByRealm(realm: Int): Flow<List<Disciple>> = 
        discipleDao.getAliveByRealm(realm)
    
    fun getAliveByRealmAggregates(realm: Int): Flow<List<DiscipleAggregate>> {
        return discipleCoreDao.getAliveByRealmWithRelations(realm).map { relations ->
            relations.map { it.toAggregate() }
        }
    }
    
    fun getAliveByRealmRange(minRealm: Int, maxRealm: Int): Flow<List<Disciple>> = 
        discipleDao.getAliveByRealmRange(minRealm, maxRealm)
    
    fun getAliveByRealmRangeAggregates(minRealm: Int, maxRealm: Int): Flow<List<DiscipleAggregate>> {
        return discipleCoreDao.getAliveByRealmRangeWithRelations(minRealm, maxRealm).map { relations ->
            relations.map { it.toAggregate() }
        }
    }
    
    suspend fun searchByName(keyword: String): List<Disciple> = 
        discipleDao.searchByName(keyword)
    
    suspend fun searchByNameAggregates(keyword: String): List<DiscipleAggregate> {
        return withContext(Dispatchers.IO) {
            discipleCoreDao.searchByNameWithRelations(keyword).map { it.toAggregate() }
        }
    }
    
    fun getByDiscipleType(type: String): Flow<List<Disciple>> = 
        discipleDao.getByDiscipleType(type)
    
    fun getByDiscipleTypeAggregates(type: String): Flow<List<DiscipleAggregate>> {
        return discipleCoreDao.getByDiscipleTypeWithRelations(type).map { relations ->
            relations.map { it.toAggregate() }
        }
    }
    
    fun getLowLoyalty(threshold: Int = 30): Flow<List<Disciple>> = 
        discipleDao.getLowLoyalty(threshold)
    
    fun getLowLoyaltyAggregates(threshold: Int = 30): Flow<List<DiscipleAggregate>> {
        return discipleAttributesDao.getLowLoyaltyWithRelations(threshold).map { relations ->
            relations.map { it.toAggregate() }
        }
    }
    
    fun getByMinAgeAggregates(minAge: Int): Flow<List<DiscipleAggregate>> {
        return discipleCoreDao.getByMinAgeWithRelations(minAge).map { relations ->
            relations.map { it.toAggregate() }
        }
    }
    
    fun getDisciplesForBattleAggregates(maxRealm: Int): Flow<List<DiscipleAggregate>> {
        return discipleCoreDao.getDisciplesForBattleWithRelations(maxRealm).map { relations ->
            relations.map { it.toAggregate() }
        }
    }
    
    suspend fun getByStatusWithLimitAggregates(status: String, limit: Int): List<DiscipleAggregate> {
        return withContext(Dispatchers.IO) {
            discipleCoreDao.getByStatusWithLimitWithRelations(status, limit).map { it.toAggregate() }
        }
    }
    
    fun getAliveCount(): Flow<Int> = discipleDao.getAliveCount()
    
    suspend fun getCountByRealm(realm: Int): Int = discipleDao.getCountByRealm(realm)
    
    suspend fun insert(disciple: Disciple) {
        withContext(Dispatchers.IO) {
            database.withTransaction {
                discipleDao.insert(disciple)
                insertSplitEntities(disciple)
            }
        }
    }
    
    suspend fun insertAll(disciples: List<Disciple>) {
        withContext(Dispatchers.IO) {
            database.withTransaction {
                discipleDao.insertAll(disciples)
                insertAllSplitEntities(disciples)
            }
        }
    }
    
    suspend fun update(disciple: Disciple) {
        withContext(Dispatchers.IO) {
            database.withTransaction {
                discipleDao.update(disciple)
                updateSplitEntities(disciple)
            }
        }
    }
    
    suspend fun updateAll(disciples: List<Disciple>) {
        withContext(Dispatchers.IO) {
            database.withTransaction {
                discipleDao.updateAll(disciples)
                updateAllSplitEntities(disciples)
            }
        }
    }
    
    suspend fun updateAggregate(aggregate: DiscipleAggregate) {
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val disciple = aggregate.toDisciple()
                discipleDao.update(disciple)
                updateSplitEntitiesFromAggregate(aggregate)
            }
        }
    }
    
    suspend fun updateCore(core: DiscipleCore) {
        discipleCoreDao.update(core)
    }
    
    suspend fun updateCombatStats(stats: DiscipleCombatStats) {
        discipleCombatStatsDao.update(stats)
    }
    
    suspend fun updateEquipment(equipment: DiscipleEquipment) {
        discipleEquipmentDao.update(equipment)
    }
    
    suspend fun updateExtended(extended: DiscipleExtended) {
        discipleExtendedDao.update(extended)
    }
    
    suspend fun updateAttributes(attributes: DiscipleAttributes) {
        discipleAttributesDao.update(attributes)
    }
    
    suspend fun delete(disciple: Disciple) {
        withContext(Dispatchers.IO) {
            database.withTransaction {
                discipleDao.delete(disciple)
                deleteSplitEntities(disciple.id)
            }
        }
    }
    
    suspend fun deleteById(id: String) {
        withContext(Dispatchers.IO) {
            database.withTransaction {
                discipleDao.deleteById(id)
                deleteSplitEntities(id)
            }
        }
    }
    
    suspend fun deleteDeadDisciples(): Int {
        return withContext(Dispatchers.IO) {
            database.withTransaction {
                val deadDisciples = discipleCoreDao.getAllSync().filter { !it.isAlive }
                val deadIds = deadDisciples.map { it.id }
                
                discipleAttributesDao.deleteByDiscipleIds(deadIds)
                discipleExtendedDao.deleteByDiscipleIds(deadIds)
                discipleEquipmentDao.deleteByDiscipleIds(deadIds)
                discipleCombatStatsDao.deleteByDiscipleIds(deadIds)
                
                val count = discipleDao.deleteDeadDisciples()
                Log.i(TAG, "Deleted $count dead disciples")
                count
            }
        }
    }
    
    suspend fun deleteAll() {
        withContext(Dispatchers.IO) {
            database.withTransaction {
                discipleDao.deleteAll()
                discipleCoreDao.deleteAll()
                discipleCombatStatsDao.deleteAll()
                discipleEquipmentDao.deleteAll()
                discipleExtendedDao.deleteAll()
                discipleAttributesDao.deleteAll()
            }
        }
    }
    
    suspend fun migrateToSplitEntities() {
        withContext(Dispatchers.IO) {
            Log.i(TAG, "Starting migration to split entities")
            
            val disciples = discipleDao.getAllAliveSync()
            var migrated = 0
            
            disciples.forEach { disciple ->
                try {
                    insertSplitEntities(disciple)
                    migrated++
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to migrate disciple ${disciple.id}", e)
                }
            }
            
            Log.i(TAG, "Migration completed: $migrated/${disciples.size} disciples migrated")
        }
    }
    
    suspend fun getCoreById(id: String): DiscipleCore? = 
        discipleCoreDao.getById(id)
    
    suspend fun getCombatStatsByDiscipleId(discipleId: String): DiscipleCombatStats? = 
        discipleCombatStatsDao.getByDiscipleId(discipleId)
    
    suspend fun getEquipmentByDiscipleId(discipleId: String): DiscipleEquipment? = 
        discipleEquipmentDao.getByDiscipleId(discipleId)
    
    suspend fun getExtendedByDiscipleId(discipleId: String): DiscipleExtended? = 
        discipleExtendedDao.getByDiscipleId(discipleId)
    
    suspend fun getAttributesByDiscipleId(discipleId: String): DiscipleAttributes? = 
        discipleAttributesDao.getByDiscipleId(discipleId)
    
    private suspend fun insertSplitEntities(disciple: Disciple) {
        discipleCoreDao.insert(DiscipleCore.fromDisciple(disciple))
        discipleCombatStatsDao.insert(DiscipleCombatStats.fromDisciple(disciple))
        discipleEquipmentDao.insert(DiscipleEquipment.fromDisciple(disciple))
        discipleExtendedDao.insert(DiscipleExtended.fromDisciple(disciple))
        discipleAttributesDao.insert(DiscipleAttributes.fromDisciple(disciple))
    }
    
    private suspend fun insertAllSplitEntities(disciples: List<Disciple>) {
        discipleCoreDao.insertAll(disciples.map { DiscipleCore.fromDisciple(it) })
        discipleCombatStatsDao.insertAll(disciples.map { DiscipleCombatStats.fromDisciple(it) })
        discipleEquipmentDao.insertAll(disciples.map { DiscipleEquipment.fromDisciple(it) })
        discipleExtendedDao.insertAll(disciples.map { DiscipleExtended.fromDisciple(it) })
        discipleAttributesDao.insertAll(disciples.map { DiscipleAttributes.fromDisciple(it) })
    }
    
    private suspend fun updateSplitEntities(disciple: Disciple) {
        discipleCoreDao.update(DiscipleCore.fromDisciple(disciple))
        discipleCombatStatsDao.update(DiscipleCombatStats.fromDisciple(disciple))
        discipleEquipmentDao.update(DiscipleEquipment.fromDisciple(disciple))
        discipleExtendedDao.update(DiscipleExtended.fromDisciple(disciple))
        discipleAttributesDao.update(DiscipleAttributes.fromDisciple(disciple))
    }
    
    private suspend fun updateAllSplitEntities(disciples: List<Disciple>) {
        discipleCoreDao.updateAll(disciples.map { DiscipleCore.fromDisciple(it) })
        discipleCombatStatsDao.updateAll(disciples.map { DiscipleCombatStats.fromDisciple(it) })
        discipleEquipmentDao.updateAll(disciples.map { DiscipleEquipment.fromDisciple(it) })
        discipleExtendedDao.updateAll(disciples.map { DiscipleExtended.fromDisciple(it) })
        discipleAttributesDao.updateAll(disciples.map { DiscipleAttributes.fromDisciple(it) })
    }
    
    private suspend fun updateSplitEntitiesFromAggregate(aggregate: DiscipleAggregate) {
        aggregate.core.let { discipleCoreDao.update(it) }
        aggregate.combatStats?.let { discipleCombatStatsDao.update(it) }
        aggregate.equipment?.let { discipleEquipmentDao.update(it) }
        aggregate.extended?.let { discipleExtendedDao.update(it) }
        aggregate.attributes?.let { discipleAttributesDao.update(it) }
    }
    
    private suspend fun deleteSplitEntities(discipleId: String) {
        discipleCoreDao.deleteById(discipleId)
        discipleCombatStatsDao.deleteByDiscipleId(discipleId)
        discipleEquipmentDao.deleteByDiscipleId(discipleId)
        discipleExtendedDao.deleteByDiscipleId(discipleId)
        discipleAttributesDao.deleteByDiscipleId(discipleId)
    }
    
    fun shutdown() {
        scope.cancel()
    }
}
