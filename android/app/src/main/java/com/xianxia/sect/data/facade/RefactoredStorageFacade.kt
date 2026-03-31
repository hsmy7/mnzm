package com.xianxia.sect.data.facade

import android.content.Context
import android.util.Log
import com.xianxia.sect.data.cache.GameDataCacheManager
import com.xianxia.sect.data.concurrent.SlotLockManager
import com.xianxia.sect.data.incremental.IncrementalStorageManager
import com.xianxia.sect.data.interceptor.SlotValidationConfig
import com.xianxia.sect.data.interceptor.SlotValidationInterceptor
import com.xianxia.sect.data.interceptor.SlotValidationStats
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.model.SaveSlot
import com.xianxia.sect.data.transaction.RefactoredTransactionalSaveManager
import com.xianxia.sect.data.unified.*
import com.xianxia.sect.data.wal.EnhancedTransactionalWAL
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class FacadeSaveProgress(
    val stage: Stage,
    val progress: Float,
    val message: String = ""
) {
    enum class Stage {
        IDLE,
        CREATING_SNAPSHOT,
        SAVING_CORE_DATA,
        SAVING_DISCIPLES,
        SAVING_ITEMS,
        SAVING_WORLD_DATA,
        SAVING_HISTORICAL_DATA,
        UPDATING_CACHE,
        CREATING_SNAPSHOT_RECORD,
        VALIDATING,
        COMMITTING,
        COMPLETED,
        ROLLING_BACK,
        FAILED
    }
}

