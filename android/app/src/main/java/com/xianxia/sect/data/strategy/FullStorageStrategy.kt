package com.xianxia.sect.data.strategy

import android.util.Log
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.model.SaveSlot
import com.xianxia.sect.data.unified.SaveError
import com.xianxia.sect.data.unified.SaveResult
import com.xianxia.sect.data.unified.UnifiedSaveRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FullStorageStrategy @Inject constructor(
    private val saveRepository: UnifiedSaveRepository
) : StorageStrategy {
    
    companion object {
        private const val TAG = "FullStorageStrategy"
    }
    
    override val name: String = "FullStorage"
    override val priority: Int = 5
    
    override suspend fun save(slot: Int, data: SaveData): SaveResult<StorageStats> {
        Log.d(TAG, "Performing full save for slot $slot")
        
        val startTime = System.currentTimeMillis()
        val result = saveRepository.save(slot, data)
        val elapsed = System.currentTimeMillis() - startTime
        
        return result.map { stats ->
            StorageStats(
                bytesWritten = stats.bytesWritten,
                timeMs = elapsed,
                isIncremental = false,
                deltaCount = 0,
                compressed = stats.wasEncrypted
            )
        }
    }
    
    override suspend fun load(slot: Int): SaveResult<SaveData> {
        Log.d(TAG, "Loading from full storage for slot $slot")
        return saveRepository.load(slot)
    }
    
    override suspend fun delete(slot: Int): SaveResult<Unit> {
        return saveRepository.delete(slot)
    }
    
    override suspend fun hasSave(slot: Int): Boolean {
        return saveRepository.hasSave(slot)
    }
    
    override suspend fun getSlotInfo(slot: Int): SaveSlot? {
        val metadata = saveRepository.getSlotInfo(slot) ?: return null
        return SaveSlot(
            slot = metadata.slot,
            name = "Save ${metadata.slot}",
            timestamp = metadata.timestamp,
            gameYear = metadata.gameYear,
            gameMonth = metadata.gameMonth,
            sectName = metadata.sectName,
            discipleCount = metadata.discipleCount,
            spiritStones = metadata.spiritStones,
            isEmpty = false,
            customName = metadata.customName
        )
    }
    
    override fun isApplicable(slot: Int, context: StorageContext): Boolean {
        return true
    }
}
