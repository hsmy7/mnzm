package com.xianxia.sect.data.serialization.unified

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.serializer
import java.io.*
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class SaveOperationResult(
    val success: Boolean,
    val slot: Int,
    val error: Throwable? = null,
    val bytesWritten: Long = 0,
    val timeMs: Long = 0,
    val compressionRatio: Double = 1.0
)

data class LoadOperationResult(
    val success: Boolean,
    val slot: Int,
    val data: SerializableSaveData? = null,
    val error: Throwable? = null,
    val timeMs: Long = 0,
    val checksumValid: Boolean = true
)

data class SaveSlotInfo(
    val slot: Int,
    val exists: Boolean,
    val timestamp: Long = 0,
    val gameYear: Int = 0,
    val gameMonth: Int = 0,
    val sectName: String = "",
    val discipleCount: Int = 0,
    val spiritStones: Long = 0,
    val fileSize: Long = 0,
    val customName: String = "",
    val format: SerializationFormat = SerializationFormat.PROTOBUF
)

sealed class SaveEvent {
    data class SaveStarted(val slot: Int) : SaveEvent()
    data class SaveProgress(val slot: Int, val progress: Float) : SaveEvent()
    data class SaveCompleted(val result: SaveOperationResult) : SaveEvent()
    data class SaveFailed(val slot: Int, val error: Throwable) : SaveEvent()
    data class LoadStarted(val slot: Int) : SaveEvent()
    data class LoadCompleted(val result: LoadOperationResult) : SaveEvent()
    data class LoadFailed(val slot: Int, val error: Throwable) : SaveEvent()
    data class SlotChanged(val slot: Int) : SaveEvent()
}

