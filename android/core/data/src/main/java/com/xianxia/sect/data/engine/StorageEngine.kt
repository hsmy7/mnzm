package com.xianxia.sect.data.engine

import android.util.Log
import com.xianxia.sect.core.model.spiritStones
import com.xianxia.sect.data.integrity.IntegrityResult
import com.xianxia.sect.data.integrity.SaveValidator
import com.xianxia.sect.data.archive.DataArchiver
import com.xianxia.sect.data.backup.SaveFileManager
import com.xianxia.sect.data.config.SaveLimitsConfig
import com.xianxia.sect.data.config.StorageConfig
import com.xianxia.sect.data.migration.SaveDataVersionMigrator
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.model.SaveSlot
import com.xianxia.sect.data.result.StorageError
import com.xianxia.sect.data.result.StorageResult
import com.xianxia.sect.data.serialization.unified.SaveDataReconciler
import com.xianxia.sect.data.serialization.unified.SerializationModule
import com.xianxia.sect.data.StorageConstants
import com.xianxia.sect.data.unified.SlotMetadata
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    val wasIncremental: Boolean = false,
    /**
     * 落盘**后置步骤**（`.sav` 文件镜像 / `.bak` 备份）的降级原因；null = 全部完成。
     *
     * 非 null 时**主保存仍成功**（Room 事务已提交，DB 是真相源），但文件镜像或备份
     * 缺失/被跳过 ⇒ 调用方（UI）**必须如实提示**，不得只报"游戏保存成功"（审计 §12-C）。
     */
    val postSaveWarning: String? = null
)

enum class SavePriority {
    NORMAL,
    HIGH,
    CRITICAL
}

