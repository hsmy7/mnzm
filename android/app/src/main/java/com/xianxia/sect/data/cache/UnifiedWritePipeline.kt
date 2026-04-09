package com.xianxia.sect.data.cache

import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class PendingEntry(
    val value: Any?,
    val flag: DirtyFlag,
    val timestamp: Long = System.currentTimeMillis(),
    var coalesceCount: Int = 0
)

data class WriteTask(
    val key: String,
    val value: Any?,
    val flag: DirtyFlag,
    val timestamp: Long = System.currentTimeMillis()
)

data class PipelineStats(
    val pendingCount: Int,
    val dirtyKeyCount: Int,
    val batchSize: Int,
    val totalCoalesced: Long,
    val totalFlushed: Long,
    val totalWritten: Long
)

class UnifiedWritePipeline(
    private val coalesceWindowMs: Long = 500L,
    private val maxPendingPerKey: Int = 10,
    private val batchSize: Int = 100,
    private val flushIntervalMs: Long = 1000L,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "UnifiedWritePipeline"
    }

    private val pendingMap = ConcurrentHashMap<String, PendingEntry>()
    private val dirtyKeys = ConcurrentHashMap.newKeySet<String>()
    private val dirtyFlags = ConcurrentHashMap<String, DirtyFlag>()
    private val batchBuffer = ConcurrentLinkedQueue<WriteTask>()

    private val totalCoalesced = AtomicLong(0)
    private val totalFlushed = AtomicLong(0)
    private val totalWritten = AtomicLong(0)

    private var flushJob: Job? = null
    private var writeHandler: ((List<WriteTask>) -> Unit)? = null

    fun startProcessing() {
        stopProcessing()
        flushJob = scope.launch {
            while (isActive) {
                delay(flushIntervalMs)
                try {
                    flushInternal()
                } catch (e: Exception) {
                    Log.e(TAG, "Error during periodic flush", e)
                }
            }
        }
    }

    fun stopProcessing() {
        flushJob?.cancel()
        flushJob = null
    }

    fun setBatchWriteHandler(handler: (List<WriteTask>) -> Unit) {
        writeHandler = handler
    }

    fun enqueue(key: String, value: Any?, flag: DirtyFlag = DirtyFlag.UPDATE): Boolean {
        val existing = pendingMap[key]

        if (existing != null) {
            // Coalesce: merge consecutive writes to same key within window
            val elapsed = System.currentTimeMillis() - existing.timestamp
            if (elapsed < coalesceWindowMs && existing.coalesceCount < maxPendingPerKey) {
                pendingMap[key] = existing.copy(
                    value = value,
                    flag = flag,
                    timestamp = System.currentTimeMillis(),
                    coalesceCount = existing.coalesceCount + 1
                )
                totalCoalesced.incrementAndGet()
                return true
            }
        }

        pendingMap[key] = PendingEntry(value = value, flag = flag)
        dirtyKeys.add(key)
        dirtyFlags[key] = flag
        return true
    }

    fun markDirty(key: String, flag: DirtyFlag = DirtyFlag.UPDATE, data: Any? = null) {
        enqueue(key, data, flag)
    }

    fun markInsert(key: String, data: Any?) {
        enqueue(key, data, DirtyFlag.INSERT)
    }

    fun markUpdate(key: String, data: Any?) {
        enqueue(key, data, DirtyFlag.UPDATE)
    }

    fun markDelete(key: String) {
        enqueue(key, null, DirtyFlag.DELETE)
        dirtyKeys.remove(key)
        pendingMap.remove(key)
    }

    fun isDirty(key: String): Boolean = dirtyKeys.contains(key)

    fun isEmpty(): Boolean = pendingMap.isEmpty() && dirtyKeys.isEmpty()

    fun flush(): FlushResult {
        return runBlocking { flushInternal() }
    }

    private suspend fun flushInternal(): FlushResult {
        if (pendingMap.isEmpty()) return FlushResult.NoChanges

        val tasks = mutableListOf<WriteTask>()
        val iterator = pendingMap.iterator()
        while (iterator.hasNext()) {
            val (key, entry) = iterator.next()
            tasks.add(WriteTask(key, entry.value, entry.flag, entry.timestamp))
            iterator.remove()
        }

        dirtyKeys.clear()
        dirtyFlags.clear()
        totalFlushed.addAndGet(tasks.size.toLong())

        if (tasks.isNotEmpty()) {
            batchBuffer.addAll(tasks)

            val handler = writeHandler
            if (handler != null) {
                try {
                    handler(tasks)
                    totalWritten.addAndGet(tasks.size.toLong())
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing batch write", e)
                    return FlushResult.Error(e.message ?: "Unknown error", e)
                }
            }
        }

        totalFlushed.incrementAndGet()
        return FlushResult.Success(tasks.size)
    }

    fun drainDirty(): List<WriteTask> {
        val result = mutableListOf<WriteTask>()
        val iterator = pendingMap.iterator()
        while (iterator.hasNext()) {
            val (key, entry) = iterator.next()
            if (dirtyKeys.contains(key)) {
                result.add(WriteTask(key, entry.value, entry.flag, entry.timestamp))
                iterator.remove()
            }
        }
        dirtyKeys.clear()
        return result
    }

    fun clearDirty(key: String?) {
        if (key != null) {
            dirtyKeys.remove(key)
            pendingMap.remove(key)
        } else {
            dirtyKeys.clear()
            pendingMap.clear()
        }
    }

    fun getStats(): PipelineStats {
        return PipelineStats(
            pendingCount = pendingMap.size,
            dirtyKeyCount = dirtyKeys.size,
            batchSize = batchBuffer.size,
            totalCoalesced = totalCoalesced.get(),
            totalFlushed = totalFlushed.get(),
            totalWritten = totalWritten.get()
        )
    }

    fun shutdown() {
        stopProcessing()
        // Use Dispatchers.Default to avoid deadlock when caller is already on pipeline's IO dispatcher.
        // Timeout prevents indefinite blocking if flush gets stuck.
        try {
            runBlocking(Dispatchers.Default) {
                withTimeout(5_000L) {
                    flushInternal()
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Shutdown flush timed out after 5s, proceeding with cleanup")
        } catch (e: Exception) {
            Log.w(TAG, "Error during shutdown flush", e)
        }
        pendingMap.clear()
        dirtyKeys.clear()
        dirtyFlags.clear()
        batchBuffer.clear()
    }
}
