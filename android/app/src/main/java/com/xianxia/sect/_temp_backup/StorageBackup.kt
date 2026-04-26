package com.xianxia.sect.data.engine

import android.util.Log
import androidx.room.withTransaction
import com.xianxia.sect.data.concurrent.SlotLockManager
import com.xianxia.sect.data.local.GameDatabase
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.result.StorageError
import com.xianxia.sect.data.result.StorageResult
import com.xianxia.sect.data.serialization.unified.SerializationModule
import com.xianxia.sect.data.unified.BackupInfo
import com.xianxia.sect.data.unified.BackupManager
import com.xianxia.sect.data.unified.SaveResult
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StorageBackup @Inject constructor(
    private val lockManager: SlotLockManager,
    private val database: GameDatabase,
    private val serializationModule: SerializationModule,
    private val backupManager: BackupManager
) {
    companion object {
        private const val TAG = "StorageBackup"
    }

    suspend fun exportToFile(slot: Int, file: File): StorageResult<Unit> {
        if (!lockManager.isValidSlot(slot)) {
            return StorageResult.failure(StorageError.INVALID_SLOT, "Invalid slot: $slot")
        }

        return lockManager.withReadLockLight(slot) {
            try {
                val saveData = database.withTransaction {
                    val gameData = database.gameDataDao().getGameDataSync(slot)
                        ?: return@withTransaction null

                    val disciples = database.discipleDao().getAllSync(slot)
                    val equipmentStacks = database.equipmentStackDao().getAllSync(slot)
                    val equipmentInstances = database.equipmentInstanceDao().getAllSync(slot)
                    val manualStacks = database.manualStackDao().getAllSync(slot)
                    val manualInstances = database.manualInstanceDao().getAllSync(slot)
                    val pills = database.pillDao().getAllSync(slot)
                    val materials = database.materialDao().getAllSync(slot)
                    val herbs = database.herbDao().getAllSync(slot)
                    val seeds = database.seedDao().getAllSync(slot)
                    val teams = database.explorationTeamDao().getAllSync(slot)
                    val alliances = gameData.alliances ?: emptyList()
                    val battleLogs = database.battleLogDao().getAllSync(slot)
                    val events = database.gameEventDao().getAllSync(slot)
                    val productionSlots = database.productionSlotDao().getBySlotSync(slot)

                    SaveData(
                        gameData = gameData,
                        disciples = disciples,
                        equipmentStacks = equipmentStacks,
                        equipmentInstances = equipmentInstances,
                        manualStacks = manualStacks,
                        manualInstances = manualInstances,
                        pills = pills,
                        materials = materials,
                        herbs = herbs,
                        seeds = seeds,
                        teams = teams,
                        events = events,
                        battleLogs = battleLogs,
                        alliances = alliances,
                        productionSlots = productionSlots
                    )
                } ?: return@withReadLockLight StorageResult.failure(StorageError.SLOT_EMPTY, "No data in slot $slot")

                val saveDataBytes = serializationModule.serializeAndCompressSaveData(saveData)

                file.parentFile?.mkdirs()
                FileOutputStream(file).use { output ->
                    output.write(saveDataBytes)
                }

                Log.i(TAG, "Exported slot $slot to ${file.absolutePath} (${saveDataBytes.size} bytes)")
                StorageResult.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Export failed for slot $slot", e)
                StorageResult.failure(StorageError.IO_ERROR, e.message ?: "Export failed", e)
            }
        }
    }

    suspend fun createBackup(slot: Int): SaveResult<String> {
        return backupManager.createBackup(slot)
    }

    fun getBackupVersions(slot: Int): List<BackupInfo> {
        return backupManager.getBackupVersions(slot)
    }

    suspend fun restoreBackup(
        slot: Int,
        backupId: String,
        loadFunc: suspend (Int) -> SaveResult<SaveData>
    ): SaveResult<SaveData> {
        return backupManager.restoreFromBackup(slot, backupId, loadFunc)
    }

    fun deleteBackupVersions(slot: Int) {
        backupManager.deleteBackupVersions(slot)
    }
}
