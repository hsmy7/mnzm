package com.xianxia.sect.data.unified

import android.content.Context
import android.util.Log
import com.xianxia.sect.core.model.*
import com.xianxia.sect.data.cache.GameDataCacheManager
import com.xianxia.sect.data.concurrent.SlotLockManager
import com.xianxia.sect.data.crypto.IntegrityValidator
import com.xianxia.sect.data.crypto.SignedPayload
import com.xianxia.sect.data.crypto.VerificationResult
import com.xianxia.sect.data.crypto.SaveCrypto
import com.xianxia.sect.data.crypto.SecureKeyManager
import com.xianxia.sect.data.integrity.MerkleTree
import com.xianxia.sect.data.local.GameDatabase
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.model.SaveSlot
import com.xianxia.sect.data.transaction.RefactoredTransactionalSaveManager
import com.xianxia.sect.data.wal.EnhancedTransactionalWAL
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.*
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import javax.inject.Singleton

interface SaveRepository {
    suspend fun save(slot: Int, data: SaveData): SaveResult<SaveOperationStats>
    suspend fun load(slot: Int): SaveResult<SaveData>
    suspend fun delete(slot: Int): SaveResult<Unit>
    suspend fun hasSave(slot: Int): Boolean
    suspend fun getSlotInfo(slot: Int): SlotMetadata?
    fun getSaveSlots(): List<SaveSlot>
    suspend fun createBackup(slot: Int): SaveResult<String>
    suspend fun restoreFromBackup(slot: Int, backupId: String): SaveResult<SaveData>
    suspend fun getBackupVersions(slot: Int): List<BackupInfo>
    suspend fun verifyIntegrity(slot: Int): IntegrityResult
    suspend fun repair(slot: Int): SaveResult<Unit>
    suspend fun getDetailedIntegrityReport(slot: Int): IntegrityReport?
}

data class BackupInfo(
    val id: String,
    val slot: Int,
    val timestamp: Long,
    val size: Long,
    val checksum: String
)

sealed class IntegrityResult {
    data object Valid : IntegrityResult()
    data class Invalid(val errors: List<String>) : IntegrityResult()
    data class Tampered(val reason: String) : IntegrityResult()
}

data class IntegrityReport(
    val slot: Int,
    val isValid: Boolean,
    val dataHash: String,
    val merkleRoot: String,
    val signatureValid: Boolean,
    val hashValid: Boolean,
    val merkleValid: Boolean,
    val errors: List<String> = emptyList()
)

data class RepositoryStats(
    val totalSaves: Long = 0,
    val totalLoads: Long = 0,
    val cacheHitRate: Float = 0f,
    val activeTransactions: Int = 0,
    val storageBytes: Long = 0
)

data class SlotMetadata(
    val slot: Int,
    val timestamp: Long,
    val gameYear: Int,
    val gameMonth: Int,
    val sectName: String,
    val discipleCount: Int,
    val spiritStones: Long,
    val fileSize: Long,
    val customName: String = "",
    val checksum: String = "",
    val version: String = "",
    val signedPayload: SignedPayload? = null,
    val dataHash: String = "",
    val merkleRoot: String = ""
)

