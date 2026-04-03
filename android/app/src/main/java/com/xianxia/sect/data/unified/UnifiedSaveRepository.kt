package com.xianxia.sect.data.unified

import android.content.Context
import android.util.Log
import com.xianxia.sect.core.model.*
import com.xianxia.sect.data.cache.GameDataCacheManager
import com.xianxia.sect.data.cache.UnifiedCacheConfig
import com.xianxia.sect.data.concurrent.SlotLockManager
import com.xianxia.sect.data.crypto.IntegrityValidator
import com.xianxia.sect.data.crypto.KeyFileSystemException
import com.xianxia.sect.data.crypto.KeyIntegrityException
import com.xianxia.sect.data.crypto.KeyPermissionException
import com.xianxia.sect.data.crypto.SaveCrypto
import com.xianxia.sect.data.crypto.SecureKeyManager
import com.xianxia.sect.data.crypto.VerificationResult
import com.xianxia.sect.data.local.GameDatabase
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.model.SaveSlot
import com.xianxia.sect.data.retry.RetryPolicy
import com.xianxia.sect.data.retry.RetryConfig
import com.xianxia.sect.data.retry.RetryResult
import com.xianxia.sect.data.transaction.RefactoredTransactionalSaveManager
import com.xianxia.sect.data.validation.StorageValidator
import com.xianxia.sect.data.wal.EnhancedTransactionalWAL
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
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
    val storageBytes: Long = 0,
    val storageUsagePercent: Float = 0f
)

