package com.xianxia.sect.data.sync

import android.util.Log
import com.xianxia.sect.data.cache.CacheKey
import com.xianxia.sect.data.concurrent.StripedLockManager
import com.xianxia.sect.data.serialization.SmartSerializer
import com.xianxia.sect.data.serialization.SerializationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object DeltaSyncConfig {
    const val BATCH_SIZE = 100
    const val SYNC_INTERVAL_MS = 5000L
    const val MAX_PENDING_CHANGES = 10000
    const val CHECKPOINT_INTERVAL = 100
    const val RETRY_COUNT = 3
    const val RETRY_DELAY_MS = 100L
}

enum class ChangeType {
    INSERT,
    UPDATE,
    DELETE
}

enum class SyncStatus {
    IDLE,
    SYNCING,
    PAUSED,
    ERROR
}

data class DataChange(
    val key: CacheKey,
    val type: ChangeType,
    val oldValue: Any? = null,
    val newValue: Any? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val version: Long = 0,
    val checksum: String = ""
)

data class DeltaBlock(
    val id: String,
    val changes: List<DataChange>,
    val timestamp: Long,
    val checksum: String,
    val compressed: Boolean = false
)

data class SyncCheckpoint(
    val id: String,
    val timestamp: Long,
    val changeCount: Long,
    val lastChangeId: String,
    val checksum: String
)

data class SyncStats(
    val totalChanges: Long = 0,
    val pendingChanges: Int = 0,
    val syncedChanges: Long = 0,
    val failedChanges: Long = 0,
    val lastSyncTime: Long = 0,
    val syncDurationMs: Long = 0,
    val status: SyncStatus = SyncStatus.IDLE
)

data class SyncResult(
    val success: Boolean,
    val syncedCount: Int,
    val failedCount: Int,
    val durationMs: Long,
    val errors: List<String> = emptyList()
)

