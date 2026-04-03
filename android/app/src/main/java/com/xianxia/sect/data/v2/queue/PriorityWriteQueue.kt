package com.xianxia.sect.data.v2.queue

import android.util.Log
import com.xianxia.sect.data.v2.StorageArchitecture
import com.xianxia.sect.data.v2.StoragePriority
import com.xianxia.sect.data.local.OptimizedGameDatabase
import com.xianxia.sect.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*

class DeduplicationWriteQueue(
    private val delegate: WriteQueueDelegate,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "DeduplicationWriteQueue"
    }
    
    private val pendingKeys = ConcurrentHashMap<String, WriteTask>()
    private val lastWriteTime = ConcurrentHashMap<String, Long>()
    private val dedupeWindowMs = 1000L
    
    fun enqueue(task: WriteTask): Boolean {
        val existing = pendingKeys[task.key]
        
        if (existing != null) {
            if (task.priority.ordinal < existing.priority.ordinal) {
                delegate.cancel(existing.id)
                pendingKeys[task.key] = task
                return delegate.enqueue(task)
            }
            
            Log.d(TAG, "Deduplicated task for key: ${task.key}")
            return true
        }
        
        pendingKeys[task.key] = task
        return delegate.enqueue(task)
    }
    
    fun markCompleted(key: String) {
        pendingKeys.remove(key)
        lastWriteTime[key] = System.currentTimeMillis()
    }
    
    fun shouldThrottle(key: String): Boolean {
        val lastWrite = lastWriteTime[key] ?: return false
        return System.currentTimeMillis() - lastWrite < dedupeWindowMs
    }
    
    fun hasPendingTask(key: String): Boolean = pendingKeys.containsKey(key)
    
    fun getPendingTasks(): List<WriteTask> = pendingKeys.values.toList()
    
    fun clear() {
        pendingKeys.clear()
        delegate.clear()
    }
    
    fun shutdown() {
        pendingKeys.clear()
        delegate.shutdown()
        scope.cancel()
        Log.i(TAG, "DeduplicationWriteQueue shutdown completed")
    }
}

