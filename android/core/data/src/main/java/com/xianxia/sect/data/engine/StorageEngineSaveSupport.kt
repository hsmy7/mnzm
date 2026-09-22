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
@Suppress("TooGenericExceptionCaught", "NestedBlockDepth") // 成功/失败双分支 + 备份恢复读的 try/catch 守卫结构（既有模式）
internal suspend fun StorageEngine.handleSaveResult(
    slot: Int,
    result: StorageResult<SaveOperationStats>,
    dataWithTimestamp: SaveData
): StorageResult<SaveOperationStats> {
    if (result.isSuccess) {
        // 文件镜像（.sav）+ 备份（.bak）——**非阻断**，但降级原因必须带回给调用方
        // （审计 §12-C：备份失败不改写 result ⇒ UI 谎报"保存成功"）
        val postSaveWarning = writeFileMirrorAndBackup(slot, dataWithTimestamp)
        _progress.value = EngineProgress(EngineProgress.Stage.UPDATING_CACHE, 0.8f, "Updating cache")
        updateCacheAfterSave(slot, dataWithTimestamp)
        _progress.value = EngineProgress(EngineProgress.Stage.SAVING_HISTORY, 0.85f, "Logging changes")
        logSaveChanges(slot)
        infra.storageMetrics.recordSave()
        _progress.value = EngineProgress(EngineProgress.Stage.COMPLETED, 1.0f, "Save completed")
        return if (postSaveWarning == null) {
            result
        } else {
            result.map { it.copy(postSaveWarning = postSaveWarning) }
        }
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
        return result
    }
}

/**
 * 写入 `.sav` 文件镜像 + `.bak` 备份（**非阻断**后置步骤）。
 *
 * @return 降级原因（null = 全部完成）：超限跳过 / 写失败 / 异常——供调用方
 *   带进 [StorageOperationStats.postSaveWarning]，让 UI 如实提示而非谎报"保存成功"。
 */
// 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
@Suppress("TooGenericExceptionCaught", "ReturnCount") // 三种降级各自 early-return 原因串，为守卫风格
private suspend fun StorageEngine.writeFileMirrorAndBackup(slot: Int, data: SaveData): String? {
    if (!storageConfig.autoBackupOnSave) return null
    _progress.value = EngineProgress(EngineProgress.Stage.VALIDATING, 0.15f, "Writing backup")
    return try {
        val br = saveFileManager.atomicWrite(slot, data)
        when (br) {
            is StorageResult.Success -> infra.storageMetrics.recordBackupSuccess()
            is StorageResult.Skipped -> {
                // 备份超限跳过——主保存已成功，如实记录跳过并带回告警
                Log.w(TAG, "备份被跳过 slot=$slot: ${br.message}（主保存成功，非阻断）")
                infra.storageMetrics.recordBackupSkippedOversize()
            }
            is StorageResult.Failure -> {
                Log.w(TAG, "文件镜像/备份写入失败 slot=$slot: ${br.message}（主保存成功，非阻断）")
                infra.storageMetrics.recordBackupFailure()
            }
        }
        backupWriteDegradationReason(br)
    } catch (e: CancellationException) {
        throw e // 取消穿透: 取消时中止备份链路, 保存流程由外层 CE 分支收口
    } catch (e: Exception) {
        Log.w(TAG, "文件镜像/备份异常 slot=$slot (非阻断)", e)
        infra.storageMetrics.recordBackupFailure()
        "文件镜像/备份写入异常: ${e.message ?: e::class.simpleName}"
    }
}

/**
 * `SaveFileManager.atomicWrite` 结果 → 后置步骤降级原因（null = 无降级）。
 *
 * **纯函数**（零副作用、零依赖）⇒ 桌面/JVM 可直测；指标记录留在调用方。
 * 空 message 也必须有可读原因（否则 UI 会弹出"游戏保存成功（）"这种空括注）。
 */
internal fun backupWriteDegradationReason(result: StorageResult<Unit>): String? = when (result) {
    is StorageResult.Success -> null
    is StorageResult.Skipped -> result.message.ifBlank { "备份被跳过（主保存成功）" }
    is StorageResult.Failure -> result.message.ifBlank { "文件镜像/备份写入失败（主保存成功）" }
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
        // 审计 §12-K：旧实现只删 game_data + disciples **两张表** ⇒ tombstone 路径
        // 残留 27 表行（合规与正确性双重问题）。现与 delete() 共用同一份全表清理。
        clearAllSlotTables(slot)
        saveFileManager.deleteSlot(slot)
        saveFileManager.clearSlotDeleted(slot)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "tombstone 清理残留数据失败 slot=$slot（非阻断）", e)
    }
}

/**
 * 清空槽位在 DB 的**全部表行**（单事务）。
 *
 * **唯一实现**：删除槽位（`StorageEngine.delete`）与 tombstone 残留清理
 * （[clearSlotDataQuietly]）共用，避免两处清单漂移——审计 §12-K 的根因正是
 * "删档路径清 29 表、tombstone 路径只清 2 表"。
 *
 * 新增 Room 实体（`@Database(entities=…)`）时**必须**在此补一行 DAO 删除，
 * 否则删档/tombstone 会留下新表残行（`GameDatabase` 注册实体数 = 本清单唯一权威对照）。
 */
@Suppress("LongMethod") // 27 个 DAO 逐行清理清单：按实体顺序平铺，拆函数反而遮蔽"清单完整性"
internal suspend fun StorageEngine.clearAllSlotTables(slot: Int) {
    core.database.withTransaction {
        core.database.gameDataDao().deleteAll(slot)
        core.database.discipleDao().deleteAll(slot)
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
        // 审计 §12-K 补齐：归档表与邮件草稿表同样带 slot 列，删档必须一并清
        //（旧实现全链漏删这 4 张表 ⇒ 账号注销/删档后仍残留玩家数据）
        core.database.archivedBattleLogDao().deleteBySlot(slot)
        core.database.archivedDiscipleDao().deleteBySlot(slot)
        core.database.mailDraftDao().deleteAllOverflowDraftsForSlot(slot)
        core.database.mailDraftDao().deleteAllDirectMailDraftsForSlot(slot)
    }
}