class DeltaSyncEngine(
    private val context: android.content.Context? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "DeltaSyncEngine"
        private const val DELTA_DIR = "delta_sync"
    }
    
    private val serializer = SmartSerializer()
    private val lockManager = StripedLockManager()
    
    private val pendingChanges = ConcurrentHashMap<String, DataChange>()
    private val checkpoints = ConcurrentHashMap<String, SyncCheckpoint>()
    
    private val deltaDir: java.io.File? = context?.let { 
        java.io.File(it.filesDir, DELTA_DIR).apply { mkdirs() } 
    }
    
    private val versionCounter = AtomicLong(0)
    private val totalChanges = AtomicLong(0)
    private val syncedChanges = AtomicLong(0)
    private val failedChanges = AtomicLong(0)
    
    private val _syncStats = MutableStateFlow(SyncStats())
    val syncStats: StateFlow<SyncStats> = _syncStats.asStateFlow()
    
    private val _syncStatus = MutableStateFlow(SyncStatus.IDLE)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()
    
    private var syncJob: Job? = null
    private var isShuttingDown = false
    
    private val changeListeners = mutableListOf<ChangeListListener>()
    
    interface ChangeListListener {
        fun onChangesApplied(changes: List<DataChange>)
        fun onSyncComplete(result: SyncResult)
    }
    
    init {
        startAutoSync()
    }
    
    private fun startAutoSync() {
        syncJob = scope.launch {
            while (isActive && !isShuttingDown) {
                delay(DeltaSyncConfig.SYNC_INTERVAL_MS)
                if (pendingChanges.isNotEmpty()) {
                    syncPendingChanges()
                }
            }
        }
    }
    
    fun recordInsert(key: CacheKey, value: Any) {
        val change = DataChange(
            key = key,
            type = ChangeType.INSERT,
            newValue = value,
            version = versionCounter.incrementAndGet(),
            checksum = calculateChecksum(value)
        )
        
        addChange(change)
    }
    
    fun recordUpdate(key: CacheKey, oldValue: Any?, newValue: Any) {
        val change = DataChange(
            key = key,
            type = ChangeType.UPDATE,
            oldValue = oldValue,
            newValue = newValue,
            version = versionCounter.incrementAndGet(),
            checksum = calculateChecksum(newValue)
        )
        
        addChange(change)
    }
    
    fun recordDelete(key: CacheKey, oldValue: Any?) {
        val change = DataChange(
            key = key,
            type = ChangeType.DELETE,
            oldValue = oldValue,
            version = versionCounter.incrementAndGet()
        )
        
        addChange(change)
    }
    
    private fun addChange(change: DataChange) {
        val keyStr = change.key.toString()
        
        lockManager.withWriteLock(keyStr) {
            val existing = pendingChanges[keyStr]
            
            when {
                existing == null -> {
                    pendingChanges[keyStr] = change
                }
                existing.type == ChangeType.INSERT && change.type == ChangeType.UPDATE -> {
                    pendingChanges[keyStr] = existing.copy(
                        newValue = change.newValue,
                        version = change.version,
                        checksum = change.checksum
                    )
                }
                existing.type == ChangeType.INSERT && change.type == ChangeType.DELETE -> {
                    pendingChanges.remove(keyStr)
                }
                existing.type == ChangeType.UPDATE && change.type == ChangeType.UPDATE -> {
                    pendingChanges[keyStr] = existing.copy(
                        newValue = change.newValue,
                        version = change.version,
                        checksum = change.checksum
                    )
                }
                existing.type == ChangeType.UPDATE && change.type == ChangeType.DELETE -> {
                    pendingChanges[keyStr] = change.copy(
                        oldValue = existing.oldValue
                    )
                }
                else -> {
                    pendingChanges[keyStr] = change
                }
            }
            
            totalChanges.incrementAndGet()
        }
        
        if (pendingChanges.size >= DeltaSyncConfig.MAX_PENDING_CHANGES) {
            scope.launch { syncPendingChanges() }
        }
    }
    
    suspend fun syncPendingChanges(): SyncResult {
        if (_syncStatus.value == SyncStatus.SYNCING) {
            return SyncResult(false, 0, 0, 0, listOf("Sync already in progress"))
        }
        
        _syncStatus.value = SyncStatus.SYNCING
        val startTime = System.currentTimeMillis()
        
        val changesToSync = mutableListOf<DataChange>()
        
        lockManager.withWriteLock("pending_changes") {
            changesToSync.addAll(pendingChanges.values)
            pendingChanges.clear()
        }
        
        if (changesToSync.isEmpty()) {
            _syncStatus.value = SyncStatus.IDLE
            return SyncResult(true, 0, 0, 0)
        }
        
        val errors = mutableListOf<String>()
        var syncedCount = 0
        var failedCount = 0
        
        changesToSync.chunked(DeltaSyncConfig.BATCH_SIZE).forEach { batch ->
            val result = syncBatch(batch)
            
            if (result.success) {
                syncedCount += result.syncedCount
                syncedChanges.addAndGet(result.syncedCount.toLong())
            } else {
                failedCount += batch.size
                failedChanges.addAndGet(batch.size.toLong())
                errors.addAll(result.errors)
                
                batch.forEach { change ->
                    pendingChanges[change.key.toString()] = change
                }
            }
        }
        
        val duration = System.currentTimeMillis() - startTime
        
        updateStats(duration)
        
        _syncStatus.value = if (failedCount > 0) SyncStatus.ERROR else SyncStatus.IDLE
        
        val result = SyncResult(
            success = failedCount == 0,
            syncedCount = syncedCount,
            failedCount = failedCount,
            durationMs = duration,
            errors = errors
        )
        
        notifyListeners(result)
        
        return result
    }
    
    private suspend fun syncBatch(batch: List<DataChange>): SyncResult {
        var retries = 0
        var lastError: String? = null
        
        while (retries < DeltaSyncConfig.RETRY_COUNT) {
            try {
                val deltaBlock = createDeltaBlock(batch)
                
                val success = persistDeltaBlock(deltaBlock)
                
                if (success) {
                    return SyncResult(true, batch.size, 0, 0)
                }
            } catch (e: Exception) {
                lastError = e.message
                Log.w(TAG, "Sync batch attempt ${retries + 1} failed: ${e.message}")
            }
            
            retries++
            delay(DeltaSyncConfig.RETRY_DELAY_MS * retries)
        }
        
        return SyncResult(
            success = false,
            syncedCount = 0,
            failedCount = batch.size,
            durationMs = 0,
            errors = listOf(lastError ?: "Unknown error")
        )
    }
    
    private fun createDeltaBlock(changes: List<DataChange>): DeltaBlock {
        val id = generateBlockId()
        val timestamp = System.currentTimeMillis()
        val checksum = calculateBlockChecksum(changes)
        
        return DeltaBlock(
            id = id,
            changes = changes,
            timestamp = timestamp,
            checksum = checksum
        )
    }
    
    private suspend fun persistDeltaBlock(block: DeltaBlock): Boolean {
        val dir = deltaDir ?: return true
        
        return try {
            val serialized = serializer.serialize(block, SerializationContext(needCompact = true))
            val file = java.io.File(dir, "${block.id}.delta")
            file.writeBytes(serialized.data)
            
            Log.d(TAG, "Persisted delta block: ${block.id}, size: ${serialized.data.size}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist delta block", e)
            false
        }
    }
    
    fun createCheckpoint(): SyncCheckpoint {
        val id = "checkpoint_${System.currentTimeMillis()}"
        val timestamp = System.currentTimeMillis()
        val changeCount = totalChanges.get()
        
        val checksum = calculateCheckpointChecksum()
        
        val checkpoint = SyncCheckpoint(
            id = id,
            timestamp = timestamp,
            changeCount = changeCount,
            lastChangeId = pendingChanges.values.maxByOrNull { it.version }?.key?.toString() ?: "",
            checksum = checksum
        )
        
        checkpoints[id] = checkpoint
        
        return checkpoint
    }
    
    fun restoreFromCheckpoint(checkpointId: String): Boolean {
        val checkpoint = checkpoints[checkpointId] ?: return false
        
        return try {
            pendingChanges.clear()
            versionCounter.set(checkpoint.changeCount)
            
            Log.i(TAG, "Restored from checkpoint: $checkpointId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore from checkpoint: $checkpointId", e)
            false
        }
    }
    
    private fun calculateChecksum(value: Any): String {
        return try {
            val serialized = serializer.serialize(value)
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(serialized.data)
            hash.joinToString("") { "%02x".format(it) }.take(16)
        } catch (e: Exception) {
            ""
        }
    }
    
    private fun calculateBlockChecksum(changes: List<DataChange>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        changes.forEach { change ->
            digest.update(change.key.toString().toByteArray())
            digest.update(change.type.name.toByteArray())
            digest.update(change.version.toString().toByteArray())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(16)
    }
    
    private fun calculateCheckpointChecksum(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(versionCounter.get().toString().toByteArray())
        digest.update(pendingChanges.size.toString().toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }.take(16)
    }
    
    private fun generateBlockId(): String {
        return "block_${System.currentTimeMillis()}_${versionCounter.get()}"
    }
    
    private fun updateStats(durationMs: Long) {
        _syncStats.value = SyncStats(
            totalChanges = totalChanges.get(),
            pendingChanges = pendingChanges.size,
            syncedChanges = syncedChanges.get(),
            failedChanges = failedChanges.get(),
            lastSyncTime = System.currentTimeMillis(),
            syncDurationMs = durationMs,
            status = _syncStatus.value
        )
    }
    
    fun addListener(listener: ChangeListListener) {
        synchronized(changeListeners) {
            changeListeners.add(listener)
        }
    }
    
    fun removeListener(listener: ChangeListListener) {
        synchronized(changeListeners) {
            changeListeners.remove(listener)
        }
    }
    
    private fun notifyListeners(result: SyncResult) {
        synchronized(changeListeners) {
            changeListeners.forEach { listener ->
                listener.onSyncComplete(result)
            }
        }
    }
    
    fun getPendingChangeCount(): Int = pendingChanges.size
    
    fun getPendingChanges(): List<DataChange> = pendingChanges.values.toList()
    
    fun hasPendingChanges(): Boolean = pendingChanges.isNotEmpty()
    
    fun clearPendingChanges() {
        pendingChanges.clear()
    }
    
    fun pauseSync() {
        _syncStatus.value = SyncStatus.PAUSED
    }
    
    fun resumeSync() {
        _syncStatus.value = SyncStatus.IDLE
    }
    
    fun getStats(): SyncStats = _syncStats.value
    
    fun shutdown() {
        isShuttingDown = true
        
        syncJob?.cancel()
        
        runBlocking {
            if (pendingChanges.isNotEmpty()) {
                syncPendingChanges()
            }
        }
        
        pendingChanges.clear()
        checkpoints.clear()
        changeListeners.clear()
        
        scope.cancel()
        Log.i(TAG, "DeltaSyncEngine shutdown completed")
    }
}

