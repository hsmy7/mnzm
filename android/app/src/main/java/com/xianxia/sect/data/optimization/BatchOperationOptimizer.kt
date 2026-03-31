package com.xianxia.sect.data.optimization

import android.util.Log
import com.xianxia.sect.core.model.*
import com.xianxia.sect.data.local.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

data class BatchOperationStats(
    val totalOperations: Long = 0,
    val successfulOperations: Long = 0,
    val failedOperations: Long = 0,
    val totalBytesProcessed: Long = 0,
    val averageBatchSize: Int = 0,
    val averageProcessingTimeMs: Long = 0
)

data class BatchConfig(
    val minBatchSize: Int = 50,
    val maxBatchSize: Int = 500,
    val maxQueueSize: Int = 10000,
    val flushIntervalMs: Long = 1000L,
    val maxRetries: Int = 3,
    val queueFullStrategy: QueueFullStrategy = QueueFullStrategy.DROP_OLDEST
)

enum class QueueFullStrategy {
    DROP_OLDEST,
    DROP_NEWEST,
    BLOCK_WITH_TIMEOUT
}

sealed class BatchOperation {
    data class Insert<T>(val entities: List<T>, val tableName: String) : BatchOperation()
    data class Update<T>(val entities: List<T>, val tableName: String) : BatchOperation()
    data class Delete(val ids: List<String>, val tableName: String) : BatchOperation()
}