@Singleton
class UnifiedSaveRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: GameDatabase,
    private val cacheManager: GameDataCacheManager,
    private val transactionalSaveManager: RefactoredTransactionalSaveManager,
    private val wal: EnhancedTransactionalWAL,
    private val lockManager: SlotLockManager
) : SaveRepository {
    
    companion object {
        private const val TAG = "UnifiedSaveRepository"
        private const val SAVE_DIR = "saves"
        private const val BACKUP_DIR = "backups"
        private const val FILE_EXTENSION = ".sav"
        private const val META_EXTENSION = ".meta"
        private const val BACKUP_EXTENSION = ".bak"
        private const val MAX_BACKUP_VERSIONS = 5
        private const val AUTO_SAVE_SLOT = 0
        private const val EMERGENCY_SLOT = -1
        
        private const val MAX_BATTLE_LOGS = 500
        private const val MAX_GAME_EVENTS = 1000
        private const val MAX_SAVE_SIZE = 50 * 1024 * 1024L
        private const val MIN_MEMORY_RATIO = 0.15
        private const val MAX_RETRY_COUNT = 2
        private const val RETRY_DELAY_MS = 100L
        private const val GZIP_BUFFER_SIZE = 64 * 1024
        
        private const val MAGIC_NEW_FORMAT = "XSAV"
        private const val MAGIC_LEGACY_GZIP = "GZIP"
    }
    
    private val gson = com.xianxia.sect.data.GsonConfig.createGson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val saveCount = AtomicLong(0)
    private val loadCount = AtomicLong(0)
    private val cacheHits = AtomicLong(0)
    private val cacheMisses = AtomicLong(0)
    
    private val _currentSlot = MutableStateFlow(1)
    val currentSlot: StateFlow<Int> = _currentSlot.asStateFlow()
    
    private val slotMetadataCache = ConcurrentHashMap<Int, SlotMetadata>()
    
    private val saveDir: File by lazy {
        File(context.filesDir, SAVE_DIR).apply { if (!exists()) mkdirs() }
    }
    
    private val backupDir: File by lazy {
        File(context.filesDir, BACKUP_DIR).apply { if (!exists()) mkdirs() }
    }
    
    private val integrityValidator = MerkleTree()
    
    init {
        loadAllMetadata()
    }
    
    override suspend fun save(slot: Int, data: SaveData): SaveResult<SaveOperationStats> {
        if (!lockManager.isValidSlot(slot)) {
            return SaveResult.failure(SaveError.INVALID_SLOT, "Invalid slot: $slot")
        }
        
        val cleanedData = cleanSaveData(data)
        
        if (!checkAvailableMemory()) {
            forceGcAndWait()
            if (!checkAvailableMemory()) {
                Log.e(TAG, "Insufficient memory for save operation")
                return SaveResult.failure(SaveError.OUT_OF_MEMORY, "Insufficient memory")
            }
        }
        
        saveCount.incrementAndGet()
        
        return lockManager.withWriteLockSuspend(slot) {
            var retryCount = 0
            var lastError: Throwable? = null
            
            while (retryCount <= MAX_RETRY_COUNT) {
                try {
                    val saveFile = getSaveFile(slot)
                    val tempFile = File(saveFile.parent, "${saveFile.name}.tmp")
                    val backupFile = File(saveFile.parent, "${saveFile.name}.bak")
                    
                    val rawData = serializeAndCompressSaveData(cleanedData.copy(timestamp = System.currentTimeMillis()))
                    
                    if (rawData.size > MAX_SAVE_SIZE) {
                        return@withWriteLockSuspend SaveResult.failure(
                            SaveError.SAVE_FAILED, 
                            "Save data exceeds maximum size: ${rawData.size} bytes"
                        )
                    }
                    
                    if (saveFile.parentFile != null && saveFile.parentFile!!.freeSpace < rawData.size * 2L) {
                        return@withWriteLockSuspend SaveResult.failure(
                            SaveError.SAVE_FAILED, 
                            "Insufficient disk space"
                        )
                    }
                    
                    val encryptedData = encryptData(rawData)
                    
                    FileOutputStream(tempFile).use { fos ->
                        fos.write(MAGIC_NEW_FORMAT.toByteArray())
                        fos.write(encryptedData)
                        fos.fd.sync()
                    }
                    
                    if (saveFile.exists()) {
                        if (!saveFile.renameTo(backupFile)) {
                            Log.w(TAG, "Failed to create backup before save for slot $slot")
                        }
                    }
                    
                    if (!tempFile.renameTo(saveFile)) {
                        if (backupFile.exists()) {
                            backupFile.renameTo(saveFile)
                        }
                        tempFile.delete()
                        return@withWriteLockSuspend SaveResult.failure(SaveError.SAVE_FAILED, "Failed to rename temp file")
                    }
                    
                    if (backupFile.exists()) {
                        backupFile.delete()
                    }
                    
                    updateSlotMetadata(slot, cleanedData, encryptedData.size.toLong())
                    invalidateCache(slot)
                    
                    Log.i(TAG, "Save completed for slot $slot: ${encryptedData.size} bytes (compressed from ${rawData.size} bytes)")
                    return@withWriteLockSuspend SaveResult.success(SaveOperationStats(
                        bytesWritten = encryptedData.size.toLong(),
                        timeMs = 0,
                        wasEncrypted = true
                    ))
                    
                } catch (e: OutOfMemoryError) {
                    lastError = e
                    retryCount++
                    Log.w(TAG, "OOM during save (attempt $retryCount/$MAX_RETRY_COUNT), slot=$slot")
                    forceGcAndWait()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save slot $slot", e)
                    return@withWriteLockSuspend SaveResult.failure(SaveError.SAVE_FAILED, e.message ?: "Unknown error", e)
                }
            }
            
            SaveResult.failure(SaveError.OUT_OF_MEMORY, "OOM after $MAX_RETRY_COUNT retries", lastError)
        }
    }
    
    override suspend fun load(slot: Int): SaveResult<SaveData> {
        if (!lockManager.isValidSlot(slot)) {
            return SaveResult.failure(SaveError.INVALID_SLOT, "Invalid slot: $slot")
        }
        
        loadCount.incrementAndGet()
        
        val cached = loadFromCache(slot)
        if (cached != null) {
            cacheHits.incrementAndGet()
            return SaveResult.success(cached)
        }
        
        cacheMisses.incrementAndGet()
        
        return lockManager.withReadLockSuspend(slot) {
            loadFromFile(slot)
        }.onSuccess { data ->
            updateCache(slot, data)
        }
    }
    
    override suspend fun delete(slot: Int): SaveResult<Unit> {
        if (!lockManager.isValidSlot(slot)) {
            return SaveResult.failure(SaveError.INVALID_SLOT, "Invalid slot: $slot")
        }
        
        return lockManager.withWriteLockSuspend(slot) {
            try {
                val saveFile = getSaveFile(slot)
                val metaFile = getMetaFile(slot)
                
                var success = true
                
                if (saveFile.exists() && !saveFile.delete()) {
                    Log.w(TAG, "Failed to delete save file for slot $slot")
                    success = false
                }
                
                if (metaFile.exists() && !metaFile.delete()) {
                    Log.w(TAG, "Failed to delete meta file for slot $slot")
                    success = false
                }
                
                deleteBackupVersions(slot)
                invalidateCache(slot)
                slotMetadataCache.remove(slot)
                
                if (success) {
                    SaveResult.success(Unit)
                } else {
                    SaveResult.failure(SaveError.DELETE_FAILED, "Partial deletion for slot $slot")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete slot $slot", e)
                SaveResult.failure(SaveError.DELETE_FAILED, e.message ?: "Unknown error", e)
            }
        }
    }
    
    override suspend fun hasSave(slot: Int): Boolean {
        if (!lockManager.isValidSlot(slot)) return false
        return getSaveFile(slot).exists()
    }
    
    override suspend fun getSlotInfo(slot: Int): SlotMetadata? {
        if (!lockManager.isValidSlot(slot)) return null
        
        slotMetadataCache[slot]?.let { return it }
        
        return loadSlotMetadata(slot)
    }
    
    override fun getSaveSlots(): List<SaveSlot> {
        return lockManager.withAllSlotsReadLock {
            (1..lockManager.getMaxSlots()).map { slot ->
                val meta = slotMetadataCache[slot]
                if (meta != null) {
                    SaveSlot(
                        slot = slot,
                        name = "Save $slot",
                        timestamp = meta.timestamp,
                        gameYear = meta.gameYear,
                        gameMonth = meta.gameMonth,
                        sectName = meta.sectName,
                        discipleCount = meta.discipleCount,
                        spiritStones = meta.spiritStones,
                        isEmpty = false,
                        customName = meta.sectName
                    )
                } else {
                    SaveSlot(slot, "", 0, 1, 1, "", 0, 0, true)
                }
            }
        }
    }
    
    override suspend fun createBackup(slot: Int): SaveResult<String> {
        if (!lockManager.isValidSlot(slot)) {
            return SaveResult.failure(SaveError.INVALID_SLOT, "Invalid slot: $slot")
        }
        
        return lockManager.withWriteLockSuspend(slot) {
            try {
                val saveFile = getSaveFile(slot)
                if (!saveFile.exists()) {
                    return@withWriteLockSuspend SaveResult.failure(SaveError.SLOT_EMPTY, "No save data for slot $slot")
                }
                
                rotateBackupVersions(slot)
                
                val backupFile = getBackupFile(slot, 1)
                val tempBackup = File(backupFile.parent, "${backupFile.name}.tmp")
                
                saveFile.copyTo(tempBackup, overwrite = true)
                
                if (!tempBackup.renameTo(backupFile)) {
                    tempBackup.delete()
                    return@withWriteLockSuspend SaveResult.failure(SaveError.BACKUP_FAILED, "Failed to create backup")
                }
                
                val backupId = "${slot}_${System.currentTimeMillis()}"
                Log.i(TAG, "Created backup for slot $slot: $backupId")
                
                SaveResult.success(backupId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create backup for slot $slot", e)
                SaveResult.failure(SaveError.BACKUP_FAILED, e.message ?: "Unknown error", e)
            }
        }
    }
    
    override suspend fun restoreFromBackup(slot: Int, backupId: String): SaveResult<SaveData> {
        if (!lockManager.isValidSlot(slot)) {
            return SaveResult.failure(SaveError.INVALID_SLOT, "Invalid slot: $slot")
        }
        
        return lockManager.withWriteLockSuspend(slot) {
            try {
                val version = extractBackupVersion(backupId) ?: 1
                val backupFile = getBackupFile(slot, version)
                
                if (!backupFile.exists()) {
                    return@withWriteLockSuspend SaveResult.failure(SaveError.SLOT_EMPTY, "Backup not found: $backupId")
                }
                
                val saveFile = getSaveFile(slot)
                val tempFile = File(saveFile.parent, "${saveFile.name}.restore_tmp")
                
                backupFile.copyTo(tempFile, overwrite = true)
                
                if (saveFile.exists()) {
                    createBackup(slot)
                    saveFile.delete()
                }
                
                if (!tempFile.renameTo(saveFile)) {
                    tempFile.delete()
                    return@withWriteLockSuspend SaveResult.failure(SaveError.RESTORE_FAILED, "Failed to restore backup")
                }
                
                loadFromFile(slot)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore backup for slot $slot", e)
                SaveResult.failure(SaveError.RESTORE_FAILED, e.message ?: "Unknown error", e)
            }
        }
    }
    
    override suspend fun getBackupVersions(slot: Int): List<BackupInfo> {
        if (!lockManager.isValidSlot(slot)) return emptyList()
        
        return (1..MAX_BACKUP_VERSIONS).mapNotNull { version ->
            val backupFile = getBackupFile(slot, version)
            if (backupFile.exists()) {
                BackupInfo(
                    id = "${slot}_${version}_${backupFile.lastModified()}",
                    slot = slot,
                    timestamp = backupFile.lastModified(),
                    size = backupFile.length(),
                    checksum = computeFileChecksum(backupFile)
                )
            } else null
        }.sortedByDescending { it.timestamp }
    }
    
    override suspend fun verifyIntegrity(slot: Int): IntegrityResult {
        if (!lockManager.isValidSlot(slot)) {
            return IntegrityResult.Invalid(listOf("Invalid slot: $slot"))
        }
        
        return lockManager.withReadLockSuspend(slot) {
            try {
                val saveFile = getSaveFile(slot)
                if (!saveFile.exists()) {
                    return@withReadLockSuspend IntegrityResult.Invalid(listOf("Save file not found"))
                }
                
                val metaFile = getMetaFile(slot)
                if (!metaFile.exists()) {
                    return@withReadLockSuspend IntegrityResult.Invalid(listOf("Meta file missing"))
                }
                
                val data = loadFromFile(slot).getOrNull()
                if (data == null) {
                    return@withReadLockSuspend IntegrityResult.Invalid(listOf("Failed to load save data"))
                }
                
                val meta = loadSlotMetadata(slot)
                if (meta == null) {
                    return@withReadLockSuspend IntegrityResult.Invalid(listOf("Failed to load metadata"))
                }
                
                val key = SecureKeyManager.getOrCreateKey(context)
                
                if (meta.signedPayload != null) {
                    val verificationResult = IntegrityValidator.verifySignedPayload(
                        data = data,
                        payload = meta.signedPayload,
                        key = key
                    )
                    
                    when (verificationResult) {
                        is VerificationResult.Valid -> IntegrityResult.Valid
                        is VerificationResult.Tampered -> IntegrityResult.Tampered(verificationResult.reason)
                        is VerificationResult.Invalid -> IntegrityResult.Invalid(listOf(verificationResult.reason))
                        is VerificationResult.Expired -> IntegrityResult.Invalid(
                            listOf("Signature expired at ${verificationResult.signedAt}")
                        )
                    }
                } else {
                    val currentChecksum = computeFullDataSignature(data, key)
                    if (meta.checksum.isNotEmpty() && meta.checksum != currentChecksum) {
                        val currentLegacyChecksum = computeLegacyDataChecksum(data)
                        if (meta.checksum != currentLegacyChecksum) {
                            Log.w(TAG, "Checksum mismatch for slot $slot")
                            return@withReadLockSuspend IntegrityResult.Tampered("Checksum mismatch")
                        }
                    }
                    
                    IntegrityResult.Valid
                }
            } catch (e: Exception) {
                Log.e(TAG, "Integrity verification failed for slot $slot", e)
                IntegrityResult.Invalid(listOf(e.message ?: "Unknown error"))
            }
        }
    }
    
    override suspend fun getDetailedIntegrityReport(slot: Int): IntegrityReport? {
        if (!lockManager.isValidSlot(slot)) return null
        
        return lockManager.withReadLockSuspend(slot) {
            try {
                val saveFile = getSaveFile(slot)
                if (!saveFile.exists()) return@withReadLockSuspend null
                
                val data = loadFromFile(slot).getOrNull() ?: return@withReadLockSuspend null
                val meta = loadSlotMetadata(slot) ?: return@withReadLockSuspend null
                val key = SecureKeyManager.getOrCreateKey(context)
                
                val currentDataHash = IntegrityValidator.computeFullDataSignature(data, key)
                val currentMerkleRoot = IntegrityValidator.computeMerkleRoot(data)
                
                val errors = mutableListOf<String>()
                var signatureValid = true
                var hashValid = true
                var merkleValid = true
                
                if (meta.signedPayload != null) {
                    val verificationResult = IntegrityValidator.verifySignedPayload(data, meta.signedPayload, key)
                    signatureValid = verificationResult.isValid
                    
                    if (!signatureValid) {
                        errors.add("Signature verification failed: ${verificationResult}")
                    }
                    
                    hashValid = currentDataHash == meta.signedPayload.dataHash
                    if (!hashValid) {
                        errors.add("Data hash mismatch")
                    }
                    
                    merkleValid = currentMerkleRoot == meta.signedPayload.merkleRoot
                    if (!merkleValid) {
                        errors.add("Merkle root mismatch")
                    }
                } else {
                    hashValid = meta.dataHash.isEmpty() || currentDataHash == meta.dataHash
                    if (!hashValid && meta.dataHash.isNotEmpty()) {
                        errors.add("Data hash mismatch")
                    }
                    
                    merkleValid = meta.merkleRoot.isEmpty() || currentMerkleRoot == meta.merkleRoot
                    if (!merkleValid && meta.merkleRoot.isNotEmpty()) {
                        errors.add("Merkle root mismatch")
                    }
                }
                
                IntegrityReport(
                    slot = slot,
                    isValid = errors.isEmpty(),
                    dataHash = currentDataHash,
                    merkleRoot = currentMerkleRoot,
                    signatureValid = signatureValid,
                    hashValid = hashValid,
                    merkleValid = merkleValid,
                    errors = errors
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to generate integrity report for slot $slot", e)
                null
            }
        }
    }
    
    override suspend fun repair(slot: Int): SaveResult<Unit> {
        if (!lockManager.isValidSlot(slot)) {
            return SaveResult.failure(SaveError.INVALID_SLOT, "Invalid slot: $slot")
        }
        
        val integrity = verifyIntegrity(slot)
        if (integrity is IntegrityResult.Valid) {
            return SaveResult.success(Unit)
        }
        
        val backups = getBackupVersions(slot)
        if (backups.isEmpty()) {
            return SaveResult.failure(SaveError.SLOT_CORRUPTED, "No backup available for repair")
        }
        
        for (backup in backups) {
            val result = restoreFromBackup(slot, backup.id)
            if (result.isSuccess) {
                val restoredIntegrity = verifyIntegrity(slot)
                if (restoredIntegrity is IntegrityResult.Valid) {
                    Log.i(TAG, "Repaired slot $slot from backup ${backup.id}")
                    return SaveResult.success(Unit)
                }
            }
        }
        
        return SaveResult.failure(SaveError.RESTORE_FAILED, "All backup restore attempts failed")
    }
    
    suspend fun autoSave(data: SaveData): SaveResult<SaveOperationStats> {
        return save(AUTO_SAVE_SLOT, data)
    }
    
    suspend fun emergencySave(data: SaveData): SaveResult<SaveOperationStats> {
        return withContext(Dispatchers.IO) {
            try {
                val emergencyFile = getEmergencySaveFile()
                val tempFile = File(emergencyFile.parent, "${emergencyFile.name}.tmp")
                
                val cleanedData = cleanSaveData(data)
                val rawData = serializeAndCompressSaveData(cleanedData.copy(timestamp = System.currentTimeMillis()))
                val encryptedData = encryptData(rawData)
                
                FileOutputStream(tempFile).use { fos ->
                    fos.write(MAGIC_NEW_FORMAT.toByteArray())
                    fos.write(encryptedData)
                    fos.fd.sync()
                }
                
                if (emergencyFile.exists()) emergencyFile.delete()
                if (!tempFile.renameTo(emergencyFile)) {
                    tempFile.delete()
                    return@withContext SaveResult.failure(SaveError.SAVE_FAILED, "Emergency save failed")
                }
                
                Log.i(TAG, "Emergency save completed")
                SaveResult.success(SaveOperationStats(bytesWritten = encryptedData.size.toLong()))
            } catch (e: Exception) {
                Log.e(TAG, "Emergency save failed", e)
                SaveResult.failure(SaveError.SAVE_FAILED, e.message ?: "Unknown error", e)
            }
        }
    }
    
    fun hasEmergencySave(): Boolean = getEmergencySaveFile().exists()
    
    suspend fun loadEmergencySave(): SaveData? {
        val file = getEmergencySaveFile()
        if (!file.exists()) return null
        
        return withContext(Dispatchers.IO) {
            try {
                val fileData = file.readBytes()
                
                if (fileData.size >= MAGIC_NEW_FORMAT.length && 
                    String(fileData, 0, MAGIC_NEW_FORMAT.length) == MAGIC_NEW_FORMAT) {
                    val encryptedData = fileData.copyOfRange(MAGIC_NEW_FORMAT.length, fileData.size)
                    val rawData = decryptData(encryptedData)
                    if (rawData != null) {
                        val decompressed = decompressData(rawData)
                        if (decompressed != null) {
                            return@withContext deserializeSaveData(decompressed)
                        }
                    }
                } else {
                    val rawData = decryptData(fileData)
                    if (rawData != null) {
                        return@withContext deserializeSaveData(rawData)
                    }
                }
                null
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load emergency save", e)
                null
            }
        }
    }
    
    fun clearEmergencySave(): Boolean {
        val file = getEmergencySaveFile()
        return if (file.exists()) file.delete() else true
    }
    
    fun setCurrentSlot(slot: Int) {
        if (lockManager.isValidSlot(slot)) {
            _currentSlot.value = slot
        }
    }
    
    fun getStats(): RepositoryStats {
        val total = saveCount.get() + loadCount.get()
        val hits = cacheHits.get()
        val hitRate = if (total > 0) hits.toFloat() / total else 0f
        
        return RepositoryStats(
            totalSaves = saveCount.get(),
            totalLoads = loadCount.get(),
            cacheHitRate = hitRate,
            activeTransactions = transactionalSaveManager.getActiveTransactionCount(),
            storageBytes = calculateTotalStorageBytes()
        )
    }
    
    private suspend fun loadFromFile(slot: Int): SaveResult<SaveData> {
        return withContext(Dispatchers.IO) {
            try {
                val saveFile = getSaveFile(slot)
                if (!saveFile.exists()) {
                    return@withContext SaveResult.failure(SaveError.SLOT_EMPTY, "No save for slot $slot")
                }
                
                val fileData = saveFile.readBytes()
                
                if (fileData.size >= MAGIC_NEW_FORMAT.length && 
                    String(fileData, 0, MAGIC_NEW_FORMAT.length) == MAGIC_NEW_FORMAT) {
                    val encryptedData = fileData.copyOfRange(MAGIC_NEW_FORMAT.length, fileData.size)
                    val rawData = decryptData(encryptedData)
                    
                    if (rawData == null) {
                        return@withContext SaveResult.failure(SaveError.DECRYPTION_ERROR, "Decryption failed")
                    }
                    
                    val decompressedData = decompressData(rawData)
                    if (decompressedData == null) {
                        return@withContext SaveResult.failure(SaveError.SLOT_CORRUPTED, "Decompression failed")
                    }
                    
                    val data = deserializeSaveData(decompressedData)
                    if (data == null) {
                        return@withContext SaveResult.failure(SaveError.SLOT_CORRUPTED, "Data corrupted")
                    }
                    
                    return@withContext SaveResult.success(data)
                }
                
                val legacyData = tryLoadLegacyFormat(fileData)
                if (legacyData != null) {
                    Log.i(TAG, "Migrated legacy save format for slot $slot")
                    scope.launch {
                        save(slot, legacyData)
                    }
                    return@withContext SaveResult.success(legacyData)
                }
                
                SaveResult.failure(SaveError.SLOT_CORRUPTED, "Unknown save format")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load slot $slot", e)
                SaveResult.failure(SaveError.LOAD_FAILED, e.message ?: "Unknown error", e)
            }
        }
    }
    
    private fun tryLoadLegacyFormat(data: ByteArray): SaveData? {
        return try {
            if (isGzipCompressed(data)) {
                GZIPInputStream(ByteArrayInputStream(data), GZIP_BUFFER_SIZE).use { input ->
                    InputStreamReader(input, Charsets.UTF_8).use { reader ->
                        gson.fromJson(reader, SaveData::class.java)
                    }
                }
            } else {
                val decrypted = decryptData(data)
                if (decrypted != null) {
                    if (isGzipCompressed(decrypted)) {
                        GZIPInputStream(ByteArrayInputStream(decrypted), GZIP_BUFFER_SIZE).use { input ->
                            InputStreamReader(input, Charsets.UTF_8).use { reader ->
                                gson.fromJson(reader, SaveData::class.java)
                            }
                        }
                    } else {
                        gson.fromJson(String(decrypted, Charsets.UTF_8), SaveData::class.java)
                    }
                } else {
                    gson.fromJson(String(data, Charsets.UTF_8), SaveData::class.java)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load legacy format: ${e.message}")
            null
        }
    }
    
    private fun isGzipCompressed(data: ByteArray): Boolean {
        return data.size >= 2 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte()
    }
    
    private suspend fun loadFromCache(slot: Int): SaveData? {
        return try {
            cacheManager.getOrNull(com.xianxia.sect.data.cache.CacheKey(
                type = "save_data",
                slot = slot,
                id = slot.toString(),
                ttl = 60000L
            ))
        } catch (e: Exception) {
            null
        }
    }
    
    private fun updateCache(slot: Int, data: SaveData) {
        try {
            cacheManager.put(
                com.xianxia.sect.data.cache.CacheKey(
                    type = "save_data",
                    slot = slot,
                    id = slot.toString(),
                    ttl = 60000L
                ),
                data
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update cache for slot $slot", e)
        }
    }
    
    private fun invalidateCache(slot: Int) {
        try {
            cacheManager.remove(com.xianxia.sect.data.cache.CacheKey(
                type = "save_data",
                slot = slot,
                id = slot.toString()
            ))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to invalidate cache for slot $slot", e)
        }
    }
    
    private fun updateSlotMetadata(slot: Int, data: SaveData, fileSize: Long) {
        val key = SecureKeyManager.getOrCreateKey(context)
        val signedPayload = IntegrityValidator.createSignedPayload(
            data = data,
            key = key,
            metadata = mapOf(
                "slot" to slot.toString(),
                "version" to (data.version ?: "1.0")
            )
        )
        
        val metadata = SlotMetadata(
            slot = slot,
            timestamp = data.timestamp,
            gameYear = data.gameData.gameYear,
            gameMonth = data.gameData.gameMonth,
            sectName = data.gameData.sectName,
            discipleCount = data.disciples.count { it.isAlive },
            spiritStones = data.gameData.spiritStones,
            fileSize = fileSize,
            checksum = signedPayload.signature,
            version = data.version ?: "1.0",
            signedPayload = signedPayload,
            dataHash = signedPayload.dataHash,
            merkleRoot = signedPayload.merkleRoot
        )
        
        slotMetadataCache[slot] = metadata
        saveSlotMetadata(slot, metadata)
    }
    
    private fun saveSlotMetadata(slot: Int, metadata: SlotMetadata) {
        try {
            val metaFile = getMetaFile(slot)
            metaFile.writeText(gson.toJson(metadata))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save metadata for slot $slot", e)
        }
    }
    
    private fun loadSlotMetadata(slot: Int): SlotMetadata? {
        return try {
            val metaFile = getMetaFile(slot)
            if (!metaFile.exists()) return null
            
            val json = metaFile.readText()
            gson.fromJson(json, SlotMetadata::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load metadata for slot $slot", e)
            null
        }
    }
    
    private fun loadAllMetadata() {
        (1..lockManager.getMaxSlots()).forEach { slot ->
            loadSlotMetadata(slot)?.let { slotMetadataCache[slot] = it }
        }
    }
    
    private fun rotateBackupVersions(slot: Int) {
        for (version in MAX_BACKUP_VERSIONS downTo 2) {
            val current = getBackupFile(slot, version - 1)
            val next = getBackupFile(slot, version)
            if (current.exists()) {
                if (next.exists()) next.delete()
                current.renameTo(next)
            }
        }
    }
    
    private fun deleteBackupVersions(slot: Int) {
        (1..MAX_BACKUP_VERSIONS).forEach { version ->
            getBackupFile(slot, version).delete()
        }
    }
    
    private fun extractBackupVersion(backupId: String): Int? {
        val parts = backupId.split("_")
        return parts.getOrNull(1)?.toIntOrNull()
    }
    
    private fun cleanSaveData(data: SaveData): SaveData {
        val cleanedBattleLogs = if (data.battleLogs.size > MAX_BATTLE_LOGS) {
            data.battleLogs.sortedByDescending { it.timestamp }.take(MAX_BATTLE_LOGS)
        } else {
            data.battleLogs
        }
        
        val cleanedEvents = if (data.events.size > MAX_GAME_EVENTS) {
            data.events.sortedByDescending { it.timestamp }.take(MAX_GAME_EVENTS)
        } else {
            data.events
        }
        
        return data.copy(
            battleLogs = cleanedBattleLogs,
            events = cleanedEvents
        )
    }
    
    private fun serializeAndCompressSaveData(data: SaveData): ByteArray {
        val jsonBytes = gson.toJson(data).toByteArray(Charsets.UTF_8)
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos, GZIP_BUFFER_SIZE).use { gzip ->
            gzip.write(jsonBytes)
        }
        return baos.toByteArray()
    }
    
    private fun decompressData(data: ByteArray): ByteArray? {
        return try {
            if (isGzipCompressed(data)) {
                GZIPInputStream(ByteArrayInputStream(data), GZIP_BUFFER_SIZE).use { input ->
                    input.readBytes()
                }
            } else {
                data
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decompress data", e)
            null
        }
    }
    
    private fun checkAvailableMemory(): Boolean {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val availableMemory = maxMemory - usedMemory
        val ratio = availableMemory.toDouble() / maxMemory.toDouble()
        return ratio >= MIN_MEMORY_RATIO
    }
    
    private fun forceGcAndWait() {
        System.gc()
        try {
            Thread.sleep(RETRY_DELAY_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
    
    private fun serializeSaveData(data: SaveData): ByteArray {
        return gson.toJson(data).toByteArray(Charsets.UTF_8)
    }
    
    private fun deserializeSaveData(data: ByteArray): SaveData? {
        return try {
            gson.fromJson(String(data, Charsets.UTF_8), SaveData::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize save data", e)
            null
        }
    }
    
    private fun encryptData(data: ByteArray): ByteArray {
        val key = SecureKeyManager.getOrCreateKey(context)
        return SaveCrypto.encrypt(data, key)
    }
    
    private fun decryptData(data: ByteArray): ByteArray? {
        val key = SecureKeyManager.getOrCreateKey(context)
        return SaveCrypto.decrypt(data, key)
    }
    
    private fun computeFullDataSignature(data: SaveData, key: ByteArray): String {
        return IntegrityValidator.computeFullDataSignature(data, key)
    }
    
    private fun computeLegacyDataChecksum(data: SaveData): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(data.version.toByteArray())
        digest.update(data.timestamp.toString().toByteArray())
        digest.update(data.gameData.spiritStones.toString().toByteArray())
        digest.update(data.disciples.size.toString().toByteArray())
        return digest.digest().take(8).joinToString("") { "%02x".format(it) }
    }
    
    private fun computeFileChecksum(file: File): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(8192)
                var read: Int
                while (fis.read(buffer).also { read = it } > 0) {
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().take(8).joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }
    }
    
    private fun calculateTotalStorageBytes(): Long {
        var total = 0L
        saveDir.listFiles()?.forEach { total += it.length() }
        backupDir.listFiles()?.forEach { total += it.length() }
        return total
    }
    
    private fun getSaveFile(slot: Int): File = File(saveDir, "slot_$slot$FILE_EXTENSION")
    private fun getMetaFile(slot: Int): File = File(saveDir, "slot_$slot$META_EXTENSION")
    private fun getBackupFile(slot: Int, version: Int): File = 
        File(backupDir, "slot_$slot$BACKUP_EXTENSION.$version")
    private fun getEmergencySaveFile(): File = File(saveDir, "emergency$FILE_EXTENSION")
    
    fun shutdown() {
        scope.cancel()
        Log.i(TAG, "UnifiedSaveRepository shutdown completed")
    }
}
