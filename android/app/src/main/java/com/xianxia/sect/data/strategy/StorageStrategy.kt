package com.xianxia.sect.data.strategy

import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.model.SaveSlot
import com.xianxia.sect.data.unified.SaveResult

interface StorageStrategy {
    val name: String
    val priority: Int
    
    suspend fun save(slot: Int, data: SaveData): SaveResult<StorageStats>
    suspend fun load(slot: Int): SaveResult<SaveData>
    suspend fun delete(slot: Int): SaveResult<Unit>
    suspend fun hasSave(slot: Int): Boolean
    suspend fun getSlotInfo(slot: Int): SaveSlot?
    
    fun isApplicable(slot: Int, context: StorageContext): Boolean
}

data class StorageContext(
    val hasExistingSave: Boolean,
    val hasPendingChanges: Boolean,
    val deltaChainLength: Int,
    val lastSaveWasIncremental: Boolean,
    val saveCountSinceLastFull: Int
)

data class StorageStats(
    val bytesWritten: Long = 0,
    val timeMs: Long = 0,
    val isIncremental: Boolean = false,
    val deltaCount: Int = 0,
    val compressed: Boolean = false
) {
    companion object {
        val EMPTY = StorageStats()
    }
}

enum class StorageType {
    FULL,
    INCREMENTAL,
    AUTO
}

data class StrategyConfig(
    val enableIncremental: Boolean = true,
    val maxDeltaChainLength: Int = 50,
    val compactionThreshold: Int = 10,
    val forceFullSaveInterval: Int = 20,
    val incrementalPriority: Int = 10,
    val fullPriority: Int = 5
)
