package com.xianxia.sect.data.engine

import android.util.Log
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.util.fixStorageBagReferences
import com.xianxia.sect.data.GameStateRepository
import com.xianxia.sect.data.integrity.IntegrityResult
import com.xianxia.sect.data.integrity.SaveValidator
import com.xianxia.sect.data.integrity.SaveValidatorFixes
import com.xianxia.sect.data.integrity.corrupted.CorruptedResultHandler
import com.xianxia.sect.data.archive.DataArchiver
import com.xianxia.sect.data.backup.SaveFileManager
import com.xianxia.sect.data.cache.CacheKey
import com.xianxia.sect.data.config.SaveLimitsConfig
import com.xianxia.sect.data.config.StorageConfig
import com.xianxia.sect.data.incremental.ChangeLogOperation
import com.xianxia.sect.data.local.GameHeavyDataDao
import com.xianxia.sect.data.local.ProtobufConverters
import com.xianxia.sect.data.local.SaveSlotMetadata
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.model.SaveSlot
import com.xianxia.sect.data.result.StorageError
import com.xianxia.sect.data.result.StorageResult
import com.xianxia.sect.data.serialization.unified.SaveDataReconciler
import com.xianxia.sect.data.serialization.unified.SerializationModule
import com.xianxia.sect.data.StorageConstants
import com.xianxia.sect.data.unified.SlotMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.Immutable
import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton

data class EngineProgress(
    val stage: Stage,
    val progress: Float,
    val message: String = ""
) {
    enum class Stage {
        IDLE,
        VALIDATING,
        SAVING_CORE,
        SAVING_DISCIPLES,
        SAVING_ITEMS,
        SAVING_WORLD,
        SAVING_HISTORY,
        UPDATING_CACHE,
        COMPLETED,
        FAILED
    }
}

@Immutable
data class SaveOperationStats(
    val bytesWritten: Long = 0,
    val timeMs: Long = 0,
    val wasIncremental: Boolean = false
)

enum class SavePriority {
    NORMAL,
    HIGH,
    CRITICAL
}

