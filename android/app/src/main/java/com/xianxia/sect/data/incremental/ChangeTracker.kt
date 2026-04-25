package com.xianxia.sect.data.incremental

import android.util.Log
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

// ==================== 具名工具类（避免匿名内部类触发 KSP getSimpleName NPE）====================
private class BoundedLinkedHashMap<V>(private val maxSize: Int) : LinkedHashMap<String, V>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?): Boolean = size > maxSize
}

enum class ChangeType {
    CREATED,
    UPDATED,
    DELETED
}

enum class DataType(val prefix: String) {
    GAME_DATA("game"),
    DISCIPLE("disciple"),
    EQUIPMENT("equipment"),
    MANUAL("manual"),
    PILL("pill"),
    MATERIAL("material"),
    HERB("herb"),
    SEED("seed"),
    TEAM("team"),
    EVENT("event"),
    BATTLE_LOG("battle_log"),
    ALLIANCE("alliance"),
    FORGE_SLOT("forge_slot"),
    ALCHEMY_SLOT("alchemy_slot"),
    PRODUCTION_SLOT("production_slot")
}

data class DataChange(
    val key: String,
    val dataType: DataType,
    val entityId: String,
    val changeType: ChangeType,
    val oldValue: Any?,
    val newValue: Any?,
    val timestamp: Long = System.currentTimeMillis(),
    val fieldChanges: Map<String, FieldChange> = emptyMap()
)

data class FieldChange(
    val fieldName: String,
    val oldValue: Any?,
    val newValue: Any?
)

data class DataChanges(
    val changes: List<DataChange>,
    val timestamp: Long = System.currentTimeMillis(),
    val version: Long = System.currentTimeMillis()
) {
    val isEmpty: Boolean get() = changes.isEmpty()
    val changeCount: Int get() = changes.size
    
    fun getByType(dataType: DataType): List<DataChange> = 
        changes.filter { it.dataType == dataType }
    
    fun getByChangeType(changeType: ChangeType): List<DataChange> = 
        changes.filter { it.changeType == changeType }
}

class ChangeTracker {
    companion object {
        private const val TAG = "ChangeTracker"
        private const val MAX_TRACKED_CHANGES = 10000
        private const val MAX_SNAPSHOTS = 1000
        private const val CHANGE_TTL_MS = 300_000L
    }
    
    private val changes = ConcurrentHashMap<String, DataChange>()
    private val changeVersion = AtomicLong(0)
    private val lastCleanupTime = AtomicLong(0)
    
    private val snapshotLock = ReentrantReadWriteLock()
    private val entitySnapshots: MutableMap<String, EntitySnapshot> = Collections.synchronizedMap(
        BoundedLinkedHashMap(MAX_SNAPSHOTS)
    )
    
    data class EntitySnapshot(
        val key: String,
        val data: Any,
        val timestamp: Long,
        val checksum: String
    )
    
    fun trackCreate(dataType: DataType, entityId: String, newValue: Any) {
        val key = buildKey(dataType, entityId)
        val change = DataChange(
            key = key,
            dataType = dataType,
            entityId = entityId,
            changeType = ChangeType.CREATED,
            oldValue = null,
            newValue = newValue,
            timestamp = System.currentTimeMillis()
        )
        trackChange(key, change)
        saveSnapshot(key, newValue)
    }
    
    fun trackUpdate(dataType: DataType, entityId: String, oldValue: Any?, newValue: Any) {
        val key = buildKey(dataType, entityId)
        
        val fieldChanges = if (oldValue != null) {
            computeFieldChanges(oldValue, newValue)
        } else {
            emptyMap()
        }
        
        val change = DataChange(
            key = key,
            dataType = dataType,
            entityId = entityId,
            changeType = ChangeType.UPDATED,
            oldValue = oldValue,
            newValue = newValue,
            timestamp = System.currentTimeMillis(),
            fieldChanges = fieldChanges
        )
        trackChange(key, change)
        saveSnapshot(key, newValue)
    }
    
    fun trackDelete(dataType: DataType, entityId: String, oldValue: Any?) {
        val key = buildKey(dataType, entityId)
        val change = DataChange(
            key = key,
            dataType = dataType,
            entityId = entityId,
            changeType = ChangeType.DELETED,
            oldValue = oldValue,
            newValue = null,
            timestamp = System.currentTimeMillis()
        )
        trackChange(key, change)
        removeSnapshot(key)
    }
    
    fun trackBatchCreate(dataType: DataType, entities: List<Pair<String, Any>>) {
        entities.forEach { (entityId, newValue) ->
            trackCreate(dataType, entityId, newValue)
        }
    }
    
    fun trackBatchUpdate(dataType: DataType, entities: List<Triple<String, Any?, Any>>) {
        entities.forEach { (entityId, oldValue, newValue) ->
            trackUpdate(dataType, entityId, oldValue, newValue)
        }
    }
    
    fun trackBatchDelete(dataType: DataType, entities: List<Pair<String, Any?>>) {
        entities.forEach { (entityId, oldValue) ->
            trackDelete(dataType, entityId, oldValue)
        }
    }
    
    private fun trackChange(key: String, change: DataChange) {
        if (changes.size >= MAX_TRACKED_CHANGES) {
            cleanupOldChanges()
        }
        
        changes[key] = change
        changeVersion.incrementAndGet()
        
        Log.v(TAG, "Tracked ${change.changeType} for ${change.dataType}:${change.entityId}")
    }
    
    fun getChanges(): DataChanges {
        return DataChanges(
            changes = changes.values.toList().sortedByDescending { it.timestamp },
            timestamp = System.currentTimeMillis(),
            version = changeVersion.get()
        )
    }
    
