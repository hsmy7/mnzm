package com.xianxia.sect.data.engine
import android.util.Log
import com.xianxia.sect.core.model.spiritStones
import com.xianxia.sect.data.cache.CacheKey
import com.xianxia.sect.data.incremental.ChangeLogOperation
import com.xianxia.sect.data.local.SaveSlotMetadata
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.result.StorageResult
import kotlinx.coroutines.CancellationException
import androidx.room.withTransaction
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

// StorageEngine 的保存支撑域:WAL 非阻断回滚/槽位元数据同步/保存变更日志/
// 缓存维护/熔断判定与保存结果处置。

private val TAG = StorageEngine.TAG

private const val MAX_BATCH_SIZE = StorageEngine.MAX_BATCH_SIZE

private const val LOW_MEMORY_THRESHOLD_MB = StorageEngine.LOW_MEMORY_THRESHOLD_MB

/** WAL 非阻断回滚：失败仅记录，不掩盖主路径异常 */
@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
internal suspend fun StorageEngine.abortWalQuietly(txnId: Long?) {
    if (txnId != null) {
        // NonCancellable 原子段: 回滚必须完成才能保证 WAL 事务一致性(失败回滚语义依赖),
        // 段内为单次 WAL 记录移除, 无无限等待; 段外取消照常传播
        withContext(NonCancellable) {
            try {
                core.wal.abort(txnId)
            } catch (e2: Exception) {
                Log.w(TAG, "WAL abort 失败", e2)
            }
        }
    }
}

/** WAL 同步非阻断回滚：取消信号传递路径使用 */
@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
internal fun StorageEngine.abortWalSyncQuietly(txnId: Long?) {
    if (txnId != null) {
        try {
            core.wal.abortSync(txnId)
        } catch (e2: Exception) {
            Log.w(TAG, "WAL abortSync 失败", e2)
        }
    }
}