@Singleton
class BatchOperationOptimizer @Inject constructor(
    private val database: GameDatabase,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "BatchOperationOptimizer"
        private const val QUEUE_FULL_TIMEOUT_MS = 5000L
    }
    
    private val config = BatchConfig()
    
    private val operationQueue = ConcurrentLinkedQueue<BatchOperation>()
    private val queueSize = AtomicLong(0)
    private val processingMutex = Mutex()
    private val isProcessing = AtomicBoolean(false)
    
    private val totalOperations = AtomicLong(0)
    private val successfulOperations = AtomicLong(0)
    private val failedOperations = AtomicLong(0)
    private val totalBytesProcessed = AtomicLong(0)
    private val totalProcessingTime = AtomicLong(0)
    
    private val _stats = MutableStateFlow(BatchOperationStats())
    val stats: StateFlow<BatchOperationStats> = _stats.asStateFlow()
    
    private var flushJob: Job? = null
    
    private val batchUpdateDao: BatchUpdateDao by lazy { database.batchUpdateDao() }
    
    init {
        startFlushScheduler()
    }
    
    private fun startFlushScheduler() {
        flushJob = scope.launch {
            while (isActive) {
                delay(config.flushIntervalMs)
                if (operationQueue.isNotEmpty()) {
                    processBatchSuspend()
                }
            }
        }
    }
    
    suspend fun <T : Any> queueInsertAsync(entities: List<T>, tableName: String): Boolean {
        if (entities.isEmpty()) return true
        
        if (queueSize.get() >= config.maxQueueSize) {
            when (config.queueFullStrategy) {
                QueueFullStrategy.DROP_OLDEST -> {
                    Log.w(TAG, "Queue full, dropping oldest operations")
                    while (queueSize.get() >= config.maxQueueSize && operationQueue.isNotEmpty()) {
                        operationQueue.poll()
                        queueSize.decrementAndGet()
                    }
                }
                QueueFullStrategy.DROP_NEWEST -> {
                    Log.w(TAG, "Queue full, rejecting new operations")
                    return false
                }
                QueueFullStrategy.BLOCK_WITH_TIMEOUT -> {
                    Log.w(TAG, "Queue full, waiting for flush with timeout")
                    withTimeoutOrNull(QUEUE_FULL_TIMEOUT_MS) {
                        while (queueSize.get() >= config.maxQueueSize) {
                            processBatchSuspend()
                            delay(100)
                        }
                    } ?: run {
                        Log.e(TAG, "Timeout waiting for queue space")
                        return false
                    }
                }
            }
        }
        
        val validatedTable = SafeTableRegistry.validateTableName(tableName)
        val chunkedEntities = entities.chunked(config.maxBatchSize)
        chunkedEntities.forEach { chunk ->
            operationQueue.offer(BatchOperation.Insert(chunk, validatedTable))
            queueSize.incrementAndGet()
        }
        
        return true
    }
    
    fun <T : Any> queueInsert(entities: List<T>, tableName: String): Boolean {
        if (entities.isEmpty()) return true
        
        if (queueSize.get() >= config.maxQueueSize) {
            Log.w(TAG, "Queue is full, triggering async flush")
            scope.launch { processBatchSuspend() }
        }
        
        val validatedTable = SafeTableRegistry.validateTableName(tableName)
        val chunkedEntities = entities.chunked(config.maxBatchSize)
        chunkedEntities.forEach { chunk ->
            operationQueue.offer(BatchOperation.Insert(chunk, validatedTable))
            queueSize.incrementAndGet()
        }
        
        return true
    }
    
    fun <T : Any> queueUpdate(entities: List<T>, tableName: String): Boolean {
        if (entities.isEmpty()) return true
        
        val validatedTable = SafeTableRegistry.validateTableName(tableName)
        val chunkedEntities = entities.chunked(config.maxBatchSize)
        chunkedEntities.forEach { chunk ->
            operationQueue.offer(BatchOperation.Update(chunk, validatedTable))
            queueSize.incrementAndGet()
        }
        
        return true
    }
    
    fun queueDelete(ids: List<String>, tableName: String): Boolean {
        if (ids.isEmpty()) return true
        
        val validatedTable = SafeTableRegistry.validateTableName(tableName)
        val chunkedIds = ids.chunked(config.maxBatchSize)
        chunkedIds.forEach { chunk ->
            operationQueue.offer(BatchOperation.Delete(chunk, validatedTable))
            queueSize.incrementAndGet()
        }
        
        return true
    }
    
    suspend fun <T : Any> insertImmediate(entities: List<T>, tableName: String): Int {
        if (entities.isEmpty()) return 0
        
        val validatedTable = SafeTableRegistry.validateTableName(tableName)
        val startTime = System.currentTimeMillis()
        
        return try {
            val batchSize = calculateOptimalBatchSize(entities.size, validatedTable)
            var insertedCount = 0
            
            entities.chunked(batchSize).forEach { batch ->
                insertedCount += insertBatchSuspend(batch, validatedTable)
            }
            
            val elapsed = System.currentTimeMillis() - startTime
            totalOperations.addAndGet(entities.size.toLong())
            successfulOperations.addAndGet(insertedCount.toLong())
            totalProcessingTime.addAndGet(elapsed)
            
            Log.d(TAG, "Inserted $insertedCount entities into $validatedTable in ${elapsed}ms")
            insertedCount
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to insert entities into $validatedTable", e)
            failedOperations.addAndGet(entities.size.toLong())
            0
        }
    }
    
    private suspend fun <T : Any> insertBatchSuspend(batch: List<T>, tableName: String): Int {
        return when (tableName.lowercase()) {
            "disciple", "disciples" -> {
                @Suppress("UNCHECKED_CAST")
                val disciples = batch as List<Disciple>
                val splitEntities = disciples.map { it.toSplitEntities() }
                database.discipleCoreDao().insertAll(splitEntities.map { it.core })
                database.discipleCombatStatsDao().insertAll(splitEntities.map { it.combatStats })
                database.discipleEquipmentDao().insertAll(splitEntities.map { it.equipment })
                database.discipleExtendedDao().insertAll(splitEntities.map { it.extended })
                database.discipleAttributesDao().insertAll(splitEntities.map { it.attributes })
                batch.size
            }
            "equipment" -> {
                @Suppress("UNCHECKED_CAST")
                database.equipmentDao().insertAll(batch as List<Equipment>)
                batch.size
            }
            "manual", "manuals" -> {
                @Suppress("UNCHECKED_CAST")
                database.manualDao().insertAll(batch as List<Manual>)
                batch.size
            }
            "pill", "pills" -> {
                @Suppress("UNCHECKED_CAST")
                database.pillDao().insertAll(batch as List<Pill>)
                batch.size
            }
            "material", "materials" -> {
                @Suppress("UNCHECKED_CAST")
                database.materialDao().insertAll(batch as List<Material>)
                batch.size
            }
            "herb", "herbs" -> {
                @Suppress("UNCHECKED_CAST")
                database.herbDao().insertAll(batch as List<Herb>)
                batch.size
            }
            "seed", "seeds" -> {
                @Suppress("UNCHECKED_CAST")
                database.seedDao().insertAll(batch as List<Seed>)
                batch.size
            }
            "battlelog", "battle_logs" -> {
                @Suppress("UNCHECKED_CAST")
                database.battleLogDao().insertAll(batch as List<BattleLog>)
                batch.size
            }
            "gameevent", "game_events" -> {
                @Suppress("UNCHECKED_CAST")
                database.gameEventDao().insertAll(batch as List<GameEvent>)
                batch.size
            }
            else -> {
                Log.w(TAG, "Unknown table: $tableName")
                0
            }
        }
    }
    
    private suspend fun <T : Any> updateBatchSuspend(batch: List<T>, tableName: String): Int {
        return insertBatchSuspend(batch, tableName)
    }
    
    suspend fun processBatchSuspend(): Int {
        if (!isProcessing.compareAndSet(false, true)) {
            return 0
        }
        
        return processingMutex.withLock {
            val startTime = System.currentTimeMillis()
            var processedCount = 0
            
            try {
                val operations = mutableListOf<BatchOperation>()
                var op: BatchOperation? = operationQueue.poll()
                while (op != null && operations.size < config.maxBatchSize) {
                    operations.add(op)
                    queueSize.decrementAndGet()
                    op = operationQueue.poll()
                }
                
                if (operations.isEmpty()) {
                    return@withLock 0
                }
                
                withContext(Dispatchers.IO) {
                    operations.forEach { operation ->
                        processedCount += processOperationSuspend(operation)
                    }
                }
                
                val elapsed = System.currentTimeMillis() - startTime
                totalOperations.addAndGet(operations.size.toLong())
                successfulOperations.addAndGet(processedCount.toLong())
                totalProcessingTime.addAndGet(elapsed)
                
                Log.d(TAG, "Processed ${operations.size} operations, $processedCount entities in ${elapsed}ms")
                processedCount
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process batch", e)
                failedOperations.incrementAndGet()
                processedCount
            } finally {
                isProcessing.set(false)
                updateStats()
            }
        }
    }
    
    private suspend fun processOperationSuspend(operation: BatchOperation): Int {
        @Suppress("UNCHECKED_CAST")
        return when (operation) {
            is BatchOperation.Insert<*> -> {
                insertBatchSuspend(operation.entities as List<Any>, operation.tableName)
            }
            is BatchOperation.Update<*> -> {
                updateBatchSuspend(operation.entities as List<Any>, operation.tableName)
            }
            is BatchOperation.Delete -> {
                deleteBatchSuspend(operation.ids, operation.tableName)
                operation.ids.size
            }
        }
    }
    
    private suspend fun deleteBatchSuspend(ids: List<String>, tableName: String) {
        if (ids.isEmpty()) return
        
        when (tableName.lowercase()) {
            "disciple", "disciples" -> {
                batchUpdateDao.batchDeleteDisciples(ids)
                batchUpdateDao.batchDeleteDiscipleCombat(ids)
                batchUpdateDao.batchDeleteDiscipleEquipment(ids)
                batchUpdateDao.batchDeleteDiscipleExtended(ids)
                batchUpdateDao.batchDeleteDiscipleAttributes(ids)
            }
            "equipment" -> batchUpdateDao.batchDeleteEquipment(ids)
            "manual", "manuals" -> batchUpdateDao.batchDeleteManuals(ids)
            "pill", "pills" -> batchUpdateDao.batchDeletePills(ids)
            "material", "materials" -> batchUpdateDao.batchDeleteMaterials(ids)
            "herb", "herbs" -> batchUpdateDao.batchDeleteHerbs(ids)
            "seed", "seeds" -> batchUpdateDao.batchDeleteSeeds(ids)
            "battlelog", "battle_logs" -> batchUpdateDao.batchDeleteBattleLogs(ids)
            "gameevent", "game_events" -> batchUpdateDao.batchDeleteGameEvents(ids)
            else -> Log.w(TAG, "Unknown table for delete: $tableName")
        }
    }
    
    private fun calculateOptimalBatchSize(totalSize: Int, tableName: String): Int {
        val baseSize = when (tableName.lowercase()) {
            "disciple", "disciples" -> 100
            "equipment" -> 150
            "manual", "manuals", "pill", "pills" -> 200
            "material", "materials", "herb", "herbs", "seed", "seeds" -> 300
            "battlelog", "battle_logs" -> 50
            "gameevent", "game_events" -> 100
            else -> 100
        }
        
        return when {
            totalSize < baseSize -> totalSize
            totalSize < baseSize * 2 -> baseSize
            totalSize < baseSize * 5 -> baseSize * 2
            else -> config.maxBatchSize
        }
    }
    
    suspend fun flush(): Int {
        var totalProcessed = 0
        while (operationQueue.isNotEmpty()) {
            totalProcessed += processBatchSuspend()
        }
        return totalProcessed
    }
    
    fun getQueueSize(): Long = queueSize.get()
    
    fun hasPendingOperations(): Boolean = operationQueue.isNotEmpty()
    
    private fun updateStats() {
        val ops = totalOperations.get()
        _stats.value = BatchOperationStats(
            totalOperations = ops,
            successfulOperations = successfulOperations.get(),
            failedOperations = failedOperations.get(),
            totalBytesProcessed = totalBytesProcessed.get(),
            averageBatchSize = if (ops > 0) (successfulOperations.get() / ops).toInt() else 0,
            averageProcessingTimeMs = if (ops > 0) totalProcessingTime.get() / ops else 0
        )
    }
    
    fun getStats(): BatchOperationStats {
        updateStats()
        return _stats.value
    }
    
    fun clearQueue() {
        operationQueue.clear()
        queueSize.set(0)
    }
    
    suspend fun shutdownAsync() {
        flushJob?.cancel()
        flush()
        Log.i(TAG, "BatchOperationOptimizer shutdown completed")
    }
    
    fun shutdown() {
        flushJob?.cancel()
        kotlinx.coroutines.runBlocking {
            flush()
        }
        Log.i(TAG, "BatchOperationOptimizer shutdown completed")
    }
}