@Singleton
class UnifiedSaveManager @Inject constructor(
    private val context: Context,
    private val serializationEngine: UnifiedSerializationEngine,
    private val compressionLayer: UnifiedCompressionLayer,
    private val integrityLayer: IntegrityLayer
) {
    companion object {
        private const val TAG = "UnifiedSaveManager"
        private const val SAVE_DIR = "saves"
        private const val SAVE_FILE_PREFIX = "save_"
        private const val SAVE_FILE_EXT = ".sav"
        private const val BACKUP_EXT = ".bak"
        private const val TEMP_EXT = ".tmp"
        private const val METADATA_FILE = "metadata.pb"
        private const val METADATA_FILE_LEGACY = "metadata.json"
        private const val CURRENT_SAVE_VERSION = "2.0"
        private const val MAX_SLOTS = 10
        private const val AUTO_SAVE_SLOT = 0
    }
    
    private val saveDir: File by lazy {
        File(context.filesDir, SAVE_DIR).apply { mkdirs() }
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val _events = MutableSharedFlow<SaveEvent>()
    val events: SharedFlow<SaveEvent> = _events.asSharedFlow()
    
    private val slotCache = ConcurrentHashMap<Int, SaveSlotInfo>()
    private var currentSlot: Int = 1
    
    private val metadataCache = ConcurrentHashMap<Int, SerializableSaveSlot>()
    
    suspend fun save(
        slot: Int,
        data: SerializableSaveData,
        context: SerializationContext = SerializationContext()
    ): SaveOperationResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        
        _events.emit(SaveEvent.SaveStarted(slot))
        
        try {
            val saveFile = getSaveFile(slot)
            val tempFile = File(saveFile.parent, saveFile.name + TEMP_EXT)
            
            if (saveFile.exists()) {
                createBackup(slot)
            }
            
            val dataToSave = data.copy(
                version = CURRENT_SAVE_VERSION,
                timestamp = System.currentTimeMillis()
            )
            
            val result = serializationEngine.serialize(dataToSave, context, serializer<SerializableSaveData>())
            
            tempFile.outputStream().use { output ->
                output.write(result.data)
            }
            
            if (saveFile.exists()) {
                saveFile.delete()
            }
            tempFile.renameTo(saveFile)
            
            updateSlotMetadata(slot, dataToSave, result.compressedSize.toLong())
            
            val timeMs = System.currentTimeMillis() - startTime
            
            val operationResult = SaveOperationResult(
                success = true,
                slot = slot,
                bytesWritten = result.compressedSize.toLong(),
                timeMs = timeMs,
                compressionRatio = result.compressionRatio
            )
            
            _events.emit(SaveEvent.SaveCompleted(operationResult))
            
            Log.i(TAG, "Save completed: slot=$slot, size=${result.compressedSize}, time=${timeMs}ms, ratio=${result.compressionRatio}")
            
            operationResult
        } catch (e: Exception) {
            Log.e(TAG, "Save failed: slot=$slot", e)
            _events.emit(SaveEvent.SaveFailed(slot, e))
            SaveOperationResult(success = false, slot = slot, error = e)
        }
    }
    
    suspend fun load(
        slot: Int,
        context: SerializationContext = SerializationContext()
    ): LoadOperationResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        
        _events.emit(SaveEvent.LoadStarted(slot))
        
        try {
            val saveFile = getSaveFile(slot)
            
            if (!saveFile.exists()) {
                return@withContext LoadOperationResult(
                    success = false,
                    slot = slot,
                    error = FileNotFoundException("Save slot $slot not found")
                )
            }
            
            val rawData = saveFile.readBytes()
            
            val result = serializationEngine.deserialize(rawData, context, serializer<SerializableSaveData>())
            
            val timeMs = System.currentTimeMillis() - startTime
            
            if (result.isSuccess) {
                val loadResult = LoadOperationResult(
                    success = true,
                    slot = slot,
                    data = result.data,
                    timeMs = timeMs,
                    checksumValid = result.checksumValid
                )
                
                _events.emit(SaveEvent.LoadCompleted(loadResult))
                
                Log.i(TAG, "Load completed: slot=$slot, time=${timeMs}ms, checksum=${result.checksumValid}")
                
                loadResult
            } else {
                val loadResult = LoadOperationResult(
                    success = false,
                    slot = slot,
                    error = result.error ?: Exception("Unknown deserialization error"),
                    timeMs = timeMs
                )
                _events.emit(SaveEvent.LoadFailed(slot, result.error ?: Exception("Unknown error")))
                loadResult
            }
        } catch (e: Exception) {
            Log.e(TAG, "Load failed: slot=$slot", e)
            _events.emit(SaveEvent.LoadFailed(slot, e))
            LoadOperationResult(success = false, slot = slot, error = e)
        }
    }
    
    suspend fun quickSave(data: SerializableSaveData): SaveOperationResult {
        return save(AUTO_SAVE_SLOT, data, getQuickSaveContext())
    }
    
    suspend fun quickLoad(): LoadOperationResult {
        return load(AUTO_SAVE_SLOT, getQuickSaveContext())
    }
    
    suspend fun autoSave(data: SerializableSaveData): SaveOperationResult {
        return save(AUTO_SAVE_SLOT, data, getAutoSaveContext())
    }
    
    fun getSlotInfo(slot: Int): SaveSlotInfo {
        return slotCache.getOrPut(slot) {
            computeSlotInfo(slot)
        }
    }
    
    fun getAllSlotInfo(): List<SaveSlotInfo> {
        return (0..MAX_SLOTS).map { slot ->
            getSlotInfo(slot)
        }
    }
    
    fun slotExists(slot: Int): Boolean {
        return getSaveFile(slot).exists()
    }
    
    suspend fun deleteSlot(slot: Int): Boolean = withContext(Dispatchers.IO) {
        val saveFile = getSaveFile(slot)
        val backupFile = getBackupFile(slot)
        
        var deleted = false
        
        if (saveFile.exists()) {
            deleted = saveFile.delete()
        }
        
        if (backupFile.exists()) {
            backupFile.delete()
        }
        
        slotCache.remove(slot)
        metadataCache.remove(slot)
        
        if (deleted) {
            _events.emit(SaveEvent.SlotChanged(slot))
        }
        
        deleted
    }
    
    suspend fun copySlot(fromSlot: Int, toSlot: Int): Boolean = withContext(Dispatchers.IO) {
        val fromFile = getSaveFile(fromSlot)
        val toFile = getSaveFile(toSlot)
        
        if (!fromFile.exists()) {
            return@withContext false
        }
        
        try {
            fromFile.copyTo(toFile, overwrite = true)
            
            val info = getSlotInfo(fromSlot)
            slotCache[toSlot] = info.copy(slot = toSlot)
            
            _events.emit(SaveEvent.SlotChanged(toSlot))
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Copy slot failed: from=$fromSlot, to=$toSlot", e)
            false
        }
    }
    
    suspend fun renameSlot(slot: Int, newName: String): Boolean = withContext(Dispatchers.IO) {
        val metadata = metadataCache[slot] ?: return@withContext false
        metadataCache[slot] = metadata.copy(customName = newName)
        
        val info = slotCache[slot] ?: return@withContext false
        slotCache[slot] = info.copy(customName = newName)
        
        saveMetadata()
        
        _events.emit(SaveEvent.SlotChanged(slot))
        
        true
    }
    
    suspend fun repairSlot(slot: Int): Boolean = withContext(Dispatchers.IO) {
        val backupFile = getBackupFile(slot)
        val saveFile = getSaveFile(slot)
        
        if (!backupFile.exists()) {
            return@withContext false
        }
        
        try {
            if (saveFile.exists()) {
                saveFile.delete()
            }
            backupFile.copyTo(saveFile)
            
            slotCache.remove(slot)
            metadataCache.remove(slot)
            
            _events.emit(SaveEvent.SlotChanged(slot))
            
            Log.i(TAG, "Slot repaired from backup: slot=$slot")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Repair failed: slot=$slot", e)
            false
        }
    }
    
    fun getCurrentSlot(): Int = currentSlot
    
    fun setCurrentSlot(slot: Int) {
        if (slot in 0..MAX_SLOTS) {
            currentSlot = slot
        }
    }
    
    fun getRecommendedContext(data: SerializableSaveData): SerializationContext {
        val estimatedSize = estimateDataSize(data)
        return serializationEngine.getRecommendedContext(estimatedSize, DataType.HOT_DATA)
    }
    
    private fun getQuickSaveContext(): SerializationContext {
        return SerializationContext(
            format = SerializationFormat.PROTOBUF,
            compression = CompressionType.LZ4,
            compressThreshold = 512,
            includeChecksum = true
        )
    }
    
    private fun getAutoSaveContext(): SerializationContext {
        return SerializationContext(
            format = SerializationFormat.PROTOBUF,
            compression = CompressionType.ZSTD,
            compressThreshold = 256,
            includeChecksum = true
        )
    }
    
    private fun getSaveFile(slot: Int): File {
        return File(saveDir, "$SAVE_FILE_PREFIX$slot$SAVE_FILE_EXT")
    }
    
    private fun getBackupFile(slot: Int): File {
        return File(saveDir, "$SAVE_FILE_PREFIX$slot$SAVE_FILE_EXT$BACKUP_EXT")
    }
    
    private fun createBackup(slot: Int) {
        val saveFile = getSaveFile(slot)
        val backupFile = getBackupFile(slot)
        
        if (saveFile.exists()) {
            if (backupFile.exists()) {
                backupFile.delete()
            }
            saveFile.copyTo(backupFile)
        }
    }
    
    private fun updateSlotMetadata(slot: Int, data: SerializableSaveData, fileSize: Long) {
        val metadata = SerializableSaveSlot(
            slot = slot,
            name = "Slot $slot",
            timestamp = data.timestamp,
            gameYear = data.gameData.gameYear,
            gameMonth = data.gameData.gameMonth,
            sectName = data.gameData.sectName,
            discipleCount = data.disciples.size,
            spiritStones = data.gameData.spiritStones,
            customName = ""
        )
        
        metadataCache[slot] = metadata
        
        slotCache[slot] = SaveSlotInfo(
            slot = slot,
            exists = true,
            timestamp = data.timestamp,
            gameYear = data.gameData.gameYear,
            gameMonth = data.gameData.gameMonth,
            sectName = data.gameData.sectName,
            discipleCount = data.disciples.size,
            spiritStones = data.gameData.spiritStones,
            fileSize = fileSize,
            format = SerializationFormat.PROTOBUF
        )
    }
    
    private fun computeSlotInfo(slot: Int): SaveSlotInfo {
        val saveFile = getSaveFile(slot)
        
        if (!saveFile.exists()) {
            return SaveSlotInfo(slot = slot, exists = false)
        }
        
        return try {
            val rawData = saveFile.readBytes()
            val format = serializationEngine.detectFormat(rawData)
            
            val metadata = metadataCache[slot]
            
            if (metadata != null) {
                SaveSlotInfo(
                    slot = slot,
                    exists = true,
                    timestamp = metadata.timestamp,
                    gameYear = metadata.gameYear,
                    gameMonth = metadata.gameMonth,
                    sectName = metadata.sectName,
                    discipleCount = metadata.discipleCount,
                    spiritStones = metadata.spiritStones,
                    fileSize = saveFile.length(),
                    customName = metadata.customName,
                    format = format
                )
            } else {
                SaveSlotInfo(
                    slot = slot,
                    exists = true,
                    fileSize = saveFile.length(),
                    format = format
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compute slot info: slot=$slot", e)
            SaveSlotInfo(slot = slot, exists = true, fileSize = saveFile.length())
        }
    }
    
    private fun estimateDataSize(data: SerializableSaveData): Int {
        var size = 100
        size += data.disciples.size * 500
        size += data.equipment.size * 100
        size += data.manuals.size * 100
        size += data.pills.size * 50
        size += data.materials.size * 50
        size += data.herbs.size * 50
        size += data.battleLogs.size * 1000
        return size
    }
    
    private suspend fun saveMetadata() {
        val metadataFile = File(saveDir, METADATA_FILE)
        
        try {
            val metadata = MetadataFile(
                version = CURRENT_SAVE_VERSION,
                slots = metadataCache.toMap()
            )
            
            val result = serializationEngine.serialize(metadata, SerializationContext(
                format = SerializationFormat.PROTOBUF,
                compression = CompressionType.LZ4
            ), serializer<MetadataFile>())
            
            metadataFile.writeBytes(result.data)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save metadata", e)
        }
    }
    
    suspend fun loadMetadata() = withContext(Dispatchers.IO) {
        val metadataFile = File(saveDir, METADATA_FILE)
        val legacyFile = File(saveDir, METADATA_FILE_LEGACY)
        
        val fileToLoad = when {
            metadataFile.exists() -> metadataFile
            legacyFile.exists() -> legacyFile
            else -> return@withContext
        }
        
        try {
            val result = serializationEngine.deserialize<MetadataFile>(
                fileToLoad.readBytes(),
                SerializationContext(format = SerializationFormat.PROTOBUF),
                serializer<MetadataFile>()
            )
            
            if (result.isSuccess && result.data != null) {
                result.data.slots.forEach { (slot, meta) ->
                    metadataCache[slot] = meta
                }
                
                if (fileToLoad == legacyFile) {
                    saveMetadata()
                    legacyFile.delete()
                    Log.i(TAG, "Migrated metadata from $METADATA_FILE_LEGACY to $METADATA_FILE")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load metadata", e)
        }
    }
    
    fun getStorageStats(): StorageStats {
        var totalSize = 0L
        var slotCount = 0
        
        (0..MAX_SLOTS).forEach { slot ->
            val file = getSaveFile(slot)
            if (file.exists()) {
                totalSize += file.length()
                slotCount++
            }
        }
        
        return StorageStats(
            totalSlots = MAX_SLOTS + 1,
            usedSlots = slotCount,
            totalBytes = totalSize,
            averageSlotSize = if (slotCount > 0) totalSize / slotCount else 0
        )
    }
    
    fun clearCache() {
        slotCache.clear()
        metadataCache.clear()
    }
    
    fun dispose() {
        scope.cancel()
    }
}

data class StorageStats(
    val totalSlots: Int,
    val usedSlots: Int,
    val totalBytes: Long,
    val averageSlotSize: Long
)
