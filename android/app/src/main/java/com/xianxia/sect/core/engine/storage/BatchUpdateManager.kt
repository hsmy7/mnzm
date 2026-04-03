package com.xianxia.sect.core.engine.storage

import android.util.Log
import kotlinx.coroutines.*

class BatchUpdateManager<T>(
    private val delayMs: Long = 100,
    private val maxBatchSize: Int = 50,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    private val onFlush: suspend (Map<String, T>) -> Unit = {}
) {
    companion object {
        private const val TAG = "BatchUpdateManager"
    }
    
    private val lock = Any()
    private val pendingUpdates = mutableMapOf<String, T>()
    private var updateJob: Job? = null
    private var pollingJob: Job? = null
    private var isRunning = false
    
    val pendingCount: Int get() = synchronized(lock) { pendingUpdates.size }
    
    fun scheduleUpdate(id: String, item: T) {
        synchronized(lock) {
            pendingUpdates[id] = item
            
            if (pendingUpdates.size >= maxBatchSize) {
                triggerFlush()
            } else {
                scheduleFlush()
            }
        }
    }
    
    fun scheduleBatch(updates: Map<String, T>) {
        synchronized(lock) {
            pendingUpdates.putAll(updates)
            
            if (pendingUpdates.size >= maxBatchSize) {
                triggerFlush()
            } else {
                scheduleFlush()
            }
        }
    }
    
    private fun scheduleFlush() {
        if (updateJob?.isActive != true) {
            updateJob = scope.launch {
                delay(delayMs)
                doFlush()
            }
        }
    }
    
    private fun triggerFlush() {
        updateJob?.cancel()
        updateJob = scope.launch {
            doFlush()
        }
    }
    
    private suspend fun doFlush() {
        val updates: Map<String, T>
        synchronized(lock) {
            updates = pendingUpdates.toMap()
            pendingUpdates.clear()
        }
        
        if (updates.isNotEmpty()) {
            try {
                onFlush(updates)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to flush updates", e)
            }
        }
    }
    
    suspend fun flush(): Map<String, T> {
        updateJob?.cancelAndJoin()
        updateJob = null
        
        val updates: Map<String, T>
        synchronized(lock) {
            updates = pendingUpdates.toMap()
            pendingUpdates.clear()
        }
        
        if (updates.isNotEmpty()) {
            try {
                onFlush(updates)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to flush updates", e)
            }
        }
        
        return updates
    }
    
    fun flushSync(): Map<String, T> {
        runBlocking {
            updateJob?.cancelAndJoin()
        }
        updateJob = null
        
        val updates: Map<String, T>
        synchronized(lock) {
            updates = pendingUpdates.toMap()
            pendingUpdates.clear()
        }
        return updates
    }
    
    fun start() {
        if (isRunning) return
        isRunning = true
        
        pollingJob = scope.launch {
            while (isActive && isRunning) {
                delay(delayMs)
                synchronized(lock) {
                    if (pendingUpdates.isNotEmpty()) {
                        triggerFlush()
                    }
                }
            }
        }
    }
    
    fun stop() {
        isRunning = false
        pollingJob?.cancel()
        pollingJob = null
        updateJob?.cancel()
        updateJob = null
    }
    
    fun clear() {
        synchronized(lock) {
            pendingUpdates.clear()
        }
    }
    
    fun destroy() {
        stop()
        clear()
        scope.cancel()
    }
}

class BatchOperationManager<T, R>(
    private val batchSize: Int = 50,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    private val processBatch: suspend (List<T>) -> List<R>
) {
    private val lock = Any()
    private val pendingItems = mutableListOf<T>()
    
    suspend fun add(item: T): List<R>? {
        val items: List<T>? = synchronized(lock) {
            pendingItems.add(item)
            if (pendingItems.size >= batchSize) {
                val result = pendingItems.toList()
                pendingItems.clear()
                result
            } else {
                null
            }
        }
        return items?.let { processBatch(it) }
    }
    
    suspend fun addAll(items: List<T>): List<R>? {
        val toProcess: List<T>? = synchronized(lock) {
            pendingItems.addAll(items)
            if (pendingItems.size >= batchSize) {
                val result = pendingItems.toList()
                pendingItems.clear()
                result
            } else {
                null
            }
        }
        return toProcess?.let { processBatch(it) }
    }
    
    suspend fun flush(): List<R> {
        val items: List<T> = synchronized(lock) {
            if (pendingItems.isEmpty()) {
                return emptyList()
            }
            val result = pendingItems.toList()
            pendingItems.clear()
            result
        }
        return processBatch(items)
    }
    
    fun clear() {
        synchronized(lock) {
            pendingItems.clear()
        }
    }
}
