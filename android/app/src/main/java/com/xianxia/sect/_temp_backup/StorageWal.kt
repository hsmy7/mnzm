package com.xianxia.sect.data.engine

import android.util.Log
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.serialization.unified.SerializationModule
import com.xianxia.sect.data.wal.WALProvider
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StorageWal @Inject constructor(
    private val wal: WALProvider,
    private val serializationModule: SerializationModule
) {
    companion object {
        private const val TAG = "StorageWal"
    }

    suspend fun createCriticalSnapshot(slot: Int, data: SaveData) {
        try {
            val snapshotData = serializationModule.serializeAndCompressSaveData(data)
            wal.createImportantSnapshot(
                slot = slot,
                snapshotProvider = { snapshotData },
                eventType = "CRITICAL_SAVE"
            )
            Log.d(TAG, "Created critical snapshot for slot $slot")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create critical snapshot for slot $slot", e)
        }
    }
}