class PriorityWriteQueue(
    private val delegate: WriteQueueDelegate,
    private val config: WriteQueueConfig = WriteQueueConfig(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "PriorityWriteQueue"
    }
    
    private val highPriorityQueue = LinkedBlockingQueue<WriteTask>(config.highPriorityCapacity)
    private val normalPriorityQueue = LinkedBlockingQueue<WriteTask>(config.normalPriorityCapacity)
    private val lowPriorityQueue = LinkedBlockingQueue<WriteTask>(config.lowPriorityCapacity)
    
    private val pendingTasks = ConcurrentHashMap<String, WriteTask>()
    private val processingTasks = ConcurrentHashMap<String, WriteTask>()
    private val processingMutex = Any()
    
    private val completedTasks = AtomicLong(0)
    private val failedTasks = AtomicLong(0)
    private val totalProcessingTime = AtomicLong(0)
    
    private val _stats = MutableStateFlow(WriteQueueStats())
    val stats: StateFlow<WriteQueueStats> = _stats.asStateFlow()
    
    private val _events = MutableSharedFlow<QueueEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<QueueEvent> = _events.asSharedFlow()
    
    private var flushJob: Job? = null
    private var isShuttingDown = false
    
    private val taskIdCounter = AtomicLong(0)
    
    init {
        startFlushTask()
    }
    
    private fun startFlushTask() {
        flushJob = scope.launch {
            while (isActive && !isShuttingDown) {
                delay(config.flushIntervalMs)
                processBatch()
            }
        }
    }
    
    fun enqueue(task: WriteTask): Boolean {
        if (isShuttingDown) return false
        
        val queue = when (task.priority) {
            StoragePriority.CRITICAL, StoragePriority.HIGH -> highPriorityQueue
            StoragePriority.NORMAL -> normalPriorityQueue
            StoragePriority.LOW, StoragePriority.BACKGROUND -> lowPriorityQueue
        }
        
        val added = queue.offer(task)
        if (added) {
            pendingTasks[task.id] = task
            _stats.value = _stats.value.copy(
                pendingCount = _stats.value.pendingCount + 1
            )
            scope.launch {
                _events.emit(QueueEvent.TaskEnqueued(task.id, task.priority))
            }
            Log.d(TAG, "Enqueued task ${task.id} with priority ${task.priority}")
        } else {
            Log.w(TAG, "Queue full for priority ${task.priority}, task ${task.id} rejected")
            task.callback?.onFailure(task.id, Exception("Queue full"))
        }
        
        return added
    }
    
    fun enqueueBatch(tasks: List<WriteTask>): Int {
        var successCount = 0
        tasks.forEach { task ->
            if (enqueue(task)) {
                successCount++
            }
        }
        return successCount
    }
    
    fun enqueueAndWait(task: WriteTask, timeoutMs: Long = 5000): Boolean {
        val result = CompletableDeferred<Boolean>()

        val taskWithCallback = task.copy(
            callback = DeferredWriteCallback(task.callback, result)
        )
        
        if (!enqueue(taskWithCallback)) {
            return false
        }
        
        return runBlocking {
            withTimeout(timeoutMs) {
                result.await()
            }
        }
    }
    
    private suspend fun processBatch() {
        val batchSize = StorageArchitecture.WriteQueue.BATCH_SIZE
        val tasks = mutableListOf<WriteTask>()
        
        repeat(batchSize) {
            pollNextTask()?.let { tasks.add(it) }
        }
        
        if (tasks.isEmpty()) return
        
        val startTime = System.currentTimeMillis()
        var successCount = 0
        
        tasks.forEach { task ->
            synchronized(processingMutex) {
                pendingTasks.remove(task.id)
                processingTasks[task.id] = task
            }
            
            try {
                if (delegate.handle(task)) {
                    completedTasks.incrementAndGet()
                    task.callback?.onSuccess(task.id)
                    successCount++
                    _events.emit(QueueEvent.TaskCompleted(task.id, System.currentTimeMillis() - task.timestamp))
                } else {
                    handleTaskFailure(task, Exception("Handler returned false"))
                }
            } catch (e: Exception) {
                handleTaskFailure(task, e)
            } finally {
                synchronized(processingMutex) {
                    processingTasks.remove(task.id)
                }
            }
        }
        
        val duration = System.currentTimeMillis() - startTime
        totalProcessingTime.addAndGet(duration)
        
        _events.emit(QueueEvent.BatchProcessed(successCount, duration))
        updateStats()
    }
    
    private fun handleTaskFailure(task: WriteTask, error: Throwable) {
        val retriedTask = task.copy(
            retryCount = task.retryCount + 1
        )
        
        if (retriedTask.retryCount < retriedTask.maxRetries) {
            val queue = when (retriedTask.priority) {
                StoragePriority.CRITICAL, StoragePriority.HIGH -> highPriorityQueue
                StoragePriority.NORMAL -> normalPriorityQueue
                StoragePriority.LOW, StoragePriority.BACKGROUND -> lowPriorityQueue
            }
            
            queue.put(retriedTask)
            pendingTasks[retriedTask.id] = retriedTask
            
            Log.w(TAG, "Task retry ${retriedTask.retryCount}/${task.maxRetries}: ${task.id}")
        } else {
            failedTasks.incrementAndGet()
            task.callback?.onFailure(task.id, error)
            scope.launch {
                _events.emit(QueueEvent.TaskFailed(task.id, error))
            }
            
            Log.e(TAG, "Task failed after ${task.maxRetries} retries: ${task.id}", error)
        }
    }
    
    private fun pollNextTask(): WriteTask? {
        return highPriorityQueue.poll()
            ?: normalPriorityQueue.poll()
            ?: lowPriorityQueue.poll()
    }
    
    suspend fun flush(): Int {
        var processed = 0
        while (true) {
            val task = pollNextTask() ?: break
            try {
                val success = delegate.handle(task)
                if (success) {
                    processed++
                    task.callback?.onSuccess(task.id)
                    completedTasks.incrementAndGet()
                } else {
                    task.callback?.onFailure(task.id, Exception("Handler returned false"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Task ${task.id} failed during flush", e)
                task.callback?.onFailure(task.id, e)
            }
        }
        
        _stats.value = _stats.value.copy(
            pendingCount = highPriorityQueue.size + normalPriorityQueue.size + lowPriorityQueue.size
        )
        
        return processed
    }
    
    fun cancel(taskId: String): Boolean {
        var removed = false
        
        if (highPriorityQueue.removeIf { it.id == taskId } != null) removed = true
        if (normalPriorityQueue.removeIf { it.id == taskId } != null) removed = true
        if (lowPriorityQueue.removeIf { it.id == taskId } != null) removed = true
        
        if (removed) {
            pendingTasks.remove(taskId)
            _stats.value = _stats.value.copy(
                pendingCount = _stats.value.pendingCount - 1
            )
            Log.d(TAG, "Cancelled task: $taskId")
        }
        
        return removed
    }
    
    fun cancelAll() {
        highPriorityQueue.clear()
        normalPriorityQueue.clear()
        lowPriorityQueue.clear()
        pendingTasks.clear()
        _stats.value = WriteQueueStats()
        
        Log.i(TAG, "Cancelled all pending tasks")
    }
    
    fun getTask(taskId: String): WriteTask? {
        return pendingTasks[taskId] ?: processingTasks[taskId]
    }
    
    fun hasPendingTask(taskId: String): Boolean = pendingTasks.containsKey(taskId)
    
    fun getPendingCount(): Int = pendingTasks.size
    
    fun getProcessingCount(): Int = processingTasks.size
    
    fun generateTaskId(): String {
        return "task_${System.currentTimeMillis()}_${taskIdCounter.incrementAndGet()}"
    }
    
    private fun updateStats() {
        _stats.value = WriteQueueStats(
            pendingCount = highPriorityQueue.size + normalPriorityQueue.size + lowPriorityQueue.size,
            processedCount = completedTasks.get(),
            failedCount = failedTasks.get(),
            totalProcessingTimeMs = totalProcessingTime.get(),
            highPriorityCount = highPriorityQueue.size,
            normalPriorityCount = normalPriorityQueue.size,
            lowPriorityCount = lowPriorityQueue.size
        )
    }
    
    fun getStats(): WriteQueueStats = _stats.value
    
    fun shutdown() {
        isShuttingDown = true
        flushJob?.cancel()
        kotlinx.coroutines.runBlocking {
            flush()
        }
        scope.cancel()
        Log.i(TAG, "PriorityWriteQueue shutdown completed")
    }
}

data class WriteTask(
    val id: String,
    val key: String,
    val data: Any?,
    val priority: StoragePriority,
    val timestamp: Long = System.currentTimeMillis(),
    val callback: WriteCallback? = null,
    val maxRetries: Int = StorageArchitecture.WriteQueue.MAX_RETRY_COUNT,
    val retryCount: Int = 0
)

interface WriteCallback {
    fun onSuccess(taskId: String)
    fun onFailure(taskId: String, error: Throwable)
}

/** 具名 WriteCallback 实现（避免匿名内部类触发 KSP getSimpleName NPE） */
private class DeferredWriteCallback(
    private val original: WriteCallback?,
    private val result: CompletableDeferred<Boolean>
) : WriteCallback {
    override fun onSuccess(taskId: String) {
        original?.onSuccess(taskId)
        result.complete(true)
    }
    override fun onFailure(taskId: String, error: Throwable) {
        original?.onFailure(taskId, error)
        result.complete(false)
    }
}

interface WriteQueueDelegate {
    suspend fun handle(task: WriteTask): Boolean
    fun enqueue(task: WriteTask): Boolean
    fun cancel(taskId: String): Boolean
    fun clear()
    fun shutdown()
}

sealed class QueueEvent {
    data class TaskEnqueued(val taskId: String, val priority: StoragePriority) : QueueEvent()
    data class TaskCompleted(val taskId: String, val durationMs: Long) : QueueEvent()
    data class TaskFailed(val taskId: String, val error: Throwable) : QueueEvent()
    data class BatchProcessed(val count: Int, val durationMs: Long) : QueueEvent()
}

data class WriteQueueStats(
    val pendingCount: Int = 0,
    val processedCount: Long = 0,
    val failedCount: Long = 0,
    val totalProcessingTimeMs: Long = 0,
    val highPriorityCount: Int = 0,
    val normalPriorityCount: Int = 0,
    val lowPriorityCount: Int = 0
)

data class WriteQueueConfig(
    val highPriorityCapacity: Int = StorageArchitecture.WriteQueue.HIGH_PRIORITY_CAPACITY,
    val normalPriorityCapacity: Int = StorageArchitecture.WriteQueue.NORMAL_PRIORITY_CAPACITY,
    val lowPriorityCapacity: Int = StorageArchitecture.WriteQueue.LOW_PRIORITY_CAPACITY,
    val flushIntervalMs: Long = StorageArchitecture.WriteQueue.FLUSH_INTERVAL_MS
)

class DatabaseWriteHandler(
    private val database: OptimizedGameDatabase
) : WriteQueueDelegate {
    
    companion object {
        private const val TAG = "DatabaseWriteHandler"
    }
    
    private val pendingTasks = ConcurrentHashMap<String, WriteTask>()
    
    override suspend fun handle(task: WriteTask): Boolean {
        return try {
            when (task.data) {
                is GameData -> {
                    database.gameDataDao().insert(task.data as GameData)
                }
                is Disciple -> {
                    database.discipleDao().insert(task.data as Disciple)
                }
                is Equipment -> {
                    database.equipmentDao().insert(task.data as Equipment)
                }
                is Manual -> {
                    database.manualDao().insert(task.data as Manual)
                }
                is Pill -> {
                    database.pillDao().insert(task.data as Pill)
                }
                is Material -> {
                    database.materialDao().insert(task.data as Material)
                }
                is Herb -> {
                    database.herbDao().insert(task.data as Herb)
                }
                is Seed -> {
                    database.seedDao().insert(task.data as Seed)
                }
                is BattleLog -> {
                    database.battleLogDao().insert(task.data as BattleLog)
                }
                is GameEvent -> {
                    database.gameEventDao().insert(task.data as GameEvent)
                }
                else -> {
                    Log.w(TAG, "Unknown data type for task ${task.id}")
                    return false
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Database write failed for task ${task.id}", e)
            false
        }
    }
    
    override fun enqueue(task: WriteTask): Boolean {
        pendingTasks[task.id] = task
        return true
    }
    
    override fun cancel(taskId: String): Boolean {
        return pendingTasks.remove(taskId) != null
    }
    
    override fun clear() {
        pendingTasks.clear()
    }
    
    override fun shutdown() {
    }
}
