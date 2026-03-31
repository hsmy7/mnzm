package com.xianxia.sect.data.cache

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

class DirtyTracker {
    companion object {
        private const val TAG = "DirtyTracker"
    }

    private val dirtyMap = ConcurrentHashMap<String, DirtyEntry>()

    data class DirtyEntry(
        val key: String,
        val flag: DirtyFlag,
        val timestamp: Long,
        val data: Any? = null
    )

    fun markDirty(key: String, flag: DirtyFlag, data: Any? = null) {
        val entry = DirtyEntry(key, flag, System.currentTimeMillis(), data)
        dirtyMap[key] = entry
        Log.d(TAG, "Marked dirty: $key, flag=$flag")
    }

    fun markInsert(key: String, data: Any?) {
        markDirty(key, DirtyFlag.INSERT, data)
    }

    fun markUpdate(key: String, data: Any?) {
        markDirty(key, DirtyFlag.UPDATE, data)
    }

    fun markDelete(key: String) {
        markDirty(key, DirtyFlag.DELETE)
    }

    fun markDirtyBatch(keys: Collection<String>, flag: DirtyFlag, dataProvider: ((String) -> Any?)? = null) {
        keys.forEach { key ->
            val data = dataProvider?.invoke(key)
            markDirty(key, flag, data)
        }
        Log.d(TAG, "Marked ${keys.size} entries dirty with flag=$flag")
    }

    fun isDirty(key: String): Boolean = dirtyMap.containsKey(key)

    fun getDirtyEntry(key: String): DirtyEntry? = dirtyMap[key]

    fun getFlag(key: String): DirtyFlag? = dirtyMap[key]?.flag

    fun removeDirty(key: String): DirtyEntry? {
        val entry = dirtyMap.remove(key)
        if (entry != null) {
            Log.d(TAG, "Removed dirty entry: $key")
        }
        return entry
    }

    fun clearDirty(key: String) {
        dirtyMap.remove(key)
    }

    fun getAllDirty(): Map<String, DirtyEntry> = dirtyMap.toMap()

    fun getDirtyByFlag(flag: DirtyFlag): List<DirtyEntry> {
        return dirtyMap.values.filter { it.flag == flag }
    }

    fun getDirtyKeys(): Set<String> = dirtyMap.keys.toSet()

    fun size(): Int = dirtyMap.size

    fun isEmpty(): Boolean = dirtyMap.isEmpty()

    fun clear() {
        val count = dirtyMap.size
        dirtyMap.clear()
        Log.d(TAG, "Cleared $count dirty entries")
    }

    fun drainAll(): List<DirtyEntry> {
        val entries = dirtyMap.values.toList()
        dirtyMap.clear()
        Log.d(TAG, "Drained ${entries.size} dirty entries")
        return entries
    }

    fun drainBatch(maxSize: Int): List<DirtyEntry> {
        val entries = mutableListOf<DirtyEntry>()
        val iterator = dirtyMap.entries.iterator()
        
        while (iterator.hasNext() && entries.size < maxSize) {
            val entry = iterator.next()
            entries.add(entry.value)
            iterator.remove()
        }
        
        Log.d(TAG, "Drained batch of ${entries.size} dirty entries")
        return entries
    }

    fun getStats(): DirtyStats {
        var inserts = 0
        var updates = 0
        var deletes = 0

        dirtyMap.values.forEach { entry ->
            when (entry.flag) {
                DirtyFlag.INSERT -> inserts++
                DirtyFlag.UPDATE -> updates++
                DirtyFlag.DELETE -> deletes++
            }
        }

        return DirtyStats(
            totalCount = dirtyMap.size,
            insertCount = inserts,
            updateCount = updates,
            deleteCount = deletes
        )
    }
}

data class DirtyStats(
    val totalCount: Int,
    val insertCount: Int,
    val updateCount: Int,
    val deleteCount: Int
)