class StorageQuotaExceededError(
    message: String,
    val currentUsage: Long,
    val budgetLimit: Long,
    val requestedBytes: Long
) : IllegalStateException(message)

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
    val signedPayload: com.xianxia.sect.data.crypto.SignedPayload? = null,
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
    private val lockManager: SlotLockManager,
    private val fileHandler: SaveFileHandler,
    private val backupManager: BackupManager,
    private val serializationHelper: SerializationHelper,
    private val metadataManager: MetadataManager
) : SaveRepository {
    
    companion object {
        private const val TAG = "UnifiedSaveRepository"
        private const val AUTO_SAVE_SLOT = 0
        private const val EMERGENCY_SLOT = -1
        private const val MAX_BATTLE_LOGS = 500
        private const val MAX_GAME_EVENTS = 1000
        private const val MAX_SAVE_SIZE = 50 * 1024 * 1024L
        private const val MAGIC_NEW_FORMAT = "XSAV"
        private const val MAGIC_LEGACY_GZIP = "GZIP"
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var emergencyTxnId: AtomicLong = AtomicLong(0)
    private val migratingSlots = ConcurrentHashMap<Int, Long>()

    private val saveCount = AtomicLong(0)
    private val loadCount = AtomicLong(0)
    private val cacheHits = AtomicLong(0)
    private val cacheMisses = AtomicLong(0)
    
    private val _currentSlot = MutableStateFlow(1)
    val currentSlot: StateFlow<Int> = _currentSlot.asStateFlow()
    
    init {
        metadataManager.loadAllMetadata(lockManager.getMaxSlots())
    }
    
    override suspend fun save(slot: Int, data: SaveData): SaveResult<SaveOperationStats> = withContext(Dispatchers.IO) {
        if (!lockManager.isValidSlot(slot)) {
            return@withContext SaveResult.failure(SaveError.INVALID_SLOT, "Invalid slot: $slot")
        }
        
        val cleanedData = cleanSaveData(data)
        
        if (!fileHandler.checkAvailableMemory()) {
            fileHandler.forceGcAndWait()
            if (!fileHandler.checkAvailableMemory()) {
                Log.e(TAG, "Insufficient memory for save operation")
                return@withContext SaveResult.failure(SaveError.OUT_OF_MEMORY, "Insufficient memory")
            }
        }

        val estimatedSize = data.toString().length.toLong() * 2L
        if (!fileHandler.checkTotalStorageBudget(estimatedSize)) {
            val result = fileHandler.enforceStorageQuota(estimatedSize, setOf(slot))

            if (!result.success && !fileHandler.checkTotalStorageBudget(estimatedSize)) {
                val currentUsage = fileHandler.calculateTotalStorageBytes()
                throw StorageQuotaExceededError(
                    "Storage quota exceeded: current=${currentUsage}, limit=${SaveFileHandler.TOTAL_STORAGE_BUDGET_BYTES}, requested=$estimatedSize",
                    currentUsage,
                    SaveFileHandler.TOTAL_STORAGE_BUDGET_BYTES,
                    estimatedSize
                )
            }

            if (result.deletedFiles.isNotEmpty()) {
                Log.w(TAG, "Freed ${result.freedBytes} bytes by deleting ${result.deletedFiles.size} old files")
            }
        }

        saveCount.incrementAndGet()
        
        return@withContext lockManager.withWriteLockSuspend(slot) {
            val retryConfig = RetryConfig(
                maxRetries = 3,
                initialDelayMs = 100L,
                maxDelayMs = 5000L,
                backoffMultiplier = 2.0,
                jitterFactor = 0.2,
                retryableErrors = setOf(OutOfMemoryError::class.java, java.io.IOException::class.java)
            )

            val result: RetryResult<SaveResult<SaveOperationStats>> = RetryPolicy.executeWithRetry(retryConfig) {
                val saveFile = fileHandler.getSaveFile(slot)
                
                val rawData = serializationHelper.serializeAndCompressSaveData(cleanedData.copy(timestamp = System.currentTimeMillis()))

                if (rawData.size > MAX_SAVE_SIZE) {
                    throw IllegalStateException("Save data exceeds maximum size: ${rawData.size} bytes")
                }

                if (!fileHandler.hasEnoughDiskSpace(saveFile, rawData.size.toLong())) {
                    throw IllegalStateException("Insufficient disk space")
                }

                val encryptedData = encryptData(rawData)

                val success = fileHandler.writeAtomically(
                    targetFile = saveFile,
                    data = encryptedData,
                    magicHeader = MAGIC_NEW_FORMAT.toByteArray()
                )

                if (!success) {
                    throw IllegalStateException("Failed to write save file atomically")
                }

                metadataManager.updateSlotMetadata(slot, cleanedData, encryptedData.size.toLong())
                updateCacheAfterSave(slot, cleanedData)

                Log.i(TAG, "Save completed for slot $slot: ${encryptedData.size} bytes (compressed from ${rawData.size} bytes)")
                SaveResult.success(SaveOperationStats(
                    bytesWritten = encryptedData.size.toLong(),
                    timeMs = 0,
                    wasEncrypted = true
                ))
            }

            when (result) {
                is RetryResult.Success -> result.data
                is RetryResult.Failure -> {
                    when (val error = result.lastError) {
                        is KeyIntegrityException -> {
                            Log.e(TAG, "Key integrity error during save for slot $slot", error)
                            SaveResult.failure(SaveError.KEY_DERIVATION_ERROR,
                                "Key error: ${error.message}. Try clearing app data or use key recovery.", error)
                        }
                        is KeyPermissionException -> {
                            Log.e(TAG, "Key permission error during save for slot $slot", error)
                            SaveResult.failure(SaveError.KEY_DERIVATION_ERROR,
                                "Permission denied: ${error.message}. Please check app permissions or reinstall.", error)
                        }
                        is KeyFileSystemException -> {
                            Log.e(TAG, "File system error during save for slot $slot", error)
                            SaveResult.failure(SaveError.IO_ERROR,
                                "File system error: ${error.message}. Please check storage space.", error)
                        }
                        is OutOfMemoryError -> {
                            fileHandler.forceGcAndWait()
                            SaveResult.failure(SaveError.OUT_OF_MEMORY,
                                "OOM after ${result.totalAttempts} retries", error)
                        }
                        is IllegalStateException -> {
                            if (error.message?.contains("maximum size") == true ||
                                error.message?.contains("disk space") == true ||
                                error.message?.contains("rename") == true) {
                                SaveResult.failure(SaveError.SAVE_FAILED, error.message ?: "Unknown error", error)
                            } else {
                                SaveResult.failure(SaveError.OUT_OF_MEMORY,
                                    "Operation failed after ${result.totalAttempts} attempts", error)
                            }
                        }
                        else -> {
                            Log.e(TAG, "Failed to save slot $slot after ${result.totalAttempts} attempts", error)
                            SaveResult.failure(SaveError.SAVE_FAILED, error.message ?: "Unknown error", error)
                        }
                    }
                }
            }
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
                val saveFile = fileHandler.getSaveFile(slot)
                val metaFile = fileHandler.getMetaFile(slot)
                
                val success = fileHandler.deleteFiles(saveFile, metaFile)
                
                backupManager.deleteBackupVersions(slot)
                invalidateCache(slot)
                metadataManager.removeFromCache(slot)
                
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
        return fileHandler.getSaveFile(slot).exists()
    }
    
    override suspend fun getSlotInfo(slot: Int): SlotMetadata? {
        if (!lockManager.isValidSlot(slot)) return null
        
        metadataManager.getFromCache(slot)?.let { return it }
        
        return metadataManager.loadSlotMetadata(slot)
    }
    
    override fun getSaveSlots(): List<SaveSlot> {
        return lockManager.withAllSlotsReadLock {
            (1..lockManager.getMaxSlots()).map { slot ->
                val meta = metadataManager.getFromCache(slot)
                val saveFile = fileHandler.getSaveFile(slot)
                
                if (meta != null && saveFile.exists()) {
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
                } else if (meta != null && !saveFile.exists()) {
                    metadataManager.removeFromCache(slot)
                    SaveSlot(slot, "", 0, 1, 1, "", 0, 0, true)
                } else if (saveFile.exists()) {
                    try {
                        val loadedMeta = metadataManager.loadSlotMetadata(slot)
                        if (loadedMeta != null) {
                            SaveSlot(
                                slot = slot,
                                name = "Save $slot",
                                timestamp = loadedMeta.timestamp,
                                gameYear = loadedMeta.gameYear,
                                gameMonth = loadedMeta.gameMonth,
                                sectName = loadedMeta.sectName,
                                discipleCount = loadedMeta.discipleCount,
                                spiritStones = loadedMeta.spiritStones,
                                isEmpty = false,
                                customName = loadedMeta.sectName
                            )
                        } else {
                            SaveSlot(
                                slot = slot,
                                name = "Save $slot",
                                timestamp = saveFile.lastModified(),
                                gameYear = 1,
                                gameMonth = 1,
                                sectName = "存档 $slot",
                                discipleCount = 0,
                                spiritStones = 0,
                                isEmpty = false,
                                customName = ""
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to load metadata for slot $slot", e)
                        SaveSlot(
                            slot = slot,
                            name = "Save $slot",
                            timestamp = saveFile.lastModified(),
                            gameYear = 1,
                            gameMonth = 1,
                            sectName = "存档 $slot (需修复)",
                            discipleCount = 0,
                            spiritStones = 0,
                            isEmpty = false,
                            customName = ""
                        )
                    }
                } else {
                    SaveSlot(slot, "", 0, 1, 1, "", 0, 0, true)
                }
            }
        }
    }
    
    override suspend fun createBackup(slot: Int): SaveResult<String> {
        return backupManager.createBackup(slot)
    }
    
    override suspend fun restoreFromBackup(slot: Int, backupId: String): SaveResult<SaveData> {
        return backupManager.restoreFromBackup(slot, backupId) { loadSlot -> loadFromFile(loadSlot) }
    }
    
    override suspend fun getBackupVersions(slot: Int): List<BackupInfo> {
        return backupManager.getBackupVersions(slot)
    }
    
    override suspend fun verifyIntegrity(slot: Int): IntegrityResult {
        // 使用StorageValidator进行槽位范围验证
        val slotValidation = StorageValidator.validateSlotRange(slot, lockManager.getMaxSlots())
        if (!slotValidation.isValid) {
            return IntegrityResult.Invalid(slotValidation.errors.map { it.message })
        }

        return lockManager.withReadLockSuspend(slot) {
            try {
                val saveFile = fileHandler.getSaveFile(slot)

                // 使用StorageValidator验证文件存在性
                val fileExistsResult = StorageValidator.validateFileExists(saveFile, "Save file")
                if (!fileExistsResult.isValid) {
                    return@withReadLockSuspend IntegrityResult.Invalid(fileExistsResult.errors.map { it.message })
                }

                val metaFile = fileHandler.getMetaFile(slot)
                val metaExistsResult = StorageValidator.validateFileExists(metaFile, "Meta file")
                if (!metaExistsResult.isValid) {
                    return@withReadLockSuspend IntegrityResult.Invalid(metaExistsResult.errors.map { it.message })
                }

                // 加载数据和元数据
                val data = loadFromFile(slot).getOrNull()
                if (data == null) {
                    return@withReadLockSuspend IntegrityResult.Invalid(listOf("Failed to load save data"))
                }

                val meta = metadataManager.loadSlotMetadata(slot)
                if (meta == null) {
                    return@withReadLockSuspend IntegrityResult.Invalid(listOf("Failed to load metadata"))
                }

                val key = SecureKeyManager.getOrCreateKey(context)

                // 使用StorageValidator进行签名/完整性验证
                if (meta.signedPayload != null) {
                    val signatureResult = StorageValidator.validateSignedData(data, meta.signedPayload, key)
                    when {
                        signatureResult.isValid -> IntegrityResult.Valid
                        signatureResult.errors.any { it.code.startsWith("SIGNATURE_TAMPERED") } ->
                            IntegrityResult.Tampered(signatureResult.errors.first { it.code.startsWith("SIGNATURE_TAMPERED") }.message)
                        signatureResult.errors.any { it.code.contains("EXPIRED") } ->
                            IntegrityResult.Invalid(listOf("Signature expired"))
                        else -> IntegrityResult.Invalid(signatureResult.errors.map { it.message })
                    }
                } else {
                    // 旧版checksum验证
                    if (meta.checksum.isEmpty()) {
                        Log.w(TAG, "Missing checksum for slot $slot, integrity cannot be verified")
                        return@withReadLockSuspend IntegrityResult.Invalid(listOf("Missing checksum - integrity cannot be verified"))
                    }

                    val currentChecksum = computeFullDataSignature(data, key)
                    if (meta.checksum != currentChecksum) {
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
        // 使用StorageValidator进行槽位范围验证
        val slotValidation = StorageValidator.validateSlotRange(slot, lockManager.getMaxSlots())
        if (!slotValidation.isValid) return null

        return lockManager.withReadLockSuspend(slot) {
            try {
                val saveFile = fileHandler.getSaveFile(slot)

                // 使用StorageValidator验证文件存在性
                val fileExistsResult = StorageValidator.validateFileExists(saveFile, "Save file")
                if (!fileExistsResult.isValid) return@withReadLockSuspend null

                val data = loadFromFile(slot).getOrNull() ?: return@withReadLockSuspend null
                val meta = metadataManager.loadSlotMetadata(slot) ?: return@withReadLockSuspend null
                val key = SecureKeyManager.getOrCreateKey(context)

                // 使用IntegrityValidator计算哈希（这些是纯计算函数，不属于验证逻辑重复）
                val currentDataHash = IntegrityValidator.computeFullDataSignature(data, key)
                val currentMerkleRoot = IntegrityValidator.computeMerkleRoot(data)

                // 使用StorageValidator进行元数据验证
                val metaValidation = StorageValidator.validateMetadata(meta)
                val errors = mutableListOf<String>()

                // 收集元数据验证中的错误
                errors.addAll(metaValidation.errors.map { it.message })

                var signatureValid = true
                var hashValid = true
                var merkleValid = true

                if (meta.signedPayload != null) {
                    // 使用StorageValidator进行签名验证
                    val signatureResult = StorageValidator.validateSignedData(data, meta.signedPayload, key)
                    signatureValid = signatureResult.isValid

                    if (!signatureValid) {
                        errors.add("Signature verification failed: ${signatureResult.errors.firstOrNull()?.message}")
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
        
        val backups = backupManager.getBackupVersions(slot)
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
            lockManager.withWriteLockSuspend(EMERGENCY_SLOT) {
                var txnId: Long = 0
                try {
                    val emergencyFile = fileHandler.getEmergencySaveFile()

                    val cleanedData = cleanSaveData(data)
                    val rawData = serializationHelper.serializeAndCompressSaveData(cleanedData.copy(timestamp = System.currentTimeMillis()))
                    val encryptedData = encryptData(rawData)

                    val txnResult = wal.beginTransaction(EMERGENCY_SLOT, com.xianxia.sect.data.wal.WALEntryType.DATA, null)
                    if (txnResult.isSuccess) {
                        txnId = txnResult.getOrNull() ?: 0
                        emergencyTxnId.set(txnId)
                    }

                    val success = fileHandler.writeAtomically(
                        targetFile = emergencyFile,
                        data = encryptedData,
                        magicHeader = MAGIC_NEW_FORMAT.toByteArray()
                    )

                    if (!success) {
                        if (txnId > 0) {
                            wal.abort(txnId)
                            emergencyTxnId.set(0)
                        }
                        return@withWriteLockSuspend SaveResult.failure(SaveError.SAVE_FAILED, "Emergency save failed")
                    }

                    if (txnId > 0) {
                        wal.commit(txnId)
                    }

                    updateCacheAfterSave(EMERGENCY_SLOT, cleanedData)

                    Log.i(TAG, "Emergency save completed with transaction $txnId")
                    SaveResult.success(SaveOperationStats(bytesWritten = encryptedData.size.toLong()))
                } catch (e: Exception) {
                    Log.e(TAG, "Emergency save failed", e)
                    if (txnId > 0) {
                        try {
                            wal.abort(txnId)
                            emergencyTxnId.set(0)
                        } catch (abortEx: Exception) {
                            Log.e(TAG, "Failed to abort emergency transaction", abortEx)
                        }
                    }
                    SaveResult.failure(SaveError.SAVE_FAILED, e.message ?: "Unknown error", e)
                }
            }
        }
    }
    
    fun hasEmergencySave(): Boolean = fileHandler.getEmergencySaveFile().exists()
    
    suspend fun loadEmergencySave(): SaveData? {
        val file = fileHandler.getEmergencySaveFile()
        if (!file.exists()) return null

        return lockManager.withReadLockSuspend(EMERGENCY_SLOT) {
            withContext(Dispatchers.IO) {
                try {
                    val fileData = file.readBytes() ?: return@withContext null

                    if (fileData.size >= MAGIC_NEW_FORMAT.length &&
                        String(fileData, 0, MAGIC_NEW_FORMAT.length) == MAGIC_NEW_FORMAT) {
                        val encryptedData = fileData.copyOfRange(MAGIC_NEW_FORMAT.length, fileData.size)
                        val rawData = decryptData(encryptedData)
                        if (rawData != null) {
                            val decompressed = serializationHelper.decompressData(rawData)
                            if (decompressed != null) {
                                return@withContext serializationHelper.deserializeSaveData(decompressed)
                            }
                        }
                    } else {
                        val rawData = decryptData(fileData)
                        if (rawData != null) {
                            return@withContext serializationHelper.deserializeSaveData(rawData)
                        }
                    }
                    null
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load emergency save", e)
                    null
                }
            }
        }
    }
    
    fun clearEmergencySave(): Boolean {
        val file = fileHandler.getEmergencySaveFile()
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
            storageBytes = fileHandler.calculateTotalStorageBytes(),
            storageUsagePercent = fileHandler.getStorageUsagePercent()
        )
    }
    
    private suspend fun loadFromFile(slot: Int): SaveResult<SaveData> {
        return withContext(Dispatchers.IO) {
            val migrationStartTime = migratingSlots[slot]
            if (migrationStartTime != null) {
                if (System.currentTimeMillis() - migrationStartTime < 30_000L) {
                    Log.w(TAG, "Slot $slot is currently being migrated, skipping")
                    return@withContext SaveResult.failure(SaveError.LOAD_FAILED, "Slot $slot is currently being migrated")
                }
            }

            try {
                val saveFile = fileHandler.getSaveFile(slot)
                if (!saveFile.exists()) {
                    return@withContext SaveResult.failure(SaveError.SLOT_EMPTY, "No save for slot $slot")
                }

                val fileData = saveFile.readBytes() ?: return@withContext SaveResult.failure(
                    SaveError.LOAD_FAILED,
                    "Failed to read save file"
                )

                if (fileData.size >= MAGIC_NEW_FORMAT.length &&
                    String(fileData, 0, MAGIC_NEW_FORMAT.length) == MAGIC_NEW_FORMAT) {
                    val encryptedData = fileData.copyOfRange(MAGIC_NEW_FORMAT.length, fileData.size)

                    val loadRetryConfig = RetryConfig(
                        maxRetries = 2,
                        initialDelayMs = 50L,
                        maxDelayMs = 2000L,
                        backoffMultiplier = 2.0,
                        jitterFactor = 0.2,
                        retryableErrors = setOf(OutOfMemoryError::class.java, java.io.IOException::class.java)
                    )

                    val decryptResult: RetryResult<SaveResult<SaveData>> = RetryPolicy.executeWithRetry(loadRetryConfig) {
                        val rawData = decryptData(encryptedData)

                        if (rawData == null) {
                            throw IllegalStateException("Decryption failed")
                        }

                        val decompressedData = serializationHelper.decompressData(rawData)
                        if (decompressedData == null) {
                            throw IllegalStateException("Decompression failed")
                        }

                        val data = serializationHelper.deserializeSaveData(decompressedData)
                        if (data == null) {
                            throw IllegalStateException("Data corrupted")
                        }

                        SaveResult.success(data)
                    }

                    when (decryptResult) {
                        is RetryResult.Success -> decryptResult.data
                        is RetryResult.Failure -> {
                            when (val error = decryptResult.lastError) {
                                is IllegalStateException -> {
                                    when (error.message) {
                                        "Decryption failed" -> SaveResult.failure(SaveError.DECRYPTION_ERROR, "Decryption failed")
                                        "Decompression failed" -> SaveResult.failure(SaveError.SLOT_CORRUPTED, "Decompression failed")
                                        "Data corrupted" -> SaveResult.failure(SaveError.SLOT_CORRUPTED, "Data corrupted")
                                        else -> SaveResult.failure(SaveError.LOAD_FAILED, error.message ?: "Unknown error", error)
                                    }
                                }
                                else -> {
                                    Log.e(TAG, "Failed to load slot $slot after ${decryptResult.totalAttempts} attempts", error)
                                    SaveResult.failure(SaveError.LOAD_FAILED, error.message ?: "Unknown error", error)
                                }
                            }
                        }
                    }
                } else {
                    val legacyData = serializationHelper.tryLoadLegacyFormat(fileData) { decryptData(it) }
                    if (legacyData != null) {
                        Log.i(TAG, "Migrated legacy save format for slot $slot")

                        migratingSlots.put(slot, System.currentTimeMillis())
                        try {
                            val migrationResult = save(slot, legacyData)
                            if (migrationResult.isFailure) {
                                Log.w(TAG, "Legacy migration failed for slot $slot", (migrationResult as? com.xianxia.sect.data.unified.SaveResult.Failure)?.cause)
                            }
                        } finally {
                            migratingSlots.remove(slot)
                        }

                        return@withContext SaveResult.success(legacyData)
                    }

                    SaveResult.failure(SaveError.SLOT_CORRUPTED, "Unknown save format")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to load slot $slot", e)
                SaveResult.failure(SaveError.LOAD_FAILED, e.message ?: "Unknown error", e)
            }
        }
    }
    
    private suspend fun loadFromCache(slot: Int): SaveData? {
        return try {
            cacheManager.getOrNull(com.xianxia.sect.data.cache.CacheKey(
                type = "save_data",
                slot = slot,
                id = slot.toString(),
                ttl = UnifiedCacheConfig.DEFAULT.hotDataTtlMs
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
                    ttl = UnifiedCacheConfig.DEFAULT.hotDataTtlMs
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
    
    private fun updateCacheAfterSave(slot: Int, data: SaveData) {
        try {
            cacheManager.putWithoutTracking(
                com.xianxia.sect.data.cache.CacheKey(
                    type = "save_data",
                    slot = slot,
                    id = slot.toString(),
                    ttl = UnifiedCacheConfig.DEFAULT.warmDataTtlMs
                ),
                data
            )
            
            cacheManager.putWithoutTracking(
                com.xianxia.sect.data.cache.CacheKey(
                    type = "game_data",
                    slot = slot,
                    id = "current",
                    ttl = UnifiedCacheConfig.DEFAULT.warmDataTtlMs
                ),
                data.gameData
            )
            
            Log.d(TAG, "Cache updated after save for slot $slot")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update cache after save for slot $slot", e)
            invalidateCache(slot)
        }
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
    
    private suspend fun encryptData(data: ByteArray): ByteArray {
        val key = SecureKeyManager.getOrCreateKey(context)
        return SaveCrypto.encryptAsync(data, key)
    }
    
    private suspend fun decryptData(data: ByteArray): ByteArray? {
        val key = SecureKeyManager.getOrCreateKey(context)
        return SaveCrypto.decryptAsync(data, key)
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
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    
    fun shutdown() {
        scope.cancel()
        Log.i(TAG, "UnifiedSaveRepository shutdown completed")
    }
}
