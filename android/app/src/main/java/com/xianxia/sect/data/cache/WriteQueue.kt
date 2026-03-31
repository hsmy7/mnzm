package com.xianxia.sect.data.cache

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

object WriteQueueConfig {
    const val MIN_CAPACITY = 32
    const val MAX_CAPACITY = 4096
    const val SCALE_UP_THRESHOLD = 0.8
    const val SCALE_DOWN_THRESHOLD = 0.3
    const val SCALE_UP_FACTOR = 2
    const val SCALE_DOWN_FACTOR = 0.5
    const val ADAPTIVE_CHECK_INTERVAL_MS = 5000L
    const val MAX_BATCH_SIZE = 500
    const val MIN_BATCH_SIZE = 10
    const val DYNAMIC_BATCH_THRESHOLD_HIGH = 0.9
    const val DYNAMIC_BATCH_THRESHOLD_LOW = 0.5
}

class WriteQueue(
    private val config: CacheConfig = CacheConfig.DEFAULT,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "WriteQueue"
    }

    private var currentCapacity = AtomicInteger(WriteQueueConfig.MIN_CAPACITY)
    private val channel = Channel<WriteTask>(capacity = Channel.UNLIMITED)
    private val overflowBuffer = ConcurrentLinkedQueue<WriteTask>()
    private val _queueSize = MutableStateFlow(0)
    val queueSize: StateFlow<Int> = _queueSize.asStateFlow()

    private val isProcessing = AtomicBoolean(false)
    private val processedCount = AtomicLong(0)
    private val errorCount = AtomicLong(0)
    private val batchSuccessCount = AtomicLong(0)
    private val adaptiveResizeCount = AtomicLong(0)
    private var processingJob: Job? = null
    private var adaptiveJob: Job? = null

    private var writeHandler: (suspend (WriteTask) -> Boolean)? = null
    private var batchWriteHandler: (suspend (List<WriteTask>) -> Int)? = null

    fun setWriteHandler(handler: suspend (WriteTask) -> Boolean) {
        this.writeHandler = handler
    }

    fun setBatchWriteHandler(handler: suspend (List<WriteTask>) -> Int) {
        this.batchWriteHandler = handler
    }

    fun enqueue(task: WriteTask): Boolean {
        return try {
            val currentSize = _queueSize.value
            val capacity = currentCapacity.get()
            
            if (currentSize >= capacity * WriteQueueConfig.SCALE_UP_THRESHOLD) {
                scaleUpCapacity()
            }
            
            channel.trySendBlocking(task)
            _queueSize.value = _queueSize.value + 1
            Log.d(TAG, "Enqueued task: ${task.key}, flag=${task.flag}, queueSize=${_queueSize.value}, capacity=$capacity")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enqueue task: ${task.key}", e)
            false
        }
    }

    fun enqueueBatch(tasks: List<WriteTask>): Int {
        var count = 0
        tasks.forEach { task ->
            if (enqueue(task)) {
                count++
            }
        }
        Log.d(TAG, "Enqueued $count/${tasks.size} tasks")
        return count
    }
    
    fun enqueuePriority(task: WriteTask): Boolean {
        overflowBuffer.add(task)
        _queueSize.value = _queueSize.value + 1
        Log.d(TAG, "Priority enqueued task: ${task.key}")
        return true
    }

    private fun scaleUpCapacity() {
        val current = currentCapacity.get()
        if (current < WriteQueueConfig.MAX_CAPACITY) {
            val newCapacity = (current * WriteQueueConfig.SCALE_UP_FACTOR).toInt()
                .coerceIn(WriteQueueConfig.MIN_CAPACITY, WriteQueueConfig.MAX_CAPACITY)
            if (currentCapacity.compareAndSet(current, newCapacity)) {
                adaptiveResizeCount.incrementAndGet()
                Log.i(TAG, "Scaled up write queue capacity: $current -> $newCapacity")
            }
        }
    }
    
    private fun scaleDownCapacity() {
        val current = currentCapacity.get()
        if (current > WriteQueueConfig.MIN_CAPACITY) {
            val newCapacity = (current * WriteQueueConfig.SCALE_DOWN_FACTOR).toInt()
                .coerceIn(WriteQueueConfig.MIN_CAPACITY, WriteQueueConfig.MAX_CAPACITY)
            if (currentCapacity.compareAndSet(current, newCapacity)) {
                adaptiveResizeCount.incrementAndGet()
                Log.i(TAG, "Scaled down write queue capacity: $current -> $newCapacity")
            }
        }
    }
    
    private fun calculateDynamicBatchSize(): Int {
        val currentSize = _queueSize.value
        val capacity = currentCapacity.get()
        val utilization = currentSize.toDouble() / capacity
        
        return when {
            utilization > WriteQueueConfig.DYNAMIC_BATCH_THRESHOLD_HIGH -> {
                (WriteQueueConfig.MAX_BATCH_SIZE * utilization).toInt()
                    .coerceIn(WriteQueueConfig.MIN_BATCH_SIZE, WriteQueueConfig.MAX_BATCH_SIZE)
            }
            utilization > WriteQueueConfig.DYNAMIC_BATCH_THRESHOLD_LOW -> {
                WriteQueueConfig.MIN_BATCH_SIZE
            }
            else -> {
                (WriteQueueConfig.MIN_BATCH_SIZE + (WriteQueueConfig.MAX_BATCH_SIZE - WriteQueueConfig.MIN_BATCH_SIZE) * utilization).toInt()
                    .coerceIn(WriteQueueConfig.MIN_BATCH_SIZE, WriteQueueConfig.MAX_BATCH_SIZE)
            }
        }
    }

    fun startProcessing() {
        if (isProcessing.getAndSet(true)) {
            Log.w(TAG, "Write queue already processing")
            return
        }

        processingJob = scope.launch {
            Log.i(TAG, "Write queue processing started")
            
            val batch = mutableListOf<WriteTask>()
            var lastFlushTime = System.currentTimeMillis()

            while (isActive) {
                try {
                    val priorityTask = overflowBuffer.poll()
                    if (priorityTask != null) {
                        batch.add(0, priorityTask)
                        _queueSize.value = (_queueSize.value - 1).coerceAtLeast(0)
                    }
                    
                    val task = channel.tryReceive().getOrNull()
                    
                    if (task != null) {
                        batch.add(task)
                        _queueSize.value = (_queueSize.value - 1).coerceAtLeast(0)
                    }

                    val dynamicBatchSize = calculateDynamicBatchSize()
                    val shouldFlush = batch.isNotEmpty() && (
                        batch.size >= dynamicBatchSize ||
                        batch.size >= currentCapacity.get() ||
                        System.currentTimeMillis() - lastFlushTime >= config.writeDelayMs ||
                        task == null
                    )

                    if (shouldFlush) {
                        processBatch(batch.toList())
                        batch.clear()
                        lastFlushTime = System.currentTimeMillis()
                    }

                    if (task == null && batch.isEmpty()) {
                        delay(100)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in write queue processing", e)
                    errorCount.incrementAndGet()
                }
            }
        }
        
        startAdaptiveMonitoring()
    }
    
    private fun startAdaptiveMonitoring() {
        adaptiveJob = scope.launch {
            while (isActive) {
                delay(WriteQueueConfig.ADAPTIVE_CHECK_INTERVAL_MS)
                
                val currentSize = _queueSize.value
                val capacity = currentCapacity.get()
                val utilization = currentSize.toDouble() / capacity
                
                if (utilization < WriteQueueConfig.SCALE_DOWN_THRESHOLD && capacity > WriteQueueConfig.MIN_CAPACITY) {
                    scaleDownCapacity()
                }
                
                Log.d(TAG, "Adaptive monitoring: size=$currentSize, capacity=$capacity, utilization=${"%.2f".format(utilization * 100)}%)")
            }
        }
    }

    private suspend fun processBatch(tasks: List<WriteTask>) {
        if (tasks.isEmpty()) return

        Log.d(TAG, "Processing batch of ${tasks.size} tasks")

        if (batchWriteHandler != null) {
            try {
                val successCount = batchWriteHandler!!(tasks)
                processedCount.addAndGet(successCount.toLong())
                batchSuccessCount.incrementAndGet()
                Log.d(TAG, "Batch processed: $successCount/${tasks.size} successful")
            } catch (e: Exception) {
                Log.e(TAG, "Batch processing failed", e)
                errorCount.addAndGet(tasks.size.toLong())
            }
        } else if (writeHandler != null) {
            var successCount = 0
            for (task in tasks) {
                try {
                    if (writeHandler!!(task)) {
                        successCount++
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process task: ${task.key}", e)
                    errorCount.incrementAndGet()
                }
            }
            processedCount.addAndGet(successCount.toLong())
            Log.d(TAG, "Individual processing: $successCount/${tasks.size} successful")
        } else {
            Log.w(TAG, "No write handler set, skipping batch")
        }
    }

    fun stopProcessing() {
        adaptiveJob?.cancel()
        adaptiveJob = null
        processingJob?.cancel()
        processingJob = null
        isProcessing.set(false)
        Log.i(TAG, "Write queue processing stopped")
    }

    suspend fun flush(): Int {
        val remaining = mutableListOf<WriteTask>()
        var task = channel.tryReceive().getOrNull()
        while (task != null) {
            remaining.add(task)
            _queueSize.value = (_queueSize.value - 1).coerceAtLeast(0)
            task = channel.tryReceive().getOrNull()
        }

        if (remaining.isNotEmpty()) {
            processBatch(remaining)
        }

        Log.d(TAG, "Flushed ${remaining.size} tasks")
        return remaining.size
    }

    fun clear() {
        var cleared = 0
        while (channel.tryReceive().getOrNull() != null) {
            cleared++
        }
        _queueSize.value = 0
        Log.d(TAG, "Cleared $cleared tasks from queue")
    }

    fun getStats(): WriteQueueStats {
        return WriteQueueStats(
            queueSize = _queueSize.value,
            processedCount = processedCount.get(),
            errorCount = errorCount.get(),
            isProcessing = isProcessing.get(),
            currentCapacity = currentCapacity.get(),
            batchSuccessCount = batchSuccessCount.get(),
            adaptiveResizeCount = adaptiveResizeCount.get()
        )
    }
    
    fun getCapacity(): Int = currentCapacity.get()
    
    fun getUtilization(): Double {
        val size = _queueSize.value
        val capacity = currentCapacity.get()
        return if (capacity > 0) size.toDouble() / capacity else 0.0
    }
}

data class WriteQueueStats(
    val queueSize: Int,
    val processedCount: Long,
    val errorCount: Long,
    val isProcessing: Boolean,
    val currentCapacity: Int = 0,
    val batchSuccessCount: Long = 0,
    val adaptiveResizeCount: Long = 0
) {
    val successRate: Double
        get() = if (processedCount > 0) {
            (processedCount - errorCount).toDouble() / processedCount
        } else 0.0
    
    val utilizationRate: Double
        get() = if (currentCapacity > 0) {
            queueSize.toDouble() / currentCapacity
        } else 0.0
}