@Singleton
class StorageEngine @Inject constructor(
    private val core: StorageCoreFacade,
    private val saveLimitsConfig: SaveLimitsConfig,
    private val dataArchiver: DataArchiver,
    private val infra: StorageInfraFacade,
    private val maintenanceFacade: StorageMaintenanceFacade,
    private val stateStore: GameStateStore,
    private val repository: GameStateRepository,
    private val saveFileManager: SaveFileManager,
    private val serializationModule: SerializationModule,
    private val storageConfig: StorageConfig
) {
    companion object {
        private const val TAG = "StorageEngine"
        private const val MAX_BATCH_SIZE = 200

        /**
         * 低内存保存守卫阈值（MB）。低于此值拒绝保存并返回失败，
         * 避免"静默跳过但报成功"导致内存态与 DB 脱节（2026-08-01 修复）。
         */
        private const val LOW_MEMORY_THRESHOLD_MB = 100L

        fun estimateSaveSize(data: SaveData): Long {
            val es = StorageConstants.EntitySize

            var size = StorageConstants.ESTIMATE_BASE_OVERHEAD

            size += data.disciples.size * es.DISCIPLE
            size += data.equipmentStacks.size * es.EQUIPMENT
            size += data.equipmentInstances.size * es.EQUIPMENT
            size += data.manualStacks.size * es.MANUAL
            size += data.manualInstances.size * es.MANUAL
            size += data.pills.size * es.PILL
            size += data.materials.size * es.MATERIAL
            size += data.herbs.size * es.HERB
            size += data.seeds.size * es.SEED
            size += data.battleLogs.size * es.BATTLE_LOG
            size += data.teams.size * es.TEAM
            size += data.alliances.size * es.ALLIANCE

            val serializationOverhead = (size * StorageConstants.ESTIMATE_SERIALIZATION_OVERHEAD_RATIO).toLong()
            size += serializationOverhead

            return size
        }
    }

    private val scope get() = infra.scopeProvider.ioScope

    private val _progress = MutableStateFlow(EngineProgress(EngineProgress.Stage.IDLE, 0f))
    val progress: StateFlow<EngineProgress> = _progress.asStateFlow()

    private val _currentSlot = MutableStateFlow(1)
    val currentSlot: StateFlow<Int> = _currentSlot.asStateFlow()

    suspend fun save(slot: Int, data: SaveData, priority: SavePriority = SavePriority.NORMAL): StorageResult<SaveOperationStats> {
        if (!core.lockManager.isValidSlot(slot)) {
            return StorageResult.failure(StorageError.INVALID_SLOT, "Invalid slot: $slot")
        }

        return core.lockManager.withWriteLockLight(slot) {
            try {
                val startTime = System.currentTimeMillis()

                // P-2 拆分：保存前校验 + 清理 + 时间戳
                val dataWithTimestamp = validateAndPrepareData(slot, data)
                    ?: return@withWriteLockLight StorageResult.failure(
                        StorageError.SAVE_FAILED, "保存前校验拒绝：存档数据损坏"
                    )

                // P-2 拆分：重试保存（OOM 短路）
                val result = saveWithRetry(slot, dataWithTimestamp)

                // P-2 拆分：结果处理（备份/缓存/变更日志/失败恢复）
                handleSaveResult(slot, result, dataWithTimestamp)

                result.map { stats ->
                    val elapsed = System.currentTimeMillis() - startTime
                    stats.copy(timeMs = elapsed)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Save failed for slot $slot", e)
                _progress.value = EngineProgress(EngineProgress.Stage.FAILED, 0f, e.message ?: "Unknown error")
                // 2026-08-01 对抗性审查修复：OutOfMemoryError 是 Error 非 Exception，
                // 旧 `is OutOfMemoryError` 分支是死代码；SerializationFailureException
                // 的 cause 可能是 OOM——识别异常链使 save() 重试循环正确短路
                // （OOM 重试无意义，旧行为会完整重试 3 次全量编码拉长 ANR 窗口）
                val isOom = e is OutOfMemoryError || e.cause is OutOfMemoryError
                val error = when {
                    isOom -> StorageError.OUT_OF_MEMORY
                    e is java.io.IOException -> StorageError.IO_ERROR
                    else -> StorageError.SAVE_FAILED
                }
                StorageResult.failure(error, e.message ?: "Save failed", e)
            }
        }
    }

    /**
     * P-2：保存前数据准备——完整性校验（损坏拒绝/修复替换）+ 清理 + 时间戳。
     *
     * @return 准备后的数据；校验拒绝损坏数据时返回 null
     */
    private suspend fun validateAndPrepareData(slot: Int, data: SaveData): SaveData? {
        // ── 保存前完整性校验 ──
        var effectiveData = data
        if (storageConfig.enablePreSaveValidation) {
            _progress.value = EngineProgress(EngineProgress.Stage.VALIDATING, 0.05f, "Validating data")
            val integrityResult = SaveValidator.validate(data)
            when (integrityResult) {
                is IntegrityResult.Corrupted -> {
                    Log.e(TAG, "拒绝保存损坏数据 slot=$slot")
                    infra.storageMetrics.recordBackupFailure()
                    return null
                }
                is IntegrityResult.Repaired -> {
                    // ★ 修复：使用修复后的数据替换原始数据，确保修复持久化
                    Log.w(TAG, "保存前校验修复 ${integrityResult.details.size} 项，使用修复后数据 slot=$slot")
                    effectiveData = integrityResult.data
                }
                is IntegrityResult.Passed -> { /* 无操作 */ }
            }
        }

        _progress.value = EngineProgress(EngineProgress.Stage.SAVING_CORE, 0.1f, "Saving core data")

        val cleanedData = cleanSaveDataWithArchive(effectiveData)
        return cleanedData.copy(timestamp = System.currentTimeMillis())
    }

    /** P-2：全量事务保存 + 重试（内存守卫已前置；OOM 类失败直接终止重试）。 */
    private suspend fun saveWithRetry(
        slot: Int,
        dataWithTimestamp: SaveData
    ): StorageResult<SaveOperationStats> {
        var result = performFullTransactionSave(slot, dataWithTimestamp)
        var retryCount = 0
        val maxRetries = storageConfig.maxRetryCount
        while (result.isFailure && retryCount < maxRetries) {
            // OOM 类失败重试无意义（内存不会在毫秒级恢复），直接终止
            if (result is StorageResult.Failure && result.error == StorageError.OUT_OF_MEMORY) break
            retryCount++
            Log.w(TAG, "保存重试 ($retryCount/$maxRetries) slot=$slot")
            kotlinx.coroutines.delay(storageConfig.retryDelayMs * retryCount)
            result = performFullTransactionSave(slot, dataWithTimestamp)
        }
        return result
    }

    /**
     * P-2：保存结果处理——成功（备份/缓存/变更日志）或失败（备份恢复尝试）。
     * 备份仅在 DB 事务成功后写入（2026-08-01 时序修复，避免"备份比真相新"）。
     */
    @Suppress("NestedBlockDepth")  // 备份异常处理守卫结构（try/catch 嵌套为既有模式）
    private suspend fun handleSaveResult(
        slot: Int,
        result: StorageResult<SaveOperationStats>,
        dataWithTimestamp: SaveData
    ) {
        if (result.isSuccess) {
            if (storageConfig.autoBackupOnSave) {
                _progress.value = EngineProgress(EngineProgress.Stage.VALIDATING, 0.15f, "Writing backup")
                try {
                    val br = saveFileManager.atomicWrite(slot, dataWithTimestamp)
                    if (br.isSuccess) infra.storageMetrics.recordBackupSuccess()
                    else infra.storageMetrics.recordBackupFailure()
                } catch (e: Exception) {
                    Log.w(TAG, "备份异常 slot=$slot (非阻断)", e)
                    infra.storageMetrics.recordBackupFailure()
                }
            }
            _progress.value = EngineProgress(EngineProgress.Stage.UPDATING_CACHE, 0.8f, "Updating cache")
            updateCacheAfterSave(slot, dataWithTimestamp)
            _progress.value = EngineProgress(EngineProgress.Stage.SAVING_HISTORY, 0.85f, "Logging changes")
            logSaveChanges(slot, dataWithTimestamp)
            infra.storageMetrics.recordSave()
            _progress.value = EngineProgress(EngineProgress.Stage.COMPLETED, 1.0f, "Save completed")
        } else {
            Log.e(TAG, "保存失败（${storageConfig.maxRetryCount}次重试），尝试恢复 slot=$slot")
            try {
                val rr = saveFileManager.readWithFallback(slot)
                if (rr.status == com.xianxia.sect.data.backup.BackupStatus.SUCCESS ||
                    rr.status == com.xianxia.sect.data.backup.BackupStatus.RECOVERED) {
                    Log.w(TAG, "从备份恢复数据成功 slot=$slot")
                }
            } catch (e2: Exception) {
                Log.e(TAG, "备份恢复也失败 slot=$slot", e2)
            }
        }
    }

    suspend fun load(slot: Int): StorageResult<SaveData> {
        if (!core.lockManager.isValidSlot(slot)) {
            return StorageResult.failure(StorageError.INVALID_SLOT, "Invalid slot: $slot")
        }

        return core.lockManager.withReadLockLight(slot) {
            try {
                // P-2 拆分：缓存命中优先
                tryCacheLoad(slot)?.let { return@withReadLockLight StorageResult.success(it) }

                infra.storageMetrics.recordCacheMiss()
                _progress.value = EngineProgress(EngineProgress.Stage.SAVING_CORE, 0.2f, "Loading from database")
                val dbData = loadFromDatabase(slot)

                if (dbData != null) {
                    infra.storageMetrics.recordLoad()
                    clearCacheForSlot(slot)
                    // P-2 拆分：完整性校验 + 损坏备份恢复
                    return@withReadLockLight validateDbData(slot, dbData)
                }

                // ── 数据库无数据时尝试从备份文件恢复 ──
                val restored = restoreFromBackup(slot)
                if (restored != null) return@withReadLockLight restored
                _progress.value = EngineProgress(EngineProgress.Stage.FAILED, 0f, "No data found")
                StorageResult.failure(StorageError.SLOT_EMPTY, "No data in slot $slot")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Load failed for slot $slot", e)
                _progress.value = EngineProgress(EngineProgress.Stage.FAILED, 0f, e.message ?: "Unknown error")
                StorageResult.failure(StorageError.LOAD_FAILED, e.message ?: "Load failed", e)
            }
        }
    }

    /** P-2：缓存命中尝试（命中时记录指标与进度，返回数据；未命中返回 null）。 */
    private suspend fun tryCacheLoad(slot: Int): SaveData? {
        _progress.value = EngineProgress(EngineProgress.Stage.SAVING_CORE, 0.1f, "Loading from cache")
        val cachedData = loadFromCache(slot) ?: return null
        infra.storageMetrics.recordCacheHit()
        infra.storageMetrics.recordLoad()
        Log.d(TAG, "Cache hit for slot $slot")
        _progress.value = EngineProgress(EngineProgress.Stage.COMPLETED, 1.0f, "Load completed (cache)")
        return cachedData
    }

    /**
     * P-2：数据库数据完整性校验（通过/修复/损坏→备份恢复）。
     *
     * 修复后数据仅缓存（读锁内无法升级写锁持久化，下次保存时自动持久化）。
     */
    @Suppress("ReturnCount")  // 校验结果分派（通过/修复/损坏→恢复），多 return 为守卫风格
    private suspend fun validateDbData(slot: Int, dbData: SaveData): StorageResult<SaveData> {
        val integrityResult = SaveValidator.validate(dbData)
        when (integrityResult) {
            is IntegrityResult.Passed -> {
                updateCacheAfterSave(slot, dbData)
                _progress.value = EngineProgress(EngineProgress.Stage.COMPLETED, 1.0f, "Load completed (database)")
                return StorageResult.success(dbData)
            }
            is IntegrityResult.Repaired -> {
                Log.w(TAG, "存档完整性修复 (slot=$slot): ${integrityResult.details.size} 项")
                integrityResult.details.forEach { Log.i(TAG, "  → $it") }
                val repairedData = integrityResult.data
                SaveValidatorFixes.logRepairStatus(slot, integrityResult.details.size, persisted = false)
                updateCacheAfterSave(slot, repairedData)
                _progress.value = EngineProgress(EngineProgress.Stage.COMPLETED, 1.0f, "Load completed (database)")
                return StorageResult.success(repairedData)
            }
            is IntegrityResult.Corrupted -> {
                Log.e(TAG, "存档数据损坏 (slot=$slot): ${integrityResult.details.size} 项")
                integrityResult.details.forEach { Log.e(TAG, "  → $it") }
                val restored = restoreFromBackup(slot)
                if (restored != null) return restored
                _progress.value = EngineProgress(EngineProgress.Stage.FAILED, 0f,
                    "存档损坏且备份恢复失败: ${integrityResult.details.size} 项问题")
                return StorageResult.failure(
                    StorageError.SLOT_CORRUPTED,
                    "存档校验失败且备份不可用 (slot=$slot): ${integrityResult.details.joinToString("; ")}"
                )
            }
        }
    }

    /**
     * P-2：从备份文件恢复（损坏恢复/无数据恢复两场景共用）。
     *
     * 流程：读备份 → 反序列化 → 二次验证 → 堆叠重建 → 写库（检查结果）→ 缓存。
     *
     * @return 恢复成功的数据；备份不可用时返回 null（调用方决定失败语义）
     */
    @Suppress("ReturnCount")  // 备份恢复多失败路径，多 return 为守卫风格
    private suspend fun restoreFromBackup(
        slot: Int
    ): StorageResult<SaveData>? {
        _progress.value = EngineProgress(EngineProgress.Stage.VALIDATING, 0.5f, "尝试从备份恢复...")
        val readResult = saveFileManager.readWithFallback(slot)
        if (readResult.status != com.xianxia.sect.data.backup.BackupStatus.SUCCESS &&
            readResult.status != com.xianxia.sect.data.backup.BackupStatus.RECOVERED
        ) {
            Log.w(TAG, "备份文件不存在或损坏 slot=$slot")
            return null
        }
        try {
            var restoredData = serializationModule.deserializeSaveData(
                readResult.payload ?: return StorageResult.failure(
                    StorageError.SLOT_CORRUPTED, "备份恢复失败：payload 为空 (slot=$slot)"
                )
            )
            Log.w(TAG, "备份恢复成功 (slot=$slot) 来源=${readResult.source}")

            // ★ 备份恢复后二次验证：防止备份本身存在数据问题
            val reValidation = CorruptedResultHandler.validateRestoredData(slot, restoredData)
            if (reValidation is IntegrityResult.Repaired) {
                Log.w(TAG, "备份恢复数据二次修复 ${reValidation.details.size} 项 (slot=$slot)")
                restoredData = reValidation.data
            } else if (reValidation is IntegrityResult.Corrupted) {
                Log.e(TAG, "备份恢复数据二次验证无法修复 (slot=$slot)")
                return StorageResult.failure(
                    StorageError.SLOT_CORRUPTED,
                    "备份恢复数据二次验证无法修复 (slot=$slot): ${reValidation.details.joinToString("; ")}"
                )
            }

            infra.storageMetrics.recordBackupRestore()
            // 旧格式备份无堆叠数据：从实例重建兜底（2026-08-01 堆叠序列化缺陷修复）
            restoredData = SaveDataReconciler.reconcileStacks(restoredData)
            // 2026-08-01 对抗性审查修复：检查保存结果——低内存/编码失败时
            // 不再静默"报成功"（旧实现忽略结果，DB 未写但 load 返回 success）
            // S11 修复（对抗性审查）：写库失败必须返回失败——否则 load 报成功、
            // 缓存与内存持有恢复数据，但 DB 仍是损坏数据 → 重启后再损坏、恢复
            // 循环丢进度（原实现仅 Log.e 后继续 success，与注释声明矛盾）
            val restoreSave = performFullTransactionSave(slot, restoredData)
            if (restoreSave is com.xianxia.sect.data.result.StorageResult.Failure) {
                Log.e(TAG, "备份恢复写库失败 slot=$slot: ${restoreSave.message}")
                return StorageResult.failure(
                    StorageError.SLOT_CORRUPTED,
                    "备份恢复写库失败 (slot=$slot): ${restoreSave.message}"
                )
            }
            clearCacheForSlot(slot)
            updateCacheAfterSave(slot, restoredData)
            _progress.value = EngineProgress(EngineProgress.Stage.COMPLETED, 1.0f, "Load completed (backup)")
            return StorageResult.success(restoredData)
        } catch (e: Exception) {
            Log.e(TAG, "备份反序列化失败 slot=$slot", e)
            return null
        }
    }

    suspend fun delete(slot: Int): StorageResult<Unit> {
        if (!core.lockManager.isValidSlot(slot)) {
            return StorageResult.failure(StorageError.INVALID_SLOT, "Invalid slot: $slot")
        }

        Log.i(TAG, "Deleting slot $slot")

        return core.lockManager.withWriteLockLight(slot) {
            try {
                clearCacheForSlot(slot)

                core.database.withTransaction {
                    core.database.gameDataDao().deleteAll(slot)
                    core.database.discipleDao().deleteAll(slot)
                    core.database.discipleCoreDao().deleteAll(slot)
                    core.database.discipleCombatStatsDao().deleteAll(slot)
                    core.database.discipleEquipmentDao().deleteAll(slot)
                    core.database.discipleExtendedDao().deleteAll(slot)
                    core.database.discipleAttributesDao().deleteAll(slot)
                    core.database.equipmentStackDao().deleteAll(slot)
                    core.database.equipmentInstanceDao().deleteAll(slot)
                    core.database.manualStackDao().deleteAll(slot)
                    core.database.manualInstanceDao().deleteAll(slot)
                    core.database.pillDao().deleteAll(slot)
                    core.database.materialDao().deleteAll(slot)
                    core.database.seedDao().deleteAll(slot)
                    core.database.herbDao().deleteAll(slot)
                    core.database.explorationTeamDao().deleteAll(slot)
                    core.database.buildingSlotDao().deleteAll(slot)
                    core.database.recipeDao().deleteAll(slot)
                    core.database.productionSlotDao().deleteBySlot(slot)
                    core.database.battleLogDao().deleteAll(slot)
                    core.database.mailDao().deleteAllForSlot(slot)
                    core.database.saveSlotMetadataDao().deleteBySlotId(slot)
                    core.database.storageBagDao().deleteAll(slot)
                    core.database.gameHeavyDataDao().deleteAllForSlot(slot)
                    core.database.diplomacyStateDao().deleteBySlot(slot)
                    core.database.productionStateDao().deleteBySlot(slot)
                    core.database.patrolStateDao().deleteBySlot(slot)
                    core.database.worldMapStateDao().deleteBySlot(slot)
                    core.database.sectPolicyStateDao().deleteBySlot(slot)
                    core.database.discipleCompactDao().deleteAll(slot)
                }

                clearCacheForSlot(slot)
                saveFileManager.deleteSlot(slot)

                Log.i(TAG, "Deleted all data for slot $slot")
                StorageResult.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Delete failed for slot $slot", e)
                StorageResult.failure(StorageError.DELETE_FAILED, e.message ?: "Delete failed", e)
            }
        }
    }

    suspend fun hasData(slot: Int): Boolean {
        if (!core.lockManager.isValidSlot(slot)) return false

        return try {
            core.database.gameDataDao().existsBySlot(slot) != null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "hasData check failed for slot $slot", e)
            false
        }
    }

    suspend fun getSlotMetadata(slot: Int): SlotMetadata? {
        if (!core.lockManager.isValidSlot(slot)) return null

        return try {
            val meta = core.database.gameDataDao().getMetadataBySlot(slot) ?: return null
            SlotMetadata(
                slot = slot,
                timestamp = meta.lastSaveTime,
                gameYear = meta.gameYear,
                gameMonth = meta.gameMonth,
                sectName = meta.sectName,
                discipleCount = core.database.discipleDao().getAliveCountSync(slot),
                spiritStones = meta.spiritStones,
                fileSize = 0,
                customName = meta.sectName
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "getSlotMetadata failed for slot $slot", e)
            null
        }
    }

    suspend fun listSlots(): StorageResult<List<SlotMetadata>> {
        return try {
            val slots = (1..core.lockManager.getMaxSlots()).mapNotNull { slot ->
                getSlotMetadata(slot)
            }
            StorageResult.success(slots)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "listSlots failed", e)
            StorageResult.failure(StorageError.LOAD_FAILED, e.message ?: "Failed to list slots")
        }
    }

    suspend fun getSaveSlots(): List<SaveSlot> {
        val slots = mutableListOf<SaveSlot>()

        // slot 0 = 云存档入口（旧自动存档改造而来）
        slots.add(SaveSlot(
            slot = StorageConstants.CLOUD_SAVE_SLOT,
            name = "云存档",
            timestamp = 0L,
            gameYear = 0,
            gameMonth = 0,
            sectName = "云存档",
            discipleCount = 0,
            spiritStones = 0L,
            isEmpty = false
        ))

        for (slot in 1..core.lockManager.getMaxSlots()) {
            try {
                slots.add(querySingleSlot(slot))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to query slot $slot, using empty placeholder", e)
                slots.add(SaveSlot(slot, "", 0, 1, 1, "", 0, 0, true))
            }
        }

        return slots
    }

    fun setCurrentSlot(slot: Int) {
        if (core.lockManager.isValidSlot(slot)) {
            _currentSlot.value = slot
        }
    }

    fun getCurrentSlot(): Int = _currentSlot.value

    /**
     * 强制删除指定 slot 的数据（跳过 slot 校验，用于云存档 slot 等特殊槽位）。
     * 仅清理 Room DB 中的 game_data 条目，不涉及文件级清理。
     */
    suspend fun forceDeleteSlotData(slot: Int) {
        try {
            core.database.gameDataDao().deleteAll(slot)
            Log.i(TAG, "forceDeleteSlotData: deleted data for slot $slot")
        } catch (e: CancellationException) { throw e }
          catch (e: Exception) {
            Log.w(TAG, "forceDeleteSlotData: failed for slot $slot", e)
        }
    }

    fun startMaintenance() {
        maintenanceFacade.startMaintenance()
        // ── WAL 恢复：扫描未完成事务（崩溃残留），仅记录日志供监控 ──
        scope.launch {
            try {
                val result = core.wal.recover()
                if (result.failedSlots.isNotEmpty()) {
                    Log.w(TAG, "WAL recovery: failedSlots=${result.failedSlots}, errors=${result.errors}")
                } else if (result.recoveredSlots.isNotEmpty()) {
                    Log.i(TAG, "WAL recovery: recoveredSlots=${result.recoveredSlots}")
                } else {
                    Log.i(TAG, "WAL recovery: clean (no incomplete transactions)")
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.e(TAG, "WAL recovery failed", e)
            }
        }
        Log.i(TAG, "Storage maintenance started")
    }

    fun stopMaintenance() {
        maintenanceFacade.stopMaintenance()
        Log.i(TAG, "Storage maintenance stopped")
    }

    fun shutdown() {
        maintenanceFacade.shutdown()
        core.cache.shutdown()
        core.wal.shutdown()
        core.lockManager.shutdown()
        Log.i(TAG, "StorageEngine shutdown completed")
    }

    private suspend fun cleanSaveDataWithArchive(data: SaveData): SaveData {
        val maxBattleLogs = saveLimitsConfig.maxBattleLogs

        val cleanedBattleLogs = if (data.battleLogs.size > maxBattleLogs) {
            val archiveResult = dataArchiver.archiveBattleLogsIfNeeded(data.battleLogs, maxBattleLogs)
            if (archiveResult.success && archiveResult.archivedCount > 0) {
                Log.i(TAG, "Archived ${archiveResult.archivedCount} battle logs")
            }
            dataArchiver.getRetainedBattleLogs(data.battleLogs, maxBattleLogs)
        } else {
            data.battleLogs
        }

        return data.copy(battleLogs = cleanedBattleLogs)
    }

    private suspend fun performFullTransactionSave(slot: Int, data: SaveData): StorageResult<SaveOperationStats> {
        // ── 内存守卫前置（2026-08-01 修复）：低内存直接失败，不写 DB / 不写 WAL / 不写备份 ──
        // 原实现低内存时静默 return 且上层仍报"保存成功"，导致内存态与 DB 脱节。
        if (availableMemoryMB() < LOW_MEMORY_THRESHOLD_MB) {
            Log.w(TAG, "Low memory (${availableMemoryMB()}MB available), save rejected for slot $slot")
            return StorageResult.failure(StorageError.OUT_OF_MEMORY, "内存不足（${availableMemoryMB()}MB），保存被拒绝")
        }

        // ── WAL 事务开始 ──
        var txnId: Long? = null
        try {
            val result = core.wal.beginTransaction(slot, com.xianxia.sect.data.wal.WALEntryType.DATA)
            if (result.isSuccess) txnId = result.getOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "WAL beginTransaction 失败（非阻断）", e)
        }

        try {
            val writeResult = core.database.withTransaction {
                writeAllDataToDatabase(slot, data)
            }
            if (writeResult.isFailure) {
                // OOM 类失败（TypeConverter 抛 SerializationFailureException 等）：
                // 事务已回滚，DB 保持旧数据，直接返回失败
                if (txnId != null) try { core.wal.abort(txnId) } catch (e2: Exception) { Log.w(TAG, "WAL abort 失败", e2) }
                return writeResult.map { SaveOperationStats(bytesWritten = 0, timeMs = 0, wasIncremental = false) }
            }

            try {
                core.database.performPostSaveCheckpoint()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Post-save checkpoint failed for slot $slot (non-fatal)", e)
            }

            // ── WAL 提交 ──
            if (txnId != null) {
                try {
                    core.wal.commit(txnId, currentGameYear = data.gameData.gameYear)
                } catch (e: Exception) {
                    Log.w(TAG, "WAL commit 失败（非阻断）", e)
                }
            }

            val bytesWritten = estimateSaveSize(data)
            return StorageResult.success(SaveOperationStats(bytesWritten = bytesWritten, timeMs = 0, wasIncremental = false))
        } catch (e: CancellationException) {
            // WAL 回滚后传递取消信号
            if (txnId != null) try { core.wal.abortSync(txnId) } catch (e2: Exception) { Log.w(TAG, "WAL abortSync 失败", e2) }
            throw e
        } catch (e: Exception) {
            if (txnId != null) try { core.wal.abort(txnId) } catch (e2: Exception) { Log.w(TAG, "WAL abort 失败", e2) }
            throw e
        }
    }

    private suspend fun writeAllDataToDatabase(slot: Int, data: SaveData): StorageResult<Unit> {
        Log.d(TAG, "writeAllDataToDatabase: slot=$slot, " +
            "${data.disciples.size} disciples, " +
            "recruitList=${data.gameData.recruitList.size} unrecruited")

        // ── 存档前数据完整性校验 ──
        if (data.gameData.worldMapSects.isEmpty()) {
            Log.e(TAG, "存档前检测到 worldMapSects 为空 slot=$slot — 数据管线异常，" +
                "世界地图宗门数据已丢失。请检查 ensureHeavyDataLoaded 日志。")
        }
        if (data.gameData.sectName.isBlank()) {
            Log.w(TAG, "存档前检测到 sectName 为空 slot=$slot")
        }

        val now = System.currentTimeMillis()
        val heavyDao = core.database.gameHeavyDataDao()

        // C-8 拆分：重型数据清理/写入/轻量实体写入分别提取（行为逐行一致）
        clearHeavyDataByPrefix(heavyDao, slot)
        writeHeavyDataIncremental(heavyDao, slot, data)

        // ── 轻型 GameData（所有大型字段已清空，TypeConverter 编码近乎零开销）──
        val lightGameData = buildLightGameData(data, slot)
        core.database.withTransaction {
            clearOldSlotEntities(slot, data)
            writeCoreEntities(slot, data, lightGameData)
            writeDomainEntities(slot, data)
        }

        return StorageResult.success(Unit)
    }

    /** C-8：清除旧重型数据（按前缀批量删除）。 */
    private suspend fun clearHeavyDataByPrefix(heavyDao: GameHeavyDataDao, slot: Int) {
        val allPrefixes = listOf(
            GameHeavyData.KEY_AI_SECT_DISCIPLES,
            GameHeavyData.KEY_SECT_DETAILS,
            GameHeavyData.KEY_EXPLORED_SECTS,
            GameHeavyData.KEY_SCOUT_INFO,
            GameHeavyData.KEY_MANUAL_PROFICIENCIES,
            GameHeavyData.KEY_RECRUIT_LIST,
            GameHeavyData.KEY_WORLD_MAP_SECTS
        )
        for (prefix in allPrefixes) {
            heavyDao.deleteByKeyPrefix(slot, prefix)
        }
    }

    /** C-8：增量编码写入重型数据（每项编码完立即写入，立即释放 ByteArray）。 */
    private suspend fun writeHeavyDataIncremental(heavyDao: GameHeavyDataDao, slot: Int, data: SaveData) {
        ProtobufConverters.encodeDiscipleListMapIncremental(
            data.gameData.aiSectDisciples, slot, GameHeavyData.KEY_AI_SECT_DISCIPLES
        ) { chunks -> heavyDao.upsertAll(chunks) }

        ProtobufConverters.encodeSectDetailMapIncremental(
            data.gameData.sectDetails, slot, GameHeavyData.KEY_SECT_DETAILS
        ) { chunks -> heavyDao.upsertAll(chunks) }

        ProtobufConverters.encodeExploredSectInfoMapIncremental(
            data.gameData.exploredSects, slot, GameHeavyData.KEY_EXPLORED_SECTS
        ) { chunks -> heavyDao.upsertAll(chunks) }

        ProtobufConverters.encodeSectScoutInfoMapIncremental(
            data.gameData.scoutInfo, slot, GameHeavyData.KEY_SCOUT_INFO
        ) { chunks -> heavyDao.upsertAll(chunks) }

        ProtobufConverters.encodeManualProficiencyMapIncremental(
            data.gameData.manualProficiencies, slot, GameHeavyData.KEY_MANUAL_PROFICIENCIES
        ) { chunks -> heavyDao.upsertAll(chunks) }

        ProtobufConverters.encodeDiscipleListIncremental(
            data.gameData.recruitList, slot, GameHeavyData.KEY_RECRUIT_LIST
        ) { chunks -> heavyDao.upsertAll(chunks) }

        ProtobufConverters.encodeWorldSectListIncremental(
            data.gameData.worldMapSects, slot, GameHeavyData.KEY_WORLD_MAP_SECTS
        ) { chunks -> heavyDao.upsertAll(chunks) }
    }

    /** C-8：构建轻型 GameData（大型字段清空，TypeConverter 编码近乎零开销）。 */
    private fun buildLightGameData(data: SaveData, slot: Int): GameData =
        data.gameData.copy(
            slotId = slot,
            id = "game_data_$slot",
            lastSaveTime = data.timestamp,
            aiSectDisciples = emptyMap(),
            sectDetails = emptyMap(),
            exploredSects = emptyMap(),
            scoutInfo = emptyMap(),
            manualProficiencies = emptyMap(),
            recruitList = emptyList(),
            worldMapSects = emptyList()
        )

    /** C-8：清空槽位旧数据（先清后写，防止旧存档高 ID 行残留）。 */
    private suspend fun clearOldSlotEntities(slot: Int, data: SaveData) {
        core.database.discipleDao().deleteAll(slot)
        core.database.discipleCoreDao().deleteAll(slot)
        core.database.discipleCombatStatsDao().deleteAll(slot)
        core.database.discipleEquipmentDao().deleteAll(slot)
        core.database.discipleExtendedDao().deleteAll(slot)
        core.database.discipleAttributesDao().deleteAll(slot)
        // 堆叠删表守卫（2026-08-01 堆叠序列化缺陷修复）：
        // 旧格式存档（stacksSerialized = false，如旧备份恢复）的堆叠未进入 SaveData，
        // 此时不删除 DB 残留的堆叠行——保留完好的既有堆叠，重建结果以 upsert 合并。
        if (data.stacksSerialized) {
            core.database.equipmentStackDao().deleteAll(slot)
            core.database.manualStackDao().deleteAll(slot)
        }
        core.database.equipmentInstanceDao().deleteAll(slot)
        core.database.manualInstanceDao().deleteAll(slot)
        core.database.pillDao().deleteAll(slot)
        core.database.materialDao().deleteAll(slot)
        core.database.herbDao().deleteAll(slot)
        core.database.seedDao().deleteAll(slot)
        core.database.storageBagDao().deleteAll(slot)
        core.database.explorationTeamDao().deleteAll(slot)
        core.database.battleLogDao().deleteAll(slot)
        core.database.recipeDao().deleteAll(slot)
        core.database.productionSlotDao().deleteBySlot(slot)
        core.database.discipleCompactDao().deleteAll(slot)
    }

    /** C-8：写入核心实体（轻型 GameData + 弟子/堆叠/实例/生产槽等）。 */
    private suspend fun writeCoreEntities(slot: Int, data: SaveData, lightGameData: GameData) {
        core.database.gameDataDao().insert(lightGameData)

        // bloodRefinementPctTotals 拍快照防止并发修改
        val bptSnapshot = data.gameData.bloodRefinementPctTotals
        data.disciples.chunked(MAX_BATCH_SIZE).forEach { batch ->
            val withSlot = batch.map { d -> d.copy(slotId = slot) }
            core.database.discipleDao().upsertAll(withSlot)
            core.database.discipleCoreDao().upsertAll(batch.map { d -> DiscipleCore.fromDisciple(d).copy(slotId = slot) })
            core.database.discipleCombatStatsDao().upsertAll(batch.map { d -> DiscipleCombatStats.fromDisciple(d).copy(slotId = slot) })
            core.database.discipleEquipmentDao().upsertAll(batch.map { d -> DiscipleEquipment.fromDisciple(d).copy(slotId = slot) })
            core.database.discipleExtendedDao().upsertAll(batch.map { d -> DiscipleExtended.fromDisciple(d).copy(slotId = slot) })
            core.database.discipleAttributesDao().upsertAll(batch.map { d -> DiscipleAttributes.fromDisciple(d).copy(slotId = slot) })
                core.database.discipleCompactDao().insertAll(batch.map { d ->
                    DiscipleCompact.fromDisciple(d, bptSnapshot).copy(slotId = slot)
                })
        }

        data.equipmentStacks.chunked(MAX_BATCH_SIZE).forEach { core.database.equipmentStackDao().upsertAll(it.map { e -> e.copy(slotId = slot) }) }
        data.equipmentInstances.chunked(MAX_BATCH_SIZE).forEach { core.database.equipmentInstanceDao().upsertAll(it.map { e -> e.copy(slotId = slot) }) }
        data.manualStacks.chunked(MAX_BATCH_SIZE).forEach { core.database.manualStackDao().upsertAll(it.map { m -> m.copy(slotId = slot) }) }
        data.manualInstances.chunked(MAX_BATCH_SIZE).forEach { core.database.manualInstanceDao().upsertAll(it.map { m -> m.copy(slotId = slot) }) }
        data.pills.chunked(MAX_BATCH_SIZE).forEach { core.database.pillDao().upsertAll(it.map { p -> p.copy(slotId = slot) }) }
        data.materials.chunked(MAX_BATCH_SIZE).forEach { core.database.materialDao().upsertAll(it.map { m -> m.copy(slotId = slot) }) }
        data.herbs.chunked(MAX_BATCH_SIZE).forEach { core.database.herbDao().upsertAll(it.map { h -> h.copy(slotId = slot) }) }
        data.seeds.chunked(MAX_BATCH_SIZE).forEach { core.database.seedDao().upsertAll(it.map { s -> s.copy(slotId = slot) }) }

        data.storageBags.chunked(MAX_BATCH_SIZE).forEach { core.database.storageBagDao().upsertAll(it.map { b -> b.copy(slotId = slot) }) }

        data.teams.chunked(MAX_BATCH_SIZE).forEach { core.database.explorationTeamDao().upsertAll(it.map { t -> t.copy(slotId = slot) }) }

        data.battleLogs.chunked(MAX_BATCH_SIZE).forEach { core.database.battleLogDao().upsertAll(it.map { b -> b.copy(slotId = slot) }) }

        val productionSlotsToSave = data.productionSlots
        if (productionSlotsToSave.isEmpty()) {
            Log.w(TAG, "writeAllDataToDatabase: productionSlotsToSave is EMPTY for slot $slot — " +
                "data.productionSlots.size=${data.productionSlots.size}")
        }
        productionSlotsToSave.chunked(MAX_BATCH_SIZE).forEach { batch ->
            core.database.productionSlotDao().upsertAll(batch.map { it.copy(slotId = slot) })
        }

        data.gameData.unlockedRecipes?.map { Recipe(it, slotId = slot) }?.let { recipes ->
            core.database.recipeDao().upsertAll(recipes)
        }

        syncSlotMetadata(slot, data)
    }

    /** C-8：写入领域实体表（外交/生产状态/巡逻/世界地图/政策——Phase B 细粒度读取路径）。 */
    private suspend fun writeDomainEntities(slot: Int, data: SaveData) {
        val gd = data.gameData
        core.database.diplomacyStateDao().upsert(DiplomacyState(
            slotId = slot,
            sectRelations = gd.sectRelations,
            alliances = gd.alliances,
            playerAllianceSlots = gd.playerAllianceSlots,
            playerProtectionEnabled = gd.playerProtectionEnabled,
            playerProtectionStartYear = gd.playerProtectionStartYear,
            playerHasAttackedAI = gd.playerHasAttackedAI,
            sectDetails = gd.sectDetails,
            exploredSects = gd.exploredSects,
            scoutInfo = gd.scoutInfo
        ))
        core.database.productionStateDao().upsert(ProductionState(
            slotId = slot,
            spiritFieldPlants = gd.spiritFieldPlants,
            unlockedRecipes = gd.unlockedRecipes ?: emptyList(),
            unlockedManuals = gd.unlockedManuals ?: emptyList(),
            manualProficiencies = gd.manualProficiencies
        ))
        core.database.patrolStateDao().upsert(PatrolStateEntity(
            slotId = slot,
            patrolSlots = gd.patrolSlots,
            patrolConfig = gd.patrolConfig,
            patrolConfigs = gd.patrolConfigs,
            patrolBattleResultPopup = gd.patrolBattleResultPopup
        ))
        core.database.worldMapStateDao().upsert(WorldMapStateEntity(
            slotId = slot,
            worldMapSects = gd.worldMapSects,
            aiSectDisciples = gd.aiSectDisciples,
            cultivatorCaves = gd.cultivatorCaves,
            caveExplorationTeams = gd.caveExplorationTeams,
            aiCaveTeams = gd.aiCaveTeams,
            worldLevels = gd.worldLevels
        ))
        core.database.sectPolicyStateDao().upsert(SectPolicyState(
            slotId = slot,
            sectPolicies = gd.sectPolicies,
            autoRecruitSpiritRootFilter = gd.autoRecruitSpiritRootFilter,
            daoCompanionBannedRootCounts = gd.daoCompanionBannedRootCounts,
            daoCompanionConsentRequired = gd.daoCompanionConsentRequired,
            breakthroughAutoPillFocused = gd.breakthroughAutoPillFocused,
            breakthroughAutoPillRootCounts = gd.breakthroughAutoPillRootCounts,
            autoEquipFromWarehouseFocused = gd.autoEquipFromWarehouseFocused,
            autoEquipFromWarehouseRootCounts = gd.autoEquipFromWarehouseRootCounts,
            autoLearnFromWarehouseFocused = gd.autoLearnFromWarehouseFocused,
            autoLearnFromWarehouseRootCounts = gd.autoLearnFromWarehouseRootCounts,
            yearlySalary = gd.yearlySalary,
            yearlySalaryEnabled = gd.yearlySalaryEnabled
        ))
    }

    /**
     * 当前可用内存（MB）。
     */
    private fun availableMemoryMB(): Long {
        val runtime = Runtime.getRuntime()
        val maxMem = runtime.maxMemory()
        val usedMem = runtime.totalMemory() - runtime.freeMemory()
        return (maxMem - usedMem) / 1024 / 1024
    }

    private suspend fun syncSlotMetadata(slot: Int, data: SaveData) {
        val gd = data.gameData
        val metadata = SaveSlotMetadata(
            slotId = slot,
            sectName = gd.sectName,
            gameYear = gd.gameYear,
            gameMonth = gd.gameMonth,
            gamePhase = gd.gamePhase,
            spiritStones = gd.spiritStones,
            spiritHerbs = gd.spiritHerbs,
            sectCultivation = gd.sectCultivation,
            lastSaveTime = data.timestamp,
            discipleCount = data.disciples.count { it.isAlive }
        )
        core.database.saveSlotMetadataDao().upsert(metadata)
    }

    private suspend fun logSaveChanges(slot: Int, data: SaveData) {
        try {
            infra.changeLogPersistence.logChange(
                tableName = "game_data",
                recordId = "game_data_$slot",
                operation = ChangeLogOperation.UPDATE
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to log save change for slot $slot", e)
        }
    }

    private suspend fun loadFromCache(slot: Int): SaveData? = withContext(Dispatchers.IO) {
        try {
            val gameDataKey = CacheKey.forGameData(slot)
            core.cache.getOrNull<SaveData>(gameDataKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load from cache for slot $slot", e)
            null
        }
    }

    /**
     * 从数据库加载完整存档数据。
     * 注意：调用方必须持有 [core.lockManager] 的读锁（[load] 已持有），
     *       否则在无事务包裹的并行读取中可能出现数据不一致。
     */
    private suspend fun loadFromDatabase(slot: Int): SaveData? {
        return try {
            loadFromDatabaseInternal(slot, loadHeavyData = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load from database for slot $slot", e)
            null
        }
    }

    private suspend fun loadFromDatabaseInternal(slot: Int, loadHeavyData: Boolean = false): SaveData? {
        val gameData = core.database.gameDataDao().getGameDataSync(slot) ?: return null

        if (loadHeavyData) {
            // mergeHeavyData 在事务中读取 heavy_data + domain state tables，
            // 确保这些表看到一致的数据库快照。
            // 注意：buildSaveDataFromDatabase 内部用 async {} 并行读表，不能放入 withTransaction
            //（Room withTransaction 要求内部 DAO 调用在同一线程，与 async 不兼容）。
            val merged = core.database.withTransaction { mergeHeavyData(gameData, slot) }
            val saveData = buildSaveDataFromDatabase(slot, merged)
            if (saveData != null) {
                val migrated = migrateSaveDataIfNeeded(saveData)
                if (!validateSaveData(migrated)) {
                    Log.w(TAG, "Save data validation failed for slot $slot after heavy data merge")
                }
                return migrated
            }
            return saveData
        }

        val saveData = buildSaveDataFromDatabase(slot, gameData)
        if (saveData != null) {
            val migrated = migrateSaveDataIfNeeded(saveData)
            if (!validateSaveData(migrated)) {
                Log.w(TAG, "Save data validation failed for slot $slot: gameYear=${gameData.gameYear}, gameMonth=${gameData.gameMonth}, sectName='${gameData.sectName}'")
            }
            return migrated
        }
        return saveData
    }

    /**
     * 顺序迁移旧版存档数据至当前版本。
     *
     * v0→1 (v4.0.13): 修炼基础值等比缩小为 1/10。
     * v1→2: 将所有 sectRelations 的 acquainted 设为 true。
     */
    private fun migrateSaveDataIfNeeded(saveData: SaveData): SaveData {
        val gd = saveData.gameData
        if (gd.saveVersion >= 2) return saveData

        var currentGd = gd
        var currentDisciples = saveData.disciples

        // ── Migration v0→1: cultivation scaling ──
        if (currentGd.saveVersion < 1) {
            val scaleFactor = 10.0
            Log.i(
                TAG,
                "Migrating save data v0→1: scaling cultivation by 1/$scaleFactor (slot ${gd.slotId})"
            )

            currentGd = currentGd.copy(
                sectCultivation = currentGd.sectCultivation / scaleFactor,
                saveVersion = 1,
                recruitList = currentGd.recruitList.map { d -> d.scaleCultivation(scaleFactor) },
                aiSectDisciples = currentGd.aiSectDisciples.mapValues { (_, list) ->
                    list.map { d -> d.scaleCultivation(scaleFactor) }
                }
            )
            currentDisciples = currentDisciples.map { it.scaleCultivation(scaleFactor) }

            Log.i(TAG, "Migration v0→1 complete: ${currentDisciples.size} disciples scaled")
        }

        // ── Migration v1→2: set all sectRelations to acquainted ──
        if (currentGd.saveVersion < 2) {
            Log.i(
                TAG,
                "Migrating save data v1→2: setting all sectRelations acquainted (slot ${gd.slotId})"
            )

            currentGd = currentGd.copy(
                saveVersion = 2,
                sectRelations = currentGd.sectRelations.map { it.copy(acquainted = true) }
            )

            Log.i(TAG, "Migration v1→2 complete: ${currentGd.sectRelations.size} relations updated")
        }

        return saveData.copy(
            gameData = currentGd,
            disciples = currentDisciples
        )
    }

    /**
     * 将弟子的修炼值和战力值同步缩放（向上取整，宁可多不可少）。
     */
    private fun Disciple.scaleCultivation(factor: Double): Disciple {
        return this.copy(
            cultivation = this.cultivation / factor,
            combat = this.combat.copy(
                totalCultivation = kotlin.math.ceil(
                    this.combat.totalCultivation / factor
                ).toLong()
            )
        )
    }

    /**
     * 存档数据完整性校验。
     * 检查关键字段是否在合法范围内，防止损坏数据导致游戏逻辑异常。
     */
    private fun validateSaveData(data: SaveData): Boolean {
        if (data.gameData.gameYear < 1) return false
        if (data.gameData.gameMonth < 1 || data.gameData.gameMonth > 12) return false
        if (data.gameData.sectName.isBlank()) return false
        return true
    }

    private suspend fun mergeHeavyData(gameData: GameData, slot: Int): GameData {
        val allRows = loadHeavyDataSafe(slot)

        // heavy_data 表无数据时，依次从 domain state 表 fallback 恢复所有重型字段。
        // 这 5 个 domain state 表与 heavy_data 在同一事务中写入（writeAllDataToDatabase
        // Phase B 第 740-791 行），若 heavy_data 因写入中断丢失，domain state 表仍有完整数据。
        // 防止写入中断后重型字段永久为空（世界地图空白/招募列表为空等）。
        if (allRows.isEmpty()) {
            val worldMapEntity = try {
                core.database.worldMapStateDao().getBySlot(slot)
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                  Log.w(TAG, "mergeHeavyData: worldMapStateDao fallback failed", e)
                  null
              }
            val diplomacyEntity = try {
                core.database.diplomacyStateDao().getBySlot(slot)
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                  Log.w(TAG, "mergeHeavyData: diplomacyStateDao fallback failed", e)
                  null
              }
            val productionEntity = try {
                core.database.productionStateDao().getBySlot(slot)
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                  Log.w(TAG, "mergeHeavyData: productionStateDao fallback failed", e)
                  null
              }

            val hasFallbackData = worldMapEntity?.worldMapSects?.isNotEmpty() == true
            if (hasFallbackData) {
                Log.w(TAG, "mergeHeavyData: heavy_data empty for slot $slot, " +
                    "falling back to domain state tables " +
                    "(worldSects=${worldMapEntity?.worldMapSects?.size}, " +
                    "sectDetails=${diplomacyEntity?.sectDetails?.size}, " +
                    "manualProficiencies=${productionEntity?.manualProficiencies?.size})")
                return gameData.copy(
                    worldMapSects = worldMapEntity?.worldMapSects ?: gameData.worldMapSects,
                    aiSectDisciples = worldMapEntity?.aiSectDisciples ?: gameData.aiSectDisciples,
                    sectDetails = diplomacyEntity?.sectDetails ?: gameData.sectDetails,
                    exploredSects = diplomacyEntity?.exploredSects ?: gameData.exploredSects,
                    scoutInfo = diplomacyEntity?.scoutInfo ?: gameData.scoutInfo,
                    manualProficiencies = productionEntity?.manualProficiencies ?: gameData.manualProficiencies
                    // recruitList 无 domain state 表可恢复，保持 gameData 原有值
                )
            }
            Log.w(TAG, "mergeHeavyData: all domain state tables empty for slot $slot")
            return gameData
        }

        return gameData.copy(
            aiSectDisciples = if (gameData.aiSectDisciples.isEmpty())
                ProtobufConverters.decodeDiscipleListMapFromRows(allRows, GameHeavyData.KEY_AI_SECT_DISCIPLES)
            else gameData.aiSectDisciples,

            sectDetails = if (gameData.sectDetails.isEmpty())
                ProtobufConverters.decodeSectDetailMapFromRows(allRows, GameHeavyData.KEY_SECT_DETAILS)
            else gameData.sectDetails,

            exploredSects = if (gameData.exploredSects.isEmpty())
                ProtobufConverters.decodeExploredSectInfoMapFromRows(allRows, GameHeavyData.KEY_EXPLORED_SECTS)
            else gameData.exploredSects,

            scoutInfo = if (gameData.scoutInfo.isEmpty())
                ProtobufConverters.decodeSectScoutInfoMapFromRows(allRows, GameHeavyData.KEY_SCOUT_INFO)
            else gameData.scoutInfo,

            manualProficiencies = if (gameData.manualProficiencies.isEmpty())
                ProtobufConverters.decodeManualProficiencyMapFromRows(allRows, GameHeavyData.KEY_MANUAL_PROFICIENCIES)
            else gameData.manualProficiencies,

            recruitList = if (gameData.recruitList.isEmpty())
                ProtobufConverters.decodeDiscipleListFromRows(allRows, GameHeavyData.KEY_RECRUIT_LIST)
            else gameData.recruitList,

            worldMapSects = if (gameData.worldMapSects.isEmpty())
                ProtobufConverters.decodeWorldSectListFromRows(allRows, GameHeavyData.KEY_WORLD_MAP_SECTS)
            else gameData.worldMapSects
        )
    }

    suspend fun loadHeavyDataForSlot(slot: Int): Map<String, ByteArray> {
        val heavyDataList = loadHeavyDataSafe(slot)
        return GameHeavyData.reassemble(heavyDataList)
    }

    /**
     * 安全加载重型数据：逐 key 读取，跳过超过 CursorWindow 限制的单行。
     * 跳过的数据会在下次保存时由游戏逻辑重新生成并分块存储。
     */
    private suspend fun loadHeavyDataSafe(slot: Int): List<GameHeavyData> {
        val keys = try {
            core.database.gameHeavyDataDao().getLoadedKeys(slot)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load heavy data keys for slot $slot, skipping", e)
            return emptyList()
        }

        val result = mutableListOf<GameHeavyData>()
        for (key in keys) {
            try {
                val row = core.database.gameHeavyDataDao().getByKey(slot, key)
                if (row != null) result.add(row)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Heavy data key '$key' exceeds CursorWindow limit, will be regenerated on next save", e)
                // 删除超大行，下次保存时会分块重写
                try { core.database.gameHeavyDataDao().deleteByKey(slot, key) } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to delete oversized heavy data key '$key'", e)
                }
            }
        }
        return result
    }

    private suspend fun buildSaveDataFromDatabase(slot: Int, gameData: GameData): SaveData = withContext(Dispatchers.IO) {
        val deferredDisciples = async { core.database.discipleDao().getAllSync(slot) }
        val deferredEquipmentStacks = async { core.database.equipmentStackDao().getAllSync(slot) }
        val deferredEquipmentInstances = async { core.database.equipmentInstanceDao().getAllSync(slot) }
        val deferredManualStacks = async { core.database.manualStackDao().getAllSync(slot) }
        val deferredManualInstances = async { core.database.manualInstanceDao().getAllSync(slot) }
        val deferredPills = async { core.database.pillDao().getAllSync(slot) }
        val deferredMaterials = async { core.database.materialDao().getAllSync(slot) }
        val deferredHerbs = async { core.database.herbDao().getAllSync(slot) }
        val deferredSeeds = async { core.database.seedDao().getAllSync(slot) }
        val deferredStorageBags = async { core.database.storageBagDao().getAll(slot) }
        val deferredTeams = async { core.database.explorationTeamDao().getAllSync(slot) }
        val deferredBattleLogs = async { core.database.battleLogDao().getAllSync(slot) }
        var deferredProductionSlots = async { core.database.productionSlotDao().getBySlotSync(slot) }

        val disciples = deferredDisciples.await()
        val equipmentStacks = deferredEquipmentStacks.await()
        val equipmentInstances = deferredEquipmentInstances.await()
        val manualStacks = deferredManualStacks.await()
        val manualInstances = deferredManualInstances.await()
        val pills = deferredPills.await()
        val materials = deferredMaterials.await()
        val herbs = deferredHerbs.await()
        val seeds = deferredSeeds.await()
        val storageBags = deferredStorageBags.await()
        val teams = deferredTeams.await()
        val battleLogs = deferredBattleLogs.await()
        var productionSlots = deferredProductionSlots.await()

        val alliances = gameData.alliances ?: emptyList()

        if (productionSlots.isEmpty()) {
            val fallbackSlots = gameData.productionSlots
            if (!fallbackSlots.isNullOrEmpty()) {
                Log.w(TAG, "Production slots empty in DB, using GameData fallback (${fallbackSlots.size} slots)")
                productionSlots = fallbackSlots
            }
        }

        val fixedDisciples = fixStorageBagReferences(
            equipmentStacks = equipmentStacks,
            equipmentInstances = equipmentInstances,
            manualStacks = manualStacks,
            manualInstances = manualInstances,
            disciples = disciples
        )

        SaveData(
            gameData = gameData,
            disciples = fixedDisciples,
            equipmentStacks = equipmentStacks,
            equipmentInstances = equipmentInstances,
            manualStacks = manualStacks,
            manualInstances = manualInstances,
            pills = pills,
            materials = materials,
            herbs = herbs,
            seeds = seeds,
            storageBags = storageBags,
            teams = teams,
            battleLogs = battleLogs,
            alliances = alliances,
            productionSlots = productionSlots,
            stacksSerialized = true
        ).also {
            Log.d(TAG, "loadFromDatabase: slot=$slot, ${disciples.size} disciples, recruitList=${gameData.recruitList.size} unrecruited disciples")
        }
    }

    private fun updateCacheAfterSave(slot: Int, data: SaveData) {
        try {
            val cacheKey = CacheKey.forGameData(slot)
            core.cache.putWithoutTracking(cacheKey, data)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update cache for slot $slot", e)
        }
    }

    private fun clearCacheForSlot(slot: Int) {
        try {
            val cacheKey = CacheKey.forGameData(slot)
            core.cache.remove(cacheKey)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear cache for slot $slot", e)
        }
    }

    private suspend fun querySingleSlot(slot: Int): SaveSlot {
        return try {
            val meta = core.database.gameDataDao().getMetadataBySlot(slot)
            if (meta != null) {
                SaveSlot(
                    slot = slot,
                    name = "Save $slot",
                    timestamp = meta.lastSaveTime,
                    gameYear = meta.gameYear,
                    gameMonth = meta.gameMonth,
                    sectName = meta.sectName,
                    discipleCount = core.database.discipleDao().getAliveCountSync(slot),
                    spiritStones = meta.spiritStones,
                    isEmpty = false,
                    customName = meta.sectName,
                )
            } else {
                SaveSlot(slot, "", 0, 1, 1, "", 0, 0, true)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "querySingleSlot FAILED for slot $slot -- database may be unreachable or schema is mismatched", e)
            throw RuntimeException("Failed to query save slot $slot: ${e.message}", e)
        }
    }

}
