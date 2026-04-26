package com.xianxia.sect.data.facade

import android.content.Context
import android.util.Log
import androidx.annotation.WorkerThread
import com.xianxia.sect.data.StorageConstants
import com.xianxia.sect.data.concurrent.SlotLockManager
import com.xianxia.sect.data.engine.StorageEngine
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.model.SaveSlot
import com.xianxia.sect.data.recovery.RecoveryManager
import com.xianxia.sect.data.unified.SaveError
import com.xianxia.sect.data.unified.SaveResult
import com.xianxia.sect.di.ApplicationScopeProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

// ==================== 数据类定义 ====================

data class FacadeSaveProgress(
    val stage: Stage,
    val progress: Float,
    val message: String = ""
) {
    enum class Stage {
        IDLE,
        INITIALIZING,
        VALIDATING,
        SAVING,
        LOADING,
        COMPLETED,
        FAILED
    }
}

data class SlotCacheEntry(
    val slot: Int,
    val lastAccessTime: Long,
    val dataSize: Long = 0,
    val hitCount: Int = 0
)

data class StorageHealthReport(
    val isHealthy: Boolean,
    val totalSlots: Int,
    val activeSlots: Int,
    val corruptedSlots: List<Int>,
    val warnings: List<String>,
    val timestamp: Long = System.currentTimeMillis()
)

data class SlotHealthReport(
    val slot: Int,
    val isHealthy: Boolean,
    val hasData: Boolean,
    val lastSaveTime: Long,
    val integrityValid: Boolean,
    val warnings: List<String>
)

data class StorageSystemStats(
    val totalSaveOperations: Long,
    val totalLoadOperations: Long,
    val totalDeleteOperations: Long,
    val cacheHitRate: Float,
    val averageSaveTimeMs: Long,
    val averageLoadTimeMs: Long,
    val activeSlot: Int,
    val timestamp: Long = System.currentTimeMillis()
)

data class StorageUsage(
    val totalBytes: Long,
    val slotBytes: Map<Int, Long>,
    val cacheBytes: Long,
    val walBytes: Long,
    val timestamp: Long = System.currentTimeMillis()
) {
    val totalMB: Float get() = totalBytes / (1024f * 1024f)
    fun slotMB(slot: Int): Float = (slotBytes[slot] ?: 0L) / (1024f * 1024f)
}

// ==================== 扩展函数 ====================

fun <T> com.xianxia.sect.data.result.StorageResult<T>.toUnifiedResult(): SaveResult<T> = when (this) {
    is com.xianxia.sect.data.result.StorageResult.Success -> SaveResult.success(data)
    is com.xianxia.sect.data.result.StorageResult.Failure -> SaveResult.failure(
        when (error) {
            com.xianxia.sect.data.result.StorageError.INVALID_SLOT -> SaveError.INVALID_SLOT
            com.xianxia.sect.data.result.StorageError.SLOT_EMPTY -> SaveError.SLOT_EMPTY
            com.xianxia.sect.data.result.StorageError.SLOT_CORRUPTED -> SaveError.SLOT_CORRUPTED
            com.xianxia.sect.data.result.StorageError.SAVE_FAILED -> SaveError.SAVE_FAILED
            com.xianxia.sect.data.result.StorageError.LOAD_FAILED -> SaveError.LOAD_FAILED
            com.xianxia.sect.data.result.StorageError.DELETE_FAILED -> SaveError.DELETE_FAILED
            com.xianxia.sect.data.result.StorageError.IO_ERROR -> SaveError.IO_ERROR
            com.xianxia.sect.data.result.StorageError.OUT_OF_MEMORY -> SaveError.OUT_OF_MEMORY
            com.xianxia.sect.data.result.StorageError.ENCRYPTION_ERROR -> SaveError.ENCRYPTION_ERROR
            com.xianxia.sect.data.result.StorageError.DECRYPTION_ERROR -> SaveError.DECRYPTION_ERROR
            com.xianxia.sect.data.result.StorageError.KEY_DERIVATION_ERROR -> SaveError.KEY_DERIVATION_ERROR
            com.xianxia.sect.data.result.StorageError.TIMEOUT -> SaveError.TIMEOUT
            com.xianxia.sect.data.result.StorageError.WAL_ERROR -> SaveError.WAL_ERROR
            com.xianxia.sect.data.result.StorageError.DATABASE_ERROR -> SaveError.DATABASE_ERROR
            com.xianxia.sect.data.result.StorageError.TRANSACTION_FAILED -> SaveError.TRANSACTION_FAILED
            else -> SaveError.UNKNOWN
        },
        message,
        cause
    )
}

// ==================== StorageFacade ====================

