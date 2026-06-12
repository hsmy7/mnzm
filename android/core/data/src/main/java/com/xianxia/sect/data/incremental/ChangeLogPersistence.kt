package com.xianxia.sect.data.incremental

import android.util.Log
import com.xianxia.sect.data.local.GameDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChangeLogPersistence @Inject constructor(
    private val database: GameDatabase
) {
    companion object {
        private const val TAG = "ChangeLogPersistence"
        private const val MAX_LOG_AGE_MS = 7 * 24 * 60 * 60 * 1000L
        private const val CLEANUP_BATCH_SIZE = 500
    }

    suspend fun logChange(
        tableName: String,
        recordId: String,
        operation: ChangeLogOperation,
        oldValue: ByteArray? = null,
        newValue: ByteArray? = null
    ) = withContext(Dispatchers.IO) {
        try {
            val entity = ChangeLogEntity(
                tableName = tableName,
                recordId = recordId,
                operation = operation.name,
                oldValue = oldValue,
                newValue = newValue,
                timestamp = System.currentTimeMillis(),
                synced = false
            )
            database.changeLogDao().insert(entity)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to log change for $tableName:$recordId", e)
        }
    }

    suspend fun logBatchChanges(changes: List<ChangeLogEntry>) = withContext(Dispatchers.IO) {
        try {
            val entities = changes.map { it.toEntity() }
            database.changeLogDao().insertAll(entities)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to log batch changes (${changes.size} entries)", e)
        }
    }

    suspend fun getUnsyncedChanges(limit: Int = 100): List<ChangeLogEntity> = withContext(Dispatchers.IO) {
        database.changeLogDao().getUnsynced(limit)
    }

    suspend fun markSynced(ids: List<Long>) = withContext(Dispatchers.IO) {
        database.changeLogDao().markSynced(ids)
    }

    suspend fun cleanupOldLogs() = withContext(Dispatchers.IO) {
        try {
            val threshold = System.currentTimeMillis() - MAX_LOG_AGE_MS
            val deleted = database.changeLogDao().deleteSyncedOlderThan(threshold)
            if (deleted > 0) {
                Log.i(TAG, "Cleaned up $deleted old synced change logs")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cleanup old logs", e)
        }
    }

    suspend fun getPendingCount(): Int = withContext(Dispatchers.IO) {
        try {
            database.changeLogDao().getCount()
        } catch (e: Exception) {
            0
        }
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        database.changeLogDao().deleteAll()
    }
}

enum class ChangeLogOperation {
    INSERT, UPDATE, DELETE
}

data class ChangeLogEntry(
    val tableName: String,
    val recordId: String,
    val operation: ChangeLogOperation,
    val oldValue: ByteArray? = null,
    val newValue: ByteArray? = null
) {
    fun toEntity() = ChangeLogEntity(
        tableName = tableName,
        recordId = recordId,
        operation = operation.name,
        oldValue = oldValue,
        newValue = newValue,
        timestamp = System.currentTimeMillis(),
        synced = false
    )
}