    fun getChangesSince(sinceVersion: Long): DataChanges {
        val filteredChanges = changes.values
            .filter { it.timestamp >= sinceVersion }
            .sortedByDescending { it.timestamp }
        
        return DataChanges(
            changes = filteredChanges,
            timestamp = System.currentTimeMillis(),
            version = changeVersion.get()
        )
    }
    
    fun getChangesByType(dataType: DataType): List<DataChange> {
        return changes.values
            .filter { it.dataType == dataType }
            .sortedByDescending { it.timestamp }
    }
    
    fun getChangesByTypes(dataTypes: Set<DataType>): List<DataChange> {
        return changes.values
            .filter { it.dataType in dataTypes }
            .sortedByDescending { it.timestamp }
    }
    
    fun hasChanges(): Boolean = changes.isNotEmpty()
    
    fun changeCount(): Int = changes.size
    
    fun clearChanges() {
        snapshotLock.write {
            changes.clear()
            entitySnapshots.clear()
            changeVersion.set(0)
        }
        Log.d(TAG, "Cleared all tracked changes and snapshots")
    }
    
    fun clearChangesByType(dataType: DataType) {
        val keysToRemove = changes.entries
            .filter { it.value.dataType == dataType }
            .map { it.key }
        
        snapshotLock.write {
            keysToRemove.forEach { key ->
                changes.remove(key)
                entitySnapshots.remove(key)
            }
        }
        
        if (keysToRemove.isNotEmpty()) {
            Log.d(TAG, "Cleared ${keysToRemove.size} changes for $dataType")
        }
    }
    
    private fun cleanupOldChanges() {
        val now = System.currentTimeMillis()
        if (now - lastCleanupTime.get() < 60_000L) {
            return
        }
        lastCleanupTime.set(now)
        
        val cutoffTime = now - CHANGE_TTL_MS
        val keysToRemove = changes.entries
            .filter { it.value.timestamp < cutoffTime }
            .map { it.key }
        
        snapshotLock.write {
            keysToRemove.forEach { key ->
                changes.remove(key)
                entitySnapshots.remove(key)
            }
        }
        
        if (keysToRemove.isNotEmpty()) {
            Log.d(TAG, "Cleaned up ${keysToRemove.size} old changes and snapshots")
        }
    }
    
    fun getSnapshot(key: String): EntitySnapshot? {
        return snapshotLock.read { entitySnapshots[key] }
    }
    
    fun getSnapshot(dataType: DataType, entityId: String): EntitySnapshot? {
        return snapshotLock.read { entitySnapshots[buildKey(dataType, entityId)] }
    }
    
    private fun saveSnapshot(key: String, data: Any) {
        val snapshot = EntitySnapshot(
            key = key,
            data = data,
            timestamp = System.currentTimeMillis(),
            checksum = computeChecksum(data)
        )
        snapshotLock.write {
            entitySnapshots[key] = snapshot
        }
    }
    
    private fun removeSnapshot(key: String) {
        snapshotLock.write {
            entitySnapshots.remove(key)
        }
    }
    
    private fun computeChecksum(data: Any): String {
        return try {
            val json = when (data) {
                is String -> data
                else -> data.toString()
            }
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(json.toByteArray(Charsets.UTF_8))
            hash.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to compute checksum: ${e.message}")
            "${data.hashCode()}_${System.currentTimeMillis()}"
        }
    }
    
    private fun computeFieldChanges(oldValue: Any, newValue: Any): Map<String, FieldChange> {
        val fieldChanges = mutableMapOf<String, FieldChange>()
        
        try {
            val oldClass = oldValue::class.java
            val newClass = newValue::class.java
            
            if (oldClass != newClass) {
                Log.w(TAG, "Type mismatch in field comparison: ${oldClass.simpleName} vs ${newClass.simpleName}")
                return fieldChanges
            }
            
            oldClass.declaredFields.forEach { field ->
                val fieldName = field.name
                
                if (fieldName.startsWith("$") || fieldName == "serialVersionUID") {
                    return@forEach
                }
                
                try {
                    field.isAccessible = true
                    val oldFieldValue = field.get(oldValue)
                    val newFieldValue = field.get(newValue)
                    
                    if (oldFieldValue != newFieldValue) {
                        fieldChanges[fieldName] = FieldChange(
                            fieldName = fieldName,
                            oldValue = oldFieldValue,
                            newValue = newFieldValue
                        )
                    }
                } catch (e: Exception) {
                    Log.v(TAG, "Could not access field $fieldName: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to compute field changes: ${e.message}")
        }
        
        return fieldChanges
    }
    
    fun getChangeStats(): ChangeStats {
        val typeCounts = changes.values.groupingBy { it.dataType }.eachCount()
        val changeTypeCounts = changes.values.groupingBy { it.changeType }.eachCount()
        
        val snapshotCount = snapshotLock.read { entitySnapshots.size }
        
        return ChangeStats(
            totalChanges = changes.size,
            typeCounts = typeCounts,
            changeTypeCounts = changeTypeCounts,
            oldestChange = changes.values.minByOrNull { it.timestamp }?.timestamp ?: 0,
            newestChange = changes.values.maxByOrNull { it.timestamp }?.timestamp ?: 0,
            snapshotCount = snapshotCount
        )
    }
    
    private fun buildKey(dataType: DataType, entityId: String): String =
        "${dataType.prefix}:$entityId"
    
    data class ChangeStats(
        val totalChanges: Int,
        val typeCounts: Map<DataType, Int>,
        val changeTypeCounts: Map<ChangeType, Int>,
        val oldestChange: Long,
        val newestChange: Long,
        val snapshotCount: Int
    )
}