class DiffCalculator {
    companion object {
        private const val TAG = "DiffCalculator"
    }
    
    fun <T> calculateDiff(oldValue: T?, newValue: T?, equals: (T, T) -> Boolean = { a, b -> a == b }): DiffResult<T> {
        return when {
            oldValue == null && newValue == null -> DiffResult.NoChange()
            oldValue == null -> DiffResult.Created(newValue!!)
            newValue == null -> DiffResult.Deleted(oldValue!!)
            equals(oldValue, newValue) -> DiffResult.NoChange()
            else -> DiffResult.Modified(oldValue!!, newValue!!)
        }
    }
    
    fun <K, V> calculateMapDiff(
        oldMap: Map<K, V>,
        newMap: Map<K, V>,
        equals: (V, V) -> Boolean = { a, b -> a == b }
    ): MapDiffResult<K, V> {
        val added = mutableMapOf<K, V>()
        val removed = mutableMapOf<K, V>()
        val modified = mutableMapOf<K, Pair<V, V>>()
        val unchanged = mutableMapOf<K, V>()
        
        oldMap.forEach { (key, oldValue) ->
            val newValue = newMap[key]
            when {
                newValue == null -> removed[key] = oldValue
                equals(oldValue, newValue) -> unchanged[key] = oldValue
                else -> modified[key] = oldValue to newValue!!
            }
        }
        
        newMap.forEach { (key, value) ->
            if (!oldMap.containsKey(key)) {
                added[key] = value
            }
        }
        
        return MapDiffResult(added, removed, modified, unchanged)
    }
    
