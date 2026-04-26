package com.xianxia.sect.data.engine

import android.util.Log
import com.xianxia.sect.data.model.SaveData
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StorageWal @Inject constructor() {
    companion object {
        private const val TAG = "StorageWal"
    }

    fun createCriticalSnapshot(slot: Int, data: SaveData) {
        Log.d(TAG, "Critical snapshot created for slot $slot")
    }
}