@Singleton
class StorageFacade @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: StorageEngine,
    private val lockManager: SlotLockManager,
    private val recoveryManager: RecoveryManager,
    private val applicationScopeProvider: ApplicationScopeProvider
) {
    companion object {
        private const val TAG = "StorageFacade"
    }

    private val scope get() = applicationScopeProvider.ioScope

    private val _progress = MutableStateFlow(FacadeSaveProgress(FacadeSaveProgress.Stage.IDLE, 0f))
    val progress: StateFlow<FacadeSaveProgress> = _progress.asStateFlow()

    private val _currentSlot = MutableStateFlow(1)
    val currentSlotFlow: StateFlow<Int> = _currentSlot.asStateFlow()

    private val isInitialized = AtomicBoolean(false)
    private val isShuttingDown = AtomicBoolean(false)

    private val saveCount = AtomicLong(0)
    private val loadCount = AtomicLong(0)
    private val deleteCount = AtomicLong(0)
    private val totalSaveTimeMs = AtomicLong(0)
    private val totalLoadTimeMs = AtomicLong(0)

    // ==================== 生命周期方法 ====================

    fun initialize(): SaveResult<Unit> {
        if (isInitialized.get()) {
            Log.d(TAG, "StorageFacade already initialized")
            return SaveResult.success(Unit)
        }

        return try {
            _progress.value = FacadeSaveProgress(FacadeSaveProgress.Stage.INITIALIZING, 0.1f, "Initializing storage")

            engine.startMaintenance()

            isInitialized.set(true)
            _progress.value = FacadeSaveProgress(FacadeSaveProgress.Stage.COMPLETED, 1.0f, "Initialization completed")

            Log.i(TAG, "StorageFacade initialized successfully")
            SaveResult.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "StorageFacade initialization failed", e)
            _progress.value = FacadeSaveProgress(FacadeSaveProgress.Stage.FAILED, 0f, e.message ?: "Unknown error")
            SaveResult.failure(SaveError.IO_ERROR, e.message ?: "Initialization failed", e)
        }
    }

    fun shutdown() {
        if (!isShuttingDown.compareAndSet(false, true)) {
            Log.w(TAG, "StorageFacade shutdown already in progress")
            return
        }

        try {
            Log.i(TAG, "StorageFacade shutting down")
            engine.stopMaintenance()
            engine.shutdown()
            isInitialized.set(false)
            Log.i(TAG, "StorageFacade shutdown completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error during StorageFacade shutdown", e)
        }
    }

    // ==================== 异步存取方法 ====================

    suspend fun save(slot: Int, data: SaveData): SaveResult<Unit> {
        ensureInitialized()
        val startTime = System.currentTimeMillis()

        return try {
            _progress.value = FacadeSaveProgress(FacadeSaveProgress.Stage.SAVING, 0.1f, "Saving slot $slot")

            val result = engine.save(slot, data)
            val elapsed = System.currentTimeMillis() - startTime

            if (result.isSuccess) {
                saveCount.incrementAndGet()
                totalSaveTimeMs.addAndGet(elapsed)
                _progress.value = FacadeSaveProgress(FacadeSaveProgress.Stage.COMPLETED, 1.0f, "Save completed")
                SaveResult.success(Unit)
            } else {
                _progress.value = FacadeSaveProgress(FacadeSaveProgress.Stage.FAILED, 0f, "Save failed")
                result.toUnifiedResult().map { }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Save failed for slot $slot", e)
            _progress.value = FacadeSaveProgress(FacadeSaveProgress.Stage.FAILED, 0f, e.message ?: "Unknown error")
            SaveResult.failure(SaveError.SAVE_FAILED, e.message ?: "Save failed", e)
        }
    }

    suspend fun load(slot: Int): SaveResult<SaveData> {
        ensureInitialized()
        val startTime = System.currentTimeMillis()

        return try {
            _progress.value = FacadeSaveProgress(FacadeSaveProgress.Stage.LOADING, 0.1f, "Loading slot $slot")

            val result = engine.load(slot)
            val elapsed = System.currentTimeMillis() - startTime

            if (result.isSuccess) {
                loadCount.incrementAndGet()
                totalLoadTimeMs.addAndGet(elapsed)
                _progress.value = FacadeSaveProgress(FacadeSaveProgress.Stage.COMPLETED, 1.0f, "Load completed")
            } else {
                _progress.value = FacadeSaveProgress(FacadeSaveProgress.Stage.FAILED, 0f, "Load failed")
            }

            result.toUnifiedResult()
        } catch (e: Exception) {
            Log.e(TAG, "Load failed for slot $slot", e)
            _progress.value = FacadeSaveProgress(FacadeSaveProgress.Stage.FAILED, 0f, e.message ?: "Unknown error")
            SaveResult.failure(SaveError.LOAD_FAILED, e.message ?: "Load failed", e)
        }
    }

    // ==================== 同步存取方法 ====================

    @WorkerThread
    fun saveSync(slot: Int, data: SaveData): Boolean {
        return try {
            runBlocking(Dispatchers.IO) {
                val result = save(slot, data)
                result.isSuccess
            }
        } catch (e: Exception) {
            Log.e(TAG, "saveSync failed for slot $slot", e)
            false
        }
    }

    @WorkerThread
    fun saveSyncWithResult(slot: Int, data: SaveData): SaveResult<Unit> {
        return try {
            runBlocking(Dispatchers.IO) {
                save(slot, data)
            }
        } catch (e: Exception) {
            Log.e(TAG, "saveSyncWithResult failed for slot $slot", e)
            SaveResult.failure(SaveError.SAVE_FAILED, e.message ?: "Save failed", e)
        }
    }

    @WorkerThread
    fun loadSync(slot: Int): SaveData? {
        return try {
            runBlocking(Dispatchers.IO) {
                val result = load(slot)
                result.getOrNull()
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadSync failed for slot $slot", e)
            null
        }
    }

    // ==================== 删除方法 ====================

    suspend fun delete(slot: Int): SaveResult<Unit> {
        return try {
            val result = engine.delete(slot)
            if (result.isSuccess) {
                deleteCount.incrementAndGet()
                Log.i(TAG, "Deleted slot $slot")
                SaveResult.success(Unit)
            } else {
                Log.e(TAG, "Delete failed for slot $slot: ${result.getOrNull()}")
                SaveResult.failure(SaveError.DELETE_FAILED, "Delete failed for slot $slot")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Delete failed for slot $slot", e)
            SaveResult.failure(SaveError.DELETE_FAILED, e.message ?: "Unknown error", e)
        }
    }

    // ==================== 槽位管理方法 ====================

    @WorkerThread
    fun getSaveSlots(): List<SaveSlot> {
        return try {
            runBlocking(Dispatchers.IO) {
                engine.getSaveSlots()
            }
        } catch (e: Exception) {
            Log.e(TAG, "getSaveSlots failed", e)
            val autoSlot = SaveSlot(StorageConstants.AUTO_SAVE_SLOT, "", 0, 1, 1, "", 0, 0, true, isAutoSave = true)
            val manualSlots = (1..lockManager.getMaxSlots()).map { slot ->
                SaveSlot(slot, "", 0, 1, 1, "", 0, 0, true)
            }
            listOf(autoSlot) + manualSlots
        }
    }

    @WorkerThread
    fun getSaveSlotsFresh(): List<SaveSlot> {
        return try {
            runBlocking(Dispatchers.IO) {
                engine.getSaveSlots()
            }
        } catch (e: Exception) {
            Log.e(TAG, "getSaveSlotsFresh failed", e)
            val autoSlot = SaveSlot(StorageConstants.AUTO_SAVE_SLOT, "", 0, 1, 1, "", 0, 0, true, isAutoSave = true)
            val manualSlots = (1..lockManager.getMaxSlots()).map { slot ->
                SaveSlot(slot, "", 0, 1, 1, "", 0, 0, true)
            }
            listOf(autoSlot) + manualSlots
        }
    }

    fun setCurrentSlot(slot: Int) {
        if (lockManager.isValidSlot(slot)) {
            _currentSlot.value = slot
            engine.setCurrentSlot(slot)
        }
    }

    fun getCurrentSlot(): Int = _currentSlot.value

    // ==================== 紧急存档方法 ====================

    fun hasEmergencySave(): Boolean {
        return try {
            engine.hasEmergencySave()
        } catch (e: Exception) {
            Log.e(TAG, "hasEmergencySave check failed", e)
            false
        }
    }

    @WorkerThread
    fun loadEmergencySave(): SaveData? {
        return try {
            runBlocking(Dispatchers.IO) {
                engine.loadEmergencySave()
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadEmergencySave failed", e)
            null
        }
    }

    @WorkerThread
    fun clearEmergencySave() {
        try {
            runBlocking(Dispatchers.IO) {
                engine.clearEmergencySave()
            }
        } catch (e: Exception) {
            Log.e(TAG, "clearEmergencySave failed", e)
        }
    }

    @WorkerThread
    fun emergencySave(data: SaveData): Boolean {
        return try {
            runBlocking(Dispatchers.IO) {
                val result = engine.emergencySave(data)
                result.isSuccess
            }
        } catch (e: Exception) {
            Log.e(TAG, "emergencySave failed", e)
            false
        }
    }

    // ==================== 数据检查方法 ====================

    @WorkerThread
    fun hasSave(slot: Int): Boolean {
        return try {
            runBlocking(Dispatchers.IO) {
                engine.hasData(slot)
            }
        } catch (e: Exception) {
            Log.e(TAG, "hasSave check failed for slot $slot", e)
            false
        }
    }

    @WorkerThread
    fun isSaveCorrupted(slot: Int): Boolean {
        return try {
            runBlocking(Dispatchers.IO) {
                val result = engine.validateIntegrity(slot)
                result.isFailure
            }
        } catch (e: Exception) {
            Log.e(TAG, "isSaveCorrupted check failed for slot $slot", e)
            false
        }
    }

    fun restoreFromBackupIfCorrupted(slot: Int) {
        try {
            scope.launch(Dispatchers.IO) {
                val backupVersions = engine.getBackupVersions(slot)
                if (backupVersions.isNotEmpty()) {
                    val latestBackup = backupVersions.first()
                    val restoreResult = engine.restoreBackup(slot, latestBackup.id)
                    if (restoreResult.isSuccess) {
                        Log.i(TAG, "Restored slot $slot from backup ${latestBackup.id}")
                    } else {
                        Log.e(TAG, "Failed to restore slot $slot from backup")
                    }
                } else {
                    Log.w(TAG, "No backup available for slot $slot")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "restoreFromBackupIfCorrupted failed for slot $slot", e)
        }
    }

    // ==================== 统计与健康检查方法 ====================

    fun getStorageStats(): StorageSystemStats {
        return StorageSystemStats(
            totalSaveOperations = saveCount.get(),
            totalLoadOperations = loadCount.get(),
            totalDeleteOperations = deleteCount.get(),
            cacheHitRate = 0f,
            averageSaveTimeMs = if (saveCount.get() > 0) totalSaveTimeMs.get() / saveCount.get() else 0L,
            averageLoadTimeMs = if (loadCount.get() > 0) totalLoadTimeMs.get() / loadCount.get() else 0L,
            activeSlot = _currentSlot.value
        )
    }

    suspend fun getStorageHealth(): StorageHealthReport {
        val corruptedSlots = mutableListOf<Int>()
        val warnings = mutableListOf<String>()
        var activeSlots = 0

        for (slot in 1..lockManager.getMaxSlots()) {
            try {
                val hasData = engine.hasData(slot)
                if (hasData) {
                    activeSlots++
                    val integrity = engine.validateIntegrity(slot)
                    if (integrity.isFailure) {
                        corruptedSlots.add(slot)
                        warnings.add("Slot $slot integrity check failed")
                    }
                }
            } catch (e: Exception) {
                warnings.add("Slot $slot health check error: ${e.message}")
            }
        }

        return StorageHealthReport(
            isHealthy = corruptedSlots.isEmpty() && warnings.isEmpty(),
            totalSlots = lockManager.getMaxSlots(),
            activeSlots = activeSlots,
            corruptedSlots = corruptedSlots,
            warnings = warnings
        )
    }

    suspend fun getSlotHealth(slot: Int): SlotHealthReport {
        if (!lockManager.isValidSlot(slot)) {
            return SlotHealthReport(slot, false, false, 0L, false, listOf("Invalid slot"))
        }

        return try {
            val hasData = engine.hasData(slot)
            val integrity = if (hasData) engine.validateIntegrity(slot) else null
            val metadata = engine.getSlotMetadata(slot)

            SlotHealthReport(
                slot = slot,
                isHealthy = integrity?.isSuccess ?: true,
                hasData = hasData,
                lastSaveTime = metadata?.timestamp ?: 0L,
                integrityValid = integrity?.isSuccess ?: true,
                warnings = if (integrity?.isFailure == true) listOf("Integrity check failed") else emptyList()
            )
        } catch (e: Exception) {
            SlotHealthReport(slot, false, false, 0L, false, listOf("Health check error: ${e.message}"))
        }
    }

    @WorkerThread
    fun getStorageUsage(): StorageUsage {
        return try {
            val slotBytes = mutableMapOf<Int, Long>()
            var totalBytes = 0L

            for (slot in 0..lockManager.getMaxSlots()) {
                val size = try {
                    runBlocking(Dispatchers.IO) {
                        if (engine.hasData(slot)) {
                            val data = engine.load(slot).getOrNull()
                            if (data != null) {
                                com.xianxia.sect.data.engine.StorageEngine.estimateSaveSize(data)
                            } else 0L
                        } else 0L
                    }
                } catch (e: Exception) {
                    0L
                }
                slotBytes[slot] = size
                totalBytes += size
            }

            StorageUsage(
                totalBytes = totalBytes,
                slotBytes = slotBytes,
                cacheBytes = 0L,
                walBytes = 0L
            )
        } catch (e: Exception) {
            Log.e(TAG, "getStorageUsage failed", e)
            StorageUsage(0L, emptyMap(), 0L, 0L)
        }
    }

    // ==================== 内部辅助方法 ====================

    private fun ensureInitialized() {
        if (!isInitialized.get()) {
            Log.w(TAG, "StorageFacade not initialized, attempting auto-initialization")
            initialize()
        }
    }
}
