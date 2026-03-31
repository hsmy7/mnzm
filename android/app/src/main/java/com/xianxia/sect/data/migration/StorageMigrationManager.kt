package com.xianxia.sect.data.migration

import android.content.Context
import android.util.Log
import com.xianxia.sect.data.facade.RefactoredStorageFacade
import com.xianxia.sect.data.local.GameDatabase
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.unified.UnifiedSaveRepository
import com.xianxia.sect.data.unified.SaveResult
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton

data class StorageMigrationResult(
    val success: Boolean,
    val migratedSlots: Set<Int>,
    val failedSlots: Set<Int>,
    val errors: List<String>
) {
    val isComplete: Boolean get() = failedSlots.isEmpty()
}

data class MigrationProgress(
    val stage: String,
    val currentSlot: Int,
    val totalSlots: Int,
    val message: String = ""
)

@Singleton
class StorageMigrationManager @Inject constructor(
    private val context: Context,
    private val database: GameDatabase,
    private val saveRepository: UnifiedSaveRepository,
    private val storageFacade: RefactoredStorageFacade
) {
    companion object {
        private const val TAG = "StorageMigrationManager"
        private const val MIGRATION_PREFS = "storage_migration_prefs"
        private const val KEY_MIGRATION_VERSION = "migration_version"
        private const val CURRENT_MIGRATION_VERSION = 2
    }
    
    private val prefs = context.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE)
    
    fun needsMigration(): Boolean {
        val currentVersion = prefs.getInt(KEY_MIGRATION_VERSION, 0)
        return currentVersion < CURRENT_MIGRATION_VERSION
    }
    
    suspend fun performMigration(): StorageMigrationResult {
        Log.i(TAG, "Starting storage system migration...")
        
        val currentVersion = prefs.getInt(KEY_MIGRATION_VERSION, 0)
        val errors = mutableListOf<String>()
        val migratedSlots = mutableSetOf<Int>()
        val failedSlots = mutableSetOf<Int>()
        
        return try {
            when (currentVersion) {
                0 -> {
                    val result = migrateFromV0()
                    if (result.success) {
                        migratedSlots.addAll(result.migratedSlots)
                    } else {
                        errors.addAll(result.errors)
                        failedSlots.addAll(result.failedSlots)
                    }
                }
                1 -> {
                    val result = migrateFromV1()
                    if (result.success) {
                        migratedSlots.addAll(result.migratedSlots)
                    } else {
                        errors.addAll(result.errors)
                        failedSlots.addAll(result.failedSlots)
                    }
                }
            }
            
            prefs.edit().putInt(KEY_MIGRATION_VERSION, CURRENT_MIGRATION_VERSION).apply()
            
            Log.i(TAG, "Migration completed: ${migratedSlots.size} slots migrated, ${failedSlots.size} failed")
            StorageMigrationResult(true, migratedSlots, failedSlots, errors)
            
        } catch (e: Exception) {
            Log.e(TAG, "Migration failed", e)
            errors.add(e.message ?: "Unknown error")
            StorageMigrationResult(false, migratedSlots, failedSlots, errors)
        }
    }
    
    private suspend fun migrateFromV0(): StorageMigrationResult {
        Log.i(TAG, "Migrating from version 0 (legacy storage)")
        
        val errors = mutableListOf<String>()
        val migratedSlots = mutableSetOf<Int>()
        val failedSlots = mutableSetOf<Int>()
        
        for (slot in 1..5) {
            try {
                val hasData = checkLegacySlotData(slot)
                if (hasData) {
                    val data = loadLegacySlotData(slot)
                    if (data != null) {
                        val result = saveRepository.save(slot, data)
                        if (result.isSuccess) {
                            migratedSlots.add(slot)
                            Log.i(TAG, "Migrated slot $slot successfully")
                        } else {
                            failedSlots.add(slot)
                            errors.add("Failed to save slot $slot to new system")
                        }
                    } else {
                        failedSlots.add(slot)
                        errors.add("Failed to load legacy data for slot $slot")
                    }
                }
            } catch (e: Exception) {
                failedSlots.add(slot)
                errors.add("Migration failed for slot $slot: ${e.message}")
                Log.e(TAG, "Migration failed for slot $slot", e)
            }
        }
        
        return StorageMigrationResult(
            success = failedSlots.isEmpty(),
            migratedSlots = migratedSlots,
            failedSlots = failedSlots,
            errors = errors
        )
    }
    
    private suspend fun migrateFromV1(): StorageMigrationResult {
        Log.i(TAG, "Migrating from version 1 (UnifiedStorageManager)")
        
        val errors = mutableListOf<String>()
        val migratedSlots = mutableSetOf<Int>()
        val failedSlots = mutableSetOf<Int>()
        
        for (slot in 1..5) {
            try {
                val hasData = checkUnifiedStorageData(slot)
                if (hasData) {
                    val data = loadUnifiedStorageData(slot)
                    if (data != null) {
                        val result = saveRepository.save(slot, data)
                        if (result.isSuccess) {
                            migratedSlots.add(slot)
                            Log.i(TAG, "Migrated slot $slot from UnifiedStorageManager")
                        } else {
                            failedSlots.add(slot)
                            errors.add("Failed to save slot $slot to new system")
                        }
                    }
                }
            } catch (e: Exception) {
                failedSlots.add(slot)
                errors.add("Migration failed for slot $slot: ${e.message}")
                Log.e(TAG, "Migration failed for slot $slot", e)
            }
        }
        
        return StorageMigrationResult(
            success = failedSlots.isEmpty(),
            migratedSlots = migratedSlots,
            failedSlots = failedSlots,
            errors = errors
        )
    }
    
    private fun checkLegacySlotData(slot: Int): Boolean {
        return try {
            val saveFile = java.io.File(
                context.filesDir,
                "saves/slot_$slot.dat"
            )
            saveFile.exists() && saveFile.length() > 0
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun checkUnifiedStorageData(slot: Int): Boolean {
        return try {
            val gameData = database.gameDataDao().getGameDataSync()
            gameData != null
        } catch (e: Exception) {
            false
        }
    }
    
    private fun loadLegacySlotData(slot: Int): SaveData? {
        return try {
            val saveFile = java.io.File(
                context.filesDir,
                "saves/slot_$slot.dat"
            )
            if (!saveFile.exists()) return null
            
            val data = saveFile.readBytes()
            deserializeLegacyData(data)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load legacy slot data for slot $slot", e)
            null
        }
    }
    
    private suspend fun loadUnifiedStorageData(slot: Int): SaveData? {
        return try {
            val result = saveRepository.load(slot)
            result.getOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load UnifiedStorage data for slot $slot", e)
            null
        }
    }
    
    @Suppress("DEPRECATION")
    @Deprecated("Used only for migrating legacy Java-serialized data. Will be removed in future versions.")
    private fun deserializeLegacyData(data: ByteArray): SaveData? {
        return try {
            val ois = java.io.ObjectInputStream(java.io.ByteArrayInputStream(data))
            @Suppress("UNCHECKED_CAST")
            val map = ois.readObject() as? Map<String, Any>
            ois.close()
            
            if (map != null) {
                convertLegacyMapToSaveData(map)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize legacy data", e)
            null
        }
    }
    
    private fun convertLegacyMapToSaveData(map: Map<String, Any>): SaveData? {
        return try {
            SaveData(
                version = (map["version"] as? String) ?: "1.0",
                timestamp = (map["timestamp"] as? Long) ?: System.currentTimeMillis(),
                gameData = (map["gameData"] as? com.xianxia.sect.core.model.GameData)
                    ?: com.xianxia.sect.core.model.GameData(),
                disciples = (map["disciples"] as? List<*>)?.filterIsInstance<com.xianxia.sect.core.model.Disciple>()
                    ?: emptyList(),
                equipment = (map["equipment"] as? List<*>)?.filterIsInstance<com.xianxia.sect.core.model.Equipment>()
                    ?: emptyList(),
                manuals = (map["manuals"] as? List<*>)?.filterIsInstance<com.xianxia.sect.core.model.Manual>()
                    ?: emptyList(),
                pills = (map["pills"] as? List<*>)?.filterIsInstance<com.xianxia.sect.core.model.Pill>()
                    ?: emptyList(),
                materials = (map["materials"] as? List<*>)?.filterIsInstance<com.xianxia.sect.core.model.Material>()
                    ?: emptyList(),
                herbs = (map["herbs"] as? List<*>)?.filterIsInstance<com.xianxia.sect.core.model.Herb>()
                    ?: emptyList(),
                seeds = (map["seeds"] as? List<*>)?.filterIsInstance<com.xianxia.sect.core.model.Seed>()
                    ?: emptyList(),
                teams = emptyList(),
                events = emptyList(),
                battleLogs = emptyList(),
                alliances = emptyList()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert legacy map to SaveData", e)
            null
        }
    }
    
    fun getMigrationVersion(): Int = prefs.getInt(KEY_MIGRATION_VERSION, 0)
    
    fun resetMigrationState() {
        prefs.edit().remove(KEY_MIGRATION_VERSION).apply()
        Log.i(TAG, "Migration state reset")
    }
}