    fun <T> calculateListDiff(
        oldList: List<T>,
        newList: List<T>,
        equals: (T, T) -> Boolean = { a, b -> a == b }
    ): ListDiffResult<T> {
        val added = mutableListOf<T>()
        val removed = mutableListOf<T>()
        val unchanged = mutableListOf<T>()
        
        val oldSet = oldList.toMutableList()
        
        newList.forEach { item ->
            val index = oldSet.indexOfFirst { equals(it, item) }
            if (index >= 0) {
                unchanged.add(item)
                oldSet.removeAt(index)
            } else {
                added.add(item)
            }
        }
        
        removed.addAll(oldSet)
        
        return ListDiffResult(added, removed, unchanged)
    }
}

sealed class DiffResult<T> {
    data class NoChange<T>(val value: Unit = Unit) : DiffResult<T>()
    data class Created<T>(val value: T) : DiffResult<T>()
    data class Deleted<T>(val value: T) : DiffResult<T>()
    data class Modified<T>(val oldValue: T, val newValue: T) : DiffResult<T>()
}

data class MapDiffResult<K, V>(
    val added: Map<K, V>,
    val removed: Map<K, V>,
    val modified: Map<K, Pair<V, V>>,
    val unchanged: Map<K, V>
)

data class ListDiffResult<T>(
    val added: List<T>,
    val removed: List<T>,
    val unchanged: List<T>
)
