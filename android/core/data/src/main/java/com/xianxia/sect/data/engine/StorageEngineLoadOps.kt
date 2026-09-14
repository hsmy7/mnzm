package com.xianxia.sect.data.engine
import android.util.Log
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.util.BagMaterializeInput
import com.xianxia.sect.core.util.StorageBagMaterializer
import com.xianxia.sect.data.integrity.IntegrityResult
import com.xianxia.sect.data.integrity.SaveValidator
import com.xianxia.sect.data.integrity.SaveValidatorFixes
import com.xianxia.sect.data.integrity.corrupted.CorruptedResultHandler
import com.xianxia.sect.data.cache.CacheKey
import com.xianxia.sect.data.migration.MigrationResult
import com.xianxia.sect.data.migration.SaveDataVersionMigrator
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.result.StorageError
import com.xianxia.sect.data.result.StorageResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import androidx.room.withTransaction

// StorageEngine 的加载管线域:缓存/数据库两级读取、迁移、校验与日志。

private val TAG = StorageEngine.TAG

private const val MAX_BATCH_SIZE = StorageEngine.MAX_BATCH_SIZE

private const val LOW_MEMORY_THRESHOLD_MB = StorageEngine.LOW_MEMORY_THRESHOLD_MB

/**
 * DB 命中路径：删除 tombstone 守卫 + 读档指标 + 缓存清理 + 完整性校验。
 */
internal suspend fun StorageEngine.handleDbDataHit(slot: Int, dbData: SaveData): StorageResult<SaveData> {
    // DB 命中路径同样查删除 tombstone——delete() 在"tombstone 已写、DB 事务未提交"
    // 窗口崩溃时 DB 数据完整，直接走 DB 路径会复活已删存档，
    // 与文件残留窗口的"已删"语义不一致
    if (saveFileManager.isSlotDeleted(slot)) {
        Log.w(TAG, "槽位 $slot 存在删除 tombstone 但 DB 有数据（删除中断），清理为已删")
        clearSlotDataQuietly(slot)
        return StorageResult.failure(
            StorageError.SLOT_EMPTY, "该槽位存档已删除"
        )
    }
    infra.storageMetrics.recordLoad()
    clearCacheForSlot(slot)
    // 完整性校验 + 损坏备份恢复
    val validated = validateDbData(slot, dbData)
    if (validated.isSuccess) infra.circuitBreaker.recordSuccess("load")
    return validated
}

/** 缓存命中尝试（命中时记录指标与进度，返回数据；未命中返回 null）。 */
@Suppress("ReturnCount") // 管线多级校验（迁移/基础校验/规则校验）早退，守卫风格
internal suspend fun StorageEngine.tryCacheLoad(slot: Int): SaveData? {
    _progress.value = EngineProgress(EngineProgress.Stage.SAVING_CORE, 0.1f, "Loading from cache")
    val cachedData = loadFromCache(slot) ?: return null
    // 缓存命中同样过迁移+校验管线（缓存内容来自保存路径，多数已处理，
    // 但防保存路径写入未盖章数据的窗口）；Rejected/Corrupted 视为未命中回落 DB
    val migrated = migrateOrNull(cachedData, slot) ?: return null
    if (!validateSaveData(migrated)) return null
    val integrity = SaveValidator.validate(migrated)
    if (integrity is IntegrityResult.Corrupted) return null
    val data = if (integrity is IntegrityResult.Repaired) integrity.data else migrated

    infra.storageMetrics.recordCacheHit()
    infra.storageMetrics.recordLoad()
    Log.d(TAG, "Cache hit for slot $slot")
    // 缓存路径物化兜底——旧版本写入的缓存可能含引用式袋条目（payload 空），
    // 取回（没收）等路径会复制/丢失物品；物化幂等（payload 非空跳过，扣减仅首次）
    val materialized = StorageBagMaterializer.materializeDiscipleBagItems(
        BagMaterializeInput(
            disciples = data.disciples,
            equipmentStacks = data.equipmentStacks,
            equipmentInstances = data.equipmentInstances,
            manualStacks = data.manualStacks,
            manualInstances = data.manualInstances,
            pills = data.pills,
            materials = data.materials,
            herbs = data.herbs,
            seeds = data.seeds
        )
    )
    if (materialized.materializedCount > 0) {
        Log.i(TAG, "缓存储物袋物化迁移 ${materialized.materializedCount} 条（D-03 独立存储）")
    }
    if (materialized.droppedCount > 0) {
        Log.w(TAG, "缓存储物袋悬空条目清理 ${materialized.droppedCount} 条（引用不存在，防复制删除）")
    }
    val materializedData = data.copy(
        disciples = materialized.disciples,
        equipmentStacks = materialized.equipmentStacks,
        equipmentInstances = materialized.equipmentInstances,
        manualStacks = materialized.manualStacks,
        manualInstances = materialized.manualInstances,
        pills = materialized.pills,
        materials = materialized.materials,
        herbs = materialized.herbs,
        seeds = materialized.seeds
    )
    _progress.value = EngineProgress(EngineProgress.Stage.COMPLETED, 1.0f, "Load completed (cache)")
    return materializedData
}

