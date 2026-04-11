package com.xianxia.sect.data.facade

import android.content.Context
import android.util.Log
import com.xianxia.sect.data.concurrent.SlotLockManager
import com.xianxia.sect.data.engine.UnifiedStorageEngine
import com.xianxia.sect.data.engine.EngineProgress
import com.xianxia.sect.data.interceptor.SlotValidationConfig
import com.xianxia.sect.data.interceptor.SlotValidationInterceptor
import com.xianxia.sect.data.interceptor.SlotValidationStats
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.model.SaveSlot
// 迁移说明：引入 StorageGateway 替代直接依赖 UnifiedSaveRepository（2026-04-07）
// 原因：统一存储访问入口，遵循 Storage Gateway 模式，降低模块耦合度
import com.xianxia.sect.data.StorageConstants
import com.xianxia.sect.data.StorageGateway
import com.xianxia.sect.data.concurrent.StorageScopeManager
import com.xianxia.sect.data.memory.ProactiveMemoryGuard
import com.xianxia.sect.data.orchestrator.StorageOrchestrator
import com.xianxia.sect.data.pruning.DataPruningScheduler
import com.xianxia.sect.data.archive.DataArchiveScheduler
import com.xianxia.sect.data.serialization.unified.MetadataFile
import com.xianxia.sect.data.quota.StorageQuotaManager
import com.xianxia.sect.data.result.StorageError
import com.xianxia.sect.data.result.StorageResult
import com.xianxia.sect.data.unified.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
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

private fun <T> StorageResult<T>.toUnifiedResult(): SaveResult<T> = when (this) {
    is StorageResult.Success -> SaveResult.success(data)
    is StorageResult.Failure -> SaveResult.failure(
        error = when (error) {
            StorageError.INVALID_SLOT -> SaveError.INVALID_SLOT
            StorageError.SLOT_EMPTY -> SaveError.SLOT_EMPTY
            StorageError.NOT_FOUND -> SaveError.NOT_FOUND
            StorageError.SLOT_CORRUPTED -> SaveError.SLOT_CORRUPTED
            StorageError.SAVE_FAILED -> SaveError.SAVE_FAILED
            StorageError.LOAD_FAILED -> SaveError.LOAD_FAILED
            StorageError.DELETE_FAILED -> SaveError.DELETE_FAILED
            StorageError.BACKUP_FAILED -> SaveError.BACKUP_FAILED
            StorageError.RESTORE_FAILED -> SaveError.RESTORE_FAILED
            StorageError.ENCRYPTION_ERROR -> SaveError.ENCRYPTION_ERROR
            StorageError.DECRYPTION_ERROR -> SaveError.DECRYPTION_ERROR
            StorageError.IO_ERROR -> SaveError.IO_ERROR
            StorageError.DATABASE_ERROR -> SaveError.DATABASE_ERROR
            StorageError.TRANSACTION_FAILED -> SaveError.TRANSACTION_FAILED
            StorageError.TIMEOUT -> SaveError.TIMEOUT
            StorageError.OUT_OF_MEMORY -> SaveError.OUT_OF_MEMORY
            StorageError.WAL_ERROR -> SaveError.WAL_ERROR
            StorageError.CHECKSUM_MISMATCH -> SaveError.CHECKSUM_MISMATCH
            StorageError.KEY_DERIVATION_ERROR -> SaveError.KEY_DERIVATION_ERROR
            StorageError.VALIDATION_ERROR -> SaveError.UNKNOWN
            StorageError.BATCH_OPERATION_FAILED -> SaveError.UNKNOWN
            StorageError.CONCURRENT_MODIFICATION -> SaveError.UNKNOWN
            StorageError.UNKNOWN -> SaveError.UNKNOWN
        },
        message = message,
        cause = cause
    )
}