internal suspend fun StorageEngine.syncSlotMetadata(slot: Int, data: SaveData) {
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

@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
internal suspend fun StorageEngine.logSaveChanges(slot: Int) {
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

@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
internal fun StorageEngine.updateCacheAfterSave(slot: Int, data: SaveData) {
    try {
        val cacheKey = CacheKey.forGameData(slot)
        core.cache.putWithoutTracking(cacheKey, data)
    } catch (e: Exception) {
        Log.w(TAG, "Failed to update cache for slot $slot", e)
    }
}

internal suspend fun StorageEngine.cleanSaveDataWithArchive(data: SaveData): SaveData {
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

/**
 * 保存熔断检查——熔断中返回 true（拒绝保存），否则 false。
 */
internal suspend fun StorageEngine.isSaveCircuitOpen(slot: Int): Boolean {
    if (infra.circuitBreaker.allowRequest("save")) return false
    Log.w(TAG, "保存熔断中（存储连续失败），拒绝本次保存 slot=$slot")
    return true
}

/**
 * 保存结果反馈熔断器——成功重置计数并清除删除 tombstone，
 * 失败累计失败计数。
 */
internal suspend fun StorageEngine.recordSaveCircuitResult(slot: Int, result: StorageResult<SaveOperationStats>) {
    if (result.isSuccess) {
        infra.circuitBreaker.recordSuccess("save")
        // 保存成功后清除删除 tombstone——删除中途崩溃残留的 tombstone 若不清除，
        // 会永久背负在新档上：日后 DB 损坏时 restoreFromBackup 见 tombstone
        // 拒绝恢复，clearSlotDataQuietly 还会删掉新档的唯一 .sav/.bak 恢复源
        saveFileManager.clearSlotDeleted(slot)
    } else {
        infra.circuitBreaker.recordFailure("save")
    }
}

/**
 * 保存结果处理——成功（备份/缓存/变更日志）或失败（备份恢复尝试）。
 * 备份仅在 DB 事务成功后写入，避免"备份比真相新"。
 */
// 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
@Suppress("TooGenericExceptionCaught", "NestedBlockDepth") // 备份异常处理守卫结构（try/catch 嵌套为既有模式）
internal suspend fun StorageEngine.handleSaveResult(
    slot: Int,
    result: StorageResult<SaveOperationStats>,
    dataWithTimestamp: SaveData
) {
    if (result.isSuccess) {
        if (storageConfig.autoBackupOnSave) {
            _progress.value = EngineProgress(EngineProgress.Stage.VALIDATING, 0.15f, "Writing backup")
            try {
                val br = saveFileManager.atomicWrite(slot, dataWithTimestamp)
                when (br) {
                    is StorageResult.Success -> infra.storageMetrics.recordBackupSuccess()
                    is StorageResult.Skipped -> {
                        // 备份超限跳过——主保存已成功，如实记录跳过不谎报成功
                        Log.w(TAG, "备份被跳过 slot=$slot: ${br.message}（主保存成功，非阻断）")
                        infra.storageMetrics.recordBackupSkippedOversize()
                    }
                    is StorageResult.Failure -> infra.storageMetrics.recordBackupFailure()
                }
            } catch (e: CancellationException) {
                throw e // 取消穿透: 取消时中止备份链路, 保存流程由外层 CE 分支收口
            } catch (e: Exception) {
                Log.w(TAG, "备份异常 slot=$slot (非阻断)", e)
                infra.storageMetrics.recordBackupFailure()
            }
        }
        _progress.value = EngineProgress(EngineProgress.Stage.UPDATING_CACHE, 0.8f, "Updating cache")
        updateCacheAfterSave(slot, dataWithTimestamp)
        _progress.value = EngineProgress(EngineProgress.Stage.SAVING_HISTORY, 0.85f, "Logging changes")
        logSaveChanges(slot)
        infra.storageMetrics.recordSave()
        _progress.value = EngineProgress(EngineProgress.Stage.COMPLETED, 1.0f, "Save completed")
    } else {
        Log.e(TAG, "保存失败（${storageConfig.maxRetryCount}次重试），尝试恢复 slot=$slot")
        try {
            val rr = saveFileManager.readWithFallback(slot)
            if (rr.status == com.xianxia.sect.data.backup.BackupStatus.SUCCESS ||
                rr.status == com.xianxia.sect.data.backup.BackupStatus.RECOVERED) {
                Log.w(TAG, "从备份恢复数据成功 slot=$slot")
                // .sav 修复失败——.sav 保持损坏持续回退，数据可用（payload 有效），
                // 下次成功保存自愈，此处如实记录
                if (rr.repairFailed) {
                    Log.e(TAG, "slot=$slot 的 .sav 修复失败（copyTo 失败），将持续回退 .bak 直至下次成功保存")
                }
            }
        } catch (e: CancellationException) {
            throw e // 取消穿透: 取消时中止备份恢复读, 重试由下次保存流程承担
        } catch (e2: Exception) {
            Log.e(TAG, "备份恢复也失败 slot=$slot", e2)
        }
    }
}

@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
internal fun StorageEngine.clearCacheForSlot(slot: Int) {
    try {
        val cacheKey = CacheKey.forGameData(slot)
        core.cache.remove(cacheKey)
    } catch (e: Exception) {
        Log.w(TAG, "Failed to clear cache for slot $slot", e)
    }
}

/**
 * A5：静默清理槽位残留数据（tombstone 命中时调用）——删除中途崩溃可能
 * 留下部分 DB 行与 .sav/.bak 文件，使其不干扰后续正常创建新档。
 */
@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
internal suspend fun StorageEngine.clearSlotDataQuietly(slot: Int) {
    try {
        core.database.withTransaction {
            core.database.gameDataDao().deleteAll(slot)
            core.database.discipleDao().deleteAll(slot)
        }
        saveFileManager.deleteSlot(slot)
        saveFileManager.clearSlotDeleted(slot)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "tombstone 清理残留数据失败 slot=$slot（非阻断）", e)
    }
}
