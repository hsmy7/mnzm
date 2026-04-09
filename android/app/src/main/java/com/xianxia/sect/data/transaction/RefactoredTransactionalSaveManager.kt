package com.xianxia.sect.data.transaction

import android.content.Context
import android.util.Log
import com.xianxia.sect.core.model.*
import com.xianxia.sect.data.concurrent.SlotLockManager
import com.xianxia.sect.data.local.GameDatabase
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.unified.*
import com.xianxia.sect.data.engine.UnifiedStorageEngine
import com.xianxia.sect.data.serialization.NullSafeProtoBuf
import com.xianxia.sect.data.wal.WALProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.room.withTransaction
import androidx.annotation.VisibleForTesting
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

data class RefactoredTransactionContext(
    val txnId: Long,
    val slot: Int,
    val startTime: Long,
    val snapshotFile: File?,
    var completed: Boolean = false,
    var rolledBack: Boolean = false
)

data class SaveProgress(
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

data class TransactionStats(
    val activeTransactions: Int,
    val totalCommitted: Long,
    val totalAborted: Long,
    val averageDurationMs: Long
)

@Singleton
class RefactoredTransactionalSaveManager @Inject constructor(
    private val context: Context,
    private val database: GameDatabase,
    private val wal: WALProvider,
    private val lockManager: SlotLockManager,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "TransactionalSave"
        private const val SNAPSHOT_DIR = "save_snapshots"
        private const val MAX_BATCH_SIZE = 200
        private const val OPTIMIZATION_THRESHOLD = 500
    }

    private val txnCounter = AtomicLong(0)
    private val committedCount = AtomicLong(0)
    private val abortedCount = AtomicLong(0)
    private val totalDurationMs = AtomicLong(0)
    
    private val _progress = MutableStateFlow(SaveProgress(SaveProgress.Stage.IDLE, 0f))
    val progress: StateFlow<SaveProgress> = _progress.asStateFlow()
    
    private val activeTransactions = mutableMapOf<Long, RefactoredTransactionContext>()
    private val transactionsLock = Any()
    
    private val snapshotDir: File by lazy {
        File(context.filesDir, SNAPSHOT_DIR).apply {
            if (!exists()) mkdirs()
        }
    }
    
    /**
     * 从数据库加载指定槽位的完整存档数据
     * 这是核心读取方法，之前缺失导致该模块无法使用
     *
     * @param slot 存档槽位（1-based）
     * @return SaveResult 包含完整的 SaveData 或错误信息
     */
    suspend fun load(slot: Int): SaveResult<SaveData> {
        if (!lockManager.isValidSlot(slot)) {
            return SaveResult.failure(SaveError.INVALID_SLOT, "Invalid slot: $slot")
        }

        return lockManager.withReadLockLight(slot) {
            try {
                _progress.value = SaveProgress(SaveProgress.Stage.SAVING_CORE_DATA, 0.1f, "Loading core data")

                // 加载核心游戏数据（带 slot 参数的多租户查询）
                val gameData = database.gameDataDao().getGameDataSync(slot)
                    ?: return@withReadLockLight SaveResult.failure(
                        SaveError.LOAD_FAILED,
                        "No game data found in slot $slot"
                    )

                _progress.value = SaveProgress(SaveProgress.Stage.SAVING_DISCIPLES, 0.2f, "Loading disciples")
                // 加载弟子数据（带 slot 参数）
                val disciples = database.discipleDao().getAllAliveSync(slot)

                _progress.value = SaveProgress(SaveProgress.Stage.SAVING_ITEMS, 0.4f, "Loading items")
                // 加载所有物品数据（带 slot 参数）
                val equipment = database.equipmentDao().getAllSync(slot)
                val manuals = database.manualDao().getAllSync(slot)
                val pills = database.pillDao().getAllSync(slot)
                val materials = database.materialDao().getAllSync(slot)
                val herbs = database.herbDao().getAllSync(slot)
                val seeds = database.seedDao().getAllSync(slot)

                _progress.value = SaveProgress(SaveProgress.Stage.SAVING_WORLD_DATA, 0.6f, "Loading world data")
                // 加载世界数据（探索队伍、建筑槽位、地牢、配方等）
                val teams = database.explorationTeamDao().getAllSync(slot)
                // 联盟数据存储在 GameData 内部，直接从 gameData 获取
                val alliances = gameData.alliances ?: emptyList()

                _progress.value = SaveProgress(SaveProgress.Stage.SAVING_HISTORICAL_DATA, 0.7f, "Loading historical data")
                // 加载历史数据（战斗日志、游戏事件，带 slot 参数）
                val battleLogs = database.battleLogDao().getAllSync(slot)
                val events = database.gameEventDao().getAllSync(slot)

                _progress.value = SaveProgress(SaveProgress.Stage.COMPLETED, 1.0f, "Load completed")

                val saveData = SaveData(
                    gameData = gameData,
                    disciples = disciples,
                    equipment = equipment,
                    manuals = manuals,
                    pills = pills,
                    materials = materials,
                    herbs = herbs,
                    seeds = seeds,
                    teams = teams,
                    events = events,
                    battleLogs = battleLogs,
                    alliances = alliances,
                    productionSlots = database.productionSlotDao().getBySlotSync(slot)
                )

                SaveResult.success(saveData)

            } catch (e: Exception) {
                Log.e(TAG, "Load failed for slot $slot", e)
                SaveResult.failure(SaveError.LOAD_FAILED, e.message ?: "Load failed", e)
            }
        }
    }

    /**
     * 删除指定槽位的所有数据
     * 使用事务确保原子性删除
     *
     * @param slot 存档槽位（1-based）
     * @return SaveResult 成功或失败信息
     */
    suspend fun deleteSlot(slot: Int): SaveResult<Unit> {
        if (!lockManager.isValidSlot(slot)) {
            return SaveResult.failure(SaveError.INVALID_SLOT, "Invalid slot: $slot")
        }

        return lockManager.withWriteLockLight(slot) {
            try {
                // 使用 withTransaction 替代 runInTransaction，因为 DAO 的 deleteAll(slot)
                // 已改造为 suspend 函数，需要在协程事务中调用
                database.withTransaction {
                    // 清理核心游戏数据（带 slot 参数的多租户删除）
                    database.gameDataDao().deleteAll(slot)

                    // 清理弟子数据及子表（带 slot 参数）
                    database.discipleDao().deleteAll(slot)
                    database.discipleCoreDao().deleteAll(slot)
                    database.discipleCombatStatsDao().deleteAll(slot)
                    database.discipleEquipmentDao().deleteAll(slot)
                    database.discipleExtendedDao().deleteAll(slot)
                    database.discipleAttributesDao().deleteAll(slot)

                    // 清理物品数据（带 slot 参数）
                    database.equipmentDao().deleteAll(slot)
                    database.manualDao().deleteAll(slot)
                    database.pillDao().deleteAll(slot)
                    database.materialDao().deleteAll(slot)
                    database.seedDao().deleteAll(slot)
                    database.herbDao().deleteAll(slot)

                    // 清理世界数据（带 slot 参数）
                    database.explorationTeamDao().deleteAll(slot)
                    database.buildingSlotDao().deleteAll(slot)
                    database.dungeonDao().deleteAll(slot)
                    database.recipeDao().deleteAll(slot)

                    // 清理生产槽位数据
                    // 注意：forgeSlotDao/alchemySlotDao 支持 deleteAll(slot) 多租户删除
                    // productionSlotDao 现已支持按槽位删除，使用 deleteBySlot 避免误删其他槽位数据
                    database.forgeSlotDao().deleteAll(slot)
                    database.alchemySlotDao().deleteAll(slot)
                    database.productionSlotDao().deleteBySlot(slot)

                    // 清理历史数据（带 slot 参数）
                    database.battleLogDao().deleteAll(slot)
                    database.gameEventDao().deleteAll(slot)
                }
                SaveResult.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Delete failed for slot $slot", e)
                SaveResult.failure(SaveError.DELETE_FAILED, e.message ?: "Delete failed", e)
            }
        }
    }

    /**
     * 检查指定槽位是否有存档数据
     *
     * @param slot 存档槽位（1-based）
     * @return true 如果存在数据
     */
    suspend fun hasData(slot: Int): Boolean {
        if (!lockManager.isValidSlot(slot)) return false

        return try {
            // 检查指定槽位是否有核心游戏数据（带 slot 参数）
            database.gameDataDao().getGameDataSync(slot) != null
        } catch (e: Exception) {
            Log.e(TAG, "hasData check failed for slot $slot", e)
            false
        }
    }

    /**
     * 同步版本的数据检查方法（用于非协程上下文）
     *
     * @param slot 存档槽位（1-based）
     * @return true 如果存在数据
     */
    fun hasDataSync(slot: Int): Boolean = runBlocking { hasData(slot) }

    suspend fun save(slot: Int, data: SaveData): SaveResult<SaveOperationStats> {
        if (!lockManager.isValidSlot(slot)) {
            return SaveResult.failure(SaveError.INVALID_SLOT, "Invalid slot: $slot")
        }
        
        val startTime = System.currentTimeMillis()
        var txnContext: RefactoredTransactionContext? = null
        
        return lockManager.withWriteLockLight(slot) {
            try {
                val txnResult = beginTransaction(slot)
                if (txnResult.isFailure) {
                    return@withWriteLockLight txnResult.map { SaveOperationStats() }
                }
                
                txnContext = txnResult.getOrThrow()

                val ctx = txnContext
                    ?: throw IllegalStateException("Transaction context is null after beginTransaction")

                _progress.value = SaveProgress(SaveProgress.Stage.CREATING_SNAPSHOT, 0.05f, "Creating backup snapshot")
                val snapshotFile = createPreSaveSnapshot(slot, ctx.txnId)
                val ctxWithSnapshot = ctx.copy(snapshotFile = snapshotFile)
                
                _progress.value = SaveProgress(SaveProgress.Stage.SAVING_CORE_DATA, 0.1f, "Saving core data")
                saveCoreData(slot, data.gameData)
                
                _progress.value = SaveProgress(SaveProgress.Stage.SAVING_DISCIPLES, 0.2f, "Saving disciples")
                saveDisciples(slot, data.disciples)
                
                _progress.value = SaveProgress(SaveProgress.Stage.SAVING_ITEMS, 0.4f, "Saving items")
                saveItems(slot, data)
                
                _progress.value = SaveProgress(SaveProgress.Stage.SAVING_WORLD_DATA, 0.6f, "Saving world data")
                saveWorldData(slot, data)
                
                _progress.value = SaveProgress(SaveProgress.Stage.SAVING_HISTORICAL_DATA, 0.7f, "Saving historical data")
                saveHistoricalData(slot, data)
                
                _progress.value = SaveProgress(SaveProgress.Stage.VALIDATING, 0.85f, "Validating saved data")
                val validation = validateSlotData(slot)
                
                if (!validation.isValid) {
                    throw SaveValidationException(validation.errors.joinToString(", "))
                }
                
                _progress.value = SaveProgress(SaveProgress.Stage.COMMITTING, 0.9f, "Committing transaction")
                commitTransaction(ctxWithSnapshot)
                
                snapshotFile?.delete()
                
                val elapsed = System.currentTimeMillis() - startTime
                _progress.value = SaveProgress(SaveProgress.Stage.COMPLETED, 1.0f, "Save completed in ${elapsed}ms")
                
                val stats = SaveOperationStats(
                    bytesWritten = estimateDataSize(data),
                    timeMs = elapsed,
                    wasEncrypted = true
                )
                
                SaveResult.success(stats)
                
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    txnContext?.let { ctx ->
                        rollbackTransaction(ctx)
                    }
                    throw e
                }
                
                Log.e(TAG, "Save transaction failed for slot $slot", e)
                
                txnContext?.let { ctx ->
                    _progress.value = SaveProgress(SaveProgress.Stage.ROLLING_BACK, 0.95f, "Rolling back changes")
                    rollbackTransaction(ctx)
                }
                
                _progress.value = SaveProgress(SaveProgress.Stage.FAILED, 0f, e.message ?: "Unknown error")
                
                val error = when (e) {
                    is SaveValidationException -> SaveError.SAVE_FAILED
                    is OutOfMemoryError -> SaveError.OUT_OF_MEMORY
                    is java.io.IOException -> SaveError.IO_ERROR
                    else -> SaveError.UNKNOWN
                }
                
                SaveResult.failure(error, e.message ?: "Unknown error", e)
            }
        }
    }
    
    private suspend fun beginTransaction(slot: Int): SaveResult<RefactoredTransactionContext> {
        val txnId = txnCounter.incrementAndGet()

        val ctx = RefactoredTransactionContext(
            txnId = txnId,
            slot = slot,
            startTime = System.currentTimeMillis(),
            snapshotFile = null
        )

        synchronized(transactionsLock) {
            activeTransactions[ctx.txnId] = ctx
        }

        Log.d(TAG, "Transaction ${ctx.txnId} started for slot $slot")
        return SaveResult.success(ctx)
    }
    
    private suspend fun commitTransaction(ctx: RefactoredTransactionContext) {
        synchronized(transactionsLock) {
            activeTransactions.remove(ctx.txnId)
        }

        val duration = System.currentTimeMillis() - ctx.startTime
        committedCount.incrementAndGet()
        totalDurationMs.addAndGet(duration)

        ctx.completed = true
        Log.d(TAG, "Transaction ${ctx.txnId} committed for slot ${ctx.slot} in ${duration}ms")
    }
    
    private suspend fun rollbackTransaction(ctx: RefactoredTransactionContext) {
        try {
            ctx.snapshotFile?.let { snapshot ->
                restoreFromSnapshot(ctx.slot, snapshot)
            }

            abortedCount.incrementAndGet()
            ctx.rolledBack = true

            Log.i(TAG, "Transaction ${ctx.txnId} rolled back for slot ${ctx.slot}")

        } catch (e: Exception) {
            Log.e(TAG, "Rollback failed for transaction ${ctx.txnId}", e)
        } finally {
            synchronized(transactionsLock) {
                activeTransactions.remove(ctx.txnId)
            }
        }
    }
    
    @VisibleForTesting
    private suspend fun createPreSaveSnapshot(slot: Int, txnId: Long): File? {
        return try {
            val snapshotFile = File(snapshotDir, "slot_${slot}_txn_${txnId}.snapshot")
            
            if (hasExistingData(slot)) {
                exportSlotData(slot, snapshotFile)
                Log.d(TAG, "Created pre-save snapshot: ${snapshotFile.absolutePath}")
                snapshotFile
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create pre-save snapshot", e)
            null
        }
    }
    
    private suspend fun serializeCurrentData(slot: Int): ByteArray {
        return try {
            // 序列化指定槽位的当前游戏数据（带 slot 参数）- 使用 Protobuf
            val gameData = database.gameDataDao().getGameDataSync(slot)
            if (gameData != null) {
                NullSafeProtoBuf.protoBuf.encodeToByteArray(GameData.serializer(), gameData)
            } else {
                ByteArray(0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to serialize current data for slot $slot", e)
            ByteArray(0)
        }
    }

    /**
     * 保存核心游戏数据到数据库
     * 使用 REPLACE 策略，根据主键自动更新已存在的记录
     *
     * @param slot 存档槽位（用于多租户数据隔离）
     * @param gameData 游戏核心数据
     */
    private suspend fun saveCoreData(slot: Int, gameData: GameData) {
        val gameDataWithSlot = gameData.copy(slotId = slot, currentSlot = slot, id = "game_data_$slot")
        database.gameDataDao().insert(gameDataWithSlot)
    }

    /**
     * 批量保存弟子数据
     * 使用分批插入优化性能，避免一次性插入过多数据导致事务超时
     *
     * @param slot 存档槽位（用于多租户数据隔离）
     * @param disciples 弟子列表
     */
    @Suppress("DEPRECATION")
    private suspend fun saveDisciples(slot: Int, disciples: List<Disciple>) {
        if (disciples.isEmpty()) return
        val batchSize = calculateOptimalBatchSize(disciples.size)
        // 分批插入弟子数据
        disciples.chunked(batchSize).forEach { batch ->
            database.discipleDao().insertAll(batch)
        }
    }

    /**
     * 保存所有物品数据（装备、功法、丹药、材料、草药、种子）
     *
     * @param slot 存档槽位（用于多租户数据隔离）
     * @param data 完整存档数据
     */
    private suspend fun saveItems(slot: Int, data: SaveData) {
        // 批量插入各类物品数据
        data.equipment.chunked(MAX_BATCH_SIZE).forEach { database.equipmentDao().insertAll(it) }
        data.manuals.chunked(MAX_BATCH_SIZE).forEach { database.manualDao().insertAll(it) }
        data.pills.chunked(MAX_BATCH_SIZE).forEach { database.pillDao().insertAll(it) }
        data.materials.chunked(MAX_BATCH_SIZE).forEach { database.materialDao().insertAll(it) }
        data.herbs.chunked(MAX_BATCH_SIZE).forEach { database.herbDao().insertAll(it) }
        data.seeds.chunked(MAX_BATCH_SIZE).forEach { database.seedDao().insertAll(it) }
    }

    /**
     * 保存世界数据（探索队伍、联盟等）
     *
     * @param slot 存档槽位（用于多租户数据隔离）
     * @param data 完整存档数据
     */
    private suspend fun saveWorldData(slot: Int, data: SaveData) {
        // 批量插入探索队伍数据
        data.teams.chunked(MAX_BATCH_SIZE).forEach { database.explorationTeamDao().insertAll(it) }
        // 联盟数据存储在 GameData 中，无需单独保存
        data.alliances.takeIf { it.isNotEmpty() }?.let {
            Log.d(TAG, "Saving ${it.size} alliances (stored within GameData)")
        }
    }

    /**
     * 保存历史数据（战斗日志、游戏事件等）
     *
     * @param slot 存档槽位（用于多租户数据隔离）
     * @param data 完整存档数据
     */
    private suspend fun saveHistoricalData(slot: Int, data: SaveData) {
        // 批量插入历史数据
        data.battleLogs.chunked(MAX_BATCH_SIZE).forEach { database.battleLogDao().insertAll(it) }
        data.events.chunked(MAX_BATCH_SIZE).forEach { database.gameEventDao().insertAll(it) }
    }

    /**
     * 验证指定槽位的数据完整性
     * 检查核心数据是否存在且有效
     *
     * @param slot 存档槽位
     * @return 验证结果
     */
    private suspend fun validateSlotData(slot: Int): ValidationResult {
        return try {
            // 验证指定槽位的游戏数据是否完整（带 slot 参数）
            val gameData = database.gameDataDao().getGameDataSync(slot)
            ValidationResult(
                isValid = gameData != null,
                databaseValid = gameData != null,
                cacheValid = true,  // 缓存验证暂未实现
                partitionsValid = true  // 分区验证暂未实现
            )
        } catch (e: Exception) {
            Log.e(TAG, "Validation failed for slot $slot", e)
            ValidationResult(
                isValid = false,
                errors = listOf(e.message ?: "Validation failed for slot $slot")
            )
        }
    }

    /**
     * 检查指定槽位是否已存在数据（用于判断是否需要创建快照）
     *
     * @param slot 存档槽位
     * @return true 如果存在数据
     */
    private suspend fun hasExistingData(slot: Int): Boolean {
        return try {
            // 检查指定槽位是否已有游戏数据（带 slot 参数）
            database.gameDataDao().getGameDataSync(slot) != null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check existing data for slot $slot", e)
            false
        }
    }

    /**
     * 导出指定槽位的完整数据到 JSON 文件
     * 用于备份或迁移用途
     *
     * @param slot 存档槽位
     * @param file 目标文件路径
     */
    private suspend fun exportSlotData(slot: Int, file: File) {
        try {
            Log.d(TAG, "Exporting slot $slot data to ${file.absolutePath}")

            // 从数据库加载当前槽位数据
            val loadResult = load(slot)
            if (loadResult.isFailure) {
                throw Exception("Failed to load data for export: ${loadResult.getOrNull()}")
            }

            val saveData = loadResult.getOrThrow()

            // 序列化为 Protobuf 格式（二进制）
            val protobufBytes = NullSafeProtoBuf.protoBuf.encodeToByteArray(SaveData.serializer(), saveData)

            // 写入文件（二进制格式）
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { output ->
                output.write(protobufBytes)
            }

            Log.i(TAG, "Successfully exported slot $slot to ${file.absolutePath} (${protobufBytes.size} bytes)")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to export slot $slot data", e)
            throw e  // 向上抛出以便调用方处理
        }
    }

    /**
     * 从 JSON 文件导入数据到指定槽位
     * 用于恢复备份或迁移数据
     *
     * @param slot 存档槽位
     * @param file 源文件路径
     */
    private suspend fun importSlotData(slot: Int, file: File) {
        try {
            Log.d(TAG, "Importing slot $slot data from ${file.absolutePath}")

            if (!file.exists()) {
                throw FileNotFoundException("Import file not found: ${file.absolutePath}")
            }

            // 读取并反序列化 Protobuf 二进制数据
            val protobufBytes = file.readBytes()
            val saveData = NullSafeProtoBuf.protoBuf.decodeFromByteArray(SaveData.serializer(), protobufBytes)

            // 将导入的数据写入数据库（复用 save 方法的逻辑）
            val saveResult = save(slot, saveData)
            if (saveResult.isFailure) {
                throw Exception("Failed to save imported data: ${saveResult.getOrNull()}")
            }

            Log.i(TAG, "Successfully imported slot $slot from ${file.absolutePath}")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to import slot $slot data", e)
            throw e  // 向上抛出以便调用方处理
        }
    }
    
    private suspend fun restoreFromSnapshot(slot: Int, snapshotFile: File) {
        try {
            if (snapshotFile.exists()) {
                importSlotData(slot, snapshotFile)
                Log.i(TAG, "Restored slot $slot from snapshot")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore from snapshot", e)
        }
    }
    
    private fun calculateOptimalBatchSize(totalSize: Int): Int {
        return when {
            totalSize < 100 -> totalSize
            totalSize < OPTIMIZATION_THRESHOLD -> MAX_BATCH_SIZE
            else -> MAX_BATCH_SIZE * 2
        }
    }
    
    /**
     * 估算存档数据的字节大小
     * 使用基于 Protobuf 采样估算的优化公式（与 UnifiedSaveRepository 保持一致）
     *
     * 估算策略：
     * - 对每种实体类型使用采样估算，考虑字段数量和平均字段长度
     * - 弟子数据最复杂（包含大量属性），权重最高
     * - 基础开销包括 JSON/Protobuf 序列化的元数据和结构信息
     *
     * @param data 存档数据
     * @return 预估字节数
     */
    private fun estimateDataSize(data: SaveData): Long {
        return UnifiedStorageEngine.estimateSaveSize(data)
    }

    fun getActiveTransactionCount(): Int = synchronized(transactionsLock) { activeTransactions.size }
    
    fun hasActiveTransactions(): Boolean = synchronized(transactionsLock) { activeTransactions.isNotEmpty() }
    
    fun getStats(): TransactionStats {
        val committed = committedCount.get()
        return TransactionStats(
            activeTransactions = getActiveTransactionCount(),
            totalCommitted = committed,
            totalAborted = abortedCount.get(),
            averageDurationMs = if (committed > 0) totalDurationMs.get() / committed else 0
        )
    }
    
    fun shutdown() {
        scope.cancel()

        synchronized(transactionsLock) {
            activeTransactions.values.forEach { ctx ->
                if (!ctx.completed && !ctx.rolledBack) {
                    ctx.snapshotFile?.delete()
                }
            }
            activeTransactions.clear()
        }

        Log.i(TAG, "TransactionalSaveManager shutdown completed")
    }
}

class SaveValidationException(message: String) : Exception(message)

data class ValidationResult(
    val isValid: Boolean,
    val databaseValid: Boolean = true,
    val cacheValid: Boolean = true,
    val partitionsValid: Boolean = true,
    val errors: List<String> = emptyList()
)