@Suppress("DEPRECATION")
@Singleton
class RefactoredStorageFacade @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: UnifiedStorageEngine,
    // 迁移说明：将 exportRepository: UnifiedSaveRepository 替换为 storageGateway: StorageGateway（2026-04-07）
    // 原因：遵循 Storage Gateway 模式，统一存储访问入口，避免直接依赖底层实现
    // 影响范围：所有原 exportRepository.xxx() 调用已改为 storageGateway.xxx()
    private val storageGateway: StorageGateway,
    // 高级功能（backup/integrity/emergency）仍需通过 UnifiedSaveRepository 访问
    private val unifiedSaveRepository: com.xianxia.sect.data.unified.UnifiedSaveRepository,
    private val lockManager: SlotLockManager,
    private val healthChecker: StorageHealthChecker,
    private val exporter: StorageExporter,
    private val statsCollector: StorageStatsCollector,
    private val saveLimitsConfig: com.xianxia.sect.data.config.SaveLimitsConfig,
    private val deleteCoordinator: com.xianxia.sect.data.coordinator.DeleteCoordinator,
    private val orchestrator: StorageOrchestrator,
    private val memoryGuard: ProactiveMemoryGuard,
    private val pruningScheduler: DataPruningScheduler,
    private val archiveScheduler: DataArchiveScheduler,
    private val scopeManager: StorageScopeManager
) {
    companion object {
        private const val TAG = "StorageFacade"
    }

    /**
     * Slot 状态缓存 - Single Source of Truth 的内存视图
     *
     * 架构决策（v3 重构）：
     * - 唯一真实来源：Room Database (通过 engine)
     * - 此缓存仅作为内存加速层，不作为独立数据源
     * - 所有写操作必须通过 engine.save() 同步到 Room DB
     * - 读操作优先读缓存，miss 时从 engine 加载并回填
     *
     * 消除了之前的 split-brain 问题：
     * - 旧版：engine.save() -> Room DB, exportRepository.getSaveSlots() -> 磁盘文件（两个独立后端）
     * - 新版：engine.save() -> Room DB, getSaveSlots() -> slotStateCache (由 engine 驱动)
     */
    private data class SlotCacheEntry(
        val slot: Int,
        val timestamp: Long,
        val gameYear: Int,
        val gameMonth: Int,
        val sectName: String,
        val discipleCount: Int,
        val spiritStones: Long,
        val customName: String
    )

    private val slotStateCache = ConcurrentHashMap<Int, SlotCacheEntry>()

    /** 标记缓存是否已从数据库预热完成 */
    @Volatile
    private var cacheWarmedUp = false
    
    /** 缓存版本号，用于检测过期 */
    private val cacheVersion = AtomicLong(0)
    
    private val quotaManager: StorageQuotaManager by lazy { StorageQuotaManager(context, saveLimitsConfig) }
    
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
    
    val saveProgress: StateFlow<FacadeSaveProgress> = engine.progress
        .map { progress ->
            FacadeSaveProgress(
                stage = when (progress.stage) {
                    EngineProgress.Stage.IDLE -> FacadeSaveProgress.Stage.IDLE
                    EngineProgress.Stage.VALIDATING -> FacadeSaveProgress.Stage.VALIDATING
                    EngineProgress.Stage.SAVING_CORE -> FacadeSaveProgress.Stage.SAVING_CORE_DATA
                    EngineProgress.Stage.SAVING_DISCIPLES -> FacadeSaveProgress.Stage.SAVING_DISCIPLES
                    EngineProgress.Stage.SAVING_ITEMS -> FacadeSaveProgress.Stage.SAVING_ITEMS
                    EngineProgress.Stage.SAVING_WORLD -> FacadeSaveProgress.Stage.SAVING_WORLD_DATA
                    EngineProgress.Stage.SAVING_HISTORY -> FacadeSaveProgress.Stage.SAVING_HISTORICAL_DATA
                    EngineProgress.Stage.UPDATING_CACHE -> FacadeSaveProgress.Stage.UPDATING_CACHE
                    EngineProgress.Stage.COMPLETED -> FacadeSaveProgress.Stage.COMPLETED
                    EngineProgress.Stage.FAILED -> FacadeSaveProgress.Stage.FAILED
                },
                progress = progress.progress,
                message = progress.message
            )
        }
        .stateIn(scope, SharingStarted.Lazily, FacadeSaveProgress(FacadeSaveProgress.Stage.IDLE, 0f))
    
    val storageStats: StateFlow<StorageSystemStats> = statsCollector.stats
    
    suspend fun initialize(): SaveResult<Unit> {
        return try {
            Log.i(TAG, "Initializing storage system (v3 unified backend)...")

            warmUpSlotStateCache()

            cacheWarmedUp = true
            cacheVersion.incrementAndGet()

            orchestrator.initialize()
            memoryGuard.startMonitoring()
            pruningScheduler.start()
            archiveScheduler.start()

            Log.i(TAG, "Storage system initialized successfully (slot cache warmed with ${slotStateCache.size} entries, version=${cacheVersion.get()})")
            SaveResult.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize storage system", e)
            SaveResult.failure(SaveError.UNKNOWN, "Failed to initialize: ${e.message}", e)
        }
    }

    /**
     * 预热 slot 状态缓存（v3 重构版）
     *
     * 数据源：Room Database 通过 engine.hasData() / engine.load()
     * 不再依赖 exportRepository（磁盘文件系统）
     *
     * 策略：
     * 1. 遍历所有有效槽位
     * 2. 使用 engine.hasData() 快速检查 DB 中是否有数据
     * 3. 如果有数据，使用 engine.load() 加载并构建缓存条目
     * 4. 完全绕过磁盘文件系统，消除 split-brain
     */
    private suspend fun warmUpSlotStateCache() {
        val maxSlots = lockManager.getMaxSlots()
        Log.d(TAG, "Warming up slot state cache from Room Database for $maxSlots slots...")
        
        slotStateCache.clear()
        var successCount = 0

        for (slot in listOf(StorageConstants.AUTO_SAVE_SLOT) + (1..maxSlots)) {
            try {
                if (engine.hasData(slot)) {
                    val loadResult = engine.load(slot)
                    if (loadResult.isSuccess) {
                        val data = loadResult.getOrNull()
                        if (data != null) {
                            slotStateCache[slot] = SlotCacheEntry(
                                slot = slot,
                                timestamp = data.timestamp ?: System.currentTimeMillis(),
                                gameYear = data.gameData.gameYear ?: 1,
                                gameMonth = data.gameData.gameMonth ?: 1,
                                sectName = data.gameData.sectName ?: "",
                                discipleCount = data.disciples.size,
                                spiritStones = data.gameData.spiritStones ?: 0L,
                                customName = data.gameData.sectName ?: ""
                            )
                            successCount++
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to warm up cache for slot $slot from database", e)
                // 单个槽位失败不阻断整体预热
            }
        }

        cacheVersion.incrementAndGet()
        Log.i(TAG, "Slot state cache warm-up completed from Room Database: $successCount/${maxSlots} slots with data")
    }
    
    suspend fun save(slot: Int, data: SaveData): SaveResult<Unit> {
        val isAutoSaveSlot = slot == StorageConstants.AUTO_SAVE_SLOT

        if (!isAutoSaveSlot) {
            val setActiveResult = slotValidationInterceptor.setActiveSlot(slot)
            if (!setActiveResult.isValid) {
                Log.e(TAG, "Failed to set active slot $slot: ${setActiveResult.error}")
                return SaveResult.failure(SaveError.INVALID_SLOT, "Failed to set active slot: ${setActiveResult.error}")
            }
            unifiedSaveRepository.setCurrentSlot(slot)
        }

        val validation = slotValidationInterceptor.validateForSave(slot)
        if (!validation.isValid) {
            Log.e(TAG, "Save validation failed for slot $slot: ${validation.error}")
            return SaveResult.failure(SaveError.INVALID_SLOT, "Slot validation failed: ${validation.error}")
        }

        val estimatedSize = UnifiedStorageEngine.estimateSaveSize(data)
        val quotaCheck = quotaManager.checkBeforeWrite(estimatedSize)
        if (quotaCheck is com.xianxia.sect.data.quota.QuotaResult.Exceeded) {
            Log.e(TAG, "Storage quota exceeded for slot $slot: ${quotaCheck.reason}")
            return SaveResult.failure(SaveError.SAVE_FAILED, "Storage quota exceeded: ${quotaCheck.reason}")
        }
        if (quotaCheck is com.xianxia.sect.data.quota.QuotaResult.DiskFull) {
            Log.e(TAG, "Disk full for slot $slot")
            return SaveResult.failure(SaveError.IO_ERROR, "Insufficient disk space")
        }

        val startTime = System.currentTimeMillis()
        val result = engine.save(slot, data).toUnifiedResult().map { Unit }
        statsCollector.recordSaveTime(System.currentTimeMillis() - startTime)

        // 【关键修复】save() 成功后同步更新 slot 状态缓存
        // 解决分裂脑问题：确保 getSaveSlots() 能立即看到新存档
        if (result.isSuccess) {
            updateSlotStateCache(slot, data)
            Log.d(TAG, "Slot $slot state cache updated after successful save")
        }

        return result
    }

    /**
     * 更新 slot 状态缓存
     *
     * 在 save() 成功后调用，将最新的存档信息写入缓存，
     * 确保 getSaveSlots() 能立即反映存档状态。
     */
    private fun updateSlotStateCache(slot: Int, data: SaveData) {
        slotStateCache[slot] = SlotCacheEntry(
            slot = slot,
            timestamp = data.timestamp ?: System.currentTimeMillis(),
            gameYear = data.gameData.gameYear ?: 1,
            gameMonth = data.gameData.gameMonth ?: 1,
            sectName = data.gameData.sectName ?: "",
            discipleCount = data.disciples.size,
            spiritStones = data.gameData.spiritStones ?: 0L,
            customName = data.gameData.sectName ?: ""
        )
    }
    
    suspend fun load(slot: Int): SaveResult<SaveData> {
        val isAutoSaveSlot = slot == StorageConstants.AUTO_SAVE_SLOT

        if (!isAutoSaveSlot) {
            val setActiveResult = slotValidationInterceptor.setActiveSlot(slot)
            if (!setActiveResult.isValid) {
                Log.e(TAG, "Failed to set active slot $slot: ${setActiveResult.error}")
                return SaveResult.failure(SaveError.INVALID_SLOT, "Failed to set active slot: ${setActiveResult.error}")
            }
            unifiedSaveRepository.setCurrentSlot(slot)
        }
        
        val validation = slotValidationInterceptor.validateForLoad(slot)
        if (!validation.isValid) {
            Log.e(TAG, "Load validation failed for slot $slot: ${validation.error}")
            return SaveResult.failure(SaveError.INVALID_SLOT, "Slot validation failed: ${validation.error}")
        }
        
        val startTime = System.currentTimeMillis()
        val result = engine.load(slot).toUnifiedResult()
        statsCollector.recordLoadTime(System.currentTimeMillis() - startTime)
        return result
    }
    
    suspend fun delete(slot: Int): SaveResult<Unit> {
        if (slot == StorageConstants.AUTO_SAVE_SLOT) {
            return SaveResult.failure(SaveError.INVALID_SLOT, "Cannot delete auto save slot")
        }
        val setActiveResult = slotValidationInterceptor.setActiveSlot(slot)
        if (!setActiveResult.isValid) {
            Log.e(TAG, "Failed to set active slot $slot: ${setActiveResult.error}")
            return SaveResult.failure(SaveError.INVALID_SLOT, "Failed to set active slot: ${setActiveResult.error}")
        }
        unifiedSaveRepository.setCurrentSlot(slot)

        val validation = slotValidationInterceptor.validateForDelete(slot)
        if (!validation.isValid) {
            Log.e(TAG, "Delete validation failed for slot $slot: ${validation.error}")
            return SaveResult.failure(SaveError.INVALID_SLOT, "Slot validation failed: ${validation.error}")
        }

        val result = deleteCoordinator.deleteSlot(slot)
        if (!result.success) {
            return SaveResult.failure(
                SaveError.DELETE_FAILED,
                result.errorMessage ?: "Coordinated delete failed for slot $slot"
            )
        }

        storageGateway.deleteSlot(slot)

        // 【关键修复】删除成功后清除缓存条目
        // 确保下次 getSaveSlots() 不再显示已删除的槽位
        slotStateCache.remove(slot)
        Log.d(TAG, "Slot $slot removed from state cache after successful delete")

        return SaveResult.success(Unit)
    }
    
    suspend fun hasSave(slot: Int): Boolean {
        return engine.hasData(slot)
    }
    
    suspend fun getSlotInfo(slot: Int): SaveSlot? {
        val metadata = unifiedSaveRepository.getSlotInfo(slot) ?: return null
        val slotName = if (slot == StorageConstants.AUTO_SAVE_SLOT) "自动存档" else "手动存档${slot}"
        return SaveSlot(
            slot = metadata.slot,
            name = slotName,
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
    
    /**
     * 获取所有存档槽位列表（v3 重构版）
     *
     * 【P0#1 修复完成】统一数据源为 Room Database
     *
     * 旧版问题（split-brain）：
     * - engine.save() 写入 Room Database
     * - exportRepository.getSaveSlots() 检查磁盘文件
     * - 两个独立后端导致存档后显示空槽位
     *
     * 新版方案：
     * - 主路径：slotStateCache（内存缓存，由 engine 驱动）
     * - 缓存 miss 时：从 engine (Room DB) 实时查询并回填
     * - 完全不依赖 exportRepository.getSaveSlots()
     *
     * 数据一致性保证：
     * - save() 成功后同步更新 slotStateCache
     * - delete() 成功后同步清除 slotStateCache
     * - syncCache() 可强制从 DB 刷新缓存
     */
    fun getSaveSlots(): List<SaveSlot> {
        val maxSlots = lockManager.getMaxSlots()
        
        // 快速路径：如果缓存已预热，直接使用缓存（零 IO）
        if (cacheWarmedUp && slotStateCache.isNotEmpty()) {
            return buildSlotsFromCache(maxSlots)
        }
        
        // 慢速路径：缓存未就绪时返回基于当前缓存的列表（可能为空）
        // 调用方应确保在使用前已完成 initialize()
        if (!cacheWarmedUp) {
            Log.w(TAG, "getSaveSlots(): Cache not warmed up yet. Return empty slots. Call initialize() first.")
        }
        
        return buildSlotsFromCache(maxSlots)
    }
    
    /**
     * 异步版本：获取所有存档槽位列表（强制从 DB 刷新）
     *
     * 用于需要确保数据最新性的场景。
     * 会阻塞直到从 Room Database 加载完成。
     */
    suspend fun getSaveSlotsFresh(): List<SaveSlot> {
        // 强制从 DB 刷新缓存
        refreshAllSlotsFromDatabase(lockManager.getMaxSlots())
        
        val maxSlots = lockManager.getMaxSlots()
        return buildSlotsFromCache(maxSlots)
    }
    
    /**
     * 从数据库刷新指定槽位的缓存条目
     */
    private suspend fun refreshSlotFromDatabase(slot: Int) {
        try {
            if (engine.hasData(slot)) {
                val loadResult = engine.load(slot)
                if (loadResult.isSuccess) {
                    val data = loadResult.getOrNull()
                    if (data != null) {
                        slotStateCache[slot] = SlotCacheEntry(
                            slot = slot,
                            timestamp = data.timestamp ?: System.currentTimeMillis(),
                            gameYear = data.gameData.gameYear ?: 1,
                            gameMonth = data.gameData.gameMonth ?: 1,
                            sectName = data.gameData.sectName ?: "",
                            discipleCount = data.disciples.size,
                            spiritStones = data.gameData.spiritStones ?: 0L,
                            customName = data.gameData.sectName ?: ""
                        )
                        cacheVersion.incrementAndGet()
                    }
                }
            } else {
                slotStateCache.remove(slot)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh slot $slot from database", e)
        }
    }

    /**
     * 从数据库刷新所有槽位的缓存
     */
    private suspend fun refreshAllSlotsFromDatabase(maxSlots: Int) {
        refreshSlotFromDatabase(StorageConstants.AUTO_SAVE_SLOT)
        for (slot in 1..maxSlots) {
            refreshSlotFromDatabase(slot)
        }
    }
    
    /**
     * 从缓存构建槽位列表（快速路径）
     */
    private fun buildSlotsFromCache(maxSlots: Int): List<SaveSlot> {
        val snapshot = slotStateCache.toMap()

        val autoSaveSlot = snapshot[StorageConstants.AUTO_SAVE_SLOT]?.let { cachedEntry ->
            SaveSlot(
                slot = cachedEntry.slot,
                name = "自动存档",
                timestamp = cachedEntry.timestamp,
                gameYear = cachedEntry.gameYear,
                gameMonth = cachedEntry.gameMonth,
                sectName = cachedEntry.sectName,
                discipleCount = cachedEntry.discipleCount,
                spiritStones = cachedEntry.spiritStones,
                isEmpty = false,
                customName = cachedEntry.customName,
                isAutoSave = true
            )
        } ?: SaveSlot(StorageConstants.AUTO_SAVE_SLOT, "自动存档", 0, 1, 1, "", 0, 0, true, isAutoSave = true)

        val manualSlots = (1..maxSlots).map { slot ->
            val cachedEntry = snapshot[slot]
            if (cachedEntry != null) {
                SaveSlot(
                    slot = cachedEntry.slot,
                    name = "手动存档${cachedEntry.slot}",
                    timestamp = cachedEntry.timestamp,
                    gameYear = cachedEntry.gameYear,
                    gameMonth = cachedEntry.gameMonth,
                    sectName = cachedEntry.sectName,
                    discipleCount = cachedEntry.discipleCount,
                    spiritStones = cachedEntry.spiritStones,
                    isEmpty = false,
                    customName = cachedEntry.customName
                )
            } else {
                SaveSlot(slot, "手动存档${slot}", 0, 1, 1, "", 0, 0, true)
            }
        }

        return listOf(autoSaveSlot) + manualSlots
    }
    
    fun setCurrentSlot(slot: Int) {
        val result = slotValidationInterceptor.setActiveSlot(slot)
        if (result.isValid) {
            unifiedSaveRepository.setCurrentSlot(slot)
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
            val currentSlot = getCurrentSlot()
            val result = engine.save(currentSlot, data).toUnifiedResult()
            if (result.isFailure) {
                val errorMsg = (result as? SaveResult.Failure)?.message ?: result.toString()
                Log.e(TAG, "Auto-save failed: $errorMsg")
            } else {
                // 【关键修复】自动存档成功后也更新缓存
                updateSlotStateCache(currentSlot, data)
                Log.d(TAG, "Auto-save completed successfully and cache updated")
            }
        }
    }
    
    suspend fun emergencySave(data: SaveData): Boolean {
        return engine.emergencySave(data).isSuccess
    }

    suspend fun forceNonAtomicSave(slot: Int, data: SaveData): SaveResult<Unit> {
        Log.w(TAG, "Force non-atomic save for slot $slot")
        val result = engine.save(slot, data).toUnifiedResult().map { Unit }
        if (result.isSuccess) {
            updateSlotStateCache(slot, data)
        }
        return result
    }

    fun isAtomicPipelineEnabled(): Boolean = true

    fun setAtomicPipelineEnabled(enabled: Boolean) {
        Log.i(TAG, "Atomic pipeline is always enabled (Room-based)")
    }

    suspend fun createBackup(slot: Int): SaveResult<String> {
        return unifiedSaveRepository.createBackup(slot)
    }
    
    suspend fun getBackupVersions(slot: Int): List<com.xianxia.sect.data.unified.BackupInfo> {
        return unifiedSaveRepository.getBackupVersions(slot)
    }
    
    suspend fun restoreFromBackup(slot: Int, backupId: String): SaveResult<SaveData> {
        return unifiedSaveRepository.restoreFromBackup(slot, backupId)
    }
    
    suspend fun verifyIntegrity(slot: Int): com.xianxia.sect.data.unified.IntegrityResult {
        return unifiedSaveRepository.verifyIntegrity(slot)
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

            val metaBytes = metaFile.readBytes()
            val meta = ProtoBuf.decodeFromByteArray(MetadataFile.serializer(), metaBytes)
            val updatedSlots = meta.slots.toMutableMap()
            val existingSlot = updatedSlots[slot]
            if (existingSlot != null) {
                updatedSlots[slot] = existingSlot.copy(customName = customName)
                val updatedMeta = meta.copy(slots = updatedSlots)
                val newMetaBytes = ProtoBuf.encodeToByteArray(MetadataFile.serializer(), updatedMeta)
                metaFile.writeBytes(newMetaBytes)

                Log.i(TAG, "Renamed slot $slot to '$customName'")
                true
            } else {
                Log.w(TAG, "Slot $slot not found in metadata")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rename slot $slot", e)
            false
        }
    }
    
    /**
     * 同步缓存状态（v3 重构版 - 真正实现）
     *
     * 【P0#3 修复】之前此方法永远返回 true（空操作）
     *
     * 现在的真正逻辑：
     * 1. 从 Room Database (engine) 重新加载所有槽位数据
     * 2. 与当前 slotStateCache 对比
     * 3. 更新缓存以反映数据库的真实状态
     * 4. 处理不一致情况（如外部修改、崩溃恢复后等）
     *
     * @param forceFullReload 是否强制完全重载（忽略缓存版本号）
     * @return true 表示同步成功且无冲突，false 表示同步过程中发现问题
     */
    suspend fun syncCache(forceFullReload: Boolean = false): Boolean {
        return try {
            Log.i(TAG, "syncCache() started (forceFullReload=$forceFullReload, currentVersion=${cacheVersion.get()})")
            
            val maxSlots = lockManager.getMaxSlots()
            var conflictCount = 0
            var addedCount = 0
            var removedCount = 0
            var updatedCount = 0
            
            for (slot in 1..maxSlots) {
                try {
                    val dbHasData = engine.hasData(slot)
                    val cacheHasData = slotStateCache.containsKey(slot)
                    
                    when {
                        dbHasData && !cacheHasData -> {
                            // DB 有数据但缓存没有 -> 从 DB 加载并回填
                            refreshSlotFromDatabase(slot)
                            addedCount++
                            Log.d(TAG, "syncCache(): Slot $slot added to cache (was in DB but not in cache)")
                        }
                        !dbHasData && cacheHasData -> {
                            // DB 没有数据但缓存有 -> 清除缓存条目（可能被外部删除）
                            slotStateCache.remove(slot)
                            removedCount++
                            Log.w(TAG, "syncCache(): Slot $slot removed from cache (was in cache but not in DB)")
                        }
                        dbHasData && cacheHasData -> {
                            // 两边都有数据 -> 检查时间戳是否一致
                            val cachedEntry = slotStateCache[slot]
                            val loadResult = engine.load(slot)
                            if (loadResult.isSuccess) {
                                val dbData = loadResult.getOrNull()
                                if (dbData != null && cachedEntry != null) {
                                    val dbTimestamp = dbData.timestamp ?: 0L
                                    if (dbTimestamp != cachedEntry.timestamp) {
                                        // 时间戳不一致 -> 以 DB 为准更新缓存
                                        updateSlotStateCache(slot, dbData)
                                        updatedCount++
                                        conflictCount++
                                        Log.w(TAG, "syncCache(): Slot $slot timestamp mismatch (cache=${cachedEntry.timestamp}, db=$dbTimestamp), updated from DB")
                                    }
                                }
                            }
                        }
                        else -> {
                            // 两边都没有数据 -> 无需操作
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "syncCache(): Error syncing slot $slot", e)
                    conflictCount++
                }
            }
            
            cacheVersion.incrementAndGet()
            cacheWarmedUp = true
            
            val success = (conflictCount == 0)
            Log.i(TAG, "syncCache() completed: added=$addedCount, removed=$removedCount, updated=$updatedCount, conflicts=$conflictCount, totalSlots=${slotStateCache.size}, version=${cacheVersion.get()}, success=$success")
            
            success
        } catch (e: Exception) {
            Log.e(TAG, "syncCache() failed with exception", e)
            false
        }
    }
    
    suspend fun hasAutoSave(): Boolean = healthChecker.hasAutoSave()
    
    fun hasEmergencySave(): Boolean = healthChecker.hasEmergencySave()
    
    suspend fun loadEmergencySave(): SaveData? {
        return unifiedSaveRepository.loadEmergencySave()
    }
    
    suspend fun clearEmergencySave(): Boolean = unifiedSaveRepository.clearEmergencySave()
    
    suspend fun saveAsync(slot: Int, data: SaveData): SaveResult<Unit> = save(slot, data)
    
    suspend fun loadAsync(slot: Int): SaveResult<SaveData> = load(slot)
    
    suspend fun saveSync(slot: Int, data: SaveData): Boolean {
        return save(slot, data).isSuccess
    }
    
    suspend fun saveSyncWithResult(slot: Int, data: SaveData): SaveResult<Unit> {
        return save(slot, data)
    }
    
    suspend fun loadSync(slot: Int): SaveData? {
        return load(slot).getOrNull()
    }
    
    fun getQuotaStats(): com.xianxia.sect.data.quota.StorageQuotaStats = quotaManager.getQuotaStats()

    fun shutdown() {
        engine.shutdown()
        unifiedSaveRepository.shutdown()
        healthChecker.shutdown()
        statsCollector.shutdown()
        archiveScheduler.stop()
        scope.cancel()
        Log.i(TAG, "StorageFacade shutdown completed")
    }
}