/**
 * 数据库数据完整性校验（通过/修复/损坏→备份恢复）。
 *
 * 修复后数据仅缓存（读锁内无法升级写锁持久化，下次保存时自动持久化）。
 */
@Suppress("ReturnCount")  // 校验结果分派（通过/修复/损坏→恢复），多 return 为守卫风格
internal suspend fun StorageEngine.validateDbData(slot: Int, dbData: SaveData): StorageResult<SaveData> {
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

@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
internal suspend fun StorageEngine.loadFromCache(slot: Int): SaveData? = withContext(Dispatchers.IO) {
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
@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
internal suspend fun StorageEngine.loadFromDatabase(slot: Int): SaveData? {
    return try {
        loadFromDatabaseInternal(slot, loadHeavyData = true)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Failed to load from database for slot $slot", e)
        null
    }
}

/**
 * 迁移存档并返回数据；版本号非法（[MigrationResult.Rejected]）
 * 时记录并返回 null，由调用方走备份恢复分支。
 */
internal fun StorageEngine.migrateOrNull(saveData: SaveData, slot: Int): SaveData? {
    return when (val migration = SaveDataVersionMigrator.migrate(saveData)) {
        is MigrationResult.Migrated -> migration.data
        is MigrationResult.Rejected -> {
            Log.e(TAG, "存档迁移拒绝 slot=$slot: ${migration.reason}")
            null
        }
    }
}

internal suspend fun StorageEngine.loadFromDatabaseInternal(slot: Int, loadHeavyData: Boolean = false): SaveData? {
    val gameData = core.database.gameDataDao().getGameDataSync(slot) ?: return null
    val source = if (loadHeavyData) loadMergedGameData(gameData, slot) else gameData
    return buildAndMigrateSaveData(slot, source, loadHeavyData)
}

/**
 * 重型数据合并读取。
 *
 * mergeHeavyData 在事务中读取 heavy_data + domain state tables，
 * 确保这些表看到一致的数据库快照。
 * 注意：buildSaveDataFromDatabase 内部用 async {} 并行读表，不能放入 withTransaction
 *（Room withTransaction 要求内部 DAO 调用在同一线程，与 async 不兼容）。
 */
internal suspend fun StorageEngine.loadMergedGameData(gameData: GameData, slot: Int): GameData {
    return core.database.withTransaction { mergeHeavyData(gameData, slot) }
}

/**
 * 构建 → 迁移 → 校验：版本号非法返回 null，
 * 走 load() 备份恢复分支；校验失败仅记录告警不阻断。
 */
internal suspend fun StorageEngine.buildAndMigrateSaveData(
    slot: Int,
    source: GameData,
    loadHeavyData: Boolean
): SaveData? {
    val saveData = buildSaveDataFromDatabase(slot, source)
    val migrated = migrateOrNull(saveData, slot) ?: return null
    if (!validateSaveData(migrated)) {
        logValidationFailure(slot, source, loadHeavyData)
    }
    return migrated
}

/**
 * 校验失败告警：重载合并路径与常规路径日志文案不同；
 * 字段取迁移前源数据（DB 行原值）。
 */
internal fun StorageEngine.logValidationFailure(slot: Int, source: GameData, loadHeavyData: Boolean) {
    if (loadHeavyData) {
        Log.w(TAG, "Save data validation failed for slot $slot after heavy data merge")
    } else {
        Log.w(TAG, "Save data validation failed for slot $slot: gameYear=${source.gameYear}, " +
            "gameMonth=${source.gameMonth}, sectName='${source.sectName}'")
    }
}

/**
 * 备份恢复后二次验证（防止备份本身存在数据问题）。
 *
 * @return 验证后数据（Repaired 用修复后数据）；Corrupted 不可修复返回 null
 */
@Suppress("ReturnCount") // 校验结果三态分派（Passed/Repaired/Corrupted），守卫风格
internal fun StorageEngine.revalidateRestoredData(slot: Int, restoredData: SaveData): SaveData? {
    val reValidation = CorruptedResultHandler.validateRestoredData(slot, restoredData)
    if (reValidation is IntegrityResult.Repaired) {
        Log.w(TAG, "备份恢复数据二次修复 ${reValidation.details.size} 项 (slot=$slot)")
        return reValidation.data
    }
    if (reValidation is IntegrityResult.Corrupted) {
        Log.e(TAG, "备份恢复数据二次验证无法修复 (slot=$slot)")
        return null
    }
    return restoredData
}

/**
 * 备份恢复数据的版本迁移。
 *
 * @return 迁移后数据；版本号非法（[MigrationResult.Rejected]）返回 null
 */
internal fun StorageEngine.migrateRestoredData(restoredData: SaveData, slot: Int): SaveData? {
    val migration = SaveDataVersionMigrator.migrate(restoredData)
    if (migration is MigrationResult.Rejected) {
        Log.e(TAG, "备份恢复版本迁移拒绝 slot=$slot: ${migration.reason}")
        return null
    }
    return (migration as MigrationResult.Migrated).data
}
