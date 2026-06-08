package com.xianxia.sect.data.engine

import android.content.Context
import android.util.Log
import com.xianxia.sect.data.StorageConstants
import com.xianxia.sect.data.local.GameDatabase
import com.xianxia.sect.data.serialization.unified.SerializationModule
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SavMigrator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val serializationModule: SerializationModule,
    private val database: GameDatabase,
    private val storageEngine: StorageEngine
) {
    companion object {
        private const val TAG = "SavMigrator"
        private const val PREFS_NAME = "sav_migration"
        private const val KEY_MIGRATION_DONE = "migration_done_version"
        private const val CURRENT_VERSION = 2
        private const val SAVE_DIR = "saves"
        private const val FILE_EXTENSION = ".sav"
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
    }

    private val saveDir: File by lazy {
        File(context.filesDir, SAVE_DIR).apply { if (!exists()) mkdirs() }
    }

    private fun getSaveFile(slot: Int): File = File(saveDir, "slot_$slot$FILE_EXTENSION")

    suspend fun migrateIfNeeded() {
        // 4.0 重置：删除所有旧 .sav 文件，标记迁移完成，不再执行旧迁移逻辑
        for (slot in (1..StorageConstants.DEFAULT_MAX_SLOTS) +
            listOf(StorageConstants.AUTO_SAVE_SLOT, StorageConstants.EMERGENCY_SLOT)) {
            getSaveFile(slot).delete()
        }
        saveDir.listFiles()?.filter { it.extension == "sav" }?.forEach {
            if (it.exists()) it.delete()
        }
        // 直接标记完成，阻止后续逻辑访问 DB（避免在 Room destructive migration 完成前触发 DB 打开）
        prefs.edit().putInt(KEY_MIGRATION_DONE, CURRENT_VERSION).apply()
        return

        Log.i(TAG, "Starting .sav → Room migration (version $CURRENT_VERSION)")

        val slotsToMigrate = (1..StorageConstants.DEFAULT_MAX_SLOTS) +
            listOf(StorageConstants.AUTO_SAVE_SLOT, StorageConstants.EMERGENCY_SLOT)

        var migratedCount = 0
        var skippedCount = 0
        var failedCount = 0

        for (slot in slotsToMigrate.distinct()) {
            val hasRoomData = try {
                database.gameDataDao().getGameDataSync(slot) != null
            } catch (e: Exception) {
                Log.w(TAG, "Failed to check Room data for slot $slot", e)
                false
            }

            if (hasRoomData) {
                skippedCount++
                continue
            }

            val saveFile = getSaveFile(slot)
            if (!saveFile.exists()) {
                skippedCount++
                continue
            }

            try {
                val bytes = saveFile.readBytes()
                val saveData = serializationModule.deserializeSaveData(bytes)

                val result = storageEngine.save(slot, saveData, SavePriority.CRITICAL)
                if (result.isSuccess) {
                    migratedCount++
                    Log.i(TAG, "Migrated slot $slot from .sav to Room (${bytes.size} bytes)")
                } else {
                    failedCount++
                    Log.e(TAG, "Failed to save migrated data for slot $slot")
                }
            } catch (e: Exception) {
                failedCount++
                Log.e(TAG, "Failed to migrate slot $slot from .sav", e)
            }
        }

        prefs.edit().putInt(KEY_MIGRATION_DONE, CURRENT_VERSION).apply()
        Log.i(TAG, "Migration complete: $migratedCount migrated, $skippedCount skipped, $failedCount failed")
    }
}
