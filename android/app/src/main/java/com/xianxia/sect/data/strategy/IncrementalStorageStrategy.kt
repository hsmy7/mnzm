package com.xianxia.sect.data.strategy

import android.util.Log
import com.xianxia.sect.data.incremental.IncrementalStorageManager
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.model.SaveSlot
import com.xianxia.sect.data.unified.SaveError
import com.xianxia.sect.data.unified.SaveResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IncrementalStorageStrategy @Inject constructor(
    private val incrementalManager: IncrementalStorageManager
) : StorageStrategy {
    
    companion object {
        private const val TAG = "IncrementalStrategy"
        private const val MAX_DELTA_CHAIN_LENGTH = 50
    }
    
    override val name: String = "IncrementalStorage"
    override val priority: Int = 10
    
    override suspend fun save(slot: Int, data: SaveData): SaveResult<StorageStats> {
        Log.d(TAG, "Performing incremental save for slot $slot")
        
        val startTime = System.currentTimeMillis()
        
        if (!incrementalManager.hasPendingChanges()) {
            Log.d(TAG, "No pending changes, skipping incremental save")
            return SaveResult.success(StorageStats.EMPTY)
        }
        
        val result = incrementalManager.saveIncremental(slot, data)
        val elapsed = System.currentTimeMillis() - startTime
        
        return if (result.success) {
            SaveResult.success(StorageStats(
                bytesWritten = result.savedBytes,
                timeMs = elapsed,
                isIncremental = true,
                deltaCount = result.deltaCount,
                compressed = true
            ))
        } else {
            SaveResult.failure(SaveError.SAVE_FAILED, result.error ?: "Incremental save failed")
        }
    }
    
    override suspend fun load(slot: Int): SaveResult<SaveData> {
        Log.d(TAG, "Loading from incremental storage for slot $slot")
        
        val result = incrementalManager.load(slot)
        
        return if (result.data != null) {
            SaveResult.success(result.data)
        } else {
            SaveResult.failure(SaveError.LOAD_FAILED, result.error ?: "Incremental load failed")
        }
    }
    
    override suspend fun delete(slot: Int): SaveResult<Unit> {
        val success = incrementalManager.delete(slot)
        return if (success) {
            SaveResult.success(Unit)
        } else {
            SaveResult.failure(SaveError.DELETE_FAILED, "Failed to delete incremental data")
        }
    }
    
    override suspend fun hasSave(slot: Int): Boolean {
        return incrementalManager.hasSave(slot)
    }
    
    override suspend fun getSlotInfo(slot: Int): SaveSlot? {
        return incrementalManager.getSlotInfo(slot)
    }
    
    override fun isApplicable(slot: Int, context: StorageContext): Boolean {
        if (!context.hasExistingSave) {
            return false
        }
        
        if (!context.hasPendingChanges) {
            return false
        }
        
        if (context.deltaChainLength >= MAX_DELTA_CHAIN_LENGTH) {
            return false
        }
        
        return true
    }
}