@Singleton
class StorageEngine @Inject constructor(
    internal val core: StorageCoreFacade,
    internal val saveLimitsConfig: SaveLimitsConfig,
    internal val dataArchiver: DataArchiver,
    internal val infra: StorageInfraFacade,
    internal val maintenanceFacade: StorageMaintenanceFacade,
    internal val saveFileManager: SaveFileManager,
    internal val serializationModule: SerializationModule,
    internal val storageConfig: StorageConfig
) {
    companion object {
        internal const val TAG = "StorageEngine"
        internal const val MAX_BATCH_SIZE = 200

        /**
         * 低内存保存守卫阈值（MB）。低于此值拒绝保存并返回失败，
         * 避免"静默跳过但报成功"导致内存态与 DB 脱节。
         */
        internal const val LOW_MEMORY_THRESHOLD_MB = 100L

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
            size += data.alliances.size * es.ALLIANCE

            val serializationOverhead = (size * StorageConstants.ESTIMATE_SERIALIZATION_OVERHEAD_RATIO).toLong()
            size += serializationOverhead

            return size
        }
    }

    internal val scope get() = infra.scopeProvider.ioScope

    // progress 发布通道(StorageEngineSaveSupport/LoadOps 跨文件推进)
    @Suppress("VariableNaming")
    internal val _progress = MutableStateFlow(EngineProgress(EngineProgress.Stage.IDLE, 0f))
    val progress: StateFlow<EngineProgress> = _progress.asStateFlow()

    @Suppress("VariableNaming")
    internal val _currentSlot = MutableStateFlow(1)
    val currentSlot: StateFlow<Int> = _currentSlot.asStateFlow()

    // priority：保存优先级语义形参（调度器接管后保留 API 调用契约） // 异常显式包装进 Result 上抛, 非静默吞噬
    @Suppress("UnusedParameter", "TooGenericExceptionCaught")
    suspend fun save(slot: Int, data: SaveData,
        priority: SavePriority = SavePriority.NORMAL): StorageResult<SaveOperationStats> {
        if (!core.lockManager.isValidSlot(slot)) {
            return StorageResult.failure(StorageError.INVALID_SLOT, "Invalid slot: $slot")
        }

        return core.lockManager.withWriteLockLight(slot) {
            try {
                // 熔断器保护保存主链路：连续失败（5 次）时熔断 30s 防雪崩重试
                if (isSaveCircuitOpen(slot = slot)) {
                    return@withWriteLockLight StorageResult.failure(
                        StorageError.SAVE_FAILED, "保存熔断中（存储连续失败），请稍后重试"
                    )
                }
                val startTime = System.currentTimeMillis()

                // 保存前校验 + 清理 + 时间戳
                val dataWithTimestamp = validateAndPrepareData(slot, data)
                    ?: return@withWriteLockLight StorageResult.failure(
                        StorageError.SAVE_FAILED, "保存前校验拒绝：存档数据损坏"
                    )

                // 重试保存（OOM 短路）
                val result = saveWithRetry(slot, dataWithTimestamp)

                // 结果处理（备份/缓存/变更日志/失败恢复）——返回可能带 postSaveWarning 的结果
                // （审计 §12-C：文件镜像/备份降级必须传到 UI，不得谎报"保存成功"）
                val handled = handleSaveResult(slot, result, dataWithTimestamp)

                // 保存结果反馈熔断器（成功重置计数，失败累计）
                recordSaveCircuitResult(slot = slot, result = handled)

                handled.map { stats ->
                    val elapsed = System.currentTimeMillis() - startTime
                    stats.copy(timeMs = elapsed)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                Log.e(TAG, "Save failed for slot $slot", e)
                _progress.value = EngineProgress(EngineProgress.Stage.FAILED, 0f, e.message ?: "Unknown error")
                // 保持原"先判 OOM 后判 IO"分类语义：IOException 携带 OOM cause 时仍归 OOM
                val isOom = e.cause is OutOfMemoryError
                val error = if (isOom) StorageError.OUT_OF_MEMORY else StorageError.IO_ERROR
                StorageResult.failure(error, e.message ?: "Save failed", e)
            } catch (e: Exception) {
                Log.e(TAG, "Save failed for slot $slot", e)
                _progress.value = EngineProgress(EngineProgress.Stage.FAILED, 0f, e.message ?: "Unknown error")
                // OutOfMemoryError 是 Error 非 Exception，不会被上方 catch (Exception) 接住；
                // SerializationFailureException 的 cause 可能是 OOM——识别异常链使 save()
                // 重试循环对 OOM 正确短路（OOM 重试无意义，只会拉长 ANR 窗口）
                val isOom = e.cause is OutOfMemoryError
                val error = if (isOom) StorageError.OUT_OF_MEMORY else StorageError.SAVE_FAILED
                StorageResult.failure(error, e.message ?: "Save failed", e)
            }
        }
    }





    /**
     * 保存前数据准备——完整性校验（损坏拒绝/修复替换）+ 清理 + 时间戳。
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
                    // 使用修复后的数据替换原始数据，确保修复持久化
                    Log.w(TAG, "保存前校验修复 ${integrityResult.details.size} 项，使用修复后数据 slot=$slot")
                    effectiveData = integrityResult.data
                }
                is IntegrityResult.Passed -> { /* 无操作 */ }
            }
        }

        _progress.value = EngineProgress(EngineProgress.Stage.SAVING_CORE, 0.1f, "Saving core data")

        val cleanedData = cleanSaveDataWithArchive(effectiveData)
        // 保存前统一盖章当前存档版本（第二层防御）——引擎创建新档已盖章，
        // 此处兜底一切遗漏路径（重启/迁移残留/外部构造），保证写库的存档
        // 恒为当前数据版本，读档不会触发旧版本迁移
        val stamped = if (cleanedData.gameData.saveVersion < SaveDataVersionMigrator.CURRENT_SAVE_VERSION) {
            cleanedData.copy(
                gameData = cleanedData.gameData.copy(
                    saveVersion = SaveDataVersionMigrator.CURRENT_SAVE_VERSION
                )
            )
        } else {
            cleanedData
        }
        return stamped.copy(timestamp = System.currentTimeMillis())
    }

    /** 全量事务保存 + 重试（内存守卫已前置；OOM 类失败直接终止重试）。 */
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



    @Suppress("TooGenericExceptionCaught") // 异常显式包装进 Result 上抛, 非静默吞噬
    suspend fun load(slot: Int): StorageResult<SaveData> {
        if (!core.lockManager.isValidSlot(slot)) {
            return StorageResult.failure(StorageError.INVALID_SLOT, "Invalid slot: $slot")
        }

        return core.lockManager.withReadLockLight(slot) {
            try {
                // 读取入口熔断保护（连续 8 次失败熔断 15s）
                if (!infra.circuitBreaker.allowRequest("load")) {
                    Log.w(TAG, "读档熔断中（存储连续失败），拒绝本次读取 slot=$slot")
                    return@withReadLockLight StorageResult.failure(
                        StorageError.LOAD_FAILED, "读档熔断中（存储连续失败），请稍后重试"
                    )
                }
                // 缓存命中优先
                tryCacheLoad(slot)?.let {
                    infra.circuitBreaker.recordSuccess("load")
                    return@withReadLockLight StorageResult.success(it)
                }

                infra.storageMetrics.recordCacheMiss()
                _progress.value = EngineProgress(EngineProgress.Stage.SAVING_CORE, 0.2f, "Loading from database")
                val dbData = loadFromDatabase(slot)

                if (dbData != null) {
                    return@withReadLockLight handleDbDataHit(slot = slot, dbData = dbData)
                }

                // ── 数据库无数据时尝试从备份文件恢复 ──
                val restored = restoreFromBackup(slot)
                if (restored != null) {
                    infra.circuitBreaker.recordSuccess("load")
                    return@withReadLockLight restored
                }
                _progress.value = EngineProgress(EngineProgress.Stage.FAILED, 0f, "No data found")
                StorageResult.failure(StorageError.SLOT_EMPTY, "No data in slot $slot")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Load failed for slot $slot", e)
                infra.circuitBreaker.recordFailure("load")
                _progress.value = EngineProgress(EngineProgress.Stage.FAILED, 0f, e.message ?: "Unknown error")
                StorageResult.failure(StorageError.LOAD_FAILED, e.message ?: "Load failed", e)
            } catch (e: OutOfMemoryError) {
                // OOM 是 Error 非 Exception，不会被上方 catch (Exception) 接住。
                // 不尝试 restoreFromBackup：备份与主档同源同尺寸，恢复必然再 OOM
                Log.e(TAG, "Load OOM for slot $slot（跳过备份恢复——备份同尺寸必再 OOM）", e)
                infra.circuitBreaker.recordFailure("load")
                _progress.value = EngineProgress(EngineProgress.Stage.FAILED, 0f, "内存不足，读档失败")
                StorageResult.failure(StorageError.LOAD_FAILED, "内存不足，读档失败", e)
            }
        }
    }







    /**
     * 从备份文件恢复（损坏恢复/无数据恢复两场景共用）。
     *
     * 流程：读备份 → 反序列化 → 二次验证 → 堆叠重建 → 写库（检查结果）→ 缓存。
     *
     * @return 恢复成功的数据；备份不可用时返回 null（调用方决定失败语义）
     */
    // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    @Suppress("TooGenericExceptionCaught", "ReturnCount") // 备份恢复多失败路径，多 return 为守卫风格
    internal suspend fun restoreFromBackup(
        slot: Int
    ): StorageResult<SaveData>? {
        _progress.value = EngineProgress(EngineProgress.Stage.VALIDATING, 0.5f, "尝试从备份恢复...")
        try {
            // 删除 tombstone 守卫——删除流程中途崩溃时（DB 已删/未删 + 文件残留），
            // 不复活已删存档；顺带清理残留数据
            if (saveFileManager.isSlotDeleted(slot)) {
                Log.w(TAG, "槽位 $slot 存在删除 tombstone，不执行备份恢复（已删存档）")
                clearSlotDataQuietly(slot)
                return null
            }
            // readWithFallback 必须在 try 内：SaveFileManager 未初始化时抛
            // IllegalStateException，未初始化应降级为"无数据"而非上抛成 LOAD_FAILED
            val readResult = saveFileManager.readWithFallback(slot)
            if (readResult.status != com.xianxia.sect.data.backup.BackupStatus.SUCCESS &&
                readResult.status != com.xianxia.sect.data.backup.BackupStatus.RECOVERED
            ) {
                Log.w(TAG, "备份文件不存在或损坏 slot=$slot")
                return null
            }
            var restoredData = serializationModule.deserializeSaveData(
                readResult.payload ?: return StorageResult.failure(
                    StorageError.SLOT_CORRUPTED, "备份恢复失败：payload 为空 (slot=$slot)"
                )
            )
            Log.w(TAG, "备份恢复成功 (slot=$slot) 来源=${readResult.source}")
            // .sav 修复失败如实记录（数据有效，继续恢复流程）
            if (readResult.repairFailed) {
                Log.e(TAG, "slot=$slot 的 .sav 修复失败（copyTo 失败），将持续回退 .bak 直至下次成功保存")
            }

            // 备份恢复路径与主档加载路径对齐，恢复数据同样过版本迁移——
            // 旧版 .sav（saveVersion 0/1）未经迁移会以旧语义运行；
            // Rejected（版本号非法）→ 恢复失败
            restoredData = migrateRestoredData(restoredData, slot)
                ?: return StorageResult.failure(
                    StorageError.SLOT_CORRUPTED,
                    "备份恢复版本迁移拒绝 (slot=$slot)"
                )

            // 备份恢复后二次验证：防止备份本身存在数据问题
            restoredData = revalidateRestoredData(slot, restoredData)
                ?: return StorageResult.failure(
                    StorageError.SLOT_CORRUPTED,
                    "备份恢复数据二次验证无法修复 (slot=$slot)"
                )

            infra.storageMetrics.recordBackupRestore()
            // 旧格式备份无堆叠数据：从实例重建兜底
            restoredData = SaveDataReconciler.reconcileStacks(restoredData)
            // 备份路径物化兜底（老备份引用式袋条目 → 持有数据，防复制）
            restoredData = restoredData.materializeRestoredBag()
            // 恢复前隔离当前数据库——.sav 整体覆写 DB 不可逆，校验器误判损坏时
            // 较新的 DB 数据会被旧备份覆盖且无保留；隔离快照供排查/手动恢复，
            // 维护任务按保留期清理
            quarantineCurrentDatabase()
            // 写库结果必须检查——低内存/编码失败导致的写库失败必须如实返回失败：
            // 否则 load 报成功、缓存与内存持有恢复数据，但 DB 仍是损坏数据，
            // 重启后再损坏、恢复循环丢进度
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
        } catch (e: CancellationException) {
            throw e // 取消穿透: 读档取消时中止备份恢复, 不误判"无备份可用"
        } catch (e: Exception) {
            Log.e(TAG, "备份读取/反序列化失败 slot=$slot", e)
            return null
        }
    }



    @Suppress("TooGenericExceptionCaught") // 异常显式包装进 Result 上抛, 非静默吞噬
    suspend fun delete(slot: Int): StorageResult<Unit> {
        if (!core.lockManager.isValidSlot(slot)) {
            return StorageResult.failure(StorageError.INVALID_SLOT, "Invalid slot: $slot")
        }

        Log.i(TAG, "Deleting slot $slot")

        return core.lockManager.withWriteLockLight(slot) {
            try {
                clearCacheForSlot(slot)

                // 先写删除 tombstone——DB 事务与文件删除之间崩溃时，
                // load 见 tombstone 即返回空档，不会从残留 .sav 复活已删存档
                saveFileManager.markSlotDeleted(slot)

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
                // 删除流程完整完成后清除 tombstone（下一次 load 正常返回空档）
                saveFileManager.clearSlotDeleted(slot)

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

    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
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

    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
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

    @Suppress("TooGenericExceptionCaught") // 异常显式包装进 Result 上抛, 非静默吞噬
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

    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    suspend fun getSaveSlots(): List<SaveSlot> {
        val slots = mutableListOf<SaveSlot>()

        // slot 0 = 云存档入口
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
                Log.e(TAG, "Failed to query slot $slot, marking as load error (not empty)", e)
                // 查询异常必须与"空档"区分（isLoadError 态）：损坏存档若伪装成空档，
                // 用户会在读取界面点击创建新游戏而静默覆盖损坏数据
                slots.add(
                    SaveSlot(
                        slot = slot,
                        name = "",
                        timestamp = 0L,
                        gameYear = 1,
                        gameMonth = 1,
                        sectName = "",
                        discipleCount = 0,
                        spiritStones = 0L,
                        isEmpty = false,
                        isLoadError = true
                    )
                )
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
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    suspend fun forceDeleteSlotData(slot: Int) {
        try {
            core.database.gameDataDao().deleteAll(slot)
            Log.i(TAG, "forceDeleteSlotData: deleted data for slot $slot")
        } catch (e: CancellationException) { throw e }
          catch (e: Exception) {
            Log.w(TAG, "forceDeleteSlotData: failed for slot $slot", e)
        }
    }

    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
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



    // 前者: 异常源跨IO/SDK不可枚举; 后者: 多步骤事务/异常翻译边界：各 throw 对应不同失败路径的领域错误，刻意独立抛出保归因清晰，非疏忽计数超标 // 防御兜底: 异常源跨IO/SDK不可枚举,
    // 降级继续+日志留痕, 非静默吞噬
    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    private suspend fun performFullTransactionSave(slot: Int, data: SaveData): StorageResult<SaveOperationStats> {
        // ── 内存守卫前置：低内存直接失败，不写 DB / 不写 WAL / 不写备份，避免内存态与 DB 脱节 ──
        if (availableMemoryMB() < LOW_MEMORY_THRESHOLD_MB) {
            Log.w(TAG, "Low memory (${availableMemoryMB()}MB available), save rejected for slot $slot")
            return StorageResult.failure(StorageError.OUT_OF_MEMORY, "内存不足（${availableMemoryMB()}MB），保存被拒绝")
        }

        // ── WAL 事务开始 ──
        var txnId: Long? = null
        try {
            val result = core.wal.beginTransaction(slot, com.xianxia.sect.data.wal.WALEntryType.DATA)
            if (result.isSuccess) txnId = result.getOrNull()
        } catch (e: CancellationException) {
            throw e // 取消穿透: 取消时不再进入后续 DB 事务, WAL 无事务需回滚
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
                abortWalQuietly(txnId)
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
                } catch (e: CancellationException) {
                    throw e // 取消穿透: WAL 是尽力日志非真源(DB 事务才是), 取消交外层 CE 分支回滚
                } catch (e: Exception) {
                    Log.w(TAG, "WAL commit 失败（非阻断）", e)
                }
            }

            val bytesWritten = estimateSaveSize(data)
            return StorageResult.success(SaveOperationStats(bytesWritten = bytesWritten, timeMs = 0,
                wasIncremental = false))
        } catch (e: CancellationException) {
            // WAL 回滚后传递取消信号
            abortWalSyncQuietly(txnId)
            throw e
        } catch (e: Exception) {
            abortWalQuietly(txnId)
            throw e
        }
    }














































    /**
     * 存档数据完整性校验。
     * 检查关键字段是否在合法范围内，防止损坏数据导致游戏逻辑异常。
     */
    internal fun validateSaveData(data: SaveData): Boolean {
        if (data.gameData.gameYear < 1) return false
        if (data.gameData.gameMonth < 1 || data.gameData.gameMonth > 12) return false
        if (data.gameData.sectName.isBlank()) return false
        return true
    }















    /** 数据库并行读取结果 */

















}