@Singleton
class RefactoredStorageFacade @Inject constructor(
    @ApplicationContext private val context: Context,
    private val saveRepository: UnifiedSaveRepository,
    private val transactionalSaveManager: RefactoredTransactionalSaveManager,
    private val wal: EnhancedTransactionalWAL,
    private val lockManager: SlotLockManager,
    private val healthChecker: StorageHealthChecker,
    private val exporter: StorageExporter,
    private val statsCollector: StorageStatsCollector,
    private val incrementalStorageManager: IncrementalStorageManager
) {
    companion object {
        private const val TAG = "StorageFacade"
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val slotValidationInterceptor: SlotValidationInterceptor by lazy {
        SlotValidationInterceptor(
            lockManager = lockManager,
            config = SlotValidationConfig(
                enforceActiveSlot = true,
                allowCrossSlotRead = true,
                allowCrossSlotWrite = false,
                validateBeforeOperation = true,
                logViolations = true,
                throwOnViolation = false
            )
        )
    }
    
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()
    
    val saveProgress: StateFlow<FacadeSaveProgress> = transactionalSaveManager.progress
        .map { progress ->
            FacadeSaveProgress(
                stage = FacadeSaveProgress.Stage.valueOf(progress.stage.name),
                progress = progress.progress,
                message = progress.message
            )
        }
        .stateIn(scope, SharingStarted.Lazily, FacadeSaveProgress(FacadeSaveProgress.Stage.IDLE, 0f))
    
    val storageStats: StateFlow<StorageSystemStats> = statsCollector.stats
    
    suspend fun initialize(): SaveResult<Unit> {
        return try {
            Log.i(TAG, "Initializing storage system...")
            
            val recoveryResult = wal.recover()
            if (!recoveryResult.success) {
                Log.w(TAG, "WAL recovery found issues: ${recoveryResult.errors}")
            }
            
            if (recoveryResult.recoveredSlots.isNotEmpty()) {
                Log.i(TAG, "Recovered slots: ${recoveryResult.recoveredSlots}")
            }
            
            _isInitialized.value = true
            Log.i(TAG, "Storage system initialized successfully")
            SaveResult.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize storage system", e)
            SaveResult.failure(SaveError.UNKNOWN, e.message ?: "Failed to initialize", e)
        }
    }
    
    suspend fun save(slot: Int, data: SaveData): SaveResult<Unit> {
        val validation = slotValidationInterceptor.validateForSave(slot)
        if (!validation.isValid) {
            Log.e(TAG, "Save validation failed for slot $slot: ${validation.error}")
            return SaveResult.failure(SaveError.INVALID_SLOT, "Slot validation failed: ${validation.error}")
        }
        
        val startTime = System.currentTimeMillis()
        val result = saveRepository.save(slot, data).map { Unit }
        statsCollector.recordSaveTime(System.currentTimeMillis() - startTime)
        return result
    }
    
    suspend fun saveIncremental(slot: Int, data: SaveData): SaveResult<Unit> {
        val validation = slotValidationInterceptor.validateForSave(slot)
        if (!validation.isValid) {
            Log.e(TAG, "Incremental save validation failed for slot $slot: ${validation.error}")
            return SaveResult.failure(SaveError.INVALID_SLOT, "Slot validation failed: ${validation.error}")
        }
        
        val startTime = System.currentTimeMillis()
        val result = incrementalStorageManager.saveIncremental(slot, data)
        statsCollector.recordSaveTime(System.currentTimeMillis() - startTime)
        return if (result.success) {
            SaveResult.success(Unit)
        } else {
            SaveResult.failure(SaveError.SAVE_FAILED, result.error ?: "Incremental save failed")
        }
    }
    
    suspend fun load(slot: Int): SaveResult<SaveData> {
        val validation = slotValidationInterceptor.validateForLoad(slot)
        if (!validation.isValid) {
            Log.e(TAG, "Load validation failed for slot $slot: ${validation.error}")
            return SaveResult.failure(SaveError.INVALID_SLOT, "Slot validation failed: ${validation.error}")
        }
        
        val startTime = System.currentTimeMillis()
        val result = saveRepository.load(slot)
        statsCollector.recordLoadTime(System.currentTimeMillis() - startTime)
        return result
    }
    
    suspend fun loadIncremental(slot: Int): SaveResult<SaveData> {
        val validation = slotValidationInterceptor.validateForLoad(slot)
        if (!validation.isValid) {
            Log.e(TAG, "Incremental load validation failed for slot $slot: ${validation.error}")
            return SaveResult.failure(SaveError.INVALID_SLOT, "Slot validation failed: ${validation.error}")
        }
        
        val startTime = System.currentTimeMillis()
        val result = incrementalStorageManager.load(slot)
        statsCollector.recordLoadTime(System.currentTimeMillis() - startTime)
        return if (result.data != null) {
            SaveResult.success(result.data)
        } else {
            SaveResult.failure(SaveError.LOAD_FAILED, result.error ?: "Incremental load failed")
        }
    }
    
    suspend fun delete(slot: Int): SaveResult<Unit> {
        val validation = slotValidationInterceptor.validateForDelete(slot)
        if (!validation.isValid) {
            Log.e(TAG, "Delete validation failed for slot $slot: ${validation.error}")
            return SaveResult.failure(SaveError.INVALID_SLOT, "Slot validation failed: ${validation.error}")
        }
        
        return saveRepository.delete(slot)
    }
    
    suspend fun hasSave(slot: Int): Boolean {
        return saveRepository.hasSave(slot)
    }
    
    suspend fun getSlotInfo(slot: Int): SaveSlot? {
        val metadata = saveRepository.getSlotInfo(slot) ?: return null
        return SaveSlot(
            slot = metadata.slot,
            name = "Save ${metadata.slot}",
            timestamp = metadata.timestamp,
            gameYear = metadata.gameYear,
            gameMonth = metadata.gameMonth,
            sectName = metadata.sectName,
            discipleCount = metadata.discipleCount,
            spiritStones = metadata.spiritStones,
            isEmpty = false,
            customName = metadata.customName
        )
    }
    
    fun getSaveSlots(): List<SaveSlot> {
        return saveRepository.getSaveSlots()
    }
    
    fun setCurrentSlot(slot: Int) {
        val result = slotValidationInterceptor.setActiveSlot(slot)
        if (result.isValid) {
            saveRepository.setCurrentSlot(slot)
        }
    }
    
    fun getCurrentSlot(): Int {
        return slotValidationInterceptor.getActiveSlot()
    }
    
    fun getSlotValidationStats(): SlotValidationStats {
        return slotValidationInterceptor.stats.value
    }
    
    fun accessSlotValidationInterceptor(): SlotValidationInterceptor {
        return slotValidationInterceptor
    }
    
    suspend fun switchSlot(newSlot: Int): SaveResult<Unit> {
        if (!lockManager.isValidSlot(newSlot)) {
            return SaveResult.failure(SaveError.INVALID_SLOT, "Invalid slot: $newSlot")
        }
        
        setCurrentSlot(newSlot)
        return SaveResult.success(Unit)
    }
    
    fun autoSave(data: SaveData) {
        scope.launch {
            saveRepository.autoSave(data)
        }
    }
    
    suspend fun emergencySave(data: SaveData): Boolean {
        return saveRepository.emergencySave(data).isSuccess
    }
    
    suspend fun createBackup(slot: Int): SaveResult<String> {
        return saveRepository.createBackup(slot)
    }
    
    suspend fun getBackupVersions(slot: Int): List<com.xianxia.sect.data.unified.BackupInfo> {
        return saveRepository.getBackupVersions(slot)
    }
    
    suspend fun restoreFromBackup(slot: Int, backupId: String): SaveResult<SaveData> {
        return saveRepository.restoreFromBackup(slot, backupId)
    }
    
    suspend fun verifyIntegrity(slot: Int): com.xianxia.sect.data.unified.IntegrityResult {
        return saveRepository.verifyIntegrity(slot)
    }
    
    suspend fun isSaveCorrupted(slot: Int): Boolean {
        return healthChecker.isSaveCorrupted(slot)
    }
    
    suspend fun restoreFromBackupIfCorrupted(slot: Int): SaveResult<SaveData> {
        val integrity = verifyIntegrity(slot)
        if (integrity is com.xianxia.sect.data.unified.IntegrityResult.Valid) {
            return load(slot)
        }
        
        val backups = getBackupVersions(slot)
        if (backups.isEmpty()) {
            return SaveResult.failure(SaveError.SLOT_CORRUPTED, "No backup available")
        }
        
        return restoreFromBackup(slot, backups.first().id)
    }
    
    suspend fun performHealthCheck(): StorageHealthReport {
        return healthChecker.performHealthCheck()
    }
    
    fun getSystemStats(): StorageSystemStats {
        return statsCollector.getSystemStats()
    }
    
    suspend fun getStorageUsage(): StorageUsage {
        return statsCollector.calculateStorageUsage()
    }
    
    suspend fun exportSave(slot: Int, destFile: File): Boolean {
        return exporter.exportSave(slot, destFile).success
    }
    
    suspend fun importSave(slot: Int, sourceFile: File): Boolean {
        return exporter.importSave(slot, sourceFile).success
    }
    
    suspend fun clearBackups(slot: Int): Int {
        return exporter.clearBackups(slot)
    }
    
    fun renameSlot(slot: Int, customName: String): Boolean {
        if (!lockManager.isValidSlot(slot)) {
            Log.e(TAG, "Invalid slot for rename: $slot")
            return false
        }
        
        return try {
            val metaFile = File(context.filesDir, "saves/slot_$slot.meta")
            if (!metaFile.exists()) {
                Log.w(TAG, "No meta file found for slot $slot")
                return false
            }
            
            val metaJson = metaFile.readText()
            val gson = com.xianxia.sect.data.GsonConfig.createGson()
            @Suppress("UNCHECKED_CAST")
            val meta = gson.fromJson(metaJson, Map::class.java).toMutableMap() as MutableMap<String, Any>
            meta["customName"] = customName
            metaFile.writeText(gson.toJson(meta))
            
            Log.i(TAG, "Renamed slot $slot to '$customName'")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rename slot $slot", e)
            false
        }
    }
    
    suspend fun syncCache(): Boolean {
        return try {
            wal.checkpoint()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Cache sync failed", e)
            false
        }
    }
    
    suspend fun hasAutoSave(): Boolean = healthChecker.hasAutoSave()
    
    fun hasEmergencySave(): Boolean = healthChecker.hasEmergencySave()
    
    suspend fun loadEmergencySave(): SaveData? {
        return saveRepository.loadEmergencySave()
    }
    
    fun clearEmergencySave(): Boolean = saveRepository.clearEmergencySave()
    
    suspend fun saveAsync(slot: Int, data: SaveData): SaveResult<Unit> = save(slot, data)
    
    suspend fun loadAsync(slot: Int): SaveResult<SaveData> = load(slot)
    
    fun saveSync(slot: Int, data: SaveData): Boolean {
        return kotlinx.coroutines.runBlocking {
            save(slot, data).isSuccess
        }
    }
    
    fun loadSync(slot: Int): SaveData? {
        return kotlinx.coroutines.runBlocking {
            load(slot).getOrNull()
        }
    }
    
    fun shutdown() {
        scope.launch {
            wal.checkpoint()
        }
        transactionalSaveManager.shutdown()
        saveRepository.shutdown()
        healthChecker.shutdown()
        statsCollector.shutdown()
        scope.cancel()
        Log.i(TAG, "StorageFacade shutdown completed")
    }
}
