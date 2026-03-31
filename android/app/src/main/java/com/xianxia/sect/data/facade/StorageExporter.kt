package com.xianxia.sect.data.facade

import android.content.Context
import android.util.Log
import com.xianxia.sect.data.GsonConfig
import com.xianxia.sect.data.concurrent.SlotLockManager
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.unified.UnifiedSaveRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import javax.inject.Singleton

data class ExportResult(
    val success: Boolean,
    val filePath: String? = null,
    val fileSize: Long = 0,
    val error: String? = null
)

data class ImportResult(
    val success: Boolean,
    val slot: Int,
    val sectName: String? = null,
    val error: String? = null
)

@Singleton
class StorageExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val saveRepository: UnifiedSaveRepository,
    private val lockManager: SlotLockManager
) {
    companion object {
        private const val TAG = "StorageExporter"
        private const val EXPORT_VERSION = 1
    }
    
    suspend fun exportSave(slot: Int, destFile: File): ExportResult {
        if (!lockManager.isValidSlot(slot)) {
            return ExportResult(false, error = "Invalid slot: $slot")
        }
        
        return withContext(Dispatchers.IO) {
            try {
                val result = saveRepository.load(slot)
                if (result.isFailure) {
                    return@withContext ExportResult(false, error = "No save data to export")
                }
                
                val data = result.getOrThrow()
                
                GZIPOutputStream(java.io.FileOutputStream(destFile)).use { output ->
                    val header = "XIAXIA_SAVE_V$EXPORT_VERSION\n"
                    output.write(header.toByteArray(Charsets.UTF_8))
                    
                    val json = GsonConfig.createGson().toJson(data)
                    output.write(json.toByteArray(Charsets.UTF_8))
                }
                
                val fileSize = destFile.length()
                Log.i(TAG, "Exported slot $slot to ${destFile.absolutePath}, size: $fileSize bytes")
                
                ExportResult(true, destFile.absolutePath, fileSize)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to export slot $slot", e)
                ExportResult(false, error = e.message ?: "Export failed")
            }
        }
    }
    
    suspend fun importSave(slot: Int, sourceFile: File): ImportResult {
        if (!lockManager.isValidSlot(slot)) {
            return ImportResult(false, slot, error = "Invalid slot: $slot")
        }
        
        return withContext(Dispatchers.IO) {
            try {
                if (!sourceFile.exists()) {
                    return@withContext ImportResult(false, slot, error = "Source file not found")
                }
                
                val rawData = GZIPInputStream(java.io.FileInputStream(sourceFile)).use { input ->
                    input.readBytes()
                }
                
                val content = rawData.toString(Charsets.UTF_8)
                
                val json = if (content.startsWith("XIAXIA_SAVE_V")) {
                    content.substringAfter('\n')
                } else {
                    content
                }
                
                val gson = GsonConfig.createGson()
                val data = gson.fromJson(json, SaveData::class.java)
                
                val saveResult = saveRepository.save(slot, data)
                if (saveResult.isSuccess) {
                    Log.i(TAG, "Imported save to slot $slot from ${sourceFile.absolutePath}")
                    ImportResult(true, slot, data.gameData.sectName)
                } else {
                    ImportResult(false, slot, error = "Failed to save imported data")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to import save to slot $slot", e)
                ImportResult(false, slot, error = e.message ?: "Import failed")
            }
        }
    }
    
    suspend fun exportAllSlots(destDir: File): Map<Int, ExportResult> {
        val results = mutableMapOf<Int, ExportResult>()
        
        if (!destDir.exists()) {
            destDir.mkdirs()
        }
        
        for (slot in 0..5) {
            if (saveRepository.hasSave(slot)) {
                val destFile = File(destDir, "slot_$slot.xianxia")
                results[slot] = exportSave(slot, destFile)
            }
        }
        
        return results
    }
    
    suspend fun clearBackups(slot: Int): Int {
        if (!lockManager.isValidSlot(slot)) return 0
        
        var cleared = 0
        try {
            val backupDir = File(context.filesDir, "backups/slot_$slot")
            if (backupDir.exists()) {
                backupDir.listFiles()?.filter { it.extension == "sav" }?.forEach {
                    if (it.delete()) cleared++
                }
            }
            Log.i(TAG, "Cleared $cleared backups for slot $slot")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear backups for slot $slot", e)
        }
        return cleared
    }
}
