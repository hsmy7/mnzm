package com.xianxia.sect.data.migration

import android.content.Context
import android.util.Log
import com.xianxia.sect.core.model.*
import com.xianxia.sect.data.local.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class DiscipleMigrationProgress(
    val totalRecords: Int = 0,
    val migratedRecords: Int = 0,
    val failedRecords: Int = 0,
    val currentTable: String = "",
    val isComplete: Boolean = false,
    val error: String? = null
) {
    val progressPercent: Int get() = if (totalRecords > 0) 
        (migratedRecords * 100 / totalRecords) else 0
}

data class DiscipleMigrationResult(
    val success: Boolean,
    val totalRecords: Int,
    val migratedRecords: Int,
    val failedRecords: Int,
    val errors: List<String> = emptyList()
)

class DiscipleMigrationService(
    private val discipleDao: DiscipleDao,
    private val discipleCoreDao: DiscipleCoreDao,
    private val discipleCombatStatsDao: DiscipleCombatStatsDao,
    private val discipleEquipmentDao: DiscipleEquipmentDao,
    private val discipleExtendedDao: DiscipleExtendedDao,
    private val discipleAttributesDao: DiscipleAttributesDao,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "DiscipleMigrationService"
        private const val BATCH_SIZE = 50
    }
    
    private val isRunning = AtomicBoolean(false)
    private val _progress = MutableStateFlow(DiscipleMigrationProgress())
    val progress: StateFlow<DiscipleMigrationProgress> = _progress.asStateFlow()
    
    suspend fun migrateAll(): DiscipleMigrationProgress {
        if (!isRunning.compareAndSet(false, true)) {
            Log.w(TAG, "Migration already in progress")
            return _progress.value
        }
        
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "Starting disciple entity migration")
                
                val allDisciples = runCatching { 
                    discipleDao.getAllAliveSync() 
                }.getOrElse { 
                    Log.e(TAG, "Failed to get disciples from DAO", it)
                    emptyList() 
                }
                
                val total = allDisciples.size
                
                _progress.value = DiscipleMigrationProgress(
                    totalRecords = total,
                    currentTable = "disciples"
                )
                
                if (total == 0) {
                    Log.i(TAG, "No disciples to migrate")
                    val emptyProgress = DiscipleMigrationProgress(
                        totalRecords = 0,
                        migratedRecords = 0,
                        failedRecords = 0,
                        currentTable = "disciples",
                        isComplete = true
                    )
                    _progress.value = emptyProgress
                    return@withContext emptyProgress
                }
                
                val migrated = AtomicInteger(0)
                val failed = AtomicInteger(0)
                val errors = mutableListOf<String>()
                
                allDisciples.chunked(BATCH_SIZE).forEach { batch ->
                    batch.forEach { disciple ->
                        try {
                            migrateDisciple(disciple)
                            migrated.incrementAndGet()
                        } catch (e: Exception) {
                            val errorMsg = "Failed to migrate disciple ${disciple.id}: ${e.message}"
                            Log.e(TAG, errorMsg, e)
                            errors.add(errorMsg)
                            failed.incrementAndGet()
                        }
                    }
                    
                    _progress.value = DiscipleMigrationProgress(
                        totalRecords = total,
                        migratedRecords = migrated.get(),
                        failedRecords = failed.get(),
                        currentTable = "disciples"
                    )
                }
                
                val finalProgress = DiscipleMigrationProgress(
                    totalRecords = total,
                    migratedRecords = migrated.get(),
                    failedRecords = failed.get(),
                    currentTable = "disciples",
                    isComplete = true
                )
                
                _progress.value = finalProgress
                Log.i(TAG, "Migration completed: ${migrated.get()}/$total migrated, ${failed.get()} failed")
                
                finalProgress
            } catch (e: Exception) {
                Log.e(TAG, "Migration failed", e)
                val errorProgress = DiscipleMigrationProgress(
                    isComplete = true,
                    error = e.message ?: "Unknown error"
                )
                _progress.value = errorProgress
                errorProgress
            } finally {
                isRunning.set(false)
            }
        }
    }
    
    suspend fun migrateAllWithResult(): DiscipleMigrationResult {
        val progress = migrateAll()
        return DiscipleMigrationResult(
            success = progress.failedRecords == 0 && progress.error == null,
            totalRecords = progress.totalRecords,
            migratedRecords = progress.migratedRecords,
            failedRecords = progress.failedRecords
        )
    }
    
    private suspend fun migrateDisciple(disciple: Disciple) {
        val core = DiscipleCore.fromDisciple(disciple)
        val combatStats = DiscipleCombatStats.fromDisciple(disciple)
        val equipment = DiscipleEquipment.fromDisciple(disciple)
        val extended = DiscipleExtended.fromDisciple(disciple)
        val attributes = DiscipleAttributes.fromDisciple(disciple)
        
        discipleCoreDao.insert(core)
        discipleCombatStatsDao.insert(combatStats)
        discipleEquipmentDao.insert(equipment)
        discipleExtendedDao.insert(extended)
        discipleAttributesDao.insert(attributes)
    }
    
    suspend fun verifyMigration(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "Verifying migration integrity")
                
                val originalCount = runCatching { 
                    discipleDao.getAllAliveSync().size 
                }.getOrElse { 0 }
                
                val coreCount = runCatching { 
                    discipleCoreDao.getAllAliveSync().size 
                }.getOrElse { 0 }
                
                if (originalCount != coreCount) {
                    Log.e(TAG, "Count mismatch: original=$originalCount, core=$coreCount")
                    return@withContext false
                }
                
                if (coreCount == 0) {
                    Log.i(TAG, "No records to verify")
                    return@withContext true
                }
                
                var allValid = true
                val allCores = runCatching { 
                    discipleCoreDao.getAllAliveSync() 
                }.getOrElse { emptyList() }
                
                allCores.forEach { core ->
                    val combatStats = runCatching { 
                        discipleCombatStatsDao.getByDiscipleId(core.id) 
                    }.getOrNull()
                    
                    val equipment = runCatching { 
                        discipleEquipmentDao.getByDiscipleId(core.id) 
                    }.getOrNull()
                    
                    val extended = runCatching { 
                        discipleExtendedDao.getByDiscipleId(core.id) 
                    }.getOrNull()
                    
                    val attributes = runCatching { 
                        discipleAttributesDao.getByDiscipleId(core.id) 
                    }.getOrNull()
                    
                    if (combatStats == null || equipment == null || 
                        extended == null || attributes == null) {
                        Log.e(TAG, "Missing split data for disciple ${core.id}: " +
                            "combatStats=${combatStats != null}, " +
                            "equipment=${equipment != null}, " +
                            "extended=${extended != null}, " +
                            "attributes=${attributes != null}")
                        allValid = false
                    }
                }
                
                if (allValid) {
                    Log.i(TAG, "Migration verification passed")
                } else {
                    Log.e(TAG, "Migration verification failed")
                }
                
                allValid
            } catch (e: Exception) {
                Log.e(TAG, "Verification failed with error", e)
                false
            }
        }
    }
    
    suspend fun verifyMigrationDetailed(): Map<String, Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "Detailed migration verification")
                
                val originalCount = runCatching { 
                    discipleDao.getAllAliveSync().size 
                }.getOrElse { 0 }
                
                val coreCount = runCatching { 
                    discipleCoreDao.getAllAliveSync().size 
                }.getOrElse { 0 }
                
                val combatCount = runCatching { 
                    discipleCombatStatsDao.getAll().first().size 
                }.getOrElse { 0 }
                
                val equipmentCount = runCatching { 
                    discipleEquipmentDao.getAll().first().size 
                }.getOrElse { 0 }
                
                val extendedCount = runCatching { 
                    discipleExtendedDao.getAll().first().size 
                }.getOrElse { 0 }
                
                val attributesCount = runCatching { 
                    discipleAttributesDao.getAll().first().size 
                }.getOrElse { 0 }
                
                mapOf(
                    "originalCount" to (originalCount == coreCount),
                    "combatStats" to (combatCount == coreCount),
                    "equipment" to (equipmentCount == coreCount),
                    "extended" to (extendedCount == coreCount),
                    "attributes" to (attributesCount == coreCount)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Detailed verification failed", e)
                mapOf("error" to false)
            }
        }
    }
    
    suspend fun rollbackMigration(): Int {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "Rolling back migration")
                
                val coreCount = runCatching { 
                    discipleCoreDao.getAllAliveSync().size 
                }.getOrElse { 0 }
                
                discipleCoreDao.deleteAll()
                discipleCombatStatsDao.deleteAll()
                discipleEquipmentDao.deleteAll()
                discipleExtendedDao.deleteAll()
                discipleAttributesDao.deleteAll()
                
                Log.i(TAG, "Rollback completed, removed $coreCount records")
                coreCount
            } catch (e: Exception) {
                Log.e(TAG, "Rollback failed", e)
                -1
            }
        }
    }
    
    fun isMigrationRunning(): Boolean = isRunning.get()
    
    fun resetProgress() {
        _progress.value = DiscipleMigrationProgress()
    }
    
    fun shutdown() {
        scope.cancel()
    }
}
